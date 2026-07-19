package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//The contraption body's Flywheel embedding, whose pose the culls zero out. Declared here rather than
//on the carriage subclass that reads it.
@Mixin(ContraptionVisual.class)
public interface AccessorContraptionVisual {
    @Accessor("embedding")
    VisualEmbedding voxy$getEmbedding();
}
