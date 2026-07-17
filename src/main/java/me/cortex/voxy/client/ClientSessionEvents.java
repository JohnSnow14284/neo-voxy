package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;

public class ClientSessionEvents {
   public static boolean inSession = false;

   public static void sessionStart() {
      if (inSession) {
         throw new IllegalStateException("Cannot start new session while in a session");
      } else {
         inSession = true;
         if (VoxyCommon.getInstance() != null) {
            throw new IllegalStateException();
         } else {
            if (VoxyCommon.isAvailable() && VoxyConfig.CONFIG.enabled) {
               VoxyCommon.createInstance();
            }
         }
      }
   }

   public static void sessionEnd() {
      if (!inSession) {
         throw new IllegalStateException("Cannot end a session while not in a session");
      } else {
         inSession = false;
         VoxyCommon.shutdownInstance();
      }
   }
}
