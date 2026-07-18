package th.in.midnight_network.immensa.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HydrologyWaterPlacerTest {
    @Test
    void lakeSedimentsFormCoherentNonPeriodicPatches() {
        int size = 96;
        int[][] bed = new int[size][size];
        int clay = 0, sand = 0, gravel = 0;
        int neighborMatches = 0, neighborPairs = 0;
        int periodicMatches = 0, periodicPairs = 0;
        long seed = 0x6eed5eed1234L;

        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            int material = HydrologyWaterPlacer.riverBedMaterial(x, z, true, (short) 0, seed);
            bed[z][x] = material;
            if (material == 0) clay++;
            else if (material == 1) sand++;
            else if (material == 2) gravel++;
            if (x > 0) {
                neighborPairs++;
                if (material == bed[z][x - 1]) neighborMatches++;
            }
            if (x >= 16) {
                periodicPairs++;
                if (material == bed[z][x - 16]) periodicMatches++;
            }
        }

        assertTrue(clay > 100 && sand > 100 && gravel > 100,
                "lake beds should contain several sediment types");
        double coherence = neighborMatches / (double) neighborPairs;
        assertTrue(coherence > 0.72,
                "neighboring bed blocks should form natural patches: " + coherence);
        double periodicity = periodicMatches / (double) periodicPairs;
        assertTrue(periodicity < 0.72,
                "bed materials must not repeat as a 16-block grid: " + periodicity);
    }
}
