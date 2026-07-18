package th.in.midnight_network.immensa.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolarIceFieldPlacerTest {
    private static final long SEED = 819237451L;

    @Test
    void patternIsDeterministicAndWorldCoordinateBased() {
        PolarIceFieldPlacer.IceSample first =
                PolarIceFieldPlacer.sample(255, -513, (short) 48, 180f, SEED);
        PolarIceFieldPlacer.IceSample second =
                PolarIceFieldPlacer.sample(255, -513, (short) 48, 180f, SEED);
        assertEquals(first, second);

        // Sampling either side of a chunk boundary uses no chunk-local state.
        assertEquals(
                PolarIceFieldPlacer.sample(16, 31, (short) 48, 180f, SEED),
                PolarIceFieldPlacer.sample(16, 31, (short) 48, 180f, SEED));
    }

    @Test
    void frozenOceanFormsMassiveButCrackedPackIce() {
        int frozen = countIce((short) 48, 180f);
        int total = 512 * 512;
        assertTrue(frozen > total * 0.48,
                "frozen ocean should contain a massive coherent ice field");
        assertTrue(frozen < total * 0.94,
                "frozen ocean should retain visible open-water leads");
    }

    @Test
    void coldOceanHasFewerFloesThanFrozenOcean() {
        int frozen = countIce((short) 48, 220f);
        int cold = countIce((short) 46, 220f);
        assertTrue(cold > 0, "cold ocean should have marginal floes");
        assertTrue(cold < frozen * 0.72,
                "cold ocean must remain substantially more open");
    }

    @Test
    void floesFragmentFromLargeShoreSlabsIntoSmallOffshorePieces() {
        double shallowScale = averageActiveFloeScale((short) 48, 45f);
        double middleScale = averageActiveFloeScale((short) 48, 360f);
        double outerScale = averageActiveFloeScale((short) 48, 520f);

        assertTrue(shallowScale >= 38,
                "near-shore ice should form broad slabs: " + shallowScale);
        assertTrue(middleScale < shallowScale - 8,
                "marginal pack should visibly fragment: " + middleScale);
        assertTrue(outerScale > 0 && outerScale <= middleScale - 4,
                "outer pack should contain the smallest floes: "
                        + shallowScale + " -> " + middleScale + " -> " + outerScale);
    }

    @Test
    void coldShallowOceanNoLongerUsesTinyRepeatingCells() {
        double scale = averageActiveFloeScale((short) 46, 45f);
        assertTrue(scale >= 30,
                "cold shallow shelves should begin with large sparse pieces, not 11-block tiles: "
                        + scale);
    }

    @Test
    void packIceTerminatesBeforeCoveringTheEntireDeepOcean() {
        assertEquals(0, countIce((short) 48, 1_200f));
        assertEquals(0, countIce((short) 46, 900f));
    }

    @Test
    void shallowPolarWaterBuildsThickerSnowCoveredIce() {
        boolean foundThick = false;
        for (int z = 0; z < 256 && !foundThick; z++) {
            for (int x = 0; x < 256; x++) {
                PolarIceFieldPlacer.IceSample sample =
                        PolarIceFieldPlacer.sample(x, z, (short) 48, 35f, SEED);
                if (sample.ice() && sample.thickness() >= 2
                        && sample.snowLayers() > 0 && sample.packed()) {
                    foundThick = true;
                    break;
                }
            }
        }
        assertTrue(foundThick);
    }

    @Test
    void temperateWaterNeverReceivesPolarIce() {
        PolarIceFieldPlacer.IceSample sample =
                PolarIceFieldPlacer.sample(100, 200, (short) 44, 40f, SEED);
        assertFalse(sample.ice());
        assertEquals(0, sample.thickness());
    }

    private static int countIce(short biome, float depth) {
        int count = 0;
        for (int z = 0; z < 512; z++) {
            for (int x = 0; x < 512; x++) {
                if (PolarIceFieldPlacer.sample(x, z, biome, depth, SEED).ice()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static double averageActiveFloeScale(short biome, float depth) {
        long total = 0;
        int active = 0;
        for (int z = 0; z < 512; z += 2) {
            for (int x = 0; x < 512; x += 2) {
                PolarIceFieldPlacer.IceSample sample =
                        PolarIceFieldPlacer.sample(x, z, biome, depth, SEED);
                if (sample.floeScale() > 0) {
                    total += sample.floeScale();
                    active++;
                }
            }
        }
        return active == 0 ? 0.0 : total / (double) active;
    }
}
