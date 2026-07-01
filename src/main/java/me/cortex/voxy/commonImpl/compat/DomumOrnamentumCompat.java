package me.cortex.voxy.commonImpl.compat;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility bridge for Domum Ornamentum materially textured blocks.
 *
 * The expensive part of the original bridge was doing variant-key construction
 * and virtual id lookup for every voxel in a section. This version resolves
 * Domum block entities once when the section is queued, stores only the local
 * voxel indexes that actually need a material variant, and then keeps the hot
 * voxel conversion path as a single array lookup.
 */
public final class DomumOrnamentumCompat {
    private static final boolean LOADED = ModList.get().isLoaded("domum_ornamentum");
    private static final String DOMUM_PACKAGE = "com.ldtteam.domumornamentum";
    private static final String DOMUM_MODEL_PROPERTIES = "com.ldtteam.domumornamentum.client.model.properties.ModProperties";

    private static final ThreadLocal<int[]> SECTION_MAPPED_BLOCK_IDS = new ThreadLocal<>();
    private static final Map<Integer, ModelData> MODEL_DATA_BY_BLOCK_ID = new ConcurrentHashMap<>();

    private static volatile Method getTextureDataMethod;
    private static volatile boolean getTextureDataMethodMissing;
    private static volatile ModelProperty<?> materialTextureProperty;
    private static volatile boolean materialTexturePropertyMissing;

    private DomumOrnamentumCompat() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static void beginSection(Mapper mapper, LevelChunk chunk, LevelChunkSection section, int sectionY) {
        if (!LOADED || mapper == null || chunk == null || section == null) {
            SECTION_MAPPED_BLOCK_IDS.remove();
            return;
        }

        int[] mappedIds = null;
        int minY = sectionY << 4;
        int maxY = minY + 15;

        try {
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity == null || !isDomumBlockEntity(blockEntity)) {
                    continue;
                }

                BlockPos pos = blockEntity.getBlockPos();
                if (pos.getY() < minY || pos.getY() > maxY) {
                    continue;
                }

                ModelData modelData = blockEntity.getModelData();
                if (modelData == null || modelData == ModelData.EMPTY) {
                    continue;
                }

                Object variantKey = extractVariantKey(blockEntity, modelData);
                if (variantKey == null) {
                    continue;
                }

                int lx = pos.getX() & 15;
                int ly = pos.getY() & 15;
                int lz = pos.getZ() & 15;
                BlockState state = section.getBlockState(lx, ly, lz);
                if (state == null || state.isAir()) {
                    continue;
                }

                int mappedId = mapper.getIdForBlockStateVariant(state, variantKey);
                MODEL_DATA_BY_BLOCK_ID.putIfAbsent(mappedId, modelData);

                if (mappedIds == null) {
                    mappedIds = new int[4096];
                }
                int localIndex = lx | (lz << 4) | (ly << 8);
                mappedIds[localIndex] = mappedId;
            }
        } catch (Throwable ignored) {
            mappedIds = null;
        }

        if (mappedIds == null) {
            SECTION_MAPPED_BLOCK_IDS.remove();
        } else {
            SECTION_MAPPED_BLOCK_IDS.set(mappedIds);
        }
    }

    public static void endSection() {
        SECTION_MAPPED_BLOCK_IDS.remove();
    }

    public static boolean hasSectionMappings() {
        if (!LOADED) {
            return false;
        }
        int[] mappedIds = SECTION_MAPPED_BLOCK_IDS.get();
        return mappedIds != null;
    }

    /**
     * Hot-path lookup used while converting 4096 voxels. For sections without
     * Domum material data this returns immediately. For sections with Domum data
     * it only checks whether the current local index was pre-resolved above.
     */
    public static int mapBlockId(Mapper mapper, BlockState state, int baseBlockId, int localIndex) {
        if (!LOADED || localIndex < 0 || localIndex >= 4096) {
            return baseBlockId;
        }

        int[] mappedIds = SECTION_MAPPED_BLOCK_IDS.get();
        if (mappedIds == null) {
            return baseBlockId;
        }

        int mappedId = mappedIds[localIndex];
        return mappedId == 0 ? baseBlockId : mappedId;
    }

    public static ModelData getModelData(int blockId, BlockState state) {
        if (!LOADED) {
            return ModelData.EMPTY;
        }
        return MODEL_DATA_BY_BLOCK_ID.getOrDefault(blockId, ModelData.EMPTY);
    }

    /**
     * Backward-compatible fallback used by older call sites. Domum material data
     * is position-specific, not BlockState-specific, so this intentionally stays
     * empty.
     */
    public static ModelData getModelData(BlockState state) {
        return ModelData.EMPTY;
    }

    private static boolean isDomumBlockEntity(BlockEntity blockEntity) {
        String name = blockEntity.getClass().getName();
        return name.startsWith(DOMUM_PACKAGE);
    }

    private static Object extractVariantKey(BlockEntity blockEntity, ModelData modelData) {
        Object textureData = extractTextureDataFromModelData(modelData);
        if (textureData != null) {
            return textureData;
        }

        textureData = extractTextureDataFromBlockEntity(blockEntity);
        if (textureData != null) {
            return textureData;
        }

        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object extractTextureDataFromModelData(ModelData modelData) {
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

    private static Object extractTextureDataFromBlockEntity(BlockEntity blockEntity) {
        Method method = getTextureDataMethod(blockEntity.getClass());
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(blockEntity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method getTextureDataMethod(Class<?> blockEntityClass) {
        Method method = getTextureDataMethod;
        if (method != null || getTextureDataMethodMissing) {
            return method;
        }
        try {
            method = blockEntityClass.getMethod("getTextureData");
            getTextureDataMethod = method;
            return method;
        } catch (Throwable ignored) {
            getTextureDataMethodMissing = true;
            return null;
        }
    }

    private static ModelProperty<?> getMaterialTextureProperty() {
        ModelProperty<?> property = materialTextureProperty;
        if (property != null || materialTexturePropertyMissing) {
            return property;
        }
        try {
            Class<?> propertiesClass = Class.forName(DOMUM_MODEL_PROPERTIES);
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
