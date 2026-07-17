package me.cortex.voxy.client.core.util;

import java.util.Random;

public abstract class ScanMesher2D {
   private static final int MAX_SIZE = 16;
   private final long[] rowData = new long[32];
   private final int[] rowLength = new int[32];
   private final int[] rowDepth = new int[32];
   private int rowBitset = 0;
   private int currentIndex = 0;
   private int currentSum = 0;
   private long currentData = 0L;

   public final void putNext(long data) {
      this.putNext0(data);
   }

   private void putNext0(long data) {
      int idx = this.currentIndex++ & 31;
      if (idx == 0) {
         if (this.currentData != 0L) {
            if ((this.rowBitset & -2147483648) != 0) {
               this.emitQuad(31, (this.currentIndex - 1 >> 5) - 1, this.rowLength[31], this.rowDepth[31], this.rowData[31]);
            }

            this.rowBitset |= Integer.MIN_VALUE;
            this.rowLength[31] = this.currentSum;
            this.rowDepth[31] = 1;
            this.rowData[31] = this.currentData;
         }

         this.currentData = data;
         this.currentSum = 0;
      }

      if (data != this.currentData || this.currentSum == 16) {
         if (this.currentData != 0L) {
            int prev = idx - 1;
            this.rowDepth[prev] = 1;
            this.rowLength[prev] = this.currentSum;
            this.rowData[prev] = this.currentData;
            this.rowBitset |= 1 << prev;
         }

         this.currentData = data;
         this.currentSum = 0;
      }

      this.currentSum++;
      boolean isSet = (this.rowBitset & 1 << idx) != 0;
      boolean causedByDepthMax = false;
      if (this.currentData != 0L && isSet && this.rowLength[idx] == this.currentSum && this.rowData[idx] == this.currentData) {
         int depth = ++this.rowDepth[idx];
         this.currentSum = 0;
         this.currentData = 0L;
         if (depth != 16) {
            return;
         }

         causedByDepthMax = true;
      }

      if (isSet) {
         this.emitQuad(idx & 31, (this.currentIndex - 1 >> 5) - (causedByDepthMax ? 0 : 1), this.rowLength[idx], this.rowDepth[idx], this.rowData[idx]);
         this.rowBitset &= ~(1 << idx);
      }
   }

   private void emitRanged(int msk) {
      int rowSet = this.rowBitset & msk;
      this.rowBitset &= ~msk;

      while (rowSet != 0) {
         int index = Integer.numberOfTrailingZeros(rowSet);
         rowSet &= ~Integer.lowestOneBit(rowSet);
         this.emitQuad(index, (this.currentIndex >> 5) - 1, this.rowLength[index], this.rowDepth[index], this.rowData[index]);
      }
   }

   public final void skip(int count) {
      if (count != 0) {
         if (this.currentData != 0L) {
            this.putNext0(0L);
            count--;
         }

         if (0 < count) {
            int msk = (int)((1L << Math.min(32, count)) - 1L) << (this.currentIndex & 31);
            this.emitRanged(msk);
            this.currentIndex += count;
         }
      }
   }

   public final void reset() {
      this.rowBitset = 0;
      this.currentSum = 0;
      this.currentData = 0L;
      this.currentIndex = 0;
   }

   public final void endRow() {
      if ((this.currentIndex & 31) != 0) {
         this.skip(32 - (this.currentIndex & 31));
      }
   }

   public final void finish() {
      if (this.currentIndex != 0) {
         this.skip(32 - (this.currentIndex & 31));
         this.emitRanged(-1);
      }

      this.reset();
   }

   protected abstract void emitQuad(int var1, int var2, int var3, int var4, long var5);

   public static void main9(String[] args) {
      final int[] qc = new int[3];
      var mesher = new ScanMesher2D() {
         @Override
         protected void emitQuad(int x, int z, int length, int width, long data) {
            qc[0]++;
            if (data != qc[0]) {
               throw new IllegalStateException();
            } else if (length * width != 1 && data != qc[0]) {
               throw new IllegalStateException();
            } else if (x != (qc[0] & 31)) {
               throw new IllegalStateException();
            } else if (z != qc[0] >> 5) {
               throw new IllegalStateException();
            }
         }
      };
      mesher.putNext(0L);
      int i = 1;

      while (true) {
         mesher.putNext(i++);
      }
   }

   public static void main2(String[] args) {
      long[] sample = new long[1024];
      sample[0] = 1L;
      sample[1] = 1L;
      sample[2] = 1L;
      sample[3] = 1L;
      sample[4] = 2L;
      sample[5] = 2L;
      sample[6] = 2L;
      sample[7] = 2L;
      sample[32] = 1L;
      sample[33] = 1L;
      sample[34] = 1L;
      sample[35] = 1L;
      sample[36] = 2L;
      sample[37] = 2L;
      sample[38] = 2L;
      sample[39] = 2L;
      sample[31] = 6L;
      sample[63] = 6L;
      sample[94] = 7L;
      sample[95] = 7L;
      sample[126] = 7L;
      sample[127] = 7L;
      sample[287] = 8L;
      var mesher = new ScanMesher2D() {
         @Override
         protected void emitQuad(int x, int z, int length, int width, long data) {
            System.out.println(length + ", " + width + ", " + data);
         }
      };
      int j = 0;

      for (long i : sample) {
         if (j % 32 == 0) {
            System.out.println("row");
         }

         mesher.putNext(i);
         j++;
      }
   }

   public static void main6(String[] args) {
      Random r = new Random(0L);
      float DENSITY = 0.9F;
      int RANGE = 3;

      while (true) {
         long[] data = new long[1024];

         for (int i = 0; i < data.length; i++) {
            data[i] = r.nextFloat() < DENSITY ? r.nextInt(RANGE) + 1 : 0L;
         }

         final long[] out = new long[1024];
         var mesher = new ScanMesher2D() {
            @Override
            protected void emitQuad(int x, int z, int length, int width, long datax) {
               if (datax == 0L) {
                  throw new IllegalStateException();
               } else if (z < 0 || x < 0 || x > 31 || z > 31) {
                  throw new IllegalStateException();
               } else if (length >= 1 && width >= 1 && length <= 16 && width <= 16) {
                  x -= length - 1;
                  z -= width - 1;
                  if (z >= 0 && x >= 0 && x <= 31 && z <= 31) {
                     for (int X = x; X < x + length; X++) {
                        for (int Z = z; Z < z + width; Z++) {
                           int idx = Z * 32 + X;
                           if (out[idx] != 0L) {
                              throw new IllegalStateException();
                           }

                           out[idx] = datax;
                        }
                     }
                  } else {
                     throw new IllegalStateException();
                  }
               } else {
                  throw new IllegalStateException();
               }
            }
         };

         for (long a : data) {
            mesher.putNext(a);
         }

         mesher.finish();

         for (int i = 0; i < 1024; i++) {
            if (data[i] != out[i]) {
               System.out.println("ERROR");
            }
         }
      }
   }

   public static void main(String[] args) {
      long[] data = new long[1024];

      for (int x = 0; x < 20; x++) {
         for (int z = 0; z < 20; z++) {
            data[z * 32 + x] = 1L;
         }
      }

      final long[] out = new long[1024];
      var mesher = new ScanMesher2D() {
         @Override
         protected void emitQuad(int x, int z, int length, int width, long datax) {
            if (datax == 0L) {
               throw new IllegalStateException();
            } else if (z < 0 || x < 0 || x > 31 || z > 31) {
               throw new IllegalStateException();
            } else if (length >= 1 && width >= 1 && length <= 16 && width <= 16) {
               x -= length - 1;
               z -= width - 1;
               if (z >= 0 && x >= 0 && x <= 31 && z <= 31) {
                  for (int X = x; X < x + length; X++) {
                     for (int Z = z; Z < z + width; Z++) {
                        int idx = Z * 32 + X;
                        if (out[idx] != 0L) {
                           throw new IllegalStateException();
                        }

                        out[idx] = datax;
                     }
                  }
               } else {
                  throw new IllegalStateException();
               }
            } else {
               throw new IllegalStateException();
            }
         }
      };

      for (long a : data) {
         mesher.putNext(a);
      }

      mesher.finish();

      for (int i = 0; i < 1024; i++) {
         if (data[i] != out[i]) {
            System.out.println("ERROR");
         }
      }
   }
}
