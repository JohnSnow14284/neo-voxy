package me.cortex.voxy.client.core.rendering;

import java.util.function.Supplier;

public class ViewportSelector <T extends Viewport<?>> {
    private final T defaultViewport;

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.defaultViewport = viewportCreator.get();
    }

    public T getViewport() {
        return this.defaultViewport;
    }

    public void free() {
        this.defaultViewport.delete();
    }
}
