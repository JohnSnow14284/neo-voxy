package me.cortex.voxy.commonImpl.importers;

import com.google.common.collect.UnmodifiableIterator;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.ByteBufferBackedInputStream;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Registry;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.apache.commons.io.IOUtils;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.zstd.Zstd;
import org.tukaani.xz.BasicArrayCache;
import org.tukaani.xz.ResettableArrayCache;
import org.tukaani.xz.XZInputStream;

public class DHImporter implements IDataImporter {
   private final Connection db;
   private final WorldEngine engine;
   private final Service service;
   private final Level world;
   private final int bottomOfWorld;
   private final int worldHeightSections;
   private final Reference<Biome> defaultBiome;
   private final Registry<Biome> biomeRegistry;
   private final Registry<Block> blockRegistry;
   private Thread runner;
   private volatile boolean isRunning = false;
   private final AtomicInteger processedChunks = new AtomicInteger();
   private int totalChunks;
   private IDataImporter.IUpdateCallback updateCallback;
   private final ConcurrentLinkedDeque<DHImporter.Task> tasks = new ConcurrentLinkedDeque<>();
   public static final boolean HasRequiredLibraries;
   private static final VarHandle LONG = create(long[].class);

   public DHImporter(File file, WorldEngine worldEngine, Level mcWorld, ServiceManager servicePool, BooleanSupplier rateLimiter) {
      this.engine = worldEngine;
      this.world = mcWorld;
      this.biomeRegistry = mcWorld.registryAccess().lookupOrThrow(Registries.BIOME);
      this.defaultBiome = this.biomeRegistry.getOrThrow(Biomes.PLAINS);
      this.blockRegistry = mcWorld.registryAccess().lookupOrThrow(Registries.BLOCK);
      this.bottomOfWorld = mcWorld.getMinY();
      int worldHeight = mcWorld.getHeight();
      this.worldHeightSections = (worldHeight + 15) / 16;
      String con = "jdbc:sqlite:" + file.getPath();

      try {
         this.db = DriverManager.getConnection(con);
      } catch (SQLException var9) {
         throw new RuntimeException(var9);
      }

      this.service = servicePool.createService(
         () -> {
            try {
               PreparedStatement dataFetchStmt = this.db
                  .prepareStatement("SELECT Data,ColumnGenerationStep,Mapping FROM FullData WHERE DetailLevel = 0 AND PosX = ? AND PosZ = ?;");
               DHImporter.WorkCTX ctx = new DHImporter.WorkCTX(dataFetchStmt, this.worldHeightSections * 16);
               return new Pair<>(() -> this.importSection(dataFetchStmt, ctx, this.tasks.poll()), () -> {
                  ctx.free();

                  try {
                     dataFetchStmt.close();
                  } catch (SQLException var3xx) {
                     throw new RuntimeException(var3xx);
                  }
               });
            } catch (SQLException var3x) {
               throw new RuntimeException(var3x);
            }
         },
         10L,
         "DH Importer",
         rateLimiter
      );
   }

   @Override
   public void runImport(IDataImporter.IUpdateCallback updateCallback, IDataImporter.ICompletionCallback completionCallback) {
      if (this.isRunning()) {
         throw new IllegalStateException();
      } else {
         this.engine.acquireRef();
         this.updateCallback = updateCallback;
         this.runner = new Thread(() -> {
            Queue<DHImporter.Task> taskQ = new PriorityQueue<>(Comparator.comparingLong(DHImporter.Task::distanceFromZero));

            try (Statement stmt = this.db.createStatement()) {
               ResultSet resSet = stmt.executeQuery("SELECT PosX,PosZ,CompressionMode,DataFormatVersion FROM FullData WHERE DetailLevel = 0;");

               while (resSet.next()) {
                  int x = resSet.getInt(1);
                  int z = resSet.getInt(2);
                  int compression = resSet.getInt(3);
                  int format = resSet.getInt(4);
                  if (format != 1) {
                     Logger.warn("Unknown format mode: " + format);
                  } else if (compression != 3 && compression != 4) {
                     Logger.warn("Unknown compression mode: " + compression);
                  } else {
                     taskQ.add(new DHImporter.Task(x, z, format, compression));
                  }
               }

               resSet.close();
            } catch (SQLException var13) {
               throw new RuntimeException(var13);
            }

            this.totalChunks = taskQ.size() * 16;

            while (this.isRunning && !taskQ.isEmpty()) {
               this.tasks.add(taskQ.poll());
               this.service.execute();

               while (this.tasks.size() > 100 && this.isRunning) {
                  try {
                     Thread.sleep(500L);
                  } catch (InterruptedException var11) {
                     throw new RuntimeException(var11);
                  }
               }
            }

            while (!this.tasks.isEmpty()) {
               try {
                  Thread.sleep(500L);
               } catch (InterruptedException var10) {
                  throw new RuntimeException(var10);
               }
            }

            completionCallback.onCompletion(this.processedChunks.get());
            this.shutdown();
         });
         this.isRunning = true;
         this.runner.setDaemon(true);
         this.runner.start();
      }
   }

   private static String getSerialBlockState(BlockState state) {
      ArrayList<Property<?>> props = new ArrayList<>(state.getProperties());
      props.sort((a, bx) -> a.getName().compareTo(bx.getName()));
      StringBuilder b = new StringBuilder();

      for (Property<?> prop : props) {
         String val = "NULL";
         if (state.hasProperty(prop)) {
            val = state.getValue(prop).toString();
         }

         b.append("{").append(prop.getName()).append(":").append(val).append("}");
      }

      return b.toString();
   }

   private long[] readMappings(InputStream in, DHImporter.WorkCTX ctx) throws IOException {
      String BLOCK_STATE_SEPARATOR_STRING = "_DH-BSW_";
      String STATE_STRING_SEPARATOR = "_STATE_";
      DataInputStream stream = new DataInputStream(in);
      int entries = stream.readInt();
      if (entries < 0) {
         throw new IllegalStateException();
      } else {
         long[] out = new long[entries];

         for (int i = 0; i < entries; i++) {
            String encEntry = stream.readUTF();
            int idx = encEntry.indexOf("_DH-BSW_");
            if (idx == -1) {
               throw new IllegalStateException();
            }

            Identifier biomeRes = Identifier.parse(encEntry.substring(0, idx));
            Reference<Biome> biome = this.biomeRegistry.get(biomeRes).orElse(this.defaultBiome);
            int biomeId = this.engine.getMapper().getIdForBiome(biome);
            int b = idx + "_DH-BSW_".length();
            int blockId;
            if (encEntry.substring(b).equals("AIR")) {
               blockId = 0;
            } else {
               int sIdx = encEntry.indexOf("_STATE_", b);
               String bStateStr = null;
               if (sIdx != -1) {
                  bStateStr = encEntry.substring(sIdx + "_STATE_".length());
               }

               Identifier bId = Identifier.parse(encEntry.substring(b, sIdx != -1 ? sIdx : encEntry.length()));
               Optional<Reference<Block>> maybeBlock = this.blockRegistry.get(bId);
               Block block = Blocks.AIR;
               if (maybeBlock.isPresent()) {
                  block = (Block)maybeBlock.get().value();
               }

               BlockState state = block.defaultBlockState();
               if (bStateStr != null && block != Blocks.AIR) {
                  boolean found = false;
                  UnmodifiableIterator var21 = block.getStateDefinition().getPossibleStates().iterator();

                  while (var21.hasNext()) {
                     BlockState bState = (BlockState)var21.next();
                     if (getSerialBlockState(bState).equals(bStateStr)) {
                        state = bState;
                        found = true;
                        break;
                     }
                  }

                  if (!found) {
                     Logger.warn("Could not find block state with data", encEntry.substring(b));
                  }
               }

               if (block == Blocks.AIR) {
                  Logger.warn("Could not find block entry with id:", bId);
               }

               blockId = this.engine.getMapper().getIdForBlockState(state);
            }

            out[i] = Mapper.composeMappingId((byte)0, blockId, biomeId);
         }

         stream.close();
         return out;
      }
   }

   private static int getId(long dp) {
      return (int)(dp & 2147483647L);
   }

   private static int getHeight(long dp) {
      return (int)(dp >>> 32 & 4095L);
   }

   private static int getMinHeight(long dp) {
      return (int)(dp >>> 44 & 4095L);
   }

   private static int getSkyLight(long dp) {
      return (int)(dp >>> 56 & 15L);
   }

   private static int getBlockLight(long dp) {
      return (int)(dp >>> 60 & 15L);
   }

   private static InputStream createDecompressedStream(int decompressor, InputStream in, DHImporter.WorkCTX ctx) throws IOException {
      if (decompressor == 3) {
         ctx.cache.reset();
         return new XZInputStream(IOUtils.toBufferedInputStream(in), -1, false, ctx.cache);
      } else if (decompressor != 4) {
         throw new IllegalArgumentException("Unknown compressor " + decompressor);
      } else {
         if (ctx.zstdScratch == null) {
            ctx.zstdScratch = MemoryUtil.memAlloc(8196);
            ctx.zstdScratch2 = MemoryUtil.memAlloc(8196);
         }

         ctx.zstdScratch.clear();
         ctx.zstdScratch2.clear();

         try (ReadableByteChannel channel = Channels.newChannel(in)) {
            while (IOUtils.read(channel, ctx.zstdScratch) == 0) {
               ByteBuffer newBuffer = MemoryUtil.memAlloc(ctx.zstdScratch.position() * 2);
               newBuffer.put(ctx.zstdScratch.rewind());
               MemoryUtil.memFree(ctx.zstdScratch);
               ctx.zstdScratch = newBuffer;
            }
         }

         ctx.zstdScratch.limit(ctx.zstdScratch.position()).rewind();
         int decompSize = (int)Zstd.ZSTD_getFrameContentSize(ctx.zstdScratch);
         if (ctx.zstdScratch2.capacity() < decompSize) {
            MemoryUtil.memFree(ctx.zstdScratch2);
            ctx.zstdScratch2 = MemoryUtil.memAlloc((int)(decompSize * 1.1));
         }

         long size = Zstd.ZSTD_decompressDCtx(ctx.zstdDCtx, ctx.zstdScratch, ctx.zstdScratch2);
         if (Zstd.ZSTD_isError(size)) {
            throw new IllegalStateException("ZSTD EXCEPTION: " + Zstd.ZSTD_getErrorName(size));
         } else {
            ctx.zstdScratch2.limit((int)size);
            return new ByteBufferBackedInputStream(ctx.zstdScratch2);
         }
      }
   }

   private void readColumnData(int X, int Z, InputStream in, DHImporter.WorkCTX ctx, long[] mapping) throws IOException {
      DataInputStream stream = new DataInputStream(in);
      long[] storage = ctx.storageCache;
      VoxelizedSection section = ctx.section;
      byte[] col = ctx.colScratch;

      for (int x = 0; x < 64; x++) {
         for (int z = 0; z < 64; z++) {
            int bPos = Integer.expand(x & 15, 15) | Integer.expand(z, 12528);
            short cl = stream.readShort();
            if (cl < 0) {
               throw new IllegalStateException();
            }

            stream.read(col, 0, cl * 8);

            for (int j = 0; j < cl; j++) {
               long entry = (long)LONG.get((byte[])col, (int)(j * 8));
               long mEntry = Mapper.withLight(mapping[getId(entry)], getBlockLight(entry) << 4 | getSkyLight(entry));
               int startY = getMinHeight(entry);
               int tall = getHeight(entry);
               int endY = Math.min(startY + tall, this.worldHeightSections * 16);
               startY = Integer.expand(startY, 4181760);
               endY = Integer.expand(endY, 4181760);
               int Msk = 4181760;
               int iMsk1 = -4181760;

               for (int y = startY; y != endY; y = y + -4181760 & 4181760) {
                  storage[y + bPos] = mEntry;
               }
            }
         }

         if ((x + 1) % 16 == 0) {
            for (int sz = 0; sz < 4; sz++) {
               for (int sy = 0; sy < this.worldHeightSections; sy++) {
                  int base = (sz | sy << 2) << 12;
                  int nonAirCount = 0;
                  long[] dat = section.section;

                  for (int i = 0; i < 4096; i++) {
                     nonAirCount += Mapper.isAir(dat[i] = storage[i + base]) ? 0 : 1;
                  }

                  section.lvl0NonAirCount = nonAirCount;
                  WorldVoxilizedSectionMipper.mipSection(section, this.engine.getMapper());
                  section.setPosition(X * 4 + (x >> 4), sy + (this.bottomOfWorld >> 4), Z * 4 + sz);
                  WorldUpdater.insertUpdate(this.engine, section);
               }

               int count = this.processedChunks.incrementAndGet();
               this.updateCallback.onUpdate(count, this.totalChunks);
            }

            Arrays.fill(storage, 0L);
         }
      }

      stream.close();
   }

   private void importSection(PreparedStatement dataFetchStmt, DHImporter.WorkCTX ctx, DHImporter.Task task) {
      if (this.isRunning) {
         try {
            dataFetchStmt.setInt(1, task.x);
            dataFetchStmt.setInt(2, task.z);

            try (ResultSet rs = dataFetchStmt.executeQuery()) {
               long[] mapping = this.readMappings(createDecompressedStream(task.compression, rs.getBinaryStream(3), ctx), ctx);
               this.readColumnData(task.x, task.z, createDecompressedStream(task.compression, rs.getBinaryStream(1), ctx), ctx, mapping);
            }
         } catch (IOException | SQLException var9) {
            throw new RuntimeException(var9);
         }
      }
   }

   @Override
   public void shutdown() {
      if (this.isRunning) {
         this.isRunning = false;

         while (!this.tasks.isEmpty()) {
            this.tasks.poll();
         }

         try {
            if (this.runner != Thread.currentThread()) {
               this.runner.join();
            }
         } catch (InterruptedException var3) {
            throw new RuntimeException(var3);
         }

         this.service.shutdown();
         this.engine.releaseRef();

         try {
            this.db.close();
         } catch (SQLException var2) {
            throw new RuntimeException(var2);
         }

         this.updateCallback = null;
         this.runner = null;
      }
   }

   @Override
   public boolean isRunning() {
      return this.isRunning;
   }

   @Override
   public WorldEngine getEngine() {
      return this.engine;
   }

   private static VarHandle create(Class<?> viewArrayClass) {
      return MethodHandles.byteArrayViewVarHandle(viewArrayClass, ByteOrder.BIG_ENDIAN);
   }

   static {
      boolean hasJDBC = false;

      try {
         Class.forName("org.sqlite.JDBC");
         Class.forName("org.tukaani.xz.XZInputStream");
         hasJDBC = true;
      } catch (NoClassDefFoundError | ClassNotFoundException var2) {
         Logger.warn("Unable to load sqlite JDBC or lzma decompressor, DHImporting wont be available");
      }

      HasRequiredLibraries = hasJDBC;
   }

   private record Task(int x, int z, int fmt, int compression) {
      public long distanceFromZero() {
         return (long)this.x * this.x + (long)this.z * this.z;
      }
   }

   private static final class WorkCTX {
      private final PreparedStatement stmt;
      private final ResettableArrayCache cache;
      private final long[] storageCache;
      private final byte[] colScratch;
      private final VoxelizedSection section;
      private ByteBuffer zstdScratch;
      private ByteBuffer zstdScratch2;
      private final long zstdDCtx;

      public WorkCTX(PreparedStatement stmt, int worldHeight) {
         this.stmt = stmt;
         this.cache = new ResettableArrayCache(new BasicArrayCache());
         this.storageCache = new long[1024 * worldHeight];
         this.colScratch = new byte[65536];
         this.section = VoxelizedSection.createEmpty();
         this.zstdDCtx = Zstd.ZSTD_createDCtx();
      }

      public void free() {
         if (this.zstdScratch != null) {
            MemoryUtil.memFree(this.zstdScratch);
            MemoryUtil.memFree(this.zstdScratch2);
            Zstd.ZSTD_freeDCtx(this.zstdDCtx);
         }
      }
   }
}
