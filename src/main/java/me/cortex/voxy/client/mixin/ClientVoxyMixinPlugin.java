package me.cortex.voxy.client.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;
    private static boolean connectorInstalled = false;
    private static boolean sableInstalled;

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
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        // Sable and Aeronautics append transformed sub-level geometry at the tail of
        // SodiumWorldRenderer.drawChunkLayer. Rendering Voxy from DefaultChunkRenderer would run
        // too early, before those additions have finished updating the active terrain targets.
        // Use a dedicated late hook for this combination instead.
        if (sableInstalled) {
            mixins.add("sable.MixinSablePostTerrainRenderer");
        } else if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
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