package me.cortex.voxy.compat.far;

import me.cortex.voxy.compat.far.FarEntityProtocol.Hello;
import me.cortex.voxy.compat.far.FarEntityProtocol.ItemSnapshot;
import me.cortex.voxy.compat.far.FarEntityProtocol.PlayerBatch;
import me.cortex.voxy.compat.far.FarEntityProtocol.PlayerSnapshot;
import me.cortex.voxy.compat.far.FarEntityProtocol.PlayersPayload;
import me.cortex.voxy.compat.far.FarEntityProtocol.VehicleSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FarEntityService {
    private static final int UPDATE_INTERVAL_TICKS = 10;
    private static final int MAX_DISTANCE_BLOCKS = 32768;
    private final Map<UUID, ClientSettings> subscribers = new ConcurrentHashMap<>();
    private int tickCounter;

    public void handleHello(ServerPlayer player, Hello hello) {
        if (hello.version() != FarEntityProtocol.VERSION) {
            this.subscribers.remove(player.getUUID());
            return;
        }
        this.subscribers.put(player.getUUID(), new ClientSettings(
                hello.enabled(),
                Math.clamp(hello.maximumDistanceBlocks(), 64, MAX_DISTANCE_BLOCKS),
                hello.shareSelf()
        ));
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        this.subscribers.remove(event.getEntity().getUUID());
    }

    public void onServerTick(ServerTickEvent.Post event) {
        this.tick(event.getServer());
    }

    private void tick(MinecraftServer server) {
        if (this.subscribers.isEmpty() || ++this.tickCounter < UPDATE_INTERVAL_TICKS) {
            return;
        }
        this.tickCounter = 0;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer viewer : players) {
            ClientSettings settings = this.subscribers.get(viewer.getUUID());
            if (settings != null && settings.enabled()) {
                this.sendSnapshot(viewer, players, settings.maximumDistanceBlocks());
            }
        }
    }

    private void sendSnapshot(ServerPlayer viewer, List<ServerPlayer> onlinePlayers, int maximumDistance) {
        double maximumDistanceSquared = (double) maximumDistance * maximumDistance;
        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (ServerPlayer target : onlinePlayers) {
            if (target == viewer || target.level() != viewer.level()) {
                continue;
            }
            if (!target.isAlive() || target.isRemoved() || target.isSpectator() || target.isInvisible()) {
                continue;
            }
            if (viewer.distanceToSqr(target) > maximumDistanceSquared) {
                continue;
            }
            ClientSettings targetSettings = this.subscribers.get(target.getUUID());
            if (targetSettings != null && !targetSettings.shareSelf()) {
                continue;
            }

            snapshots.add(new PlayerSnapshot(
                    target.getUUID(),
                    target.getGameProfile().getName(),
                    target.getX(), target.getY(), target.getZ(),
                    target.getYRot(), target.getYHeadRot(), target.getXRot(),
                    target.isShiftKeyDown(), target.isFallFlying(), target.isSwimming(),
                    item(target.getMainHandItem()),
                    item(target.getOffhandItem()),
                    item(target.getItemBySlot(EquipmentSlot.FEET)),
                    item(target.getItemBySlot(EquipmentSlot.LEGS)),
                    item(target.getItemBySlot(EquipmentSlot.CHEST)),
                    item(target.getItemBySlot(EquipmentSlot.HEAD)),
                    vehicle(target.getVehicle())
            ));
        }

        PlayerBatch batch = new PlayerBatch(
                viewer.level().dimension().location().toString(),
                snapshots
        );
        PacketDistributor.sendToPlayer(viewer, new PlayersPayload(batch));
    }

    private static ItemSnapshot item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemSnapshot.EMPTY;
        }
        return new ItemSnapshot(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount()
        );
    }

    private static VehicleSnapshot vehicle(Entity entity) {
        if (entity == null) {
            return null;
        }
        return new VehicleSnapshot(
                entity.getUUID(),
                entity.getId(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                entity.getX(), entity.getY(), entity.getZ(),
                entity.getYRot(), entity.getXRot()
        );
    }

    private record ClientSettings(boolean enabled, int maximumDistanceBlocks, boolean shareSelf) {
    }
}
