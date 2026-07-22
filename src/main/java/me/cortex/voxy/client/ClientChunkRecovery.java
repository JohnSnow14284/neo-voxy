package me.cortex.voxy.client;

import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.LevelChunk;

//Client-only half of the chunkless-ingest fix, kept out of the common ingest service so that class does
//not name Minecraft: a common class that reaches a client type is what trips RuntimeDistCleaner on a
//dedicated server, and this fork has been broken that way before.
public final class ClientChunkRecovery {
    private ClientChunkRecovery() {}

    //The chunk a server-fed section belongs to, if this client happens to have it loaded.
    //
    //The dimension key is checked rather than assuming the client is where the section is for: this pack
    //runs sable sub-levels and generated mirror_* dimensions, so the active level is often not the one an
    //engine belongs to, and a chunk taken from the wrong level would decorate with the wrong materials.
    public static LevelChunk find(WorldIdentifier id, int chunkX, int chunkZ) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(id.key)) {
            return null;
        }
        return ((ICheekyClientChunkCache) level.getChunkSource()).voxy$cheekyGetChunk(chunkX, chunkZ);
    }
}
