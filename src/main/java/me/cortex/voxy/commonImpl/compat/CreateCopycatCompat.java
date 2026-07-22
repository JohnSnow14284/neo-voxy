package me.cortex.voxy.commonImpl.compat;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.commonImpl.compat.DisguiseStore;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

//Create's copycat blocks (and the Copycats+ addon's) take their entire appearance from a material
//BlockState stored on the block entity and fed to the wrapper model through ModelData - the json
//models behind their blockstates are literally minecraft:block/air, so with EMPTY model data the
//wrapper emits nothing and every copycat baked to a LOD model came out invisible. Same disease,
//same cure as Domum Ornamentum: register (block state, material) pairs as Mapper variants at
//ingest time, then rebuild the wrapper's ModelData when the variant block id gets baked. Copycat
//states with no registered material (unfilled ones, or stale LOD data from before re-ingest) fall
//back to the copycat base material - the grid skeleton the block shows up close when unfilled,
//since an unfilled block entity carries the base state as its material rather than null. Material
//extraction is reflective (getMaterial() exists on both Create's CopycatBlockEntity and Copycats+'
//independent CCCopycatBlockEntity); the multi-material blocks of Copycats+ have no single
//getMaterial() and quietly fall through (future work). Contraption meshes read the material from
//the captured block entity nbt instead (see materialFromContraptionNbt).
public final class CreateCopycatCompat {
    public static final String DISGUISE_TABLE = "disguise_copycat";
    public static final String VARIANT_TYPE = "create_copycat";

    private static final boolean LOADED = CopycatCommon.isLoaded();
    private static final String CREATE_PREFIX = CopycatCommon.CREATE_PREFIX;
    private static final String ADDON_PREFIX = CopycatCommon.ADDON_PREFIX;

    private static final ThreadLocal<SectionMappings> SECTION_MAPPINGS =
            ThreadLocal.withInitial(SectionMappings::new);
    private static final Map<Mapper, Map<Integer, BlockState>> MATERIALS = new ConcurrentHashMap<>();
    //material -> (serialized nbt, variant key) - stable per material, shared read-only downstream
    private record MaterialKey(CompoundTag data, String key) {}
    private static final Map<BlockState, MaterialKey> MATERIAL_KEYS = new ConcurrentHashMap<>();

    private static final Predicate<BlockState> COPYCAT_STATE_PREDICATE = CreateCopycatCompat::isCopycatState;

    private static final ClassValue<Optional<Method>> GET_MATERIAL_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getMaterial"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    //The wrapper models' ModelData keys, fetched reflectively once. All are public static finals;
    //stuffing the material under every key lets one ModelData serve either mod's wrapper model.
    //Copycats+ getQuads reads the MATERIALS map keyed by model part ("material" for single-material
    //blocks - the same upgrade its own gatherModelData applies), not the single-value property.
    private static volatile ModelProperty<BlockState> createMaterialProperty;
    private static volatile ModelProperty<BlockState> addonMaterialProperty;
    private static volatile ModelProperty<Map<String, BlockState>> addonMaterialsProperty;
    private static volatile boolean propertiesResolved;

    private CreateCopycatCompat() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static boolean isCopycatState(BlockState state) {
        return CopycatCommon.isCopycatState(state);
    }

    public static void beginSection(Mapper mapper, SectionStorage storage, LevelChunk chunk, LevelChunkSection section, int sectionX, int sectionY, int sectionZ) {
        if (!LOADED) {
            return;
        }
        SectionMappings mappings = SECTION_MAPPINGS.get();
        mappings.reset();
        //No block entities to ask - a section streamed from the server, or a chunk that has gone. The
        //materials were recorded the last time they COULD be read, so use those instead of publishing
        //plain ids over a build that was dressed. Without this a camouflaged build reverts to bare
        //skeleton exactly where it is most visible: out at LOD range, where the client never loads the
        //chunk and only a server-fed section ever arrives.
        if (mapper == null || section == null) return;
        if (chunk == null || chunk.getBlockEntities().isEmpty()) {
            if (section.maybeHas(COPYCAT_STATE_PREDICATE)) {
                int restored = DisguiseStore.load(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ,
                        mappings::put);
                mappings.active = restored != 0;
            }
            return;
        }
        if (!section.maybeHas(COPYCAT_STATE_PREDICATE)) return;

        int minY = sectionY << 4;
        int maxY = minY + 15;

        //The iteration itself is inside the guard, not just the body: this runs on an ingest worker over
        //a map the main thread mutates, so hasNext()/next() can throw ConcurrentModification. Whatever
        //was collected before the throw still publishes below - a partial dressing beats none, and the
        //next ingest of this section redoes it.
        try {
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (!CopycatCommon.isCopycatClass(blockEntity)) {
                    continue;
                }
                BlockPos pos = blockEntity.getBlockPos();
                if (pos.getY() < minY || pos.getY() > maxY) {
                    continue;
                }

                try {
                    Method getMaterial = GET_MATERIAL_METHODS.get(blockEntity.getClass()).orElse(null);
                    if (getMaterial == null) {
                        continue;//multi-material blocks etc: leave on the plain path
                    }
                    Object materialObj = getMaterial.invoke(blockEntity);
                    if (!(materialObj instanceof BlockState material) || material.isAir()) {
                        continue;
                    }
                    //An unfilled copycat carries the copycat base "material" - nothing to dress it in
                    var materialId = BuiltInRegistries.BLOCK.getKey(material.getBlock());
                    if (materialId == null || materialId.getPath().equals("copycat_base")) {
                        continue;
                    }

                    int lx = pos.getX() & 15;
                    int ly = pos.getY() & 15;
                    int lz = pos.getZ() & 15;
                    BlockState state = section.getBlockState(lx, ly, lz);
                    if (state == null || state.isAir()) {
                        continue;
                    }

                    //writeBlockState + toString are pure per-BE waste for a base built from one material
                    //(e.g. hundreds of andesite copycats re-serialize andesite every ingest). The material
                    //-> (nbt,key) mapping is stable and the downstream mapper only reads the tag, so cache
                    //it. BlockStates are interned registry singletons, safe as identity keys.
                    MaterialKey mk = MATERIAL_KEYS.get(material);
                    if (mk == null) {
                        me.cortex.voxy.commonImpl.PerfStats.copycatKeyMiss.increment();
                        CompoundTag tag = NbtUtils.writeBlockState(material);
                        mk = new MaterialKey(tag, tag.toString());
                        MaterialKey prior = MATERIAL_KEYS.putIfAbsent(material, mk);
                        if (prior != null) {
                            mk = prior;
                        }
                    } else {
                        me.cortex.voxy.commonImpl.PerfStats.copycatKeyHit.increment();
                    }

                    int mappedId = mapper.getIdForBlockStateVariant(state, VARIANT_TYPE, mk.key, mk.data);
                    materialsFor(mapper).putIfAbsent(mappedId, material);
                    mappings.put(lx | (lz << 4) | (ly << 8), mappedId);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        mappings.active = mappings.touchedCount != 0;
        //Recorded while the block entities are readable, which is the only moment the material can be
        //derived at all. Rewritten whole per section, so a block that stopped being disguised leaves
        //with the re-scan that no longer sees it.
        if (storage != null) {
            if (mappings.touchedCount == 0) {
                DisguiseStore.clear(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ);
            } else {
                int[] packed = new int[mappings.touchedCount * 2];
                for (int i = 0; i < mappings.touchedCount; i++) {
                    int index = mappings.touched[i];
                    packed[i * 2] = index;
                    packed[i * 2 + 1] = mappings.ids[index];
                }
                DisguiseStore.save(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ,
                        packed, mappings.touchedCount);
            }
        }
    }

    public static void endSection() {
        if (LOADED) SECTION_MAPPINGS.get().active = false;
    }

    //The active section's per-voxel id map, or null when this section has none. Fetch once per section
    //so the voxel loop can index it directly instead of a ThreadLocal.get per voxel.
    public static int[] activeSectionIds() {
        if (!LOADED) {
            return null;
        }
        SectionMappings m = SECTION_MAPPINGS.get();
        return m.active ? m.ids : null;
    }

    //Restore a stored variant on world load: the material NBT round-trips through the Mapper storage
    public static void restoreVariant(Mapper mapper, int blockId, BlockState state, String variantType, CompoundTag data) {
        if (!LOADED || mapper == null || !VARIANT_TYPE.equals(variantType) || data == null || data.isEmpty()) {
            return;
        }
        try {
            BlockState material = NbtUtils.readBlockState(
                    BuiltInRegistries.BLOCK.asLookup(), data);
            if (!material.isAir()) {
                materialsFor(mapper).putIfAbsent(blockId, material);
            }
        } catch (Throwable ignored) {
        }
    }

    //Client only (called from the model bakery): the material's own chunk render type - the copycat
    //wrapper model only emits quads when queried with the MATERIAL's layer, not the copycat's
    public static net.minecraft.client.renderer.RenderType renderLayerOverride(Mapper mapper, int blockId, BlockState state) {
        BlockState material = materialFor(mapper, blockId);
        if (material == null) {
            material = baseMaterialFor(state);
        }
        if (material == null) {
            return null;
        }
        try {
            return net.minecraft.client.renderer.ItemBlockRenderTypes.getChunkRenderType(material);
        } catch (Throwable ignored) {
            return null;
        }
    }

    //The material state drives block colour providers (grass/leaf copycats biome-tint like their material)
    public static BlockState getColourState(Mapper mapper, int blockId, BlockState fallback) {
        BlockState material = materialFor(mapper, blockId);
        return material == null ? fallback : material;
    }

    //Client only: bake plan carrying the wrapper ModelData (material under both mods' keys) and the
    //material state for biome tinting
    public static DomumOrnamentumCompat.BakePlan getBakePlan(Mapper mapper, int blockId, BlockState state) {
        BlockState material = materialFor(mapper, blockId);
        if (material == null) {
            material = baseMaterialFor(state);
        }
        if (material == null) {
            return DomumOrnamentumCompat.BakePlan.empty();
        }
        try {
            ModelData modelData = buildModelData(material);
            return new DomumOrnamentumCompat.BakePlan(modelData, null, material, -1, false);
        } catch (Throwable ignored) {
            return DomumOrnamentumCompat.BakePlan.empty();
        }
    }

    //Client only: the ModelData a copycat wrapper model expects, with the material stuffed under
    //every key either mod reads
    public static ModelData buildModelData(BlockState material) {
        resolveProperties();
        ModelData.Builder builder = ModelData.builder();
        if (createMaterialProperty != null) {
            builder.with(createMaterialProperty, material);
        }
        if (addonMaterialProperty != null) {
            builder.with(addonMaterialProperty, material);
        }
        if (addonMaterialsProperty != null) {
            builder.with(addonMaterialsProperty, new java.util.HashMap<>(Map.of("material", material)));
        }
        return builder.build();
    }

    public static void closeMapper(Mapper mapper) {
        if (LOADED && mapper != null) {
            MATERIALS.remove(mapper);
        }
    }

    //The unfilled look: block entities carry the copycat base state as their material until filled
    private static volatile BlockState baseSkeleton;

    private static BlockState baseMaterialFor(BlockState state) {
        if (!isCopycatState(state)) {
            return null;
        }
        BlockState base = baseSkeleton;
        if (base == null) {
            var block = BuiltInRegistries.BLOCK.get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "copycat_base"));
            base = block.defaultBlockState();
            baseSkeleton = base;
        }
        return base.isAir() ? null : base;
    }

    //Contraption/carriage mesh path: contraptions capture their block entities as nbt, so the
    //material comes from the copycat block entity's serialized "Material" tag (both mods use the
    //same key). Unfilled or unreadable falls back to the base skeleton. Null for non-copycats.
    public static ModelData materialFromContraptionNbt(BlockState state, CompoundTag beNbt) {
        if (!isCopycatState(state)) {
            return null;
        }
        BlockState material = null;
        try {
            if (beNbt != null && beNbt.contains("Material")) {
                material = NbtUtils.readBlockState(
                        BuiltInRegistries.BLOCK.asLookup(), beNbt.getCompound("Material"));
            }
        } catch (Throwable ignored) {
        }
        if (material == null || material.isAir()) {
            material = baseMaterialFor(state);
        }
        if (material == null) {
            return null;
        }
        try {
            return buildModelData(material);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BlockState materialFor(Mapper mapper, int blockId) {
        if (!LOADED || mapper == null) {
            return null;
        }
        Map<Integer, BlockState> materials = MATERIALS.get(mapper);
        return materials == null ? null : materials.get(blockId);
    }

    private static Map<Integer, BlockState> materialsFor(Mapper mapper) {
        return MATERIALS.computeIfAbsent(mapper, m -> new ConcurrentHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private static void resolveProperties() {
        if (propertiesResolved) {
            return;
        }
        try {
            createMaterialProperty = (ModelProperty<BlockState>) Class
                    .forName(CREATE_PREFIX + ".CopycatModel")
                    .getField("MATERIAL_PROPERTY").get(null);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> addonModel = Class.forName(ADDON_PREFIX + ".foundation.copycat.model.neoforge.CopycatModelNeoForge");
            addonMaterialProperty = (ModelProperty<BlockState>) addonModel.getField("MATERIAL_PROPERTY").get(null);
            addonMaterialsProperty = (ModelProperty<Map<String, BlockState>>) addonModel.getField("MATERIALS_PROPERTY").get(null);
        } catch (Throwable ignored) {
        }
        propertiesResolved = true;
    }

    private static final class SectionMappings {
        private final int[] ids = new int[4096];
        private final int[] touched = new int[4096];
        private int touchedCount;
        private boolean active;

        void reset() {
            for (int i = 0; i < this.touchedCount; i++) {
                this.ids[this.touched[i]] = 0;
            }
            this.touchedCount = 0;
            this.active = false;
        }

        void put(int index, int id) {
            if (this.ids[index] == 0) {
                this.touched[this.touchedCount++] = index;
            }
            this.ids[index] = id;
        }
    }
}
