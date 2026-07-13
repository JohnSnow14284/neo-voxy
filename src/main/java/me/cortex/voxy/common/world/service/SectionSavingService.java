package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.concurrent.ConcurrentLinkedDeque;

public class SectionSavingService {
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;

    private record SaveEntry(WorldEngine engine, WorldSection section) {
    }

    private final Service service;
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();

    public SectionSavingService(ServiceManager serviceManager) {
        this.service = serviceManager.createServiceNoCleanup(() -> this::processJob, 100, "Section saving service");
    }

    private void processJob() {
        SaveEntry entry = this.saveQueue.pop();
        WorldSection section = entry.section();
        section.assertNotFree();

        try {
            section.setNotDirty();
            if (!section.exchangeIsInSaveQueue(false)) {
                section.markDirty();
                Logger.error("Voxy saver lost ownership of queued section: " + WorldEngine.pprintPos(section.key));
            } else {
                try {
                    entry.engine().storage.saveSection(section);
                } catch (Exception e) {
                    section.markDirty();
                    throw e;
                }
            }
        } catch (Exception e) {
            Logger.error("Voxy saver failed while writing a section", e);
        }

        section.release();
    }

    public boolean enqueueSave(WorldEngine engine, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        if (!section.exchangeIsInSaveQueue(true)) {
            return false;
        }

        if (!sectionAlreadyAcquired) {
            section.acquire();
        }

        if (!nonBlocking && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
            Thread.yield();
            while (this.getTaskCount() > SOFT_MAX_QUEUE_SIZE && this.service.isLive()) {
                if (!this.service.steal()) {
                    break;
                }
                this.processJob();
            }
        }

        this.saveQueue.add(new SaveEntry(engine, section));
        this.service.execute();
        return true;
    }

    public void shutdown() {
        if (this.service.numJobs() != 0) {
            Logger.error("Voxy section saving still in progress, estimated " + this.service.numJobs() + " sections remaining");
            this.service.blockTillEmpty();
        }

        this.service.shutdown();
        while (!this.saveQueue.isEmpty()) {
            this.processJob();
        }
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }
}
