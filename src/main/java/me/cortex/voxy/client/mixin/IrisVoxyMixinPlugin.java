package me.cortex.voxy.client.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Prevents Iris compatibility classes from being considered when Iris is absent. */
public final class IrisVoxyMixinPlugin implements IMixinConfigPlugin {
    private boolean irisInstalled;

    @Override
    public void onLoad(String mixinPackage) {
        var list = LoadingModList.get();
        this.irisInstalled = list != null && list.getModFileById("iris") != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return this.irisInstalled;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return List.of(); }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
