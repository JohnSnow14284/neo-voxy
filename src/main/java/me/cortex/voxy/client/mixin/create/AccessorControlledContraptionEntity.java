package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//The bearing/piston/gantry block driving a controlled contraption - the kinetic snapshot at this
//position is the other frozen half of the same drivetrain, and the two freeze in the same tick
//through it (no getter upstream).
@Mixin(ControlledContraptionEntity.class)
public interface AccessorControlledContraptionEntity {
    @Accessor("controllerPos")
    BlockPos voxy$getControllerPos();
}
