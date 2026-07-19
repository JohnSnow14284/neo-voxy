package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        var imports = Commands.literal("import")
                .then(Commands.literal("world")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(Commands.literal("bobby")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(Commands.literal("raw")
                        .then(Commands.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(Commands.literal("zip")
                        .then(Commands.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(Commands.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(Commands.literal("current")
                        .executes(VoxyCommands::importCurrentWorldIn))
                .then(Commands.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports
                    .then(Commands.literal("distant_horizons")
                            .then(Commands.argument("sqlDbPath", StringArgumentType.string())
                                    .executes(VoxyCommands::importDistantHorizons)));
        }

        var debug = Commands.literal("debug")
                .then(Commands.literal("verifyTLNChildMask")
                        .executes(ctx->verifyTLNs(ctx, false))
                        .then(Commands.argument("attemptRepair", BoolArgumentType.bool())
                                .executes(ctx->verifyTLNs(ctx, BoolArgumentType.getBool(ctx, "attemptRepair"))))
                )
                .then(Commands.literal("probe")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(VoxyCommands::probeStorage)))))
                .then(Commands.literal("trains")
                        .executes(VoxyCommands::dumpTrains)
                        .then(Commands.literal("occlusion")
                                .executes(ctx -> occlusionCapture(ctx, 20))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 120))
                                        .executes(ctx -> occlusionCapture(ctx, IntegerArgumentType.getInteger(ctx, "seconds"))))))
                .then(Commands.literal("kinetics")
                        .executes(VoxyCommands::dumpKinetics))
                .then(Commands.literal("ship")
                        .executes(VoxyCommands::dumpShipContraptions))
                .then(Commands.literal("perf")
                        .executes(VoxyCommands::dumpPerf)
                        .then(Commands.literal("reset")
                                .executes(VoxyCommands::resetPerf)))
                .then(Commands.literal("capture")
                        .executes(ctx -> frameCapture(ctx, 20))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(3, 300))
                                .executes(ctx -> frameCapture(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))));

        return Commands.literal("voxy")//.requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(Commands.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(imports)
                .then(debug);
    }

    //Live counters for the fork's optimizations - proves they are firing and by how much. Values
    //accumulate across the session; run "/voxy debug perf reset" to zero them and watch a fresh window
    //(e.g. reset, fly across a fresh chunk area, then check the biome/copycat cache hit rate).
    private static int dumpPerf(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(me.cortex.voxy.commonImpl.PerfStats.report()), false);
        return 1;
    }

    //Samples per-frame cost while the player moves, then writes voxy-frame-capture.txt in the game dir.
    //Running it again while a capture is armed stops it early.
    private static int frameCapture(CommandContext<CommandSourceStack> ctx, int seconds) {
        String msg = FrameProfiler.isActive()
                ? FrameProfiler.stopAndDump()
                : FrameProfiler.start(seconds);
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int resetPerf(CommandContext<CommandSourceStack> ctx) {
        me.cortex.voxy.commonImpl.PerfStats.reset();
        ctx.getSource().sendSuccess(() -> Component.literal("Voxy optimization stats reset"), false);
        return 1;
    }

    //Arms (or stops early) the per-frame occlusion recorder; the dump file lands in the game dir
    private static int occlusionCapture(CommandContext<CommandSourceStack> ctx, int seconds) {
        String msg;
        if (me.cortex.voxy.client.compat.create.DistantOcclusionDebug.isActive()) {
            msg = me.cortex.voxy.client.compat.create.DistantOcclusionDebug.stopAndDump();
        } else {
            msg = me.cortex.voxy.client.compat.create.DistantOcclusionDebug.start(seconds);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    //Dumps the kinetic snapshot pipeline: config gates, draw counters, queue/sweep state, recent
    //capture attempts (renderer + vertex counts) and the buckets near the camera. Run it standing at
    //a broken machine: it distinguishes captured-nothing / captured-garbage / captured-but-not-drawn.
    private static int dumpKinetics(CommandContext<CommandSourceStack> ctx) {
        if (!net.neoforged.fml.ModList.get().isLoaded("create")) {
            ctx.getSource().sendSuccess(() -> Component.literal("create not loaded"), false);
            return 0;
        }
        var cfg = me.cortex.voxy.client.config.VoxyConfig.CONFIG;
        var mc = net.minecraft.client.Minecraft.getInstance();
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        var sb = new StringBuilder("distant kinetics: rendering=").append(cfg.isRenderingEnabled())
                .append(" distantKinetics=").append(cfg.distantKinetics)
                .append(" enclosedCulling=").append(cfg.kineticEnclosedCulling)
                .append(" reach=").append((int) reach)
                .append(" lodMax=").append((int) cfg.createRenderDistance(0))
                .append(" sectionsDrawnLastFrame=").append(me.cortex.voxy.client.compat.create.DistantKineticRenderer.lastFrameSectionsDrawn)
                .append('\n')
                .append(me.cortex.voxy.client.compat.create.KineticSnapshots.debugDump(cam.x, cam.y, cam.z));
        String msg = sb.toString();
        me.cortex.voxy.common.Logger.info("[kinetics debug]\n" + msg);
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    //Splits "ship contraptions don't render" into its two possible worlds: exempt counters moving
    //while the structure stays invisible means we let it through and the problem is past us
    //(transform/depth); a renderer that is never even called clears our culls entirely.
    private static int dumpShipContraptions(CommandContext<CommandSourceStack> ctx) {
        if (!net.neoforged.fml.ModList.get().isLoaded("create")) {
            ctx.getSource().sendSuccess(() -> Component.literal("create not loaded"), false);
            return 0;
        }
        String msg = me.cortex.voxy.client.compat.create.ShipContraptionDebug.dump();
        me.cortex.voxy.common.Logger.info("[ship debug]\n" + msg);
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    //Dumps the client-side distant-train state: render gates plus every tracked train with sample
    //age and distance. Zero tracked trains with a moving train 192-3072 blocks away means the
    //server side is not sampling (old jar or no voxy on the server).
    private static int dumpTrains(CommandContext<CommandSourceStack> ctx) {
        var cfg = me.cortex.voxy.client.config.VoxyConfig.CONFIG;
        var sb = new StringBuilder("distant trains: rendering=").append(cfg.isRenderingEnabled())
                .append(" distantTrains=").append(cfg.distantTrains)
                .append(" renderDist=").append((int) (32 * cfg.sectionRenderDistance))
                .append(" bogeyMeshes=").append(me.cortex.voxy.client.compat.create.DistantTrainRenderer.bogeyMeshProvider != null)
                .append(" drawnLastFrame=").append(me.cortex.voxy.client.compat.create.DistantTrainRenderer.lastFrameCarriagesDrawn)
                .append(" shapesReceived=").append(me.cortex.voxy.client.compat.create.DistantTrainManager.shapesReceived)
                .append(" bakesFailed=").append(me.cortex.voxy.client.compat.create.DistantTrainManager.bakesFailed)
                .append(" meshCount=").append(me.cortex.voxy.client.compat.create.DistantTrainManager.meshCount());
        if (net.neoforged.fml.ModList.get().isLoaded("create")) {
            sb.append(" trackTiles=").append(me.cortex.voxy.client.compat.create.DistantTrackRenderer.tileCount)
                    .append(" tilesDrawn=").append(me.cortex.voxy.client.compat.create.DistantTrackRenderer.lastFrameTilesDrawn);
        }
        //One-shot depth probe: aim the crosshair at LOD terrain, run this command twice - the
        //second run prints the depth values captured right after our draws
        me.cortex.voxy.client.compat.LodPipelineHooks.depthProbeRequested = true;
        if (me.cortex.voxy.client.compat.LodPipelineHooks.depthProbeResult != null) {
            sb.append("\ndepthProbe: ").append(me.cortex.voxy.client.compat.LodPipelineHooks.depthProbeResult);
        }
        sb.append("\nshaders=").append(me.cortex.voxy.client.core.util.IrisUtil.irisShaderPackEnabled());
        var voxyRenderer = me.cortex.voxy.client.core.IGetVoxyRenderSystem.getNullable();
        if (voxyRenderer != null) {
            sb.append(" sableDepthTex=").append(voxyRenderer.getSableOcclusionDepthTexture())
                    .append(" (0 means the LOD depth already lands in the vanilla depth buffer)");
        }
        sb.append("\nmesh keys:");
        for (long key : me.cortex.voxy.client.compat.create.DistantTrainManager.meshKeys()) {
            sb.append(' ').append(Long.toHexString(key));
        }
        var trains = me.cortex.voxy.client.compat.create.DistantTrainManager.trains();
        sb.append("\ntracked trains=").append(trains.size());
        var player = Minecraft.getInstance().player;
        long now = System.nanoTime();
        for (var e : trains.entrySet()) {
            var state = e.getValue();
            sb.append("\n ").append(e.getKey().toString(), 0, 8)
                    .append(" dim=").append(state.dimension)
                    .append(" carriages=").append(state.carriages.size());
            for (var ce : state.carriages.entrySet()) {
                var track = ce.getValue();
                if (track.cur == null) {
                    continue;
                }
                long ageMs = (now - track.curTimeNanos) / 1_000_000L;
                int dist = -1;
                if (player != null) {
                    double dx = track.cur.x() - player.getX(), dy = track.cur.y() - player.getY(), dz = track.cur.z() - player.getZ();
                    dist = (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
                }
                sb.append("\n  #").append(ce.getKey())
                        .append(" dist=").append(dist)
                        .append(" age=").append(ageMs).append("ms")
                        .append(" shapeId=").append(Long.toHexString(track.shapeId))
                        .append(" mesh=").append(me.cortex.voxy.client.compat.create.DistantTrainManager.shape(track.shapeId) != null)
                        .append(" bogeys=").append(track.cur.bogeys().size());
            }
        }
        if (trains.isEmpty()) {
            sb.append("\n(no packets received: check the SERVER runs this voxy build, a train sits beyond your view distance, and the server log for 'Distant train')");
        }
        var integrated = Minecraft.getInstance().getSingleplayerServer();
        if (integrated != null && net.neoforged.fml.ModList.get().isLoaded("create") && player != null) {
            try {
                var serverPlayer = integrated.getPlayerList().getPlayer(player.getUUID());
                if (serverPlayer != null) {
                    sb.append("\n---- integrated server ----\n")
                            .append(me.cortex.voxy.commonImpl.compat.create.CreateTrainSampler.debugDump(serverPlayer));
                }
            } catch (Throwable t) {
                sb.append("\nserver-side dump failed: ").append(t);
            }
        }
        String out = sb.toString();
        Logger.info(out);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    //Dumps the stored voxel (block + light nibbles) at every lod level for a position, plus the
    //voxel above it (the one most faces light from).
    private static int probeStorage(CommandContext<CommandSourceStack> ctx) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var engine = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (engine == null) {
            ctx.getSource().sendFailure(Component.translatable("No voxy world engine for this dimension"));
            return 1;
        }
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        var sb = new StringBuilder("voxy probe @ " + x + " " + y + " " + z);
        for (int lvl = 0; lvl <= 4; lvl++) {
            var sec = engine.acquireIfExists(lvl, x >> (5 + lvl), y >> (5 + lvl), z >> (5 + lvl));
            if (sec == null) {
                sb.append("\nlvl").append(lvl).append(": <section not in storage>");
                continue;
            }
            int lx = (x >> lvl) & 31, ly = (y >> lvl) & 31, lz = (z >> lvl) & 31;
            long self = sec.get(lx | (lz << 5) | (ly << 10));
            String above = ly < 31 ? formatVoxel(sec.get(lx | (lz << 5) | ((ly + 1) << 10)), engine) : "<in +y section>";
            sb.append("\nlvl").append(lvl).append(": self=").append(formatVoxel(self, engine))
                    .append(sec.isUniform() ? " [uniform]" : "").append(" above=").append(above);
            sec.release();
        }
        String out = sb.toString();
        Logger.info(out);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 0;
    }

    private static String formatVoxel(long v, me.cortex.voxy.common.world.WorldEngine engine) {
        if (v == 0) return "void";
        int light = me.cortex.voxy.common.world.other.Mapper.getLightId(v);
        String block;
        if (me.cortex.voxy.common.world.other.Mapper.isAir(v)) {
            block = "air";
        } else {
            try {
                block = String.valueOf(engine.getMapper().getBlockStateFromBlockId(me.cortex.voxy.common.world.other.Mapper.getBlockId(v)));
            } catch (Exception e) {
                block = "<unmapped:" + me.cortex.voxy.common.world.other.Mapper.getBlockId(v) + ">";
            }
        }
        return block + "{sky=" + (light & 0xF) + ",blk=" + ((light >> 4) & 0xF) + "}";
    }

    private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr!=null) {
            ((IGetVoxyRenderSystem)wr).voxy$shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null) r.allChanged();
        return 0;
    }

    private static int verifyTLNs(CommandContext<CommandSourceStack> ctx, boolean attemptRepair) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("How you even do this");
        }
        DebugUtils.verifyAllTopLevelNodes(WorldIdentifier.ofEngine(Minecraft.getInstance().level), attemptRepair);
        return 0;
    }


    private static int importDistantHorizons(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                return 1;
            }
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null)return 1;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile_, engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter))?0:1;
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) return false;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importBobby(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }


    private static int importCurrentWorldIn(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var localServer = Minecraft.getInstance().getSingleplayerServer();
        if (localServer == null) {
            ctx.getSource().sendFailure(Component.translatable("You must be in single player to use this command"));
            return 1;
        }
        var regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), localServer.getWorldPath(LevelResource.ROOT)).resolve("region");
        if ((!regionPath.toFile().exists())||!regionPath.toFile().isDirectory()) {
            ctx.getSource().sendFailure(Component.translatable("Cannot find region folder for current dimension"));
            return 1;
        }
        return fileBasedImporter(regionPath.toFile())?0:1;
    }

    private static int importWorld(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (file.resolve("level.dat").toFile().exists()) {
            var dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) return 1;
            return fileBasedImporter(dimFile)?0:1;
            //We are in a world directory, so import the current dimension we are in
            /*
            for (var dim : new String[]{"overworld", "the_nether", "the_end"}) {//This is so annoying that you cant loop through all the dimensions
                var id = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(dim));
                var dimPath = DimensionType.getStorageFolder(id, file);
                dimPath = dimPath.resolve("region");
                var dimFile = dimPath.toFile();
                if (dimFile.isDirectory()) {//exists and is a directory
                    if (!fileBasedImporter(dimFile)) {
                        Logger.error("Failed to import dimension: " + id);
                    }
                }
            }*/
        } else {
            if (!(name.endsWith("region"))) {
                file = file.resolve("region");
            }
            return fileBasedImporter(file.toFile()) ? 0 : 1;
        }
    }

    private static int importZip(CommandContext<CommandSourceStack> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}