package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.common.util.HierarchicalBitSet;
import org.lwjgl.system.MemoryUtil;

public final class NodeStore {
   public static final int EMPTY_GEOMETRY_ID = -1;
   public static final int NODE_ID_MSK = 16777215;
   public static final int REQUEST_ID_MSK = 524287;
   public static final int GEOMETRY_ID_MSK = 16777215;
   public static final int MAX_GEOMETRY_ID = 16777213;
   private static final int SENTINEL_NULL_GEOMETRY_ID = 16777215;
   private static final int SENTINEL_EMPTY_GEOMETRY_ID = 16777214;
   private static final int SENTINEL_NULL_NODE_ID = 16777215;
   private static final int LONGS_PER_NODE = 4;
   private static final int INCREMENT_SIZE = 65536;
   private final HierarchicalBitSet allocationSet;
   private long[] localNodeData;

   public NodeStore(int maxNodeCount) {
      if (maxNodeCount >= 16777215) {
         throw new IllegalArgumentException("Max count too large");
      } else {
         this.localNodeData = new long[262144];
         this.allocationSet = new HierarchicalBitSet(maxNodeCount);
      }
   }

   private static int id2idx(int idx) {
      return idx * 4;
   }

   public int allocate() {
      int id = this.allocationSet.allocateNext();
      if (id < 0) {
         throw new IllegalStateException("Failed to allocate node slot!");
      } else {
         this.ensureSized(id);
         this.clear(id);
         return id;
      }
   }

   public int allocate(int count) {
      if (count <= 0) {
         throw new IllegalArgumentException("Count cannot be <= 0 was " + count);
      } else {
         int id = this.allocationSet.allocateNextConsecutiveCounted(count);
         if (id < 0) {
            throw new IllegalStateException("Failed to allocate " + count + " consecutive nodes!!");
         } else {
            this.ensureSized(id + count);

            for (int i = 0; i < count; i++) {
               this.clear(id + i);
            }

            return id;
         }
      }
   }

   private void ensureSized(int index) {
      if (index * 4 >= this.localNodeData.length) {
         int newSize = Math.min(index + 65536, this.allocationSet.getLimit());
         long[] newStore = new long[newSize * 4];
         System.arraycopy(this.localNodeData, 0, newStore, 0, this.localNodeData.length);
         this.localNodeData = newStore;
      }
   }

   public void free(int nodeId) {
      this.free(nodeId, 1);
   }

   public void free(int baseNodeId, int count) {
      for (int i = 0; i < count; i++) {
         int nodeId = baseNodeId + i;
         if (!this.allocationSet.free(nodeId)) {
            throw new IllegalStateException("Node " + nodeId + " was not allocated!");
         }

         this.clear(nodeId);
      }
   }

   private void clear(int nodeId) {
      int idx = id2idx(nodeId);
      this.localNodeData[idx] = -1L;
      this.localNodeData[idx + 1] = 281474976710655L;
      this.localNodeData[idx + 2] = 524287L;
      this.localNodeData[idx + 3] = 0L;
   }

   public void copyNode(int fromId, int toId) {
      if (this.allocationSet.isSet(fromId) && this.allocationSet.isSet(toId)) {
         int f = id2idx(fromId);
         int t = id2idx(toId);
         this.localNodeData[t] = this.localNodeData[f];
         this.localNodeData[t + 1] = this.localNodeData[f + 1];
         this.localNodeData[t + 2] = this.localNodeData[f + 2];
         this.localNodeData[t + 3] = this.localNodeData[f + 3];
      } else {
         throw new IllegalArgumentException();
      }
   }

   public void setNodePosition(int node, long position) {
      this.localNodeData[id2idx(node)] = position;
   }

   public long nodePosition(int nodeId) {
      return this.localNodeData[id2idx(nodeId)];
   }

   public boolean nodeExists(int nodeId) {
      return this.allocationSet.isSet(nodeId);
   }

   public int getNodeGeometry(int node) {
      long data = this.localNodeData[id2idx(node) + 1];
      int geometryPtr = (int)(data & 16777215L);
      if (geometryPtr == 16777215) {
         return -1;
      } else {
         return geometryPtr == 16777214 ? -2 : geometryPtr;
      }
   }

   public void setNodeGeometry(int node, int geometryId) {
      if (geometryId > 16777213) {
         throw new IllegalArgumentException("Geometry ptr greater than MAX_GEOMETRY_ID: " + geometryId);
      } else {
         if (geometryId == -1) {
            geometryId = 16777215;
         }

         if (geometryId == -2) {
            geometryId = 16777214;
         }

         if (geometryId < 0) {
            throw new IllegalArgumentException("Geometry ptr less than -1 : " + geometryId);
         } else {
            int idx = id2idx(node) + 1;
            long data = this.localNodeData[idx];
            data &= -16777216L;
            data |= geometryId;
            this.localNodeData[idx] = data;
         }
      }
   }

   public int getChildPtr(int nodeId) {
      long data = this.localNodeData[id2idx(nodeId) + 1];
      int nodePtr = (int)(data >> 24 & 16777215L);
      return nodePtr == 16777215 ? -1 : nodePtr;
   }

   public void setChildPtr(int nodeId, int ptr) {
      if (ptr < 16777215 && ptr >= -1) {
         if (ptr == -1) {
            ptr = 16777215;
         }

         int idx = id2idx(nodeId) + 1;
         long data = this.localNodeData[idx];
         data &= -281474959933441L;
         data |= (long)ptr << 24;
         this.localNodeData[idx] = data;
      } else {
         throw new IllegalArgumentException("Node child ptr greater GEQ NODE_ID_MSK or less than -1 : " + ptr);
      }
   }

   public void setNodeRequest(int node, int requestId) {
      if (requestId >= 0 && requestId <= 524287) {
         int id = id2idx(node) + 2;
         long data = this.localNodeData[id];
         data &= ~Integer.toUnsignedLong(524287);
         data |= requestId;
         this.localNodeData[id] = data;
      } else {
         throw new IllegalStateException("Too many requests to happen at once!");
      }
   }

   public int getNodeRequest(int node) {
      return (int)(this.localNodeData[id2idx(node) + 2] & 524287L);
   }

   public void markRequestInFlight(int nodeId) {
      this.localNodeData[id2idx(nodeId) + 1] |= Long.MIN_VALUE;
   }

   public void unmarkRequestInFlight(int nodeId) {
      this.localNodeData[id2idx(nodeId) + 1] &= Long.MAX_VALUE;
   }

   public boolean isNodeRequestInFlight(int nodeId) {
      return (this.localNodeData[id2idx(nodeId) + 1] >> 63 & 1L) != 0L;
   }

   public void setAllChildrenAreLeaf(int nodeId, boolean state) {
      this.localNodeData[id2idx(nodeId) + 2] &= -524289L;
      this.localNodeData[id2idx(nodeId) + 2] |= state ? 524288L : 0L;
   }

   public boolean getAllChildrenAreLeaf(int nodeId) {
      return (this.localNodeData[id2idx(nodeId) + 2] >> 19 & 1L) != 0L;
   }

   public void markNodeGeometryInFlight(int nodeId) {
      this.localNodeData[id2idx(nodeId) + 1] |= 576460752303423488L;
   }

   public void unmarkNodeGeometryInFlight(int nodeId) {
      this.localNodeData[id2idx(nodeId) + 1] &= -576460752303423489L;
   }

   public boolean isNodeGeometryInFlight(int nodeId) {
      return (this.localNodeData[id2idx(nodeId) + 1] & 576460752303423488L) != 0L;
   }

   public int getNodeType(int nodeId) {
      return (int)(this.localNodeData[id2idx(nodeId) + 1] >> 61 & 3L) << 30;
   }

   public void setNodeType(int nodeId, int type) {
      type >>>= 30;
      int idx = id2idx(nodeId) + 1;
      long data = this.localNodeData[idx];
      data &= -6917529027641081857L;
      data |= (long)type << 61;
      this.localNodeData[idx] = data;
   }

   public byte getNodeChildExistence(int nodeId) {
      long data = this.localNodeData[id2idx(nodeId) + 1];
      return (byte)(data >> 48 & 255L);
   }

   public void setNodeChildExistence(int nodeId, byte existence) {
      int idx = id2idx(nodeId) + 1;
      long data = this.localNodeData[idx];
      data &= -71776119061217281L;
      data |= Byte.toUnsignedLong(existence) << 48;
      this.localNodeData[idx] = data;
   }

   public int getChildPtrCount(int nodeId) {
      long data = this.localNodeData[id2idx(nodeId) + 1];
      return (int)(data >> 56 & 7L) + 1;
   }

   public void setChildPtrCount(int nodeId, int count) {
      if (count > 0 && count <= 8) {
         int idx = id2idx(nodeId) + 1;
         long data = this.localNodeData[idx];
         data &= -504403158265495553L;
         data |= (long)(count - 1) << 56;
         this.localNodeData[idx] = data;
      } else {
         throw new IllegalArgumentException("Count: " + count);
      }
   }

   public void writeNode(long ptr, int nodeId) {
      if (!this.nodeExists(nodeId)) {
         MemoryUtil.memPutLong(ptr, -1L);
         MemoryUtil.memPutLong(ptr + 8L, -1L);
      } else {
         long pos = this.nodePosition(nodeId);
         MemoryUtil.memPutInt(ptr, (int)(pos >> 32));
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, (int)pos);
         ptr += 4L;
         int z = 0;
         int w = 0;
         short flags = 0;
         flags = (short)(flags | (short)(this.isNodeRequestInFlight(nodeId) ? 1 : 0));
         flags = (short)(flags | (short)(this.getChildPtrCount(nodeId) - 1 << 2));
         boolean isEligibleForCleaning = false;
         isEligibleForCleaning |= this.getAllChildrenAreLeaf(nodeId);
         flags = (short)(flags | (short)(isEligibleForCleaning ? 32 : 0));
         int geometry = this.getNodeGeometry(nodeId);
         if (geometry == -2) {
            z |= 16777214;
         } else if (geometry == -1) {
            z |= 16777215;
         } else {
            z |= geometry & 16777215;
         }

         geometry = this.getChildPtr(nodeId);
         w |= geometry & 16777215;
         z |= (flags & 255) << 24;
         w |= (flags >> 8 & 0xFF) << 24;
         MemoryUtil.memPutInt(ptr, z);
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, w);
         ptr += 4L;
      }
   }

   public int getEndNodeId() {
      return this.allocationSet.getMaxIndex();
   }

   public int getNodeCount() {
      return this.allocationSet.getCount();
   }
}
