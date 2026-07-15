package me.cortex.voxy.compat.far.create;

/** Forces Create's normal buffered fallback only for Voxy's detached proxies. */
public final class FarContraptionRenderContext {
    private static final ThreadLocal<Integer> FALLBACK_DEPTH = ThreadLocal.withInitial(() -> 0);

    private FarContraptionRenderContext() {
    }

    public static boolean forceFallback() {
        return FALLBACK_DEPTH.get() > 0;
    }

    public static void enter() {
        FALLBACK_DEPTH.set(FALLBACK_DEPTH.get() + 1);
    }

    public static void exit() {
        int depth = FALLBACK_DEPTH.get() - 1;
        if (depth <= 0) FALLBACK_DEPTH.remove();
        else FALLBACK_DEPTH.set(depth);
    }
}
