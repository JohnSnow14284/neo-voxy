package me.cortex.voxy.client.config;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.config.SodiumConfigBuilder.*;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.compat.far.FarEntityClient;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

@ConfigEntryPointForge("voxy")
public class VoxyConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder B) {
        if (!VoxyCommon.isAvailable()) return;//Dont even register the config if its not avalible

        var CFG = VoxyConfig.CONFIG;
        boolean sableInstalled = ModList.get().isLoaded("sable");
        boolean createInstalled = ModList.get().isLoaded("create");
        boolean seasonsInstalled = ModList.get().isLoaded("eclipticseasons");

        var cc = B.registerModOptions("voxy", VoxyCommon.displayName(), VoxyCommon.MOD_VERSION)
                .setIcon(ResourceLocation.parse("voxy:icon.png"));

        final var RENDER_RELOAD = OptionFlag.REQUIRES_RENDERER_RELOAD.getId().toString();

        SodiumConfigBuilder.buildToSodium(B, cc, CFG::save, postOp->{
                    postOp.register("voxy:update_threads", ()->{
                        var instance = VoxyCommon.getInstance();
                        if (instance != null) {
                            instance.updateDedicatedThreads();
                        }
                    }, "voxy:enabled")
                            .register("voxy:iris_reload", IrisUtil::reload)
                            .register("voxy:refresh_far_entities", FarEntityClient::sendHello)
                            .register("voxy:refresh_chunk_request", ()->{
                                var minecraft = Minecraft.getInstance();
                                if (minecraft.getConnection() != null) {
                                    minecraft.options.broadcastOptions();
                                }
                            });
                },
                new Page(Component.translatable("voxy.config.general"),
                        new Group(
                                new BoolOption(
                                        "voxy:enabled",
                                        Component.translatable("voxy.config.general.enabled"),
                                        ()->CFG.enabled, v->{
                                            CFG.enabled=v;
                                            //we need to special case enabled, since the render reload flag runs befor us and its quite important we get it right
                                            if (v && ClientSessionEvents.inSession) {//We should only load when we are in session
                                                VoxyCommon.createInstance();
                                            }
                                        })
                                        .setPostChangeRunner(c->{
                                            if (!c) {
                                                var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                                if (vrsh != null) {
                                                    vrsh.voxy$shutdownRenderer();
                                                }
                                                VoxyCommon.shutdownInstance();
                                            }
                                        }).setPostChangeFlags(RENDER_RELOAD, "voxy:iris_reload").setEnabler(null)
                        ), new Group(
                                new IntOption(
                                        "voxy:thread_count",
                                        Component.translatable("voxy.config.general.serviceThreads"),
                                        ()->CFG.serviceThreads, v->CFG.serviceThreads=v,
                                        new Range(1, CpuLayout.getCoreCount(), 1))
                                        .setPostChangeFlags("voxy:update_threads"),
                                new BoolOption(
                                        "voxy:use_sodium_threads",
                                        Component.translatable("voxy.config.general.useSodiumBuilder"),
                                        ()->!CFG.dontUseSodiumBuilderThreads, v->CFG.dontUseSodiumBuilderThreads=!v)
                                        .setPostChangeFlags("voxy:update_threads", RENDER_RELOAD)
                        ), new Group(
                                new BoolOption(
                                        "voxy:ingest_enabled",
                                        Component.translatable("voxy.config.general.ingest"),
                                        ()->CFG.ingestEnabled, v->CFG.ingestEnabled=v),
                                new BoolOption(
                                        "voxy:show_join_message",
                                        Component.translatable("voxy.config.general.showJoinMessage"),
                                        ()->CFG.showJoinMessage, v->CFG.showJoinMessage=v)
                        )
                ).setEnabler("voxy:enabled"),
                new Page(Component.translatable("voxy.config.rendering"),
                        new Group(
                                new BoolOption(
                                        "voxy:rendering",
                                        Component.translatable("voxy.config.general.rendering"),
                                        ()->CFG.enableRendering, v->CFG.enableRendering=v)
                                        .setPostChangeRunner(c->{
                                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                                            if (vrsh != null) {
                                                if (c) {
                                                    vrsh.voxy$createRenderer();
                                                } else {
                                                    vrsh.voxy$shutdownRenderer();
                                                }
                                            }
                                        },"voxy:enabled", RENDER_RELOAD)
                                        .setPostChangeFlags("voxy:iris_reload")
                                        .setEnabler("voxy:enabled")
                        ), new Group(
                                new IntOption(
                                        "voxy:subdivsize",
                                        Component.translatable("voxy.config.general.subDivisionSize"),
                                        ()->subDiv2ln(CFG.subDivisionSize), v->CFG.subDivisionSize=ln2subDiv(v),
                                        new Range(0, SUBDIV_IN_MAX, 1))
                                        .setFormatter(v->Component.literal(Integer.toString(Math.round(ln2subDiv(v)))))
                                        .setImpact(OptionImpact.HIGH),
                                new IntOption(
                                        "voxy:render_distance",
                                        Component.translatable("voxy.config.general.renderDistance"),
                                        ()->Math.round(CFG.sectionRenderDistance*16), v->CFG.sectionRenderDistance=((float)v)/16,
                                        new Range(10/*1*16*/, 64*16, 1))
                                        //The value is stored as a float with respect to the size of top level lods, it its increment is a fraction with respect to the size of the bottom level lod
                                        // the value is displayed as a chunk render distance
                                        .setFormatter(v->Component.literal(Integer.toString(v*2)))
                                        .setPostChangeRunner(c->{
                                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                                            if (vrsh != null) {
                                                var vrs = vrsh.voxy$getRenderSystem();
                                                if (vrs != null) {
                                                    //CFG.sectionRenderDistance == c/16
                                                    vrs.setRenderDistance(CFG.sectionRenderDistance);
                                                }
                                            }
                                        }, "voxy:rendering", RENDER_RELOAD)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:render_pressure",
                                        Component.translatable("voxy.config.general.renderPressure"),
                                        ()->CFG.getRenderPressureLevel(), v->CFG.renderPressure=v,
                                        new Range(0, 4, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.general.renderPressure." + v))
                                        .setImpact(OptionImpact.HIGH),
                                new BoolOption(
                                        "voxy:lod_boundary_fade",
                                        Component.translatable("voxy.config.general.lodBoundaryFade.warning")
                                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                                        ()->CFG.enableLodBoundaryFade, v->CFG.enableLodBoundaryFade=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:lod_boundary_fade_length",
                                        Component.translatable("voxy.config.general.lodBoundaryFadeLength"),
                                        ()->CFG.lodBoundaryFadeLength, v->CFG.lodBoundaryFadeLength=v,
                                        new Range(8, 64, 1))
                                        .setFormatter(v->Component.literal(v + " blocks"))
                                        .setEnabler("voxy:lod_boundary_fade")
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:lod_boundary_inset",
                                        Component.translatable("voxy.config.general.lodBoundaryInset"),
                                        ()->CFG.lodBoundaryInset, v->CFG.lodBoundaryInset=v,
                                        new Range(8, 32, 1))
                                        .setFormatter(v->Component.literal(v + " blocks"))
                                        .setEnabler("voxy:lod_boundary_fade")
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:distant_beacon_beams",
                                        Component.translatable("voxy.config.general.distantBeaconBeams"),
                                        ()->CFG.distantBeaconBeams, v->CFG.distantBeaconBeams=v)
                                        .setImpact(OptionImpact.LOW),
                                new EnumOption<>(
                                        "voxy:leaf_lod_mode",
                                        VoxyConfig.LeafLodMode.class,
                                        Component.translatable("voxy.config.general.leafLodMode"),
                                        CFG::getLeafLodMode,
                                        CFG::setLeafLodMode)
                                        .setNameProvider(mode -> Component.translatable(
                                                "voxy.config.general.leafLodMode." + mode.name().toLowerCase(java.util.Locale.ROOT)))
                                        .setPostChangeFlags(RENDER_RELOAD)
                                        .setImpact(OptionImpact.MEDIUM)
                        ), new Group(
                                new BoolOption(
                                    "voxy:environmental_fog",
                                    Component.translatable("voxy.config.general.environmental_fog"),
                                    () -> CFG.useEnvironmentalFog,
                                    v -> CFG.useEnvironmentalFog = v),
                                new EnumOption<>("voxy:ssao_mode",
                                        SSAO.SSAOMode.class,
                                        Component.translatable("voxy.config.general.ssao_mode"),
                                        ()->CFG.getSSAOMode(), v->CFG.setSSAOMode(v))
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setPostChangeFlags(RENDER_RELOAD)
                        )
                        .setEnablerInherit(s->!IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD), new Group(
                                new BoolOption(
                                        "voxy:adapt_cloud_distance",
                                        Component.translatable("voxy.config.general.adaptCloudDistance"),
                                        ()->CFG.adaptCloudDistance, v->CFG.adaptCloudDistance=v),
                                new IntOption(
                                        "voxy:cloud_distance",
                                        Component.translatable("voxy.config.general.cloudDistance"),
                                        ()->CFG.cloudDistance, v->CFG.cloudDistance=v,
                                        new Range(0, VoxyConfig.MAX_CLOUD_DISTANCE, 1))
                                        .setImpact(OptionImpact.LOW)
                        )
                        .setEnablerInherit(s->!IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD), new Group(
                                new IntOption(
                                        "voxy:fog_intensity",
                                        Component.translatable("voxy.config.general.fogIntensity"),
                                        ()->Math.round(CFG.fogIntensity * 100), v->CFG.fogIntensity=v / 100.0f,
                                        new Range(0, 100, 1))
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:fog_density",
                                        Component.translatable("voxy.config.general.fogDensity"),
                                        ()->Math.round(CFG.fogDensity * 100), v->CFG.fogDensity=v / 100.0f,
                                        new Range(0, 100, 1))
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:sky_fog_distance",
                                        Component.translatable("voxy.config.general.skyFogDistance"),
                                        ()->CFG.skyFogDistance, v->CFG.skyFogDistance=v,
                                        new Range(0, 1024, 1))
                                        .setImpact(OptionImpact.LOW)
                                        .setPostChangeFlags(RENDER_RELOAD),
                                new IntOption(
                                        "voxy:fog_distance",
                                        Component.translatable("voxy.config.general.fog_distance"),
                                        ()->CFG.fogDistancePercent, v->CFG.fogDistancePercent=v,
                                        new Range(25, 2000, 5))
                                        .setFormatter(v->Component.literal(v+"%"))
                                        .setImpact(OptionImpact.LOW)
                        )
                        .setEnablerInherit(s->!IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD)
                ).setEnablerAND("voxy:enabled", "voxy:rendering"),
                new Page(Component.translatable("voxy.config.fakesight"),
                        new Group(
                                new BoolOption(
                                        "voxy:fakesight_enabled",
                                        Component.translatable("voxy.config.fakesight.enabled"),
                                        ()->CFG.enableExtendedRequestDistance,
                                        v->CFG.enableExtendedRequestDistance=v)
                                        .setPostChangeFlags("voxy:refresh_chunk_request")
                                        .setImpact(OptionImpact.HIGH),
                                new BoolOption(
                                        "voxy:fakesight_follow_lod",
                                        Component.translatable("voxy.config.fakesight.followLod"),
                                        ()->CFG.followLodRequestDistance,
                                        v->CFG.followLodRequestDistance=v)
                                        .setPostChangeFlags("voxy:refresh_chunk_request")
                                        .setEnabler("voxy:fakesight_enabled")
                                        .setImpact(OptionImpact.HIGH),
                                new IntOption(
                                        "voxy:fakesight_request_distance",
                                        Component.translatable("voxy.config.fakesight.distance"),
                                        CFG::getRequestDistance, v->CFG.requestDistance=v,
                                        new Range(VoxyConfig.MIN_REQUEST_DISTANCE,
                                                VoxyConfig.MAX_REQUEST_DISTANCE, 1))
                                        .setFormatter(v->Component.literal(Integer.toString(v)))
                                        .setPostChangeFlags("voxy:refresh_chunk_request")
                                        .setEnabler(s -> s.readBooleanOption(ResourceLocation.parse("voxy:fakesight_enabled"))
                                                        && !s.readBooleanOption(ResourceLocation.parse("voxy:fakesight_follow_lod")),
                                                "voxy:fakesight_enabled", "voxy:fakesight_follow_lod")
                                        .setImpact(OptionImpact.HIGH)
                        )
                ).setEnablerAND("voxy:enabled", "voxy:rendering"),
                new Page(Component.translatable("voxy.config.farEntities"),
                        new Group(
                                new BoolOption(
                                        "voxy:far_players",
                                        Component.translatable("voxy.config.farEntities.players"),
                                        ()->CFG.enableFarPlayerRendering, v->CFG.enableFarPlayerRendering=v)
                                        .setPostChangeFlags("voxy:refresh_far_entities"),
                                new BoolOption(
                                        "voxy:far_vehicles",
                                        Component.translatable("voxy.config.farEntities.vehicles"),
                                        ()->CFG.enableFarVehicleRendering, v->CFG.enableFarVehicleRendering=v)
                                        .setPostChangeFlags("voxy:refresh_far_entities"),
                                new BoolOption(
                                        "voxy:far_player_names",
                                        Component.translatable("voxy.config.farEntities.names"),
                                        ()->CFG.renderFarPlayerNames, v->CFG.renderFarPlayerNames=v)
                                        .setEnabler("voxy:far_players"),
                                new IntOption(
                                        "voxy:far_player_animation_distance",
                                        Component.translatable("voxy.config.farEntities.animationDistance"),
                                        ()->CFG.farPlayerAnimationDistance, v->CFG.farPlayerAnimationDistance=v,
                                        new Range(0, 32768, 64))
                                        .setFormatter(v->Component.literal(v + " blocks"))
                                        .setEnabler("voxy:far_players")
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:share_far_player_position",
                                        Component.translatable("voxy.config.farEntities.sharePosition"),
                                        ()->CFG.shareFarPlayerPosition, v->CFG.shareFarPlayerPosition=v)
                                        .setPostChangeFlags("voxy:refresh_far_entities")
                        )
                ).setEnablerAND("voxy:enabled", "voxy:rendering"),
                new Page(Component.translatable("voxy.config.compat"),
                        new Group(
                                new BoolOption(
                                        "voxy:sable_lod",
                                        Component.translatable("voxy.config.compat.sableLod"),
                                        ()->CFG.sableLodRendering, v->CFG.sableLodRendering=v),
                                new IntOption(
                                        "voxy:sable_lod_distance",
                                        Component.translatable("voxy.config.compat.sableLodDistance"),
                                        ()->CFG.simulatedContraptionRenderDistancePercent,
                                        v->CFG.simulatedContraptionRenderDistancePercent=v,
                                        new Range(0, 100, 5))
                                        .setFormatter(v->Component.literal(v+"%"))
                                        .setImpact(OptionImpact.MEDIUM)
                        ).setEnablerInherit(s->sableInstalled), new Group(
                                new BoolOption(
                                        "voxy:distant_trains",
                                        Component.translatable("voxy.config.compat.distantTrains"),
                                        ()->CFG.distantTrains, v->CFG.distantTrains=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_train_distance",
                                        Component.translatable("voxy.config.compat.distantTrainDistance"),
                                        ()->CFG.distantTrainMaxChunks, v->CFG.distantTrainMaxChunks=v,
                                        new Range(0, VoxyConfig.MAX_CREATE_DISTANCE_CHUNKS, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:distant_tracks",
                                        Component.translatable("voxy.config.compat.distantTracks"),
                                        ()->CFG.distantTracks, v->CFG.distantTracks=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_track_distance",
                                        Component.translatable("voxy.config.compat.distantTrackDistance"),
                                        ()->CFG.distantTrackMaxChunks, v->CFG.distantTrackMaxChunks=v,
                                        new Range(0, VoxyConfig.MAX_CREATE_DISTANCE_CHUNKS, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:distant_contraptions",
                                        Component.translatable("voxy.config.compat.distantContraptions"),
                                        ()->CFG.distantContraptions, v->CFG.distantContraptions=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_contraption_distance",
                                        Component.translatable("voxy.config.compat.distantContraptionDistance"),
                                        ()->CFG.distantContraptionMaxChunks, v->CFG.distantContraptionMaxChunks=v,
                                        new Range(0, VoxyConfig.MAX_CREATE_DISTANCE_CHUNKS, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:distant_kinetics",
                                        Component.translatable("voxy.config.compat.distantKinetics"),
                                        ()->CFG.distantKinetics, v->CFG.distantKinetics=v)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:kinetic_enclosed_culling",
                                        Component.translatable("voxy.config.compat.kineticEnclosedCulling"),
                                        ()->CFG.kineticEnclosedCulling, v->CFG.kineticEnclosedCulling=v)
                                        .setImpact(OptionImpact.LOW)
                        ).setEnablerInherit(s->createInstalled), new Group(
                                new BoolOption(
                                        "voxy:es_snow_lod",
                                        Component.translatable("voxy.config.compat.esSnowLod"),
                                        ()->CFG.eclipticSeasonsSnowLod, v->CFG.eclipticSeasonsSnowLod=v),
                                new BoolOption(
                                        "voxy:es_lod_auto_reload",
                                        Component.translatable("voxy.config.compat.esLodAutoReload"),
                                        ()->CFG.eclipticSeasonsLodAutoReload, v->CFG.eclipticSeasonsLodAutoReload=v),
                                new BoolOption(
                                        "voxy:es_reload_on_season_change",
                                        Component.translatable("voxy.config.compat.esReloadOnSeasonChange"),
                                        ()->CFG.eclipticSeasonsReloadOnSeasonChange, v->CFG.eclipticSeasonsReloadOnSeasonChange=v)
                        ).setEnablerInherit(s->seasonsInstalled)
                ).setEnabler("voxy:enabled"));

    }


    //0 = follow the LOD radius; otherwise a chunk count. Shared by the three Create distance sliders.
    private static Component formatCreateDistance(int chunks) {
        return chunks == 0
                ? Component.translatable("voxy.config.compat.distanceFollowLod")
                : Component.translatable("voxy.config.compat.distanceChunks", chunks);
    }

    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX/SUBDIV_MIN)/Math.log(2);

    //In range is 0->200
    //Out range is 28->256
    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN*Math.pow(2, SUBDIV_CONST*((double)in/SUBDIV_IN_MAX)));
    }

    //In range is ... any?
    //Out range is 0->200
    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double)in)/SUBDIV_MIN)/Math.log(2))/SUBDIV_CONST)*SUBDIV_IN_MAX);
    }
}
