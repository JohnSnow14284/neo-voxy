package me.cortex.voxy.client.core.util;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import java.lang.reflect.Array;
import java.util.Arrays;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33;

public class GPUTiming {
   public static GPUTiming INSTANCE = new GPUTiming();
   private final GPUTiming.GlTimestampQuerySet<String> timingSet = new GPUTiming.GlTimestampQuerySet<>(String.class);
   private float[] timings = new float[0];
   private String[] lables = new String[0];
   private boolean enabled = false;

   public void marker() {
      this.marker(null);
   }

   public void marker(String lable) {
      if (this.enabled) {
         this.timingSet.capture(lable);
      }
   }

   public void setEnabled(boolean enable) {
      if (this.enabled != enable) {
         this.enabled = enable;
      }
   }

   public String getDebug() {
      if (!this.enabled) {
         return "";
      } else {
         StringBuilder str = new StringBuilder("GpuTime: [");

         for (int i = 0; i < this.timings.length; i++) {
            if (this.lables[i] != null) {
               str.append(this.lables[i] + ":" + String.format("%.2f", this.timings[i]));
            } else {
               str.append(String.format("%.2f", this.timings[i]));
            }

            if (i != this.timings.length - 1) {
               str.append(", ");
            }
         }

         str.append(']');
         return str.toString();
      }
   }

   public void tick() {
      this.timingSet.download((meta, data) -> {
         long current = data[0];
         if (data.length - 1 != this.timings.length) {
            this.timings = new float[data.length - 1];
            this.lables = new String[meta.length - 1];
         }

         Arrays.fill(this.lables, null);

         for (int i = 1; i < meta.length; i++) {
            long next = data[i];
            long delta = next - current;
            float time = (float)(delta / 1000000.0);
            this.timings[i - 1] = Math.max(this.timings[i - 1] * 0.99F + time * 0.01F, time);
            this.lables[i - 1] = meta[i - 1];
            current = next;
         }
      });
      this.timingSet.tick();
   }

   public void free() {
      this.timingSet.free();
   }

   private static final class GlTimestampQuerySet<T> extends TrackedObject {
      private final IntArrayFIFOQueue POOL = new IntArrayFIFOQueue();
      private final ObjectArrayFIFOQueue<GPUTiming.GlTimestampQuerySet.InflightRequest<T>> INFLIGHT = new ObjectArrayFIFOQueue();
      private final int[] queries = new int[64];
      private final T[] metadata;
      private int index;

      private GlTimestampQuerySet(Class<T> metaClass) {
         this.metadata = (T[])((Object[])Array.newInstance(metaClass, 64));
      }

      public void capture(T metadata) {
         if (this.index > this.metadata.length) {
            throw new IllegalStateException();
         } else {
            int slot = this.index++;
            this.metadata[slot] = metadata;
            int query = this.getQuery();
            ARBTimerQuery.glQueryCounter(query, 36392);
            this.queries[slot] = query;
         }
      }

      public void download(GPUTiming.TimingDataConsumer<T[]> consumer) {
         if (this.index != 0) {
            int[] queries = Arrays.copyOf(this.queries, this.index);
            T[] metadata = (T[])Arrays.copyOf(this.metadata, this.index);
            Arrays.fill(this.metadata, null);
            this.index = 0;
            this.INFLIGHT.enqueue(new GPUTiming.GlTimestampQuerySet.InflightRequest<>(queries, metadata, consumer));
         }
      }

      public void tick() {
         while (!this.INFLIGHT.isEmpty() && ((GPUTiming.GlTimestampQuerySet.InflightRequest)this.INFLIGHT.first()).callbackIfReady(this.POOL)) {
            this.INFLIGHT.dequeue();
         }
      }

      private int getQuery() {
         return this.POOL.isEmpty() ? GL15.glGenQueries() : this.POOL.dequeueInt();
      }

      @Override
      public void free() {
         super.free0();

         while (!this.POOL.isEmpty()) {
            GL15.glDeleteQueries(this.POOL.dequeueInt());
         }

         while (!this.INFLIGHT.isEmpty()) {
            GL15.glDeleteQueries(((GPUTiming.GlTimestampQuerySet.InflightRequest)this.INFLIGHT.dequeue()).queries);
         }
      }

      private record InflightRequest<T>(int[] queries, T[] meta, GPUTiming.TimingDataConsumer<T[]> callback) {
         private boolean callbackIfReady(IntArrayFIFOQueue queryPool) {
            boolean ready = GL15C.glGetQueryObjecti(this.queries[this.queries.length - 1], 34919) == 1;
            if (!ready) {
               return false;
            } else {
               long[] results = new long[this.queries.length];

               for (int i = 0; i < this.queries.length; i++) {
                  results[i] = GL33.glGetQueryObjecti64(this.queries[i], 34918);
                  queryPool.enqueue(this.queries[i]);
               }

               this.callback.accept(this.meta, results);
               return true;
            }
         }
      }
   }

   public interface TimingDataConsumer<T> {
      void accept(T var1, long[] var2);
   }
}
