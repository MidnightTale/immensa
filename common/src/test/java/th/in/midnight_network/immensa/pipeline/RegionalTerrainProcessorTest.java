package th.in.midnight_network.immensa.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RegionalTerrainProcessorTest {
    @Test
    void regionalPassIsDeterministicAndProducesPhysicalMetadata() {
        int h = 96, w = 96, n = h * w;
        float[] elevation = new float[n];
        float[] climate = new float[n * 4];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int i = r * w + c;
            elevation[i] = c < 12 ? -80f : 100f + r * 4f + (float) Math.sin(c * 0.15) * 45f;
            climate[i] = 24f;
            climate[n + i] = 7f;
            climate[2 * n + i] = 900f;
            climate[3 * n + i] = 5f;
        }

        RegionalTerrainProcessor.Result first = RegionalTerrainProcessor.process(
                elevation, climate, -48, -48, h, w, 30f, 812731L);
        RegionalTerrainProcessor.Result second = RegionalTerrainProcessor.process(
                elevation, climate, -48, -48, h, w, 30f, 812731L);

        assertArrayEquals(first.elevation(), second.elevation());
        assertArrayEquals(first.climate(), second.climate());
        assertArrayEquals(first.geology(), second.geology());
        assertFalse(Arrays.equals(elevation, first.elevation()), "erosion and regional relief must alter the raw DEM");
        assertTrue(Arrays.stream(toInts(first.landforms()))
                .anyMatch(flags -> (flags & TerrainMetadata.COAST) != 0));
        assertTrue(Arrays.stream(toInts(first.landforms()))
                .anyMatch(flags -> (flags & TerrainMetadata.STRUCTURE_SUITABLE) != 0));
    }

    @Test
    void orogenyProducesSparseMassiveConnectedMountainBelts() {
        int side = 384;
        int count = side * side;
        float[] plateau = new float[count];
        Arrays.fill(plateau, 700f);

        RegionalTerrainProcessor.Result terrain = RegionalTerrainProcessor.process(
                plateau, null, -side / 2, -side / 2,
                side, side, 500f, 812731L);

        boolean[] alpine = new boolean[count];
        int alpineCount = 0;
        float maximum = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            maximum = Math.max(maximum, terrain.elevation()[i]);
            if (terrain.elevation()[i] >= 2_200f) {
                alpine[i] = true;
                alpineCount++;
            }
        }
        Component largest = largestComponent(alpine, side);
        assertTrue(maximum >= 3_500f,
                "tectonic cores should raise genuinely massive peaks: " + maximum);
        assertTrue(alpineCount > 500 && alpineCount < count * 0.30,
                "alpine terrain should be substantial but sparse: " + alpineCount);
        assertTrue(largest.cells > 350,
                "mountain belts should be connected, not isolated peak dots: " + largest);
        assertTrue(Math.max(largest.width, largest.height) * 500 >= 24_000,
                "the main range should continue for tens of kilometres: " + largest);
    }

    @Test
    void rareCollisionCoresBuildAggressiveMultiSummitMassifs() {
        int side = 384;
        int count = side * side;
        float[] plateau = new float[count];
        Arrays.fill(plateau, 700f);

        float[] elevation = RegionalTerrainProcessor.process(
                plateau, null, -side / 2, -side / 2,
                side, side, 500f, 812731L).elevation();

        float maximum = Float.NEGATIVE_INFINITY;
        int summit = -1;
        int extremeCount = 0;
        for (int i = 0; i < count; i++) {
            if (elevation[i] > maximum) {
                maximum = elevation[i];
                summit = i;
            }
            if (elevation[i] >= 6_000f) extremeCount++;
        }

        int summitRow = summit / side;
        int summitColumn = summit % side;
        double shoulderTotal = 0.0;
        int shoulderSamples = 0;
        for (int dr = -12; dr <= 12; dr++) for (int dc = -12; dc <= 12; dc++) {
            if (Math.max(Math.abs(dr), Math.abs(dc)) < 7) continue;
            int row = summitRow + dr, column = summitColumn + dc;
            if (row < 0 || row >= side || column < 0 || column >= side) continue;
            shoulderTotal += elevation[row * side + column];
            shoulderSamples++;
        }
        double shoulderMean = shoulderTotal / shoulderSamples;

        assertTrue(maximum >= 10_000f,
                "strong collision cores should create enormous peaks: " + maximum);
        assertTrue(extremeCount > 12 && extremeCount < count * 0.025,
                "six-kilometre summits should be dramatic but rare: " + extremeCount);
        assertTrue(maximum - shoulderMean > 1_200.0,
                "the tallest peak needs aggressive prominence above broad foothills: "
                        + maximum + " vs " + shoulderMean);
    }

    @Test
    void escarpmentsCreateRareStackedWallsAndPreserveOpenPlateaus() {
        int side = 1024;
        float[] elevation = new float[side * side];
        int[] oceanDistance = new int[elevation.length];
        Arrays.fill(elevation, 620f);
        Arrays.fill(oceanDistance, 193);

        RegionalTerrainProcessor.addEscarpments(elevation, oceanDistance,
                -side / 2, -side / 2, side, side, 220f, 812731L);

        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        int walls = 0;
        int quiet = 0;
        for (int r = 1; r < side - 1; r++) for (int c = 1; c < side - 1; c++) {
            int i = r * side + c;
            minimum = Math.min(minimum, elevation[i]);
            maximum = Math.max(maximum, elevation[i]);
            float drop = Math.max(Math.abs(elevation[i] - elevation[i - 1]),
                    Math.abs(elevation[i] - elevation[i - side]));
            if (drop > 115f) walls++;
            if (drop < 12f) quiet++;
        }
        int interior = (side - 2) * (side - 2);
        assertTrue(maximum - minimum > 300f,
                "stacked fault walls need major vertical relief: " + minimum + ".." + maximum);
        assertTrue(walls > 80 && walls < interior * 0.035,
                "cliff walls should be dramatic, connected and rare: " + walls);
        assertTrue(quiet > interior * 0.72,
                "broad usable benches and plateaus must remain between walls: " + quiet);
    }

    @Test
    void rockyHeadlandsBecomeSeaCliffsWithoutDeletingGentleCoasts() {
        int h = 1024, w = 64, coastColumn = 10;
        float[] elevation = new float[h * w];
        int[] oceanDistance = new int[elevation.length];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int i = r * w + c;
            elevation[i] = c < coastColumn ? -260f : 320f;
            oceanDistance[i] = c < coastColumn ? 0 : c - coastColumn + 1;
        }

        RegionalTerrainProcessor.addEscarpments(elevation, oceanDistance,
                -h / 2, -w / 2, h, w, 220f, 812731L);

        int dramatic = 0;
        int gentle = 0;
        for (int r = 0; r < h; r++) {
            float uplift = elevation[r * w + coastColumn] - 320f;
            if (uplift > 260f) dramatic++;
            if (uplift < 45f) gentle++;
        }
        assertTrue(dramatic > h * 0.08,
                "some coherent rocky headlands should rise sharply from the sea: " + dramatic);
        assertTrue(gentle > h * 0.08,
                "beaches and low coasts must remain between cliff provinces: " + gentle);
    }

    @Test
    void rainShadowsAreIndependentOfGenerationTileOrigin() {
        int height = 256, width = 320, count = height * width;
        int firstOriginColumn = -160;
        int secondOriginColumn = -96;
        float[] firstElevation = new float[count];
        float[] secondElevation = new float[count];
        Arrays.fill(firstElevation, 700f);
        Arrays.fill(secondElevation, 700f);
        float[] firstClimate = climateFixture(count);
        float[] secondClimate = climateFixture(count);

        RegionalTerrainProcessor.Result first = RegionalTerrainProcessor.process(
                firstElevation, firstClimate, -128, firstOriginColumn,
                height, width, 15f, 812731L);
        RegionalTerrainProcessor.Result second = RegionalTerrainProcessor.process(
                secondElevation, secondClimate, -128, secondOriginColumn,
                height, width, 15f, 812731L);

        int globalColumn = 40;
        int firstColumn = globalColumn - firstOriginColumn;
        int secondColumn = globalColumn - secondOriginColumn;
        for (int row = 72; row <= 184; row += 8) {
            int firstIndex = row * width + firstColumn;
            int secondIndex = row * width + secondColumn;
            assertEquals(first.climate()[2 * count + firstIndex],
                    second.climate()[2 * count + secondIndex], 0.02f,
                    "precipitation must not jump at a 256-block terrain tile boundary");
        }
    }

    @Test
    void coastalReliefIsIndependentOfGenerationTileOrigin() {
        int height = 192, width = 512, count = height * width;
        int firstOriginColumn = 0;
        int secondOriginColumn = 256;
        int coastColumn = 560;
        float[] firstElevation = coastalFixture(height, width, firstOriginColumn, coastColumn);
        float[] secondElevation = coastalFixture(height, width, secondOriginColumn, coastColumn);

        RegionalTerrainProcessor.Result first = RegionalTerrainProcessor.process(
                firstElevation, null, -96, firstOriginColumn,
                height, width, 15f, 812731L);
        RegionalTerrainProcessor.Result second = RegionalTerrainProcessor.process(
                secondElevation, null, -96, secondOriginColumn,
                height, width, 15f, 812731L);

        // The coast is outside the first window but inside the second. These
        // shared columns are farther from it than the guaranteed context radius,
        // so neither tile may invent a different coastal escarpment there.
        for (int row = 24; row < height - 24; row += 6) {
            for (int globalColumn = 376; globalColumn <= 424; globalColumn += 4) {
                int firstIndex = row * width + globalColumn - firstOriginColumn;
                int secondIndex = row * width + globalColumn - secondOriginColumn;
                assertEquals(first.elevation()[firstIndex], second.elevation()[secondIndex], 0.05f,
                        "coastal relief must not jump at a terrain tile boundary");
            }
        }
    }

    private static float[] coastalFixture(int height, int width, int originColumn, int coastColumn) {
        float[] elevation = new float[height * width];
        for (int row = 0; row < height; row++) for (int column = 0; column < width; column++) {
            int globalColumn = originColumn + column;
            elevation[row * width + column] = globalColumn >= coastColumn ? -220f : 420f;
        }
        return elevation;
    }

    private static float[] climateFixture(int count) {
        float[] climate = new float[count * 4];
        for (int i = 0; i < count; i++) {
            climate[i] = 24f;
            climate[count + i] = 7f;
            climate[2 * count + i] = 900f;
            climate[3 * count + i] = 5f;
        }
        return climate;
    }

    private static Component largestComponent(boolean[] cells, int side) {
        boolean[] seen = new boolean[cells.length];
        int[] queue = new int[cells.length];
        Component largest = new Component(0, 0, 0);
        for (int start = 0; start < cells.length; start++) {
            if (!cells[start] || seen[start]) continue;
            int head = 0, tail = 0;
            int minRow = start / side, maxRow = minRow;
            int minColumn = start % side, maxColumn = minColumn;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int index = queue[head++];
                int row = index / side, column = index % side;
                minRow = Math.min(minRow, row); maxRow = Math.max(maxRow, row);
                minColumn = Math.min(minColumn, column); maxColumn = Math.max(maxColumn, column);
                if (row > 0) tail = enqueue(index - side, cells, seen, queue, tail);
                if (row + 1 < side) tail = enqueue(index + side, cells, seen, queue, tail);
                if (column > 0) tail = enqueue(index - 1, cells, seen, queue, tail);
                if (column + 1 < side) tail = enqueue(index + 1, cells, seen, queue, tail);
            }
            Component current = new Component(tail,
                    maxColumn - minColumn + 1, maxRow - minRow + 1);
            if (current.cells > largest.cells) largest = current;
        }
        return largest;
    }

    private static int enqueue(int index, boolean[] cells, boolean[] seen,
                               int[] queue, int tail) {
        if (cells[index] && !seen[index]) {
            seen[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    private record Component(int cells, int width, int height) {
    }

    private static int[] toInts(short[] values) {
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }
}
