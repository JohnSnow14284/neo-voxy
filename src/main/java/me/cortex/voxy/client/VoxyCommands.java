package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

public class VoxyCommands {
   public static LiteralArgumentBuilder<CommandSourceStack> register() {
      LiteralArgumentBuilder<CommandSourceStack> imports = (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                           "import"
                        )
                        .then(
                           Commands.literal("world")
                              .then(
                                 Commands.argument("world_name", StringArgumentType.string())
                                    .suggests(VoxyCommands::importWorldSuggester)
                                    .executes(VoxyCommands::importWorld)
                              )
                        ))
                     .then(
                        Commands.literal("bobby")
                           .then(
                              Commands.argument("world_name", StringArgumentType.string())
                                 .suggests(VoxyCommands::importBobbySuggester)
                                 .executes(VoxyCommands::importBobby)
                           )
                     ))
                  .then(Commands.literal("raw").then(Commands.argument("path", StringArgumentType.string()).executes(VoxyCommands::importRaw))))
               .then(
                  Commands.literal("zip")
                     .then(
                        ((RequiredArgumentBuilder)Commands.argument("zipPath", StringArgumentType.string()).executes(VoxyCommands::importZip))
                           .then(Commands.argument("innerPath", StringArgumentType.string()).executes(VoxyCommands::importZip))
                     )
               ))
            .then(Commands.literal("current").executes(VoxyCommands::importCurrentWorldIn)))
         .then(Commands.literal("cancel").executes(VoxyCommands::cancelImport));
      if (DHImporter.HasRequiredLibraries) {
         imports = (LiteralArgumentBuilder<CommandSourceStack>)imports.then(
            Commands.literal("distant_horizons")
               .then(Commands.argument("sqlDbPath", StringArgumentType.string()).executes(VoxyCommands::importDistantHorizons))
         );
      }

      LiteralArgumentBuilder<CommandSourceStack> debug = (LiteralArgumentBuilder<CommandSourceStack>)Commands.literal("debug")
         .then(
            ((LiteralArgumentBuilder)Commands.literal("verifyTLNChildMask").executes(ctx -> verifyTLNs(ctx, false)))
               .then(
                  Commands.argument("attemptRepair", BoolArgumentType.bool()).executes(ctx -> verifyTLNs(ctx, BoolArgumentType.getBool(ctx, "attemptRepair")))
               )
         );
      return (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("voxy")
               .then(Commands.literal("reload").executes(VoxyCommands::reloadInstance)))
            .then(imports))
         .then(debug);
   }

   private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
      VoxyClientInstance instance = (VoxyClientInstance)VoxyCommon.getInstance();
      if (instance == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
         return 1;
      } else {
         IVoxyRenderSystemHolder vrsh = IVoxyRenderSystemHolder.getNullableHolder();
         if (vrsh != null) {
            vrsh.voxy$shutdownRenderer();
         }

         VoxyCommon.shutdownInstance();
         System.gc();
         VoxyCommon.createInstance();
         LevelRenderer r = Minecraft.getInstance().levelRenderer;
         if (r != null) {
            r.allChanged();
         }

         return 0;
      }
   }

   private static int verifyTLNs(CommandContext<CommandSourceStack> ctx, boolean attemptRepair) {
      VoxyInstance instance = VoxyCommon.getInstance();
      if (instance == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
         return 1;
      } else if (Minecraft.getInstance().level == null) {
         throw new IllegalStateException("How you even do this");
      } else {
         WorldEngine engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
         if (engine != null) {
            DebugUtils.verifyAllTopLevelNodes(engine, attemptRepair);
            return 0;
         } else {
            return 1;
         }
      }
   }

   private static int importDistantHorizons(CommandContext<CommandSourceStack> ctx) {
      VoxyClientInstance instance = (VoxyClientInstance)VoxyCommon.getInstance();
      if (instance == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
         return 1;
      } else {
         File dbFile = new File((String)ctx.getArgument("sqlDbPath", String.class));
         if (!dbFile.exists()) {
            return 1;
         } else {
            if (dbFile.isDirectory()) {
               dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
               if (!dbFile.exists()) {
                  return 1;
               }
            }

            File dbFile_ = dbFile;
            WorldEngine engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
            if (engine == null) {
               return 1;
            } else {
               return instance.getImportManager()
                     .makeAndRunIfNone(
                        engine,
                        () -> new DHImporter(dbFile_, engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter)
                     )
                  ? 0
                  : 1;
            }
         }
      }
   }

   private static boolean fileBasedImporter(File directory) {
      VoxyClientInstance instance = (VoxyClientInstance)VoxyCommon.getInstance();
      if (instance == null) {
         return false;
      } else {
         WorldEngine engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
         return engine == null ? false : instance.getImportManager().makeAndRunIfNone(engine, () -> {
            WorldImporter importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
         });
      }
   }

   private static int importRaw(CommandContext<CommandSourceStack> ctx) {
      if (VoxyCommon.getInstance() == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
         return 1;
      } else {
         return fileBasedImporter(new File((String)ctx.getArgument("path", String.class))) ? 0 : 1;
      }
   }

   private static int importBobby(CommandContext<CommandSourceStack> ctx) {
      if (VoxyCommon.getInstance() == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
         return 1;
      } else {
         File file = new File(".bobby").toPath().resolve((String)ctx.getArgument("world_name", String.class)).toFile();
         return fileBasedImporter(file) ? 0 : 1;
      }
   }

   private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
      return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
   }

   private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
      return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
   }

   private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
      String str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
      if (str.startsWith("\"")) {
         str = str.substring(1);
      }

      if (str.endsWith("\"")) {
         str = str.substring(0, str.length() - 1);
      }

      String remaining = str;
      if (str.contains("/")) {
         int idx = str.lastIndexOf(47);
         remaining = str.substring(idx + 1);

         try {
            dir = dir.resolve(str.substring(0, idx));
         } catch (Exception var8) {
            return Suggestions.empty();
         }

         str = str.substring(0, idx + 1);
      } else {
         str = "";
      }

      try {
         for (Path world : Files.list(dir).toList()) {
            if (world.toFile().isDirectory()) {
               String wn = world.getFileName().toString();
               if (!wn.equals(remaining)
                  && (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, "\"" + wn))) {
                  wn = str + wn + "/";
                  sb.suggest(StringArgumentType.escapeIfRequired(wn));
               }
            }
         }
      } catch (IOException var9) {
      }

      return sb.buildFuture();
   }

   private static int importCurrentWorldIn(CommandContext<CommandSourceStack> ctx) {
      if (VoxyCommon.getInstance() == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
         return 1;
      } else {
         IntegratedServer localServer = Minecraft.getInstance().getSingleplayerServer();
         if (localServer == null) {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("You must be in single player to use this command"));
            return 1;
         } else {
            Path regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), localServer.getWorldPath(LevelResource.ROOT))
               .resolve("region");
            if (regionPath.toFile().exists() && regionPath.toFile().isDirectory()) {
               return fileBasedImporter(regionPath.toFile()) ? 0 : 1;
            } else {
               ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("Cannot find region folder for current dimension"));
               return 1;
            }
         }
      }
   }

   private static int importWorld(CommandContext<CommandSourceStack> ctx) {
      if (VoxyCommon.getInstance() == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
         return 1;
      } else {
         String name = (String)ctx.getArgument("world_name", String.class);
         Path file = new File("saves").toPath().resolve(name);
         name = name.toLowerCase(Locale.ROOT);
         if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
         }

         if (file.resolve("level.dat").toFile().exists()) {
            File dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file).resolve("region").toFile();
            if (!dimFile.isDirectory()) {
               return 1;
            } else {
               return fileBasedImporter(dimFile) ? 0 : 1;
            }
         } else {
            if (!name.endsWith("region")) {
               file = file.resolve("region");
            }

            return fileBasedImporter(file.toFile()) ? 0 : 1;
         }
      }
   }

   private static int importZip(CommandContext<CommandSourceStack> ctx) {
      File zip = new File((String)ctx.getArgument("zipPath", String.class));
      String innerDir = "region/";

      try {
         innerDir = (String)ctx.getArgument("innerPath", String.class);
      } catch (Exception var6) {
      }

      VoxyClientInstance instance = (VoxyClientInstance)VoxyCommon.getInstance();
      if (instance == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
         return 1;
      } else {
         String finalInnerDir = innerDir;
         WorldEngine engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
         if (engine != null) {
            return instance.getImportManager()
                  .makeAndRunIfNone(
                     engine,
                     () -> {
                        WorldImporter importer = new WorldImporter(
                           engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter
                        );
                        importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                        return importer;
                     }
                  )
               ? 0
               : 1;
         } else {
            return 1;
         }
      }
   }

   private static int cancelImport(CommandContext<CommandSourceStack> ctx) {
      VoxyClientInstance instance = (VoxyClientInstance)VoxyCommon.getInstance();
      if (instance == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
         return 1;
      } else {
         WorldEngine world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
         if (world != null) {
            return instance.getImportManager().cancelImport(world) ? 0 : 1;
         } else {
            return 1;
         }
      }
   }
}
