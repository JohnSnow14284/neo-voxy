package me.cortex.voxy.client.mixin.iris;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
   @Shadow
   @Final
   private Minecraft minecraft;

   @Inject(method = "renderLevel", at = @At("HEAD"), order = 100)
   private void voxy$injectIrisCompat(
      GraphicsResourceAllocator resourceAllocator,
      DeltaTracker deltaTracker,
      boolean renderOutline,
      CameraRenderState cameraState,
      Matrix4fc modelViewMatrix,
      GpuBufferSlice terrainFog,
      Vector4f fogColor,
      boolean shouldRenderSky,
      ChunkSectionsToRender chunkSectionsToRender,
      CallbackInfo ci
   ) {
      if (IrisUtil.irisShaderPackEnabled()) {
         IVoxyRenderSystemHolder renderer = IVoxyRenderSystemHolder.getNullableHolder();
         if (renderer != null) {
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            GL11C.glViewport(0, 0, target.width, target.height);
            Vec3 pos = cameraState.pos;
            IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = new IrisUtil.CapturedViewportParameters(
               new ChunkRenderMatrices(cameraState.projectionMatrix, cameraState.viewRotationMatrix),
               ((GameRendererStorage)this.minecraft.gameRenderer).sodium$getFogParameters(),
               target.width,
               target.height,
               pos.x,
               pos.y,
               pos.z
            );
         }
      }
   }
}
