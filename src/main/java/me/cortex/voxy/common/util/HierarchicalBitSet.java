package me.cortex.voxy.common.util;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Random;

public class HierarchicalBitSet {
   public static final int SET_FULL = -1;
   private final int limit;
   private int cnt;
   private long A = 0L;
   private final long[] B = new long[64];
   private final long[] C = new long[4096];
   private final long[] D = new long[262144];
   private int endId = -1;

   public HierarchicalBitSet(int limit) {
      this.limit = limit;
      if (limit > 16777216) {
         throw new IllegalArgumentException("Limit greater than capacity");
      }
   }

   public HierarchicalBitSet() {
      this(16777216);
   }

   public int allocateNext() {
      if (this.A == -1L) {
         return -1;
      } else if (this.cnt + 1 > this.limit) {
         return -1;
      } else {
         int idx = Long.numberOfTrailingZeros(~this.A);
         long bp = this.B[idx];
         idx = Long.numberOfTrailingZeros(~bp) + 64 * idx;
         long cp = this.C[idx];
         idx = Long.numberOfTrailingZeros(~cp) + 64 * idx;
         long dp = this.D[idx];
         idx = Long.numberOfTrailingZeros(~dp) + 64 * idx;
         int allocatedId = idx;
         dp |= 1L << (idx & 63);
         this.D[idx >> 6] = dp;
         if (dp == -1L) {
            idx >>= 6;
            cp |= 1L << (idx & 63);
            this.C[idx >> 6] = cp;
            if (cp == -1L) {
               idx >>= 6;
               bp |= 1L << (idx & 63);
               this.B[idx >> 6] = bp;
               if (bp == -1L) {
                  idx >>= 6;
                  this.A |= 1L << (idx & 63);
               }
            }
         }

         this.cnt++;
         this.endId = this.endId + (allocatedId == this.endId + 1 ? 1 : 0);
         return allocatedId;
      }
   }

   private void set(int idx) {
      this.endId = this.endId + (idx == this.endId + 1 ? 1 : 0);
      long dp = this.D[idx >> 6] = this.D[idx >> 6] | 1L << (idx & 63);
      if (dp == -1L) {
         idx >>= 6;
         long cp = this.C[idx >> 6] = this.C[idx >> 6] | 1L << (idx & 63);
         if (cp == -1L) {
            idx >>= 6;
            long bp = this.B[idx >> 6] = this.B[idx >> 6] | 1L << (idx & 63);
            if (bp == -1L) {
               idx >>= 6;
               this.A |= 1L << (idx & 63);
            }
         }
      }

      this.cnt++;
   }

   private int findNextFree(int idx) {
      int var4;
      do {
         int pos = Long.numberOfTrailingZeros(~this.A & -(1L << (idx >> 18)));
         idx = Math.max(pos << 18, idx);
         var4 = Long.numberOfTrailingZeros(~this.B[idx >> 18] & -(1L << (idx >> 12 & 63)));
         idx = Math.max(var4 + (idx >> 18 << 6) << 12, idx);
         if (var4 != 64) {
            var4 = Long.numberOfTrailingZeros(~this.C[idx >> 12] & -(1L << (idx >> 6 & 63)));
            idx = Math.max(var4 + (idx >> 12 << 6) << 6, idx);
            if (var4 != 64) {
               var4 = Long.numberOfTrailingZeros(~this.D[idx >> 6] & -(1L << (idx & 63)));
               idx = Math.max(var4 + (idx >> 6 << 6), idx);
            }
         }
      } while (var4 == 64);

      return idx;
   }

   public int allocateNextConsecutiveCounted(int count) {
      if (count > 64) {
         throw new IllegalStateException("Count to large for current implementation which has fastpath");
      } else if (this.A == -1L) {
         return -1;
      } else if (this.cnt + count >= this.limit) {
         return -2;
      } else {
         long chkMsk = (1L << count) - 1L;
         int i = this.findNextFree(0);

         while (true) {
            long fusedValue = this.D[i >> 6] >>> (i & 63);
            if (64 - (i & 63) < count) {
               fusedValue |= this.D[(i >> 6) + 1] << 64 - (i & 63);
            }

            if ((fusedValue & chkMsk) == 0L) {
               for (int j = 0; j < count; j++) {
                  this.set(j + i);
               }

               return i;
            }

            i += Long.numberOfTrailingZeros(fusedValue);
            i = this.findNextFree(i);
         }
      }
   }

   public boolean free(int idx) {
      long v = this.D[idx >> 6];
      boolean wasSet = (v & 1L << (idx & 63)) != 0L;
      this.cnt -= wasSet ? 1 : 0;
      if (wasSet && idx == this.endId) {
         this.endId--;

         while (this.endId >= 0 && !this.isSet(this.endId)) {
            this.endId--;
         }
      }

      this.D[idx >> 6] = v & ~(1L << (idx & 63));
      idx >>= 6;
      this.C[idx >> 6] = this.C[idx >> 6] & ~(1L << (idx & 63));
      idx >>= 6;
      this.B[idx >> 6] = this.B[idx >> 6] & ~(1L << (idx & 63));
      idx >>= 6;
      this.A &= ~(1L << (idx & 63));
      return wasSet;
   }

   public int getCount() {
      return this.cnt;
   }

   public int getLimit() {
      return this.limit;
   }

   public boolean isSet(int idx) {
      return (this.D[idx >> 6] & 1L << (idx & 63)) != 0L;
   }

   public int getMaxIndex() {
      return this.endId;
   }

   public static void main3(String[] args) {
      HierarchicalBitSet h = new HierarchicalBitSet(524288);

      for (int i = 0; i < 524288; i++) {
         if (h.allocateNext() != i) {
            throw new IllegalStateException("At:" + i);
         }

         if (h.endId != i) {
            throw new IllegalStateException();
         }
      }

      for (int i = 0; i < 262144; i++) {
         if (!h.free(i)) {
            throw new IllegalStateException();
         }
      }

      for (int ix = 524287; ix != 262143; ix--) {
         if (h.endId != ix) {
            throw new IllegalStateException();
         }

         if (!h.free(ix)) {
            throw new IllegalStateException();
         }
      }

      if (h.endId != -1) {
         throw new IllegalStateException();
      }
   }

   public static void main2(String[] args) {
      HierarchicalBitSet h = new HierarchicalBitSet();

      for (int i = 0; i < 2048; i++) {
         h.set(i);
      }

      h.set(0);
      int i = 0;

      while (i < 2048) {
         int j = h.findNextFree(i);
         if (h.isSet(j)) {
            throw new IllegalStateException();
         }

         for (int k = i; k < j; k++) {
            if (!h.isSet(k)) {
               throw new IllegalStateException();
            }
         }

         i = j + 1;
      }

      Random r = new Random(0L);

      for (int ix = 0; ix < 500; ix++) {
         h.free(r.nextInt(2048));
      }

      h.allocateNextConsecutiveCounted(10);
   }

   public static void main(String[] args) {
      for (int i = 0; i < 100; i++) {
         Random r = new Random(i * 12345L);
         HierarchicalBitSet h = new HierarchicalBitSet();
         IntSet set = new IntOpenHashSet(10000);

         for (int j = 0; j < 100000; j++) {
            int q = h.allocateNext();
            if (q != j || !set.add(q)) {
               throw new IllegalStateException();
            }
         }

         for (int jx = 0; jx < 100000; jx++) {
            int op = r.nextInt(5);
            int extra = r.nextInt(8) + 1;
            if (op == 0) {
               int v = h.allocateNext();
               if (v < 0) {
                  throw new IllegalStateException();
               }

               if (!set.add(v)) {
                  throw new IllegalStateException();
               }
            } else if (op == 1) {
               int base = h.allocateNextConsecutiveCounted(extra);
               if (base < 0) {
                  throw new IllegalStateException();
               }

               for (int q = 0; q < extra; q++) {
                  if (!set.add(q + base)) {
                     throw new IllegalStateException();
                  }
               }
            } else if (op < 5 && !set.isEmpty()) {
               int rr = r.nextInt(set.size());
               IntIterator s = set.iterator();
               if (rr != 0) {
                  s.skip(rr);
               }

               int qx = s.nextInt();
               s.remove();
               if (!h.free(qx)) {
                  throw new IllegalStateException();
               }
            }
         }
      }
   }
}
