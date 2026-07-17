package me.cortex.voxy.client.core;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

public record RenderProperties(boolean isZero2One, boolean isReverseZ, boolean useBlockAtlasUVs) {
   public <T extends Shader.Builder<J>, J extends Shader> T apply(T builder) {
      return (T)builder.defineIf("USE_ZERO_ONE_DEPTH", this.isZero2One).defineIf("USE_REVERSE_Z", this.isReverseZ);
   }

   public int closerEqualDepthCompare() {
      return this.isReverseZ ? 518 : 515;
   }

   public int closerDepthCompare() {
      return this.isReverseZ ? 516 : 513;
   }

   public int furtherDepthCompare() {
      return this.isReverseZ ? 513 : 516;
   }

   public float clearDepth() {
      return this.isReverseZ ? 0.0F : 1.0F;
   }

   public float inverseClearDepth() {
      return this.isReverseZ ? 1.0F : 0.0F;
   }

   private static boolean irisUseBlockAtlasUv() {
      WorldRenderingPipeline irisPipe = Iris.getPipelineManager().getPipelineNullable();
      if (irisPipe == null) {
         return false;
      } else if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
         IrisVoxyRenderPipelineData pipeData = getVoxyPipeData.voxy$getPipelineData();
         return pipeData == null ? false : false;
      } else {
         return false;
      }
   }

   private static boolean useReverseZ() {
      return IrisUtil.irisShaderPackEnabled() ? false : DepthStencilState.DEFAULT.depthTest().equals(CompareOp.GREATER_THAN_OR_EQUAL);
   }

   public static RenderProperties getRenderProperties() {
      RenderProperties properties = new RenderProperties(RenderSystem.getDevice().isZZeroToOne(), useReverseZ(), false);
      if (IrisUtil.IRIS_INSTALLED) {
         properties = new RenderProperties(properties.isZero2One(), properties.isReverseZ(), irisUseBlockAtlasUv());
      }

      return properties;
   }
}
