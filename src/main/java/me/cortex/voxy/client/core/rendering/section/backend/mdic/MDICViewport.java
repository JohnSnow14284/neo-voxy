package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;

public class MDICViewport extends Viewport<MDICViewport> {
   public final GlBuffer drawCountCallBuffer = new GlBuffer(1024L).zero();
   public final GlBuffer drawCallBuffer = new GlBuffer(12000000L).zero();
   public final GlBuffer positionScratchBuffer = new GlBuffer(3200000L).zero();
   public final GlBuffer indirectLookupBuffer = new GlBuffer(800004L);
   public final GlBuffer visibilityBuffer;

   public MDICViewport(RenderProperties properties, int maxSectionCount) {
      super(properties);
      this.visibilityBuffer = new GlBuffer(maxSectionCount * 4L);
   }

   @Override
   protected void delete0() {
      super.delete0();
      this.visibilityBuffer.free();
      this.indirectLookupBuffer.free();
      this.drawCountCallBuffer.free();
      this.drawCallBuffer.free();
      this.positionScratchBuffer.free();
   }

   @Override
   public GlBuffer getRenderList() {
      return this.indirectLookupBuffer;
   }
}
