package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;

/**
 * Common initialization for Voxy on NeoForge.
 *
 * IMPORTANT: This class may be loaded very early via mixin class loading,
 * before NeoForge's ModList is populated. We must use LoadingModList or
 * FMLLoader APIs that are available during early bootstrap.
 */
public class VoxyCommon {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;

    static {
        // Use LoadingModList for early access - ModList.get() may be null during mixin loading
        var modFile = LoadingModList.get() != null ? LoadingModList.get().getModFileById("voxy") : null;
        if (modFile == null) {
            IS_IN_MINECRAFT = false;
            Logger.error("Running voxy without minecraft");
            MOD_VERSION = "0.3.3";
            IS_DEDICATED_SERVER = false;
        } else {
            IS_IN_MINECRAFT = true;
            // Get version from LoadingModList (available early)
            var version = modFile.getMods().stream()
                    .filter(m -> m.getModId().equals("voxy"))
                    .findFirst()
                    .map(m -> m.getVersion().toString())
                    .orElse("0.3.3");
            MOD_VERSION = version;
            IS_DEDICATED_SERVER = FMLLoader.getDist() == Dist.DEDICATED_SERVER;
            Serialization.init();
        }
    }

    //The name shown in mod lists, the Sodium options page and the join line. Read from the mod entry
    //rather than repeated as a literal per call site. Needs a populated ModList, so not usable during
    //early mixin bootstrap like the fields above.
    public static String displayName() {
        var list = ModList.get();
        if (list == null) {
            return "voxy";
        }
        return list.getModContainerById("voxy")
                .map(container -> container.getModInfo().getDisplayName())
                .orElse("voxy");
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;//Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            //Logger.info("Voxy factory");
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        INSTANCE = FACTORY.create();
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
