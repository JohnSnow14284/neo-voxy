package me.cortex.voxy.client;

import java.util.ArrayList;
import java.util.List;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

public class VoxyDebugScreenEntry implements DebugScreenEntry {
   public void display(DebugScreenDisplayer lines, @Nullable Level world, @Nullable LevelChunk clientChunk, @Nullable LevelChunk chunk) {
      if (VoxyCommon.isAvailable()) {
         VoxyInstance instance = VoxyCommon.getInstance();
         if (instance != null) {
            VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
            List<String> instanceLines = new ArrayList<>();
            instance.addDebug(instanceLines);
            lines.addToGroup(Identifier.fromNamespaceAndPath("voxy", "instance_debug"), instanceLines);
            if (vrs != null) {
               List<String> renderLines = new ArrayList<>();
               vrs.addDebugInfo(renderLines);
               lines.addToGroup(Identifier.fromNamespaceAndPath("voxy", "render_debug"), renderLines);
            }
         }
      }
   }
}
