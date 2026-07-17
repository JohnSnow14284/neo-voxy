package me.cortex.voxy;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.common.config.Serialization;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(value = "voxy", dist = Dist.CLIENT)
public final class Voxy {
   public Voxy(IEventBus modEventBus, ModContainer container) {
      Serialization.init();
      VoxyClient.initializeNeoForge(container);
   }
}
