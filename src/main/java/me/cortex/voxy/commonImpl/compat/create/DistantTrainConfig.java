package me.cortex.voxy.commonImpl.compat.create;

//Control point for distant-train pose streaming, read by the server-side CreateTrainSampler. Holds no
//client-type references, so the sampler can read it on any dist without dragging in client classes.
//Two independent inputs are combined:
//
//  - CLIENT preference  (VoxyConfig.save -> updateClientConfig): how far THIS client wants trains.
//    On the integrated server (client + server in one JVM) it carries the host's own render distance;
//    on a dedicated server there is no client in the JVM to write it, so it stays at the defaults.
//  - SERVER ceiling     (CreateServerConfig -> updateServerConfig): the dedicated-server admin's
//    uniform ceiling from voxy-server.toml, applied to every player. On a dedicated server this is the
//    only control; on the integrated server it further caps the host's preference.
//
//The sampler uses the tighter of the two (enabled = both, distance = min), so neither side can widen
//what the other narrowed, and each degrades to "no constraint" (enabled, HARD_MAX) when unset.
public final class DistantTrainConfig {
    private DistantTrainConfig() {}

    //Absolute ceiling the sampler will never exceed regardless of either input.
    public static final double HARD_MAX = 3072;

    //Client (integrated-server host) preference.
    public static volatile boolean clientEnabled = true;
    public static volatile double clientMaxDistance = HARD_MAX;

    //Server (dedicated admin) uniform ceiling.
    public static volatile boolean serverEnabled = true;
    public static volatile double serverMaxDistance = HARD_MAX;
    public static volatile int sampleIntervalTicks = 5;

    private static double clampDistance(double blocks) {
        return blocks > 0 ? Math.min(blocks, HARD_MAX) : HARD_MAX;
    }

    public static void updateClientConfig(boolean enabled, double maxDistanceBlocks) {
        clientEnabled = enabled;
        clientMaxDistance = clampDistance(maxDistanceBlocks);
    }

    public static void updateServerConfig(boolean enabled, double maxDistanceBlocks, int intervalTicks) {
        serverEnabled = enabled;
        serverMaxDistance = clampDistance(maxDistanceBlocks);
        sampleIntervalTicks = Math.max(1, intervalTicks);
    }

    //Combined values the sampler reads.
    public static boolean enabled() {
        return clientEnabled && serverEnabled;
    }

    public static double maxDistance() {
        return Math.min(clientMaxDistance, serverMaxDistance);
    }

    public static int sampleInterval() {
        return sampleIntervalTicks;
    }
}
