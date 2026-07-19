package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;

//Rasterizes an AABB per vanilla-rendered chunk section into the depth bounding buffer - the mask
//that keeps LOD terrain from drawing over the vanilla area.
//
//The section set is streamed from sodium's own render-list traversal each time it rebuilds
//(MixinSectionCollector), not tracked from build/unload events: the mask then covers exactly the
//sections sodium draws this frame. Punching by "every built section in range" both over-punched
//(sections built but not drawn - the void ring at the render distance edge, sections mid-rebuild)
//and paid for tens of thousands of boxes when only the visible few thousand matter.
public class ChunkBoundRenderer {
    private static final int INIT_MAX_SECTION_COUNT = 1<<12;

    private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_SECTION_COUNT*8);//Stored as ivec2
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Shader rasterShader;
    private final RenderProperties properties;

    //CPU-side stream of visible section positions (2 ints each), mirrored to the gpu buffer when
    //it changes; sodium caches its render lists across frames so most frames re-draw the same set
    private int[] visibleSections = new int[INIT_MAX_SECTION_COUNT*2];
    private int count;
    private boolean changed;

    private final AbstractRenderPipeline pipeline;
    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.properties = pipeline.properties;

        String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            this.pipeline = pipeline;
            vert = vert+"\n\n\n"+taa;
        } else {
            this.pipeline = null;
        }

        this.rasterShader = Shader.makeAuto()
                .addSource(ShaderType.VERTEX, vert)
                .defineIf("TAA", taa != null)
                .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
                .apply(this.properties::apply)
                .compile()
                .ubo(0, this.uniformBuffer)
                .ssbo(1, this.chunkPosBuffer);
    }

    //Called from sodium's section-list traversal for every visible built section
    public void put(long pos) {
        this.visibleSections[this.count++] = (int) pos;
        this.visibleSections[this.count++] = (int) (pos >>> 32);
        if (this.count >= this.visibleSections.length - 2) {
            this.visibleSections = Arrays.copyOf(this.visibleSections, (int) (this.visibleSections.length * 1.25));
        }
    }

    //Called when sodium starts rebuilding its render list (and on level swap)
    public void reset() {
        this.count = 0;
        this.changed = true;
    }

    //How many sodium-visible sections the last mask pass rasterised. This is what the mask's fill cost
    //scales with, so it is the number to read when chunk-mesh count is suspected of driving frame time.
    private int lastRenderedSectionCount;

    public int getLastRenderedSectionCount() {
        return this.lastRenderedSectionCount;
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    public void render(Viewport<?> viewport) {
        viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());

        int sectionCount = this.count >> 1;
        this.lastRenderedSectionCount = sectionCount;
        if (sectionCount == 0) {
            return;
        }

        if (this.changed) {
            if (this.count * 4L > this.chunkPosBuffer.size()) {
                this.chunkPosBuffer.free();
                this.chunkPosBuffer = new GlBuffer((long) Math.ceil(this.count * 1.25) * 4L);
                ((AutoBindingShader) this.rasterShader).ssbo(1, this.chunkPosBuffer);
            }
            long ptr = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 0, this.count * 4L);
            for (int i = 0; i < this.count; i++) {
                MemoryUtil.memPutInt(ptr + i * 4L, this.visibleSections[i]);
            }
            UploadStream.INSTANCE.commit();
            this.changed = false;
        }

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr; ptr += 4*4*4;

        final float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance()*16;//In blocks

        {//This is recomputed to be in chunk section space not worldsection

            //Camera block pos. floor, not a cast: a cast truncates toward zero, so at negative
            //coordinates it lands one block the wrong way and the sub-block remainder below comes out
            //negative, shifting the whole mask by a block on that side of the origin.
            int bx = (int)Math.floor(viewport.cameraX);
            int by = (int)Math.floor(viewport.cameraY);
            int bz = (int)Math.floor(viewport.cameraZ);
            new Vector3i(bx, by, bz).getToAddress(ptr); ptr += 4*4;

            var negInnerBlock = new Vector3f(
                    (float) (viewport.cameraX - bx),
                    (float) (viewport.cameraY - by),
                    (float) (viewport.cameraZ - bz));


            negInnerBlock.getToAddress(ptr); ptr += 4*3;
            viewport.MVP.translate(negInnerBlock.negate(), new Matrix4f()).getToAddress(matPtr);
            MemoryUtil.memPutFloat(ptr, renderDistance); ptr += 4;
        }
        UploadStream.INSTANCE.commit();


        {
            //need to reverse the winding order since we want the back faces of the AABB, not the front

            glFrontFace(GL_CW);//Reverse winding order

            //"reverse depth buffer" it goes from 0->1 where 1 is far away
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(this.properties.furtherDepthCompare());
        }

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.bind();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        if (this.pipeline != null) this.pipeline.bindUniforms();//shader TAA

        //Batch the draws into groups of size 32
        if (sectionCount >= 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, sectionCount/32);
        }
        if (sectionCount%32 != 0) {
            glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6 * 2 * 3 * (sectionCount%32), GL_UNSIGNED_BYTE, 0, 1, (sectionCount/32)*32);
        }

        {
            glFrontFace(GL_CCW);//Restore winding order

            glDepthFunc(this.properties.closerEqualDepthCompare());

            //TODO: check this is correct
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }
    }

    public void free() {
        this.rasterShader.free();
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
    }
}
