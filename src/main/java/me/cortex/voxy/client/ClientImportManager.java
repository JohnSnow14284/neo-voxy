package me.cortex.voxy.client;

import java.util.Objects;
import java.util.UUID;
import me.cortex.voxy.client.mixin.minecraft.AccessorBossHealthOverlay;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.importers.IDataImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;

public class ClientImportManager extends ImportManager {
   @Override
   protected synchronized ImportManager.ImportTask createImportTask(IDataImporter importer) {
      return new ClientImportManager.ClientImportTask(importer);
   }

   protected class ClientImportTask extends ImportManager.ImportTask {
      private final UUID bossbarUUID;
      private final LerpingBossEvent bossBar;

      protected ClientImportTask(IDataImporter importer) {
         Objects.requireNonNull(ClientImportManager.this);
         super(importer);
         this.bossbarUUID = UUID.randomUUID();
         this.bossBar = new LerpingBossEvent(
            this.bossbarUUID, Component.nullToEmpty("Voxy world importer"), 0.0F, BossBarColor.GREEN, BossBarOverlay.PROGRESS, false, false, false
         );
         Minecraft.getInstance().execute(() -> {
            BossHealthOverlay overlay = Minecraft.getInstance().gui.getBossOverlay();
            ((AccessorBossHealthOverlay)overlay).voxy$getEvents().put(this.bossBar.getId(), this.bossBar);
         });
      }

      @Override
      protected boolean onUpdate(int completed, int outOf) {
         if (!super.onUpdate(completed, outOf)) {
            return false;
         } else {
            Minecraft.getInstance().execute(() -> {
               this.bossBar.setProgress((float)((double)completed / Math.max(1, outOf)));
               this.bossBar.setName(Component.nullToEmpty("Voxy import: " + completed + "/" + outOf + " chunks"));
            });
            return true;
         }
      }

      @Override
      protected void onCompleted(int total) {
         super.onCompleted(total);
         Minecraft.getInstance()
            .execute(
               () -> {
                  BossHealthOverlay overlay = Minecraft.getInstance().gui.getBossOverlay();
                  ((AccessorBossHealthOverlay)overlay).voxy$getEvents().remove(this.bossbarUUID);
                  long delta = Math.max(System.currentTimeMillis() - this.startTime, 1L);
                  String msg = "Voxy world import finished in "
                     + delta / 1000L
                     + " seconds, averaging "
                     + (int)(total / ((float)delta / 1000.0F))
                     + " chunks per second";
                  Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.literal(msg));
                  Logger.info(msg);
               }
            );
      }
   }
}
