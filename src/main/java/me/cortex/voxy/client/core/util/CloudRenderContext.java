package me.cortex.voxy.client.core.util;

public final class CloudRenderContext {
    private static int depth;

    private CloudRenderContext() {}

    public static void begin() {
        depth++;
    }

    public static void end() {
        if (depth > 0) {
            depth--;
        }
    }

    public static boolean isActive() {
        return depth != 0;
    }
}
