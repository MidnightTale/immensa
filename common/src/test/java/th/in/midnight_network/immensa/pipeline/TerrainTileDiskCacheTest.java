package th.in.midnight_network.immensa.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TerrainTileDiskCacheTest {
    @TempDir Path cacheRoot;

    @Test
    void roundTripsEveryTerrainChannelAndSeparatesSeeds() {
        int h = 7, w = 9;
        short[][] elevation = new short[h][w];
        short[][] biomes = new short[h][w];
        short[][] water = new short[h][w];
        boolean[][] lakes = new boolean[h][w];
        short[][] landforms = new short[h][w];
        byte[][] geology = new byte[h][w];
        byte[][] soil = new byte[h][w];
        byte[][] widths = new byte[h][w];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            elevation[r][c] = (short) (r * 100 + c);
            biomes[r][c] = (short) (r + c);
            water[r][c] = c == 4 ? (short) 82 : Short.MIN_VALUE;
            lakes[r][c] = r == 3 && c == 4;
            landforms[r][c] = (short) (r == 3 ? TerrainMetadata.FLOODPLAIN : 0);
            geology[r][c] = (byte) (c % 5);
            soil[r][c] = (byte) (r % 9);
            widths[r][c] = (byte) (c == 4 ? 7 : 0);
        }
        LocalTerrainProvider.HeightmapData input = new LocalTerrainProvider.HeightmapData(
                elevation, biomes, water, lakes, landforms, geology, soil, widths, w, h);
        TerrainTileDiskCache.store(cacheRoot, 44L, 17, 0, 0, h, w, input);

        LocalTerrainProvider.HeightmapData output = TerrainTileDiskCache.load(
                cacheRoot, 44L, 17, 0, 0, h, w);
        assertNotNull(output);
        assertTrue(Arrays.deepEquals(input.heightmap, output.heightmap));
        assertTrue(Arrays.deepEquals(input.biomeIds, output.biomeIds));
        assertTrue(Arrays.deepEquals(input.waterSurface, output.waterSurface));
        assertTrue(Arrays.deepEquals(input.lakeMask, output.lakeMask));
        assertTrue(Arrays.deepEquals(input.landforms, output.landforms));
        assertTrue(Arrays.deepEquals(input.geology, output.geology));
        assertTrue(Arrays.deepEquals(input.soilDepth, output.soilDepth));
        assertTrue(Arrays.deepEquals(input.riverWidth, output.riverWidth));
        assertNull(TerrainTileDiskCache.load(cacheRoot, 45L, 17, 0, 0, h, w));
    }
}
