package th.in.midnight_network.immensa.pipeline;

import java.util.Arrays;

/**
 * Deterministic regional pass layered over diffusion output.
 *
 * <p>It supplies the physical relationships a local image model cannot guarantee by itself:
 * coherent plate/ridge scales, thermal relaxation, maritime climate, prevailing-wind rain
 * shadows, coast type, geology, soil depth and settlement suitability. All noise is sampled
 * in physical metres, so changing Minecraft world scale does not move the generated systems.
 */
public final class RegionalTerrainProcessor {
    public record Result(float[] elevation, float[] climate, byte[] geology,
                         byte[] soilDepth, short[] landforms) {
    }

    private static final int[] DR4 = {-1, 1, 0, 0};
    private static final int[] DC4 = {0, 0, -1, 1};
    /**
     * Ocean influence must fit inside the 128-cell terrain-generation halo.
     * Keeping a small safety margin also covers the erosion/thermal stencils.
     */
    private static final int OCEAN_CONTEXT_RADIUS = 112;
    private static final int OCEAN_DISTANCE_UNKNOWN = 1_000_000;

    private RegionalTerrainProcessor() {
    }

    public static Result process(float[] sourceElevation, float[] sourceClimate,
                                 int originRow, int originColumn, int height, int width,
                                 float pixelSizeMeters, long seed) {
        int count = height * width;
        float[] elevation = sourceElevation.clone();
        float[] climate = sourceClimate == null ? null : sourceClimate.clone();
        byte[] geology = new byte[count];
        byte[] soilDepth = new byte[count];
        short[] landforms = new short[count];

        addMultiScaleRelief(elevation, originRow, originColumn, height, width, pixelSizeMeters, seed);
        int[] oceanDistance = distanceFromOcean(elevation, height, width);
        addEscarpments(elevation, oceanDistance, originRow, originColumn,
                height, width, pixelSizeMeters, seed);
        if (climate != null && climate.length >= count * 4) {
            simulateClimate(elevation, climate, oceanDistance, originRow, originColumn,
                    height, width, pixelSizeMeters, seed);
        }
        hydraulicErode(elevation, climate, height, width, pixelSizeMeters, 2);
        thermalRelax(elevation, height, width, pixelSizeMeters, 3);

        // Erosion changes altitude, slope and distance-to-sea. Re-evaluate climate
        // from the unmodified model channels rather than applying corrections twice.
        oceanDistance = distanceFromOcean(elevation, height, width);
        if (sourceClimate != null && sourceClimate.length >= count * 4) {
            climate = sourceClimate.clone();
            simulateClimate(elevation, climate, oceanDistance, originRow, originColumn,
                    height, width, pixelSizeMeters, seed);
        }
        classifyTerrain(elevation, climate, oceanDistance, geology, soilDepth, landforms,
                originRow, originColumn, height, width, pixelSizeMeters, seed);
        return new Result(elevation, climate, geology, soilDepth, landforms);
    }

    /**
     * Rainfall-weighted stream-power erosion with downstream sediment deposition.
     * The padded regional input lets flow cross the requested tile instead of
     * producing the familiar per-chunk erosion squares.
     */
    private static void hydraulicErode(float[] elevation, float[] climate,
                                       int h, int w, float pixelSize, int iterations) {
        int n = elevation.length;
        int[] order = new int[n];
        int[] downstream = new int[n];
        float[] runoff = new float[n];
        float[] delta = new float[n];
        for (int i = 0; i < n; i++) order[i] = i;

        for (int pass = 0; pass < iterations; pass++) {
            sortByElevationDescending(order, elevation, 0, n - 1);
            Arrays.fill(delta, 0f);
            for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
                int i = r * w + c;
                downstream[i] = -1;
                float best = elevation[i];
                for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                    if ((dr == 0 && dc == 0) || r + dr < 0 || r + dr >= h || c + dc < 0 || c + dc >= w) continue;
                    int ni = (r + dr) * w + c + dc;
                    if (elevation[ni] < best) {
                        best = elevation[ni];
                        downstream[i] = ni;
                    }
                }
                float rainfall = climate == null ? 1f
                        : Math.max(0.08f, Math.min(4f, climate[2 * n + i] / 800f));
                runoff[i] = elevation[i] > 0 ? rainfall : 0f;
            }
            for (int index : order) {
                int target = downstream[index];
                if (target >= 0) runoff[target] += runoff[index];
            }
            for (int i = 0; i < n; i++) {
                int target = downstream[i];
                if (target < 0 || elevation[i] <= 0) continue;
                float distance = (i / w == target / w || i % w == target % w) ? pixelSize
                        : pixelSize * 1.41421356f;
                float slope = Math.max(0f, (elevation[i] - elevation[target]) / Math.max(1f, distance));
                float eroded = Math.min(Math.min(5f, pixelSize * 0.20f),
                        0.055f * (float) Math.sqrt(runoff[i]) * slope);
                if (slope < 0.025f) eroded *= slope / 0.025f;
                delta[i] -= eroded;
                // Coarse material settles on gentler downstream ground, making
                // fans and alluvial plains instead of deleting terrain mass.
                float deposited = eroded * Math.max(0.15f, 0.72f - slope * 1.8f);
                delta[target] += deposited;
            }
            for (int i = 0; i < n; i++) elevation[i] += delta[i];
        }
    }

    private static void sortByElevationDescending(int[] values, float[] elevation, int low, int high) {
        int i = low, j = high;
        float pivot = elevation[values[(low + high) >>> 1]];
        while (i <= j) {
            while (elevation[values[i]] > pivot) i++;
            while (elevation[values[j]] < pivot) j--;
            if (i <= j) {
                int swap = values[i]; values[i] = values[j]; values[j] = swap;
                i++; j--;
            }
        }
        if (low < j) sortByElevationDescending(values, elevation, low, j);
        if (i < high) sortByElevationDescending(values, elevation, i, high);
    }

    private static void addMultiScaleRelief(float[] elevation, int originRow, int originColumn,
                                             int h, int w, float pixelSize, long seed) {
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int i = r * w + c;
            float original = elevation[i];
            double x = (originColumn + c) * (double) pixelSize;
            double z = (originRow + r) * (double) pixelSize;
            double continent = fbm(x, z, 140_000.0, seed ^ 0x53a9d2e1L, 2);
            double regional = fbm(x, z, 31_000.0, seed ^ 0x79b4ac31L, 3);

            if (original < -120f) {
                // Broad shelf/basin variation without turning deep ocean into noisy islands.
                elevation[i] += (float) (continent * 16.0 + regional * 6.0);
                continue;
            }

            // Slowly warped plate boundaries form continuous, branching mountain
            // belts. The previous short-wave ridged value noise made a field of
            // unrelated bright blobs instead of recognizable ranges.
            double warpX = valueNoise(x, z, 135_000.0, seed ^ 0x4f1bbcdc676f3a21L) * 18_000.0
                    + valueNoise(x, z, 52_000.0, seed ^ 0x2b992ddfa23249d6L) * 4_500.0;
            double warpZ = valueNoise(x, z, 135_000.0, seed ^ 0x94d049bb133111ebL) * 18_000.0
                    + valueNoise(x, z, 52_000.0, seed ^ 0x369dea0f31a53f85L) * 4_500.0;
            double warpedX = x + warpX;
            double warpedZ = z + warpZ;
            double boundary = Math.abs(valueNoise(warpedX, warpedZ, 78_000.0,
                    seed ^ 0x1d872b41L));
            double envelope = smootherStep(clamp01((float) ((0.72 - boundary) / 0.56)));
            double spine = Math.pow(clamp01((float) (1.0 - boundary / 0.30)), 2.15);

            // Convergence varies very slowly, breaking the global boundary web
            // into long believable ranges with natural gaps and passes.
            double activity = valueNoise(x, z, 185_000.0, seed ^ 0xa24baed4963ee407L);
            double convergence = smootherStep(clamp01((float) ((activity + 0.20) / 0.72)));
            envelope *= convergence;
            spine *= convergence;

            double branchSignal = valueNoise(warpedX + warpZ * 0.17,
                    warpedZ - warpedX * 0.13, 24_000.0, seed ^ 0x9fb21c651e98df25L);
            double branchRidge = Math.pow(Math.max(0.0, 1.0 - Math.abs(branchSignal)), 3.4);
            double alpineTexture = fbm(warpedX, warpedZ, 8_500.0,
                    seed ^ 0x6ef17a4bL, 2);

            // Only the most strongly convergent portions of a plate boundary
            // become supermassifs. Nested warped ridges create a long chain of
            // separate summits rather than one smooth dome or random peak dots.
            double collisionCore = smootherStep(clamp01((float) ((activity - 0.10) / 0.46)));
            double summitSignal = valueNoise(warpedX + warpedZ * 0.31,
                    warpedZ - warpedX * 0.19, 17_500.0,
                    seed ^ 0xc6bc279692b5cc83L);
            double summitRidge = Math.pow(Math.max(0.0,
                    1.0 - Math.abs(summitSignal) / 0.62), 4.6);
            double peakSignal = valueNoise(warpedX - warpedZ * 0.41,
                    warpedZ + warpedX * 0.23, 6_200.0,
                    seed ^ 0x2545f4914f6cdd1dL);
            double summitKnots = Math.pow(Math.max(0.0,
                    1.0 - Math.abs(peakSignal) / 0.68), 5.2);
            double needleSignal = valueNoise(warpedX + warpedZ * 0.57,
                    warpedZ - warpedX * 0.34, 2_350.0,
                    seed ^ 0x8cb92baa38f32d41L);
            double summitNeedles = Math.pow(Math.max(0.0,
                    1.0 - Math.abs(needleSignal) / 0.58), 8.4);
            double summitFacets = fbm(warpedX - warpedZ * 0.21,
                    warpedZ + warpedX * 0.18, 1_050.0,
                    seed ^ 0xd1b54a32d192ed03L, 2);

            float landMask = smootherStep(clamp01((original + 40f) / 620f));
            float highland = smootherStep(clamp01((original - 260f) / 2_100f));

            // De-emphasize isolated model peaks outside an orogenic belt. This
            // preserves coastlines and plains while making high terrain organize
            // into ranges instead of evenly scattered mounds.
            float excessHighland = Math.max(0f, original - 560f);
            float retention = (float) (0.38 + 0.62 * smootherStep(envelope));
            double shapedBase = original - excessHighland * (1.0 - retention);

            double broadRelief = continent * 30.0 + regional * 16.0;
            double foothills = envelope * (420.0 + highland * 480.0)
                    + envelope * collisionCore * 620.0;
            double mainRange = spine * (3_250.0 + highland * 2_250.0)
                    * (0.82 + branchRidge * 0.18);
            double branchingRidges = envelope * branchRidge * (430.0 + highland * 360.0);
            double crags = spine * alpineTexture * (190.0 + highland * 220.0);
            double massifShoulder = spine * collisionCore * (1_550.0 + highland * 1_000.0);
            double summitCrown = spine * collisionCore * summitRidge
                    * (3_450.0 + highland * 1_550.0)
                    * (0.74 + summitKnots * 0.46);
            double summitCrags = spine * collisionCore * summitRidge * summitKnots
                    * (820.0 + highland * 620.0);
            // A third, much shorter tectonic scale keeps the highest portions
            // pointed after interpolation and erosion. Faceting modulates the
            // needle instead of adding noisy isolated blocks.
            double summitNeedleCrown = spine * collisionCore * summitRidge
                    * summitNeedles * (2_150.0 + highland * 1_050.0)
                    * (0.88 + Math.max(-0.35, summitFacets) * 0.34);
            elevation[i] = (float) (shapedBase + landMask
                    * (broadRelief + foothills + mainRange + branchingRidges + crags
                    + massifShoulder + summitCrown + summitCrags + summitNeedleCrown));
        }
    }

    /**
     * Adds rare coastal headlands and inland fault escarpments.
     *
     * <p>Several close, independently warped thresholds form a cliff stair: a
     * near-vertical wall, a traversable bench, then another wall. This preserves
     * the drama of a sheer face without turning every slope into an unplayable
     * heightfield staircase. All inputs use global physical coordinates, so cliff
     * chains remain continuous across generation tiles.</p>
     */
    static void addEscarpments(float[] elevation, int[] oceanDistance,
                               int originRow, int originColumn, int h, int w,
                               float pixelSize, long seed) {
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int i = r * w + c;
            float original = elevation[i];
            if (original <= 18f || original >= 6_800f) continue;

            double x = (originColumn + c) * (double) pixelSize;
            double z = (originRow + r) * (double) pixelSize;
            double rough = fbm(x, z, 4_800.0, seed ^ 0x3f84d5b5b5470917L, 2);

            // Sea cliffs occur in coherent headland provinces. Low coastal
            // plains stay untouched, leaving beaches, marshes and deltas intact.
            double coastProvince = valueNoise(x, z, 92_000.0,
                    seed ^ 0xa4093822299f31d0L);
            double headland = smootherStep((coastProvince - 0.08) / 0.42)
                    * smootherStep((original - 55.0) / 260.0);
            double coastMeters = oceanDistance[i] * (double) pixelSize;
            double coastContextMeters = OCEAN_CONTEXT_RADIUS * (double) pixelSize;
            if (headland > 0.001 && oceanDistance[i] <= OCEAN_CONTEXT_RADIUS) {
                // Fade before the finite ocean-search radius. Without this,
                // scale-2 tiles could disagree by hundreds of metres when only
                // one tile's padded window happened to contain the coastline.
                double fadeStart = coastContextMeters * 0.64;
                double fadeLength = Math.max(pixelSize, coastContextMeters - fadeStart);
                double inlandFade = 1.0 - smootherStep((coastMeters - fadeStart) / fadeLength);
                double firstWall = smootherStep((coastMeters - 180.0) / 360.0);
                double secondWall = smootherStep((coastMeters - 1_050.0) / 460.0);
                double thirdWall = smootherStep((coastMeters - 2_250.0) / 620.0);
                double coastalUplift = 430.0 + firstWall * 230.0
                        + secondWall * 270.0 + thirdWall * 300.0;
                coastalUplift += rough * (55.0 + thirdWall * 65.0);
                elevation[i] += (float) (headland * inlandFade * coastalUplift);
            }

            // Inland fault scarps are broad plateaus bounded by several offset
            // walls. A slow province mask makes them rare and long, while the
            // warped threshold prevents ruler-straight contour bands.
            double faultProvince = valueNoise(x, z, 128_000.0,
                    seed ^ 0x13198a2e03707344L);
            double faultMask = smootherStep((faultProvince + 0.02) / 0.40)
                    * smootherStep((original - 130.0) / 900.0);
            if (faultMask <= 0.001) continue;

            double warpX = valueNoise(x, z, 46_000.0,
                    seed ^ 0x243f6a8885a308d3L) * 4_600.0;
            double warpZ = valueNoise(x, z, 46_000.0,
                    seed ^ 0x452821e638d01377L) * 4_600.0;
            double fault = valueNoise(x + warpX, z + warpZ, 34_000.0,
                    seed ^ 0xbe5466cf34e90c6cL);
            double wallA = smootherStep((fault + 0.10) / 0.018);
            double wallB = smootherStep((fault - 0.035) / 0.015);
            double wallC = smootherStep((fault - 0.165) / 0.014);
            double inlandUplift = wallA * 210.0 + wallB * 285.0 + wallC * 355.0;
            inlandUplift += rough * (35.0 + wallC * 45.0);
            elevation[i] += (float) (faultMask * inlandUplift);
        }
    }

    private static void thermalRelax(float[] elevation, int h, int w, float pixelSize, int iterations) {
        float talus = Math.max(4f, pixelSize * 0.72f);
        float[] delta = new float[elevation.length];
        for (int pass = 0; pass < iterations; pass++) {
            Arrays.fill(delta, 0f);
            for (int r = 1; r < h - 1; r++) for (int c = 1; c < w - 1; c++) {
                int i = r * w + c;
                if (elevation[i] <= 0) continue;
                int lowest = -1;
                float greatestDrop = talus;
                for (int k = 0; k < 4; k++) {
                    int ni = (r + DR4[k]) * w + c + DC4[k];
                    float drop = elevation[i] - elevation[ni];
                    if (drop > greatestDrop) {
                        greatestDrop = drop;
                        lowest = ni;
                    }
                }
                if (lowest >= 0) {
                    float moved = Math.min(pixelSize * 0.28f, (greatestDrop - talus) * 0.16f);
                    delta[i] -= moved;
                    delta[lowest] += moved;
                }
            }
            for (int i = 0; i < elevation.length; i++) elevation[i] += delta[i];
        }
    }

    private static void simulateClimate(float[] elevation, float[] climate, int[] oceanDistance,
                                        int originRow, int originColumn, int h, int w,
                                        float pixelSize, long seed) {
        int n = h * w;
        for (int r = 0; r < h; r++) {
            double worldZ = (originRow + r) * (double) pixelSize;
            float latitudeCooling = (float) (10.0 * Math.sin(worldZ / 185_000.0));
            boolean westToEast = valueNoise(0, worldZ, 120_000.0, seed ^ 0x468c2d91L) >= 0;
            int windStep = westToEast ? 1 : -1;
            for (int c = 0; c < w; c++) {
                int i = r * w + c;
                // A cumulative row scan made rainfall depend on where each
                // generation tile began. Tall mountains carried that state for
                // kilometres and produced perfectly rectangular biome cuts.
                // Fixed upwind fetches use only overlapping generation context,
                // so the same world coordinate receives the same rain shadow.
                float near = upwindElevation(elevation, r, c, h, w, windStep, 8);
                float middle = upwindElevation(elevation, r, c, h, w, windStep, 32);
                float far = upwindElevation(elevation, r, c, h, w, windStep, 96);
                float barrier = Math.max(near - pixelSize * 8f * 0.055f,
                        Math.max(middle - pixelSize * 32f * 0.055f,
                                far - pixelSize * 96f * 0.055f));
                float shadowHeight = Math.max(0f, barrier - elevation[i]);
                float upslope = Math.max(0f, elevation[i] - near);
                float maritime = (float) Math.exp(-oceanDistance[i] * pixelSize / 42_000.0);

                climate[i] -= latitudeCooling;
                climate[i] -= Math.max(0f, elevation[i]) * 0.0018f;
                climate[n + i] *= 1f - maritime * 0.24f;
                float rainShadow = (float) Math.exp(-shadowHeight / 1350f);
                float orographic = 1f + Math.min(0.38f, upslope / 1100f);
                climate[2 * n + i] = Math.max(0f,
                        climate[2 * n + i] * (0.82f + maritime * 0.30f) * rainShadow * orographic);
                climate[3 * n + i] *= 1f - maritime * 0.18f;
            }
        }
    }

    private static float upwindElevation(float[] elevation, int row, int column,
                                         int height, int width, int windStep,
                                         int distance) {
        int sampleColumn = Math.max(0, Math.min(width - 1,
                column - windStep * distance));
        return elevation[Math.max(0, Math.min(height - 1, row)) * width + sampleColumn];
    }

    private static void classifyTerrain(float[] elevation, float[] climate, int[] oceanDistance,
                                        byte[] geology, byte[] soilDepth, short[] landforms,
                                        int originRow, int originColumn, int h, int w,
                                        float pixelSize, long seed) {
        int n = h * w;
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int i = r * w + c;
            double x = (originColumn + c) * (double) pixelSize;
            double z = (originRow + r) * (double) pixelSize;
            float slope = localSlope(elevation, r, c, h, w, pixelSize);
            double plate = tectonicCore(x, z, seed);
            double rock = valueNoise(x, z, 9_000.0, seed ^ 0x6ef17a4bL);

            if (plate > 0.72 && elevation[i] > 650) geology[i] = TerrainMetadata.GEO_VOLCANIC;
            else if (elevation[i] > 1900 || slope > 0.82f) geology[i] = TerrainMetadata.GEO_GRANITIC;
            else if (rock > 0.42) geology[i] = TerrainMetadata.GEO_LIMESTONE;
            else if (elevation[i] > 1300 && climate != null && climate[i] < 2f) geology[i] = TerrainMetadata.GEO_GLACIAL;
            else geology[i] = TerrainMetadata.GEO_SEDIMENTARY;

            float moisture = climate == null ? 700f : climate[2 * n + i];
            int soil = Math.round(5f - slope * 5f + Math.min(3f, moisture / 550f));
            soilDepth[i] = (byte) Math.max(0, Math.min(8, soil));
            if (slope > 0.95f) landforms[i] |= TerrainMetadata.SCREE;
            if (elevation[i] > 70f && slope > 1.18f) {
                float dx = horizontalGradient(elevation, r, c, h, w, true);
                float dz = horizontalGradient(elevation, r, c, h, w, false);
                int highSide;
                if (Math.abs(dx) >= Math.abs(dz)) {
                    highSide = dx >= 0f
                            ? TerrainMetadata.CLIFF_EAST : TerrainMetadata.CLIFF_WEST;
                } else {
                    highSide = dz >= 0f
                            ? TerrainMetadata.CLIFF_SOUTH : TerrainMetadata.CLIFF_NORTH;
                }
                landforms[i] = TerrainMetadata.withCliff(
                        landforms[i], highSide, slope > 1.85f);
            }

            if (elevation[i] > 0 && oceanDistance[i] <= 3) landforms[i] |= TerrainMetadata.COAST;
            boolean stable = elevation[i] > 12 && elevation[i] < 1450
                    && oceanDistance[i] > 2 && slope < 0.16f;
            if (stable) landforms[i] |= TerrainMetadata.STRUCTURE_SUITABLE;
        }
    }

    private static int[] distanceFromOcean(float[] elevation, int h, int w) {
        int[] distance = new int[elevation.length];
        Arrays.fill(distance, OCEAN_DISTANCE_UNKNOWN);
        int[] queue = new int[elevation.length];
        int head = 0, tail = 0;
        for (int i = 0; i < elevation.length; i++) if (elevation[i] <= 0) {
            distance[i] = 0;
            queue[tail++] = i;
        }
        while (head < tail) {
            int i = queue[head++];
            int d = distance[i];
            if (d >= OCEAN_CONTEXT_RADIUS) continue;
            int r = i / w, c = i % w;
            for (int k = 0; k < 4; k++) {
                int nr = r + DR4[k], nc = c + DC4[k];
                if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                int ni = nr * w + nc;
                if (distance[ni] > d + 1) {
                    distance[ni] = d + 1;
                    queue[tail++] = ni;
                }
            }
        }
        return distance;
    }

    private static float localSlope(float[] elevation, int r, int c, int h, int w, float pixelSize) {
        float dx = horizontalGradient(elevation, r, c, h, w, true)
                / Math.max(1f, pixelSize);
        float dz = horizontalGradient(elevation, r, c, h, w, false)
                / Math.max(1f, pixelSize);
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private static float horizontalGradient(float[] elevation, int r, int c,
                                            int h, int w, boolean xAxis) {
        if (xAxis) {
            int left = r * w + Math.max(0, c - 1);
            int right = r * w + Math.min(w - 1, c + 1);
            return (elevation[right] - elevation[left]) * 0.5f;
        }
        int up = Math.max(0, r - 1) * w + c;
        int down = Math.min(h - 1, r + 1) * w + c;
        return (elevation[down] - elevation[up]) * 0.5f;
    }

    private static double fbm(double x, double z, double wavelength, long seed, int octaves) {
        double sum = 0, amplitude = 1, normalizer = 0;
        for (int octave = 0; octave < octaves; octave++) {
            sum += valueNoise(x, z, wavelength, seed + octave * 0x9e3779b97f4a7c15L) * amplitude;
            normalizer += amplitude;
            wavelength *= 0.5;
            amplitude *= 0.5;
        }
        return sum / normalizer;
    }

    /** Narrow warped plate-boundary core, shared with geological classification. */
    private static double tectonicCore(double x, double z, long seed) {
        double warpX = valueNoise(x, z, 135_000.0, seed ^ 0x4f1bbcdc676f3a21L) * 18_000.0
                + valueNoise(x, z, 52_000.0, seed ^ 0x2b992ddfa23249d6L) * 4_500.0;
        double warpZ = valueNoise(x, z, 135_000.0, seed ^ 0x94d049bb133111ebL) * 18_000.0
                + valueNoise(x, z, 52_000.0, seed ^ 0x369dea0f31a53f85L) * 4_500.0;
        double boundary = Math.abs(valueNoise(x + warpX, z + warpZ, 78_000.0,
                seed ^ 0x1d872b41L));
        double activity = valueNoise(x, z, 185_000.0, seed ^ 0xa24baed4963ee407L);
        double convergence = smootherStep(clamp01((float) ((activity + 0.20) / 0.72)));
        return Math.pow(clamp01((float) (1.0 - boundary / 0.30)), 2.15) * convergence;
    }

    private static double valueNoise(double x, double z, double wavelength, long seed) {
        double gx = x / wavelength, gz = z / wavelength;
        long x0 = fastFloor(gx), z0 = fastFloor(gz);
        double tx = smooth(gx - x0), tz = smooth(gz - z0);
        double a = randomSigned(x0, z0, seed);
        double b = randomSigned(x0 + 1, z0, seed);
        double c = randomSigned(x0, z0 + 1, seed);
        double d = randomSigned(x0 + 1, z0 + 1, seed);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    private static double randomSigned(long x, long z, long seed) {
        long v = seed ^ x * 0x632be59bd9b4e019L ^ z * 0x9e3779b97f4a7c15L;
        v ^= v >>> 30;
        v *= 0xbf58476d1ce4e5b9L;
        v ^= v >>> 27;
        v *= 0x94d049bb133111ebL;
        v ^= v >>> 31;
        return ((v >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static long fastFloor(double value) {
        long whole = (long) value;
        return value < whole ? whole - 1 : whole;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static float smootherStep(float value) {
        float t = clamp01(value);
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static double smootherStep(double value) {
        double t = Math.max(0.0, Math.min(1.0, value));
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
