package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ControlledContraptionEntity.class, remap = false)
public interface ControlledContraptionEntityAccessor {
    @Accessor("prevAngle")
    void voxy$setPreviousAngle(float angle);
}
