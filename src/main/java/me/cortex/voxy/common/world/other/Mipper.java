package me.cortex.voxy.common.world.other;

public class Mipper {
   public static long mip(long I000, long I100, long I001, long I101, long I010, long I110, long I011, long I111, Mapper mapper) {
      int max = -1;
      if (!Mapper.isAir(I111)) {
         max = mapper.getBlockStateOpacity(I111) << 4 | 7;
      }

      if (!Mapper.isAir(I110)) {
         max = Math.max(mapper.getBlockStateOpacity(I110) << 4 | 6, max);
      }

      if (!Mapper.isAir(I011)) {
         max = Math.max(mapper.getBlockStateOpacity(I011) << 4 | 3, max);
      }

      if (!Mapper.isAir(I010)) {
         max = Math.max(mapper.getBlockStateOpacity(I010) << 4 | 2, max);
      }

      if (!Mapper.isAir(I101)) {
         max = Math.max(mapper.getBlockStateOpacity(I101) << 4 | 5, max);
      }

      if (!Mapper.isAir(I100)) {
         max = Math.max(mapper.getBlockStateOpacity(I100) << 4 | 4, max);
      }

      if (!Mapper.isAir(I001)) {
         max = Math.max(mapper.getBlockStateOpacity(I001) << 4 | 1, max);
      }

      if (!Mapper.isAir(I000)) {
         max = Math.max(mapper.getBlockStateOpacity(I000) << 4, max);
      }

      if (max != -1) {
         return switch (max & 7) {
            case 0 -> I000;
            case 1 -> I001;
            case 2 -> I010;
            case 3 -> I011;
            case 4 -> I100;
            case 5 -> I101;
            case 6 -> I110;
            case 7 -> I111;
            default -> throw new IllegalStateException("Unexpected value: " + (max & 7));
         };
      } else {
         int blockLight = (Mapper.getLightId(I000) & 240)
            + (Mapper.getLightId(I001) & 240)
            + (Mapper.getLightId(I010) & 240)
            + (Mapper.getLightId(I011) & 240)
            + (Mapper.getLightId(I100) & 240)
            + (Mapper.getLightId(I101) & 240)
            + (Mapper.getLightId(I110) & 240)
            + (Mapper.getLightId(I111) & 240);
         int skyLight = (Mapper.getLightId(I000) & 15)
            + (Mapper.getLightId(I001) & 15)
            + (Mapper.getLightId(I010) & 15)
            + (Mapper.getLightId(I011) & 15)
            + (Mapper.getLightId(I100) & 15)
            + (Mapper.getLightId(I101) & 15)
            + (Mapper.getLightId(I110) & 15)
            + (Mapper.getLightId(I111) & 15);
         blockLight = blockLight / 8 & 240;
         skyLight = (int)Math.ceil(skyLight / 8.0);
         return Mapper.withLight(I111, blockLight | skyLight);
      }
   }
}
