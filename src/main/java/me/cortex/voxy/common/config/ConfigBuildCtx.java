package me.cortex.voxy.common.config;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.Map.Entry;

public class ConfigBuildCtx {
   public static final String BASE_SAVE_PATH = "{base_save_path}";
   public static final String WORLD_IDENTIFIER = "{world_identifier}";
   public static final String PLAYER_UUID = "{player_uuid}";
   public static final String DEFAULT_STORAGE_PATH = "{base_save_path}/{world_identifier}/storage/";
   private final Map<String, String> properties = new HashMap<>();
   private final Stack<String> pathStack = new Stack<>();

   public ConfigBuildCtx setProperty(String property, String value) {
      if (property.startsWith("{") && property.endsWith("}")) {
         this.properties.put(property, value);
         return this;
      } else {
         throw new IllegalArgumentException("Property name doesnt start with { and end with }");
      }
   }

   public ConfigBuildCtx pushPath(String path) {
      this.pathStack.push(path);
      return this;
   }

   public ConfigBuildCtx popPath() {
      this.pathStack.pop();
      return this;
   }

   private static String concatPath(String a, String b) {
      if (b.contains("..")) {
         throw new IllegalStateException("Relative resolving not supported");
      } else {
         if (!a.isBlank() && !a.endsWith("/")) {
            a = a + "/";
         }

         if (b.startsWith("/")) {
            return b;
         } else {
            if (b.startsWith("./")) {
               b = b.substring(2);
            }

            return b.startsWith(":", 1) ? b : a + b;
         }
      }
   }

   public String resolvePath() {
      String prev = "";
      String path = "";

      do {
         prev = path;
         path = "";

         for (String part : this.pathStack) {
            path = concatPath(path, part);
         }
      } while (!prev.equals(path));

      return path;
   }

   public String substituteString(String string) {
      for (Entry<String, String> entry : this.properties.entrySet()) {
         string = string.replace(entry.getKey(), entry.getValue());
      }

      return string;
   }

   public String ensurePathExists(String path) {
      try {
         Files.createDirectories(new File(path).toPath());
         return path;
      } catch (Exception var3) {
         throw new RuntimeException(var3);
      }
   }
}
