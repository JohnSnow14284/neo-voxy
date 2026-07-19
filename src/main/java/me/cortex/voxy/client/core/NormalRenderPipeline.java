package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.util.GPUTiming;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_COLOR;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL20C.glUniform4f;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glClearNamedFramebufferfv;
import static org.lwjgl.opengl.GL45C.glTextureParameterf;
import static org.lwjgl.opengl.GL42C.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.GL_TEXTURE_FETCH_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;

public class NormalRenderPipeline extends AbstractRenderPipeline {
    private static final float[] CLEAR_COLOUR = {0.0f, 0.0f, 0.0f, 0.0f};
    private GlTexture colourTex;
    private GlTexture colourSSAOTex;
    private final GlFramebuffer fbSSAO = new GlFramebuffer();

    private final FullscreenBlit finalBlit;

    private final SSAO ssao;
    private final Matrix4f targetTransform = new Matrix4f();

    protected NormalRenderPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, false);
        this.finalBlit = new FullscreenBlit(properties, "voxy:post/blit_texture_depth_cutout.frag",
                builder -> builder.define("USE_ENV_FOG").define("EMIT_COLOUR"));
        this.ssao = SSAO.createSSAO(properties, VoxyConfig.CONFIG.getSSAOMode());
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFB, int srcWidth, int srcHeight) {
        if (this.colourTex == null || this.colourTex.getHeight() != viewport.height || this.colourTex.getWidth() != viewport.width) {
            if (this.colourTex != null) {
                this.colourTex.free();
                this.colourSSAOTex.free();
            }
            this.fb.resize(viewport.width, viewport.height);

            this.colourTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);
            this.colourSSAOTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);

            this.fb.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.colourTex).verify();
            this.fbSSAO.bind(this.fb.getDepthAttachmentType(), this.fb.getDepthTex()).bind(GL_COLOR_ATTACHMENT0, this.colourSSAOTex).verify();

            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.fb.getDepthTex().id, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
        }

        // Transition guard pixels can contain depth without LOD colour. Clear
        // the private target so they fall back to vanilla instead of reusing a
        // colour left by an older frame.
        glClearNamedFramebufferfv(this.fb.framebuffer.id, GL_COLOR, 0, CLEAR_COLOUR);
        this.initDepthStencil(viewport, sourceFB, this.fb.framebuffer.id,
                viewport.width, viewport.height, viewport.width, viewport.height);

        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceFrameBuffer) {
        //Vanilla-covered pixels held reprojected real depth for the hook geometry; SSAO and the
        //final cutout blit identify vanilla coverage by depth==NEAR, so put the sentinel back
        this.fb.bind();
        this.restoreSentinelDepth();

        GPUTiming.INSTANCE.marker("ao");
        this.ssao.computeSSAO(viewport, this.colourSSAOTex, this.colourTex, this.fb.getDepthTex(), sourceFrameBuffer);

        // Make the SSAO image writes visible before translucent terrain uses the target.
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        this.finalBlit.bind();
        boolean submerged = Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera()
                != FogType.NONE;
        VoxyRenderSystem vrs = IGetVoxyRenderSystem.getNullable();
        boolean restrictiveFog = vrs != null && vrs.hasCapturedRestrictiveFog();
        if (submerged || restrictiveFog) {
            // The vanilla terrain already uses these live medium parameters. Apply the same linear
            // fog to the separately rendered LOD target so the water-vision expansion does not expose
            // a sharp square/blue boundary between vanilla terrain and Voxy terrain.
            float near = vrs != null ? vrs.getCapturedFogStart() : RenderSystem.getShaderFogStart();
            float far = vrs != null ? vrs.getCapturedFogEnd() : RenderSystem.getShaderFogEnd();
            float[] fogColor = vrs != null ? vrs.getCapturedFogColor() : RenderSystem.getShaderFogColor();
            int fogShape = vrs != null ? vrs.getCapturedFogShape() : RenderSystem.getShaderFogShape().getIndex();
            if (far > near) {
                glUniform2f(4, near, far);
                glUniform4f(5, fogColor[0], fogColor[1], fogColor[2], 1.0f);
                glUniform1i(6, fogShape);
                glUniform1f(7, 1.0f);
                glUniform1f(8, 0.0f);
                glUniform1i(9, 1);
            } else {
                clearFogUniforms();
            }
        } else if (VoxyConfig.CONFIG.useEnvironmentalFog && VoxyConfig.CONFIG.fogIntensity > 0.0f) {
            float[] fogColor = RenderSystem.getShaderFogColor();
            float voxyRenderBlocks = 32f * VoxyConfig.CONFIG.sectionRenderDistance;
            float far = voxyRenderBlocks * (VoxyConfig.CONFIG.fogDistancePercent / 100.0f);
            float near = far * 0.5f;
            if (far - near > 1) {
                glUniform2f(4, near, far);
                glUniform4f(5, fogColor[0], fogColor[1], fogColor[2], 1.0f);
                glUniform1i(6, RenderSystem.getShaderFogShape().getIndex());
                glUniform1f(7, Math.clamp(VoxyConfig.CONFIG.fogIntensity, 0.0f, 1.0f));
                glUniform1f(8, Math.clamp(VoxyConfig.CONFIG.fogDensity, 0.0f, 1.0f));
                glUniform1i(9, 0);
            } else {
                clearFogUniforms();
            }
        } else {
            clearFogUniforms();
        }

        glBindTextureUnit(3, this.colourSSAOTex.id);

        // Always composite the LOD target. The previous "fully fogged" shortcut
        // ignored fog intensity/density and could drop the whole LOD image.
        glEnable(GL_BLEND);
        // The LOD target stores straight-alpha translucency.
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        boolean circularHandoff = LodBoundaryFade.getDistances().enabled();
        if (circularHandoff) {
            // The stencil pass has already selected a terrain owner. Avoid a
            // second comparison between simplified LOD depth and vanilla depth.
            glDepthFunc(GL_ALWAYS);
        }
        AbstractRenderPipeline.transformBlitDepth(this.finalBlit, this.fb.getDepthTex().id,
                sourceFrameBuffer, viewport,
                this.targetTransform.set(viewport.vanillaProjection).mul(viewport.modelView));
        if (circularHandoff) {
            glDepthFunc(this.properties.closerEqualDepthCompare());
        }
        glDisable(GL_BLEND);
    }

    private static void clearFogUniforms() {
        glUniform2f(4, 0, 0);
        glUniform4f(5, 0, 0, 0, 0);
        glUniform1i(6, 0);
        glUniform1f(7, 0);
        glUniform1f(8, 0);
        glUniform1i(9, 0);
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    public void free() {
        this.finalBlit.delete();
        this.ssao.free();
        this.fbSSAO.free();
        if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
        }
        super.free0();
    }

    @Override
    public void addDebug(List<String> debug) {
        super.addDebug(debug);
        this.ssao.addDebugInfo(debug);
    }
}
