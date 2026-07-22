package me.cortex.voxy.client.core;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.util.TrackedObject;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilMask;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL42.GL_LEQUAL;
import static org.lwjgl.opengl.GL42.GL_NOTEQUAL;
import static org.lwjgl.opengl.GL42.glDepthFunc;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfi;
import static org.lwjgl.opengl.GL45.glGetNamedFramebufferAttachmentParameteri;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

public abstract class AbstractRenderPipeline extends TrackedObject {
    public final RenderProperties properties;
    private final BooleanSupplier frexStillHasWork;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    protected AbstractSectionRenderer<?,?> sectionRenderer;

    private final FullscreenBlit depthStencilSetup;
    private final FullscreenBlit sentinelRestore;

    public final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

    protected final boolean deferTranslucency;

    private static final int DEPTH_SAMPLER = glGenSamplers();
    static {
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        //The stencil-setup pass samples the source depth at UV*scaleFactor; when a shader pipeline
        //renders at a scaled resolution the factor is not 1 and unclamped sampling wraps around
        //(default REPEAT), smearing the vanilla-coverage sentinel over sky/LOD regions
        glSamplerParameteri(DEPTH_SAMPLER, org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
        glSamplerParameteri(DEPTH_SAMPLER, org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
    }

    protected AbstractRenderPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier, boolean deferTranslucency) {
        this.properties = properties;
        this.frexStillHasWork = frexSupplier;
        this.nodeManager = nodeManager;
        this.nodeCleaner = nodeCleaner;
        this.traversal = traversal;
        this.deferTranslucency = deferTranslucency;

        this.depthStencilSetup = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/setup_stencil_depth.frag");
        this.sentinelRestore = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/depth0.frag");
    }

    //Allows pipelines to configure model baking system
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {}

    public final void setSectionRenderer(AbstractSectionRenderer<?,?> sectionRenderer) {//Stupid java ordering not allowing something pre super
        if (this.sectionRenderer != null) throw new IllegalStateException();
        this.sectionRenderer = sectionRenderer;
    }

    //Called before the pipeline starts running, used to update uniforms etc
    public void preSetup(Viewport<?> viewport) {

    }

    protected abstract int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight);
    protected abstract void postOpaquePreTranslucent(Viewport<?> viewport, int sourceFrameBuffer);
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        glDisable(GL_STENCIL_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
    }

    public void runPipeline(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        int depthTexture = this.setup(viewport, sourceFrameBuffer, srcWidth, srcHeight);

        var rs = ((AbstractSectionRenderer)this.sectionRenderer);
        GPUTiming.INSTANCE.marker("RO");
        rs.renderOpaque(viewport);
        var occlusionDebug = VoxyClient.getOcclusionDebugState();
        if (occlusionDebug==0) {
            GPUTiming.INSTANCE.marker("I");
            this.innerPrimaryWork(viewport, depthTexture);
            GPUTiming.INSTANCE.marker();
        }

        if (occlusionDebug<=1) {
            TimingStatistics.G.start();
            rs.buildDrawCalls(viewport);
            TimingStatistics.G.stop();
        }

        GPUTiming.INSTANCE.marker("TP");
        rs.renderTemporal(viewport);

        rs.postOpaquePreperation(viewport);

        //Opaque extras (distant trains/tracks) draw into the opaque target here, on both pipelines:
        //the depth attachment holds full LOD depth in voxy's far-projection space so occlusion is
        //per-pixel, and on the iris pipeline the renderers use the shader pack's patched fragment
        //shader to fill the whole g-buffer. Running before postOpaquePreTranslucent means the depth
        //copy/composite passes carry our geometry too.
        me.cortex.voxy.client.compat.LodPipelineHooks.beforeTranslucent(this, viewport, this.properties.closerEqualDepthCompare());

        this.postOpaquePreTranslucent(viewport, sourceFrameBuffer);

        GPUTiming.INSTANCE.marker("RT");

        if (!this.deferTranslucency) {
            rs.renderTranslucent(viewport);
        }
        GPUTiming.INSTANCE.marker();

        this.finish(viewport, sourceFrameBuffer, srcWidth, srcHeight);
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
    }

    protected void initDepthStencil(Viewport<?> viewport, int sourceFrameBuffer, int targetFb, int srcWidth, int srcHeight, int width, int height) {
        glClearNamedFramebufferfi(targetFb, GL_DEPTH_STENCIL, 0, this.properties.clearDepth(), 1);
        // using blit to copy depth from mismatched depth formats is not portable so instead a full screen pass is performed for a depth copy
        // the mismatched formats in this case is the d32 to d24s8
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFb);

        //If pixel passes, update stencil to 0 and set depth to the reprojected source depth
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);

        glEnable(GL_STENCIL_TEST);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        glStencilFunc(GL_ALWAYS, 0, 0xFF);
        glStencilMask(0xFF);


        this.depthStencilSetup.bind();
        int depthTexture = glGetNamedFramebufferAttachmentParameteri(sourceFrameBuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        this.lastSourceDepthTex = depthTexture;
        this.lastSrcWidth = srcWidth;
        this.lastSrcHeight = srcHeight;
        glBindTextureUnit(0, depthTexture);
        glBindSampler(0, DEPTH_SAMPLER);
        glUniform2f(1,((float)width)/srcWidth, ((float)height)/srcHeight);
        INVERSE_MVP.set(viewport.vanillaProjection)
                .mul(viewport.modelView)
                .invert()
                .getToAddress(SCRATCH);
        nglUniformMatrix4fv(2, 1, false, SCRATCH);
        viewport.MVP.getToAddress(SCRATCH);
        nglUniformMatrix4fv(3, 1, false, SCRATCH);
        //ndc-z -> window-z of rasterized geometry: the projection's ndc range alone does not change
        //the fixed-function 0.5*z+0.5 map, and gl_FragDepth writes must land in the same space as
        //rasterized depth or mixed comparisons flip. Queried per frame - it is one glGetInteger and
        //stale caching would silently skew every reprojected depth if anything flips clip control.
        boolean halfNdc = RenderProperties.windowIsHalfNdc();
        float ndcRemapScale = halfNdc ? 0.5f : 1.0f;
        float ndcRemapBias = halfNdc ? 0.5f : 0.0f;
        //xy: voxy ndc->window; zw: the inverse map for the source depth being unprojected - both
        //sides share the one clip-control mode, and only z is affected by it
        glUniform4f(4, ndcRemapScale, ndcRemapBias,
                1.0f / ndcRemapScale, -ndcRemapBias / ndcRemapScale);
        var boundary = me.cortex.voxy.client.core.rendering.LodBoundaryFade.getDistances();
        glUniform1f(6, boundary.fadeStart());
        glUniform1f(7, boundary.fadeEnd());
        glUniform1i(10, 0);
        glDepthMask(true);
        glColorMask(false,false,false,false);
        this.depthStencilSetup.blit();

        if (boundary.enabled() && this.useBoundaryGuardPass()) {
            //Second pass over the same shader, stencil writes masked off: the dithered LOD-won pixels
            //in the band keep stencil=1 but trade the cleared FAR depth for the vanilla surface pushed
            //slightly outward. Without it those pixels read as empty to HiZ and stop occluding the
            //pre-translucent hook geometry, which ignores stencil and tests depth alone.
            glStencilMask(0x00);
            glUniform1i(10, 1);
            this.depthStencilSetup.blit();
            glUniform1i(10, 0);
            glStencilMask(0xFF);
        }


        glDepthFunc(this.properties.closerEqualDepthCompare());
        glColorMask(true,true,true,true);

        //Make voxy terrain render only where there isnt mc terrain. The compare mask is bit0 only:
        //the pre-translucent hook tags its mesh pixels 3 (bit0 kept set), and translucent LOD must
        //still composite in front of them - a full-mask EQUAL,1 would punch mesh-shaped holes in
        //distant water. Bit1 is the hook's "keep my depth" mark, tested full-mask where it matters.
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glStencilFunc(GL_EQUAL, 1, 0x1);
    }

    //The normal pipeline composites from a cleared private colour target, so a band pixel whose LOD
    //geometry is missing would show through to nothing - hence the guard depth. Iris draws into an
    //already-populated gbuffer where a missing LOD pixel simply keeps vanilla's colour, and applying
    //the guard there rejects coarse LOD across the whole band instead.
    protected boolean useBoundaryGuardPass() {
        return true;
    }

    //Rewrites every vanilla-covered (stencil==0) pixel back to the NEAR sentinel. The setup pass
    //stamps reprojected real depth there so the pre-translucent hook geometry occludes correctly,
    //but downstream consumers (SSAO, the composite cutout blit, shader-pack protocols) identify
    //vanilla coverage by the exact sentinel value - call this once the hook has drawn.
    protected void restoreSentinelDepth() {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);
        glDepthMask(true);
        glColorMask(false,false,false,false);
        glEnable(GL_STENCIL_TEST);
        //Full-mask EQUAL,0: only untouched vanilla-covered pixels revert; pixels the hook meshes
        //tagged (3) keep their real depth so SSAO/composite/the vanilla handback carry them
        glStencilFunc(GL_EQUAL, 0, 0xFF);
        this.sentinelRestore.blit();
        glStencilFunc(GL_EQUAL, 1, 0x1);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        glColorMask(true,true,true,true);
    }

    private static final long SCRATCH = MemoryUtil.nmemAlloc(4*4*4);
    private static final Matrix4f INVERSE_MVP = new Matrix4f();
    protected static void transformBlitDepth(FullscreenBlit blitShader, int srcDepthTex, int dstFB, Viewport<?> viewport, Matrix4f targetTransform) {
        // at this point the dst frame buffer doesn't have a stencil attachment so we don't need to keep the stencil test on for the blit
        // in the worst case the dstFB does have a stencil attachment causing this pass to become 'corrupted'
        glDisable(GL_STENCIL_TEST);
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, dstFB);

        blitShader.bind();
        glBindTextureUnit(0, srcDepthTex);
        viewport.MVP.invert(INVERSE_MVP).getToAddress(SCRATCH);
        nglUniformMatrix4fv(1, 1, false, SCRATCH);//inverse fromProjection
        targetTransform.getToAddress(SCRATCH);//new Matrix4f(tooProjection).mul(vp.modelView).get(data);
        nglUniformMatrix4fv(2, 1, false, SCRATCH);//tooProjection

        glEnable(GL_DEPTH_TEST);
        blitShader.blit();
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);
    }

    protected void innerPrimaryWork(Viewport<?> viewport, int depthBuffer) {

        //Compute the mip chain
        viewport.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);

        do {
            TimingStatistics.main.stop();
            TimingStatistics.dynamic.start();

            TimingStatistics.D.start();
            //Tick download stream
            DownloadStream.INSTANCE.tick();
            TimingStatistics.D.stop();

            this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
            //glFlush();

            this.nodeCleaner.tick(this.traversal.getNodeBuffer());//Probably do this here??

            TimingStatistics.dynamic.stop();
            TimingStatistics.main.start();

            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT);

            TimingStatistics.F.start();
            this.traversal.doTraversal(viewport);
            TimingStatistics.F.stop();
        } while (this.frexStillHasWork.getAsBoolean());
    }

    @Override
    protected void free0() {
        this.fb.free();
        this.sectionRenderer.free();
        this.depthStencilSetup.delete();
        this.sentinelRestore.delete();
        super.free0();
    }

    public void addDebug(List<String> debug) {
        this.sectionRenderer.addDebug(debug);
        this.traversal.addDebug(debug);
        RenderStatistics.addDebug(debug);
    }

    //Binds the framebuffer and any other bindings needed for rendering
    public abstract void setupAndBindOpaque(Viewport<?> viewport);
    public abstract void setupAndBindTranslucent(Viewport<?> viewport);


    public void bindUniforms() {
        this.bindUniforms(-1);
    }

    public void bindUniforms(int index) {
    }

    public boolean hasTAA() {
        return false;
    }

    //null means no function, otherwise return the taa injection function
    public String taaFunction(String functionName) {
        return this.taaFunction(-1, functionName);
    }

    public String taaFunction(int uboBindingPoint, String functionName) {
        return null;
    }

    //null means dont transform the shader
    public String patchOpaqueShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    //Returning null means apply the same patch as the opaque
    public String patchTranslucentShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    public float[] getRenderScalingFactor() {
        return null;
    }

    //Depth texture LOD geometry renders into, for sable contraption depth-occlusion compositing.
    //Default none; only the Iris pipeline provides one.
    public int getSableOcclusionDepthTexture() {
        return 0;
    }

    //Inputs of the last initDepthStencil, for the occlusion debug recorder
    private int lastSourceDepthTex;
    private int lastSrcWidth, lastSrcHeight;
    public final int debugSourceDepthTex() { return this.lastSourceDepthTex; }
    public final int debugSrcWidth() { return this.lastSrcWidth; }
    public final int debugSrcHeight() { return this.lastSrcHeight; }

}
