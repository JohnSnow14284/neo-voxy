package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public class NodeCleaner {
   private static final int SORTING_WORKER_SIZE = 64;
   private static final int WORK_PER_THREAD = 8;
   static final int OUTPUT_COUNT = 256;
   private final AutoBindingShader sorter = Shader.makeAuto(PrintfDebugUtil.PRINTF_processor)
      .define("WORK_SIZE", 64)
      .define("ELEMS_PER_THREAD", 8)
      .define("OUTPUT_SIZE", 256)
      .define("VISIBILITY_BUFFER_BINDING", 1)
      .define("OUTPUT_BUFFER_BINDING", 2)
      .define("NODE_DATA_BINDING", 3)
      .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/sort_visibility.comp")
      .compile();
   private final AutoBindingShader resultTransformer = Shader.makeAuto()
      .define("OUTPUT_SIZE", 256)
      .define("MIN_ID_BUFFER_BINDING", 0)
      .define("NODE_BUFFER_BINDING", 1)
      .define("OUTPUT_BUFFER_BINDING", 2)
      .define("VISIBILITY_BUFFER_BINDING", 3)
      .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/result_transformer.comp")
      .compile();
   private final AutoBindingShader batchClear = Shader.makeAuto()
      .define("VISIBILITY_BUFFER_BINDING", 0)
      .define("LIST_BUFFER_BINDING", 1)
      .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/batch_visibility_set.comp")
      .compile();
   final GlBuffer visibilityBuffer;
   private final GlBuffer outputBuffer = new GlBuffer(3072L);
   private final AsyncNodeManager nodeManager;
   int visibilityId = 0;

   public NodeCleaner(AsyncNodeManager nodeManager) {
      this.nodeManager = nodeManager;
      this.visibilityBuffer = new GlBuffer(nodeManager.maxNodeCount * 4L).zero();
      this.visibilityBuffer.fill(-1);
      this.batchClear.ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer);
      this.sorter.ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer).ssbo("OUTPUT_BUFFER_BINDING", this.outputBuffer);
   }

   public void tick(GlBuffer nodeDataBuffer) {
      this.visibilityId++;
      if (this.shouldCleanGeometry()) {
         this.outputBuffer.fill(this.nodeManager.maxNodeCount - 2);
         this.sorter.bind();
         GL43C.glBindBufferBase(37074, 3, nodeDataBuffer.id);
         GL42C.glMemoryBarrier(8192);
         GL43C.glDispatchCompute((this.nodeManager.getCurrentMaxNodeId() + 512 - 1) / 512, 1, 1);
         this.resultTransformer.bind();
         GL30C.glBindBufferRange(37074, 0, this.outputBuffer.id, 0L, 1024L);
         GL43C.glBindBufferBase(37074, 1, nodeDataBuffer.id);
         GL30C.glBindBufferRange(37074, 2, this.outputBuffer.id, 1024L, 2048L);
         GL43C.glBindBufferBase(37074, 3, this.visibilityBuffer.id);
         GL43C.glUniform1ui(0, this.visibilityId);
         GL42C.glMemoryBarrier(8192);
         GL43C.glDispatchCompute(1, 1, 1);
         GL42C.glMemoryBarrier(8192);
         DownloadStream.INSTANCE.download(this.outputBuffer, 1024L, 2048L, buffer -> this.nodeManager.submitRemoveBatch(buffer.copy()));
      }
   }

   private boolean shouldCleanGeometry() {
      long remaining = this.nodeManager.getGeometryCapacity() - this.nodeManager.getUsedGeometryCapacity();
      return remaining < 256000000L;
   }

   public void updateIds(IntOpenHashSet collection) {
      if (!collection.isEmpty()) {
         int count = collection.size();
         long addr = UploadStream.INSTANCE.rawUploadAddress(count * 4);
         long ptr = UploadStream.INSTANCE.getBaseAddress() + addr;

         for (IntIterator iter = collection.iterator(); iter.hasNext(); ptr += 4L) {
            MemoryUtil.memPutInt(ptr, iter.nextInt());
         }

         UploadStream.INSTANCE.commit();
         this.batchClear.bind();
         GL30C.glBindBufferRange(37074, 1, UploadStream.INSTANCE.getRawBufferId(), addr, UploadStream.alignUpAlloc(count * 4));
         GL43C.glUniform1ui(0, count);
         GL43C.glUniform1ui(1, this.visibilityId);
         GL42C.glMemoryBarrier(8192);
         GL43C.glDispatchCompute((count + 127) / 128, 1, 1);
         GL42C.glMemoryBarrier(8192);
      }
   }

   private void dumpDebugData() {
      int[] outData = new int[768];
      ARBDirectStateAccess.glGetNamedBufferSubData(this.outputBuffer.id, 0L, outData);

      for (int i = 0; i < 256; i++) {
         System.out.println(outData[i]);
      }

      int[] visData = new int[(int)(this.visibilityBuffer.size() / 4L)];
      ARBDirectStateAccess.glGetNamedBufferSubData(this.visibilityBuffer.id, 0L, visData);
      int a = 0;
   }

   public void free() {
      this.sorter.free();
      this.visibilityBuffer.free();
      this.outputBuffer.free();
      this.batchClear.free();
      this.resultTransformer.free();
   }
}
