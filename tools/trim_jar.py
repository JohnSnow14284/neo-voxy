"""Post-build surgery on the voxy jarjar libs. Run after `gradlew build`, before deploy.

Three transforms on nested (jarjar) jars:
  1. rocksdbjni: keep only win64.dll + linux64.so natives (drops ~60MB of unused platforms).
  2. sqlite-jdbc: same idea. It is not a storage backend - nothing in the tree implements one - it
     exists purely so DHImporter can read a DistantHorizons.sqlite, and that call site already
     degrades gracefully when the driver is missing. Almost all of its ~13MB is natives for
     platforms we never run: Android, FreeBSD, Linux-Musl, Mac, and a pile of non-x86_64 arches.
     Keep Windows/x86_64 + Linux/x86_64 so the importer still works where it is actually used.
  3. lwjgl-zstd / lwjgl-lmdb: strip their module-info so they become AUTOMATIC modules.
     They declare `requires org.lwjgl`; a dedicated server has no org.lwjgl module, so the JPMS
     module graph fails at startup (`FindException: Module org.lwjgl not found, required by
     org.lwjgl.zstd`). As automatic modules they have no `requires`, so the server resolves; the
     client still works (an automatic module reads org.lwjgl, which is present there).

Nested jarjar jars must stay STORED (uncompressed) so NeoForge can read them.
"""
import zipfile, io, os, glob, sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "libs")


def _find_src():
    """The built jar, found by pattern rather than named literally - a hardcoded filename here goes
    stale on every version bump and fails with nothing but a missing-file error."""
    candidates = [p for p in glob.glob(os.path.join(BASE, "neo-voxy-*.jar"))
                  if not p.endswith(("-slim.jar", "-sources.jar", "-javadoc.jar"))]
    if not candidates:
        sys.exit("no neo-voxy-*.jar in %s - run gradlew build first" % os.path.normpath(BASE))
    if len(candidates) > 1:
        sys.exit("several candidate jars in %s, clean the stale ones:\n  %s"
                 % (os.path.normpath(BASE), "\n  ".join(sorted(os.path.basename(c) for c in candidates))))
    return candidates[0]


SRC = _find_src()
DST = SRC[:-len(".jar")] + "-slim.jar"

ROCKS = "META-INF/jarjar/rocksdbjni-10.2.1.jar"
ROCKS_KEEP = {"librocksdbjni-win64.dll", "librocksdbjni-linux64.so"}

SQLITE = "META-INF/jarjar/sqlite-jdbc-3.49.1.0.jar"
SQLITE_NATIVE_PREFIX = "org/sqlite/native/"
SQLITE_KEEP_DIRS = ("org/sqlite/native/Windows/x86_64/", "org/sqlite/native/Linux/x86_64/")
DEMODULARIZE = [
    "META-INF/jarjar/lwjgl-zstd-3.3.3.jar",
    "META-INF/jarjar/lwjgl-lmdb-3.3.3.jar",
]
MODULE_INFO = "META-INF/versions/9/module-info.class"


def is_rocks_native(name):
    base = name.rsplit("/", 1)[-1]
    return base.startswith("librocksdbjni-") and base.endswith((".so", ".dll", ".jnilib"))


def filter_rocks(nested):
    buf = io.BytesIO()
    dropped, kept = [], []
    with zipfile.ZipFile(io.BytesIO(nested)) as nz, zipfile.ZipFile(buf, "w") as out:
        for e in nz.infolist():
            base = e.filename.rsplit("/", 1)[-1]
            if is_rocks_native(e.filename) and base not in ROCKS_KEEP:
                dropped.append(base); continue
            if is_rocks_native(e.filename):
                kept.append(base)
            out.writestr(e, nz.read(e.filename))   # ZipInfo preserves per-entry compression
    print("  rocksdb: kept %s, dropped %d" % (sorted(kept), len(dropped)))
    return buf.getvalue()


def filter_sqlite(nested):
    buf = io.BytesIO()
    dropped, kept = 0, []
    with zipfile.ZipFile(io.BytesIO(nested)) as nz, zipfile.ZipFile(buf, "w") as out:
        for e in nz.infolist():
            if e.filename.startswith(SQLITE_NATIVE_PREFIX) and not e.filename.endswith("/"):
                if not e.filename.startswith(SQLITE_KEEP_DIRS):
                    dropped += 1; continue
                kept.append(e.filename[len(SQLITE_NATIVE_PREFIX):])
            out.writestr(e, nz.read(e.filename))
    print("  sqlite: kept %s, dropped %d natives" % (sorted(kept), dropped))
    return buf.getvalue()


def demodularize(nested, label):
    buf = io.BytesIO()
    removed = 0
    with zipfile.ZipFile(io.BytesIO(nested)) as nz, zipfile.ZipFile(buf, "w") as out:
        for e in nz.infolist():
            if e.filename == MODULE_INFO:
                removed += 1; continue
            out.writestr(e, nz.read(e.filename))
    print("  %s: stripped module-info x%d -> automatic module" % (label, removed))
    return buf.getvalue()


with zipfile.ZipFile(SRC) as zin:
    transforms = {ROCKS: filter_rocks(zin.read(ROCKS)),
                  SQLITE: filter_sqlite(zin.read(SQLITE))}
    for nj in DEMODULARIZE:
        transforms[nj] = demodularize(zin.read(nj), nj.rsplit("/", 1)[-1])

    with zipfile.ZipFile(DST, "w") as zout:
        for e in zin.infolist():
            if e.filename in transforms:
                zi = zipfile.ZipInfo(e.filename, date_time=e.date_time)
                zi.compress_type = zipfile.ZIP_STORED   # jarjar nested jars must be STORED
                zi.external_attr = e.external_attr
                zout.writestr(zi, transforms[e.filename])
            else:
                # NeoForge opens jarjar entries as nested archives.  Keep every nested jar STORED,
                # including the ones which did not need content surgery; copying their original
                # compression method can leave a few dependencies DEFLATED and intermittently
                # unreadable by loaders which expect direct random access.
                if e.filename.startswith("META-INF/jarjar/") and e.filename.endswith(".jar"):
                    zi = zipfile.ZipInfo(e.filename, date_time=e.date_time)
                    zi.compress_type = zipfile.ZIP_STORED
                    zi.external_attr = e.external_attr
                    zout.writestr(zi, zin.read(e.filename))
                else:
                    zout.writestr(e, zin.read(e.filename))

print("outer jar: %.1f MB -> %.1f MB" % (os.path.getsize(SRC) / 1048576, os.path.getsize(DST) / 1048576))
