package me.cortex.voxy.common;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

public class Logger {
   public static boolean INSERT_CLASS = true;
   public static boolean SHUTUP = false;
   public static boolean SHUTUP_INFO = false;
   private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("Voxy");

   private static String callClsName() {
      String className = "";
      if (INSERT_CLASS) {
         StackTraceElement stackEntry = new Throwable().getStackTrace()[2];
         className = stackEntry.getClassName();
         StringBuilder builder = new StringBuilder();
         String[] parts = className.split("\\.");

         for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i < parts.length - 1) {
               builder.append(part.charAt(0)).append(part.charAt(part.length() - 1));
            } else {
               builder.append(part);
            }

            if (i != parts.length - 1) {
               builder.append(".");
            }
         }

         className = builder.toString();
      }

      return className;
   }

   public static void error(Object... args) {
      if (!SHUTUP) {
         Throwable throwable = null;

         for (Object i : args) {
            if (i instanceof Throwable) {
               throwable = (Throwable)i;
            }
         }

         String error = (INSERT_CLASS ? "[" + callClsName() + "]: " : "") + Stream.of(args).map(Logger::objToString).collect(Collectors.joining(" "));
         LOGGER.error(error, throwable);
         if (VoxyCommon.IS_IN_MINECRAFT && !VoxyCommon.IS_DEDICATED_SERVER) {
            showInHUD(error);
         }
      }
   }

   public static void showInHUD(String msg) {
      Minecraft instance = Minecraft.getInstance();
      if (instance != null) {
         instance.executeIfPossible(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
               instance.getChatListener().handleSystemMessage(Component.literal(msg), true);
            }
         });
      }
   }

   public static void warn(Object... args) {
      if (!SHUTUP) {
         Throwable throwable = null;

         for (Object i : args) {
            if (i instanceof Throwable) {
               throwable = (Throwable)i;
            }
         }

         LOGGER.warn((INSERT_CLASS ? "[" + callClsName() + "]: " : "") + Stream.of(args).map(Logger::objToString).collect(Collectors.joining(" ")), throwable);
      }
   }

   public static String info(Object... args) {
      if (!SHUTUP && !SHUTUP_INFO) {
         Throwable throwable = null;

         for (Object i : args) {
            if (i instanceof Throwable) {
               throwable = (Throwable)i;
            }
         }

         String val = (INSERT_CLASS ? "[" + callClsName() + "]: " : "") + Stream.of(args).map(Logger::objToString).collect(Collectors.joining(" "));
         LOGGER.info(val, throwable);
         return val;
      } else {
         return "";
      }
   }

   private static String objToString(Object obj) {
      if (obj == null) {
         return "NULL";
      } else {
         return obj.getClass().isArray() ? Arrays.deepToString((Object[])obj) : obj.toString();
      }
   }
}
