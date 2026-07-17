package me.cortex.voxy.client.config;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class VoxyConfigMenu implements ConfigEntryPoint {
   private static final int SUBDIV_IN_MAX = 100;
   private static final double SUBDIV_MIN = 28.0;
   private static final double SUBDIV_MAX = 256.0;
   private static final double SUBDIV_CONST = Math.log(9.142857142857142) / Math.log(2.0);

   public void registerConfigLate(ConfigBuilder B) {
      if (VoxyCommon.isAvailable()) {
         VoxyConfig CFG = VoxyConfig.CONFIG;
         ModOptionsBuilder cc = B.registerModOptions("voxy", "Voxy", VoxyCommon.MOD_VERSION).setIcon(Identifier.parse("voxy:icon.png"));
         String RENDER_RELOAD = OptionFlag.REQUIRES_RENDERER_RELOAD.getId().toString();
         SodiumConfigBuilder.buildToSodium(
            B,
            cc,
            CFG::save,
            postOp -> postOp.register("voxy:update_threads", () -> {
               VoxyInstance instance = VoxyCommon.getInstance();
               if (instance != null) {
                  instance.updateDedicatedThreads();
               }
            }, "voxy:enabled").register("voxy:iris_reload", () -> IrisUtil.reload()),
            new SodiumConfigBuilder.Page(
                  Component.translatable("voxy.config.general"),
                  new SodiumConfigBuilder.Group(
                     (SodiumConfigBuilder.Option)new SodiumConfigBuilder.BoolOption(
                           "voxy:enabled", Component.translatable("voxy.config.general.enabled"), () -> CFG.enabled, v -> {
                              CFG.enabled = v;
                              if (v && ClientSessionEvents.inSession) {
                                 VoxyCommon.createInstance();
                              }
                           }
                        )
                        .setPostChangeRunner(c -> {
                           if (!c) {
                              IVoxyRenderSystemHolder vrsh = IVoxyRenderSystemHolder.getNullableHolder();
                              if (vrsh != null) {
                                 vrsh.voxy$shutdownRenderer();
                              }

                              VoxyCommon.shutdownInstance();
                           }
                        })
                        .setPostChangeFlags(RENDER_RELOAD, "voxy:iris_reload")
                        .setEnabler(null)
                  ),
                  new SodiumConfigBuilder.Group(
                     new SodiumConfigBuilder.IntOption(
                           "voxy:thread_count",
                           Component.translatable("voxy.config.general.serviceThreads"),
                           () -> CFG.serviceThreads,
                           v -> CFG.serviceThreads = v,
                           new Range(1, CpuLayout.getCoreCount(), 1)
                        )
                        .setPostChangeFlags("voxy:update_threads"),
                     new SodiumConfigBuilder.BoolOption(
                           "voxy:use_sodium_threads",
                           Component.translatable("voxy.config.general.useSodiumBuilder"),
                           () -> !CFG.dontUseSodiumBuilderThreads,
                           v -> CFG.dontUseSodiumBuilderThreads = !v
                        )
                        .setPostChangeFlags("voxy:update_threads", RENDER_RELOAD)
                  ),
                  new SodiumConfigBuilder.Group(
                     new SodiumConfigBuilder.BoolOption(
                        "voxy:ingest_enabled", Component.translatable("voxy.config.general.ingest"), () -> CFG.ingestEnabled, v -> CFG.ingestEnabled = v
                     )
                  )
               )
               .setEnabler("voxy:enabled"),
            new SodiumConfigBuilder.Page(
                  Component.translatable("voxy.config.rendering"),
                  new SodiumConfigBuilder.Group(
                     (SodiumConfigBuilder.Option)new SodiumConfigBuilder.BoolOption(
                           "voxy:rendering", Component.translatable("voxy.config.general.rendering"), () -> CFG.enableRendering, v -> CFG.enableRendering = v
                        )
                        .setPostChangeRunner(c -> {
                           IVoxyRenderSystemHolder vrsh = IVoxyRenderSystemHolder.getNullableHolder();
                           if (vrsh != null) {
                              if (c) {
                                 vrsh.voxy$createRenderer();
                              } else {
                                 vrsh.voxy$shutdownRenderer();
                              }
                           }
                        }, "voxy:enabled", RENDER_RELOAD)
                        .setPostChangeFlags("voxy:iris_reload")
                        .setEnabler("voxy:enabled")
                  ),
                  new SodiumConfigBuilder.Group(
                     new SodiumConfigBuilder.IntOption(
                           "voxy:subdivsize",
                           Component.translatable("voxy.config.general.subDivisionSize"),
                           () -> subDiv2ln(CFG.subDivisionSize),
                           v -> CFG.subDivisionSize = ln2subDiv(v),
                           new Range(0, 100, 1)
                        )
                        .setFormatter(v -> Component.literal(Integer.toString(Math.round(ln2subDiv(v)))))
                        .setImpact(OptionImpact.HIGH),
                     new SodiumConfigBuilder.IntOption(
                           "voxy:render_distance",
                           Component.translatable("voxy.config.general.renderDistance"),
                           () -> Math.round(CFG.sectionRenderDistance * 16.0F),
                           v -> CFG.sectionRenderDistance = v.intValue() / 16.0F,
                           new Range(10, 1024, 1)
                        )
                        .setFormatter(v -> Component.literal(Integer.toString(v * 2)))
                        .setPostChangeRunner(c -> {
                           VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
                           if (vrs != null) {
                              vrs.setRenderDistance(CFG.sectionRenderDistance);
                           }
                        }, "voxy:rendering", RENDER_RELOAD)
                        .setImpact(OptionImpact.MEDIUM)
                  ),
                  new SodiumConfigBuilder.Group(
                        new SodiumConfigBuilder.BoolOption(
                              "voxy:eviromental_fog",
                              Component.translatable("voxy.config.general.environmental_fog"),
                              () -> CFG.useEnvironmentalFog,
                              v -> CFG.useEnvironmentalFog = v
                           )
                           .setPostChangeFlags(RENDER_RELOAD),
                        new SodiumConfigBuilder.EnumOption<>(
                              "voxy:ssao_mode",
                              SSAO.SSAOMode.class,
                              Component.translatable("voxy.config.general.ssao_mode"),
                              () -> CFG.getSSAOMode(),
                              v -> CFG.setSSAOMode(v)
                           )
                           .setImpact(OptionImpact.MEDIUM)
                           .setPostChangeFlags(RENDER_RELOAD)
                     )
                     .setEnablerInherit(s -> !IrisUtil.irisShadersEnabledInConfig(), ConfigState.UPDATE_ON_REBUILD)
               )
               .setEnablerAND("voxy:enabled", "voxy:rendering")
         );
      }
   }

   private static float ln2subDiv(int in) {
      return (float)(28.0 * Math.pow(2.0, SUBDIV_CONST * (in / 100.0)));
   }

   private static int subDiv2ln(float in) {
      return (int)(Math.log(in / 28.0) / Math.log(2.0) / SUBDIV_CONST * 100.0);
   }
}
