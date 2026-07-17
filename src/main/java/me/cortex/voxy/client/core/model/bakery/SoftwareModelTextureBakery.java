package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

public class SoftwareModelTextureBakery {
   private static final Matrix4f[] VIEWS = new Matrix4f[6];
   private final ReuseVertexConsumer opaqueVC = new ReuseVertexConsumer();
   private final ReuseVertexConsumer translucentVC = new ReuseVertexConsumer(1);
   private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(16);
   private final FluidRenderer fr = new FluidRenderer(Minecraft.getInstance().getModelManager().getFluidStateModelSet());
   private static final long SINGLE_FACE_OUTPUT_SIZE = 2048L;

   public void setupTexture() {
      GpuTexture tex = Minecraft.getInstance()
         .getTextureManager()
         .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"))
         .getTexture();
      if (tex.getFormat() != TextureFormat.RGBA8) {
         throw new IllegalStateException("Block atlas not rgba8: " + tex.getFormat());
      } else {
         int targetMipLevel = 0;
         int width = tex.getWidth(targetMipLevel);
         int height = tex.getHeight(targetMipLevel);
         int[] texture = new int[width * height];
         GL11.glFlush();
         GL11.glFinish();
         GL30C.glBindFramebuffer(36160, 0);
         GL15C.glBindBuffer(35051, 0);
         GL11.glPixelStorei(3330, width);
         GL11.glPixelStorei(32876, 0);
         GL11.glPixelStorei(3331, 0);
         GL11.glPixelStorei(3332, 0);
         GL11.glPixelStorei(3333, 4);
         ARBDirectStateAccess.glGetTextureImage(((GlTexture)tex).glId(), 0, 6408, 5121, texture);
         this.rasterizer.setSamplerTexture(texture, width, height);
      }
   }

   private void bakeBlockModel(BlockState state) {
      if (state.getRenderShape() != RenderShape.INVISIBLE) {
         BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
         List<BlockStateModelPart> out = new ArrayList<>();
         model.collectParts(new SingleThreadedRandomSource(42L), out);

         for (BlockStateModelPart part : out) {
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
               for (BakedQuad quad : part.getQuads(direction)) {
                  (quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT ? this.translucentVC : this.opaqueVC).quad(quad, state.is(BlockTags.LEAVES));
               }
            }
         }
      }
   }

   private void bakeFluidState(final BlockState state, final int face) {
      this.fr.tesselate(new BlockAndTintGetter() {
         {
            Objects.requireNonNull(SoftwareModelTextureBakery.this);
         }

         public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
         }

         public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
         }

         public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
         }

         public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            SoftwareModelTextureBakery.this.translucentVC.setDefaultMeta(SoftwareModelTextureBakery.this.translucentVC.getDefaultMeta() | 4);
            SoftwareModelTextureBakery.this.opaqueVC.setDefaultMeta(SoftwareModelTextureBakery.this.opaqueVC.getDefaultMeta() | 4);
            return -1;
         }

         @Nullable
         public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
         }

         public BlockState getBlockState(BlockPos pos) {
            return SoftwareModelTextureBakery.shouldReturnAirForFluid(pos, face) ? Blocks.AIR.defaultBlockState() : state;
         }

         public FluidState getFluidState(BlockPos pos) {
            return SoftwareModelTextureBakery.shouldReturnAirForFluid(pos, face) ? Blocks.AIR.defaultBlockState().getFluidState() : state.getFluidState();
         }

         public int getHeight() {
            return 0;
         }

         public int getMinY() {
            return 0;
         }
      }, BlockPos.ZERO, layer -> {
         if (layer == ChunkSectionLayer.TRANSLUCENT) {
            return this.translucentVC;
         } else {
            if (layer == ChunkSectionLayer.CUTOUT) {
               this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta() | 1);
            } else {
               this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta() & -2);
            }

            return this.opaqueVC;
         }
      }, state, state.getFluidState());
      this.translucentVC.setDefaultMeta(0);
      this.opaqueVC.setDefaultMeta(0);
   }

   private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
      Vec3i fv = Direction.from3DDataValue(face).getUnitVec3i();
      int dot = fv.getX() * pos.getX() + fv.getY() * pos.getY() + fv.getZ() * pos.getZ();
      return dot >= 1;
   }

   public void free() {
      this.opaqueVC.free();
      this.translucentVC.free();
   }

   public int renderToOutput(BlockState state, long outputBuffer) {
      MemoryUtil.memSet(outputBuffer, 0, 12288L);
      boolean isBlock = true;
      if (state.getBlock() instanceof LiquidBlock) {
         isBlock = false;
      }

      if (state.hasBlockEntity()) {
      }

      boolean isAnyShaded = false;
      boolean isAnyDarkend = false;
      boolean anyTranslucent = false;
      boolean anyDiscard = false;
      if (isBlock) {
         this.opaqueVC.reset();
         this.translucentVC.reset();
         this.bakeBlockModel(state);
         isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
         isAnyDarkend |= this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
         anyTranslucent |= !this.translucentVC.isEmpty();
         anyDiscard |= this.opaqueVC.anyDiscard;
         if (!this.opaqueVC.isEmpty() || !this.translucentVC.isEmpty()) {
            for (int i = 0; i < VIEWS.length; i++) {
               this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
               this.rasterizer.clear();
               this.rasterizer.setBlending(false);
               this.rasterizer.raster(VIEWS[i], this.opaqueVC);
               this.rasterizer.setBlending(true);
               this.rasterizer.raster(VIEWS[i], this.translucentVC);
               UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer + 2048L * i);
            }
         }
      } else {
         if (!(state.getBlock() instanceof LiquidBlock)) {
            throw new IllegalStateException();
         }

         for (int i = 0; i < VIEWS.length; i++) {
            this.opaqueVC.reset();
            this.translucentVC.reset();
            this.bakeFluidState(state, i);
            if (!this.opaqueVC.isEmpty() || !this.translucentVC.isEmpty()) {
               isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
               isAnyDarkend |= this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
               anyTranslucent |= !this.translucentVC.isEmpty();
               anyDiscard |= this.opaqueVC.anyDiscard;
               this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
               this.rasterizer.clear();
               this.rasterizer.setBlending(false);
               this.rasterizer.raster(VIEWS[i], this.opaqueVC);
               this.rasterizer.setBlending(true);
               this.rasterizer.raster(VIEWS[i], this.translucentVC);
               UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer + 2048L * i);
            }
         }
      }

      return (isAnyShaded ? 1 : 0) | (isAnyDarkend ? 2 : 0) | (anyTranslucent ? 4 : 0) | (anyDiscard ? 8 : 0);
   }

   private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
      PoseStack stack = new PoseStack();
      stack.translate(0.5F, 0.5F, 0.5F);
      stack.mulPose(makeQuatFromAxisExact(new Vector3f(0.0F, 0.0F, 1.0F), rotation));
      stack.mulPose(makeQuatFromAxisExact(new Vector3f(1.0F, 0.0F, 0.0F), pitch));
      stack.mulPose(makeQuatFromAxisExact(new Vector3f(0.0F, 1.0F, 0.0F), yaw));
      stack.mulPose(new Matrix4f().scale(1 - 2 * (flip & 1), 1 - (flip & 2), 1 - (flip >> 1 & 2)));
      stack.translate(-0.5F, -0.5F, -0.5F);
      Matrix4f mat = new Matrix4f(stack.last().pose());
      mat = new Matrix4f().set(2.0F, 0.0F, 0.0F, 0.0F, 0.0F, 2.0F, 0.0F, 0.0F, 0.0F, 0.0F, -2.0F, 0.0F, -1.0F, -1.0F, 1.0F, 1.0F).mul(mat);
      VIEWS[i] = mat;
   }

   private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
      angle = (float)Math.toRadians(angle);
      float hangle = angle / 2.0F;
      float sinAngle = (float)Math.sin(hangle);
      float invVLength = (float)(1.0 / Math.sqrt(vec.lengthSquared()));
      return new Quaternionf(vec.x * invVLength * sinAngle, vec.y * invVLength * sinAngle, vec.z * invVLength * sinAngle, Math.cos(hangle));
   }

   static {
      addView(0, -90.0F, 0.0F, 0.0F, 0);
      addView(1, 90.0F, 0.0F, 0.0F, 4);
      addView(2, 0.0F, 180.0F, 0.0F, 1);
      addView(3, 0.0F, 0.0F, 0.0F, 0);
      addView(4, 0.0F, 90.0F, 270.0F, 4);
      addView(5, 0.0F, 270.0F, 270.0F, 0);
   }
}
