package me.cortex.voxy.commonImpl.mixin.minecraft;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface AccessorChunkMap {
    @Invoker("getPlayerViewDistance")
    int voxy$invokeGetPlayerViewDistance(ServerPlayer player);
}
