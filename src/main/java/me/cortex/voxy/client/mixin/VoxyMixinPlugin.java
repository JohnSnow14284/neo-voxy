package me.cortex.voxy.client.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class VoxyMixinPlugin implements IMixinConfigPlugin {
   private boolean irisLoaded;

   public void onLoad(String mixinPackage) {
      this.irisLoaded = isClassPresent("net.irisshaders.iris.Iris");
   }

   private static boolean isClassPresent(String className) {
      try {
         Class.forName(className, false, VoxyMixinPlugin.class.getClassLoader());
         return true;
      } catch (LinkageError | ClassNotFoundException var2) {
         return false;
      }
   }

   public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
      return !mixinClassName.startsWith("me.cortex.voxy.client.mixin.iris.") || this.irisLoaded;
   }

   public String getRefMapperConfig() {
      return null;
   }

   public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
   }

   public List<String> getMixins() {
      return null;
   }

   public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }

   public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }
}
