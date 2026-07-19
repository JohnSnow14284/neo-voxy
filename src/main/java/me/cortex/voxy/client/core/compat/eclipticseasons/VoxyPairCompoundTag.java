package me.cortex.voxy.client.core.compat.eclipticseasons;

import net.minecraft.nbt.CompoundTag;

public class VoxyPairCompoundTag
extends CompoundTag {
    public CompoundTag original;
    public CompoundTag above;

    public VoxyPairCompoundTag setOriginal(CompoundTag original) {
        this.original = original;
        return this;
    }

    public CompoundTag getOriginal() {
        return this.original;
    }

    public VoxyPairCompoundTag setAbove(CompoundTag above) {
        this.above = above;
        return this;
    }

    public CompoundTag getAbove() {
        return this.above;
    }
}

