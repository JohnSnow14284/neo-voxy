package me.cortex.voxy.common.util;

import it.unimi.dsi.fastutil.longs.LongBidirectionalIterator;
import it.unimi.dsi.fastutil.longs.LongRBTreeSet;

public class AllocationArena {
   public static final long SIZE_LIMIT = -1L;
   private static final int ADDR_BITS = 34;
   private static final int SIZE_BITS = 30;
   private static final long SIZE_MSK = 1073741823L;
   private static final long ADDR_MSK = 17179869183L;
   private final LongRBTreeSet FREE = new LongRBTreeSet(Long::compareUnsigned);
   private final LongRBTreeSet TAKEN = new LongRBTreeSet(Long::compareUnsigned);
   private long sizeLimit = Long.MAX_VALUE;
   private long totalSize;
   private boolean resized;

   public void reset() {
      this.FREE.clear();
      this.TAKEN.clear();
      this.sizeLimit = Long.MAX_VALUE;
      this.totalSize = 0L;
      this.resized = false;
   }

   public boolean getResetResized() {
      boolean ret = this.resized;
      this.resized = false;
      return ret;
   }

   public long getSize() {
      return this.totalSize;
   }

   public int numFreeBlocks() {
      return this.FREE.size();
   }

   public int getLargestFreeBlockSize(int index) {
      LongBidirectionalIterator iter = this.FREE.tailSet(-1L).iterator();

      while (index > 0 && iter.hasPrevious()) {
         iter.previousLong();
         index--;
      }

      long slot = iter.previousLong();
      return (int)(slot >> 34);
   }

   public long alloc(int size) {
      if (size == 0) {
         throw new IllegalArgumentException();
      } else {
         LongBidirectionalIterator iter = this.FREE.iterator(((long)size << 34) - 1L);
         if (!iter.hasNext()) {
            this.resized = true;
            long addr = this.totalSize;
            if (this.totalSize + size > this.sizeLimit) {
               return -1L;
            } else {
               this.totalSize += size;
               this.TAKEN.add(addr << 30 | size);
               return addr;
            }
         } else {
            long slot = iter.nextLong();
            iter.remove();
            if (slot >>> 34 == size) {
               this.TAKEN.add(slot << 30 | slot >>> 34);
            } else {
               this.TAKEN.add((slot & 17179869183L) << 30 | size);
               this.FREE.add((slot >>> 34) - size << 34 | (slot & 17179869183L) + size);
            }

            return slot & 17179869183L;
         }
      }
   }

   public int free(long addr) {
      addr &= 17179869183L;
      LongBidirectionalIterator iter = this.TAKEN.iterator(addr << 30);
      long slot = iter.nextLong();
      if (slot >> 30 != addr) {
         throw new IllegalStateException();
      } else {
         long size = slot & 1073741823L;
         iter.remove();
         if (iter.hasPrevious()) {
            long prevSlot = iter.previousLong();
            long endAddr = (prevSlot >>> 30) + (prevSlot & 1073741823L);
            if (endAddr != addr) {
               long delta = addr - endAddr;
               this.FREE.remove(delta << 34 | endAddr);
               slot = endAddr << 30 | (slot & 1073741823L) + delta;
            }

            iter.nextLong();
         } else if (!this.FREE.isEmpty() && this.FREE.remove(addr << 34)) {
            slot = addr + size;
         }

         if (iter.hasNext()) {
            long nextSlot = iter.nextLong();
            long endAddr = (slot >>> 30) + (slot & 1073741823L);
            if (endAddr != nextSlot >>> 30) {
               long delta = (nextSlot >>> 30) - endAddr;
               this.FREE.remove(delta << 34 | endAddr);
               slot = slot & -1073741824L | (slot & 1073741823L) + delta;
            }

            slot = slot >>> 30 | slot << 34;
            this.FREE.add(slot);
            return (int)size;
         } else {
            this.resized = true;
            this.totalSize -= slot & 1073741823L;
            return (int)size;
         }
      }
   }

   public boolean expand(long addr, int extra) {
      addr &= 17179869183L;
      LongBidirectionalIterator iter = this.TAKEN.iterator(addr << 30);
      if (!iter.hasNext()) {
         return false;
      } else {
         long slot = iter.nextLong();
         if (slot >> 30 != addr) {
            throw new IllegalStateException();
         } else {
            long updatedSlot = slot & -1073741824L | (slot & 1073741823L) + extra;
            if (iter.hasNext()) {
               long next = iter.nextLong();
               long endAddr = (slot >>> 30) + (slot & 1073741823L);
               long delta = (next >>> 30) - endAddr;
               if (extra <= delta) {
                  this.FREE.remove(delta << 34 | endAddr);
                  iter.previousLong();
                  iter.previousLong();
                  iter.remove();
                  this.TAKEN.add(updatedSlot);
                  if (extra != delta) {
                     this.FREE.add(delta - extra << 34 | endAddr + extra);
                  }

                  return true;
               } else {
                  return false;
               }
            } else if (this.totalSize + extra > this.sizeLimit) {
               return false;
            } else {
               iter.remove();
               this.TAKEN.add(updatedSlot);
               this.totalSize += extra;
               return true;
            }
         }
      }
   }

   public long getSize(long addr) {
      addr &= 17179869183L;
      LongBidirectionalIterator iter = this.TAKEN.iterator(addr << 30);
      if (!iter.hasNext()) {
         throw new IllegalArgumentException();
      } else {
         long slot = iter.nextLong();
         if (slot >> 30 != addr) {
            throw new IllegalStateException();
         } else {
            return slot & 1073741823L;
         }
      }
   }

   public void setLimit(long size) {
      this.sizeLimit = size;
      if (this.sizeLimit < this.totalSize) {
         throw new IllegalStateException("Size set smaller than current size");
      }
   }

   public long getLimit() {
      return this.sizeLimit;
   }
}
