package me.cortex.voxy.compat.far;

import me.cortex.voxy.compat.far.FarEntityProtocol.ContraptionBatch;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public interface FarContraptionClient {
    void clear();
    void apply(ContraptionBatch batch);
    void render(RenderLevelStageEvent event);
}
