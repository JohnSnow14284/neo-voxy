package me.cortex.voxy.client.mixin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    //This experimental depth redirect performs several full-screen depth copies per Sable layer.
    //Some driver/shader-pack combinations terminate in native code on its first frame, leaving no
    //Java crash report. Keep the distance/loading integration, but require an explicit JVM opt-in
    //for this nonessential occlusion enhancement until it can be implemented without framebuffer
    //attachment aliasing.
    private static final boolean ENABLE_UNSAFE_SABLE_DEPTH_SHIM =
            Boolean.getBoolean("voxy.enableUnsafeSableDepthShim");
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;
    private static boolean connectorInstalled = false;
    private static boolean sableInstalled;
    private static boolean eclipticSeasonsInstalled;
    private static boolean createInstalled;

    private static boolean isLoadedEarly(String modId) {
        var list = LoadingModList.get();
        return list != null && list.getModFileById(modId) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = isLoadedEarly("valkyrienskies");
        nvidiumInstalled = isLoadedEarly("nvidium");
        connectorInstalled = isLoadedEarly("connector");
        sableInstalled = isLoadedEarly("sable");
        eclipticSeasonsInstalled = isLoadedEarly("eclipticseasons");
        createInstalled = isLoadedEarly("create");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        // client.voxy.mixins.json is entirely client-rendering (sodium/iris/sable/eclipticseasons targets).
        // None of it applies on a dedicated server and the targets don't exist there, so add nothing server-side.
        if (FMLLoader.getDist() != Dist.CLIENT) {
            return mixins;
        }
        //(sable.MixinSableSubLevelRenderSectionManager omitted: its sable target class was removed in
        // sable 2.0.3 and its sodium ctor target no longer matches sodium 0.8.12.)
        if (sableInstalled) {
            mixins.add("minecraft.MixinGameRendererSableRenderDistance");
            mixins.add("sable.MixinSableReacharoundCulling");
            if (ENABLE_UNSAFE_SABLE_DEPTH_SHIM) {
                mixins.add("sable.MixinSableDepthShim");
            }
        }
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }

        //Distance-cull Create's distant track rendering so it hands over to the LOD copy instead of
        //floating past the view distance (references Create + Flywheel classes). MixinTrackVisual is
        //the real fix under Flywheel (default + iris/colorwheel); MixinTrackRenderer covers the
        //vanilla-BER fallback path when the Flywheel backend is off.
        if (createInstalled) {
            mixins.add("create.MixinTrackRenderer");
            mixins.add("create.MixinTrackVisual");
            mixins.add("create.AccessorContraptionVisual");
            mixins.add("create.AccessorAbstractEntityVisual");
            mixins.add("create.MixinCarriageContraptionVisual");
            mixins.add("create.MixinCarriageContraptionEntityRenderer");
            mixins.add("create.MixinStationRenderer");
            mixins.add("create.MixinContraptionEntityRenderer");
            mixins.add("create.MixinContraptionVisual");
            //Placed kinetic machine blocks: their Flywheel moving parts (rotating shafts/cogs/machine
            //animations) have no distance limit and float over LOD past the render distance. These cull
            //them there - KineticBlockEntityVisual takes the shaft/cog/belt/fan family via a base beginFrame,
            //MachineVisuals the ones that override it, the Renderer the backend-off BER; the accessor
            //feeds `pos`.
            mixins.add("create.AccessorAbstractBlockEntityVisual");
            mixins.add("create.MixinKineticBlockEntityVisual");
            mixins.add("create.MixinKineticMachineVisuals");
            mixins.add("create.MixinBnbKineticVisuals");
            mixins.add("create.MixinAzimuthBehaviourVisual");
            mixins.add("create.MixinVisualizationManagerImpl");
            mixins.add("create.MixinSafeBlockEntityRenderer");
            //Ship-borne contraptions: force open the plot-coordinate render gates that kill them
            //(vanilla dispatcher distance/frustum + EntityCulling, update-rate banding)
            mixins.add("create.MixinEntityRenderDispatcherShip");
            mixins.add("create.MixinBandedPrimeLimiter");
            mixins.add("create.AccessorControlledContraptionEntity");
            //Disassembly is the one removal with an explicit signal: kill the frozen snapshot at once
            //instead of letting the 2s presence grace show a ghost where the blocks just landed
            mixins.add("create.MixinContraptionDisassembly");
        }

        // EclipticSeasons snow-LOD compat: client-gated even for the common-class targets, because the shared
        // VoxyTool references EclipticSeasons client classes (ClientCon) and our delta-sync server also runs ingest.
        if (eclipticSeasonsInstalled && FMLLoader.getDist() == Dist.CLIENT) {
            mixins.add("eclipticseasons.MixinClientLevel");
            mixins.add("eclipticseasons.MixinMapping");
            mixins.add("eclipticseasons.MixinModelBakerySubsystem");
            mixins.add("eclipticseasons.MixinModelFactory");
            mixins.add("eclipticseasons.MixinModelTextureBakery");
            mixins.add("eclipticseasons.MixinWorldConversionFactory");
            mixins.add("eclipticseasons.MixinWorldImporter");
        }

        return mixins;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
