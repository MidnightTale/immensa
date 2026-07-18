package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider.HeightmapData;

import java.util.function.IntUnaryOperator;

/** Selects one stable high-quality hillside cave mouth fully contained by a terrain tile. */
final class ImmensaEntranceSelector {
    private static final double MIN_PASSAGE_REACH = 96.0;
    private static final double MAX_PASSAGE_REACH = 154.0;
    private static final double WALL_CLEARANCE = 26.0;
    private ImmensaEntranceSelector() {
    }

    static Site find(HeightmapData data, int blockStartX, int blockStartZ, long worldSeed) {
        return find(data, blockStartX, blockStartZ, worldSeed,
                meters -> HeightConverter.convertToMinecraftHeight((short) meters));
    }

    /** Test seam that keeps the terrain-site selection independent from Fabric bootstrap. */
    static Site find(HeightmapData data, int blockStartX, int blockStartZ, long worldSeed,
                     IntUnaryOperator heightConverter) {
        if (data == null || data.heightmap == null || data.width < 32 || data.height < 32) {
            return Site.NONE;
        }
        long hash = mix64(worldSeed ^ (long) blockStartX * 0x9e3779b97f4a7c15L
                ^ (long) blockStartZ * 0xc2b2ae3d27d4eb4fL);
        if ((hash & 1L) != 0L) return Site.NONE;

        int[] candidateX = {
                clampCandidate(Math.round(data.width * 0.36f), data.width),
                clampCandidate(Math.round(data.width * 0.50f), data.width),
                clampCandidate(Math.round(data.width * 0.64f), data.width)
        };
        int[] candidateZ = {
                clampCandidate(Math.round(data.height * 0.36f), data.height),
                clampCandidate(Math.round(data.height * 0.50f), data.height),
                clampCandidate(Math.round(data.height * 0.64f), data.height)
        };
        int rotation = (int) ((hash >>> 1) % 9L);
        double bestScore = 0.0;
        Site best = Site.NONE;
        for (int step = 0; step < 9; step++) {
            int candidate = (step + rotation) % 9;
            int localX = candidateX[candidate % 3];
            int localZ = candidateZ[candidate / 3];
            int surfaceY = heightConverter.applyAsInt(data.heightmap[localZ][localX]);
            if (surfaceY < 68) continue;
            double slopeX = surfaceSlopeX(data, localX, localZ, heightConverter);
            double slopeZ = surfaceSlopeZ(data, localX, localZ, heightConverter);
            double magnitude = Math.sqrt(slopeX * slopeX + slopeZ * slopeZ);
            if (magnitude < 0.10) continue;
            double axisX = slopeX / magnitude;
            double axisZ = slopeZ / magnitude;
            double reach = passageReach(localX, localZ, axisX, axisZ,
                    data.width, data.height);
            if (reach < MIN_PASSAGE_REACH) continue;
            double score = magnitude * (0.72 + Math.min(1.0, reach / 128.0) * 0.28);
            if (score > bestScore) {
                bestScore = score;
                best = new Site(blockStartX + localX, blockStartZ + localZ,
                        surfaceY, slopeX, slopeZ,
                        Math.min(1.0, Math.min(data.width, data.height) / 256.0),
                        Math.min(MAX_PASSAGE_REACH, reach));
            }
        }
        return bestScore > 0.0 ? best : Site.NONE;
    }

    /** Distance available uphill before any part of the cavern can meet a tile seam. */
    private static double passageReach(double x, double z, double axisX, double axisZ,
                                       int width, int height) {
        double toX = axisX > 1.0e-6 ? (width - 1.0 - WALL_CLEARANCE - x) / axisX
                : axisX < -1.0e-6 ? (WALL_CLEARANCE - x) / axisX
                : Double.POSITIVE_INFINITY;
        double toZ = axisZ > 1.0e-6 ? (height - 1.0 - WALL_CLEARANCE - z) / axisZ
                : axisZ < -1.0e-6 ? (WALL_CLEARANCE - z) / axisZ
                : Double.POSITIVE_INFINITY;
        return Math.min(toX, toZ);
    }

    private static int clampCandidate(int value, int size) {
        return Math.max(8, Math.min(size - 9, value));
    }

    private static double surfaceSlopeX(HeightmapData data, int localX, int localZ,
                                        IntUnaryOperator heightConverter) {
        int left = Math.max(0, localX - 3);
        int right = Math.min(data.width - 1, localX + 3);
        if (left == right) return 0.0;
        int leftY = heightConverter.applyAsInt(data.heightmap[localZ][left]);
        int rightY = heightConverter.applyAsInt(data.heightmap[localZ][right]);
        return (rightY - leftY) / (double) (right - left);
    }

    private static double surfaceSlopeZ(HeightmapData data, int localX, int localZ,
                                        IntUnaryOperator heightConverter) {
        int back = Math.max(0, localZ - 3);
        int front = Math.min(data.height - 1, localZ + 3);
        if (back == front) return 0.0;
        int backY = heightConverter.applyAsInt(data.heightmap[back][localX]);
        int frontY = heightConverter.applyAsInt(data.heightmap[front][localX]);
        return (frontY - backY) / (double) (front - back);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    record Site(int x, int z, int surfaceY,
                double slopeX, double slopeZ, double scale, double reach) {
        static final Site NONE = new Site(0, 0, 0, 0.0, 0.0, 1.0, 0.0);
    }
}
