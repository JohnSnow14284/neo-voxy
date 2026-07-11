package me.cortex.voxy.commonImpl.compat;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DomumOrnamentumCompat {
    public static final String VARIANT_TYPE = "domum_ornamentum";

    private static final boolean LOADED = ModList.get().isLoaded(VARIANT_TYPE);
    private static final String PACKAGE_PREFIX = "com.ldtteam.domumornamentum";
    private static final String MODEL_PROPERTIES = PACKAGE_PREFIX + ".client.model.properties.ModProperties";
    private static final String TEXTURE_DATA_CLASS = PACKAGE_PREFIX + ".client.model.data.MaterialTextureData";

    private static final ThreadLocal<int[]> SECTION_IDS = new ThreadLocal<>();
    private static final Map<Mapper, Map<Integer, BakePlan>> BAKE_PLANS = new ConcurrentHashMap<>();
    private static final Map<Object, VariantDescriptor> DESCRIPTORS = new ConcurrentHashMap<>();

    private static final ClassValue<Boolean> DOMUM_BLOCK_ENTITIES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith(PACKAGE_PREFIX);
        }
    };

    private static final ClassValue<Optional<Method>> TEXTURE_DATA_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getTextureData"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static volatile ModelProperty<?> materialTextureProperty;
    private static volatile boolean materialTexturePropertyMissing;

    private DomumOrnamentumCompat() {
    }

    public record BakePlan(ModelData modelData, BlockState modelState, BlockState colourState,
                           int fallbackTintAbgr, boolean forceTint) {
        private static final BakePlan EMPTY = new BakePlan(ModelData.EMPTY, null, null, -1, false);
    }

    private record VariantDescriptor(String key, CompoundTag data, BlockState colourState, int tintAbgr) {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static boolean isDomumState(BlockState state) {
        if (!LOADED || state == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && VARIANT_TYPE.equals(id.getNamespace());
    }

    public static void beginSection(Mapper mapper, LevelChunk chunk, LevelChunkSection section, int sectionY) {
        if (!LOADED || mapper == null || chunk == null || section == null || chunk.getBlockEntities().isEmpty()) {
            SECTION_IDS.remove();
            return;
        }

        int[] mappedIds = null;
        int minY = sectionY << 4;
        int maxY = minY + 15;

        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity == null || !DOMUM_BLOCK_ENTITIES.get(blockEntity.getClass())) {
                continue;
            }

            BlockPos pos = blockEntity.getBlockPos();
            if (pos.getY() < minY || pos.getY() > maxY) {
                continue;
            }

            try {
                ModelData modelData = blockEntity.getModelData();
                Object textureData = extractTextureData(modelData);
                if (textureData == null) {
                    textureData = extractTextureData(blockEntity);
                }
                if (textureData == null) {
                    continue;
                }

                VariantDescriptor descriptor = DESCRIPTORS.computeIfAbsent(textureData,
                        DomumOrnamentumCompat::createDescriptor);
                if (descriptor == null) {
                    continue;
                }

                int lx = pos.getX() & 15;
                int ly = pos.getY() & 15;
                int lz = pos.getZ() & 15;
                BlockState state = section.getBlockState(lx, ly, lz);
                if (state == null || state.isAir()) {
                    continue;
                }

                int mappedId = mapper.getIdForBlockStateVariant(
                        state, VARIANT_TYPE, descriptor.key(), descriptor.data());
                plansFor(mapper).computeIfAbsent(mappedId,
                        ignored -> createBakePlan(state, modelData, descriptor));

                if (mappedIds == null) {
                    mappedIds = new int[4096];
                }
                mappedIds[lx | (lz << 4) | (ly << 8)] = mappedId;
            } catch (Throwable ignored) {
            }
        }

        if (mappedIds == null) {
            SECTION_IDS.remove();
        } else {
            SECTION_IDS.set(mappedIds);
        }
    }

    public static void endSection() {
        SECTION_IDS.remove();
    }

    public static boolean hasSectionMappings() {
        return LOADED && SECTION_IDS.get() != null;
    }

    public static int mapBlockId(Mapper mapper, BlockState state, int baseBlockId, int localIndex) {
        if (!LOADED || localIndex < 0 || localIndex >= 4096) {
            return baseBlockId;
        }
        int[] mappedIds = SECTION_IDS.get();
        if (mappedIds == null) {
            return baseBlockId;
        }
        int mappedId = mappedIds[localIndex];
        return mappedId == 0 ? baseBlockId : mappedId;
    }

    public static BakePlan getBakePlan(Mapper mapper, int blockId) {
        if (!LOADED || mapper == null) {
            return BakePlan.EMPTY;
        }
        Map<Integer, BakePlan> plans = BAKE_PLANS.get(mapper);
        return plans == null ? BakePlan.EMPTY : plans.getOrDefault(blockId, BakePlan.EMPTY);
    }

    public static BlockState getColourState(Mapper mapper, int blockId, BlockState fallback) {
        BakePlan plan = getBakePlan(mapper, blockId);
        return plan.forceTint() ? null : plan.colourState() == null ? fallback : plan.colourState();
    }

    public static void restoreVariant(Mapper mapper, int blockId, BlockState state, String variantType, CompoundTag data) {
        if (!LOADED || mapper == null || !VARIANT_TYPE.equals(variantType) || data == null || data.isEmpty()) {
            return;
        }
        try {
            Object textureData = deserializeTextureData(data);
            if (textureData == null) {
                return;
            }
            VariantDescriptor descriptor = DESCRIPTORS.computeIfAbsent(textureData,
                    DomumOrnamentumCompat::createDescriptor);
            if (descriptor == null) {
                return;
            }
            ModelData modelData = createModelData(textureData);
            plansFor(mapper).putIfAbsent(blockId, createBakePlan(state, modelData, descriptor));
        } catch (Throwable ignored) {
        }
    }

    private static BakePlan createBakePlan(BlockState state, ModelData modelData, VariantDescriptor descriptor) {
        BlockState proxy = createStairProxy(state);
        if (proxy != null && descriptor.tintAbgr() != -1) {
            return new BakePlan(ModelData.EMPTY, proxy, null, descriptor.tintAbgr(), true);
        }
        ModelData resolved = modelData == null || modelData == ModelData.EMPTY
                ? createModelDataFromDescriptor(descriptor)
                : modelData;
        return new BakePlan(resolved, null, descriptor.colourState(), descriptor.tintAbgr(), false);
    }

    private static ModelData createModelDataFromDescriptor(VariantDescriptor descriptor) {
        try {
            Object textureData = deserializeTextureData(descriptor.data());
            return textureData == null ? ModelData.EMPTY : createModelData(textureData);
        } catch (Throwable ignored) {
            return ModelData.EMPTY;
        }
    }

    private static VariantDescriptor createDescriptor(Object textureData) {
        try {
            CompoundTag data = serializeTextureData(textureData);
            if (data == null || data.isEmpty()) {
                return null;
            }
            BlockState colourState = selectMaterialState(textureData);
            return new VariantDescriptor(canonicalKey(data), data.copy(), colourState, resolveTint(colourState));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object extractTextureData(BlockEntity blockEntity) {
        Optional<Method> method = TEXTURE_DATA_METHODS.get(blockEntity.getClass());
        if (method.isEmpty()) {
            return null;
        }
        try {
            return method.get().invoke(blockEntity);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object extractTextureData(ModelData modelData) {
        if (modelData == null || modelData == ModelData.EMPTY) {
            return null;
        }
        ModelProperty property = getMaterialTextureProperty();
        if (property == null) {
            return null;
        }
        try {
            return modelData.has(property) ? modelData.get(property) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static CompoundTag serializeTextureData(Object textureData) throws ReflectiveOperationException {
        Object value = textureData.getClass().getMethod("serializeNBT").invoke(textureData);
        return value instanceof CompoundTag tag ? tag : null;
    }

    private static Object deserializeTextureData(CompoundTag data) throws ReflectiveOperationException {
        Class<?> type = Class.forName(TEXTURE_DATA_CLASS);
        return type.getMethod("deserializeFromNBT", CompoundTag.class).invoke(null, data.copy());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ModelData createModelData(Object textureData) {
        ModelProperty property = getMaterialTextureProperty();
        if (property == null || textureData == null) {
            return ModelData.EMPTY;
        }
        try {
            Object builder = ModelData.class.getMethod("builder").invoke(null);
            Method with = null;
            for (Method method : builder.getClass().getMethods()) {
                if (method.getName().equals("with") && method.getParameterCount() == 2) {
                    with = method;
                    break;
                }
            }
            if (with == null) {
                return ModelData.EMPTY;
            }
            with.invoke(builder, property, textureData);
            Object result = builder.getClass().getMethod("build").invoke(builder);
            return result instanceof ModelData data ? data : ModelData.EMPTY;
        } catch (Throwable ignored) {
            return ModelData.EMPTY;
        }
    }

    private static BlockState selectMaterialState(Object textureData) {
        try {
            Object value = textureData.getClass().getMethod("getTexturedComponents").invoke(textureData);
            if (!(value instanceof Map<?, ?> components) || components.isEmpty()) {
                return null;
            }

            var entries = new ArrayList<>(components.entrySet());
            entries.sort(Comparator
                    .comparingInt((Map.Entry<?, ?> entry) -> materialPriority(String.valueOf(entry.getKey())))
                    .thenComparing(entry -> String.valueOf(entry.getKey())));

            for (Map.Entry<?, ?> entry : entries) {
                if (entry.getValue() instanceof Block block && block != Blocks.AIR) {
                    return block.defaultBlockState();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int materialPriority(String key) {
        String normalized = key.toLowerCase();
        if (normalized.contains("main") || normalized.contains("body") || normalized.contains("brick")
                || normalized.contains("shingle") || normalized.contains("material")) {
            return 0;
        }
        if (normalized.contains("frame") || normalized.contains("pillar") || normalized.contains("support")) {
            return 2;
        }
        return 1;
    }

    private static int resolveTint(BlockState materialState) {
        if (materialState == null) {
            return -1;
        }
        try {
            int colour = net.minecraft.client.Minecraft.getInstance().getBlockColors()
                    .getColor(materialState, null, BlockPos.ZERO, 0);
            if (colour != -1) {
                return rgbToAbgr(colour);
            }
        } catch (Throwable ignored) {
        }
        try {
            int colour = materialState.getMapColor(null, BlockPos.ZERO).col;
            return rgbToAbgr(colour);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static BlockState createStairProxy(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null || !VARIANT_TYPE.equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        if (!(path.contains("stair") || path.contains("shingle"))
                || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || !state.hasProperty(BlockStateProperties.HALF)) {
            return null;
        }

        BlockState proxy = Blocks.QUARTZ_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(BlockStateProperties.HORIZONTAL_FACING))
                .setValue(BlockStateProperties.HALF, state.getValue(BlockStateProperties.HALF));
        if (state.hasProperty(BlockStateProperties.STAIRS_SHAPE)) {
            proxy = proxy.setValue(BlockStateProperties.STAIRS_SHAPE, state.getValue(BlockStateProperties.STAIRS_SHAPE));
        }
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            proxy = proxy.setValue(BlockStateProperties.WATERLOGGED, state.getValue(BlockStateProperties.WATERLOGGED));
        }
        return proxy;
    }

    private static String canonicalKey(CompoundTag data) {
        StringBuilder canonical = new StringBuilder(128);
        appendCanonical(data, canonical);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void appendCanonical(Tag tag, StringBuilder output) {
        output.append(tag.getId()).append(':');
        if (tag instanceof CompoundTag compound) {
            var keys = new ArrayList<>(compound.getAllKeys());
            keys.sort(String::compareTo);
            output.append('{');
            for (String key : keys) {
                output.append(key.length()).append(':').append(key).append('=');
                Tag value = compound.get(key);
                if (value != null) {
                    appendCanonical(value, output);
                }
                output.append(';');
            }
            output.append('}');
            return;
        }
        if (tag instanceof ListTag list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                appendCanonical(list.get(index), output);
                output.append(';');
            }
            output.append(']');
            return;
        }
        String value = tag.toString();
        output.append(value.length()).append(':').append(value);
    }

    private static Map<Integer, BakePlan> plansFor(Mapper mapper) {
        return BAKE_PLANS.computeIfAbsent(mapper, ignored -> new ConcurrentHashMap<>());
    }

    public static void closeMapper(Mapper mapper) {
        if (LOADED && mapper != null) {
            BAKE_PLANS.remove(mapper);
        }
    }

    private static int rgbToAbgr(int rgb) {
        return 0xFF000000 | ((rgb & 0x0000FF) << 16) | (rgb & 0x00FF00) | ((rgb >>> 16) & 0xFF);
    }

    private static ModelProperty<?> getMaterialTextureProperty() {
        ModelProperty<?> property = materialTextureProperty;
        if (property != null || materialTexturePropertyMissing) {
            return property;
        }
        try {
            Class<?> propertiesClass = Class.forName(MODEL_PROPERTIES);
            Field field = propertiesClass.getField("MATERIAL_TEXTURE_PROPERTY");
            Object value = field.get(null);
            if (value instanceof ModelProperty<?> modelProperty) {
                materialTextureProperty = modelProperty;
                return modelProperty;
            }
        } catch (Throwable ignored) {
        }
        materialTexturePropertyMissing = true;
        return null;
    }
}
