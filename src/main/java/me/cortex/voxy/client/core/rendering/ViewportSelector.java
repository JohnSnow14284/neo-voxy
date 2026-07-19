package me.cortex.voxy.client.core.rendering;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

//Holds one viewport per render pass that needs its own matrices. Only the default one is handed out:
//the LOD terrain is drawn once per frame from the main camera, so getOrCreate exists for callers that
//key a pass explicitly rather than for pass detection here.
public class ViewportSelector <T extends Viewport<?>> {
    private final Supplier<T> creator;
    private final T defaultViewport;
    private final Map<Object, T> extraViewports = new HashMap<>();//TODO should maybe be a weak hashmap with value cleanup queue thing?

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.creator = viewportCreator;
        this.defaultViewport = viewportCreator.get();
    }

    private T getOrCreate(Object holder) {
        return this.extraViewports.computeIfAbsent(holder, a->this.creator.get());
    }

    public T getViewport() {
        return this.defaultViewport;
    }

    public void free() {
        this.defaultViewport.delete();
        this.extraViewports.values().forEach(Viewport::delete);
        this.extraViewports.clear();
    }
}
