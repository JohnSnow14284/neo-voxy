package me.cortex.voxy.client.taskbar;

import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.apache.commons.lang3.SystemUtils;

public abstract class Taskbar {
   public static final Taskbar.ITaskbar INSTANCE = createInterface();

   private static Taskbar.ITaskbar createInterface() {
      if (SystemUtils.IS_OS_WINDOWS) {
         try {
            return new WindowsTaskbar(Minecraft.getInstance().getWindow().handle());
         } catch (Exception var1) {
            Logger.error("Unable to create windows taskbar interface", var1);
            return new Taskbar.NoopTaskbar();
         }
      } else {
         return new Taskbar.NoopTaskbar();
      }
   }

   public interface ITaskbar {
      void setProgress(long var1, long var3);

      void setIsNone();

      void setIsProgression();

      void setIsPaused();

      void setIsError();
   }

   public static class NoopTaskbar implements Taskbar.ITaskbar {
      private NoopTaskbar() {
      }

      @Override
      public void setIsNone() {
      }

      @Override
      public void setProgress(long count, long outOf) {
      }

      @Override
      public void setIsPaused() {
      }

      @Override
      public void setIsProgression() {
      }

      @Override
      public void setIsError() {
      }
   }
}
