package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;

public class ClientSessionEvents {
    public static volatile boolean inSession = false;
    private static boolean closingSession = false;

    public static void sessionStart() {
        synchronized (ClientSessionEvents.class) {
            if (inSession || closingSession) {
                throw new IllegalStateException("Cannot start a new Voxy session while another session is active");
            }
            inSession = true;
        }

        try {
            //Should never try creating multiple instances via session start
            if (VoxyCommon.getInstance() != null) throw new IllegalStateException();

            if (VoxyCommon.isAvailable() && VoxyConfig.CONFIG.enabled) {
                VoxyCommon.createInstance();
            }
        } catch (RuntimeException exception) {
            synchronized (ClientSessionEvents.class) {
                inSession = false;
            }
            throw exception;
        }
    }

    public static void sessionEnd() {
        synchronized (ClientSessionEvents.class) {
            //Minecraft can reach both disconnect and clearClientLevel for one leave operation.
            if (!inSession || closingSession) return;
            inSession = false;
            closingSession = true;
        }

        try {
            //A capture only ends from the render loop, which stops running here - left armed it would
            //keep the watchdog spinning and GPU timestamp queries enabled for the rest of the process.
            //Diagnostics must never take the shutdown below down with them: failing to reach
            //shutdownInstance leaks the RocksDB handle and strands closingSession, which bricks every
            //later session start.
            try {
                if (FrameProfiler.isActive()) {
                    Logger.info(FrameProfiler.stopAndDump());
                }
            } catch (Throwable t) {
                Logger.error("Failed to close the frame capture on session end", t);
            }

            //Release the render system first. It owns a WorldEngine reference and must be gone
            //before the save queue and RocksDB backend are closed.
            var minecraft = Minecraft.getInstance();
            if (minecraft.levelRenderer instanceof IGetVoxyRenderSystem renderHook) {
                renderHook.voxy$shutdownRenderer();
            }
            VoxyCommon.shutdownInstance();
        } finally {
            synchronized (ClientSessionEvents.class) {
                closingSession = false;
            }
        }
    }
}
