package th.in.midnight_network.immensa.pipeline;

import th.in.midnight_network.immensa.config.ImmensaConfig;

import java.util.Arrays;

/** Deterministic, climate-aware drainage post-process for generated heightmaps. */
public final class HydrologyProcessor {
    static final short RIVER = 7;
    static final short FROZEN_RIVER = 11;
    private static final short DRY = Short.MIN_VALUE;
    private static final int[] DR = {-1, 1, 0, 0, -1, -1, 1, 1};
    private static final int[] DC = {0, 0, -1, 1, -1, 1, -1, 1};
    private static final float[] DIST = {1, 1, 1, 1, 1.41421356f, 1.41421356f, 1.41421356f, 1.41421356f};

    private record FloodResult(float[] spill, int[] outletPath) {}

    /** Primitive heap avoids one Cell allocation per terrain pixel. */
    private static final class IntMinHeap {
        private final int[] heap;
        private final float[] priorities;
        private int size;

        IntMinHeap(int capacity, float[] priorities) {
            this.heap = new int[capacity];
            this.priorities = priorities;
        }

        boolean isEmpty() { return size == 0; }

        void add(int value) {
            int position = size++;
            while (position > 0) {
                int parent = (position - 1) >>> 1;
                if (!less(value, heap[parent])) break;
                heap[position] = heap[parent];
                position = parent;
            }
            heap[position] = value;
        }

        int remove() {
            int result = heap[0];
            int value = heap[--size];
            if (size == 0) return result;
            int position = 0;
            while (true) {
                int left = position * 2 + 1;
                if (left >= size) break;
                int right = left + 1;
                int child = right < size && less(heap[right], heap[left]) ? right : left;
                if (!less(heap[child], value)) break;
                heap[position] = heap[child];
                position = child;
            }
            heap[position] = value;
            return result;
        }

        private boolean less(int a, int b) {
            float pa = priorities[a], pb = priorities[b];
            return pa < pb || (pa == pb && a < b);
        }
    }

    private HydrologyProcessor() {}

    static float scaleAdjustedMinCatchmentKm2(float configured, float pixelSizeMeters, boolean enabled) {
        if (!enabled) return configured;
        // Scale 2 (15 m/block) is the visual reference. Scaling by pixel area
        // keeps roughly the same number of visible channel heads per block area.
        float ratio = Math.max(1f / 3f, pixelSizeMeters / 15f);
        return configured * ratio * ratio;
    }

    public static LocalTerrainProvider.HeightmapData process(
            LocalTerrainProvider.HeightmapData input, int padding, float pixelSizeMeters,
            float minCatchmentKm2, float minLakeAreaKm2, float maxLakeDepthMeters) {
        return process(input, padding, pixelSizeMeters, minCatchmentKm2, minLakeAreaKm2,
                maxLakeDepthMeters, 0, 0, 0L);
    }

    public static LocalTerrainProvider.HeightmapData process(
            LocalTerrainProvider.HeightmapData input, int padding, float pixelSizeMeters,
            float minCatchmentKm2, float minLakeAreaKm2, float maxLakeDepthMeters,
            int originRow, int originColumn, long seed) {
        int h = input.height, w = input.width;
        if (padding < 0 || h <= padding * 2 || w <= padding * 2) {
            throw new IllegalArgumentException("Hydrology padding leaves no output area");
        }
        int n = h * w;
        float[] elevation = new float[n];
        short[] biomes = new short[n];
        short[] landforms = new short[n];
        byte[] geology = new byte[n];
        byte[] soilDepth = new byte[n];
        byte[] riverWidth = new byte[n];
        boolean[] belowSea = new boolean[n];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int i = r * w + c;
            elevation[i] = input.heightmap[r][c];
            biomes[i] = input.biomeIds[r][c];
            landforms[i] = input.landforms[r][c];
            geology[i] = input.geology[r][c];
            soilDepth[i] = input.soilDepth[r][c];
            belowSea[i] = elevation[i] <= 0;
        }

        float cellAreaKm2 = pixelSizeMeters * pixelSizeMeters / 1_000_000.0f;
        int minLakeCells = Math.max(1, (int) Math.ceil(minLakeAreaKm2 / cellAreaKm2));
        boolean[] ocean = classifyOceanAndRemoveTinyInlandBasins(
                elevation, belowSea, h, w, minLakeCells, pixelSizeMeters);

        FloodResult flood = priorityFlood(elevation, ocean, h, w);
        float[] spill = flood.spill();
        boolean[] lake = new boolean[n];
        float[] water = new float[n];
        Arrays.fill(water, Float.NaN);
        findBoundedLakes(elevation, spill, ocean, lake, water, h, w,
                minLakeCells, maxLakeDepthMeters);

        int[] downstream = flowDirections(spill, flood.outletPath(), ocean, h, w);
        float[] discharge = new float[n];
        float meanRunoff = 0;
        int landCount = 0;
        for (int i = 0; i < n; i++) {
            if (ocean[i]) continue;
            float runoff = runoffForBiome(biomes[i]);
            if (elevation[i] >= 1800 && isSnowBiome(biomes[i])) runoff += 2.5;
            discharge[i] = runoff;
            meanRunoff += runoff;
            landCount++;
        }
        meanRunoff = landCount == 0 ? 1 : meanRunoff / landCount;

        accumulateDischarge(discharge, downstream, biomes, pixelSizeMeters);

        float[] catchment = new float[n];
        for (int i = 0; i < n; i++) {
            catchment[i] = discharge[i] / Math.max(meanRunoff, 0.01f) * cellAreaKm2;
        }
        boolean[] river = carveRivers(elevation, spill, water, ocean, lake, catchment,
                downstream, minCatchmentKm2, pixelSizeMeters, h, w,
                originRow, originColumn, seed, riverWidth);

        applyHydrologicLandforms(elevation, water, biomes, ocean, lake, river, catchment,
                downstream, landforms, soilDepth, riverWidth, minCatchmentKm2,
                pixelSizeMeters, h, w, originRow, originColumn, seed);

        boolean[] frozenWater = coherentFrozenWater(biomes, river, lake, h, w);
        applyRiparianBiomes(biomes, river, lake, h, w);
        for (int i = 0; i < n; i++) {
            if (river[i] || lake[i]) biomes[i] = frozenWater[i] ? FROZEN_RIVER : RIVER;
        }
        return crop(elevation, biomes, water, lake, landforms, geology, soilDepth,
                riverWidth, h, w, padding);
    }

    private static FloodResult priorityFlood(float[] z, boolean[] ocean, int h, int w) {
        float[] filled = z.clone();
        boolean[] visited = ocean.clone();
        int[] outletPath = new int[z.length];
        Arrays.fill(outletPath, -1);
        IntMinHeap queue = new IntMinHeap(z.length, filled);
        for (int r = 0; r < h; r++) { seed(r * w, filled, visited, queue); seed(r * w + w - 1, filled, visited, queue); }
        for (int c = 0; c < w; c++) { seed(c, filled, visited, queue); seed((h - 1) * w + c, filled, visited, queue); }
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int i = r * w + c;
            if (visited[i]) continue;
            for (int k = 0; k < 8; k++) {
                int nr = r + DR[k], nc = c + DC[k];
                if (nr >= 0 && nr < h && nc >= 0 && nc < w && ocean[nr * w + nc]) { seed(i, filled, visited, queue); break; }
            }
        }
        while (!queue.isEmpty()) {
            int index = queue.remove();
            float level = filled[index];
            int r = index / w, c = index % w;
            for (int k = 0; k < 8; k++) {
                int nr = r + DR[k], nc = c + DC[k];
                if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                int ni = nr * w + nc;
                if (visited[ni]) continue;
                visited[ni] = true;
                filled[ni] = Math.max(z[ni], level);
                outletPath[ni] = index;
                queue.add(ni);
            }
        }
        return new FloodResult(filled, outletPath);
    }

    private static void seed(int i, float[] levels, boolean[] visited, IntMinHeap queue) {
        if (!visited[i]) { visited[i] = true; queue.add(i); }
    }

    /**
     * Only below-sea terrain connected to the padded tile boundary is ocean.
     * Small enclosed negative components are DEM noise, not useful lakes; lift
     * them above the vanilla sea fill level before density generation.
     */
    private static boolean[] classifyOceanAndRemoveTinyInlandBasins(
            float[] elevation, boolean[] belowSea, int h, int w,
            int minLakeCells, float blockHeightMeters) {
        boolean[] ocean = new boolean[elevation.length];
        boolean[] seen = new boolean[elevation.length];
        int[] component = new int[elevation.length];
        for (int start = 0; start < elevation.length; start++) {
            if (!belowSea[start] || seen[start]) continue;
            int head = 0, tail = 1;
            component[0] = start;
            seen[start] = true;
            boolean touchesBoundary = false;
            while (head < tail) {
                int i = component[head++];
                int r = i / w, c = i % w;
                touchesBoundary |= r == 0 || r == h - 1 || c == 0 || c == w - 1;
                for (int k = 0; k < 8; k++) {
                    int nr = r + DR[k], nc = c + DC[k];
                    if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                    int ni = nr * w + nc;
                    if (belowSea[ni] && !seen[ni]) {
                        seen[ni] = true;
                        component[tail++] = ni;
                    }
                }
            }
            if (touchesBoundary) {
                for (int j = 0; j < tail; j++) ocean[component[j]] = true;
            } else if (tail < minLakeCells) {
                float dryLowland = blockHeightMeters * 1.05f;
                for (int j = 0; j < tail; j++) elevation[component[j]] = dryLowland;
            }
        }
        return ocean;
    }

    private static void findBoundedLakes(float[] z, float[] spill, boolean[] ocean, boolean[] lake,
                                         float[] water, int h, int w, int minLakeCells,
                                         float maxDepth) {
        boolean[] seen = ocean.clone();
        int[] component = new int[z.length];
        for (int start = 0; start < z.length; start++) {
            if (seen[start] || spill[start] - z[start] <= 0.05f) continue;
            int head = 0, tail = 1;
            component[0] = start; seen[start] = true;
            float floor = z[start], level = spill[start];
            while (head < tail) {
                int i = component[head++];
                floor = Math.min(floor, z[i]); level = Math.max(level, spill[i]);
                int r = i / w, c = i % w;
                for (int k = 0; k < 8; k++) {
                    int nr = r + DR[k], nc = c + DC[k];
                    if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                    int ni = nr * w + nc;
                    if (!seen[ni] && !ocean[ni] && spill[ni] - z[ni] > 0.05f) {
                        seen[ni] = true; component[tail++] = ni;
                    }
                }
            }
            if (tail < minLakeCells || level - floor > maxDepth) continue;
            for (int componentIndex = 0; componentIndex < tail; componentIndex++) {
                int i = component[componentIndex];
                if (z[i] < level) { lake[i] = true; water[i] = level; }
            }
        }
    }

    private static int[] flowDirections(float[] surface, int[] outletPath, boolean[] ocean, int h, int w) {
        int[] result = new int[surface.length]; Arrays.fill(result, -1);
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int i = r * w + c;
            if (ocean[i]) continue;
            float bestSlope = 0; int best = -1;
            for (int k = 0; k < 8; k++) {
                int nr = r + DR[k], nc = c + DC[k];
                if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                int ni = nr * w + nc;
                if (ocean[ni]) {
                    // Preserve the receiving ocean cell in the flow graph.
                    // The old -1 sentinel made sea-bound rivers look like
                    // endorheic channels and required the mouth pass to hide
                    // their disconnected final drainage edge.
                    best = ni;
                    bestSlope = -Float.MAX_VALUE;
                    break;
                }
                float slope = (surface[ni] - surface[i]) / DIST[k];
                if (slope < bestSlope) {
                    bestSlope = slope; best = ni;
                }
            }
            // Priority-flood parents provide an acyclic route across flats and
            // depressions. This avoids the old index-biased diagonal staircases.
            result[i] = best >= 0 ? best : outletPath[i];
        }
        return result;
    }

    private static void accumulateDischarge(float[] discharge, int[] downstream, short[] biomes,
                                            float pixelSizeMeters) {
        int[] upstreamCount = new int[downstream.length];
        for (int target : downstream) if (target >= 0) upstreamCount[target]++;
        int[] queue = new int[downstream.length];
        int head = 0, tail = 0;
        for (int i = 0; i < upstreamCount.length; i++) if (upstreamCount[i] == 0) queue[tail++] = i;
        while (head < tail) {
            int source = queue[head++];
            int target = downstream[source];
            if (target < 0) continue;
            discharge[target] += discharge[source]
                    * transmissionForBiome(biomes[target], pixelSizeMeters);
            if (--upstreamCount[target] == 0) queue[tail++] = target;
        }
    }

    /**
     * Carves rounded, gradually widening channels around the drainage centerline.
     * Each cross-section shares its centerline water level, eliminating the old
     * checkerboard of independently stepped water columns.
     */
    private static boolean[] carveRivers(float[] elevation, float[] spill, float[] water,
                                          boolean[] ocean, boolean[] lake, float[] catchment,
                                          int[] downstream, float minCatchmentKm2,
                                          float pixelSizeMeters, int h, int w,
                                          int originRow, int originColumn, long seed,
                                          byte[] riverWidth) {
        int n = elevation.length;
        boolean[] centerline = new boolean[n];
        for (int i = 0; i < n; i++) {
            centerline[i] = catchment[i] >= minCatchmentKm2 && !ocean[i] && !lake[i];
        }

        boolean[] drainsToOcean = drainsToOcean(downstream, ocean);
        float[] centerSurface = regularizeRiverSurfaces(
                elevation, spill, centerline, downstream, drainsToOcean, pixelSizeMeters);
        float[] ownerDistanceSquared = new float[n];
        Arrays.fill(ownerDistanceSquared, Float.POSITIVE_INFINITY);
        float[] bedTarget = new float[n];
        float[] surfaceTarget = new float[n];
        float[] centerRadius = new float[n];
        byte[] widthTarget = new byte[n];
        Arrays.fill(bedTarget, Float.POSITIVE_INFINITY);
        Arrays.fill(surfaceTarget, Float.NaN);

        for (int i = 0; i < n; i++) {
            if (!centerline[i]) continue;
            // Width is tied to absolute drainage area rather than the display
            // threshold. Pruning tributaries therefore leaves genuinely wider
            // main rivers instead of equally thin replacement streams.
            float relativeFlow = catchment[i] / Math.max(0.01f, minCatchmentKm2);
            float logFlow = (float) (Math.log1p(relativeFlow) / Math.log(2.0));
            // Hydraulic-geometry approximation in physical metres, converted
            // back to Minecraft blocks. This avoids 300-500 m wide tributaries
            // at scale 1 while retaining broad downstream trunk rivers.
            float physicalWidthMeters = Math.min(220f,
                    6f + 7f * (float) Math.sqrt(Math.max(0f, catchment[i])));
            float physicalRadius = physicalWidthMeters / Math.max(2f, pixelSizeMeters * 2f);
            // A literal 30 m-per-block conversion makes nearly every scale-1
            // river only 2-3 blocks wide. Preserve hydraulic hierarchy while
            // giving even a threshold channel a readable wetted cross-section.
            float hierarchyRadius = 0.55f + 0.52f * (float) (
                    Math.log1p(Math.max(0f, catchment[i])) / Math.log(2.0));
            float scaleMinimumRadius = pixelSizeMeters >= 25f ? 3.25f : 2.75f;
            float configuredMinimumRadius =
                    (ImmensaConfig.hydrologyNormalRiverMinWidthBlocks() - 1f) * 0.5f;
            float minimumRadius = Math.max(scaleMinimumRadius, configuredMinimumRadius);
            float normalMaximumRadius =
                    (ImmensaConfig.hydrologyNormalRiverMaxWidthBlocks() - 1f) * 0.5f;
            float radius = Math.min(normalMaximumRadius, Math.max(minimumRadius,
                    Math.max(physicalRadius, hierarchyRadius)
                            * ImmensaConfig.hydrologyRiverWidthMultiplier()));
            float greatBlend = greatRiverBlend(
                    catchment[i], minCatchmentKm2, drainsToOcean[i]);
            if (greatBlend > 0f) {
                float greatThreshold = minCatchmentKm2
                        * ImmensaConfig.hydrologyGreatRiverCatchmentMultiplier();
                float greatRatio = catchment[i] / Math.max(0.01f, greatThreshold);
                float growth = 1f - (float) Math.exp(
                        -1.2f * Math.max(0f, greatRatio - 1f));
                int configuredMaximum = ImmensaConfig
                        .hydrologyGreatRiverMaxWidthBlocks();
                int contextSafeMaximum = Math.max(32, Math.min(configuredMaximum,
                        ImmensaConfig.hydrologyPadding() * 2 - 16));
                int contextSafeMinimum = Math.min(contextSafeMaximum,
                        ImmensaConfig.hydrologyGreatRiverMinWidthBlocks());
                float greatWidth = contextSafeMinimum
                        + (contextSafeMaximum - contextSafeMinimum) * growth;
                float greatRadius = Math.max(15.5f, (greatWidth - 1f) * 0.5f);
                radius += (greatRadius - radius) * greatBlend;
            }
            centerRadius[i] = radius;
            float centerDepthBlocks = Math.min(4.0f, 1.20f + 0.28f * logFlow);
            if (greatBlend > 0f) {
                float greatThreshold = minCatchmentKm2
                        * ImmensaConfig.hydrologyGreatRiverCatchmentMultiplier();
                float greatRatio = catchment[i] / Math.max(0.01f, greatThreshold);
                float greatDepth = Math.min(9.0f,
                        5.0f + 1.65f * (float) Math.log1p(greatRatio));
                centerDepthBlocks += (greatDepth - centerDepthBlocks) * greatBlend;
            }
            float channelSurface = centerSurface[i];
            int range = (int) Math.ceil(radius);
            int r = i / w, c = i % w;
            int centerR = r, centerC = c;
            int downstreamTarget = downstream[i];
            if (downstreamTarget >= 0 && catchment[i] >= minCatchmentKm2 * 2.5f) {
                int flowR = downstreamTarget / w - r;
                int flowC = downstreamTarget % w - c;
                float flowLength = (float) Math.sqrt(flowR * flowR + flowC * flowC);
                float localDrop = Math.max(0f, elevation[i] - elevation[downstreamTarget]);
                if (flowLength > 0 && localDrop / Math.max(1f, pixelSizeMeters) < 0.24f) {
                    double worldX = (originColumn + c) * (double) pixelSizeMeters;
                    double worldZ = (originRow + r) * (double) pixelSizeMeters;
                    double phase = ((seed ^ 0x52dce729L) & 0xffff) * 0.00017;
                    float ordinaryOffset = (float) Math.sin(
                            (worldX + worldZ * 0.73) / 760.0 + phase)
                            * Math.min(2.4f, radius * 0.30f);
                    float greatOffset = ((float) Math.sin(
                            (worldX * 0.43 + worldZ * 0.91) / 2400.0 + phase)
                            + 0.34f * (float) Math.sin(
                            (worldX * 0.79 - worldZ * 0.37) / 1100.0 - phase * 1.7))
                            * Math.min(22f, radius * 0.38f);
                    float offset = ordinaryOffset
                            + (greatOffset - ordinaryOffset) * greatBlend;
                    centerR += Math.round(-flowC / flowLength * offset);
                    centerC += Math.round(flowR / flowLength * offset);
                }
            }
            centerR = Math.max(0, Math.min(h - 1, centerR));
            centerC = Math.max(0, Math.min(w - 1, centerC));

            for (int dr = -range; dr <= range; dr++) for (int dc = -range; dc <= range; dc++) {
                int nr = centerR + dr, nc = centerC + dc;
                if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                float distanceSquared = dr * dr + dc * dc;
                float distance = (float) Math.sqrt(distanceSquared);
                int ni = nr * w + nc;
                if (ocean[ni] || lake[ni]) continue;
                // Do not let a below-sea-level endorheic profile stamp a very
                // wide trench sideways into ordinary above-sea-level land.
                if (channelSurface < 0f && elevation[ni] >= 0f) continue;

                if (distance > radius) {
                    continue;
                }

                // Do not cut implausibly high valley walls merely to satisfy a
                // wide downstream radius; let the channel conform to terrain.
                float bankTolerance = pixelSizeMeters * (1.0f
                        + Math.min(radius, 16f) * 0.38f
                        + Math.max(0f, radius - 16f) * 0.08f);
                if (elevation[ni] > spill[i] + bankTolerance) continue;

                float normalized = Math.min(1.0f, distance / Math.max(0.75f, radius));
                // A smooth U-shaped cross-section. Even its shallow edge is a
                // complete block below the surface, so every wet column fills.
                float profile = 1.0f - normalized * normalized;
                profile = profile * profile;
                float depthBlocks = 1.05f + (centerDepthBlocks - 1.05f) * profile;
                float target = channelSurface - pixelSizeMeters * depthBlocks;

                // The nearest centerline sample owns the water level. This
                // keeps each cross-section level instead of making diagonal
                // seams from overlapping circular channel stamps.
                if (distanceSquared < ownerDistanceSquared[ni]
                        || (distanceSquared == ownerDistanceSquared[ni]
                        && (!Float.isFinite(surfaceTarget[ni]) || channelSurface < surfaceTarget[ni]))) {
                    ownerDistanceSquared[ni] = distanceSquared;
                    surfaceTarget[ni] = channelSurface;
                    widthTarget[ni] = (byte) Math.max(1, Math.min(127, Math.round(radius * 2f + 1f)));
                }
                bedTarget[ni] = Math.min(bedTarget[ni], target);
            }
        }

        // Meandered wide stamps may touch a steep bank and be rejected by the
        // wall-tolerance check. Overlay a narrower unshifted core on the actual
        // drainage path so every downstream cell remains wet and adjacent stamps
        // overlap by several blocks. This is the topological river; the wider
        // offset stamp supplies its natural banks and bends.
        for (int i = 0; i < n; i++) {
            if (!centerline[i] || !Float.isFinite(centerSurface[i])) continue;
            int r = i / w, c = i % w;
            float fullRadius = centerRadius[i];
            float coreRadius = Math.min(fullRadius,
                    Math.max(pixelSizeMeters >= 25f ? 3.0f : 2.5f, fullRadius * 0.58f));
            int range = (int) Math.ceil(coreRadius);
            float channelSurface = centerSurface[i];
            for (int dr = -range; dr <= range; dr++) for (int dc = -range; dc <= range; dc++) {
                int nr = r + dr, nc = c + dc;
                if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                float distanceSquared = dr * dr + dc * dc;
                if (distanceSquared > coreRadius * coreRadius) continue;
                int ni = nr * w + nc;
                if (ocean[ni] || lake[ni]) continue;

                float normalized = (float) Math.sqrt(distanceSquared)
                        / Math.max(0.75f, coreRadius);
                float depthBlocks = 1.10f + 0.45f * (1.0f - normalized * normalized);
                bedTarget[ni] = Math.min(bedTarget[ni],
                        channelSurface - pixelSizeMeters * depthBlocks);
                if (!Float.isFinite(surfaceTarget[ni])) {
                    surfaceTarget[ni] = channelSurface;
                    ownerDistanceSquared[ni] = distanceSquared;
                }
                widthTarget[ni] = (byte) Math.max(Byte.toUnsignedInt(widthTarget[ni]),
                        Math.min(127, Math.round(fullRadius * 2f + 1f)));
            }
        }

        boolean[] river = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (Float.isFinite(surfaceTarget[i]) && Float.isFinite(bedTarget[i])) {
                river[i] = true;
                elevation[i] = Math.min(elevation[i], bedTarget[i]);
                water[i] = surfaceTarget[i];
                riverWidth[i] = widthTarget[i];
            }
        }
        return river;
    }

    static float greatRiverBlend(float catchmentKm2, float minCatchmentKm2,
                                 boolean drainsToOcean) {
        if (!ImmensaConfig.hydrologyGreatRiversEnabled() || !drainsToOcean) {
            return 0f;
        }
        float threshold = Math.max(0.01f, minCatchmentKm2)
                * ImmensaConfig.hydrologyGreatRiverCatchmentMultiplier();
        float start = threshold * 0.82f;
        float end = threshold * 1.18f;
        float t = Math.max(0f, Math.min(1f,
                (catchmentKm2 - start) / Math.max(0.01f, end - start)));
        return t * t * (3f - 2f * t);
    }

    private static void applyHydrologicLandforms(
            float[] elevation, float[] water, short[] biomes, boolean[] ocean,
            boolean[] lake, boolean[] river, float[] catchment, int[] downstream,
            short[] landforms, byte[] soilDepth, byte[] riverWidth, float minCatchmentKm2,
            float pixelSizeMeters, int h, int w, int originRow, int originColumn, long seed) {
        int[] oceanDistance = distanceToMask(ocean, h, w, 24);
        connectRiverMouths(elevation, water, ocean, river, catchment, oceanDistance,
                landforms, riverWidth, minCatchmentKm2, pixelSizeMeters, h, w);

        boolean[] mainRiver = new boolean[river.length];
        for (int i = 0; i < river.length; i++) {
            mainRiver[i] = river[i] && catchment[i] >= minCatchmentKm2 * 3f;
        }
        boolean[] greatRiver = new boolean[river.length];
        int greatWidthThreshold = ImmensaConfig
                .hydrologyGreatRiverMinWidthBlocks();
        for (int i = 0; i < river.length; i++) {
            greatRiver[i] = river[i]
                    && Byte.toUnsignedInt(riverWidth[i]) >= greatWidthThreshold;
            if (greatRiver[i]) {
                landforms[i] |= TerrainMetadata.GREAT_RIVER;
            }
        }
        int[] riverDistance = distanceToMask(mainRiver, h, w, 20);
        int[] greatRiverDistance = distanceToMask(greatRiver, h, w, 28);
        int[] waterDistance = distanceToCombinedMask(river, lake, ocean, h, w, 12);

        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int i = r * w + c;
            if (ocean[i]) continue;
            float slope = localSlope(elevation, r, c, h, w, pixelSizeMeters);
            boolean broadFloodplain = greatRiverDistance[i] <= 18 && slope < 0.14f;
            if (broadFloodplain || (riverDistance[i] <= 6 && slope < 0.18f)) {
                landforms[i] |= TerrainMetadata.FLOODPLAIN;
                soilDepth[i] = (byte) Math.max(soilDepth[i], broadFloodplain ? 8 : 6);
            }
            boolean humid = biomes[i] != BiomeClassifier.DESERT && biomes[i] != BiomeClassifier.BADLANDS;
            if (humid && waterDistance[i] <= 3 && slope < 0.10f && elevation[i] < 500) {
                landforms[i] |= TerrainMetadata.WETLAND;
                soilDepth[i] = (byte) Math.max(soilDepth[i], 7);
                if (!river[i] && !lake[i]) biomes[i] = BiomeClassifier.SWAMP;
            }
            if (river[i] && oceanDistance[i] <= 9 && slope < 0.22f) {
                landforms[i] |= TerrainMetadata.ESTUARY;
                if (oceanDistance[i] <= 5 && catchment[i] >= minCatchmentKm2 * 2f) {
                    landforms[i] |= TerrainMetadata.DELTA;
                    biomes[i] = BiomeClassifier.MANGROVE_SWAMP;
                }
            }
            int target = downstream[i];
            if (river[i] && target >= 0
                    && elevation[i] - elevation[target] > Math.max(12f, pixelSizeMeters * 1.8f)) {
                landforms[i] |= TerrainMetadata.WATERFALL;
            }
        }

        addDeltaDistributaries(elevation, water, biomes, ocean, river, lake, catchment,
                landforms, riverWidth, minCatchmentKm2, h, w);
        addOxbows(elevation, water, biomes, river, lake, catchment, landforms,
                minCatchmentKm2, pixelSizeMeters, h, w, originRow, originColumn, seed);
        chooseInfrastructureSites(river, catchment, downstream, riverWidth, landforms, minCatchmentKm2,
                h, w, originRow, originColumn, seed);
    }

    /** Opens and gently widens the final coastal cells instead of ending at a beach wall. */
    private static void connectRiverMouths(float[] elevation, float[] water, boolean[] ocean,
                                            boolean[] river, float[] catchment, int[] oceanDistance,
                                            short[] landforms, byte[] riverWidth, float threshold,
                                            float pixelSize, int h, int w) {
        boolean[] mouth = new boolean[river.length];
        for (int i = 0; i < river.length; i++) {
            mouth[i] = river[i] && oceanDistance[i] <= 2 && catchment[i] >= threshold;
        }
        for (int i = 0; i < mouth.length; i++) {
            if (!mouth[i]) continue;
            int r = i / w, c = i % w;
            int existingWidth = Math.max(3, Byte.toUnsignedInt(riverWidth[i]));
            boolean greatRiver = existingWidth >= ImmensaConfig
                    .hydrologyGreatRiverMinWidthBlocks();
            int radius = greatRiver
                    ? Math.min(ImmensaConfig.hydrologyGreatRiverMaxWidthBlocks() / 2 + 4,
                    existingWidth / 2 + 4)
                    : Math.max(2, Math.min(5, existingWidth / 2 + 1));
            for (int dr = -radius; dr <= radius; dr++) for (int dc = -radius; dc <= radius; dc++) {
                int nr = r + dr, nc = c + dc;
                if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                int ni = nr * w + nc;
                if (ocean[ni] || oceanDistance[ni] > 4) continue;
                float distance = (float) Math.sqrt(dr * dr + dc * dc);
                float maximumBank = pixelSize * (greatRiver ? 4.0f : 2.25f);
                if (distance > radius || elevation[ni] > maximumBank) continue;
                float profile = 1f - distance / (radius + 0.5f);
                river[ni] = true;
                water[ni] = 0f;
                elevation[ni] = Math.min(elevation[ni], -pixelSize * (1.02f + 0.45f * profile));
                riverWidth[ni] = (byte) Math.max(Byte.toUnsignedInt(riverWidth[ni]), existingWidth);
                landforms[ni] |= TerrainMetadata.ESTUARY;
                if (greatRiver) landforms[ni] |= TerrainMetadata.GREAT_RIVER;
            }
        }
    }

    private static void addDeltaDistributaries(float[] elevation, float[] water, short[] biomes,
                                                boolean[] ocean, boolean[] river, boolean[] lake,
                                                float[] catchment, short[] landforms, byte[] riverWidth,
                                                float threshold, int h, int w) {
        for (int r = 2; r < h - 2; r++) for (int c = 2; c < w - 2; c++) {
            int i = r * w + c;
            if (!river[i] || catchment[i] < threshold * 4f
                    || (landforms[i] & TerrainMetadata.DELTA) == 0) continue;
            for (int branch = -1; branch <= 1; branch += 2) {
                for (int step = 1; step <= 5; step++) {
                    int nr = r + step;
                    int nc = c + branch * Math.max(1, step / 2);
                    if (nr >= h || nc < 0 || nc >= w) break;
                    int ni = nr * w + nc;
                    if (ocean[ni]) break;
                    river[ni] = true;
                    lake[ni] = false;
                    landforms[ni] |= TerrainMetadata.DELTA | TerrainMetadata.WETLAND;
                    float surface = Float.isFinite(water[i]) ? water[i] : elevation[i] - 1f;
                    water[ni] = surface;
                    elevation[ni] = Math.min(elevation[ni], surface - 1.2f);
                    riverWidth[ni] = (byte) Math.max(riverWidth[ni], 3);
                    biomes[ni] = BiomeClassifier.MANGROVE_SWAMP;
                }
            }
        }
    }

    private static void addOxbows(float[] elevation, float[] water, short[] biomes,
                                  boolean[] river, boolean[] lake, float[] catchment,
                                  short[] landforms, float threshold, float pixelSize,
                                  int h, int w, int originRow, int originColumn, long seed) {
        int radius = Math.max(2, Math.min(6, Math.round(42f / Math.max(5f, pixelSize))));
        int margin = radius * 2 + 3;
        for (int r = margin; r < h - margin; r++) for (int c = margin; c < w - margin; c++) {
            int i = r * w + c;
            if (!river[i] || catchment[i] < threshold * 5f
                    || (landforms[i] & TerrainMetadata.FLOODPLAIN) == 0) continue;
            long hash = mix(seed, originColumn + c, originRow + r);
            if ((hash & 1023L) != 0) continue;
            int side = ((hash >>> 11) & 1L) == 0 ? -1 : 1;
            int centerC = c + side * (radius + 2);
            float surface = water[i];
            if (!Float.isFinite(surface)) continue;
            for (int dr = -radius; dr <= radius; dr++) for (int dc = -radius; dc <= radius; dc++) {
                float distance = (float) Math.sqrt(dr * dr + dc * dc);
                if (distance < radius - 1.35f || distance > radius + 0.35f) continue;
                int ni = (r + dr) * w + centerC + dc;
                if (river[ni]) continue;
                lake[ni] = true;
                landforms[ni] |= TerrainMetadata.OXBOW | TerrainMetadata.WETLAND;
                water[ni] = surface;
                elevation[ni] = Math.min(elevation[ni], surface - 1.1f);
                biomes[ni] = RIVER;
            }
        }
    }

    private static void chooseInfrastructureSites(boolean[] river, float[] catchment,
                                                  int[] downstream, byte[] riverWidth,
                                                  short[] landforms, float threshold, int h, int w,
                                                  int originRow, int originColumn, long seed) {
        for (int r = 3; r < h - 3; r++) for (int c = 3; c < w - 3; c++) {
            int i = r * w + c;
            if (!river[i] || catchment[i] < threshold * 3f) continue;
            long hash = mix(seed ^ 0x3e5a91d7L, originColumn + c, originRow + r);
            int width = Byte.toUnsignedInt(riverWidth[i]);
            if (width >= 3 && width <= 13 && downstream[i] >= 0
                    && (landforms[i] & (TerrainMetadata.WATERFALL | TerrainMetadata.DELTA)) == 0
                    && (hash & 2047L) == 0) {
                int target = downstream[i];
                int flowR = target / w - r;
                int flowC = target % w - c;
                int extent = Math.min(9, width / 2 + 3);
                // The bridge axis is perpendicular to downstream flow. A second
                // parallel row prevents diagonal crossings from becoming dotted.
                for (int offset = -extent; offset <= extent; offset++) {
                    for (int thickness = 0; thickness <= 1; thickness++) {
                        int nr = Math.abs(flowR) >= Math.abs(flowC) ? r + thickness : r + offset;
                        int nc = Math.abs(flowR) >= Math.abs(flowC) ? c + offset : c + thickness;
                        if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                        landforms[nr * w + nc] |= TerrainMetadata.BRIDGE_SITE;
                    }
                }
            }
            if ((landforms[i] & TerrainMetadata.ESTUARY) != 0 && (hash & 1023L) == 1) {
                landforms[i] |= TerrainMetadata.PORT_SITE;
            }
        }
    }

    private static int[] distanceToMask(boolean[] mask, int h, int w, int maximum) {
        int[] distance = new int[mask.length];
        Arrays.fill(distance, maximum + 1);
        int[] queue = new int[mask.length];
        int head = 0, tail = 0;
        for (int i = 0; i < mask.length; i++) if (mask[i]) {
            distance[i] = 0;
            queue[tail++] = i;
        }
        while (head < tail) {
            int i = queue[head++], d = distance[i];
            if (d >= maximum) continue;
            int r = i / w, c = i % w;
            for (int k = 0; k < 4; k++) {
                int nr = r + DR[k], nc = c + DC[k];
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

    private static int[] distanceToCombinedMask(boolean[] a, boolean[] b, boolean[] c,
                                                int h, int w, int maximum) {
        boolean[] combined = new boolean[a.length];
        for (int i = 0; i < combined.length; i++) combined[i] = a[i] || b[i] || c[i];
        return distanceToMask(combined, h, w, maximum);
    }

    private static float localSlope(float[] elevation, int r, int c, int h, int w, float pixelSize) {
        int left = r * w + Math.max(0, c - 1), right = r * w + Math.min(w - 1, c + 1);
        int up = Math.max(0, r - 1) * w + c, down = Math.min(h - 1, r + 1) * w + c;
        float dx = (elevation[right] - elevation[left]) / Math.max(1f, pixelSize * 2f);
        float dz = (elevation[down] - elevation[up]) / Math.max(1f, pixelSize * 2f);
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private static long mix(long seed, int x, int z) {
        long value = seed ^ x * 0x632be59bd9b4e019L ^ z * 0x9e3779b97f4a7c15L;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    /**
     * Builds Minecraft-friendly longitudinal river profiles. Surfaces are
     * quantized to one block, never rise downstream, and sit one complete
     * block below the original valley floor so source blocks cannot spill
     * sideways across level ground.
     */
    private static float[] regularizeRiverSurfaces(float[] elevation, float[] spill,
                                                    boolean[] centerline, int[] downstream,
                                                    boolean[] drainsToOcean,
                                                    float blockHeightMeters) {
        int n = elevation.length;
        float[] result = new float[n];
        Arrays.fill(result, Float.NaN);
        for (int i = 0; i < n; i++) {
            if (!centerline[i]) continue;
            float safeSurface = Math.min(elevation[i], spill[i]) - blockHeightMeters * 1.05f;
            float quantized = (float) (Math.floor(safeSurface / blockHeightMeters) * blockHeightMeters);
            // Sea-bound rivers cannot descend below their receiving ocean.
            // Low coastal reaches become level estuaries at exactly 0 m.
            result[i] = drainsToOcean[i] ? Math.max(0f, quantized) : quantized;
        }

        int[] upstreamCount = new int[n];
        for (int i = 0; i < n; i++) {
            int target = downstream[i];
            if (centerline[i] && target >= 0 && centerline[target]) upstreamCount[target]++;
        }
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) if (centerline[i] && upstreamCount[i] == 0) queue[tail++] = i;
        while (head < tail) {
            int source = queue[head++];
            int target = downstream[source];
            if (target < 0 || !centerline[target]) continue;
            result[target] = Math.min(result[target], result[source]);
            if (--upstreamCount[target] == 0) queue[tail++] = target;
        }
        return result;
    }

    private static boolean[] drainsToOcean(int[] downstream, boolean[] ocean) {
        boolean[] result = ocean.clone();
        byte[] state = new byte[downstream.length]; // 0 unknown, 1 current path, 2 resolved
        int[] path = new int[downstream.length];
        for (int start = 0; start < downstream.length; start++) {
            if (ocean[start]) { state[start] = 2; continue; }
            if (state[start] == 2) continue;
            int length = 0, current = start;
            while (current >= 0 && !ocean[current] && state[current] == 0) {
                state[current] = 1;
                path[length++] = current;
                current = downstream[current];
            }
            boolean reachesOcean = current >= 0 && (ocean[current] || result[current]);
            for (int j = length - 1; j >= 0; j--) {
                int i = path[j];
                result[i] = reachesOcean;
                state[i] = 2;
            }
        }
        return result;
    }

    private static float runoffForBiome(short biome) {
        return switch (biome) {
            case 5, 26 -> 0.10f; // desert, badlands
            case 17 -> 0.35f; // savanna
            case 6, 8, 23 -> 1.35f; // swamp, forest, jungle
            case 3, 16, 32, 33, 116 -> 1.15f;
            default -> 0.75f;
        };
    }

    private static float transmissionForBiome(short biome, float pixelSizeMeters) {
        // Retention is defined per kilometre, not per raster cell. Applying
        // 0.985 every 15 m erased ~96% of a river over 3.3 km and prevented
        // tributaries from ever becoming a substantial trunk channel.
        float retentionPerKilometre = switch (biome) {
            case 5, 26 -> 0.82f;
            case 17 -> 0.92f;
            default -> 0.995f;
        };
        return (float) Math.pow(retentionPerKilometre, pixelSizeMeters / 1000.0f);
    }

    private static boolean isSnowBiome(short biome) {
        return biome == 3 || biome == 16 || biome == 32 || biome == 33 || biome == 48 || biome == 116;
    }

    /**
     * Prevents quart-biome checkerboards from freezing isolated water columns.
     * Lakes freeze as complete connected bodies only when at least 80% of their
     * source biome is cold. Rivers require both a cold center and a strong cold
     * majority over a broad neighborhood, producing one stable climatic front.
     */
    private static boolean[] coherentFrozenWater(short[] biomes, boolean[] river,
                                                  boolean[] lake, int h, int w) {
        int n = biomes.length;
        boolean[] frozen = new boolean[n];
        boolean[] visited = new boolean[n];
        int[] component = new int[n];
        int[] queue = new int[n];

        for (int start = 0; start < n; start++) {
            if (!lake[start] || visited[start]) continue;
            int head = 0, tail = 0, componentSize = 0, cold = 0;
            visited[start] = true;
            queue[tail++] = start;
            while (head < tail) {
                int i = queue[head++];
                component[componentSize++] = i;
                if (isSnowBiome(biomes[i])) cold++;
                int r = i / w, c = i % w;
                for (int k = 0; k < 4; k++) {
                    int nr = r + DR[k], nc = c + DC[k];
                    if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                    int ni = nr * w + nc;
                    if (lake[ni] && !visited[ni]) {
                        visited[ni] = true;
                        queue[tail++] = ni;
                    }
                }
            }
            if (cold * 5 >= componentSize * 4) {
                for (int j = 0; j < componentSize; j++) frozen[component[j]] = true;
            }
        }

        int[] coldIntegral = new int[(h + 1) * (w + 1)];
        int stride = w + 1;
        for (int r = 0; r < h; r++) {
            int rowCold = 0;
            for (int c = 0; c < w; c++) {
                if (isSnowBiome(biomes[r * w + c])) rowCold++;
                coldIntegral[(r + 1) * stride + c + 1] = coldIntegral[r * stride + c + 1] + rowCold;
            }
        }
        int radius = 10;
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) {
            int i = r * w + c;
            if (!river[i] || lake[i] || !isSnowBiome(biomes[i])) continue;
            int r0 = Math.max(0, r - radius), r1 = Math.min(h, r + radius + 1);
            int c0 = Math.max(0, c - radius), c1 = Math.min(w, c + radius + 1);
            int cold = coldIntegral[r1 * stride + c1] - coldIntegral[r0 * stride + c1]
                    - coldIntegral[r1 * stride + c0] + coldIntegral[r0 * stride + c0];
            int area = (r1 - r0) * (c1 - c0);
            frozen[i] = cold * 4 >= area * 3;
        }
        return frozen;
    }

    private static void applyRiparianBiomes(short[] biomes, boolean[] river, boolean[] lake, int h, int w) {
        int[] distance = new int[biomes.length]; Arrays.fill(distance, Integer.MAX_VALUE);
        int[] queue = new int[biomes.length];
        int head = 0, tail = 0;
        for (int i = 0; i < biomes.length; i++) if (river[i] || lake[i]) {
            distance[i] = 0; queue[tail++] = i;
        }
        while (head < tail) {
            int i = queue[head++], d = distance[i];
            if (d >= 5) continue;
            int r = i / w, c = i % w;
            for (int k = 0; k < 4; k++) {
                int nr = r + DR[k], nc = c + DC[k];
                if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                int ni = nr * w + nc;
                if (distance[ni] > d + 1) { distance[ni] = d + 1; queue[tail++] = ni; }
            }
        }
        for (int i = 0; i < biomes.length; i++) {
            if (distance[i] == 0 || distance[i] > 5) continue;
            if (biomes[i] == 5 || biomes[i] == 26) biomes[i] = distance[i] <= 2 ? (short) 17 : (short) 1;
            else if (biomes[i] == 1 || biomes[i] == 17 || biomes[i] == 31) biomes[i] = 108;
            else if (biomes[i] == 115) biomes[i] = 15;
        }
    }

    private static LocalTerrainProvider.HeightmapData crop(float[] elevation, short[] biomes, float[] water,
                                                            boolean[] lake, short[] landforms, byte[] geology,
                                                            byte[] soilDepth, byte[] riverWidth,
                                                            int h, int w, int p) {
        int oh = h - 2 * p, ow = w - 2 * p;
        short[][] outHeight = new short[oh][ow], outBiome = new short[oh][ow], outWater = new short[oh][ow];
        boolean[][] outLake = new boolean[oh][ow];
        short[][] outLandforms = new short[oh][ow];
        byte[][] outGeology = new byte[oh][ow], outSoil = new byte[oh][ow], outWidth = new byte[oh][ow];
        for (int r = 0; r < oh; r++) for (int c = 0; c < ow; c++) {
            int i = (r + p) * w + c + p;
            outHeight[r][c] = clampShort(elevation[i]); outBiome[r][c] = biomes[i];
            outWater[r][c] = Float.isFinite(water[i]) ? clampShort(water[i]) : DRY;
            outLake[r][c] = lake[i];
            outLandforms[r][c] = landforms[i];
            outGeology[r][c] = geology[i];
            outSoil[r][c] = soilDepth[i];
            outWidth[r][c] = riverWidth[i];
        }
        return new LocalTerrainProvider.HeightmapData(outHeight, outBiome, outWater, outLake,
                outLandforms, outGeology, outSoil, outWidth, ow, oh);
    }

    private static short clampShort(float value) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, (int) Math.floor(value)));
    }
}
