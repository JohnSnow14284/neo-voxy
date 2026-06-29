package me.cortex.voxy.commonImpl.mixin.minecraft;

import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PalettedContainer.class)
public interface AccessorPalettedContainer<T> {
    @Accessor("data")
    PalettedContainer.Data<T> voxy$getData();
}
