package me.cortex.voxy.client;

import java.util.ArrayList;

public class TimingStatistics {
   public static double ROLLING_WEIGHT = 0.96;
   private static final ArrayList<TimingStatistics.TimeSampler> allSamplers = new ArrayList<>();
   public static TimingStatistics.TimeSampler all = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler main = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler dynamic = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler postDynamic = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler A = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler B = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler C = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler D = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler E = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler F = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler G = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler H = new TimingStatistics.TimeSampler();
   public static TimingStatistics.TimeSampler I = new TimingStatistics.TimeSampler();

   public static void resetSamplers() {
      allSamplers.forEach(TimingStatistics.TimeSampler::reset);
   }

   private static void updateSamplers() {
      allSamplers.forEach(TimingStatistics.TimeSampler::update);
   }

   public static void update() {
      updateSamplers();
   }

   public static final class TimeSampler {
      private boolean running;
      private long timestamp;
      private long runtime;
      private double rolling;

      public TimeSampler() {
         TimingStatistics.allSamplers.add(this);
      }

      private void reset() {
         if (this.running) {
            throw new IllegalStateException();
         } else {
            this.runtime = 0L;
         }
      }

      public void start() {
         if (this.running) {
            throw new IllegalStateException();
         } else {
            this.running = true;
            this.timestamp = System.nanoTime();
         }
      }

      public void stop() {
         if (!this.running) {
            throw new IllegalStateException();
         } else {
            this.running = false;
            this.runtime = this.runtime + (System.nanoTime() - this.timestamp);
         }
      }

      public void subtract(TimingStatistics.TimeSampler sampler) {
         this.runtime = this.runtime - sampler.runtime;
      }

      private void update() {
         double time = this.runtime / 1000L / 1000.0;
         this.rolling = Math.max(this.rolling * TimingStatistics.ROLLING_WEIGHT + time * (1.0 - TimingStatistics.ROLLING_WEIGHT), time);
      }

      public double getRolling() {
         return this.rolling;
      }

      public String pVal() {
         return String.format("%6.3f", this.rolling);
      }
   }
}
