package me.cortex.voxy.compat.create;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Set;

public final class CreateFarEntityCompat {
    public static final int TRACKING_DISTANCE_CHUNKS = Math.clamp(
            Integer.getInteger("voxy.createTrackingDistance", 127), 16, 127);
    private static final Set<String> DYNAMIC_ENTITY_PATHS = Set.of(
            "contraption",
            "stationary_contraption",
            "gantry_contraption",
            "carriage_contraption"
    );

    private CreateFarEntityCompat() {
    }

    public static boolean isCreateDynamicEntity(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id != null && "create".equals(id.getNamespace()) && DYNAMIC_ENTITY_PATHS.contains(id.getPath());
    }

    public static boolean shouldExtendServerTracking(EntityType<?> type) {
        if (!isCreateDynamicEntity(type)) {
            return false;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return false;
        }
        return !server.isDedicatedServer() || Boolean.getBoolean("voxy.extendCreateTrackingOnDedicated");
    }

    public static boolean isWithinExtendedDistance(double distanceSquared) {
        double distance = TRACKING_DISTANCE_CHUNKS * 16.0D;
        return distanceSquared <= distance * distance;
    }
}
