package me.cortex.voxy.client.core.rendering.section.geometry;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.Consumer;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.util.BufferArena;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.HierarchicalBitSet;
import org.lwjgl.system.MemoryUtil;

public class BasicSectionGeometryManager extends AbstractSectionGeometryManager {
   public static final int SECTION_METADATA_SIZE = 32;
   private final GlBuffer sectionMetadataBuffer;
   private final BufferArena geometry;
   private final HierarchicalBitSet allocationSet;
   private final ObjectArrayList<BasicSectionGeometryManager.SectionMeta> sectionMetadata = new ObjectArrayList(32768);
   private final IntOpenHashSet invalidatedSectionIds = new IntOpenHashSet();

   public BasicSectionGeometryManager(int maxSectionCount, long geometryCapacity) {
      super(maxSectionCount, geometryCapacity);
      this.allocationSet = new HierarchicalBitSet(maxSectionCount);
      this.sectionMetadataBuffer = new GlBuffer(maxSectionCount * 32L);
      this.geometry = new BufferArena(geometryCapacity, 8);
   }

   @Override
   public int uploadReplaceSection(int oldId, BuiltSection sectionData) {
      if (sectionData.isEmpty()) {
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
         } else {
            BasicSectionGeometryManager.SectionMeta newMeta = this.createMeta(sectionData);
            sectionData.free();
            if (newId == this.sectionMetadata.size()) {
               this.sectionMetadata.add(newMeta);
            } else {
               this.sectionMetadata.set(newId, newMeta);
            }

            this.invalidatedSectionIds.add(newId);
            return newId;
         }
      }
   }

   @Override
   public void removeSection(int id) {
      if (!this.allocationSet.free(id)) {
         throw new IllegalStateException("Id was not already allocated. id: " + id);
      } else {
         BasicSectionGeometryManager.SectionMeta oldMetadata = (BasicSectionGeometryManager.SectionMeta)this.sectionMetadata.set(id, null);
         this.geometry.free(oldMetadata.geometryPtr);
         this.invalidatedSectionIds.add(id);
      }
   }

   private BasicSectionGeometryManager.SectionMeta createMeta(BuiltSection geometry) {
      int geometryPtr = (int)this.geometry.upload(geometry.geometryBuffer);
      if (geometryPtr == -1) {
         throw new IllegalStateException("Unable to upload section geometry as geometry buffer is full");
      } else {
         return new BasicSectionGeometryManager.SectionMeta(
            geometry.position, geometry.aabb, geometryPtr, (int)(geometry.geometryBuffer.size / 8L), geometry.offsets, geometry.childExistence
         );
      }
   }

   @Override
   public void tick() {
      if (!this.invalidatedSectionIds.isEmpty()) {
         this.invalidatedSectionIds.forEach(id -> {
            BasicSectionGeometryManager.SectionMeta meta = (BasicSectionGeometryManager.SectionMeta)this.sectionMetadata.get(id);
            long ptr = UploadStream.INSTANCE.upload(this.sectionMetadataBuffer, id * 32L, 32L);
            if (meta == null) {
               MemoryUtil.memSet(ptr, 0, 32L);
            } else {
               meta.writeMetadata(ptr);
            }
         });
         this.invalidatedSectionIds.clear();
         UploadStream.INSTANCE.commit();
      }
   }

   @Override
   public void free() {
      super.free();
      this.sectionMetadataBuffer.free();
      this.geometry.free();
   }

   @Override
   public void downloadAndRemove(int id, Consumer<BuiltSection> callback) {
      if (!this.allocationSet.free(id)) {
         throw new IllegalStateException("Id was not already allocated. id: " + id);
      } else {
         BasicSectionGeometryManager.SectionMeta oldMetadata = (BasicSectionGeometryManager.SectionMeta)this.sectionMetadata.set(id, null);
         this.geometry
            .downloadRemove(
               oldMetadata.geometryPtr,
               buffer -> callback.accept(
                  new BuiltSection(oldMetadata.position, oldMetadata.childExistence, oldMetadata.aabb, buffer.copy(), oldMetadata.offsets, null)
               )
            );
         this.invalidatedSectionIds.add(id);
      }
   }

   int getSectionCount() {
      return this.allocationSet.getCount();
   }

   long getGeometryUsed() {
      return this.geometry.getUsedBytes();
   }

   int getGeometryBufferId() {
      return this.geometry.id();
   }

   int getMetadataBufferId() {
      return this.sectionMetadataBuffer.id;
   }

   @Override
   public long getUsedCapacity() {
      return this.geometry.getUsedBytes();
   }

   private record SectionMeta(long position, int aabb, int geometryPtr, int itemCount, int[] offsets, byte childExistence) {
      public void writeMetadata(long ptr) {
         MemoryUtil.memPutInt(ptr, (int)(this.position >> 32));
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, (int)this.position);
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, this.aabb);
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, this.geometryPtr + this.offsets[0]);
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, this.offsets[1] - this.offsets[0] | this.offsets[2] - this.offsets[1] << 16);
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, this.offsets[3] - this.offsets[2] | this.offsets[4] - this.offsets[3] << 16);
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, this.offsets[5] - this.offsets[4] | this.offsets[6] - this.offsets[5] << 16);
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, this.offsets[7] - this.offsets[6] | this.itemCount - this.offsets[7] << 16);
         ptr += 4L;
      }
   }
}
