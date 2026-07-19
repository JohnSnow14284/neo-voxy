package me.cortex.voxy.common.config.storage.other;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.compressors.CompressorConfig;
import me.cortex.voxy.common.config.compressors.StorageCompressor;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;

//Compresses the section data
public class CompressionStorageAdaptor extends DelegatingStorageAdaptor {
    private final StorageCompressor compressor;
    public CompressionStorageAdaptor(StorageCompressor compressor, StorageBackend delegate) {
        super(delegate);
        this.compressor = compressor;
    }


    //TODO: figure out a nicer way w.r.t scratch buffer shit
    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        var data = this.delegate.getSectionData(key, scratch);
        if (data == null) {
            return null;
        }
        return this.compressor.decompress(data);
    }

    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        var cdata = this.compressor.compress(data);
        this.delegate.setSectionData(key, cdata);
        //Note that the data isnt freed (data cache in the compressors are used)
    }

    //Must override: inheriting the base pass-through would hand raw sections to the delegate and
    //silently stop compressing them. Compression happens per entry, exactly as in setSectionData.
    @Override
    public SectionWriteBatch createSectionWriteBatch() {
        var inner = this.delegate.createSectionWriteBatch();
        return new SectionWriteBatch() {
            @Override
            public void put(long key, MemoryBuffer data) {
                inner.put(key, CompressionStorageAdaptor.this.compressor.compress(data));
            }

            @Override public long dataSize() { return inner.dataSize(); }
            @Override public void commit() { inner.commit(); }
            @Override public void close() { inner.close(); }
        };
    }

    @Override
    public void close() {
        this.compressor.close();
        super.close();
    }

    public static class Config extends DelegateStorageConfig {
        public CompressorConfig compressor;

        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            return new CompressionStorageAdaptor(this.compressor.build(ctx), this.delegate.build(ctx));
        }

        public static String getConfigTypeName() {
            return "CompressionAdaptor";
        }
    }
}
