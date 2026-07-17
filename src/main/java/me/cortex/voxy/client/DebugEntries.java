package me.cortex.voxy.client;

import java.util.List;
import java.util.Map;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.mixin.minecraft.InvokerDebugScreenEntries;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

public class DebugEntries {
   public static final Identifier GPU_DEBUG = Identifier.fromNamespaceAndPath("voxy", "gpu_debug");
   private static boolean previousGpuDebugEnabled = false;

   public static void init() {
      InvokerDebugScreenEntries.voxy$register(
         Identifier.fromNamespaceAndPath("voxy", "version"),
         new DebugScreenEntry() {
            public void display(DebugScreenDisplayer lines, @Nullable Level level, @Nullable LevelChunk levelChunk, @Nullable LevelChunk levelChunk2) {
               if (!VoxyCommon.isAvailable()) {
                  lines.addLine(ChatFormatting.RED + "voxy-" + VoxyCommon.MOD_VERSION);
               } else {
                  VoxyInstance instance = VoxyCommon.getInstance();
                  if (instance == null) {
                     lines.addLine(ChatFormatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);
                  } else {
                     lines.addLine(
                        (IVoxyRenderSystemHolder.getNullable() == null ? ChatFormatting.DARK_GREEN : ChatFormatting.GREEN) + "voxy-" + VoxyCommon.MOD_VERSION
                     );
                  }
               }
            }
         }
      );
      InvokerDebugScreenEntries.voxy$register(Identifier.fromNamespaceAndPath("voxy", "debug"), new VoxyDebugScreenEntry());
      InvokerDebugScreenEntries.voxy$register(
         GPU_DEBUG,
         new DebugScreenEntry() {
            public void display(
               DebugScreenDisplayer debugScreenDisplayer, @Nullable Level level, @Nullable LevelChunk levelChunk, @Nullable LevelChunk levelChunk2
            ) {
            }
         }
      );
   }

   public static void onRebuild(Map<Identifier, DebugScreenEntryStatus> allStatuses, List<Identifier> enabled) {
      DebugScreenEntryStatus entry = allStatuses.getOrDefault(GPU_DEBUG, DebugScreenEntryStatus.NEVER);
      if (entry != DebugScreenEntryStatus.NEVER != previousGpuDebugEnabled) {
         previousGpuDebugEnabled ^= true;
         GPUTiming.INSTANCE.setEnabled(previousGpuDebugEnabled);
         RenderStatistics.enabled = previousGpuDebugEnabled;
         LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
         if (renderer != null) {
            renderer.allChanged();
         }
      }
   }
}
