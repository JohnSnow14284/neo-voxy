package me.cortex.voxy.client.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;

    private static boolean isLoadedEarly(String modId) {
        var list = LoadingModList.get();
        return list != null && list.getModFileById(modId) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = isLoadedEarly("valkyrienskies");
        nvidiumInstalled = isLoadedEarly("nvidium");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override
    public List<String> getMixins() {
        return List.of(valkyrienSkiesInstalled && !nvidiumInstalled
                ? "sodium.MixinSodiumWorldRendererVS"
                : "sodium.MixinDefaultChunkRenderer");
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