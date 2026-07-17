package me.cortex.voxy.client.core.model;

import java.util.Arrays;
import net.caffeinemc.mods.sodium.client.util.color.ColorSRGB;
import net.minecraft.util.ARGB;

public class TextureUtils {
   public static final int WRITE_CHECK_STENCIL = 1;
   public static final int WRITE_CHECK_DEPTH = 2;
   public static final int WRITE_CHECK_ALPHA = 3;
   public static final int DEPTH_MODE_AVG = 1;
   public static final int DEPTH_MODE_MAX = 2;
   public static final int DEPTH_MODE_MIN = 3;

   public static int getWrittenPixelCount(ColourDepthTextureData texture, int checkMode) {
      int count = 0;

      for (int i = 0; i < texture.colour().length; i++) {
         count += wasPixelWritten(texture, checkMode, i) ? 1 : 0;
      }

      return count;
   }

   public static boolean isSolid(ColourDepthTextureData texture) {
      for (int pixel : texture.colour()) {
         if ((pixel >> 24 & 0xFF) != 255) {
            return false;
         }
      }

      return true;
   }

   private static boolean wasPixelWritten(ColourDepthTextureData data, int mode, int index) {
      if (mode == 1) {
         return (data.depth()[index] & 0xFF) != 0;
      } else if (mode == 2) {
         return data.depth()[index] >>> 8 != 16777215;
      } else if (mode == 3) {
         return (data.colour()[index] >>> 24 & 0xFF) > 1;
      } else {
         throw new IllegalArgumentException();
      }
   }

   public static boolean hasTranslucentPixel(ColourDepthTextureData data) {
      for (int i = 0; i < data.colour().length; i++) {
         int alpha = data.colour()[i] >>> 24;
         int depth = data.depth()[i];
         if ((depth & 0xFF) != 0 && alpha != 0 && alpha != 255) {
            return true;
         }
      }

      return false;
   }

   public static boolean isSolidWhereDrawn(ColourDepthTextureData data) {
      for (int i = 0; i < data.colour().length; i++) {
         int alpha = data.colour()[i] >>> 24;
         int depth = data.depth()[i];
         if ((depth & 0xFF) != 0 && alpha != 255) {
            return false;
         }
      }

      return true;
   }

   public static int computeFaceTint(ColourDepthTextureData texture, int checkMode) {
      boolean allTinted = true;
      boolean someTinted = false;
      boolean wasWriten = false;
      int[] colourData = texture.colour();
      int[] depthData = texture.depth();

      for (int i = 0; i < colourData.length; i++) {
         if (wasPixelWritten(texture, checkMode, i) && (colourData[i] & 16777215) != 0 && colourData[i] >>> 24 != 0) {
            boolean pixelTinited = (depthData[i] & 128) != 0;
            wasWriten |= true;
            allTinted &= pixelTinited;
            someTinted |= pixelTinited;
         }
      }

      if (!wasWriten) {
         return 0;
      } else {
         return someTinted ? (allTinted ? 3 : 2) : 1;
      }
   }

   public static float computeDepth(ColourDepthTextureData texture, int mode, int checkMode) {
      int[] colourData = texture.colour();
      int[] depthData = texture.depth();
      long a = 0L;
      long b = 0L;
      if (mode == 3) {
         a = Long.MAX_VALUE;
      }

      if (mode == 2) {
         a = Long.MIN_VALUE;
      }

      for (int i = 0; i < colourData.length; i++) {
         if (wasPixelWritten(texture, checkMode, i)) {
            int depth = depthData[i] >>> 8;
            if (mode == 1) {
               a++;
               b += depth;
            } else if (mode == 2) {
               a = Math.max(a, (long)depth);
            } else if (mode == 3) {
               a = Math.min(a, (long)depth);
            }
         }
      }

      if (mode == 1) {
         return a == 0L ? -1.0F : u2fdepth((int)(b / a));
      } else if (mode == 2) {
         return a == Long.MIN_VALUE ? -1.0F : u2fdepth((int)a);
      } else if (mode != 3) {
         throw new IllegalArgumentException();
      } else {
         return a == Long.MAX_VALUE ? -1.0F : u2fdepth((int)a);
      }
   }

   private static float u2fdepth(int depth) {
      return (float)(depth / 1.6777215E7);
   }

   public static long[] generateMask(ColourDepthTextureData data, int checkMode) {
      return generateMask(data, checkMode, new long[data.width() * data.height() / 64]);
   }

   public static long[] generateMask(ColourDepthTextureData data, int checkMode, long[] outMsk) {
      Arrays.fill(outMsk, 0L);
      int i = 0;

      for (int y = 0; y < data.height(); y++) {
         for (int x = 0; x < data.width(); x++) {
            if (wasPixelWritten(data, checkMode, i)) {
               outMsk[i / 64] = outMsk[i / 64] | 1L << (i & 63);
            }

            i++;
         }
      }

      return outMsk;
   }

   public static int[] computeBounds(ColourDepthTextureData data, int checkMode) {
      int minX = 0;

      label74:
      do {
         for (int y = 0; y < data.height(); y++) {
            int idx = minX + y * data.width();
            if (wasPixelWritten(data, checkMode, idx)) {
               break label74;
            }
         }
      } while (++minX != data.width());

      int maxX = data.width() - 1;

      label63:
      do {
         for (int yx = data.height() - 1; yx != -1; yx--) {
            int idx = maxX + yx * data.width();
            if (wasPixelWritten(data, checkMode, idx)) {
               break label63;
            }
         }
      } while (--maxX != -1);

      int minY = 0;

      label52:
      do {
         for (int x = 0; x < data.width(); x++) {
            int idx = minY * data.height() + x;
            if (wasPixelWritten(data, checkMode, idx)) {
               break label52;
            }
         }
      } while (++minY != data.height());

      int maxY = data.height() - 1;

      do {
         for (int xx = data.width() - 1; xx != -1; xx--) {
            int idx = maxY * data.height() + xx;
            if (wasPixelWritten(data, checkMode, idx)) {
               return new int[]{minX, maxX, minY, maxY};
            }
         }
      } while (--maxY != -1);

      return new int[]{minX, maxX, minY, maxY};
   }

   public static int mipColours(boolean darkend, int C00, int C01, int C10, int C11) {
      darkend = !darkend;
      float r = 0.0F;
      float g = 0.0F;
      float b = 0.0F;
      float a = 0.0F;
      if (darkend || C00 >>> 24 != 0) {
         r += ColorSRGB.srgbToLinear(C00 >> 0 & 0xFF);
         g += ColorSRGB.srgbToLinear(C00 >> 8 & 0xFF);
         b += ColorSRGB.srgbToLinear(C00 >> 16 & 0xFF);
         a += darkend ? C00 >>> 24 : ColorSRGB.srgbToLinear(C00 >>> 24);
      }

      if (darkend || C01 >>> 24 != 0) {
         r += ColorSRGB.srgbToLinear(C01 >> 0 & 0xFF);
         g += ColorSRGB.srgbToLinear(C01 >> 8 & 0xFF);
         b += ColorSRGB.srgbToLinear(C01 >> 16 & 0xFF);
         a += darkend ? C01 >>> 24 : ColorSRGB.srgbToLinear(C01 >>> 24);
      }

      if (darkend || C10 >>> 24 != 0) {
         r += ColorSRGB.srgbToLinear(C10 >> 0 & 0xFF);
         g += ColorSRGB.srgbToLinear(C10 >> 8 & 0xFF);
         b += ColorSRGB.srgbToLinear(C10 >> 16 & 0xFF);
         a += darkend ? C10 >>> 24 : ColorSRGB.srgbToLinear(C10 >>> 24);
      }

      if (darkend || C11 >>> 24 != 0) {
         r += ColorSRGB.srgbToLinear(C11 >> 0 & 0xFF);
         g += ColorSRGB.srgbToLinear(C11 >> 8 & 0xFF);
         b += ColorSRGB.srgbToLinear(C11 >> 16 & 0xFF);
         a += darkend ? C11 >>> 24 : ColorSRGB.srgbToLinear(C11 >>> 24);
      }

      return ColorSRGB.linearToSrgb(r / 4.0F, g / 4.0F, b / 4.0F, darkend ? (int)a / 4 : ARGB.linearToSrgbChannel(a / 4.0F));
   }
}
