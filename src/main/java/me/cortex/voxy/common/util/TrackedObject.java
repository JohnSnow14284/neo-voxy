package me.cortex.voxy.common.util;

import java.lang.ref.Cleaner.Cleanable;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;

public abstract class TrackedObject {
   public static final boolean TRACK_OBJECT_ALLOCATIONS = VoxyCommon.isVerificationFlagOn("ensureTrackedObjectsAreFreed", true);
   public static final boolean TRACK_OBJECT_ALLOCATION_STACKS = VoxyCommon.isVerificationFlagOn("trackObjectAllocationStacks");
   private final TrackedObject.Ref ref;

   protected TrackedObject() {
      this(true);
   }

   protected TrackedObject(boolean shouldTrack) {
      this.ref = register(shouldTrack, this);
   }

   protected TrackedObject(Object forObj, boolean shouldTrack) {
      this.ref = register(shouldTrack, forObj);
   }

   protected void free0() {
      if (this.isFreed()) {
         throw new IllegalStateException("Object " + this + " was double freed.");
      } else {
         this.ref.freedRef[0] = true;
         if (TRACK_OBJECT_ALLOCATIONS && this.ref.cleanable != null) {
            this.ref.cleanable.clean();
         }
      }
   }

   public abstract void free();

   public void assertNotFreed() {
      if (this.isFreed()) {
         throw new IllegalStateException("Object " + this + " should not be free, but is");
      }
   }

   public boolean isFreed() {
      return this.ref.freedRef[0];
   }

   public static TrackedObject.Ref register(boolean track, Object obj) {
      boolean[] freed = new boolean[1];
      Cleanable cleanable = null;
      if (TRACK_OBJECT_ALLOCATIONS && track) {
         String clazz = obj.getClass().getName();
         Throwable trace;
         if (TRACK_OBJECT_ALLOCATION_STACKS) {
            trace = new Throwable();
            trace.fillInStackTrace();
         } else {
            trace = null;
         }

         cleanable = GlobalCleaner.CLEANER.register(obj, () -> {
            if (!freed[0]) {
               Logger.error("Object named: " + clazz + " was not freed, location at:\n", trace == null ? "Enable allocation stack tracing" : trace);
            }
         });
      }

      return new TrackedObject.Ref(cleanable, freed);
   }

   public static TrackedObject createTrackedObject(Object forObj) {
      return new TrackedObject.TrackedObjectObject(forObj);
   }

   public record Ref(Cleanable cleanable, boolean[] freedRef) {
   }

   public static final class TrackedObjectObject extends TrackedObject {
      private TrackedObjectObject(Object forObj) {
         super(forObj, true);
      }

      @Override
      public void free() {
         this.free0();
      }
   }
}
