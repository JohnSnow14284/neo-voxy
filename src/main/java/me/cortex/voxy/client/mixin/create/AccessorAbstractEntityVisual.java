package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//The visual's entity (erased to Entity), read by the carriage cull for its distance check. Declared
//here rather than on the subclass that reads it.
@Mixin(AbstractEntityVisual.class)
public interface AccessorAbstractEntityVisual {
    @Accessor("entity")
    Entity voxy$getEntity();
}
