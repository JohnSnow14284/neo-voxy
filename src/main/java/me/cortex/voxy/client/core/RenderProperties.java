package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import net.irisshaders.iris.Iris;

import static org.lwjgl.opengl.GL11C.*;

public record RenderProperties(boolean isZero2One, boolean isReverseZ, boolean useBlockAtlasUVs) {

    public <T extends Shader.Builder<J>, J extends Shader> T apply(T builder) {
        return (T) builder.defineIf("USE_ZERO_ONE_DEPTH", this.isZero2One)
                .defineIf("USE_REVERSE_Z", this.isReverseZ)
                //Rasterized window depth is 0.5*ndc+0.5 under default clip control regardless of
                //the projection's ndc range; shaders converting between sampled window depth and
                //analytic ndc must apply this map or every mixed comparison carries a constant bias
                .defineIf("WINDOW_HALF_NDC", windowIsHalfNdc());
    }

    //Queried per shader compile (cheap) so a pipeline rebuild picks up a changed clip-control mode
    public static boolean windowIsHalfNdc() {
        return glGetInteger(org.lwjgl.opengl.GL45C.GL_CLIP_DEPTH_MODE) != org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
    }

    public int closerEqualDepthCompare() {
        return this.isReverseZ?GL_GEQUAL:GL_LEQUAL;
    }

    public int closerDepthCompare() {
        return this.isReverseZ?GL_GREATER:GL_LESS;
    }

    public int furtherDepthCompare() {
        return this.isReverseZ?GL_LESS:GL_GREATER;
    }

    public float clearDepth() {
        return this.isReverseZ?0.0f:1.0f;
    }

    public float inverseClearDepth() {
        return this.isReverseZ?1.0f:0.0f;
    }







    private static boolean irisUseBlockAtlasUv() {
        var irisPipe = Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe == null) {
            return false;
        }
        if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
            var pipeData = getVoxyPipeData.voxy$getPipelineData();
            if (pipeData == null) {
                return false;
            }
            //return pipeData.useBlockAtlasUV;
            return false;
        }
        return false;
    }

    public static RenderProperties getRenderProperties() {
        RenderProperties properties = new RenderProperties(
                true,
                false,
                false);

        if (IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT) {
            properties = new RenderProperties(properties.isZero2One(), properties.isReverseZ(), irisUseBlockAtlasUv());
        }

        return properties;
    }
}
