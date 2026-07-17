package me.cortex.voxy.client.core.gl.shader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.apache.commons.io.IOUtils;

public class ShaderLoader {
   public static String parse(String id) {
      String src = "#version 460 core\n";
      return src + String.join("\n", ShaderLoader.ShaderLoadingParser.parseRoot(Identifier.parse(id)));
   }

   private static final class ShaderLoadingParser {
      private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");

      public static List<String> parseRoot(Identifier id) {
         List<String> out = new ArrayList<>();

         for (String line : toLines(loadShaderAsset(id))) {
            if (!line.startsWith("#version")) {
               if (line.startsWith("#import")) {
                  Matcher match = IMPORT_PATTERN.matcher(line);
                  if (!match.matches()) {
                     throw new IllegalArgumentException("Unknown import: " + line);
                  }

                  Identifier iid = Identifier.fromNamespaceAndPath(match.group("namespace"), match.group("path"));
                  out.addAll(parseRoot(iid));
               } else {
                  out.add(line);
               }
            }
         }

         return out;
      }

      private static List<String> toLines(String src) {
         try {
            return new BufferedReader(new StringReader(src)).readAllLines();
         } catch (IOException var2) {
            throw new RuntimeException(var2);
         }
      }

      private static String loadShaderAsset(Identifier id) {
         String path = String.format("/assets/%s/shaders/%s", id.getNamespace(), id.getPath());

         try {
            String var3;
            try (InputStream in = ShaderLoader.ShaderLoadingParser.class.getResourceAsStream(path)) {
               if (in == null) {
                  throw new RuntimeException("Shader not found: " + path);
               }

               var3 = IOUtils.toString(in, StandardCharsets.UTF_8);
            }

            return var3;
         } catch (IOException var7) {
            throw new RuntimeException("Failed to read shader source for " + path, var7);
         }
      }
   }
}
