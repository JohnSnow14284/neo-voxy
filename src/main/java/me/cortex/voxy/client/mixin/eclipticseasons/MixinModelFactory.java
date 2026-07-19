package me.cortex.voxy.client.mixin.eclipticseasons;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool;
import me.cortex.voxy.client.core.compat.eclipticseasons.IVoxyModelController;
import java.lang.reflect.Method;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={ModelFactory.class})
public abstract class MixinModelFactory {
    //Resolve the bake-result's blockId() accessor once per class instead of scanning the method table
    //(getDeclaredMethod + setAccessible) on every polled bake result.
    @org.spongepowered.asm.mixin.Unique
    private static final ClassValue<Method> eclipticseasons$blockIdMethod = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                Method m = type.getDeclaredMethod("blockId");
                m.setAccessible(true);
                return m;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    };

    @Shadow(remap=false)
    @Final
    public SoftwareModelTextureBakery bakery2;
    @Shadow(remap=false)
    @Final
    private Mapper mapper;

    @WrapOperation(remap=false, method={"addEntry"}, at={@At(value="INVOKE", target="Lme/cortex/voxy/common/world/other/Mapper;getBlockStateFromBlockId(I)Lnet/minecraft/world/level/block/state/BlockState;")})
    private BlockState eclipticseasons$addEntry_setBS(Mapper instance, int blockId, Operation<BlockState> original) {
        return (BlockState)original.call(new Object[]{instance, VoxyTool.fixId(instance, blockId, i -> {})});
    }

    @Inject(remap=false, method={"addEntry"}, at={@At(value="RETURN")})
    private void eclipticseasons$addEntry_clean(int blockId, CallbackInfoReturnable<Boolean> cir) {
    }

    @Inject(remap=false, method={"processModelResult"}, at={@At(value="RETURN")})
    private void eclipticseasons$processModelResult_return(CallbackInfoReturnable<Boolean> cir) {
        SoftwareModelTextureBakery softwareModelTextureBakery = this.bakery2;
        if (softwareModelTextureBakery instanceof IVoxyModelController) {
            IVoxyModelController modelController = (IVoxyModelController)softwareModelTextureBakery;
            modelController.setSnowyBlock(false);
        }
    }

    @ModifyExpressionValue(remap=false, method={"processModelResult"}, at={@At(value="INVOKE", target="Ljava/util/concurrent/ConcurrentLinkedDeque;poll()Ljava/lang/Object;")})
    private <E> E eclipticseasons$processModelResult_setBS(E original, @Share(value="isSnowyBlock") LocalBooleanRef ref) {
        if (original != null) {
            try {
                Method m = eclipticseasons$blockIdMethod.get(original.getClass());
                if (m != null) {
                    int blockId = (Integer)m.invoke(original, new Object[0]);
                    VoxyTool.fixId(this.mapper, blockId, i -> {
                        SoftwareModelTextureBakery patt0$temp = this.bakery2;
                        if (patt0$temp instanceof IVoxyModelController) {
                            IVoxyModelController modelController = (IVoxyModelController)patt0$temp;
                            modelController.setSnowyBlock(true);
                        }
                    });
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
        return original;
    }
}

