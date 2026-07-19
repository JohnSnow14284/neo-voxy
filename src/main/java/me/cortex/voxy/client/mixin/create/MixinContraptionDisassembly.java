package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionDisassemblyPacket;
import me.cortex.voxy.client.compat.create.DistantContraptionManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Entity removal is ambiguous (tracker release vs death), so snapshot cleanup normally rides a 2s
//presence grace - which shows as a ~1s ghost right where a contraption just DISASSEMBLED into
//blocks (entity gone -> live=false -> the renderer stops yielding and draws the frozen copy until
//the grace expires, even up close). Disassembly however has its own explicit signal: this packet.
//Drop the snapshot the moment it arrives; the presence grace stays as the fallback for lost packets.
@Mixin(AbstractContraptionEntity.class)
public class MixinContraptionDisassembly {
    @Inject(method = "handleDisassemblyPacket", at = @At("HEAD"))
    private static void voxy$dropSnapshotOnDisassembly(ContraptionDisassemblyPacket packet, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        var entity = level.getEntity(packet.entityId());
        if (entity != null) {
            DistantContraptionManager.removeDead(entity.getUUID());
        }
    }
}
