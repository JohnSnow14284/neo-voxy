package me.cortex.voxy.client.core.compat.eclipticseasons;

import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.importers.IDataImporter;

public class VoxyESImportManager
extends ImportManager {
    protected synchronized ImportManager.ImportTask createImportTask(IDataImporter importer) {
        return new ESImportTask(importer);
    }

    protected class ESImportTask
    extends ImportManager.ImportTask {
        protected ESImportTask(IDataImporter importer) {
            super(importer);
        }

        protected void onCompleted(int total) {
            VoxyTool.releaseImporter();
            super.onCompleted(total);
        }

        protected void shutdown() {
            VoxyTool.releaseImporter();
            super.shutdown();
        }
    }
}

