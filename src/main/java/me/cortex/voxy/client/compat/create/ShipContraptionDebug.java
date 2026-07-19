package me.cortex.voxy.client.compat.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import me.cortex.voxy.client.compat.ShipBorne;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

//State dump behind /voxy debug ship. Ship-borne contraptions cross six render/sync gates split over
//four mods (see the sable compat classes); when one regresses, the per-entity readouts here - the
//synced contraption size, EntityCulling's verdict, and sable's per-plot Flywheel state - identify the
//broken layer without a round of instrumented builds.
public final class ShipContraptionDebug {
    private ShipContraptionDebug() {}

    public static String dump() {
        var sb = new StringBuilder("ship contraption debug: sableShipsPresent=").append(ShipBorne.anyShipPresent())
                .append("\n flywheel depth wrap lastSkip=").append(me.cortex.voxy.client.compat.sable.VoxySableDepthShim.lastSkipReason);
        try {
            sb.append("\n sable flw state self-heal: calls=").append(me.cortex.voxy.client.compat.sable.SableShipContent.ensureCalls)
                    .append(" registered=").append(me.cortex.voxy.client.compat.sable.SableShipContent.ensureRegistered);
        } catch (LinkageError ignored) {
        }

        var level = Minecraft.getInstance().level;
        if (level != null) {
            int total = 0, onShip = 0;
            var lines = new StringBuilder();
            for (Entity e : level.entitiesForRendering()) {
                if (!(e instanceof AbstractContraptionEntity)) {
                    continue;
                }
                total++;
                boolean ship = ShipBorne.isShipBorne(e.getX(), e.getZ());
                if (ship) {
                    onShip++;
                }
                if (total <= 8) {
                    lines.append("\n  ").append(e.getType().toShortString())
                            .append(" @ ").append((int) e.getX()).append(',').append((int) e.getY()).append(',').append((int) e.getZ())
                            .append(ship ? " [ship]" : "");
                    var contraption = ((AbstractContraptionEntity) e).getContraption();
                    lines.append(" contraption=").append(contraption == null ? "NULL" : contraption.getBlocks().size() + " blocks");
                    lines.append(" nowheelCulled=").append(NowheelCulled.isCulled(e));
                    if (ship) {
                        String flwState;
                        try {
                            flwState = me.cortex.voxy.client.compat.sable.SableShipContent.flywheelStateStatus(e);
                        } catch (LinkageError | RuntimeException ex) {
                            flwState = "error: " + ex;
                        }
                        lines.append(" sableFlwState=").append(flwState);
                    }
                }
            }
            sb.append("\n contraption entities in level: ").append(total).append(" (onShip=").append(onShip).append(')').append(lines);
        }
        return sb.toString();
    }
}
