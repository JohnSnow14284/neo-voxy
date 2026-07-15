package me.cortex.voxy.compat.far;

import me.cortex.voxy.compat.far.FarEntityProtocol.ContraptionSnapshot;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public interface FarContraptionProvider {
    List<ContraptionSnapshot> collect(ServerPlayer viewer, int maximumDistance);
}
