package me.cortex.voxy.client.core.model;

public abstract class ModelQueries {
   public static boolean faceExists(long metadata, int face) {
      return (metadata >> 8 * face & 255L) != 255L;
   }

   public static boolean faceCanBeOccluded(long metadata, int face) {
      return (metadata >> 8 * face & 4L) == 4L;
   }

   public static boolean faceOccludes(long metadata, int face) {
      return faceExists(metadata, face) && (metadata >> 8 * face & 1L) == 1L;
   }

   public static boolean faceUsesSelfLighting(long metadata, int face) {
      return (metadata >> 8 * face & 8L) != 0L;
   }

   public static boolean isDoubleSided(long metadata) {
      return (metadata >> 48 & 4L) != 0L;
   }

   public static long _isDoubleSided(long metadata) {
      return metadata >> 50 & 1L;
   }

   public static boolean isTranslucent(long metadata) {
      return (metadata >> 48 & 2L) != 0L;
   }

   public static long _isTranslucent(long metadata) {
      return metadata >> 49 & 1L;
   }

   public static boolean containsFluid(long metadata) {
      return (metadata >> 48 & 8L) != 0L;
   }

   public static long _containsFluid(long metadata) {
      return metadata >> 51 & 1L;
   }

   public static boolean isFluid(long metadata) {
      return (metadata >> 48 & 16L) != 0L;
   }

   public static long _isFluid(long metadata) {
      return metadata >> 52 & 1L;
   }

   public static boolean isBiomeColoured(long metadata) {
      return (metadata >> 48 & 1L) != 0L;
   }

   public static long _isBiomeColoured(long metadata) {
      return metadata >> 48 & 1L;
   }

   public static long _notIsBiomeColoured(long metadata) {
      return ~metadata >> 48 & 1L;
   }

   public static boolean cullsSame(long metadata) {
      return (metadata >> 48 & 32L) != 0L;
   }

   public static boolean isFullyOpaque(long metadata) {
      return (metadata >> 48 & 64L) != 0L;
   }

   public static long _isFullyOpaque(long metadata) {
      return metadata >> 54 & 1L;
   }

   public static long lightEmission(long meta) {
      return meta >> 55 & 15L;
   }
}
