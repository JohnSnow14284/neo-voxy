package me.cortex.voxy.commonImpl.compat.create;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

//Wire format for distant train rendering. Shapes are sent once per assembled carriage and cached by
//id; poses stream at a low rate and are interpolated client side. Blocks travel as raw block-state
//registry ids (registry sync keeps them consistent), so the client never touches Create classes.
public final class DistantTrainProtocol {
    private DistantTrainProtocol() {}

    //A block within a carriage, in contraption-local coordinates. Positions are packed as three
    //signed bytes (carriages are far smaller than +-127 on any axis). renderNbt is the slice of
    //the block entity's data a block needs to look right (copycat materials); empty for the
    //overwhelming majority of blocks it costs one boolean on the wire.
    public record ShapeBlock(byte x, byte y, byte z, BlockState state, Optional<CompoundTag> renderNbt) {
        public ShapeBlock(byte x, byte y, byte z, BlockState state) {
            this(x, y, z, state, Optional.empty());
        }

        public static final StreamCodec<ByteBuf, ShapeBlock> CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE, ShapeBlock::x,
                ByteBufCodecs.BYTE, ShapeBlock::y,
                ByteBufCodecs.BYTE, ShapeBlock::z,
                ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), ShapeBlock::state,
                ByteBufCodecs.optional(ByteBufCodecs.TRUSTED_COMPOUND_TAG), ShapeBlock::renderNbt,
                ShapeBlock::new);
    }

    //Static bogey description: which Create bogey style/size to draw and its data tag. The client
    //resolves the style from Create's registry, so addon styles (Steam 'n' Rails, create_bb) work
    //unmodified; the full tag travels along because some addon renderers read orientation from it.
    public record ShapeBogey(ResourceLocation styleId, ResourceLocation sizeId, float wheelRadius, CompoundTag data) {
        public static final StreamCodec<ByteBuf, ShapeBogey> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC.cast(), ShapeBogey::styleId,
                ResourceLocation.STREAM_CODEC.cast(), ShapeBogey::sizeId,
                ByteBufCodecs.FLOAT, ShapeBogey::wheelRadius,
                ByteBufCodecs.TRUSTED_COMPOUND_TAG, ShapeBogey::data,
                ShapeBogey::new);
    }

    //One carriage's block shape. shapeId is stable for the carriage's lifetime so the client can
    //cache the baked mesh; the same id is referenced by every pose update. initialYaw is the
    //assembly orientation (OrientedContraptionEntity.getInitialYaw) - contraption-local coordinates
    //are only meaningful once rotated by it, exactly like applyLocalTransforms does.
    public record CarriageShapePayload(UUID trainId, int carriageIndex, long shapeId, float initialYaw, List<ShapeBlock> blocks, List<ShapeBogey> bogeys) implements CustomPacketPayload {
        public static final Type<CarriageShapePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "train_shape"));
        public static final StreamCodec<ByteBuf, CarriageShapePayload> CODEC = StreamCodec.composite(
                net.minecraft.core.UUIDUtil.STREAM_CODEC, CarriageShapePayload::trainId,
                ByteBufCodecs.VAR_INT, CarriageShapePayload::carriageIndex,
                ByteBufCodecs.VAR_LONG, CarriageShapePayload::shapeId,
                ByteBufCodecs.FLOAT, CarriageShapePayload::initialYaw,
                ShapeBlock.CODEC.apply(ByteBufCodecs.list()), CarriageShapePayload::blocks,
                ShapeBogey.CODEC.apply(ByteBufCodecs.list()), CarriageShapePayload::bogeys,
                CarriageShapePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    //World-space pose of one bogey. yaw/pitch follow CarriageBogey.updateAngles conventions (yaw is
    //the negated track heading), so the client can feed them straight into Create's bogey transform.
    //The anchor already carries the upside-down rail offset; the flag only drives the model roll.
    public record BogeyPose(double x, double y, double z, float yaw, float pitch, boolean upsideDown) {
        public static final StreamCodec<ByteBuf, BogeyPose> CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, BogeyPose::x,
                ByteBufCodecs.DOUBLE, BogeyPose::y,
                ByteBufCodecs.DOUBLE, BogeyPose::z,
                ByteBufCodecs.FLOAT, BogeyPose::yaw,
                ByteBufCodecs.FLOAT, BogeyPose::pitch,
                ByteBufCodecs.BOOL, BogeyPose::upsideDown,
                BogeyPose::new);
    }

    //A single carriage pose sample in world space. Bogey poses align by index with the shape's bogey
    //list; the list is empty while any bogey is off-graph (just assembled, mid portal, derailed).
    //Hand-rolled codec - composite() caps at six fields.
    public record CarriagePose(int carriageIndex, long shapeId, double x, double y, double z, float yaw, float pitch, List<BogeyPose> bogeys) {
        private static final StreamCodec<ByteBuf, List<BogeyPose>> BOGEYS_CODEC = BogeyPose.CODEC.apply(ByteBufCodecs.list());
        public static final StreamCodec<ByteBuf, CarriagePose> CODEC = StreamCodec.of(
                (buf, pose) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, pose.carriageIndex());
                    ByteBufCodecs.VAR_LONG.encode(buf, pose.shapeId());
                    buf.writeDouble(pose.x());
                    buf.writeDouble(pose.y());
                    buf.writeDouble(pose.z());
                    buf.writeFloat(pose.yaw());
                    buf.writeFloat(pose.pitch());
                    BOGEYS_CODEC.encode(buf, pose.bogeys());
                },
                buf -> new CarriagePose(
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readFloat(),
                        BOGEYS_CODEC.decode(buf)));
    }

    //Pose stream for one train in one dimension. A train with an empty carriage list is a removal
    //(disassembled, derailed, left the broadcast radius or changed dimension).
    public record TrainPosesPayload(UUID trainId, ResourceLocation dimension, List<CarriagePose> carriages) implements CustomPacketPayload {
        public static final Type<TrainPosesPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "train_poses"));
        public static final StreamCodec<ByteBuf, TrainPosesPayload> CODEC = StreamCodec.composite(
                net.minecraft.core.UUIDUtil.STREAM_CODEC, TrainPosesPayload::trainId,
                ResourceLocation.STREAM_CODEC.cast(), TrainPosesPayload::dimension,
                CarriagePose.CODEC.apply(ByteBufCodecs.list()), TrainPosesPayload::carriages,
                TrainPosesPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static ResourceLocation dimensionId(Level level) {
        return level.dimension().location();
    }
}
