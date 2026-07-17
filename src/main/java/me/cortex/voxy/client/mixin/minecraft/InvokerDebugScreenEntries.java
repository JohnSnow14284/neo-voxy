package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DebugScreenEntries.class)
public interface InvokerDebugScreenEntries {
   @Invoker("register")
   static Identifier voxy$register(Identifier identifier, DebugScreenEntry entry) {
      throw new AssertionError("Mixin was not applied");
   }
}
