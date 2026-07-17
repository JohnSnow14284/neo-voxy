package me.cortex.voxy.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class ByteBufferBackedInputStream extends InputStream {
   private final ByteBuffer buf;

   public ByteBufferBackedInputStream(ByteBuffer buf) {
      this.buf = buf;
   }

   @Override
   public int read() throws IOException {
      return !this.buf.hasRemaining() ? -1 : this.buf.get() & 0xFF;
   }

   @Override
   public int read(byte[] bytes, int off, int len) throws IOException {
      if (!this.buf.hasRemaining()) {
         return -1;
      } else {
         len = Math.min(len, this.buf.remaining());
         this.buf.get(bytes, off, len);
         return len;
      }
   }
}
