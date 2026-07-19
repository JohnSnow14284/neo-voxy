package me.cortex.voxy.client.mixin.eclipticseasons;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cortex.voxy.common.world.other.Mapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value={Mapper.class})
public abstract class MixinMapping {
    @WrapOperation(remap=false, method={"getBlockStateOpacity(I)I"}, at={@At(value="INVOKE", target="Lit/unimi/dsi/fastutil/objects/ObjectArrayList;get(I)Ljava/lang/Object;")})
    private <K> K eclipticseasons$getBlockStateOpacity_fixId(ObjectArrayList<K> instance, int index, Operation<K> original) {
        return (K)original.call(new Object[]{instance, VoxyTool.fixId((Mapper)(Object)this, index)});
    }

    @WrapOperation(remap=false, method={"getBlockStateFromBlockId"}, at={@At(value="INVOKE", target="Lit/unimi/dsi/fastutil/objects/ObjectArrayList;get(I)Ljava/lang/Object;")})
    private <K> K eclipticseasons$getBlockStateFromBlockId_fixId(ObjectArrayList<K> instance, int index, Operation<K> original) {
        return (K)original.call(new Object[]{instance, VoxyTool.fixId((Mapper)(Object)this, index)});
    }
}

