package me.cortex.voxy.client;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class RenderStatistics {
   public static boolean enabled = false;
   public static final int[] hierarchicalTraversalCounts = new int[5];
   public static final int[] hierarchicalRenderSections = new int[5];
   public static final int[] visibleSections = new int[5];
   public static final int[] quadCount = new int[5];

   public static void addDebug(List<String> debug) {
      if (enabled) {
         debug.add("HTC: [" + Arrays.stream(flipCopy(hierarchicalTraversalCounts)).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "]");
         debug.add("HRS: [" + Arrays.stream(flipCopy(hierarchicalRenderSections)).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "]");
         debug.add("VS: [" + Arrays.stream(flipCopy(visibleSections)).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "]");
         debug.add("QC: [" + Arrays.stream(flipCopy(quadCount)).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "]");
      }
   }

   private static int[] flipCopy(int[] array) {
      int[] ret = new int[array.length];
      int i = ret.length;

      for (int j : array) {
         ret[--i] = j;
      }

      return ret;
   }
}
