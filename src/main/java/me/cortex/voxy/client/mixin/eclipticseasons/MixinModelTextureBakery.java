package me.cortex.voxy.client.mixin.eclipticseasons;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyClientTool;
import me.cortex.voxy.client.core.compat.eclipticseasons.IVoxyModelController;
import me.cortex.voxy.client.core.model.bakery.ReuseVertexConsumer;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={SoftwareModelTextureBakery.class})
public abstract class MixinModelTextureBakery
implements IVoxyModelController {
    @Shadow
    @Final
    private ReuseVertexConsumer translucentVC;
    @Shadow
    @Final
    private ReuseVertexConsumer opaqueVC;
    @Unique
    boolean eclipticseasons$snowyBlock = false;

    //Signature tracks the upstream-merged bakeBlockModel: (blockId, state, layer, forceSolidLeaves)
    //returning the ground-cross verdict, so TAIL needs a CallbackInfoReturnable
    @Inject(remap=false, method={"bakeBlockModel"}, at={@At(value="TAIL")})
    private void eclipticseasons$bakeBlockModel_pre(int blockId, BlockState state, RenderType layer, boolean forceSolidLeaves,
                                                    org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir,
                                                    @Share(value="snowy_model") LocalRef<BakedModel> modelLocalRef) {
        if (this.isSnowyBlock()) {
            VoxyClientTool.renderToStream(state, layer, this.translucentVC, this.opaqueVC);
        }
    }

    @Override
    public void setSnowyBlock(boolean snowyBlock) {
        this.eclipticseasons$snowyBlock = snowyBlock;
    }

    @Override
    public boolean isSnowyBlock() {
        return this.eclipticseasons$snowyBlock;
    }
}

