package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.teamtea.eclipticseasons.api.EclipticSeasonsApi;
import com.teamtea.eclipticseasons.client.util.ClientCon;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.config.CommonConfig;
import me.cortex.voxy.client.config.VoxyConfig;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.IntConsumer;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

public class VoxyTool {
    private static final int maxBlockId = 1048575;
    public static ImportManager esImporter;

    public static boolean isVoxyTest() {
        return VoxyConfig.CONFIG.eclipticSeasonsSnowLod;
    }

    public static int changeBlockId(int blockId, Mapper stateMapper, int i, VoxelizedSection section, ILightingSupplier lightSupplier, int biomeId) {
        if (!VoxyTool.isVoxyTest()) {
            return blockId;
        }
        int maxBlockId = 1048575;
        BlockState state = stateMapper.getBlockStateFromBlockId(blockId);
        if (MapChecker.getDefaultBlockTypeFlag((BlockState)state) > 0) {
            BlockPos offset = SectionPos.of((int)section.x, (int)section.y, (int)section.z).origin().offset(i & 0xF, i >> 8 & 0xF, i >> 4 & 0xF);
            Level level = ClientCon.getUseLevel();
            if (level != null) {
                IVoxyAboveLightingSupplier supplier;
                byte supply;
                int skyLight;
                if (MapChecker.isLoaded((Level)level, (int)section.x, (int)section.z)) {
                    if (EclipticSeasonsApi.getInstance().isSnowyBlock(level, state, offset)) {
                        blockId = maxBlockId - blockId;
                    }
                } else if (lightSupplier instanceof IVoxyAboveLightingSupplier && (skyLight = (supply = (supplier = (IVoxyAboveLightingSupplier)lightSupplier).supply(i & 0xF, (i >> 8 & 0xF) + 1, i >> 4 & 0xF)) & 0xFF & 0xF) > 9 && (!((Boolean)CommonConfig.Snow.notSnowyNearGlowingBlock.get()).booleanValue() || ((supply & 0xFF) >> 4 & 0xF) < CommonConfig.Snow.notSnowyNearGlowingBlockLevel.getAsInt())) {
                    BlockState aboveState = supplier.getBlockState(i & 0xF, (i >> 8 & 0xF) + 1, i >> 4 & 0xF);
                    boolean isLight = true;
                    int flag = MapChecker.getDefaultBlockTypeFlag((BlockState)state);
                    if (MapChecker.leaveLike((int)flag)) {
                        boolean specialLeaves;
                        boolean bl = specialLeaves = aboveState.is(state.getBlock()) && (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(aboveState) || MapChecker.extraSnowPassable((BlockState)aboveState));
                        if (specialLeaves) {
                            isLight = (Boolean)CommonConfig.Snow.snowyTree.get();
                        }
                    } else if (MapChecker.extraSnowPassable((BlockState)state)) {
                        boolean bl = isLight = !MapChecker.extraSnowPassable((BlockState)aboveState);
                    }
                    if (isLight) {
                        String biome = stateMapper.getBiomeEntries()[biomeId].biome;
                        ResourceKey holderKey = ResourceKey.create((ResourceKey)Registries.BIOME, (ResourceLocation)ResourceLocation.parse((String)biome));
                        Holder.Reference holder = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(holderKey);
                        if (MapChecker.shouldSnowAtBiome((Level)level, (Biome)((Biome)holder.value()), (BlockState)state, (RandomSource)level.getRandom(), (long)state.getSeed(offset), (BlockPos)offset)) {
                            blockId = maxBlockId - blockId;
                        }
                    }
                }
            }
        }
        return blockId;
    }

    public static int fixId(Mapper mapper, int blockId) {
        return VoxyTool.fixId(mapper, blockId, VoxyTool::emptyConsumer);
    }

    private static void emptyConsumer(int i) {
    }

    public static int fixId(Mapper mapper, int blockId, IntConsumer consumer) {
        int blockStateCount = mapper.getBlockStateCount();
        if (blockId < blockStateCount) {
            return blockId;
        }
        if ((blockId = 1048575 - blockId) < blockStateCount) {
            consumer.accept(blockId);
            return blockId;
        }
        return 1048575 - blockId;
    }

    public static WorldEngine getWorld(Level level) {
        return VoxyTool.getVoxyInstance().getNullable(WorldIdentifier.of((Level)level));
    }

    public static Mapper getMapper(Level level) {
        WorldEngine world = VoxyTool.getWorld(level);
        return world == null ? null : world.getMapper();
    }

    public static int getSkyLightFromBlockId(long blockId) {
        return Mapper.getLightId((long)blockId) % 16;
    }

    public static WorldSection getWorldSection(WorldEngine into, SectionPos section) {
        int lvl = 0;
        return into.acquireIfExists(lvl, section.x() >> lvl + 1, section.y() >> lvl + 1, section.z() >> lvl + 1);
    }

    public static WorldSection getWorldSection(Level level, SectionPos section) {
        WorldEngine world = VoxyTool.getWorld(level);
        return world == null ? null : VoxyTool.getWorldSection(world, section);
    }

    public static void releaseImporter() {
        esImporter = null;
    }

    public static void tryUpdate() {
        if (!VoxyTool.isVoxyTest()) {
            return;
        }
        if (!VoxyConfig.CONFIG.eclipticSeasonsLodAutoReload) {
            return;
        }
        Level level = ClientCon.getUseLevel();
        if (level == null || level.getGameTime() % 300L == 0L || !ClientCon.getAgent().isSnowChange() || esImporter != null) {
            return;
        }
        VoxyInstance instance = VoxyTool.getVoxyInstance();
        if (instance == null) {
            return;
        }
        WorldEngine engine = WorldIdentifier.ofEngine((Level)level);
        if (engine == null) {
            return;
        }
        ClientCon.agent.setSnowChange(false);
        esImporter = new VoxyESImportManager();
        esImporter.makeAndRunIfNone(engine, () -> {
            WorldImporter importer = new WorldImporter(engine, level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            String worldName = ClientCon.getAgent().getCurrentWorldName();
            Path file = new File("saves").toPath().resolve(worldName);
            if (!worldName.endsWith("region")) {
                file = file.resolve("region");
            }
            importer.importRegionDirectoryAsync(file.toFile());
            return importer;
        });
    }

    @Nullable
    private static VoxyInstance getVoxyInstance() {
        VoxyInstance instance = null;
        try {
            Class<?> clazz = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            Method method = clazz.getDeclaredMethod("getInstance", new Class[0]);
            instance = (VoxyInstance)method.invoke(null, new Object[0]);
        }
        catch (Exception exception) {
            // empty catch block
        }
        return instance;
    }
}

