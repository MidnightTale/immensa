package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.pipeline.TerrainMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeologicalStrataPlacerTest {
    private static final long SEED = 773492817L;

    @Test
    void contactMovesAcrossBroadGeologicalProvinces() {
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int z = -2400; z <= 2400; z += 80) {
            for (int x = -2400; x <= 2400; x += 80) {
                int contact = GeologicalStrataPlacer.contactY(
                        x, z, TerrainMetadata.GEO_SEDIMENTARY, SEED);
                minimum = Math.min(minimum, contact);
                maximum = Math.max(maximum, contact);
            }
        }
        assertTrue(maximum - minimum >= 28,
                "the contact should roll through many vertical levels");
    }

    @Test
    void geologyChangesContactWithoutCreatingAUniversalLevel() {
        int volcanic = GeologicalStrataPlacer.contactY(
                500, -700, TerrainMetadata.GEO_VOLCANIC, SEED);
        int limestone = GeologicalStrataPlacer.contactY(
                500, -700, TerrainMetadata.GEO_LIMESTONE, SEED);
        assertTrue(volcanic >= limestone + 14);
    }

    @Test
    void deepRockIsReliableAndUpperRockRemainsStone() {
        for (int z = -128; z <= 128; z += 16) {
            for (int x = -128; x <= 128; x += 16) {
                assertTrue(GeologicalStrataPlacer.isDeepslate(
                        x, -54, z, TerrainMetadata.GEO_SEDIMENTARY, SEED));
                assertFalse(GeologicalStrataPlacer.isDeepslate(
                        x, 54, z, TerrainMetadata.GEO_SEDIMENTARY, SEED));
            }
        }
    }

    @Test
    void mixedBandContainsStoneWindowsAndDeepslateTongues() {
        boolean stoneBelowZero = false;
        boolean deepslateAboveZero = false;
        for (int z = -512; z <= 512; z += 8) {
            for (int x = -512; x <= 512; x += 8) {
                stoneBelowZero |= !GeologicalStrataPlacer.isDeepslate(
                        x, -4, z, TerrainMetadata.GEO_LIMESTONE, SEED);
                deepslateAboveZero |= GeologicalStrataPlacer.isDeepslate(
                        x, 12, z, TerrainMetadata.GEO_VOLCANIC, SEED);
            }
        }
        assertTrue(stoneBelowZero, "stone should occasionally extend below Y=0");
        assertTrue(deepslateAboveZero,
                "deepslate should occasionally rise well above Y=0");
    }

    @Test
    void adjacentColumnsRemainContinuousAcrossChunkBorders() {
        for (int z = -256; z <= 256; z += 16) {
            int left = GeologicalStrataPlacer.contactY(
                    15, z, TerrainMetadata.GEO_GRANITIC, SEED);
            int right = GeologicalStrataPlacer.contactY(
                    16, z, TerrainMetadata.GEO_GRANITIC, SEED);
            assertTrue(Math.abs(left - right) <= 2,
                    "contact jumped at chunk border near z=" + z);
        }
    }
}
