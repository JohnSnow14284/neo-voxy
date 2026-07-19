package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.client.core.ExtraRendererContext;
import com.teamtea.eclipticseasons.client.util.ClientCon;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool;
import java.util.List;
import me.cortex.voxy.client.core.model.bakery.ReuseVertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

public class VoxyClientTool {
    public static void renderToStream(BlockState state, RenderType layer, ReuseVertexConsumer translucentVC, ReuseVertexConsumer opaqueVC) {
        if (!VoxyTool.isVoxyTest()) {
            return;
        }
        if (state.getRenderShape() != RenderShape.INVISIBLE) {
            int defaultBlockTypeFlag = MapChecker.getDefaultBlockTypeFlag((BlockState)state);
            BakedModel model = ExtraModelManager.getSnowyModel((BlockState)state, null, (int)defaultBlockTypeFlag, (int)MapChecker.getSnowOffset((BlockState)state, (int)defaultBlockTypeFlag));
            if (model == null) {
                return;
            }
            ExtraRendererContext context = new ExtraRendererContext();
            context.setReplace(ExtraModelManager.isModelReplaceable((BlockState)state, (BlockAndTintGetter)ClientCon.getUseLevel(), (BlockPos)BlockPos.ZERO, (BakedModel)model)).setExtraModel(model).setOriginalModel(Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state));
            RenderType type = state.getBlock() instanceof LeavesBlock ? layer : ExtraModelManager.getRenderType((BlockState)state);
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                SingleThreadedRandomSource randomSource = new SingleThreadedRandomSource(42L);
                for (Object q : ExtraModelManager.cancelTop((ExtraRendererContext)context, (BakedModel)model, (BlockAndTintGetter)ClientCon.getUseLevel(), (BlockState)state, (BlockPos)BlockPos.ZERO, (Direction)direction, (RandomSource)randomSource, (long)42L, model.getQuads(state, direction, (RandomSource)randomSource), List.of())) {
                    BakedQuad quad = (BakedQuad) q;
                    (type == RenderType.translucent() ? translucentVC : opaqueVC).quad(quad, state.is(BlockTags.LEAVES), layer);
                }
            }
        }
    }
}

