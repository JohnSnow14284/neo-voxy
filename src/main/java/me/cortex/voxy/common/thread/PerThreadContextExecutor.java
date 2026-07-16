package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.util.TrackedObject;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class PerThreadContextExecutor extends TrackedObject {
    private static final class ThreadContext {
        private final Runnable execute;
        private final Runnable cleanup;

        private ThreadContext(Pair<Runnable, Runnable> wrap) {
            this(wrap.left(), wrap.right());
        }

        private ThreadContext(Runnable execute, Runnable cleanup) {
            this.execute = execute;
            this.cleanup = cleanup;
        }
    }

    private static record ThreadObj(long id) implements LongSupplier {
        private static final AtomicLong IDENTIFIER = new AtomicLong();
        public ThreadObj() {
            this(IDENTIFIER.getAndIncrement());
        }

        @Override
        public long getAsLong() {
            return this.id;
        }
    }

    private static final ThreadLocal<ThreadObj> THREAD_CTX = ThreadLocal.withInitial(ThreadObj::new);
    private final WeakConcurrentCleanableHashMap<ThreadObj, ThreadContext> contexts = new WeakConcurrentCleanableHashMap<>(this::ctxCleaner); //TODO: a custom weak concurrent hashmap that can enqueue values when the value is purged
    private final Supplier<ThreadContext> contextFactory;
    private final Consumer<Exception> exceptionHandler;

    private final AtomicInteger currentRunning = new AtomicInteger();
    private volatile boolean isLive = true;

    PerThreadContextExecutor(Supplier<Pair<Runnable, Runnable>> ctxFactory) {
        this(ctxFactory, (e)->{
            Logger.error("Executor had the following exception",e);
        });
    }
    PerThreadContextExecutor(Supplier<Pair<Runnable, Runnable>> ctxFactory, Consumer<Exception> exceptionHandler) {
        this.contextFactory = ()->new ThreadContext(ctxFactory.get());
        this.exceptionHandler = exceptionHandler;
    }

    private void ctxCleaner(ThreadContext ctx) {
        try {
            ctx.cleanup.run();
        } catch (Exception e) {
            this.exceptionHandler.accept(e);
        }
    }

    boolean run() {
        this.currentRunning.incrementAndGet();
        if (!this.isLive) {
            this.currentRunning.decrementAndGet();
            this.exceptionHandler.accept(new IllegalStateException("Executor is in shutdown"));
            return false;
        }
        var ctx = this.contexts.computeIfAbsent(THREAD_CTX.get(), this.contextFactory);
        try {
            ctx.execute.run();
        } catch (Exception e) {
            this.exceptionHandler.accept(e);
        }
        this.currentRunning.decrementAndGet();
        return true;
    }

    public void shutdown() {
        if (!this.isLive) {
            throw new IllegalStateException("Tried shutting down a executor twice");
        }
        this.isLive = false;
        while (this.currentRunning.get() != 0) {
            Thread.onSpinWait();//TODO: maybe add a sleep or something
        }
        for (var ctx : this.contexts.clear()) {
            ctx.cleanup.run();
        }

        this.free0();
    }

    @Override
    public void free() {
        this.shutdown();
    }

    public boolean isLive() {
        return this.isLive;
    }

}
