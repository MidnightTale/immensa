package th.in.midnight_network.immensa.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class HydrologyProcessorTest {
    @Test
    void closedBasinCreatesLevelLakeAndGreenerShore() {
        int size = 13;
        short[][] elevation = filled(size, size, (short) 120);
        short[][] biome = filled(size, size, (short) 5);
        for (int r = 3; r < 10; r++) for (int c = 3; c < 10; c++) elevation[r][c] = 80;
        for (int r = 5; r < 8; r++) for (int c = 5; c < 8; c++) elevation[r][c] = 60;

        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, size, size),
                1, 30, 1000, 0.01f, 300);

        assertTrue(result.lakeMask[5][5]);
        short surface = result.waterSurface[5][5];
        assertNotEquals(Short.MIN_VALUE, surface);
        assertEquals(surface, result.waterSurface[4][4]);
        assertEquals(HydrologyProcessor.RIVER, result.biomeIds[5][5]);
        assertNotEquals(5, result.biomeIds[2][5], "lake shores should not remain desert");
    }

    @Test
    void lakeDeeperThanThreeHundredMetersIsRejected() {
        int size = 11;
        short[][] elevation = filled(size, size, (short) 500);
        short[][] biome = filled(size, size, (short) 1);
        for (int r = 2; r < 9; r++) for (int c = 2; c < 9; c++) elevation[r][c] = 100;
        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, size, size),
                1, 30, 1000, 0.01f, 300);
        for (boolean[] row : result.lakeMask) for (boolean cell : row) assertFalse(cell);
    }

    @Test
    void glacierValleysProduceMergingRivers() {
        int h = 64, w = 48;
        short[][] elevation = new short[h][w];
        short[][] biome = filled(h, w, (short) 8);
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            elevation[r][c] = (short) (2500 - r * 35 + Math.abs(c - w / 2) * 4);
            if (r < 12) biome[r][c] = 33;
        }
        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, w, h),
                2, 30, 0.03f, 0.01f, 300);
        int rivers = 0;
        for (short[] row : result.biomeIds) for (short cell : row) {
            if (cell == HydrologyProcessor.RIVER || cell == HydrologyProcessor.FROZEN_RIVER) rivers++;
        }
        assertTrue(rivers > 10, "expected a connected glacier-fed drainage network");
    }

    @Test
    void riverColumnsHaveLevelCrossSectionsAndSubmergedBeds() {
        int h = 72, w = 41;
        short[][] elevation = new short[h][w];
        short[][] biome = filled(h, w, (short) 8);
        int center = w / 2;
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int valley = Math.abs(c - center);
            elevation[r][c] = (short) (1800 - r * 5 + valley * valley * 4);
        }

        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, w, h),
                4, 15, 0.02f, 0.01f, 300);

        int wetRows = 0;
        for (int r = 4; r < result.height - 4; r++) {
            Short rowSurface = null;
            int wetColumns = 0;
            for (int c = 0; c < result.width; c++) {
                short surface = result.waterSurface[r][c];
                if (surface == Short.MIN_VALUE || result.lakeMask[r][c]) continue;
                wetColumns++;
                assertTrue(result.heightmap[r][c] < surface,
                        "every river column must have a bed below its water surface");
                if (rowSurface == null) rowSurface = surface;
                else assertEquals(rowSurface.shortValue(), surface,
                        "a straight river cross-section should have one coherent surface");
            }
            if (wetColumns >= 2) wetRows++;
        }
        assertTrue(wetRows > 8, "expected a substantial river through the test valley");
    }

    @Test
    void dryRiverBanksAreNotCutIntoArtificialContourBands() {
        int h = 72, w = 41;
        short[][] elevation = new short[h][w];
        short[][] original = new short[h][w];
        short[][] biome = filled(h, w, BiomeClassifier.FOREST);
        int center = w / 2;
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int valley = Math.abs(c - center);
            elevation[r][c] = (short) (1800 - r * 5 + valley * valley * 4);
            original[r][c] = elevation[r][c];
        }

        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, w, h),
                4, 15, 0.02f, 0.01f, 300);

        int dryBanksChecked = 0;
        for (int r = 0; r < result.height; r++) for (int c = 0; c < result.width; c++) {
            if (result.waterSurface[r][c] != Short.MIN_VALUE) continue;
            // Result coordinates are cropped by four cells on every side.
            assertEquals(original[r + 4][c + 4], result.heightmap[r][c],
                    "dry terrain must not become a geometric river-outline terrace");
            dryBanksChecked++;
        }
        assertTrue(dryBanksChecked > result.width * result.height / 2);
    }

    @Test
    void tinyDepressionsDoNotCoverLowlandsInPuddles() {
        int size = 31;
        short[][] elevation = filled(size, size, (short) 120);
        short[][] biome = filled(size, size, (short) 17);
        for (int r = 4; r < size - 4; r += 5) {
            for (int c = 4; c < size - 4; c += 5) elevation[r][c] = -10;
        }

        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, size, size),
                2, 15, 1000, 0.05f, 300);

        for (int r = 0; r < result.height; r++) for (int c = 0; c < result.width; c++) {
            assertFalse(result.lakeMask[r][c], "one-cell DEM pits must not become lakes");
            assertEquals(Short.MIN_VALUE, result.waterSurface[r][c],
                    "filtered pits must remain dry");
        }
    }

    @Test
    void productionThresholdMakesFewWideMainChannels() {
        int h = 224, w = 112, center = w / 2;
        short[][] elevation = new short[h][w];
        short[][] biome = filled(h, w, (short) 8);
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int valley = Math.abs(c - center);
            elevation[r][c] = (short) (2600 - r * 7 + valley * valley * 2);
        }

        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, w, h),
                8, 30, 8.0f, 0.12f, 300);

        int wet = 0, widestRow = 0;
        int[] wetPerRow = new int[result.height];
        int firstWetRow = -1, lastWetRow = -1;
        for (int r = 0; r < result.height; r++) {
            int rowWet = 0;
            for (int c = 0; c < result.width; c++) {
                if (result.waterSurface[r][c] != Short.MIN_VALUE && !result.lakeMask[r][c]) {
                    wet++;
                    rowWet++;
                }
            }
            wetPerRow[r] = rowWet;
            if (rowWet > 0) {
                if (firstWetRow < 0) firstWetRow = r;
                lastWetRow = r;
            }
            widestRow = Math.max(widestRow, rowWet);
        }
        assertTrue(wet > 0, "the main basin should still produce a river");
        assertTrue(widestRow >= 11,
                "retained scale-1 rivers should have a broad readable wetted width");
        for (int r = firstWetRow; r <= lastWetRow; r++) {
            assertTrue(wetPerRow[r] >= 5,
                    "the downstream river core must not disappear at row " + r);
        }
        assertEquals(1, countRiverComponents(result),
                "a single-valley river must remain one connected water body");
        assertTrue(wet < result.width * result.height / 8,
                "rivers should not dominate the whole landscape");
    }

    @Test
    void ordinaryRiverWidensWithoutBecomingAGreatRiver() {
        int h = 180, w = 96, center = w / 2;
        short[][] elevation = new short[h][w];
        short[][] biome = filled(h, w, BiomeClassifier.FOREST);
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int valley = Math.abs(c - center);
            elevation[r][c] = (short) (1700 - r * 5 + valley * valley * 2);
        }

        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, w, h),
                8, 30, 3.0f, 0.03f, 300f, -8, -8, 8291L);

        int maximumWidth = 0;
        boolean greatRiver = false;
        for (int r = 0; r < result.height; r++) for (int c = 0; c < result.width; c++) {
            maximumWidth = Math.max(maximumWidth,
                    Byte.toUnsignedInt(result.riverWidth[r][c]));
            greatRiver |= (result.landforms[r][c] & TerrainMetadata.GREAT_RIVER) != 0;
        }
        assertTrue(maximumWidth >= 12,
                "ordinary river should no longer look like a narrow stream");
        assertTrue(maximumWidth <= 32,
                "ordinary river must respect its separate width cap");
        assertFalse(greatRiver,
                "an inland ordinary valley must not be promoted to a great river");
    }

    @Test
    void processorIsDeterministicUnderParallelLoad() throws Exception {
        int h = 96, w = 96;
        LocalTerrainProvider.HeightmapData input = syntheticTerrain(h, w);
        LocalTerrainProvider.HeightmapData expected = HydrologyProcessor.process(input, 8, 30, 0.1f, 0.02f, 300);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<LocalTerrainProvider.HeightmapData>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(executor.submit(() -> HydrologyProcessor.process(input, 8, 30, 0.1f, 0.02f, 300)));
            }
            for (Future<LocalTerrainProvider.HeightmapData> future : futures) {
                LocalTerrainProvider.HeightmapData actual = future.get();
                assertTrue(Arrays.deepEquals(expected.heightmap, actual.heightmap));
                assertTrue(Arrays.deepEquals(expected.biomeIds, actual.biomeIds));
                assertTrue(Arrays.deepEquals(expected.waterSurface, actual.waterSurface));
                assertTrue(Arrays.deepEquals(expected.lakeMask, actual.lakeMask));
                assertTrue(Arrays.deepEquals(expected.landforms, actual.landforms));
                assertTrue(Arrays.deepEquals(expected.riverWidth, actual.riverWidth));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void majorValleyGetsWidthAndFloodplainMetadata() {
        int h = 160, w = 80, center = w / 2;
        short[][] elevation = new short[h][w];
        short[][] biome = filled(h, w, BiomeClassifier.PLAINS);
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int valley = Math.abs(c - center);
            elevation[r][c] = (short) (1600 - r * 2 + valley * valley);
        }
        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, w, h),
                8, 15, 0.15f, 0.03f, 300, -8, -8, 991L);
        boolean hasWideRiver = false, hasFloodplain = false;
        for (int r = 0; r < result.height; r++) for (int c = 0; c < result.width; c++) {
            hasWideRiver |= Byte.toUnsignedInt(result.riverWidth[r][c]) >= 3;
            hasFloodplain |= (result.landforms[r][c] & TerrainMetadata.FLOODPLAIN) != 0;
        }
        assertTrue(hasWideRiver);
        assertTrue(hasFloodplain);
    }

    @Test
    void continentalOceanBoundBasinCreatesRareGreatRiver() {
        int h = 280, w = 160, center = w / 2;
        short[][] elevation = new short[h][w];
        short[][] biome = filled(h, w, BiomeClassifier.FOREST);
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int valley = Math.abs(c - center);
            elevation[r][c] = (short) (900 - r * 5 + valley * valley / 3);
            if (elevation[r][c] <= 0) biome[r][c] = BiomeClassifier.OCEAN;
        }

        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, w, h),
                16, 15, 0.35f, 0.03f, 300f, -16, -16, 44891L);

        int greatRiverColumns = 0;
        int maximumWidth = 0;
        for (int r = 0; r < result.height; r++) for (int c = 0; c < result.width; c++) {
            if ((result.landforms[r][c] & TerrainMetadata.GREAT_RIVER) != 0) {
                greatRiverColumns++;
                assertNotEquals(Short.MIN_VALUE, result.waterSurface[r][c]);
                maximumWidth = Math.max(maximumWidth,
                        Byte.toUnsignedInt(result.riverWidth[r][c]));
            }
        }

        assertTrue(greatRiverColumns > 200,
                "a continental outlet should contain a substantial great-river reach");
        assertTrue(maximumWidth >= 64,
                "great river should reach its configured navigable width");
        assertTrue(maximumWidth <= 120,
                "great river width must remain within the padded context");
    }

    @Test
    void greatRiverPromotionRequiresAnOceanBoundBasin() {
        assertEquals(0f, HydrologyProcessor.greatRiverBlend(500f, 8f, false));
        assertEquals(0f, HydrologyProcessor.greatRiverBlend(8f, 8f, true));
        assertTrue(HydrologyProcessor.greatRiverBlend(96f, 8f, true) > 0.99f);
    }

    @Test
    void isolatedSnowBiomePixelsDoNotCreateCheckerboardIce() {
        int size = 31;
        short[][] elevation = filled(size, size, (short) 160);
        short[][] biome = filled(size, size, BiomeClassifier.FOREST);
        for (int r = 5; r < 26; r++) for (int c = 5; c < 26; c++) elevation[r][c] = 110;
        for (int r = 8; r < 23; r += 4) for (int c = 8; c < 23; c += 4) {
            biome[r][c] = BiomeClassifier.SNOWY_PLAINS;
        }
        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, size, size),
                2, 15, 1000f, 0.02f, 300f);
        for (int r = 0; r < result.height; r++) for (int c = 0; c < result.width; c++) {
            if (result.waterSurface[r][c] != Short.MIN_VALUE) {
                assertEquals(HydrologyProcessor.RIVER, result.biomeIds[r][c]);
            }
        }
    }

    @Test
    void uniformlyColdLakeFreezesAsOneBody() {
        int size = 25;
        short[][] elevation = filled(size, size, (short) 180);
        short[][] biome = filled(size, size, BiomeClassifier.SNOWY_PLAINS);
        for (int r = 4; r < 21; r++) for (int c = 4; c < 21; c++) elevation[r][c] = 100;
        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, size, size),
                2, 15, 1000f, 0.02f, 300f);
        boolean found = false;
        for (int r = 0; r < result.height; r++) for (int c = 0; c < result.width; c++) {
            if (result.lakeMask[r][c]) {
                found = true;
                assertEquals(HydrologyProcessor.FROZEN_RIVER, result.biomeIds[r][c]);
            }
        }
        assertTrue(found);
    }

    @Test
    void oceanBoundRiverReachesSeaLevelThroughOpenMouth() {
        int h = 90, w = 51, center = w / 2;
        short[][] elevation = new short[h][w];
        short[][] biome = filled(h, w, BiomeClassifier.FOREST);
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int valley = Math.abs(c - center);
            elevation[r][c] = (short) (360 - r * 6 + valley * valley * 2);
            if (elevation[r][c] < 0) biome[r][c] = BiomeClassifier.OCEAN;
        }
        LocalTerrainProvider.HeightmapData result = HydrologyProcessor.process(
                new LocalTerrainProvider.HeightmapData(elevation, biome, w, h),
                4, 15, 0.08f, 0.03f, 300f, -4, -4, 71L);
        boolean foundSeaLevelMouth = false;
        for (int r = 1; r < result.height - 1; r++) for (int c = 1; c < result.width - 1; c++) {
            if (result.waterSurface[r][c] == 0 && result.heightmap[r][c] < 0) {
                foundSeaLevelMouth = true;
            }
            if (result.waterSurface[r][c] != Short.MIN_VALUE && !result.lakeMask[r][c]) {
                assertTrue(result.waterSurface[r][c] >= 0,
                        "an ocean-draining river must not run below sea level at "
                                + r + "," + c + ": " + result.waterSurface[r][c]);
            }
        }
        assertTrue(foundSeaLevelMouth, "the river should carve an open estuary at sea level");
    }

    static LocalTerrainProvider.HeightmapData syntheticTerrain(int h, int w) {
        short[][] elevation = new short[h][w];
        short[][] biome = new short[h][w];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            double ridge = Math.sin(c * 0.11) * 90 + Math.cos(r * 0.07) * 55;
            elevation[r][c] = (short) Math.max(20, 2400 - r * 18 + ridge);
            biome[r][c] = r < h / 5 ? (short) 33 : c < w / 4 ? (short) 5 : (short) 8;
        }
        return new LocalTerrainProvider.HeightmapData(elevation, biome, w, h);
    }

    private static short[][] filled(int h, int w, short value) {
        short[][] result = new short[h][w];
        for (short[] row : result) Arrays.fill(row, value);
        return result;
    }

    private static int countRiverComponents(LocalTerrainProvider.HeightmapData data) {
        boolean[][] visited = new boolean[data.height][data.width];
        int[] queue = new int[data.height * data.width];
        int components = 0;
        for (int startR = 0; startR < data.height; startR++) {
            for (int startC = 0; startC < data.width; startC++) {
                if (visited[startR][startC]
                        || data.waterSurface[startR][startC] == Short.MIN_VALUE
                        || data.lakeMask[startR][startC]) continue;
                components++;
                int head = 0, tail = 0;
                queue[tail++] = startR * data.width + startC;
                visited[startR][startC] = true;
                while (head < tail) {
                    int index = queue[head++];
                    int r = index / data.width, c = index % data.width;
                    for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                        if (dr == 0 && dc == 0) continue;
                        int nr = r + dr, nc = c + dc;
                        if (nr < 0 || nr >= data.height || nc < 0 || nc >= data.width
                                || visited[nr][nc]
                                || data.waterSurface[nr][nc] == Short.MIN_VALUE
                                || data.lakeMask[nr][nc]) continue;
                        visited[nr][nc] = true;
                        queue[tail++] = nr * data.width + nc;
                    }
                }
            }
        }
        return components;
    }
}
