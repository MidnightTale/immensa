package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmensaCaveSamplerTest {
    private static final long SEED = 0x5eedc0de1234L;

    @Test
    void protectsSurfaceAndBedrockOutsideIntentionalEntrances() {
        int lowSurface = 64; // Surface entrances are deliberately disabled below y=68.
        for (int x = -128; x <= 128; x += 8) {
            for (int z = -128; z <= 128; z += 8) {
                for (int depth = 0; depth < 8; depth++) {
                    assertEquals(depth,
                            ImmensaCaveSampler.apply(depth, x, lowSurface - depth, z,
                                    lowSurface, SEED));
                }
                assertEquals(120.0,
                        ImmensaCaveSampler.apply(120.0, x,
                                ImmensaCaveSampler.BEDROCK_PROTECTION_Y, z,
                                lowSurface, SEED));
            }
        }
    }

    @Test
    void surfaceShellVariesInsteadOfCreatingAFlatCeiling() {
        int surfaceY = 128;
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int x = -768; x <= 768; x += 8) {
            for (int z = -768; z <= 768; z += 8) {
                double shell = ImmensaCaveSampler.surfaceShellDepth(
                        x, z, 33.0, SEED);
                minimum = Math.min(minimum, shell);
                maximum = Math.max(maximum, shell);
            }
        }
        assertTrue(maximum - minimum >= 12.0,
                "the cave roof must undulate rather than form one depth plane: "
                        + minimum + ".." + maximum);
        assertTrue(minimum >= 12.0,
                "ordinary cave noise still needs a protective cap: " + minimum);
    }

    @Test
    void rareElongatedCavesActuallyBreakTheSurface() {
        int surfaceY = 112;
        int openings = 0;
        int samples = 0;
        for (int x = -1024; x <= 1024; x += 4) {
            for (int z = -1024; z <= 1024; z += 4) {
                if (ImmensaCaveSampler.apply(
                        0.0, x, surfaceY, z, surfaceY, SEED) < 0.0) {
                    openings++;
                }
                samples++;
            }
        }
        double ratio = openings / (double) samples;
        assertTrue(openings >= 8,
                "some cave rifts must reach daylight: " + openings);
        assertTrue(ratio < 0.002,
                "surface cave breakthroughs must remain rare: " + ratio);
    }

    @Test
    void wormNetworksPeakInTheMiddleAndLowerUnderground() {
        int high = 0, middle = 0, deep = 0;
        for (int x = -512; x <= 512; x += 4) {
            for (int z = -512; z <= 512; z += 4) {
                if (ImmensaCaveSampler.tunnelDensityAt(
                        x, 104, z, 160, SEED) < 0.0) high++;
                if (ImmensaCaveSampler.tunnelDensityAt(
                        x, 4, z, 128, SEED) < 0.0) middle++;
                if (ImmensaCaveSampler.tunnelDensityAt(
                        x, -48, z, 128, SEED) < 0.0) deep++;
            }
        }
        assertTrue(middle > high * 2,
                "high elevations should have far fewer worm caves: "
                        + high + ", " + middle);
        assertTrue(deep > high,
                "lower terrain should remain more connected than high terrain: "
                        + high + ", " + deep);
        assertTrue(middle > deep,
                "worm density should taper again near bedrock: "
                        + middle + ", " + deep);
    }

    @Test
    void giantMountainsDoNotRepeatWormsThroughTheirUpperMass() {
        assertTrue(ImmensaCaveSampler.tunnelActivityAt(160, 620) < 0.02);
        assertEquals(0.0, ImmensaCaveSampler.tunnelActivityAt(300, 620));
        assertTrue(ImmensaCaveSampler.tunnelActivityAt(104, 620)
                        < ImmensaCaveSampler.tunnelActivityAt(104, 180) * 0.40,
                "a 500+ block mountain needs much stronger upper tunnel suppression");
        assertTrue(ImmensaCaveSampler.tunnelActivityAt(32, 620) > 0.90,
                "the lower mountain should retain useful connected caves");

        int upperWorms = 0;
        for (int x = -512; x <= 512; x += 8) {
            for (int z = -512; z <= 512; z += 8) {
                if (ImmensaCaveSampler.tunnelDensityAt(
                        x, 176, z, 620, SEED) < 0.0) upperWorms++;
            }
        }
        assertTrue(upperWorms < 12,
                "upper giant mountains should contain only rare ascending worms: "
                        + upperWorms);
    }

    @Test
    void giantMountainsContainSparseLargeAlpineGalleries() {
        int open = 0;
        int broad = 0;
        int samples = 0;
        for (int y = 96; y <= 360; y += 8) {
            for (int x = -1024; x <= 1024; x += 16) {
                for (int z = -1024; z <= 1024; z += 16) {
                    double density = ImmensaCaveSampler.mountainGalleryDensityAt(
                            x, y, z, 620, SEED);
                    if (density < 0.0) open++;
                    if (density < -8.0) broad++;
                    samples++;
                }
            }
        }
        double ratio = open / (double) samples;
        assertTrue(ratio > 0.004,
                "large mountains need discoverable internal galleries: " + ratio);
        assertTrue(ratio < 0.075,
                "alpine galleries must stay sparse, not hollow the mountain: " + ratio);
        assertTrue(broad > 120,
                "mountain systems need real chambers, not only thin passages: " + broad);

        assertEquals(Double.POSITIVE_INFINITY,
                ImmensaCaveSampler.mountainGalleryDensityAt(
                        0, 150, 0, 220, SEED));
        assertEquals(Double.POSITIVE_INFINITY,
                ImmensaCaveSampler.mountainGalleryDensityAt(
                        0, 592, 0, 620, SEED));
    }

    @Test
    void tunnelProvincesAreSparseAndPassageWidthsVary() {
        int open = 0;
        int broad = 0;
        int samples = 0;
        for (int x = -1536; x <= 1536; x += 4) {
            for (int z = -1536; z <= 1536; z += 4) {
                double density = ImmensaCaveSampler.tunnelDensityAt(
                        x, 8, z, 144, SEED);
                if (density < 0.0) open++;
                if (density < -2.0) broad++;
                samples++;
            }
        }
        double ratio = open / (double) samples;
        assertTrue(ratio > 0.002,
                "regional tunnels must remain discoverable: " + ratio);
        assertTrue(ratio < 0.055,
                "worm caves should occupy sparse provinces, not the whole underground: "
                        + ratio);
        assertTrue(broad > 40 && broad < open,
                "networks need both narrow passages and occasional broad trunks: "
                        + broad + "/" + open);
    }

    @Test
    void deepCaveFloorVariesBeforeTheBedrockBand() {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        boolean[] roundedLevels = new boolean[32];
        for (int x = -512; x <= 512; x += 8) {
            for (int z = -512; z <= 512; z += 8) {
                double floor = ImmensaCaveSampler.bottomFloorY(x, z, SEED);
                min = Math.min(min, floor);
                max = Math.max(max, floor);
                int level = Math.max(0, Math.min(roundedLevels.length - 1,
                        (int) Math.round(floor) + 64));
                roundedLevels[level] = true;
            }
        }
        int distinctLevels = 0;
        for (boolean present : roundedLevels) if (present) distinctLevels++;
        assertTrue(max - min > 6.0,
                "the bottom transition must not collapse to a flat plane");
        assertTrue(distinctLevels >= 7,
                "the deep floor should occupy many Y levels: " + distinctLevels);
        assertTrue(min > -61.5 && max < -44.0,
                "the floor must blend above bedrock without becoming shallow");
    }

    @Test
    void createsCavesWithoutHollowingMostTerrain() {
        int surfaceY = 128;
        int caveSamples = 0;
        int totalSamples = 0;
        int widestSlice = 0;
        for (int y = -48; y <= 108; y += 4) {
            int sliceCaves = 0;
            for (int x = 0; x < 512; x += 4) {
                for (int z = 0; z < 512; z += 4) {
                    double density = ImmensaCaveSampler.apply(
                            surfaceY - y, x, y, z, surfaceY, SEED);
                    if (density < 0.0) {
                        caveSamples++;
                        sliceCaves++;
                    }
                    totalSamples++;
                }
            }
            widestSlice = Math.max(widestSlice, sliceCaves);
        }

        double caveRatio = caveSamples / (double) totalSamples;
        assertTrue(caveRatio > 0.005, "Caves should be discoverable: " + caveRatio);
        assertTrue(caveRatio < 0.28, "Caves should not perforate most terrain: " + caveRatio);
        assertTrue(widestSlice > 500,
                "At least one cathedral-scale cavern slice should exist: " + widestSlice);
    }

    @Test
    void massiveChambersContainSparseContinuousRockFormations() {
        int solid = 0;
        int samples = 0;
        int continuousColumns = 0;
        int surfaceY = 144;
        for (int z = -256; z < 256; z += 2) {
            for (int x = -256; x < 256; x += 2) {
                double middle = ImmensaCaveSampler.formationSolidDensity(
                        x, 34, z, surfaceY, SEED);
                if (middle > 0.0) solid++;
                if (middle > 0.0
                        && ImmensaCaveSampler.formationSolidDensity(
                                x, -18, z, surfaceY, SEED) > 0.0
                        && ImmensaCaveSampler.formationSolidDensity(
                                x, 86, z, surfaceY, SEED) > 0.0) {
                    continuousColumns++;
                }
                assertEquals(middle,
                        ImmensaCaveSampler.formationSolidDensity(
                                x, 34, z, surfaceY, SEED));
                samples++;
            }
        }

        double ratio = solid / (double) samples;
        assertTrue(ratio > 0.008,
                "large caves need visible pillars and arches: " + ratio);
        assertTrue(ratio < 0.09,
                "formations must not refill the massive open space: " + ratio);
        assertTrue(continuousColumns > 80,
                "some rock columns must connect floor-height to ceiling-height: "
                        + continuousColumns);
    }

    @Test
    void deepLavaNetworkIsHugeConnectedAndVerticallyOpen() {
        int step = 16;
        int side = 257;
        int origin = -2048;
        boolean[] open = new boolean[side * side];
        int openCount = 0;
        double deepestDensity = Double.POSITIVE_INFINITY;
        int deepestX = 0, deepestZ = 0;
        for (int row = 0; row < side; row++) for (int column = 0; column < side; column++) {
            int x = origin + column * step;
            int z = origin + row * step;
            double density = ImmensaCaveSampler.deepLavaNetworkDensity(
                    x, -34, z, SEED);
            if (density < 0.0) {
                open[row * side + column] = true;
                openCount++;
            }
            if (density < deepestDensity) {
                deepestDensity = density;
                deepestX = x;
                deepestZ = z;
            }
        }

        Component largest = largestComponent(open, side, step);
        assertTrue(openCount > 1_000, "deep provinces must be discoverable: " + openCount);
        assertTrue(openCount < open.length * 0.30,
                "deep networks must not hollow the whole world: " + openCount);
        assertTrue(largest.blocks() > 650,
                "the largest deep network must be a substantial connected space: " + largest);
        assertTrue(Math.max(largest.width(), largest.depth()) >= 1_800,
                "a lava-tunnel network must run for well over a kilometre: " + largest);

        int openVertical = 0;
        for (int y = -56; y <= 18; y++) {
            if (ImmensaCaveSampler.deepLavaNetworkDensity(
                    deepestX, y, deepestZ, SEED) < 0.0) {
                openVertical++;
            }
        }
        assertTrue(openVertical >= 48,
                "the main vault must have cathedral-scale vertical clearance: " + openVertical);
    }

    @Test
    void massiveLavaBasinsShareOneLevelInsideTheNetworkOnly() {
        int lavaColumns = 0;
        int minSurface = Integer.MAX_VALUE;
        int maxSurface = Integer.MIN_VALUE;
        int samples = 0;
        for (int z = -1536; z <= 1536; z += 16) {
            for (int x = -1536; x <= 1536; x += 16) {
                int surface = ImmensaCaveSampler.deepLavaSurfaceY(x, z, SEED);
                minSurface = Math.min(minSurface, surface);
                maxSurface = Math.max(maxSurface, surface);
                if (ImmensaCaveSampler.isDeepLavaInterior(
                        x, surface, z, SEED)) {
                    lavaColumns++;
                }
                samples++;
            }
        }
        double ratio = lavaColumns / (double) samples;
        assertTrue(ratio > 0.01, "massive lava lakes should exist: " + ratio);
        assertTrue(ratio < 0.25, "lava must stay inside sparse deep provinces: " + ratio);
        assertEquals(-40, minSurface,
                "the connected lava network should stay safely above bedrock");
        assertEquals(minSurface, maxSurface,
                "connected lava arteries must share one equilibrium surface");
    }

    @Test
    void entrancesBreakThroughOnlySparseDownhillFaces() {
        int sideCarving = 0;
        int flatCarving = 0;
        int surfaceBreakthroughs = 0;
        int topCarving = 0;
        int samples = 0;
        double slopeX = 0.50;
        double slopeZ = 0.12;
        int entranceSurfaceY = 100;
        for (int x = -96; x <= 112; x += 2) {
            for (int z = -72; z <= 72; z += 2) {
                int surfaceY = entranceSurfaceY
                        + (int) Math.floor(x * slopeX + z * slopeZ);
                if (ImmensaCaveSampler.apply(
                        0.0, x, surfaceY, z, surfaceY, SEED,
                        0, 0, entranceSurfaceY, slopeX, slopeZ, 1.0) < 0.0) {
                    topCarving++;
                }
                if (ImmensaCaveSampler.apply(
                        2.0, x, surfaceY - 2, z, surfaceY, SEED,
                        0, 0, entranceSurfaceY, slopeX, slopeZ, 1.0) < 0.0) {
                    surfaceBreakthroughs++;
                }
                if (ImmensaCaveSampler.apply(
                        8.0, x, surfaceY - 8, z, surfaceY, SEED,
                        0, 0, entranceSurfaceY, slopeX, slopeZ, 1.0) < 0.0) {
                    sideCarving++;
                }
                if (ImmensaCaveSampler.apply(
                        8.0, x, surfaceY - 8, z, surfaceY, SEED,
                        0, 0, entranceSurfaceY, 0.0, 0.0, 1.0) < 0.0) {
                    flatCarving++;
                }
                samples++;
            }
        }
        assertEquals(0, topCarving, "The terrain's top density must remain intact");
        assertEquals(0, flatCarving, "Flat terrain must never receive a surface hole");
        assertTrue(surfaceBreakthroughs > 0,
                "Selected downhill faces must actually open to daylight");
        assertTrue(surfaceBreakthroughs / (double) samples < 0.01,
                "Daylight breakthroughs must remain rare");
        assertTrue(sideCarving > 40, "Steep terrain should contain visible side mouths");
        assertTrue(sideCarving / (double) samples < 0.12,
                "Hillside openings must remain sparse");
    }

    @Test
    void terrainTileSelectionFindsAndOpensItsSteepestHillside() {
        int size = 256;
        short[][] elevation = new short[size][size];
        short[][] biomes = new short[size][size];
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            elevation[z][x] = (short) (900 + x * 12 + z * 3);
            biomes[z][x] = 8;
        }
        LocalTerrainProvider.HeightmapData data =
                new LocalTerrainProvider.HeightmapData(elevation, biomes, size, size);

        ImmensaEntranceSelector.Site site = ImmensaEntranceSelector.Site.NONE;
        long selectedSeed = 0;
        for (long seed = 1; seed < 64 && site.surfaceY() == 0; seed++) {
            site = ImmensaEntranceSelector.find(data, 0, 0, seed,
                    meters -> 63 + meters / 30);
            selectedSeed = seed;
        }
        assertTrue(site.surfaceY() > 0, "a selected sloped tile must produce an entrance site");
        assertTrue(Math.hypot(site.slopeX(), site.slopeZ()) >= 0.10,
                "the chosen site must have a real downhill direction");
        assertTrue(site.reach() >= 96.0,
                "an entrance needs enough contained distance to form a natural descent");
        double magnitude = Math.hypot(site.slopeX(), site.slopeZ());
        double endpointX = site.x() + site.slopeX() / magnitude * site.reach();
        double endpointZ = site.z() + site.slopeZ() / magnitude * site.reach();
        assertTrue(endpointX >= 26.0 && endpointX <= size - 27.0
                        && endpointZ >= 26.0 && endpointZ <= size - 27.0,
                "the entrance field must close before a terrain-tile seam: "
                        + endpointX + ", " + endpointZ);

        int daylightBlocks = 0;
        for (int z = Math.max(0, site.z() - 70); z <= Math.min(size - 1, site.z() + 70); z++) {
            for (int x = Math.max(0, site.x() - 70); x <= Math.min(size - 1, site.x() + 70); x++) {
                int surfaceY = 63 + elevation[z][x] / 30;
                if (ImmensaCaveSampler.apply(
                        2.0, x, surfaceY - 2, z, surfaceY, selectedSeed,
                        site.x(), site.z(), site.surfaceY(), site.slopeX(), site.slopeZ(),
                        site.scale()) < 0.0) {
                    daylightBlocks++;
                }
            }
        }
        assertTrue(daylightBlocks >= 8,
                "the selected hillside must actually break through to daylight: " + daylightBlocks);
    }

    @Test
    void isDeterministicAndSafeToSampleInParallel() {
        double[] expected = IntStream.range(0, 20_000)
                .mapToDouble(ImmensaCaveSamplerTest::samplePoint)
                .toArray();
        double[] parallel = IntStream.range(0, 20_000)
                .parallel()
                .mapToDouble(ImmensaCaveSamplerTest::samplePoint)
                .toArray();
        assertArrayEquals(expected, parallel);

        double seedA = ImmensaCaveSampler.apply(80, 117, 20, -93, 100, SEED);
        double seedB = ImmensaCaveSampler.apply(80, 117, 20, -93, 100, SEED + 1);
        assertNotEquals(seedA, seedB);
    }

    private static double samplePoint(int index) {
        int x = (index * 73) & 511;
        int z = (index * 151) & 511;
        int y = -48 + ((index * 29) & 127);
        return ImmensaCaveSampler.apply(128 - y, x, y, z, 128, SEED);
    }

    private static Component largestComponent(boolean[] cells, int side, int step) {
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
                    (maxColumn - minColumn) * step,
                    (maxRow - minRow) * step);
            if (current.blocks() > largest.blocks()) largest = current;
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

    private record Component(int blocks, int width, int depth) {
    }
}
