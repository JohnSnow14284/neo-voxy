package me.cortex.voxy.client.taskbar;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.W32Errors;
import com.sun.jna.platform.win32.COM.COMInvoker;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.PointerByReference;
import org.lwjgl.glfw.GLFWNativeWin32;

public class WindowsTaskbar extends COMInvoker implements Taskbar.ITaskbar {
   private final HWND hwnd;

   WindowsTaskbar(long windowId) {
      PointerByReference itaskbar3res = new PointerByReference();
      if (W32Errors.FAILED(
         Ole32.INSTANCE
            .CoCreateInstance(new GUID("56FDF344-FD6D-11d0-958A-006097C9A090"), null, 21, new GUID("EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF"), itaskbar3res)
      )) {
         throw new IllegalStateException("Failed to create ITaskbar3");
      } else {
         this.setPointer(itaskbar3res.getValue());
         this.hwnd = new HWND(new Pointer(GLFWNativeWin32.glfwGetWin32Window(windowId)));
         this.invokeNative(3);
      }
   }

   private void invokeNative(int ventry, Object... objects) {
      Object[] args = new Object[objects.length + 1];
      args[0] = this.getPointer();
      System.arraycopy(objects, 0, args, 1, objects.length);
      if (W32Errors.FAILED((HRESULT)this._invokeNativeObject(ventry, args, HRESULT.class))) {
         throw new IllegalStateException("Failed to invoke vtable: " + ventry);
      }
   }

   public void close() {
      this.invokeNative(10, this.hwnd, 0);
      this.invokeNative(2);
      this.setPointer(null);
   }

   @Override
   public void setIsNone() {
      this.invokeNative(10, this.hwnd, 0);
   }

   @Override
   public void setProgress(long count, long outOf) {
      this.invokeNative(9, this.hwnd, count, outOf);
   }

   @Override
   public void setIsPaused() {
      this.invokeNative(10, this.hwnd, 8);
   }

   @Override
   public void setIsProgression() {
      this.invokeNative(10, this.hwnd, 2);
   }

   @Override
   public void setIsError() {
      this.invokeNative(10, this.hwnd, 2);
   }
}
