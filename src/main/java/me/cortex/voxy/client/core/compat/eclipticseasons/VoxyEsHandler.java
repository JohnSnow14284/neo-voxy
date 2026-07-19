package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.teamtea.eclipticseasons.api.event.SolarTermChangeEvent;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;

public class VoxyEsHandler {
    public static final VoxyEsHandler INSTANCE = new VoxyEsHandler();

    @SubscribeEvent
    public void onSolarTermChangeEvent(SolarTermChangeEvent event) {
        if (event.getLevel() == Minecraft.getInstance().level && VoxyConfig.CONFIG.eclipticSeasonsReloadOnSeasonChange) {
            try {
                IGetVoxyRenderSystem levelRenderer = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                levelRenderer.voxy$shutdownRenderer();
                levelRenderer.voxy$createRenderer();
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}

