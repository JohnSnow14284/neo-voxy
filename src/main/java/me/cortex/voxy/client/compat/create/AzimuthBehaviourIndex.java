package me.cortex.voxy.client.compat.create;

import dev.engine_room.flywheel.api.instance.Instance;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

//azimuth (bits_n_bobs' framework) renders extra moving parts through per-behaviour visuals hanging off
//a block entity's main visual - a cogwheel's chain strap is one ScrollTransformedInstance living there,
//with no per-frame callback of its own (the scroll is GPU-clock driven). Our cull runs on the parent
//visual, whose collectCrumblingInstances never enumerates the behaviour instances, so they kept drawing
//past the render distance. Behaviour visuals register their instance walker here (keyed by the parent),
//and the cull walks them alongside the parent's own instances.
//
//Values are lambdas over the behaviour's collectCrumblingInstances, so this class carries no azimuth
//types; the registering mixin only applies when azimuth is present. Keys are weak - a deleted visual
//drops its entry with the visual itself. All access happens on Flywheel's frame threads and the main
//thread, hence the synchronized map + copy-on-write lists.
public final class AzimuthBehaviourIndex {
    private AzimuthBehaviourIndex() {}

    private static final Map<Object, List<Consumer<Consumer<Instance>>>> BEHAVIOURS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    public static void register(Object parentVisual, Consumer<Consumer<Instance>> instanceWalker) {
        if (parentVisual == null) {
            return;
        }
        BEHAVIOURS.computeIfAbsent(parentVisual, k -> new CopyOnWriteArrayList<>()).add(instanceWalker);
    }

    //Applies the action to every behaviour instance registered under this visual. A walker that throws
    //(its instance already deleted mid-teardown) is dropped rather than retried forever.
    public static void apply(Object parentVisual, Consumer<Instance> action) {
        List<Consumer<Consumer<Instance>>> walkers = BEHAVIOURS.get(parentVisual);
        if (walkers == null) {
            return;
        }
        for (Consumer<Consumer<Instance>> walker : walkers) {
            try {
                walker.accept(action);
            } catch (Throwable e) {
                walkers.remove(walker);
            }
        }
    }
}
