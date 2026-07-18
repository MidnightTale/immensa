package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.pipeline.WorldPipelineModelConfig;

public class HeightConverter {
    private static final int SEA_LEVEL = 63;
    private static final double HIGH_ALTITUDE_COMPRESSION_START_METERS = 6_000.0;
    private static final double EXTREME_INPUT_REFERENCE_METERS = 14_000.0;
    private static final int SUMMIT_HEADROOM_BLOCKS = 24;
    private static final int TAIL_RESERVE_BLOCKS = 6;
    private static final int[] WORLD_TOP_Y = {0, 399, 735, 1071, 1407, 1743, 1967};

    public static int convertToMinecraftHeight(short meters) {
        return convertToMinecraftHeight(meters, WorldScaleManager.getCurrentScale());
    }

    public static int convertToMinecraftHeight(short meters, int configuredScale) {
        return convertToMinecraftHeight(meters, configuredScale,
                WorldPipelineModelConfig.nativeResolution());
    }

    static int convertToMinecraftHeight(short meters, int configuredScale,
                                        float nativeResolution) {
        int baseY;
        int scale = WorldScaleManager.clampScale(configuredScale);
        float resolution = nativeResolution / scale;

        if (meters >= 0) {
            baseY = (int) (compressHighElevation(meters, resolution,
                    WORLD_TOP_Y[scale]) / resolution);
        } else {
            baseY = (int) (-Math.sqrt(Math.abs(meters) + 10) + Math.sqrt(10.0)) - 1;
        }

        return baseY + SEA_LEVEL;
    }

    /**
     * Returns the highest generated block Y expected from pipeline output for a given scale.
     */
    public static int getMaxGeneratedYForScale(int configuredScale) {
        return convertToMinecraftHeight(Short.MAX_VALUE, configuredScale);
    }

    /**
     * Preserves ordinary terrain exactly, then maps extreme regional uplift into
     * the usable vertical range for the selected world scale. The previous tanh
     * curve saturated too early: a real 2.5 km summit profile could collapse to
     * only a few dozen blocks and look like a table. This monotonic Hermite curve
     * keeps useful slope through 6-14 km, then approaches the ceiling smoothly.
     */
    private static double compressHighElevation(double meters, double resolution,
                                                  int worldTopY) {
        if (meters <= HIGH_ALTITUDE_COMPRESSION_START_METERS) return meters;

        double ceilingMeters = (worldTopY - SUMMIT_HEADROOM_BLOCKS - SEA_LEVEL) * resolution;
        double tailReserveMeters = TAIL_RESERVE_BLOCKS * resolution;
        double curveTargetMeters = ceilingMeters - tailReserveMeters;
        double inputRange = EXTREME_INPUT_REFERENCE_METERS
                - HIGH_ALTITUDE_COMPRESSION_START_METERS;
        double outputRange = curveTargetMeters
                - HIGH_ALTITUDE_COMPRESSION_START_METERS;

        if (meters < EXTREME_INPUT_REFERENCE_METERS) {
            double t = (meters - HIGH_ALTITUDE_COMPRESSION_START_METERS) / inputRange;
            double t2 = t * t;
            double t3 = t2 * t;
            double h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
            double h10 = t3 - 2.0 * t2 + t;
            double h01 = -2.0 * t3 + 3.0 * t2;
            double h11 = t3 - t2;
            double startSlope = inputRange / outputRange;
            double endSlope = startSlope * 0.08;
            double normalized = h10 * startSlope + h01 + h11 * endSlope;
            return HIGH_ALTITUDE_COMPRESSION_START_METERS + outputRange * normalized;
        }

        // Match the Hermite curve's 0.08 end derivative while retaining a true
        // asymptote. Even pathological elevations cannot create a hard-cut roof.
        double tailScale = tailReserveMeters / 0.08;
        return ceilingMeters - tailReserveMeters
                * Math.exp(-(meters - EXTREME_INPUT_REFERENCE_METERS) / tailScale);
    }
}
