package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//The absolute BlockPos of a placed block-entity visual, read by the kinetic culls for their distance
//check. Declared here, several levels above the machine visuals that need it.
@Mixin(AbstractBlockEntityVisual.class)
public interface AccessorAbstractBlockEntityVisual {
    @Accessor("pos")
    BlockPos voxy$getPos();
}
