package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL45C;

public class GlTexture extends TrackedObject {
   public final int id;
   private final int type;
   private int format;
   private int width;
   private int height;
   private int levels;
   private boolean hasAllocated;
   private static int COUNT;
   private static long ESTIMATED_TOTAL_SIZE;

   public GlTexture() {
      this(3553);
   }

   public GlTexture(int type) {
      this.id = GL45C.glCreateTextures(type);
      this.type = type;
      COUNT++;
   }

   private GlTexture(int type, boolean useGenTypes) {
      if (useGenTypes) {
         this.id = GL45C.glGenTextures();
      } else {
         this.id = GL45C.glCreateTextures(type);
      }

      this.type = type;
      COUNT++;
   }

   public GlTexture store(int format, int levels, int width, int height) {
      if (this.hasAllocated) {
         throw new IllegalStateException("Texture already allocated");
      } else {
         this.hasAllocated = true;
         this.format = format;
         if (this.type == 3553) {
            GL45C.glTextureStorage2D(this.id, levels, format, width, height);
            this.width = width;
            this.height = height;
            this.levels = levels;
            ESTIMATED_TOTAL_SIZE = ESTIMATED_TOTAL_SIZE + this.getEstimatedSize();
            return this;
         } else {
            throw new IllegalStateException("Unknown texture type");
         }
      }
   }

   public GlTexture createView() {
      this.assertAllocated();
      GlTexture view = new GlTexture(this.type, true);
      GL45C.glTextureView(view.id, this.type, this.id, this.format, 0, 1, 0, 1);
      return view;
   }

   @Override
   public void free() {
      if (this.hasAllocated) {
         ESTIMATED_TOTAL_SIZE = ESTIMATED_TOTAL_SIZE - this.getEstimatedSize();
      }

      COUNT--;
      this.hasAllocated = false;
      super.free0();
      GL45C.glDeleteTextures(this.id);
   }

   public GlTexture name(String name) {
      this.assertAllocated();
      return GlDebug.name(name, this);
   }

   public int getWidth() {
      this.assertAllocated();
      return this.width;
   }

   public int getHeight() {
      this.assertAllocated();
      return this.height;
   }

   public int getLevels() {
      this.assertAllocated();
      return this.levels;
   }

   public int getFormat() {
      this.assertAllocated();
      return this.format;
   }

   public int getPixelTransferFormat() {
      this.assertAllocated();

      return switch (this.format) {
         case 32856 -> 6408;
         case 33190, 33191, 36012 -> 6402;
         case 33326 -> 6403;
         case 33327 -> 33319;
         case 33334 -> 36244;
         case 35056 -> 34041;
         default -> throw new IllegalStateException("Unknown format");
      };
   }

   private long getEstimatedSize() {
      this.assertAllocated();

      long elemSize = switch (this.format) {
         case 32856, 33326, 33327, 33334, 35056 -> 4L;
         case 33190 -> 4L;
         case 33191 -> 4L;
         case 36012 -> 4L;
         default -> throw new IllegalStateException("Unknown element size");
      };
      long size = 0L;

      for (int lvl = 0; lvl < this.levels; lvl++) {
         size += Math.max((long)this.width >> lvl, 1L) * Math.max((long)this.height >> lvl, 1L) * elemSize;
      }

      return size;
   }

   public void assertAllocated() {
      if (!this.hasAllocated) {
         throw new IllegalStateException("Texture not yet allocated");
      }
   }

   public GlTexture zero() {
      this.assertAllocated();

      int type = switch (this.format) {
         case 32856 -> 'ᐄ';
         case 33190, 33191, 33326, 33327, 36012 -> 'ᐆ';
         case 33334 -> 'ᐅ';
         case 35056 -> '蓺';
         case 36013 -> '趭';
         default -> throw new IllegalStateException("Unknown format");
      };

      for (int lvl = 0; lvl < this.levels; lvl++) {
         GL45C.nglClearTexImage(this.id, lvl, this.getPixelTransferFormat(), type, 0L);
      }

      return this;
   }

   public static int getCount() {
      return COUNT;
   }

   public static long getEstimatedTotalSize() {
      return ESTIMATED_TOTAL_SIZE;
   }
}
