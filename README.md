# voxy — NeoForge 1.21.1 fork

Unofficial NeoForge 1.21.1 fork of [Voxy](https://github.com/MCRcortex/voxy) by MCRcortex,
continuing the [neo-voxy](https://github.com/JohnSnow14284/neo-voxy) port lineage.
Maintained by NHblock714.

## Changes over neo-voxy

Rendering
- Optional camera-centred circular LOD handoff for opaque terrain; water retains the stable section boundary
- Shader-pack shadow casters use the same handoff band when Iris exposes a compatible terrain shadow pipeline
- Translucent water: straight-alpha bake (no alpha accumulation), SRC_ALPHA composite, ordered fluid meshing (walls first, surfaces last)
- Sea surface renders at the true sea height at every lod level instead of rounding up per ring
- Cross plants (grass, flowers, saplings) render as two crossed mid planes instead of a four-sided box
- Per-face-tile mip generation with an exact per-pixel tint mask; lightmap sampling parity with 0.2.14
- Underwater the LOD blit is skipped so the water fog occludes properly

Lighting
- Above-surface air carries sky light at all lod levels: light probe for sections without a DataLayer,
  sky-lit empty sections, self/neighbor light max for fluid faces, max-based sky mip
- Nearest-rounding terrain mips (no systematic +1 surface bias at lod rings)

Storage / lifecycle
- Re-ingest walks the whole mip chain, so stale or corrupt higher levels heal on revisit
- Instance shutdown flushes pending section saves before closing the storages
- Block states from removed mods fall back to air instead of crashing world join
- World engines close when leaving a world (worlds are deletable again)
- Dynamically registered mixins are dist-gated (dedicated servers boot clean)

Performance
- Uniform (single-material) world sections carry one value instead of a 32768-long array
- Section saves batch into one RocksDB write batch per engine rather than one write per section
- Create's distant-terrain shaders link at init, not on the first frame that draws them
- `/voxy debug capture` (frame timings, CPU stages, GPU markers, stall stacks) and `/voxy debug perf`

Integrations
- FakeSight-style extended chunk requests, throttled to 32 chunks while moving and expanded gradually while stationary
- Create: distant tracks, trains, contraptions and copycat blocks in LOD, with matching culling of
  the live render paths so nothing floats past the render distance — see `CREATE-COMPAT-NOTES.md`
- sable: contraption LOD rendering out to a configurable percentage of voxy's render distance
- EclipticSeasons: seasonal snow LOD (code adapted from the VoxyCompat addon by TeamTea, BSD-3-Clause)
- VSS (voxy server side): terrain streaming compatibility; `/voxy debug probe` for storage inspection
- In-game "Integrations" options page (Sodium 0.8 video settings)

> **WARNING:** Disable Voxy's circular LOD fade when the active shader pack already implements an
> LOD fade (for example Photon). Running both transitions can produce dither noise or a visible
> shadow boundary. The same warning is shown in red in the in-game option.

## Building

The integrations compile against mods that cannot be redistributed, so `libs/` is not part of this
repository and there is no CI build. Populate `libs/aero-spike/` yourself before building:

```
create-1.21.1-6.0.10.jar
ponder-neoforge-1.0.82+mc1.21.1.jar     (jarjar'd inside Create)
flywheel-neoforge-api-1.21.1-1.0.6.jar
flywheel-neoforge-impl-1.21.1-1.0.6.jar     (jarjar'd inside Create)
sable-neoforge-1.21.1-2.0.3.jar
sable-companion-common-1.21.1-1.6.0.jar     (jarjar'd inside sable)
dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.3.jar   (jarjar'd inside sable)
EclipticSeasons-1.21.1-neoforge-0.13.8.4.1.jar
bits_n_bobs-2.1.11-beta.jar
azimuth-1.4.1.jar
entityculling-neoforge-1.10.5-mc1.21.1.jar
```

Versions are pinned: several mixins bind symbol names in Create and Flywheel, so a different
Create/Flywheel will fail mixin apply rather than degrade. Then:

```
gradlew build
python tools/trim_jar.py
```

`trim_jar.py` produces the `-slim` jar: it keeps only the win64/linux64 rocksdb natives and strips
the lwjgl extension `module-info` so dedicated servers can boot (JPMS).

## License

Upstream Voxy is "All rights reserved — do not redistribute" (see LICENSE.md). This repository
exists as a GitHub fork for development and review; no built jars are distributed here.
All original Voxy credit belongs to [MCRcortex](https://github.com/MCRcortex).
