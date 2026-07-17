package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

public class SharedIndexBuffer {
   public static final int CUBE_INDEX_OFFSET = 786432;
   public static final SharedIndexBuffer INSTANCE = new SharedIndexBuffer();
   public static final SharedIndexBuffer INSTANCE_BYTE = new SharedIndexBuffer(true);
   public static final SharedIndexBuffer INSTANCE_BB_BYTE = new SharedIndexBuffer(true, true);
   private final GlBuffer indexBuffer;

   public SharedIndexBuffer() {
      this.indexBuffer = new GlBuffer(786468L);
      MemoryBuffer quadIndexBuff = generateQuadIndicesShort(16380);
      MemoryBuffer cubeBuff = generateCubeIndexBuffer();
      long ptr = UploadStream.INSTANCE.upload(this.indexBuffer, 0L, this.indexBuffer.size());
      quadIndexBuff.cpyTo(ptr);
      cubeBuff.cpyTo(786432L + ptr);
      quadIndexBuff.free();
      cubeBuff.free();
      UploadStream.INSTANCE.commit();
   }

   private SharedIndexBuffer(boolean type2) {
      this.indexBuffer = new GlBuffer(1572L);
      MemoryBuffer quadIndexBuff = generateQuadIndicesByte(63);
      MemoryBuffer cubeBuff = generateCubeIndexBuffer();
      long ptr = UploadStream.INSTANCE.upload(this.indexBuffer, 0L, this.indexBuffer.size());
      quadIndexBuff.cpyTo(ptr);
      cubeBuff.cpyTo(1536L + ptr);
      quadIndexBuff.free();
      cubeBuff.free();
   }

   private SharedIndexBuffer(boolean type2, boolean type3) {
      this.indexBuffer = new GlBuffer(1152L);
      MemoryBuffer cubeBuff = generateByteCubesIndexBuffer(32);
      cubeBuff.cpyTo(UploadStream.INSTANCE.upload(this.indexBuffer, 0L, this.indexBuffer.size()));
      UploadStream.INSTANCE.commit();
      cubeBuff.free();
   }

   private static MemoryBuffer generateCubeIndexBuffer() {
      MemoryBuffer buffer = new MemoryBuffer(36L);
      long ptr = buffer.address;
      MemoryUtil.memSet(ptr, 0, 36L);
      MemoryUtil.memPutByte(ptr++, (byte)0);
      MemoryUtil.memPutByte(ptr++, (byte)1);
      MemoryUtil.memPutByte(ptr++, (byte)2);
      MemoryUtil.memPutByte(ptr++, (byte)3);
      MemoryUtil.memPutByte(ptr++, (byte)2);
      MemoryUtil.memPutByte(ptr++, (byte)1);
      MemoryUtil.memPutByte(ptr++, (byte)6);
      MemoryUtil.memPutByte(ptr++, (byte)5);
      MemoryUtil.memPutByte(ptr++, (byte)4);
      MemoryUtil.memPutByte(ptr++, (byte)5);
      MemoryUtil.memPutByte(ptr++, (byte)6);
      MemoryUtil.memPutByte(ptr++, (byte)7);
      MemoryUtil.memPutByte(ptr++, (byte)0);
      MemoryUtil.memPutByte(ptr++, (byte)4);
      MemoryUtil.memPutByte(ptr++, (byte)1);
      MemoryUtil.memPutByte(ptr++, (byte)5);
      MemoryUtil.memPutByte(ptr++, (byte)1);
      MemoryUtil.memPutByte(ptr++, (byte)4);
      MemoryUtil.memPutByte(ptr++, (byte)3);
      MemoryUtil.memPutByte(ptr++, (byte)6);
      MemoryUtil.memPutByte(ptr++, (byte)2);
      MemoryUtil.memPutByte(ptr++, (byte)6);
      MemoryUtil.memPutByte(ptr++, (byte)3);
      MemoryUtil.memPutByte(ptr++, (byte)7);
      MemoryUtil.memPutByte(ptr++, (byte)2);
      MemoryUtil.memPutByte(ptr++, (byte)4);
      MemoryUtil.memPutByte(ptr++, (byte)0);
      MemoryUtil.memPutByte(ptr++, (byte)4);
      MemoryUtil.memPutByte(ptr++, (byte)2);
      MemoryUtil.memPutByte(ptr++, (byte)6);
      MemoryUtil.memPutByte(ptr++, (byte)1);
      MemoryUtil.memPutByte(ptr++, (byte)5);
      MemoryUtil.memPutByte(ptr++, (byte)3);
      MemoryUtil.memPutByte(ptr++, (byte)7);
      MemoryUtil.memPutByte(ptr++, (byte)3);
      MemoryUtil.memPutByte(ptr++, (byte)5);
      return buffer;
   }

   private static MemoryBuffer generateByteCubesIndexBuffer(int cnt) {
      MemoryBuffer buffer = new MemoryBuffer(cnt * 6L * 2L * 3L);
      long ptr = buffer.address;
      MemoryUtil.memSet(ptr, 0, buffer.size);

      for (int i = 0; i < cnt; i++) {
         int j = i * 8;
         MemoryUtil.memPutByte(ptr++, (byte)(0 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(1 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(2 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(3 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(2 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(1 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(6 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(5 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(4 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(5 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(6 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(7 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(0 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(4 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(1 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(5 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(1 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(4 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(3 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(6 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(2 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(6 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(3 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(7 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(2 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(4 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(0 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(4 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(2 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(6 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(1 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(5 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(3 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(7 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(3 + j));
         MemoryUtil.memPutByte(ptr++, (byte)(5 + j));
      }

      return buffer;
   }

   public static MemoryBuffer generateQuadIndicesByte(int quadCount) {
      if (quadCount * 4 >= 256) {
         throw new IllegalArgumentException("Quad count to large");
      } else {
         MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L);
         long ptr = buffer.address;

         for (int i = 0; i < quadCount * 4; i += 4) {
            MemoryUtil.memPutByte(ptr + 0L, (byte)(i + 1));
            MemoryUtil.memPutByte(ptr + 1L, (byte)(i + 2));
            MemoryUtil.memPutByte(ptr + 2L, (byte)(i + 0));
            MemoryUtil.memPutByte(ptr + 3L, (byte)(i + 1));
            MemoryUtil.memPutByte(ptr + 4L, (byte)(i + 3));
            MemoryUtil.memPutByte(ptr + 5L, (byte)(i + 2));
            ptr += 6L;
         }

         return buffer;
      }
   }

   public static MemoryBuffer generateQuadIndicesShort(int quadCount) {
      if (quadCount * 4 >= 65536) {
         throw new IllegalArgumentException("Quad count to large");
      } else {
         MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 2L);
         long ptr = buffer.address;

         for (int i = 0; i < quadCount * 4; i += 4) {
            MemoryUtil.memPutShort(ptr + 0L, (short)(i + 1));
            MemoryUtil.memPutShort(ptr + 2L, (short)(i + 2));
            MemoryUtil.memPutShort(ptr + 4L, (short)(i + 0));
            MemoryUtil.memPutShort(ptr + 6L, (short)(i + 1));
            MemoryUtil.memPutShort(ptr + 8L, (short)(i + 3));
            MemoryUtil.memPutShort(ptr + 10L, (short)(i + 2));
            ptr += 12L;
         }

         return buffer;
      }
   }

   public static MemoryBuffer generateQuadIndicesInt(int quadCount) {
      MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 2L);
      long ptr = buffer.address;

      for (int i = 0; i < quadCount * 4; i += 4) {
         MemoryUtil.memPutInt(ptr + 0L, i);
         MemoryUtil.memPutInt(ptr + 4L, i + 1);
         MemoryUtil.memPutInt(ptr + 8L, i + 2);
         MemoryUtil.memPutInt(ptr + 12L, i + 1);
         MemoryUtil.memPutInt(ptr + 16L, i + 3);
         MemoryUtil.memPutInt(ptr + 20L, i + 2);
         ptr += 24L;
      }

      return buffer;
   }

   public int id() {
      return this.indexBuffer.id;
   }
}
