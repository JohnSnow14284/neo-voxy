package me.cortex.voxy.client.core.rendering.section.geometry;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.Consumer;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.HierarchicalBitSet;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

public class BasicAsyncGeometryManager implements IGeometryManager {
   public static final int SECTION_METADATA_SIZE = 32;
   private static final long GEOMETRY_ELEMENT_SIZE = 8L;
   private final HierarchicalBitSet allocationSet;
   private final AllocationArena allocationHeap = new AllocationArena();
   private final ObjectArrayList<BasicAsyncGeometryManager.SectionMeta> sectionMetadata = new ObjectArrayList(32768);
   private final IntOpenHashSet invalidatedIds = new IntOpenHashSet(1024);
   private final Int2ObjectOpenHashMap<MemoryBuffer> heapUploads = new Int2ObjectOpenHashMap(1024);
   private final IntOpenHashSet heapRemoveUploads = new IntOpenHashSet(1024);
   private long usedCapacity = 0L;

   public BasicAsyncGeometryManager(int maxSectionCount, long geometryCapacity) {
      this.allocationSet = new HierarchicalBitSet(maxSectionCount);
      if (geometryCapacity % 8L != 0L) {
         throw new IllegalStateException();
      } else {
         this.allocationHeap.setLimit(geometryCapacity / 8L);
      }
   }

   @Override
   public int uploadSection(BuiltSection section) {
      return this.uploadReplaceSection(-1, section);
   }

   @Override
   public int uploadReplaceSection(int oldId, BuiltSection section) {
      if (section.isEmpty()) {
         throw new IllegalArgumentException("sectionData is empty, cannot upload nothing");
      } else {
         if (oldId != -1) {
            this.removeSection(oldId);
         }

         int newId = this.allocationSet.allocateNext();
         if (newId == -1) {
            throw new IllegalStateException("Tried adding section when section count is already at capacity");
         } else if (newId > this.sectionMetadata.size()) {
            throw new IllegalStateException("Size exceeds limits: " + newId + ", " + this.sectionMetadata.size() + ", " + this.allocationSet.getCount());
         } else if (newId < this.sectionMetadata.size() && this.sectionMetadata.get(newId) != null) {
            throw new IllegalStateException();
         } else {
            BasicAsyncGeometryManager.SectionMeta newMeta = this.createMeta(section);
            if (newId == this.sectionMetadata.size()) {
               this.sectionMetadata.add(newMeta);
            } else if (this.sectionMetadata.set(newId, newMeta) != null) {
               throw new IllegalStateException();
            }

            this.invalidatedIds.add(newId);
            return newId;
         }
      }
   }

   @Override
   public void removeSection(int id) {
      if (!this.allocationSet.free(id)) {
         throw new IllegalStateException("Id was not already allocated. id: " + id);
      } else {
         BasicAsyncGeometryManager.SectionMeta oldMetadata = (BasicAsyncGeometryManager.SectionMeta)this.sectionMetadata.set(id, null);
         int ptr = oldMetadata.geometryPtr;
         this.usedCapacity = this.usedCapacity - this.allocationHeap.free(Integer.toUnsignedLong(ptr));
         MemoryBuffer buf = (MemoryBuffer)this.heapUploads.remove(ptr);
         if (buf != null) {
            buf.free();
         }

         this.heapRemoveUploads.add(ptr);
         this.invalidatedIds.add(id);
      }
   }

   private BasicAsyncGeometryManager.SectionMeta createMeta(BuiltSection section) {
      if (section.geometryBuffer.size % 8L != 0L) {
         throw new IllegalStateException();
      } else {
         int size = (int)(section.geometryBuffer.size / 8L);
         int upsized = size + 127 & -128;
         int addr = (int)this.allocationHeap.alloc(upsized);
         if (addr == -1) {
            throw new IllegalStateException(
               "Geometry OOM. requested allocation size (in elements): "
                  + size
                  + ", Heap size at top remaining: "
                  + (this.allocationHeap.getLimit() - this.allocationHeap.getSize())
                  + ", used elements: "
                  + this.usedCapacity
            );
         } else {
            this.usedCapacity += upsized;
            if (this.heapUploads.put(addr, section.geometryBuffer) != null) {
               throw new IllegalStateException("Addr: " + addr);
            } else {
               this.heapRemoveUploads.remove(addr);
               return new BasicAsyncGeometryManager.SectionMeta(section.position, section.aabb, addr, size, section.offsets, section.childExistence);
            }
         }
      }
   }

   @Override
   public void downloadAndRemove(int id, Consumer<BuiltSection> callback) {
      throw new IllegalStateException("Not yet implemented");
   }

   public Int2ObjectOpenHashMap<MemoryBuffer> getUploads() {
      return this.heapUploads;
   }

   public IntOpenHashSet getHeapRemovals() {
      return this.heapRemoveUploads;
   }

   public int getSectionCount() {
      return this.allocationSet.getCount();
   }

   public long getGeometryUsedBytes() {
      return this.usedCapacity * 8L;
   }

   public IntOpenHashSet getUpdateIds() {
      return this.invalidatedIds;
   }

   public void writeMetadata(int sectionId, long ptr) {
      BasicAsyncGeometryManager.SectionMeta sec = (BasicAsyncGeometryManager.SectionMeta)this.sectionMetadata.get(sectionId);
      if (sec == null) {
         MemoryUtil.memSet(ptr, 0, 32L);
      } else {
         sec.writeMetadata(ptr);
      }
   }

   public void writeMetadataSplit(int sectionId, long ptrA, long ptrB) {
      BasicAsyncGeometryManager.SectionMeta sec = (BasicAsyncGeometryManager.SectionMeta)this.sectionMetadata.get(sectionId);
      if (sec == null) {
         MemoryUtil.memSet(ptrA, 0, 16L);
         MemoryUtil.memSet(ptrB, 0, 16L);
      } else {
         sec.writeMetadataSplitParts(ptrA, ptrB);
      }
   }

   private record SectionMeta(long position, int aabb, int geometryPtr, int itemCount, int[] offsets, byte childExistence) {
      public void writeMetadata(long ptr) {
         this.writeMetadataSplitParts(ptr, ptr + 16L);
      }

      public void writeMetadataSplitParts(long ptrA, long ptrB) {
         MemoryUtil.memPutInt(ptrA, (int)(this.position >> 32));
         ptrA += 4L;
         MemoryUtil.memPutInt(ptrA, (int)this.position);
         ptrA += 4L;
         MemoryUtil.memPutInt(ptrA, this.aabb);
         ptrA += 4L;
         MemoryUtil.memPutInt(ptrA, this.geometryPtr + this.offsets[0]);
         ptrA += 4L;
         MemoryUtil.memPutInt(ptrB, this.offsets[1] - this.offsets[0] | this.offsets[2] - this.offsets[1] << 16);
         ptrB += 4L;
         MemoryUtil.memPutInt(ptrB, this.offsets[3] - this.offsets[2] | this.offsets[4] - this.offsets[3] << 16);
         ptrB += 4L;
         MemoryUtil.memPutInt(ptrB, this.offsets[5] - this.offsets[4] | this.offsets[6] - this.offsets[5] << 16);
         ptrB += 4L;
         MemoryUtil.memPutInt(ptrB, this.offsets[7] - this.offsets[6] | this.itemCount - this.offsets[7] << 16);
         ptrB += 4L;
      }
   }
}
