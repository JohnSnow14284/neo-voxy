package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntFunction;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongListIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import me.cortex.voxy.client.core.rendering.ISectionWatcher;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.AbstractSectionGeometryManager;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.HierarchicalBitSet;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

public class TestNodeManager {
   private static void fillInALl(TestNodeManager.TestBase test, long pos, Long2IntFunction converter) {
      test.request(pos);
      int ce = converter.get(pos);

      for (int i = 0; i < 8; i++) {
         if ((ce & 1 << i) != 0) {
            long p = makeChildPos(pos, i);
            test.meshUpdate(p, converter.get(p), 8);
         }
      }

      if (WorldEngine.getLevel(pos) != 1) {
         for (int ix = 0; ix < 8; ix++) {
            if ((ce & 1 << ix) != 0) {
               fillInALl(test, makeChildPos(pos, ix), converter);
            }
         }
      }
   }

   public static void main(String[] args) {
      Logger.INSERT_CLASS = false;
      int ITER_COUNT = 5000;
      int INNER_ITER_COUNT = 1000000;
      boolean GEO_REM = true;
      boolean LIMIT_REQUEST_SEC_ALLOCATION = true;
      AtomicInteger finished = new AtomicInteger();
      HashSet<List<StackTraceElement>> seenTraces = new HashSet<>();
      Logger.SHUTUP_INFO = true;
      Logger.SHUTUP = true;
      IntStream.range(0, ITER_COUNT).parallel().forEach(i -> {
         if (runTest(INNER_ITER_COUNT, i, seenTraces, GEO_REM, LIMIT_REQUEST_SEC_ALLOCATION)) {
            finished.incrementAndGet();
         }
      });
      System.out.println("Finished " + finished.get() + " iterations out of " + ITER_COUNT);
   }

   private static long rPos(Random r, LongList tops) {
      int lvl = r.nextInt(5);
      long top = tops.getLong(r.nextInt(tops.size()));
      if (lvl == 4) {
         return top;
      } else {
         int bound = 16 >> lvl;
         return WorldEngine.getWorldSectionId(
            lvl,
            r.nextInt(bound) + (WorldEngine.getX(top) << 4),
            r.nextInt(bound) + (WorldEngine.getY(top) << 4),
            r.nextInt(bound) + (WorldEngine.getZ(top) << 4)
         );
      }
   }

   private static boolean runTest(int ITERS, int testIdx, Set<List<StackTraceElement>> traces, boolean geoRemoval, boolean requestLimiter) {
      Random r = new Random(testIdx * 1234L);

      try {
         TestNodeManager.TestBase test = new TestNodeManager.TestBase();
         LongList tops = new LongArrayList();
         int R = 1;
         if (r.nextBoolean()) {
            R++;
            if (r.nextBoolean()) {
               R++;
               if (r.nextBoolean()) {
                  R++;
               }
            }
         }

         for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
               for (int y = -1; y <= 0; y++) {
                  tops.add(WorldEngine.getWorldSectionId(4, x, y, z));
               }
            }
         }

         LongListIterator var24 = tops.iterator();

         while (var24.hasNext()) {
            long p = (Long)var24.next();
            test.putTopPos(p);
            test.meshUpdate(p, -1, 18);
            fillInALl(test, p, a -> -1);
            test.printNodeChanges();
            test.verifyIntegrity();
         }

         for (int i = 0; i < ITERS; i++) {
            long pos = rPos(r, tops);
            int op = r.nextInt(5);
            int extra = r.nextInt(256);
            boolean geoAddOk = !requestLimiter || test.geometryManager.allocation.getLimit() - test.geometryManager.allocation.getCount() > 1000;
            boolean hasGeometry = r.nextBoolean();
            boolean addRemTLN = r.nextInt(64) == 0;
            boolean extraBool = r.nextBoolean();
            if (op == 0 && addRemTLN) {
               pos = WorldEngine.getWorldSectionId(4, r.nextInt(5) - 2, r.nextInt(2) - 1, r.nextInt(5) - 2);
               boolean cont = tops.contains(pos);
               if (cont && extraBool && tops.size() > 1) {
                  extraBool = true;
                  test.remTopPos(pos);
                  tops.rem(pos);
               } else if (!cont && geoAddOk) {
                  extraBool = false;
                  test.putTopPos(pos);
                  tops.add(pos);
               }
            } else if (op == 0 && geoAddOk) {
               test.request(pos);
            }

            if (op == 1) {
               test.childUpdate(pos, extra);
            }

            if (op == 2 && (!hasGeometry || geoAddOk)) {
               test.meshUpdate(pos, extra, hasGeometry ? 100 : 0);
            }

            if (op == 3 && geoRemoval) {
               test.nodeManager.removeNodeGeometry(pos);
            }

            test.printNodeChanges();
            test.verifyIntegrity();
         }

         var24 = tops.iterator();

         while (var24.hasNext()) {
            long top = (Long)var24.next();
            test.remTopPos(top);
         }

         test.printNodeChanges();
         test.verifyIntegrity();
         if (test.nodeManager.getCurrentMaxNodeId() != -1) {
            throw new IllegalStateException();
         } else if (!test.cleaner.active.isEmpty()) {
            throw new IllegalStateException();
         } else if (!test.watcher.updateTypes.isEmpty()) {
            throw new IllegalStateException();
         } else if (test.geometryManager.memoryInUse != 0L) {
            throw new IllegalStateException();
         } else {
            return true;
         }
      } catch (Exception var21) {
         Exception e = var21;
         ArrayList<StackTraceElement> trace = new ArrayList<>(List.of(var21.getStackTrace()));

         while (!trace.getLast().getMethodName().equals("runTest")) {
            trace.removeLast();
         }

         synchronized (traces) {
            if (traces.add(trace)) {
               e.printStackTrace();
            }

            return false;
         }
      }
   }

   public static void main3(String[] args) {
      Logger.INSERT_CLASS = false;
   }

   public static void main2(String[] args) {
      Logger.INSERT_CLASS = false;
      TestNodeManager.TestBase test = new TestNodeManager.TestBase();
      long POS_A = WorldEngine.getWorldSectionId(4, 0, 0, 0);
      test.putTopPos(POS_A);
      test.meshUpdate(POS_A, -1, 0);
      fillInALl(test, POS_A, a -> -1);
      test.printNodeChanges();
      Logger.info("\n\n");
      test.removeNodeGeometry(WorldEngine.getWorldSectionId(0, 0, 0, 0));
      test.printNodeChanges();
      test.removeNodeGeometry(WorldEngine.getWorldSectionId(3, 0, 0, 0));
      test.printNodeChanges();
      Logger.info("changing child existance");
      test.childUpdate(WorldEngine.getWorldSectionId(4, 0, 0, 0), 1);
      test.childUpdate(WorldEngine.getWorldSectionId(3, 0, 0, 0), 1);
      test.childUpdate(WorldEngine.getWorldSectionId(2, 0, 0, 0), 1);
      test.childUpdate(WorldEngine.getWorldSectionId(1, 0, 0, 0), 1);
      test.printNodeChanges();
   }

   public static void main1(String[] args) {
      Logger.INSERT_CLASS = false;
      Random r = new Random(1234L);
      Long2IntOpenHashMap aa = new Long2IntOpenHashMap();
      Long2IntFunction cc = p -> aa.computeIfAbsent(p, poss -> {
         int b = r.nextInt() & 0xFF;

         while (b == 0) {
            b = r.nextInt() & 0xFF;
         }

         return b;
      });
      TestNodeManager.TestBase test = new TestNodeManager.TestBase();
      long POS_A = WorldEngine.getWorldSectionId(4, 0, 0, 0);
      test.putTopPos(POS_A);
      test.meshUpdate(POS_A, cc.get(POS_A), 0);
      fillInALl(test, POS_A, cc);
      test.printNodeChanges();
      Logger.info("\n\n");
      ArrayList<Long> positions = new ArrayList<>(aa.keySet().longStream().filter(k -> WorldEngine.getLevel(k) != 0).sorted().mapToObj(Long::valueOf).toList());
      Collections.shuffle(positions, r);
      Logger.info("Removing", WorldEngine.pprintPos(positions.get(0)));
      test.removeNodeGeometry(positions.get(0));
      test.printNodeChanges();
   }

   private static int getChildIdx(long pos) {
      int x = WorldEngine.getX(pos);
      int y = WorldEngine.getY(pos);
      int z = WorldEngine.getZ(pos);
      return x & 1 | (y & 1) << 2 | (z & 1) << 1;
   }

   private static long makeChildPos(long basePos, int addin) {
      int lvl = WorldEngine.getLevel(basePos);
      if (lvl == 0) {
         throw new IllegalArgumentException("Cannot create a child lower than lod level 0");
      } else {
         return WorldEngine.getWorldSectionId(
            lvl - 1,
            WorldEngine.getX(basePos) << 1 | addin & 1,
            WorldEngine.getY(basePos) << 1 | addin >> 2 & 1,
            WorldEngine.getZ(basePos) << 1 | addin >> 1 & 1
         );
      }
   }

   private long makeParentPos(long pos) {
      int lvl = WorldEngine.getLevel(pos);
      if (lvl == 4) {
         throw new IllegalArgumentException("Cannot create a parent higher than LoD 4");
      } else {
         return WorldEngine.getWorldSectionId(lvl + 1, WorldEngine.getX(pos) >> 1, WorldEngine.getY(pos) >> 1, WorldEngine.getZ(pos) >> 1);
      }
   }

   private static class CleanerImp implements NodeManager.ICleaner {
      private final IntOpenHashSet active = new IntOpenHashSet();

      @Override
      public void alloc(int id) {
         if (!this.active.add(id)) {
            throw new IllegalStateException();
         }
      }

      @Override
      public void move(int from, int to) {
      }

      @Override
      public void free(int id) {
         if (!this.active.remove(id)) {
            throw new IllegalStateException();
         }
      }
   }

   private static final class MemoryGeometryManager extends AbstractSectionGeometryManager {
      private long memoryInUse = 0L;
      private final HierarchicalBitSet allocation;
      private final Int2ObjectOpenHashMap<TestNodeManager.MemoryGeometryManager.Entry> sections = new Int2ObjectOpenHashMap();

      public MemoryGeometryManager(int maxSections, long geometryCapacity) {
         super(maxSections, geometryCapacity);
         this.allocation = new HierarchicalBitSet(maxSections);
      }

      @Override
      public int uploadReplaceSection(int oldId, BuiltSection section) {
         if (section.isEmpty()) {
            throw new IllegalArgumentException();
         } else {
            if (oldId != -1) {
               this.removeSection(oldId);
            }

            int newId = this.allocation.allocateNext();
            if (newId == -1) {
               Logger.error("Allocator full: " + this.allocation.getCount() + " " + section, new Throwable());
               section.free();
               return -1;
            } else {
               TestNodeManager.MemoryGeometryManager.Entry entry = new TestNodeManager.MemoryGeometryManager.Entry(
                  section.position, section.geometryBuffer.size
               );
               TestNodeManager.MemoryGeometryManager.Entry old = (TestNodeManager.MemoryGeometryManager.Entry)this.sections.put(newId, entry);
               if (old != null) {
                  throw new IllegalStateException(oldId + "," + newId + " " + old + "," + entry);
               } else {
                  this.memoryInUse = this.memoryInUse + entry.size;
                  section.free();
                  Logger.info("Creating geometry with id", newId, "and size", entry.size, "at pos", WorldEngine.pprintPos(entry.pos));
                  return newId;
               }
            }
         }
      }

      @Override
      public void removeSection(int id) {
         if (!this.allocation.free(id)) {
            throw new IllegalStateException();
         } else {
            TestNodeManager.MemoryGeometryManager.Entry old = (TestNodeManager.MemoryGeometryManager.Entry)this.sections.remove(id);
            if (old == null) {
               throw new IllegalStateException();
            } else {
               this.memoryInUse = this.memoryInUse - old.size;
               Logger.info("Removing geometry with id", id, "it was at pos", WorldEngine.pprintPos(old.pos));
            }
         }
      }

      @Override
      public void downloadAndRemove(int id, Consumer<BuiltSection> callback) {
         this.removeSection(id);
      }

      @Override
      public long getUsedCapacity() {
         return this.memoryInUse;
      }

      private record Entry(long pos, long size) {
      }
   }

   private static class Node {
      private final long pos;
      private final TestNodeManager.Node[] children = new TestNodeManager.Node[8];
      private byte childExistenceMask;
      private int meshId;

      private Node(long pos) {
         this.pos = pos;
      }
   }

   private static class TestBase {
      public final TestNodeManager.MemoryGeometryManager geometryManager;
      public final NodeManager nodeManager;
      public final TestNodeManager.Watcher watcher = new TestNodeManager.Watcher();
      public final TestNodeManager.CleanerImp cleaner = new TestNodeManager.CleanerImp();

      public TestBase() {
         this.geometryManager = new TestNodeManager.MemoryGeometryManager(1048576, 1073741824L);
         this.nodeManager = new NodeManager(2097152, this.geometryManager, this.watcher);
         this.nodeManager.setClear(this.cleaner);
      }

      public void putTopPos(long pos) {
         this.nodeManager.insertTopLevelNode(pos);
      }

      public void meshUpdate(long pos, int childExistence, int geometrySize) {
         if (childExistence == -1) {
            childExistence = 255;
         }

         if (childExistence > 255) {
            throw new IllegalArgumentException();
         } else {
            MemoryBuffer buff = null;
            if (geometrySize != 0) {
               buff = new MemoryBuffer(geometrySize);
            }

            BuiltSection builtGeometry = new BuiltSection(pos, (byte)childExistence, -2, buff, null, null);
            this.nodeManager.processGeometryResult(builtGeometry);
         }
      }

      public void request(long pos) {
         this.nodeManager.processRequest(pos);
      }

      public void childUpdate(long pos, int existence) {
         if (existence == -1) {
            existence = 255;
         }

         if (existence > 255) {
            throw new IllegalArgumentException();
         } else {
            this.nodeManager.processChildChange(pos, (byte)existence);
         }
      }

      public boolean printNodeChanges() {
         MemoryBuffer changes = this.nodeManager._generateChangeList();
         if (changes == null) {
            return false;
         } else {
            for (int c = 0; c < changes.size / 20L; c++) {
               long ptr = changes.address + 20L * c;
               int nodeId = MemoryUtil.memGetInt(ptr);
               ptr += 4L;
               long pos = Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)) << 32;
               ptr += 4L;
               pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr));
               ptr += 4L;
               int z = MemoryUtil.memGetInt(ptr);
               ptr += 4L;
               int w = MemoryUtil.memGetInt(ptr);
               ptr += 4L;
               int childPtr = w & 16777215;
               int geometry = z & 16777215;
               short flags = 0;
               flags = (short)(flags | (short)(z >>> 24 & 0xFF));
               flags = (short)(flags | (short)((w >>> 24 & 0xFF) << 8));
               Logger.info("Node update, id:", nodeId, "pos:", WorldEngine.pprintPos(pos), "childPtr:", childPtr, "geometry:", geometry, "flags:", flags);
            }

            changes.free();
            return true;
         }
      }

      public void removeNodeGeometry(long pos) {
         this.nodeManager.removeNodeGeometry(pos);
      }

      public void verifyIntegrity() {
         this.nodeManager.verifyIntegrity(this.watcher.updateTypes.keySet(), this.cleaner.active);
      }

      public void remTopPos(long pos) {
         this.nodeManager.removeTopLevelNode(pos);
      }
   }

   private static class Watcher implements ISectionWatcher {
      private final Long2ByteOpenHashMap updateTypes = new Long2ByteOpenHashMap();

      @Override
      public boolean watch(long position, int types) {
         byte current = 0;
         boolean had = false;
         if (this.updateTypes.containsKey(position)) {
            current = this.updateTypes.get(position);
            had = true;
         }

         if (had && current == 0) {
            throw new IllegalStateException();
         } else {
            this.updateTypes.put(position, (byte)(current | types));
            byte delta = (byte)(types & ~current);
            Logger.info("Watching pos", WorldEngine.pprintPos(position), "with types", getPrettyTypes(types), "was", getPrettyTypes(current));
            return delta != 0;
         }
      }

      @Override
      public boolean unwatch(long position, int types) {
         if (!this.updateTypes.containsKey(position)) {
            throw new IllegalStateException("Pos not in map: " + WorldEngine.pprintPos(position));
         } else {
            byte current = this.updateTypes.get(position);
            byte newTypes = (byte)(current & ~types);
            if (newTypes == 0) {
               this.updateTypes.remove(position);
            } else {
               this.updateTypes.put(position, newTypes);
            }

            Logger.info(
               "UnWatching pos",
               WorldEngine.pprintPos(position),
               "removing types",
               getPrettyTypes(types),
               "was watching",
               getPrettyTypes(current),
               "new types",
               getPrettyTypes(newTypes)
            );
            return newTypes == 0;
         }
      }

      @Override
      public int get(long position) {
         return this.updateTypes.getOrDefault(position, (byte)0);
      }

      private static String[] getPrettyTypes(int msk) {
         if ((msk & -8) != 0) {
            throw new IllegalStateException();
         } else {
            String[] types = new String[Integer.bitCount(msk)];
            int i = 0;
            if ((msk & 1) != 0) {
               types[i++] = "BLOCK";
            }

            if ((msk & 2) != 0) {
               types[i++] = "CHILD";
            }

            if ((msk & 4) != 0) {
               types[i++] = "DONT_SAVE";
            }

            return types;
         }
      }
   }
}
