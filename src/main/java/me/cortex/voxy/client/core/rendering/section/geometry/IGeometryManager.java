package me.cortex.voxy.client.core.rendering.section.geometry;

import java.util.function.Consumer;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;

public interface IGeometryManager {
   int uploadSection(BuiltSection var1);

   int uploadReplaceSection(int var1, BuiltSection var2);

   void removeSection(int var1);

   void downloadAndRemove(int var1, Consumer<BuiltSection> var2);
}
