package th.in.midnight_network.immensa.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CaveFluidDrainerTest {
    @Test
    void localCavePoolsAreSparseCoherentAndVerticallySeparated() {
        int size = 768;
        long seed = 0x7a6b5c4d3e2fL;
        boolean[][] water = new boolean[size][size];
        boolean[][] lava = new boolean[size][size];
        int waterCount = 0, lavaCount = 0;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            CaveFluidDrainer.CavePool waterPool = CaveFluidDrainer.cavePoolAt(x, z, seed, false);
            CaveFluidDrainer.CavePool lavaPool = CaveFluidDrainer.cavePoolAt(x, z, seed, true);
            if (waterPool != null) {
                water[z][x] = true;
                waterCount++;
                assertTrue(waterPool.surfaceY() >= -6 && waterPool.surfaceY() <= 45);
                assertTrue(waterPool.maxDepth() >= 1 && waterPool.maxDepth() <= 9);
            }
            if (lavaPool != null) {
                lava[z][x] = true;
                lavaCount++;
                assertTrue(lavaPool.surfaceY() >= -50 && lavaPool.surfaceY() <= -22);
                assertTrue(lavaPool.maxDepth() >= 1 && lavaPool.maxDepth() <= 6);
            }
        }

        double waterRatio = waterCount / (double) (size * size);
        double lavaRatio = lavaCount / (double) (size * size);
        assertTrue(waterRatio > 0.01 && waterRatio < 0.09,
                "water pools should be visible but uncommon: " + waterRatio);
        assertTrue(lavaRatio > 0.001 && lavaRatio < 0.03,
                "local magma pools should be rarer than water: " + lavaRatio);
        assertTrue(supportedColumns(water) > waterCount * 0.94,
                "water shorelines must form basins rather than isolated blocks");
        assertTrue(supportedColumns(lava) > lavaCount * 0.94,
                "lava shorelines must form basins rather than isolated blocks");
    }

    @Test
    void deepLavaMaskCreatesRareCoherentBasins() {
        int size = 384;
        boolean[][] mask = new boolean[size][size];
        int kept = 0;
        int changedBySeed = 0;
        long seed = 0x1a2b3c4d5e6fL;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            boolean value = CaveFluidDrainer.keepDeepLava(x, z, seed);
            mask[z][x] = value;
            if (value) kept++;
            if (value != CaveFluidDrainer.keepDeepLava(x, z, seed + 1)) changedBySeed++;
        }

        double ratio = kept / (double) (size * size);
        assertTrue(ratio > 0.003, "some deep lava basins should remain: " + ratio);
        assertTrue(ratio < 0.18, "lava must not cover the entire deep floor: " + ratio);
        assertTrue(changedBySeed > 500, "lava basins should vary by world seed");

        int supported = 0;
        for (int z = 1; z < size - 1; z++) for (int x = 1; x < size - 1; x++) {
            if (!mask[z][x]) continue;
            if (mask[z][x - 1] || mask[z][x + 1] || mask[z - 1][x] || mask[z + 1][x]) {
                supported++;
            }
        }
        assertTrue(supported > kept * 0.90,
                "lava should form smooth basins, not isolated pixels");
    }

    private static int supportedColumns(boolean[][] mask) {
        int supported = 0;
        for (int z = 1; z < mask.length - 1; z++) {
            for (int x = 1; x < mask[z].length - 1; x++) {
                if (!mask[z][x]) continue;
                if (mask[z][x - 1] || mask[z][x + 1]
                        || mask[z - 1][x] || mask[z + 1][x]) {
                    supported++;
                }
            }
        }
        return supported;
    }
}
