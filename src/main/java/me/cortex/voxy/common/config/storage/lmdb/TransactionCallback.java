package me.cortex.voxy.common.config.storage.lmdb;

import org.lwjgl.system.MemoryStack;

public interface TransactionCallback<T> {
   T exec(MemoryStack var1, long var2);
}
