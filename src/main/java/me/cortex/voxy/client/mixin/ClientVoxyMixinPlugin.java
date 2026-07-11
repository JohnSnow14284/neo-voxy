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
    private static boolean aeronauticsInstalled;

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
        aeronauticsInstalled = isLoadedEarly("aeronautics") || isLoadedEarly("aeronautics_bundled");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        // Keep Voxy on its normal Sodium renderer hook. The earlier late-tail experiment did not
        // address the missing parent-world SOLID pass and moved Voxy away from the renderer path
        // used by the rest of this port.
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }

        // Aeronautics adds custom chunk layers whose setup/cleanup branch depends on mutable shader
        // state. When Sable renders those layers a second time for transformed sub-levels, the next
        // parent-world SOLID pass can inherit a disabled colour mask or front-face culling. Repair
        // only that combination and only immediately before Sodium draws the SOLID terrain pass.
        if (sableInstalled && aeronauticsInstalled) {
            mixins.add("sable.MixinAeronauticsSolidPassState");
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