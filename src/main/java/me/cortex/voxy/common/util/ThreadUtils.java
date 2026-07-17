package me.cortex.voxy.common.util;

import me.cortex.voxy.common.Logger;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.Kernel32;

public class ThreadUtils {
   public static final int WIN32_THREAD_PRIORITY_TIME_CRITICAL = 15;
   public static final int WIN32_THREAD_PRIORITY_LOWEST = -2;
   public static final int WIN32_THREAD_MODE_BACKGROUND_BEGIN = 65536;
   public static final int WIN32_THREAD_MODE_BACKGROUND_END = 131072;
   public static final boolean isWindows = Platform.get() == Platform.WINDOWS;
   public static final boolean isLinux = Platform.get() == Platform.LINUX;
   private static final long SetThreadPriority;
   private static final long SetThreadSelectedCpuSetMasks;
   private static final long schedSetaffinity;

   public static boolean SetThreadSelectedCpuSetMasksWin32(long mask) {
      return SetThreadSelectedCpuSetMasksWin32(new long[]{mask}, new short[]{0});
   }

   public static boolean SetThreadSelectedCpuSetMasksWin32(long[] masks, short[] groups) {
      if (SetThreadSelectedCpuSetMasks == 0L || !isWindows) {
         return false;
      } else if (masks == null) {
         int retVal = JNI.invokePPCI(Kernel32.GetCurrentThread(), 0L, (short)0, SetThreadSelectedCpuSetMasks);
         if (retVal == 0) {
            throw new IllegalStateException();
         } else {
            return true;
         }
      } else if (masks.length != groups.length) {
         throw new IllegalArgumentException();
      } else {
         MemoryStack stack = MemoryStack.stackPush();

         boolean var6;
         try {
            long ptr = stack.ncalloc(16, masks.length, 16);
            MemoryUtil.memSet(ptr, 0, masks.length * 16L);

            for (int i = 0; i < masks.length; i++) {
               MemoryUtil.memPutLong(ptr + i * 16L, masks[i]);
               MemoryUtil.memPutShort(ptr + i * 16L + 8L, groups[i]);
            }

            int retVal = JNI.invokePPCI(Kernel32.GetCurrentThread(), ptr, (short)masks.length, SetThreadSelectedCpuSetMasks);
            if (retVal == 0) {
               throw new IllegalStateException();
            }

            var6 = true;
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

         return var6;
      }
   }

   public static boolean SetSelfThreadPriorityWin32(int priority) {
      if (SetThreadPriority != 0L && isWindows) {
         if (JNI.callPI(Kernel32.GetCurrentThread(), priority, SetThreadPriority) == 0) {
            throw new IllegalStateException("Operation failed");
         } else {
            return true;
         }
      } else {
         return false;
      }
   }

   public static boolean schedSetaffinityLinux(long[] masks) {
      if (schedSetaffinity != 0L && !isWindows) {
         MemoryStack stack = MemoryStack.stackPush();

         boolean var5;
         try {
            long ptr = stack.ncalloc(8, masks.length, 8);

            for (int i = 0; i < masks.length; i++) {
               MemoryUtil.memPutLong(ptr + i * 8L, masks[i]);
            }

            int retVal = JNI.invokePPI(0, masks.length * 8L, ptr, schedSetaffinity);
            if (retVal != 0) {
               throw new IllegalStateException();
            }

            var5 = true;
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

         return var5;
      } else {
         return false;
      }
   }

   static {
      if (isWindows) {
         SetThreadPriority = Kernel32.getLibrary().getFunctionAddress("SetThreadPriority");
         SetThreadSelectedCpuSetMasks = Kernel32.getLibrary().getFunctionAddress("SetThreadSelectedCpuSetMasks");
      } else {
         SetThreadPriority = 0L;
         SetThreadSelectedCpuSetMasks = 0L;
      }

      if (Platform.get() == Platform.LINUX) {
         long fn = 0L;

         try {
            SharedLibrary libc = APIUtil.apiCreateLibrary("libc.so.6");
            fn = APIUtil.apiGetFunctionAddress(libc, "sched_setaffinity");
         } catch (Exception var3) {
            Logger.error(var3);
         }

         schedSetaffinity = fn;
      } else {
         schedSetaffinity = 0L;
      }
   }
}
