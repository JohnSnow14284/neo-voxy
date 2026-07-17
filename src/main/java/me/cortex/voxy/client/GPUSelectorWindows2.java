package me.cortex.voxy.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.GDI32;
import org.lwjgl.system.windows.Kernel32;

public class GPUSelectorWindows2 {
   private static final long D3DKMTSetProperties = APIUtil.apiGetFunctionAddressOptional(GDI32.getLibrary(), "D3DKMTSetProperties");
   private static final long D3DKMTEnumAdapters2 = APIUtil.apiGetFunctionAddressOptional(GDI32.getLibrary(), "D3DKMTEnumAdapters2");
   private static final long D3DKMTCloseAdapter = APIUtil.apiGetFunctionAddressOptional(GDI32.getLibrary(), "D3DKMTCloseAdapter");
   private static final long D3DKMTQueryAdapterInfo = APIUtil.apiGetFunctionAddressOptional(GDI32.getLibrary(), "D3DKMTQueryAdapterInfo");
   private static final int[] HDC_STUB = new int[]{
      72,
      131,
      193,
      12,
      72,
      184,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      31,
      72,
      137,
      1,
      72,
      184,
      255,
      255,
      255,
      255,
      255,
      255,
      255,
      47,
      81,
      255,
      208,
      89,
      139,
      65,
      8,
      137,
      65,
      252,
      72,
      49,
      192,
      137,
      65,
      8,
      195
   };
   private static final long D3DKMTOpenAdapterFromLuid = APIUtil.apiGetFunctionAddressOptional(GDI32.getLibrary(), "D3DKMTOpenAdapterFromLuid");
   private static final long D3DKMTOpenAdapterFromHdc = APIUtil.apiGetFunctionAddressOptional(GDI32.getLibrary(), "D3DKMTOpenAdapterFromHdc");
   private static final long VirtualProtect = APIUtil.apiGetFunctionAddressOptional(Kernel32.getLibrary(), "VirtualProtect");

   private static int setPCIProperties(int type, int vendor, int device, int subSys) {
      MemoryStack stack = MemoryStack.stackPush();

      int var6;
      try {
         ByteBuffer buff = stack.calloc(16).order(ByteOrder.nativeOrder());
         buff.putInt(0, vendor);
         buff.putInt(4, device);
         buff.putInt(8, subSys);
         buff.putInt(12, 0);
         var6 = setProperties(type, buff);
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

   private static int setProperties(int type, ByteBuffer payload) {
      if (D3DKMTSetProperties == 0L) {
         return -1;
      } else {
         MemoryStack stack = MemoryStack.stackPush();

         int var4;
         try {
            ByteBuffer buff = stack.calloc(24).order(ByteOrder.nativeOrder());
            buff.putInt(0, type);
            buff.putInt(4, payload.remaining());
            buff.putLong(16, MemoryUtil.memAddress(payload));
            var4 = JNI.callPI(MemoryUtil.memAddress(buff), D3DKMTSetProperties);
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

         return var4;
      }
   }

   private static int query(int handle, int type, ByteBuffer payload) {
      if (D3DKMTQueryAdapterInfo == 0L) {
         return -1;
      } else {
         MemoryStack stack = MemoryStack.stackPush();

         int var5;
         try {
            ByteBuffer buff = stack.calloc(20).order(ByteOrder.nativeOrder());
            buff.putInt(0, handle);
            buff.putInt(4, type);
            buff.putLong(8, MemoryUtil.memAddress(payload));
            buff.putInt(16, payload.remaining());
            var5 = JNI.callPI(MemoryUtil.memAddress(buff), D3DKMTQueryAdapterInfo);
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
      }
   }

   private static int closeHandle(int handle) {
      if (D3DKMTCloseAdapter == 0L) {
         return -1;
      } else {
         MemoryStack stack = MemoryStack.stackPush();

         int var3;
         try {
            ByteBuffer buff = stack.calloc(4).order(ByteOrder.nativeOrder());
            buff.putInt(0, handle);
            var3 = JNI.callPI(MemoryUtil.memAddress(buff), D3DKMTCloseAdapter);
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

   private static int queryAdapterType(int handle, int[] out) {
      MemoryStack stack = MemoryStack.stackPush();

      int var5;
      label43: {
         try {
            ByteBuffer buff = stack.calloc(4).order(ByteOrder.nativeOrder());
            int ret;
            if ((ret = query(handle, 15, buff)) < 0) {
               var5 = ret;
               break label43;
            }

            out[0] = buff.getInt(0);
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

         return 0;
      }

      if (stack != null) {
         stack.close();
      }

      return var5;
   }

   private static int queryAdapterIcd(int handle, String[] out) {
      MemoryStack stack = MemoryStack.stackPush();

      int len;
      label43: {
         try {
            ByteBuffer buff = stack.calloc(528).order(ByteOrder.nativeOrder());
            int ret;
            if ((ret = query(handle, 2, buff)) < 0) {
               len = ret;
               break label43;
            }

            len = Math.min(MemoryUtil.memLengthNT2(buff), 520);
            out[0] = MemoryUtil.memUTF16(buff.limit(len));
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

         return 0;
      }

      if (stack != null) {
         stack.close();
      }

      return len;
   }

   private static int queryPCIAddress(int handle, int index, GPUSelectorWindows2.PCIDeviceId[] deviceOut) {
      int ret = 0;
      MemoryStack stack = MemoryStack.stackPush();

      int var10;
      label43: {
         try {
            ByteBuffer buff = stack.calloc(28).order(ByteOrder.nativeOrder());
            buff.putInt(0, index);
            if ((ret = query(handle, 31, buff)) < 0) {
               var10 = ret;
               break label43;
            }

            deviceOut[0] = new GPUSelectorWindows2.PCIDeviceId(
               buff.getInt(4), buff.getInt(8), buff.getInt(12), buff.getInt(16), buff.getInt(20), buff.getInt(24)
            );
            var10 = 0;
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

         return var10;
      }

      if (stack != null) {
         stack.close();
      }

      return var10;
   }

   private static int enumAdapters(Consumer<GPUSelectorWindows2.AdapterInfo> consumer) {
      if (D3DKMTEnumAdapters2 == 0L) {
         return -1;
      } else {
         int ret = 0;
         MemoryStack stack = MemoryStack.stackPush();

         int adapterCount;
         label83: {
            int var24;
            label84: {
               try {
                  ByteBuffer query = stack.calloc(16).order(ByteOrder.nativeOrder());
                  if ((ret = JNI.callPI(MemoryUtil.memAddress(query), D3DKMTEnumAdapters2)) < 0) {
                     adapterCount = ret;
                     break label83;
                  }

                  adapterCount = query.getInt(0);
                  ByteBuffer adapterList = stack.calloc(20 * adapterCount).order(ByteOrder.nativeOrder());
                  query.putLong(8, MemoryUtil.memAddress(adapterList));
                  if ((ret = JNI.callPI(MemoryUtil.memAddress(query), D3DKMTEnumAdapters2)) < 0) {
                     var24 = ret;
                     break label84;
                  }

                  adapterCount = query.getInt(0);

                  for (int adapterIndex = 0; adapterIndex < adapterCount; adapterIndex++) {
                     ByteBuffer adapter = adapterList.slice(adapterIndex * 20, 20).order(ByteOrder.nativeOrder());
                     int handle = adapter.getInt(0);
                     long luid = adapter.getLong(4);
                     int[] type = new int[1];
                     if ((ret = queryAdapterType(handle, type)) < 0) {
                        Logger.error("Query type error: " + ret);
                        if (closeHandle(handle) < 0) {
                           throw new IllegalStateException();
                        }
                     } else {
                        String[] icd = new String[1];
                        if ((ret = queryAdapterIcd(handle, icd)) < 0) {
                           Logger.error("Query icd error: " + ret);
                           if (closeHandle(handle) < 0) {
                              throw new IllegalStateException();
                           }
                        } else {
                           GPUSelectorWindows2.PCIDeviceId[] out = new GPUSelectorWindows2.PCIDeviceId[1];
                           if ((ret = queryPCIAddress(handle, 0, out)) < 0) {
                              Logger.error("Query pci error: " + ret);
                              if (closeHandle(handle) < 0) {
                                 throw new IllegalStateException();
                              }
                           } else {
                              int subSys = out[0].subSystem << 16 | out[0].subVendor;
                              consumer.accept(new GPUSelectorWindows2.AdapterInfo(icd[0], type[0], luid, out[0].vendor, out[0].device, subSys));
                              if (closeHandle(handle) < 0) {
                                 throw new IllegalStateException();
                              }
                           }
                        }
                     }
                  }
               } catch (Throwable var16) {
                  if (stack != null) {
                     try {
                        stack.close();
                     } catch (Throwable var15) {
                        var16.addSuppressed(var15);
                     }
                  }

                  throw var16;
               }

               if (stack != null) {
                  stack.close();
               }

               return 0;
            }

            if (stack != null) {
               stack.close();
            }

            return var24;
         }

         if (stack != null) {
            stack.close();
         }

         return adapterCount;
      }
   }

   private static void insertLong(long l, byte[] out, int offset) {
      for (int i = 0; i < 8; i++) {
         out[i + offset] = (byte)(l & 255L);
         l >>= 8;
      }
   }

   private static byte[] createFinishedHDCStub(long luid) {
      byte[] stub = new byte[HDC_STUB.length];

      for (int i = 0; i < stub.length; i++) {
         stub[i] = (byte)HDC_STUB[i];
      }

      insertLong(luid, stub, 6);
      insertLong(D3DKMTOpenAdapterFromLuid, stub, 19);
      return stub;
   }

   private static byte[] toByteArray(int... array) {
      byte[] res = new byte[array.length];

      for (int i = 0; i < array.length; i++) {
         res[i] = (byte)array[i];
      }

      return res;
   }

   private static void VirtualProtect(long addr, long size) {
      MemoryStack stack = MemoryStack.stackPush();

      try {
         ByteBuffer oldProtection = stack.calloc(4);
         JNI.callPPPPI(addr, size, 64L, MemoryUtil.memAddress(oldProtection), VirtualProtect);
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
   }

   private static void memcpy(long ptr, byte[] data) {
      for (int i = 0; i < data.length; i++) {
         MemoryUtil.memPutByte(ptr + i, data[i]);
      }
   }

   private static void installHDCStub(long adapterLuid) {
      if (D3DKMTOpenAdapterFromHdc != 0L && VirtualProtect != 0L && D3DKMTOpenAdapterFromLuid != 0L) {
         Logger.info("AdapterLuid callback at: " + Long.toHexString(D3DKMTOpenAdapterFromLuid));
         byte[] stub = createFinishedHDCStub(adapterLuid);
         VirtualProtect(D3DKMTOpenAdapterFromHdc, stub.length);
         memcpy(D3DKMTOpenAdapterFromHdc, stub);
      }
   }

   private static byte[] createIntelStub(long origA, long origB, long jmpA, long jmpB) {
      byte[] stub = toByteArray(
         254,
         13,
         99,
         0,
         0,
         0,
         128,
         61,
         92,
         0,
         0,
         0,
         2,
         117,
         7,
         72,
         49,
         192,
         72,
         247,
         208,
         195,
         72,
         184,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         1,
         72,
         139,
         13,
         67,
         0,
         0,
         0,
         72,
         137,
         8,
         72,
         139,
         13,
         65,
         0,
         0,
         0,
         72,
         137,
         72,
         8,
         128,
         61,
         45,
         0,
         0,
         0,
         0,
         116,
         29,
         80,
         255,
         208,
         88,
         72,
         139,
         13,
         49,
         0,
         0,
         0,
         72,
         137,
         8,
         72,
         139,
         13,
         47,
         0,
         0,
         0,
         72,
         137,
         72,
         8,
         72,
         49,
         192,
         195,
         72,
         139,
         65,
         8,
         199,
         0,
         0,
         0,
         0,
         0,
         72,
         49,
         192,
         195,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0
      );
      insertLong(D3DKMTQueryAdapterInfo, stub, 24);
      stub[105] = 3;
      insertLong(origA, stub, 106);
      insertLong(origB, stub, 114);
      insertLong(jmpA, stub, 122);
      insertLong(jmpB, stub, 130);
      return stub;
   }

   private static byte[] createSimpleStub(long origA, long origB) {
      byte[] stub = toByteArray(
         72,
         184,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         72,
         139,
         13,
         21,
         0,
         0,
         0,
         72,
         137,
         8,
         72,
         139,
         13,
         19,
         0,
         0,
         0,
         72,
         137,
         72,
         8,
         72,
         49,
         192,
         72,
         247,
         208,
         195,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0,
         0
      );
      insertLong(D3DKMTQueryAdapterInfo, stub, 2);
      insertLong(origA, stub, 38);
      insertLong(origB, stub, 46);
      return stub;
   }

   private static void installQueryStub(boolean installIntelBypass) {
      if (D3DKMTQueryAdapterInfo != 0L && VirtualProtect != 0L) {
         VirtualProtect(D3DKMTQueryAdapterInfo, 16L);
         int MAX_STUB_SIZE = 1024;
         long stubPtr = MemoryUtil.nmemAlloc(MAX_STUB_SIZE);
         VirtualProtect(stubPtr, MAX_STUB_SIZE);
         Logger.info("Do stub at: " + Long.toHexString(stubPtr));
         long origA = MemoryUtil.memGetLong(D3DKMTQueryAdapterInfo);
         long origB = MemoryUtil.memGetLong(D3DKMTQueryAdapterInfo + 8L);
         byte[] jmpStub = new byte[]{72, -72, 0, 0, 0, 0, 0, 0, 0, 0, -1, -32};
         insertLong(stubPtr, jmpStub, 2);
         memcpy(D3DKMTQueryAdapterInfo, jmpStub);
         Logger.info("D3DKMTQueryAdapterInfo at: " + Long.toHexString(D3DKMTQueryAdapterInfo));
         long jmpA = MemoryUtil.memGetLong(D3DKMTQueryAdapterInfo);
         long jmpB = MemoryUtil.memGetLong(D3DKMTQueryAdapterInfo + 8L);
         byte[] stub;
         if (installIntelBypass) {
            stub = createIntelStub(origA, origB, jmpA, jmpB);
         } else {
            stub = createSimpleStub(origA, origB);
         }

         memcpy(stubPtr, stub);
         Logger.info("QueryAdapterInfo stubs installed");
      }
   }

   public static void doSelector(int index) {
      List<GPUSelectorWindows2.AdapterInfo> adapters = new ArrayList<>();
      if (enumAdapters(adapterx -> {
         if ((adapterx.type & 5) == 1) {
            adapters.add(adapterx);
         }
      }) >= 0) {
         for (GPUSelectorWindows2.AdapterInfo adapter : adapters) {
            Logger.error(adapter.toString());
         }

         GPUSelectorWindows2.AdapterInfo adapter = adapters.get(index);
         installHDCStub(adapter.luid);
         installQueryStub(adapter.icdPath.matches("\\\\ig[a-z0-9]+icd(32|64)\\.dll$"));
         setPCIProperties(1, adapter.vendor, adapter.device, adapter.subSystem);
         setPCIProperties(2, adapter.vendor, adapter.device, adapter.subSystem);
      }
   }

   private record AdapterInfo(String icdPath, int type, long luid, int vendor, int device, int subSystem) {
      @Override
      public String toString() {
         String LUID = Integer.toHexString((int)(this.luid >>> 32 & 4294967295L)) + "-" + Integer.toHexString((int)(this.luid & 4294967295L));
         return "{type=%s, luid=%s, vendor=%s, device=%s, subSys=%s, icd=\"%s\"}"
            .formatted(
               Integer.toString(this.type),
               LUID,
               Integer.toHexString(this.vendor),
               Integer.toHexString(this.device),
               Integer.toHexString(this.subSystem),
               this.icdPath
            );
      }
   }

   private record PCIDeviceId(int vendor, int device, int subVendor, int subSystem, int revision, int busType) {
   }
}
