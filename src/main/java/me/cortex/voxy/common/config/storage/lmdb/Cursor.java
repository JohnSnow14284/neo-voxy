package me.cortex.voxy.common.config.storage.lmdb;

import org.lwjgl.util.lmdb.LMDB;
import org.lwjgl.util.lmdb.MDBVal;

public class Cursor implements AutoCloseable {
   private final long cursor;

   public Cursor(long cursor) {
      this.cursor = cursor;
   }

   public int get(int op, MDBVal key, MDBVal data) {
      int e = LMDB.mdb_cursor_get(this.cursor, key, data, op);
      if (e != 0 && e != -30798) {
         LMDBInterface.E(e);
      }

      return e;
   }

   @Override
   public void close() {
      LMDB.mdb_cursor_close(this.cursor);
   }
}
