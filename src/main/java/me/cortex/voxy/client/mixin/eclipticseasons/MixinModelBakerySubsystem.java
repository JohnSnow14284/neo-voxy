package me.cortex.voxy.client.mixin.eclipticseasons;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Definitions;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.common.world.other.Mapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value={ModelBakerySubsystem.class})
public abstract class MixinModelBakerySubsystem {
    @Shadow(remap=false)
    @Final
    private Mapper mapper;

    @WrapOperation(remap=false, method={"requestBlockBake"}, at={@At(value="MIXINEXTRAS:EXPRESSION")})
    @Definitions(value={@Definition(id="mapper", field={"Lme/cortex/voxy/client/core/model/ModelBakerySubsystem;mapper:Lme/cortex/voxy/common/world/other/Mapper;"}), @Definition(id="getBlockStateCount", method={"Lme/cortex/voxy/common/world/other/Mapper;getBlockStateCount()I"})})
    @Expression(value={"this.mapper.getBlockStateCount()<=?"})
    private boolean eclipticseasons$requestBlockBake(int left, int right, Operation<Boolean> original, @Local(argsOnly=true) int blockId) {
        return (Boolean)original.call(new Object[]{left, VoxyTool.fixId(this.mapper, right)});
    }
}

