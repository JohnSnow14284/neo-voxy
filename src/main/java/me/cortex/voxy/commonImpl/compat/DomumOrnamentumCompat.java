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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class DomumOrnamentumCompat {
    public static final String VARIANT_TYPE = "domum_ornamentum";
    private static final BlockEntity[] NO_BLOCK_ENTITIES = new BlockEntity[0];

    private static final boolean LOADED = ModList.get().isLoaded(VARIANT_TYPE);
    private static final String PACKAGE_PREFIX = "com.ldtteam.domumornamentum";
    private static final String MODEL_PROPERTIES = PACKAGE_PREFIX + ".client.model.properties.ModProperties";
    private static final String TEXTURE_DATA_CLASS = PACKAGE_PREFIX + ".client.model.data.MaterialTextureData";

    private static final Predicate<BlockState> DOMUM_STATE_PREDICATE = DomumOrnamentumCompat::isDomumState;
    private static final ThreadLocal<SectionMappings> SECTION_MAPPINGS =
            ThreadLocal.withInitial(SectionMappings::new);
    private static final Map<Mapper, Map<Integer, BakePlan>> BAKE_PLANS = new ConcurrentHashMap<>();
    private static final Map<Object, VariantDescriptor> DESCRIPTORS = new ConcurrentHashMap<>();

    //Walk the superclass chain rather than testing the concrete class alone: an addon subclassing a Domum
    //block entity lands outside the package but still carries the material data we are after.
    private static final ClassValue<Boolean> DOMUM_BLOCK_ENTITIES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (current.getName().startsWith(PACKAGE_PREFIX)) {
                    return true;
                }
            }
            return false;
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

    private static final ClassValue<Optional<Method>> SERIALIZE_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("serializeNBT"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> TEXTURED_COMPONENT_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getTexturedComponents"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> MAIN_COMPONENT_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getMainComponent"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> COMPONENT_ID_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getId"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static volatile ModelProperty<?> materialTextureProperty;
    private static volatile boolean materialTexturePropertyMissing;
    private static volatile Method textureDataDeserializer;

    private DomumOrnamentumCompat() {
    }

    public record BakePlan(ModelData modelData, BlockState modelState, BlockState colourState,
                           int fallbackTintAbgr, boolean forceTint, boolean independentModel) {
        private static final BakePlan EMPTY =
                new BakePlan(ModelData.EMPTY, null, null, -1, false, false);

        public static BakePlan empty() {
            return EMPTY;
        }

        public boolean isEmpty() {
            return this == EMPTY;
        }
    }

    private record VariantDescriptor(String key, CompoundTag data) {
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

    public static final class SectionBlockEntities {
        private static final SectionBlockEntities EMPTY = new SectionBlockEntities(Map.of());
        private final Map<Integer, BlockEntity[]> sections;

        private SectionBlockEntities(Map<Integer, BlockEntity[]> sections) {
            this.sections = sections;
        }

        public BlockEntity[] forSection(int sectionY) {
            return this.sections.getOrDefault(sectionY, NO_BLOCK_ENTITIES);
        }
    }

    /**
     * Captures and groups Domum block entities once on the caller thread. Ingest workers receive
     * immutable arrays for their own section instead of repeatedly traversing the live chunk map.
     */
    public static SectionBlockEntities captureBlockEntitiesBySection(LevelChunk chunk) {
        if (!LOADED || chunk == null || chunk.getBlockEntities().isEmpty()) {
            return SectionBlockEntities.EMPTY;
        }

        Map<Integer, ArrayList<BlockEntity>> buckets = null;
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity == null || !DOMUM_BLOCK_ENTITIES.get(blockEntity.getClass())) {
                continue;
            }
            if (buckets == null) {
                buckets = new HashMap<>();
            }
            buckets.computeIfAbsent(blockEntity.getBlockPos().getY() >> 4, ignored -> new ArrayList<>())
                    .add(blockEntity);
        }
        if (buckets == null) {
            return SectionBlockEntities.EMPTY;
        }

        Map<Integer, BlockEntity[]> sections = new HashMap<>(buckets.size());
        buckets.forEach((sectionY, entities) ->
                sections.put(sectionY, entities.toArray(BlockEntity[]::new)));
        return new SectionBlockEntities(Map.copyOf(sections));
    }

    public static BlockEntity[] captureBlockEntities(LevelChunk chunk, LevelChunkSection section) {
        if (!LOADED || chunk == null || section == null
                || !section.maybeHas(DOMUM_STATE_PREDICATE)
                || chunk.getBlockEntities().isEmpty()) {
            return NO_BLOCK_ENTITIES;
        }

        ArrayList<BlockEntity> result = null;
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity != null && DOMUM_BLOCK_ENTITIES.get(blockEntity.getClass())) {
                if (result == null) {
                    result = new ArrayList<>();
                }
                result.add(blockEntity);
            }
        }
        return result == null ? NO_BLOCK_ENTITIES : result.toArray(BlockEntity[]::new);
    }

    public static void beginSection(Mapper mapper, BlockEntity[] blockEntities,
                                    LevelChunkSection section, int sectionY) {
        if (!LOADED) {
            return;
        }
        SectionMappings mappings = SECTION_MAPPINGS.get();
        mappings.reset();
        if (mapper == null || blockEntities == null || blockEntities.length == 0
                || section == null || !section.maybeHas(DOMUM_STATE_PREDICATE)) {
            return;
        }

        int minY = sectionY << 4;
        int maxY = minY + 15;
        Map<Integer, BakePlan> plans = null;

        for (BlockEntity blockEntity : blockEntities) {
            BlockPos pos = blockEntity.getBlockPos();
            if (pos.getY() < minY || pos.getY() > maxY) {
                continue;
            }

            try {
                Object textureData = extractTextureData(blockEntity);
                ModelData modelData = ModelData.EMPTY;
                if (textureData == null) {
                    modelData = blockEntity.getModelData();
                    textureData = extractTextureData(modelData);
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
                if (plans == null) {
                    plans = plansFor(mapper);
                }
                if (plans.get(mappedId) == null) {
                    ModelData resolvedModelData = modelData == ModelData.EMPTY
                            ? createModelData(textureData) : modelData;
                    plans.putIfAbsent(mappedId,
                            createBakePlan(state, resolvedModelData, descriptor, textureData));
                }

                mappings.put(lx | (lz << 4) | (ly << 8), mappedId);
            } catch (Throwable ignored) {
            }
        }
        mappings.active = mappings.touchedCount != 0;
    }

    public static void endSection() {
        if (LOADED) SECTION_MAPPINGS.get().active = false;
    }

    public static boolean hasSectionMappings() {
        return LOADED && SECTION_MAPPINGS.get().active;
    }

    public static int mapBlockId(Mapper mapper, BlockState state, int baseBlockId, int localIndex) {
        if (!LOADED || localIndex < 0 || localIndex >= 4096) {
            return baseBlockId;
        }
        SectionMappings mappings = SECTION_MAPPINGS.get();
        if (!mappings.active) {
            return baseBlockId;
        }
        int mappedId = mappings.ids[localIndex];
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
            plansFor(mapper).putIfAbsent(blockId,
                    createBakePlan(state, modelData, descriptor, textureData));
        } catch (Throwable ignored) {
        }
    }

    private static BakePlan createBakePlan(BlockState state, ModelData modelData,
                                           VariantDescriptor descriptor, Object textureData) {
        ModelData resolved = modelData == null || modelData == ModelData.EMPTY
                ? createModelDataFromDescriptor(descriptor)
                : modelData;
        BlockState colourState = selectMaterialState(state, textureData);
        int tintAbgr = resolveTint(colourState);

        // Prefer Domum's actual retextured model. The proxy is only a last-resort shape when
        // optional model data could not be reconstructed from the saved variant.
        BlockState proxy = resolved == ModelData.EMPTY ? createStairProxy(state) : null;
        if (proxy != null && tintAbgr != -1) {
            return new BakePlan(ModelData.EMPTY, proxy, null, tintAbgr, true, true);
        }
        return new BakePlan(resolved, null, colourState, tintAbgr, false, true);
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
            return new VariantDescriptor(canonicalKey(data), data.copy());
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
        Optional<Method> method = SERIALIZE_METHODS.get(textureData.getClass());
        if (method.isEmpty()) return null;
        Object value = method.get().invoke(textureData);
        return value instanceof CompoundTag tag ? tag : null;
    }

    private static Object deserializeTextureData(CompoundTag data) throws ReflectiveOperationException {
        Method method = textureDataDeserializer;
        if (method == null) {
            synchronized (DomumOrnamentumCompat.class) {
                method = textureDataDeserializer;
                if (method == null) {
                    Class<?> type = Class.forName(TEXTURE_DATA_CLASS);
                    method = type.getMethod("deserializeFromNBT", CompoundTag.class);
                    textureDataDeserializer = method;
                }
            }
        }
        return method.invoke(null, data.copy());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ModelData createModelData(Object textureData) {
        ModelProperty property = getMaterialTextureProperty();
        if (property == null || textureData == null) {
            return ModelData.EMPTY;
        }
        try {
            return ModelData.builder().with((ModelProperty) property, textureData).build();
        } catch (Throwable ignored) {
            return ModelData.EMPTY;
        }
    }

    private static BlockState selectMaterialState(BlockState ownerState, Object textureData) {
        try {
            Optional<Method> method = TEXTURED_COMPONENT_METHODS.get(textureData.getClass());
            if (method.isEmpty()) return null;
            Object value = method.get().invoke(textureData);
            if (!(value instanceof Map<?, ?> components) || components.isEmpty()) {
                return null;
            }

            // Domum exposes the semantic main component on its block. Prefer it over name
            // heuristics so the independent Voxy model uses the same dominant material tint.
            if (ownerState != null) {
                Optional<Method> mainMethod =
                        MAIN_COMPONENT_METHODS.get(ownerState.getBlock().getClass());
                if (mainMethod.isPresent()) {
                    Object mainComponent = mainMethod.get().invoke(ownerState.getBlock());
                    if (mainComponent != null) {
                        Optional<Method> idMethod =
                                COMPONENT_ID_METHODS.get(mainComponent.getClass());
                        if (idMethod.isPresent()) {
                            Object mainId = idMethod.get().invoke(mainComponent);
                            Object mainMaterial = components.get(mainId);
                            if (mainMaterial instanceof Block block && block != Blocks.AIR) {
                                return block.defaultBlockState();
                            }
                        }
                    }
                }
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

    private static final class SectionMappings {
        private final int[] ids = new int[4096];
        private final short[] touched = new short[4096];
        private int touchedCount;
        private boolean active;

        void reset() {
            for (int index = 0; index < this.touchedCount; index++) {
                this.ids[Short.toUnsignedInt(this.touched[index])] = 0;
            }
            this.touchedCount = 0;
            this.active = false;
        }

        private void put(int localIndex, int mappedId) {
            if (this.ids[localIndex] == 0) {
                this.touched[this.touchedCount++] = (short) localIndex;
            }
            this.ids[localIndex] = mappedId;
        }
    }

    public static void closeMapper(Mapper mapper) {
        if (LOADED && mapper != null) {
            BAKE_PLANS.remove(mapper);
            if (BAKE_PLANS.isEmpty()) {
                DESCRIPTORS.clear();
            }
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
