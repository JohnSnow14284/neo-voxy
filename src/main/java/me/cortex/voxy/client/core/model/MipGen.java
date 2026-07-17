package me.cortex.voxy.client.core.model;

import it.unimi.dsi.fastutil.bytes.ByteArrayFIFOQueue;
import java.util.Arrays;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

public class MipGen {
   private static final ThreadLocal<MipGen.Cache> CACHE = ThreadLocal.withInitial(MipGen.Cache::new);

   private static long getOffset(int bx, int by, int i) {
      bx += i & 15;
      by += i / 16;
      return bx + by * 16 * 3;
   }

   private static void solidify(long baseAddr, byte msk, short[] SCRATCH, ByteArrayFIFOQueue QUEUE) {
      for (int idx = 0; idx < 6; idx++) {
         if ((msk >> idx & 1) != 0) {
            int bx = (idx >> 1) * 16;
            int by = (idx & 1) * 16;
            long cAddr = baseAddr + (bx + by * 16 * 3) * 4L;
            Arrays.fill(SCRATCH, (short)-1);

            for (int y = 0; y < 16; y++) {
               for (int x = 0; x < 16; x++) {
                  int colour = MemoryUtil.memGetInt(cAddr + (x + y * 16 * 3) * 4);
                  if ((colour & 0xFF000000) != 0) {
                     int pos = x + y * 16;
                     SCRATCH[pos] = (short)pos;
                     QUEUE.enqueue((byte)pos);
                  }
               }
            }

            while (!QUEUE.isEmpty()) {
               int pos = Byte.toUnsignedInt(QUEUE.dequeueByte());
               int xx = pos & 15;
               int y = pos / 16;
               short newVal = (short)(SCRATCH[pos] + 256);

               for (int D = 3; D != -1; D--) {
                  int d = 2 * (D & 1) - 1;
                  int x2 = xx + ((D & 2) == 2 ? d : 0);
                  int y2 = y + ((D & 2) == 0 ? d : 0);
                  if (x2 >= 0 && x2 < 16 && y2 >= 0 && y2 < 16) {
                     int pos2 = x2 + y2 * 16;
                     if ((newVal & '\uff00') < (SCRATCH[pos2] & '\uff00')) {
                        SCRATCH[pos2] = newVal;
                        QUEUE.enqueue((byte)pos2);
                     }
                  }
               }
            }

            for (int i = 0; i < 256; i++) {
               int d = Short.toUnsignedInt(SCRATCH[i]);
               if ((d & 0xFF00) != 0) {
                  int c = MemoryUtil.memGetInt(baseAddr + getOffset(bx, by, d & 0xFF) * 4L) & 16777215;
                  MemoryUtil.memPutInt(baseAddr + getOffset(bx, by, i) * 4L, c);
               }
            }
         }
      }
   }

   public static void putTextures(boolean darkened, ColourDepthTextureData[] textures, MemoryBuffer into) {
      long addr = into.address;
      int LENGTH_B = 48;
      byte solidMsk = 0;

      for (int i = 0; i < 6; i++) {
         int x = (i >> 1) * 16;
         int y = (i & 1) * 16;
         int j = 0;
         boolean anyTransparent = false;

         for (int t : textures[i].colour()) {
            int o = ((y + (j >> ModelFactory.LAYERS)) * 48 + (j & 15) + x) * 4;
            j++;
            MemoryUtil.memPutInt(addr + o, t);
            anyTransparent |= (t & 0xFF000000) == 0;
         }

         solidMsk = (byte)(solidMsk | (anyTransparent ? 1 : 0) << i);
      }

      if (!darkened) {
         MipGen.Cache cache = CACHE.get();
         solidify(addr, solidMsk, cache.SCRATCH, cache.QUEUE);
      }

      long dAddr = addr;

      for (int i = 0; i < ModelFactory.LAYERS - 1; i++) {
         long sAddr = dAddr;
         dAddr += 6144 >> (i << 1);
         int width = 48 >> i + 1;
         int sWidth = 48 >> i;
         int height = 32 >> i + 1;

         for (int px = 0; px < width; px++) {
            for (int py = 0; py < height; py++) {
               long bp = sAddr + (px * 2 + py * 2 * sWidth) * 4;
               int C00 = MemoryUtil.memGetInt(bp);
               int C01 = MemoryUtil.memGetInt(bp + sWidth * 4);
               int C10 = MemoryUtil.memGetInt(bp + 4L);
               int C11 = MemoryUtil.memGetInt(bp + sWidth * 4 + 4L);
               MemoryUtil.memPutInt(dAddr + (px + py * width) * 4L, TextureUtils.mipColours(darkened, C00, C01, C10, C11));
            }
         }
      }
   }

   public static void generateMipmaps(long[] textures, int size) {
   }

   private record Cache(short[] SCRATCH, ByteArrayFIFOQueue QUEUE) {
      private Cache() {
         this(new short[256], new ByteArrayFIFOQueue(256));
      }
   }
}
