package me.cortex.voxy.client.core.rendering.building;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;
import org.lwjgl.system.MemoryUtil;

public class OccupancySet2 {
   private long topLvl;
   private final long[] bottomLvl = new long[64];

   public void set(int pos) {
      long topBit = 1L << Integer.compress(pos, 3276);
      int botIdx = Integer.compress(pos, 819);
      int baseBotIdx = Long.bitCount(this.topLvl & topBit - 1L);
      if ((this.topLvl & topBit) == 0L) {
         long toMove = this.topLvl & ~((topBit << 1) - 1L);
         if (toMove != 0L) {
            int base = baseBotIdx + 1;
            int count = Long.bitCount(toMove);

            for (int i = base + count - 1; base <= i; i--) {
               this.bottomLvl[i] = this.bottomLvl[i - 1];
            }

            this.bottomLvl[baseBotIdx] = 0L;
         }

         this.topLvl |= topBit;
      }

      this.bottomLvl[baseBotIdx + (botIdx >> 6)] = this.bottomLvl[baseBotIdx + (botIdx >> 6)] | 1L << (botIdx & 63);
   }

   private boolean get(int pos) {
      long topBit = 1L << Integer.compress(pos, 3276);
      int botIdx = Integer.compress(pos, 819);
      if ((this.topLvl & topBit) == 0L) {
         return false;
      } else {
         int baseBotIdx = Long.bitCount(this.topLvl & topBit - 1L);
         return (this.bottomLvl[baseBotIdx + (botIdx >> 6)] & 1L << (botIdx & 63)) != 0L;
      }
   }

   public void reset() {
      if (this.topLvl != 0L) {
         Arrays.fill(this.bottomLvl, 0L);
      }

      this.topLvl = 0L;
   }

   public int writeSize() {
      return 8 + Long.bitCount(this.topLvl) * 8;
   }

   public boolean isEmpty() {
      return this.topLvl == 0L;
   }

   public void write(long ptr, boolean asLongs) {
      if (asLongs) {
         MemoryUtil.memPutLong(ptr, this.topLvl);
         ptr += 8L;
         int cnt = Long.bitCount(this.topLvl);

         for (int i = 0; i < cnt; i++) {
            MemoryUtil.memPutLong(ptr, this.bottomLvl[i]);
            ptr += 8L;
         }
      } else {
         MemoryUtil.memPutInt(ptr, (int)(this.topLvl >>> 32));
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, (int)this.topLvl);
         ptr += 4L;
         int cnt = Long.bitCount(this.topLvl);

         for (int i = 0; i < cnt; i++) {
            long v = this.bottomLvl[i];
            MemoryUtil.memPutInt(ptr, (int)(v >>> 32));
            ptr += 4L;
            MemoryUtil.memPutInt(ptr, (int)v);
            ptr += 4L;
         }
      }
   }

   public static void main(String[] args) {
      for (int q = 0; q < 1000; q++) {
         OccupancySet2 o = new OccupancySet2();
         Random r = new Random(12523532643L * q);
         BitSet bs = new BitSet(4096);

         for (int i = 0; i < 5000; i++) {
            int p = r.nextInt(4096);
            o.set(p);
            bs.set(p);

            for (int j = 0; j < 4096; j++) {
               if (o.get(j) != bs.get(j)) {
                  throw new IllegalStateException();
               }
            }
         }
      }
   }
}
