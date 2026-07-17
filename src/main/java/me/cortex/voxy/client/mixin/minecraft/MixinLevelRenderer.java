package me.cortex.voxy.client.mixin.minecraft;

import java.util.Objects;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IVoxyRenderSystemHolder {
   @Unique
   @Nullable
   private WorldIdentifier identifier;
   @Unique
   @Nullable
   private VoxyRenderSystem renderer;
   @Unique
   private double voxy$previousChunkFadeTime = Double.NaN;

   @Inject(method = "setLevel", at = @At("HEAD"))
   private void voxy$setLevel(ClientLevel level, CallbackInfo ci) {
      this.voxy$setWorld(level);
   }

   @Inject(method = "allChanged()V", at = @At("RETURN"), order = 900)
   private void voxy$reloadRenderer(CallbackInfo ci) {
      this.voxy$shutdownRenderer();
      this.voxy$createRenderer();
   }

   @Override
   public VoxyRenderSystem voxy$getRenderSystem() {
      return this.renderer;
   }

   @Inject(method = "close", at = @At("HEAD"))
   private void voxy$injectClose(CallbackInfo ci) {
      this.voxy$shutdownRenderer();
   }

   @Override
   public void voxy$shutdownRenderer() {
      if (this.renderer != null) {
         try {
            this.renderer.shutdown();
         } finally {
            this.renderer = null;
            this.voxy$restoreChunkFade();
         }
      }
   }

   @Override
   public void voxy$setWorld(Level level) {
      WorldIdentifier identifier = level == null ? null : WorldIdentifier.of(level);
      if (!Objects.equals(this.identifier, identifier)) {
         this.voxy$shutdownRenderer();
         this.identifier = identifier;
      }
   }

   @Override
   public void voxy$createRenderer() {
      if (this.renderer != null) {
         throw new IllegalStateException("Cannot have multiple renderers");
      } else if (!VoxyConfig.CONFIG.enabled) {
         Logger.info("Not creating renderer due to disabled");
      } else if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
         Logger.info("Not creating renderer due to disabled rendering");
      } else if (this.identifier == null) {
         Logger.info("Not creating renderer due to null identifier");
      } else {
         VoxyClientInstance instance = (VoxyClientInstance)VoxyCommon.getInstance();
         if (instance == null) {
            Logger.info("Not creating renderer due to null instance");
         } else {
            WorldEngine world = this.identifier.getOrCreateEngine(true);
            if (world == null) {
               Logger.warn("Not creating renderer due to null engine");
            } else {
               this.voxy$createEngineDirect(world);
            }
         }
      }
   }

   @Unique
   private void voxy$createEngineDirect(WorldEngine world) {
      VoxyInstance instance = world.instanceIn;
      if (instance == null) {
         throw new IllegalStateException();
      } else {
         try {
            this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
         } catch (RuntimeException var4) {
            if (!IrisUtil.irisShaderPackEnabled()) {
               throw var4;
            }

            IrisUtil.disableIrisShaders();
         }

         if (this.renderer != null) {
            this.voxy$disableChunkFade();
         }

         instance.updateDedicatedThreads();
      }
   }

   @Unique
   private void voxy$disableChunkFade() {
      if (Double.isNaN(this.voxy$previousChunkFadeTime)) {
         OptionInstance<Double> option = Minecraft.getInstance().options.chunkSectionFadeInTime();
         this.voxy$previousChunkFadeTime = (Double)option.get();
         if (this.voxy$previousChunkFadeTime != 0.0) {
            option.set(0.0);
         }
      }
   }

   @Unique
   private void voxy$restoreChunkFade() {
      if (!Double.isNaN(this.voxy$previousChunkFadeTime)) {
         OptionInstance<Double> option = Minecraft.getInstance().options.chunkSectionFadeInTime();
         if ((Double)option.get() == 0.0) {
            option.set(this.voxy$previousChunkFadeTime);
         }

         this.voxy$previousChunkFadeTime = Double.NaN;
      }
   }
}
