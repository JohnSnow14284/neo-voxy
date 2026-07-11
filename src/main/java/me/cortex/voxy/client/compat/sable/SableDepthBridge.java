package me.cortex.voxy.client.compat.sable;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.util.Collection;

/**
 * Makes an off-screen Voxy depth buffer temporarily visible to Sable's transformed sub-level
 * renderer. It swaps only the active framebuffer's depth attachment, leaving all colour targets
 * untouched, then commits only depth pixels actually changed by Sable.
 *
 * <p>The bridge is render-thread only and allocates no per-frame Java objects.</p>
 */
public final class SableDepthBridge {
    private static final int[] VIEWPORT = new int[4];
    private static final int[] COLOR_MASK = new int[4];
    private static final Matrix4f MATRIX_SCRATCH = new Matrix4f();
    private static long matrixAddress;

    private static int workFramebuffer;
    private static int workDepth;
    private static int baselineDepth;
    private static int textureWidth;
    private static int textureHeight;
    private static int textureFormat;
    private static int temporaryAttachment;

    private static FullscreenBlit mergeDepth;
    private static FullscreenBlit commitDepthDelta;
    private static RenderProperties shaderProperties;
    private static int nearestSampler;

    private static boolean active;
    private static int nesting;

    private static int sourceFramebuffer;
    private static int sourceAttachment;
    private static int sourceObjectType;
    private static int sourceObjectName;
    private static int sourceTextureLevel;
    private static boolean sourceHasStencil;

    private static int savedReadFramebuffer;
    private static int savedProgram;
    private static int savedVertexArray;
    private static int savedActiveTexture;
    private static int savedTexture0;
    private static int savedTexture1;
    private static int savedSampler0;
    private static int savedSampler1;
    private static int savedDepthFunction;
    private static boolean savedDepthWrite;
    private static boolean savedDepthTest;
    private static boolean savedStencilTest;
    private static boolean savedScissorTest;

    private static boolean warnedUnsupportedTarget;
    private static boolean warnedFramebufferFailure;

    private SableDepthBridge() {
    }

    public static boolean enter(Matrix4f modelView, Matrix4f projection, Iterable<?> subLevels) {
        if (active) {
            nesting++;
            return true;
        }

        if (subLevels instanceof Collection<?> collection && collection.isEmpty()) {
            return false;
        }

        VoxyRenderSystem renderer = IGetVoxyRenderSystem.getNullable();
        if (renderer == null) {
            return false;
        }

        Viewport<?> voxyViewport = renderer.getViewport();
        if (voxyViewport == null || !renderer.isDetachedSceneDepthCurrent(voxyViewport)) {
            return false;
        }

        int voxyDepth = renderer.getDetachedSceneDepthTexture();
        if (voxyDepth == 0) {
            return false;
        }

        int drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (drawFramebuffer == 0 || !captureDepthAttachment(drawFramebuffer)) {
            return false;
        }

        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, VIEWPORT);
        if (VIEWPORT[0] != 0 || VIEWPORT[1] != 0 || VIEWPORT[2] <= 0 || VIEWPORT[3] <= 0) {
            return false;
        }

        AttachmentInfo attachment = inspectAttachment();
        if (attachment == null || attachment.samples != 0
                || attachment.width != VIEWPORT[2] || attachment.height != VIEWPORT[3]
                || voxyViewport.width != attachment.width || voxyViewport.height != attachment.height) {
            warnUnsupportedOnce();
            return false;
        }

        captureState(drawFramebuffer);

        if (!ensureResources(attachment.width, attachment.height, attachment.internalFormat, sourceAttachment, renderer.getRenderProperties())) {
            restoreState();
            return false;
        }

        int copyMask = GL11C.GL_DEPTH_BUFFER_BIT;
        if (sourceHasStencil) {
            copyMask |= GL11C.GL_STENCIL_BUFFER_BIT;
        }

        GL45C.glBlitNamedFramebuffer(
                sourceFramebuffer,
                workFramebuffer,
                0, 0, textureWidth, textureHeight,
                0, 0, textureWidth, textureHeight,
                copyMask,
                GL11C.GL_NEAREST
        );

        mergeVoxyDepth(voxyDepth, voxyViewport, modelView, projection, renderer.getRenderProperties());

        GL43C.glCopyImageSubData(
                workDepth, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                baselineDepth, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                textureWidth, textureHeight, 1
        );

        // Replace only the active depth attachment. Sable keeps rendering into the exact same
        // colour attachments and draw buffers selected by Sodium/Iris.
        detachSourceDepth();
        GL45C.glNamedFramebufferTexture(sourceFramebuffer, sourceAttachment, workDepth, 0);

        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, sourceFramebuffer);
        restoreStateExceptFramebuffer();

        active = true;
        nesting = 1;
        return true;
    }

    public static void exit() {
        if (!active || --nesting > 0) {
            return;
        }

        try {
            GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);

            // Restore the original depth object before writing Sable's depth delta back to it.
            GL45C.glNamedFramebufferTexture(sourceFramebuffer, sourceAttachment, 0, 0);
            restoreSourceDepth();

            if (sourceHasStencil) {
                GL45C.glBlitNamedFramebuffer(
                        workFramebuffer,
                        sourceFramebuffer,
                        0, 0, textureWidth, textureHeight,
                        0, 0, textureWidth, textureHeight,
                        GL11C.GL_STENCIL_BUFFER_BIT,
                        GL11C.GL_NEAREST
                );
            }

            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, sourceFramebuffer);
            GL11C.glViewport(0, 0, textureWidth, textureHeight);
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_ALWAYS);
            GL11C.glDepthMask(true);
            GL11C.glColorMask(false, false, false, false);

            commitDepthDelta.bind();
            GL45C.glBindTextureUnit(0, baselineDepth);
            GL45C.glBindTextureUnit(1, workDepth);
            GL33C.glBindSampler(0, nearestSampler);
            GL33C.glBindSampler(1, nearestSampler);
            commitDepthDelta.blit();
        } finally {
            active = false;
            nesting = 0;
            restoreState();
        }
    }

    public static void release() {
        if (active) {
            // A normal render return should always close the scope. Avoid deleting an attachment
            // that is still installed if another renderer failed unexpectedly.
            return;
        }

        if (mergeDepth != null) {
            mergeDepth.delete();
            mergeDepth = null;
        }
        if (commitDepthDelta != null) {
            commitDepthDelta.delete();
            commitDepthDelta = null;
        }
        if (workDepth != 0) {
            GL11C.glDeleteTextures(workDepth);
            workDepth = 0;
        }
        if (baselineDepth != 0) {
            GL11C.glDeleteTextures(baselineDepth);
            baselineDepth = 0;
        }
        if (workFramebuffer != 0) {
            GL30C.glDeleteFramebuffers(workFramebuffer);
            workFramebuffer = 0;
        }
        if (nearestSampler != 0) {
            GL33C.glDeleteSamplers(nearestSampler);
            nearestSampler = 0;
        }
        if (matrixAddress != 0L) {
            MemoryUtil.nmemFree(matrixAddress);
            matrixAddress = 0L;
        }

        textureWidth = 0;
        textureHeight = 0;
        textureFormat = 0;
        temporaryAttachment = 0;
        shaderProperties = null;
    }

    private static boolean captureDepthAttachment(int framebuffer) {
        int depthStencilType = GL45C.glGetNamedFramebufferAttachmentParameteri(
                framebuffer,
                GL30C.GL_DEPTH_STENCIL_ATTACHMENT,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
        );

        sourceFramebuffer = framebuffer;
        if (depthStencilType != GL11C.GL_NONE) {
            sourceAttachment = GL30C.GL_DEPTH_STENCIL_ATTACHMENT;
            sourceObjectType = depthStencilType;
            sourceHasStencil = true;
        } else {
            sourceAttachment = GL30C.GL_DEPTH_ATTACHMENT;
            sourceObjectType = GL45C.glGetNamedFramebufferAttachmentParameteri(
                    framebuffer,
                    sourceAttachment,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
            );
            sourceHasStencil = false;
        }

        if (sourceObjectType != GL11C.GL_TEXTURE && sourceObjectType != GL30C.GL_RENDERBUFFER) {
            return false;
        }

        sourceObjectName = GL45C.glGetNamedFramebufferAttachmentParameteri(
                framebuffer,
                sourceAttachment,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
        );
        if (sourceObjectName == 0) {
            return false;
        }

        sourceTextureLevel = 0;
        if (sourceObjectType == GL11C.GL_TEXTURE) {
            int layered = GL45C.glGetNamedFramebufferAttachmentParameteri(
                    framebuffer,
                    sourceAttachment,
                    GL32C.GL_FRAMEBUFFER_ATTACHMENT_LAYERED
            );
            int layer = GL45C.glGetNamedFramebufferAttachmentParameteri(
                    framebuffer,
                    sourceAttachment,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER
            );
            int cubeFace = GL45C.glGetNamedFramebufferAttachmentParameteri(
                    framebuffer,
                    sourceAttachment,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE
            );
            if (layered != 0 || layer != 0 || cubeFace != 0) {
                return false;
            }
            sourceTextureLevel = GL45C.glGetNamedFramebufferAttachmentParameteri(
                    framebuffer,
                    sourceAttachment,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL
            );
        }
        return true;
    }

    private static AttachmentInfo inspectAttachment() {
        if (sourceObjectType == GL11C.GL_TEXTURE) {
            int width = GL45C.glGetTextureLevelParameteri(sourceObjectName, sourceTextureLevel, GL11C.GL_TEXTURE_WIDTH);
            int height = GL45C.glGetTextureLevelParameteri(sourceObjectName, sourceTextureLevel, GL11C.GL_TEXTURE_HEIGHT);
            int depth = GL45C.glGetTextureLevelParameteri(sourceObjectName, sourceTextureLevel, GL12Compat.GL_TEXTURE_DEPTH);
            int samples = GL45C.glGetTextureLevelParameteri(sourceObjectName, sourceTextureLevel, GL32C.GL_TEXTURE_SAMPLES);
            int format = GL45C.glGetTextureLevelParameteri(sourceObjectName, sourceTextureLevel, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
            if (depth > 1) {
                return null;
            }
            return new AttachmentInfo(width, height, samples, format);
        }

        int width = GL45C.glGetNamedRenderbufferParameteri(sourceObjectName, GL30C.GL_RENDERBUFFER_WIDTH);
        int height = GL45C.glGetNamedRenderbufferParameteri(sourceObjectName, GL30C.GL_RENDERBUFFER_HEIGHT);
        int samples = GL45C.glGetNamedRenderbufferParameteri(sourceObjectName, GL30C.GL_RENDERBUFFER_SAMPLES);
        int format = GL45C.glGetNamedRenderbufferParameteri(sourceObjectName, GL30C.GL_RENDERBUFFER_INTERNAL_FORMAT);
        return new AttachmentInfo(width, height, samples, format);
    }

    private static boolean ensureResources(int width, int height, int internalFormat, int attachment, RenderProperties properties) {
        if (mergeDepth == null || commitDepthDelta == null || !properties.equals(shaderProperties)) {
            if (mergeDepth != null) {
                mergeDepth.delete();
            }
            if (commitDepthDelta != null) {
                commitDepthDelta.delete();
            }
            mergeDepth = new FullscreenBlit(properties, "voxy:post/blit_texture_depth_cutout.frag");
            commitDepthDelta = new FullscreenBlit(properties, "voxy:post/external_depth_delta.frag");
            shaderProperties = properties;
        }

        if (nearestSampler == 0) {
            nearestSampler = GL33C.glGenSamplers();
            GL33C.glSamplerParameteri(nearestSampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL33C.glSamplerParameteri(nearestSampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL33C.glSamplerParameteri(nearestSampler, GL14Compat.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
        }

        if (workFramebuffer == 0) {
            workFramebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(workFramebuffer, GL11C.GL_NONE);
            GL45C.glNamedFramebufferReadBuffer(workFramebuffer, GL11C.GL_NONE);
        }

        if (workDepth == 0 || width != textureWidth || height != textureHeight
                || internalFormat != textureFormat || attachment != temporaryAttachment) {
            if (workDepth != 0) {
                GL11C.glDeleteTextures(workDepth);
                GL11C.glDeleteTextures(baselineDepth);
            }

            workDepth = createDepthTexture(width, height, internalFormat);
            baselineDepth = createDepthTexture(width, height, internalFormat);
            textureWidth = width;
            textureHeight = height;
            textureFormat = internalFormat;
            temporaryAttachment = attachment;

            GL45C.glNamedFramebufferTexture(workFramebuffer, GL30C.GL_DEPTH_ATTACHMENT, 0, 0);
            GL45C.glNamedFramebufferTexture(workFramebuffer, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, 0, 0);
            GL45C.glNamedFramebufferTexture(workFramebuffer, attachment, workDepth, 0);

            if (GL45C.glCheckNamedFramebufferStatus(workFramebuffer, GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                if (!warnedFramebufferFailure) {
                    Logger.warn("Voxy disabled the Sable depth bridge because its temporary framebuffer is incomplete");
                    warnedFramebufferFailure = true;
                }
                return false;
            }
        }

        return true;
    }

    private static int createDepthTexture(int width, int height, int internalFormat) {
        int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, internalFormat, width, height);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(texture, GL14Compat.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
        if (hasStencil(internalFormat)) {
            GL45C.glTextureParameteri(texture, GL43C.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11C.GL_DEPTH_COMPONENT);
        }
        return texture;
    }

    private static void mergeVoxyDepth(int voxyDepth, Viewport<?> viewport, Matrix4f modelView, Matrix4f projection, RenderProperties properties) {
        GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, workFramebuffer);
        GL11C.glViewport(0, 0, textureWidth, textureHeight);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(properties.closerEqualDepthCompare());
        GL11C.glDepthMask(true);
        GL11C.glColorMask(false, false, false, false);

        mergeDepth.bind();
        GL45C.glBindTextureUnit(0, voxyDepth);
        GL33C.glBindSampler(0, nearestSampler);

        if (matrixAddress == 0L) {
            matrixAddress = MemoryUtil.nmemAlloc(16L * Float.BYTES);
        }
        MATRIX_SCRATCH.set(viewport.MVP).invert().getToAddress(matrixAddress);
        GL20C.nglUniformMatrix4fv(1, 1, false, matrixAddress);
        projection.mul(modelView, MATRIX_SCRATCH).getToAddress(matrixAddress);
        GL20C.nglUniformMatrix4fv(2, 1, false, matrixAddress);
        mergeDepth.blit();
    }

    private static void captureState(int drawFramebuffer) {
        savedReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        savedProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        savedVertexArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        savedActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);

        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        savedTexture0 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        savedTexture1 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL13C.glActiveTexture(savedActiveTexture);

        savedSampler0 = GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, 0);
        savedSampler1 = GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, 1);
        savedDepthFunction = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        savedDepthWrite = GL11C.glGetInteger(GL11C.GL_DEPTH_WRITEMASK) != 0;
        savedDepthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        savedStencilTest = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
        savedScissorTest = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        GL11C.glGetIntegerv(GL11C.GL_COLOR_WRITEMASK, COLOR_MASK);

        sourceFramebuffer = drawFramebuffer;
    }

    private static void restoreStateExceptFramebuffer() {
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, savedReadFramebuffer);
        GL11C.glViewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        GL20C.glUseProgram(savedProgram);
        GL30C.glBindVertexArray(savedVertexArray);
        GL45C.glBindTextureUnit(0, savedTexture0);
        GL45C.glBindTextureUnit(1, savedTexture1);
        GL33C.glBindSampler(0, savedSampler0);
        GL33C.glBindSampler(1, savedSampler1);
        GL13C.glActiveTexture(savedActiveTexture);
        GL11C.glDepthFunc(savedDepthFunction);
        GL11C.glDepthMask(savedDepthWrite);
        GL11C.glColorMask(COLOR_MASK[0] != 0, COLOR_MASK[1] != 0, COLOR_MASK[2] != 0, COLOR_MASK[3] != 0);
        setEnabled(GL11C.GL_DEPTH_TEST, savedDepthTest);
        setEnabled(GL11C.GL_STENCIL_TEST, savedStencilTest);
        setEnabled(GL11C.GL_SCISSOR_TEST, savedScissorTest);
    }

    private static void restoreState() {
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, sourceFramebuffer);
        restoreStateExceptFramebuffer();
    }

    private static void detachSourceDepth() {
        if (sourceObjectType == GL11C.GL_TEXTURE) {
            GL45C.glNamedFramebufferTexture(sourceFramebuffer, sourceAttachment, 0, 0);
        } else {
            GL45C.glNamedFramebufferRenderbuffer(sourceFramebuffer, sourceAttachment, GL30C.GL_RENDERBUFFER, 0);
        }
    }

    private static void restoreSourceDepth() {
        if (sourceObjectType == GL11C.GL_TEXTURE) {
            GL45C.glNamedFramebufferTexture(sourceFramebuffer, sourceAttachment, sourceObjectName, sourceTextureLevel);
        } else {
            GL45C.glNamedFramebufferRenderbuffer(sourceFramebuffer, sourceAttachment, GL30C.GL_RENDERBUFFER, sourceObjectName);
        }
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            GL11C.glEnable(capability);
        } else {
            GL11C.glDisable(capability);
        }
    }

    private static boolean hasStencil(int internalFormat) {
        return internalFormat == GL30C.GL_DEPTH24_STENCIL8 || internalFormat == GL30C.GL_DEPTH32F_STENCIL8;
    }

    private static void warnUnsupportedOnce() {
        if (!warnedUnsupportedTarget) {
            Logger.warn("Voxy skipped Sable depth sharing because the active depth target is layered, multisampled, or size-mismatched");
            warnedUnsupportedTarget = true;
        }
    }

    private record AttachmentInfo(int width, int height, int samples, int internalFormat) {
    }

    /** Constants that live in older LWJGL capability classes. */
    private static final class GL12Compat {
        private static final int GL_TEXTURE_DEPTH = 0x8071;
    }

    private static final class GL14Compat {
        private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;
    }
}
