package me.cortex.voxy.client.core.model.bakery;

import java.util.Arrays;
import net.caffeinemc.mods.sodium.api.util.ColorMixer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;

public class SoftwareRasterizer {
   private static final int INTEGER_BITS = 9;
   private static final int TOTAL_INTEGER_BITS = 10;
   private static final int FIXED_POINT_BITS = 22;
   private static final long FIXED_POINT_BIT_SCALE = 4194303L;
   private final Vector4f scratch = new Vector4f();
   private final Vector3f scratch1 = new Vector3f();
   private final Vector3f scratch2 = new Vector3f();
   private final Vector3f scratch3 = new Vector3f();
   private final Vector3f scratch4 = new Vector3f();
   private final Vector3f qmuv1 = new Vector3f();
   private final Vector3f qmuv2 = new Vector3f();
   private final Vector3f qmuv3 = new Vector3f();
   private final Vector3f qmuv4 = new Vector3f();
   private final Vector3i scratchR1 = new Vector3i();
   private final Vector3i scratchR2 = new Vector3i();
   private final Vector3i scratchR3 = new Vector3i();
   private final Vector3f a1 = new Vector3f();
   private final Vector3f a2 = new Vector3f();
   private final Vector3f a3 = new Vector3f();
   private static final long DEPTH_MASK = -1099511627776L;
   private static final long CLEAR_VALUE = -1099511627776L;
   private final int targetSize;
   private final long[] framebuffer;
   private boolean cullBackFace;
   private boolean doTheBlending;
   private int samplerWidth;
   private int samplerHeight;
   private int[] samplerTexture;

   public SoftwareRasterizer(int targetSize) {
      int testExpect = targetSize * targetSize;
      int testGot = fromFixed2Int(toFixed(targetSize * targetSize));
      if (testExpect != testGot) {
         throw new IllegalStateException("Target resolution not supported, not enough precision bits. got: " + testGot + ", expect: " + testExpect);
      } else {
         this.targetSize = targetSize;
         this.framebuffer = new long[targetSize * targetSize];
      }
   }

   public void setFaceCull(boolean isBackFaceCulling) {
      this.cullBackFace = isBackFaceCulling;
   }

   public void setBlending(boolean blending) {
      this.doTheBlending = blending;
   }

   public void setSamplerTexture(int[] texture, int width, int height) {
      if (texture.length != width * height) {
         throw new IllegalArgumentException();
      } else {
         this.samplerTexture = texture;
         this.samplerWidth = width;
         this.samplerHeight = height;
      }
   }

   private int sampleTexture(float u, float v) {
      int pu = Math.clamp((long)Math.round(u * this.samplerWidth - 0.5F), 0, this.samplerWidth - 1);
      int pv = Math.clamp((long)Math.round(v * this.samplerHeight - 0.5F), 0, this.samplerHeight - 1);
      return this.samplerTexture[this.samplerWidth * pv + pu];
   }

   public void clear() {
      Arrays.fill(this.framebuffer, -1099511627776L);
   }

   public void raster(Matrix4f mvp, ReuseVertexConsumer vertices) {
      this.raster(mvp, vertices.getAddress(), vertices.quadCount());
   }

   public void raster(Matrix4f mvp, long verticesAddr, int quadCount) {
      if (quadCount != 0) {
         for (int i = 0; i < quadCount; i++) {
            this.rasterQuad(mvp, verticesAddr + 96L * i);
         }
      }
   }

   private void rasterQuad(Matrix4f transform, long addr) {
      this.loadTransformPos(transform, addr, 0, this.scratch1, this.qmuv1);
      this.loadTransformPos(transform, addr, 1, this.scratch2, this.qmuv2);
      this.loadTransformPos(transform, addr, 2, this.scratch3, this.qmuv3);
      this.loadTransformPos(transform, addr, 3, this.scratch4, this.qmuv4);
      toFixed(this.scratchR1, this.scratch1);
      toFixed(this.scratchR2, this.scratch2);
      toFixed(this.scratchR3, this.scratch3);
      this.a1.set(this.qmuv1);
      this.a2.set(this.qmuv2);
      this.a3.set(this.qmuv3);
      this.rasterTriangle(false);
      toFixed(this.scratchR1, this.scratch3);
      toFixed(this.scratchR2, this.scratch4);
      toFixed(this.scratchR3, this.scratch1);
      this.a1.set(this.qmuv3);
      this.a2.set(this.qmuv4);
      this.a3.set(this.qmuv1);
      this.rasterTriangle(true);
   }

   private void rasterTriangle(boolean orZero) {
      Vector3i v1 = this.scratchR1;
      Vector3i v2 = this.scratchR2;
      Vector3i v3 = this.scratchR3;
      int area = edge(v1, v2, v3);
      if (area < 0 != this.cullBackFace) {
         if (!(Math.abs(fromFixed(area)) < 0.001)) {
            int minX = fromFixed2Int(Math.max(Math.min(Math.min(v1.x, v2.x), v3.x), 0));
            int maxX = fromFixed2Int(Math.min(Math.max(Math.max(v1.x, v2.x), v3.x), toFixed(this.targetSize - 1)));
            int minY = fromFixed2Int(Math.max(Math.min(Math.min(v1.y, v2.y), v3.y), 0));
            int maxY = fromFixed2Int(Math.min(Math.max(Math.max(v1.y, v2.y), v3.y), toFixed(this.targetSize - 1)));

            for (int py = minY; py <= maxY; py++) {
               for (int px = minX; px <= maxX; px++) {
                  int cx = toFixed(px) + toFixed(0.5F);
                  int cy = toFixed(py) + toFixed(0.5F);
                  int w1 = fixedDiv(edge(v2, v3, cx, cy), area);
                  int w2 = fixedDiv(edge(v3, v1, cx, cy), area);
                  int w3 = toFixed(1.0F) - w1 - w2;
                  if (w1 > 0 && w2 > 0 && w3 > 0 || orZero && w1 >= 0 && w2 >= 0 && w3 >= 0) {
                     float b1 = fromFixed(w1);
                     float b2 = fromFixed(w2);
                     float b3 = fromFixed(w3);
                     float z = Math.fma(b1, fromFixed(this.scratchR1.z), Math.fma(b2, fromFixed(this.scratchR2.z), b3 * fromFixed(this.scratchR3.z)));
                     this.rasterPixel(px + py * this.targetSize, b1, b2, b3, z);
                  }
               }
            }
         }
      }
   }

   private void rasterPixel(int index, float b1, float b2, float b3, float z) {
      z = Math.fma(z, 0.5F, 0.5F);
      if (z < 0.0F && -1.0E-6F <= z) {
         z = 0.0F;
      }

      if (!(z < 0.0F) && !(z > 1.0F)) {
         int meta = Float.floatToRawIntBits(this.a1.x);
         float u = Math.fma(b1, this.a1.y, Math.fma(b2, this.a2.y, b3 * this.a3.y));
         float v = Math.fma(b1, this.a1.z, Math.fma(b2, this.a2.z, b3 * this.a3.z));
         int colour = this.sampleTexture(u, v);
         int ALPHA_CUTOFF_THRESHOLD = 0;
         if ((meta & 1) == 0 || colour >>> 24 > 0) {
            this.framebuffer[index] = this.framebuffer[index] + 4294967296L;
            long depthVal = (long)(z * 1.6777215E7) << 40;
            if (depthVal == -1099511627776L) {
               depthVal--;
            }

            if (Long.compareUnsigned(this.framebuffer[index], depthVal) > 0) {
               this.framebuffer[index] = this.framebuffer[index] & 1099511627775L;
               this.framebuffer[index] = this.framebuffer[index] | depthVal;
               this.framebuffer[index] = this.framebuffer[index] & -549755813889L;
               this.framebuffer[index] = this.framebuffer[index] | (long)(meta & 4) << 37;
               int srcColour = (int)this.framebuffer[index];
               this.framebuffer[index] = this.framebuffer[index] & ~Integer.toUnsignedLong(-1);
               if (this.doTheBlending) {
                  colour = doBlending(srcColour, colour);
               }

               this.framebuffer[index] = this.framebuffer[index] | Integer.toUnsignedLong(colour);
            }
         }
      }
   }

   private static int doBlending(int scr, int dst) {
      int srcAlpha = scr >>> 24 & 0xFF;
      if (srcAlpha == 0) {
         return dst;
      } else {
         int dstAlpha = dst >>> 24 & 0xFF;
         scr &= 16777215;
         dst &= 16777215;
         int blendAlpha = Math.min(255, srcAlpha + (dstAlpha * (255 - srcAlpha) >> 8));
         int blend = ColorMixer.mix(dst, scr, dstAlpha);
         return blend | blendAlpha << 24;
      }
   }

   private static int addRGB(int a, int b) {
      return Math.min(255, (a & 0xFF) + (b & 0xFF)) | Math.min(65280, (a & 0xFF00) + (b & 0xFF00)) | Math.min(16711680, (a & 0xFF0000) + (b & 0xFF0000));
   }

   private static float edge(Vector3f a, Vector3f b, Vector3f c) {
      return (c.x - a.x) * (b.y - a.y) - (c.y - a.y) * (b.x - a.x);
   }

   private static float edge(Vector3f a, Vector3f b, float cx, float cy) {
      return (cx - a.x) * (b.y - a.y) - (cy - a.y) * (b.x - a.x);
   }

   private static int edge(Vector3i a, Vector3i b, Vector3i c) {
      return fixedMul(c.x - a.x, b.y - a.y) - fixedMul(c.y - a.y, b.x - a.x);
   }

   private static int edge(Vector3i a, Vector3i b, int cx, int cy) {
      return fixedMul(cx - a.x, b.y - a.y) - fixedMul(cy - a.y, b.x - a.x);
   }

   private static int toFixed(float a) {
      return (int)(a * 4194303.0);
   }

   private static int toFixed(int a) {
      return (int)(a * 4194303L);
   }

   private static void toFixed(Vector3i dst, Vector3f src) {
      dst.set(toFixed(src.x), toFixed(src.y), toFixed(src.z));
   }

   private static float fromFixed(int a) {
      return (float)(a / 4194303.0);
   }

   private static int fromFixed2Int(int a) {
      return (int)(a / 4194303L);
   }

   private static void fromFixed(Vector3f dst, Vector3i src) {
      dst.set(fromFixed(src.x), fromFixed(src.y), fromFixed(src.z));
   }

   private static int fixedMul(int a, int b) {
      return (int)((long)a * b / 4194303L);
   }

   private static int fixedDiv(int a, int b) {
      return (int)(a * 4194303L / b);
   }

   private void loadTransformPos(Matrix4f transform, long addr, int vert, Vector3f out, Vector3f otherAttributesOut) {
      this.scratch.setFromAddress(addr + vert * 24);
      otherAttributesOut.setFromAddress(addr + vert * 24 + 12L);
      this.scratch.w = 1.0F;
      Vector4f vec = transform.transformProject(this.scratch);
      if (Math.abs(this.scratch.w - 1.0F) > 1.0E-6F) {
         throw new IllegalStateException();
      } else {
         out.set(maintainPrecision(Math.fma(vec.x, 0.5F, 0.5F) * this.targetSize), maintainPrecision(Math.fma(vec.y, 0.5F, 0.5F) * this.targetSize), vec.z);
      }
   }

   private static float maintainPrecision(float x) {
      return x;
   }

   public long[] getRawFramebuffer() {
      return this.framebuffer;
   }
}
