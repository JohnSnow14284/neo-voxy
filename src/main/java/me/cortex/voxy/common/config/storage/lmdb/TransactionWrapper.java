package me.cortex.voxy.common.config.storage.lmdb;

import java.nio.ByteBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.lmdb.LMDB;
import org.lwjgl.util.lmdb.MDBVal;

public class TransactionWrapper {
   public final MemoryStack stack;
   private final long transaction;
   private int dbi;

   public TransactionWrapper(long transaction, MemoryStack stack) {
      this.transaction = transaction;
      this.stack = stack;
   }

   public TransactionWrapper set(LMDBInterface.Database db) {
      this.dbi = db.getDBI();
      return this;
   }

   public TransactionWrapper put(ByteBuffer key, ByteBuffer val, int flags) {
      MemoryStack stack = MemoryStack.stackPush();

      TransactionWrapper var5;
      try {
         LMDBInterface.E(LMDB.mdb_put(this.transaction, this.dbi, MDBVal.malloc(stack).mv_data(key), MDBVal.malloc(stack).mv_data(val), flags));
         var5 = this;
      } catch (Throwable var8) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var7) {
               var8.addSuppressed(var7);
            }
         }

         throw var8;
      }

      if (stack != null) {
         stack.close();
      }

      return var5;
   }

   public TransactionWrapper del(ByteBuffer key) {
      MemoryStack stack = MemoryStack.stackPush();

      TransactionWrapper var3;
      try {
         LMDBInterface.E(LMDB.mdb_del(this.transaction, this.dbi, MDBVal.malloc(stack).mv_data(key), null));
         var3 = this;
      } catch (Throwable var6) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }
         }

         throw var6;
      }

      if (stack != null) {
         stack.close();
      }

      return var3;
   }

   public ByteBuffer get(ByteBuffer key) {
      MemoryStack stack = MemoryStack.stackPush();

      ByteBuffer var8;
      label43: {
         try {
            MDBVal ret = MDBVal.malloc(stack);
            int retVal = LMDB.mdb_get(this.transaction, this.dbi, MDBVal.calloc(stack).mv_data(key), ret);
            if (retVal == -30798) {
               var8 = null;
               break label43;
            }

            LMDBInterface.E(retVal);
            var8 = ret.mv_data();
         } catch (Throwable var7) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var6) {
                  var7.addSuppressed(var6);
               }
            }

            throw var7;
         }

         if (stack != null) {
            stack.close();
         }

         return var8;
      }

      if (stack != null) {
         stack.close();
      }

      return var8;
   }

   public Cursor createCursor() {
      MemoryStack stack = MemoryStack.stackPush();

      Cursor var3;
      try {
         PointerBuffer pb = stack.mallocPointer(1);
         LMDBInterface.E(LMDB.mdb_cursor_open(this.transaction, this.dbi, pb));
         var3 = new Cursor(pb.get(0));
      } catch (Throwable var5) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }
         }

         throw var5;
      }

      if (stack != null) {
         stack.close();
      }

      return var3;
   }
}
