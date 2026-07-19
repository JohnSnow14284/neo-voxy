package me.cortex.voxy.commonImpl.mixin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

//Gates the sable contraption-compat common mixins (and the vanilla ServerLevel ticket hook that only
//serves sable) on sable being installed. They are @Pseudo / reference sable types, so registering them
//unconditionally in a config with no plugin makes voxy hard-depend on sable and crash without it.
//Adding them dynamically here means they are never even loaded when sable is absent.
public class CommonVoxyMixinPlugin implements IMixinConfigPlugin {
    private boolean sableInstalled;
    private boolean createInstalled;

    private static boolean modOnLoadingList(String id) {
        try {
            var ll = FMLLoader.getLoadingModList();
            if (ll == null) {
                return false;
            }
            for (var m : ll.getMods()) {
                if (id.equals(m.getModId())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {
        sableInstalled = modOnLoadingList("sable");
        createInstalled = modOnLoadingList("create");
    }

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (sableInstalled) {
            mixins.add("minecraft.MixinServerLevel");
            if (FMLLoader.getDist() == Dist.CLIENT) {
                //References ClientLevel; attaching on a dedicated server crashes the mixin transformer once
                //sable's ClientSubLevel class gets touched server side.
                mixins.add("sable.MixinClientSubLevelFinalizeLighting");
            }
            mixins.add("sable.MixinPhysicsChunkTicketManager");
            mixins.add("sable.MixinSubLevelHoldingChunk");
            mixins.add("sable.MixinSubLevelHoldingChunkMap");
            mixins.add("sable.MixinSubLevelTrackingSystem");
            mixins.add("sable.SableSubLevelHoldingChunkMapAccessor");
            if (createInstalled) {
                //Ship-borne contraption entities must track as far as their ship's hull does, or the
                //structure pops off the distant hull at the entity view distance (references Create)
                mixins.add("sable.MixinChunkMapTrackedEntityShip");
            }
        }
        return mixins;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
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
