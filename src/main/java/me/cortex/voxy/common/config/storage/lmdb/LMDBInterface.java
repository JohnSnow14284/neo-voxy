package me.cortex.voxy.common.config.storage.lmdb;

import java.nio.IntBuffer;
import java.util.Objects;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.lmdb.LMDB;
import org.lwjgl.util.lmdb.MDBEnvInfo;

public class LMDBInterface {
   private final long env;

   private LMDBInterface(long env) {
      this.env = env;
   }

   public void close() {
      LMDB.mdb_env_close(this.env);
   }

   public static void E(int rc) {
      if (rc != 0) {
         throw new IllegalStateException("Code: " + rc + " msg: " + LMDB.mdb_strerror(rc));
      }
   }

   public void setMapSize(long size) {
      E(LMDB.mdb_env_set_mapsize(this.env, size));
   }

   public <T> T transaction(TransactionCallback<T> transaction) {
      return this.transaction(0, transaction);
   }

   public <T> T transaction(int flags, TransactionCallback<T> transaction) {
      return this.transaction(0L, flags, transaction);
   }

   public <T> T transaction(long parent, int flags, TransactionCallback<T> transaction) {
      MemoryStack stack = MemoryStack.stackPush();

      T ret;
      try {
         PointerBuffer pp = stack.mallocPointer(1);
         E(LMDB.mdb_txn_begin(this.env, parent, flags, pp));
         long txn = pp.get(0);

         int err;
         try {
            ret = transaction.exec(stack, txn);
            err = LMDB.mdb_txn_commit(txn);
         } catch (Throwable var13) {
            LMDB.mdb_txn_abort(txn);
            throw var13;
         }

         E(err);
      } catch (Throwable var14) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var12) {
               var14.addSuppressed(var12);
            }
         }

         throw var14;
      }

      if (stack != null) {
         stack.close();
      }

      return ret;
   }

   public LMDBInterface.Database createDb(String name) {
      return this.createDb(name, 262152);
   }

   public LMDBInterface.Database createDb(String name, int flags) {
      return new LMDBInterface.Database(name, flags);
   }

   public LMDBInterface flush(boolean force) {
      E(LMDB.mdb_env_sync(this.env, force));
      return this;
   }

   public long getMapSize() {
      MemoryStack stack = MemoryStack.stackPush();

      long var3;
      try {
         MDBEnvInfo info = MDBEnvInfo.calloc(stack);
         E(LMDB.mdb_env_info(this.env, info));
         var3 = info.me_mapsize();
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

   public static class Builder {
      private final long env;

      public Builder() {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            PointerBuffer pp = stack.mallocPointer(1);
            LMDBInterface.E(LMDB.mdb_env_create(pp));
            this.env = pp.get(0);
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
      }

      public LMDBInterface.Builder setMaxDbs(int maxDbs) {
         LMDBInterface.E(LMDB.mdb_env_set_maxdbs(this.env, maxDbs));
         return this;
      }

      public LMDBInterface.Builder open(String directory, int flags) {
         LMDBInterface.E(LMDB.mdb_env_open(this.env, directory, flags, 436));
         return this;
      }

      public LMDBInterface fetch() {
         return new LMDBInterface(this.env);
      }
   }

   public class Database {
      private final int dbi;

      public Database(String name, int flags) {
         Objects.requireNonNull(LMDBInterface.this);
         super();
         this.dbi = LMDBInterface.this.transaction((stack, txn) -> {
            IntBuffer ip = stack.mallocInt(1);
            LMDBInterface.E(LMDB.mdb_dbi_open(txn, name, flags, ip));
            return ip.get(0);
         });
      }

      public void close() {
         LMDB.mdb_dbi_close(LMDBInterface.this.env, this.dbi);
      }

      public <T> T transaction(TransactionWrappedCallback<T> callback) {
         return this.transaction(0, callback);
      }

      public <T> T transaction(int flags, TransactionWrappedCallback<T> callback) {
         return LMDBInterface.this.transaction(flags, (stack, transaction) -> callback.exec(new TransactionWrapper(transaction, stack).set(this)));
      }

      public int getDBI() {
         return this.dbi;
      }
   }
}
