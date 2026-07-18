package th.in.midnight_network.immensa.pipeline;

import th.in.midnight_network.immensa.config.ImmensaConfig;
import th.in.midnight_network.immensa.infinitetensor.FloatTensor;
import th.in.midnight_network.immensa.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import java.util.List;
import java.util.Collections;

/**
 * Provides terrain heightmap and biome data from the local WorldPipeline.
 *
 * <p>When scale=1 the pipeline is sampled at native model resolution directly.
 * When scale>1 the pipeline is sampled at native resolution and the result is
 * bilinearly upsampled, giving 1 block = nativeResolution/scale.
 */
public final class LocalTerrainProvider {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);

    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();
    /** Bump whenever deterministic regional elevation changes invalidate disk tiles. */
    private static final int TERRAIN_ALGORITHM_VERSION = 10;

    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 1f/24f, 3, 2f, 0.5f);
    private static final FastNoiseLite ELEV_NOISE_FINE   = makeFnl(88888, 1f/6f,  2, 2f, 0.6f);

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static final class HeightmapData {
        public final short[][] heightmap;
        public final short[][] biomeIds;
        /** Terrain-space water elevation, or Short.MIN_VALUE for dry columns. */
        public final short[][] waterSurface;
        public final boolean[][] lakeMask;
        /** Bit flags from {@link TerrainMetadata}. */
        public final short[][] landforms;
        /** Geological province from {@link TerrainMetadata}. */
        public final byte[][] geology;
        /** Approximate biome-aware topsoil depth in blocks. */
        public final byte[][] soilDepth;
        /** Approximate full river width in blocks; zero away from channels. */
        public final byte[][] riverWidth;
        public final int width;
        public final int height;

        public HeightmapData(short[][] heightmap, short[][] biomeIds, int width, int height) {
            this(heightmap, biomeIds, emptyWaterSurface(height, width), new boolean[height][width],
                    new short[height][width], new byte[height][width], new byte[height][width],
                    new byte[height][width], width, height);
        }

        public HeightmapData(short[][] heightmap, short[][] biomeIds, short[][] waterSurface,
                             boolean[][] lakeMask, int width, int height) {
            this(heightmap, biomeIds, waterSurface, lakeMask, new short[height][width],
                    new byte[height][width], new byte[height][width], new byte[height][width], width, height);
        }

        public HeightmapData(short[][] heightmap, short[][] biomeIds, short[][] waterSurface,
                             boolean[][] lakeMask, short[][] landforms, byte[][] geology,
                             byte[][] soilDepth, byte[][] riverWidth, int width, int height) {
            this.heightmap = heightmap;
            this.biomeIds  = biomeIds;
            this.waterSurface = waterSurface;
            this.lakeMask = lakeMask;
            this.landforms = landforms;
            this.geology = geology;
            this.soilDepth = soilDepth;
            this.riverWidth = riverWidth;
            this.width     = width;
            this.height    = height;
        }

        private static short[][] emptyWaterSurface(int height, int width) {
            short[][] result = new short[height][width];
            for (short[] row : result) java.util.Arrays.fill(row, Short.MIN_VALUE);
            return result;
        }
    }

    private static record CacheKey(int i1, int j1, int i2, int j2) {}
    private static record CacheEntry(HeightmapData data, AtomicLong lastAccessed) {}
    private static record RawTile(HeightmapData data, int padding, float pixelSizeMeters,
                                  long newlyComputedWindows, long inferenceNanos, long epoch) {}

    private static final int MAX_CACHE_SIZE_HEADROOM = 8;
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();
    private static final AtomicLong GENERATION_EPOCH = new AtomicLong();
    private static final Map<CacheKey, CompletableFuture<HeightmapData>> PENDING = new ConcurrentHashMap<>();
    private static final Set<CacheKey> PREFETCH_CENTERS = ConcurrentHashMap.newKeySet();
    /**
     * Spawn preparation already requests every tile it needs. Speculative work at
     * that point competes with the tiles blocking the loading screen and can be
     * multiplied by structure packs that probe many candidate chunks.
     */
    private static volatile boolean SERVER_READY;
    /** InfiniteTensor mutation remains serialized; the bounded queue prevents runaway chunk requests. */
    private static final BoundedPriorityExecutor INFERENCE_EXECUTOR = new BoundedPriorityExecutor(
            1, ImmensaConfig.inferenceQueueSize(), "immensa-inference");
    private static final Executor PREFETCH_INFERENCE_EXECUTOR = INFERENCE_EXECUTOR::executeLowPriority;
    /** CPU-only biome/hydrology work overlaps the next GPU inference and scales across cores. */
    private static final ExecutorService POSTPROCESS_EXECUTOR = boundedExecutor(
            ImmensaConfig.postprocessThreads(),
            ImmensaConfig.postprocessThreads() * 4,
            "immensa-postprocess", true);
    /** Reads have their own lane so a backlog of optional writes cannot delay gameplay. */
    private static final ExecutorService CACHE_READ_EXECUTOR = boundedExecutor(
            2, 64, "immensa-cache-read", true);
    /** Disk persistence is best effort; dropping a saturated write only causes a later recompute. */
    private static final ExecutorService CACHE_WRITE_EXECUTOR = bestEffortExecutor(
            1, 32, "immensa-cache-write");

    private static volatile LocalTerrainProvider INSTANCE;
    private static volatile long instanceSeed;

    private final WorldPipeline pipeline;

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
    }

    /** Seed is 64-bit world seed. Creates provider once; later worlds only update seed and clear caches (lightweight). */
    public static synchronized void init(long seed) {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) throw new IllegalStateException("PipelineModels failed to load");
        if (INSTANCE == null) {
            INSTANCE = new LocalTerrainProvider(seed, models);
            instanceSeed = seed;
        } else if (instanceSeed != seed) {
            invalidatePendingWork();
            INSTANCE.pipeline.setSeed(seed);
            instanceSeed = seed;
        }
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE != null) return INSTANCE;

        synchronized(INIT_LOCK) {
            if (INSTANCE != null) return INSTANCE;
            PipelineModels.awaitLoad();
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) throw new IllegalStateException("PipelineModels failed to load");
            INSTANCE = new LocalTerrainProvider(0L, models);
            instanceSeed = 0L;
        }

        return INSTANCE;
    }

    public static void clearCache() {
        SERVER_READY = false;
        invalidatePendingWork();
    }

    /** Enables predictive prefetch after Minecraft has finished preparing spawn. */
    public static void markServerReady() {
        SERVER_READY = true;
    }

    /** Disables speculative requests while a server is stopping or bootstrapping. */
    public static void markServerNotReady() {
        SERVER_READY = false;
    }

    // =========================================================================
    // Explorer API — all pipeline calls routed through INFERENCE_EXECUTOR
    // =========================================================================

    /** Returns the current world seed used by the pipeline. */
    public static long getSeed() {
        return instanceSeed;
    }

    /**
     * Rejects unstable surface-structure sites when the tile is already available.
     *
     * <p>Structure placement runs before noise generation and third-party structure
     * packs may probe candidates far outside the spawn region. Generating an AI tile
     * from this optional validation hook caused world creation to fan out without a
     * useful bound. A cache miss therefore means "do not veto"; normal terrain
     * generation will produce the tile when its chunk is actually needed.</p>
     */
    public boolean isStructureSiteSuitable(int worldX, int worldZ) {
        int tileSize = ImmensaConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        int tileX = (worldX >> shift) << shift;
        int tileZ = (worldZ >> shift) << shift;
        CacheKey key = new CacheKey(tileZ, tileX, tileZ + tileSize, tileX + tileSize);
        CacheEntry cached = CACHE.get(key);
        if (cached == null) return true;
        cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
        HeightmapData data = cached.data;
        int cx = Math.max(0, Math.min(data.width - 1, worldX - tileX));
        int cz = Math.max(0, Math.min(data.height - 1, worldZ - tileZ));
        if (data.heightmap[cz][cx] <= 0) return true;
        short forbidden = TerrainMetadata.WETLAND | TerrainMetadata.DELTA
                | TerrainMetadata.WATERFALL | TerrainMetadata.OXBOW
                | TerrainMetadata.GREAT_RIVER;
        int suitable = 0, sampled = 0;
        short minimum = Short.MAX_VALUE, maximum = Short.MIN_VALUE;
        for (int dz = -12; dz <= 12; dz += 4) for (int dx = -12; dx <= 12; dx += 4) {
            int z = Math.max(0, Math.min(data.height - 1, cz + dz));
            int x = Math.max(0, Math.min(data.width - 1, cx + dx));
            short flags = data.landforms[z][x];
            if ((flags & TerrainMetadata.STRUCTURE_SUITABLE) != 0 && (flags & forbidden) == 0) suitable++;
            minimum = (short) Math.min(minimum, data.heightmap[z][x]);
            maximum = (short) Math.max(maximum, data.heightmap[z][x]);
            sampled++;
        }
        return suitable * 10 >= sampled * 6 && maximum - minimum <= 120;
    }

    /**
     * Run elevation and climate inference on the inference thread.
     *
     * @return float[2]: [0] = elev (H*W), [1] = climate (5*H*W, or null)
     */
    public static float[][] getPipelineData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.get(i1, j1, i2, j2, withClimate));
    }

    /**
     * Fetch a coarse tensor slice on the inference thread.
     * Coordinates are in coarse index units (1 unit = 256 native pixels).
     *
     * @return FloatTensor with shape [7, ci1-ci0, cj1-cj0]
     */
    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    /**
     * Change the world seed used by the pipeline and clear all caches.
     * Note: this also affects terrain generation for new Minecraft chunks.
     */
    public static void changeSeedFromExplorer(long newSeed) throws Exception {
        invalidatePendingWork();
        submitToInferenceThread(() -> {
            LocalTerrainProvider provider = getInstance();
            provider.pipeline.setSeed(newSeed);
            instanceSeed = newSeed;
            return null;
        });
    }

    /** Change to a random new seed; returns the new seed value. */
    public static long generateRandomSeedFromExplorer() throws Exception {
        long newSeed = new Random().nextLong();
        changeSeedFromExplorer(newSeed);
        return newSeed;
    }

    private static <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        return INFERENCE_EXECUTOR.submit(task).get();
    }

    /**
     * Fetch heightmap for a block-coordinate region (i=Z, j=X).
     * Coordinates are in block space; scale from config determines blocks per native pixel.
     * Blocks the calling thread until the tile is ready (one tile can take 10–30+ seconds).
     * If the caller is the server or a chunk worker, the game will stall until this returns.
     */
    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        try {
            return fetchHeightmapAsync(i1, j1, i2, j2).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException("Terrain tile failed", cause);
        }
    }

    /** Non-blocking tile request; identical concurrent requests share the same future. */
    public CompletableFuture<HeightmapData> fetchHeightmapAsync(int i1, int j1, int i2, int j2) {
        return fetchHeightmapAsync(i1, j1, i2, j2, true);
    }

    /**
     * Starts the tile containing a block without blocking the caller. Chunk-status
     * futures use this to release Minecraft's worker while inference and regional
     * post-processing run on the mod's bounded executors.
     */
    public CompletableFuture<HeightmapData> prewarmBlockAsync(int blockX, int blockZ) {
        int tileSize = ImmensaConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int blockStartX = (blockX >> tileShift) << tileShift;
        int blockStartZ = (blockZ >> tileShift) << tileShift;
        return fetchHeightmapAsync(
                blockStartZ, blockStartX,
                blockStartZ + tileSize, blockStartX + tileSize);
    }

    private CompletableFuture<HeightmapData> fetchHeightmapAsync(
            int i1, int j1, int i2, int j2, boolean gameplayRequest) {
        CacheKey key = new CacheKey(i1, j1, i2, j2);
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
            return CompletableFuture.completedFuture(cached.data);
        }

        CompletableFuture<HeightmapData> future = PENDING.computeIfAbsent(
                key, ignored -> createTileFuture(key, i1, j1, i2, j2, gameplayRequest));
        // Register after computeIfAbsent returns, so even an immediately completed
        // future is removed after it has definitely entered the map.
        future.whenComplete((data, error) -> PENDING.remove(key, future));
        if (gameplayRequest && SERVER_READY && PREFETCH_CENTERS.add(key)) {
            future.thenRun(() -> prefetchNeighbors(key));
        }
        return future;
    }

    private CompletableFuture<HeightmapData> createTileFuture(
            CacheKey key, int i1, int j1, int i2, int j2, boolean gameplayRequest) {
        int scale = WorldScaleManager.getCurrentScale();
        long epoch = GENERATION_EPOCH.get();
        int cacheVariant = cacheVariant(scale);
        int regionWidth = j2 - j1;
        int regionHeight = i2 - i1;

        CompletableFuture<HeightmapData> diskLookup = ImmensaConfig.persistentTileCache()
                ? CompletableFuture.supplyAsync(
                        () -> TerrainTileDiskCache.load(instanceSeed, cacheVariant, i1, j1, i2, j2),
                        CACHE_READ_EXECUTOR)
                : CompletableFuture.completedFuture(null);
        return diskLookup.thenCompose(cached -> {
            if (cached != null && epoch == GENERATION_EPOCH.get()) {
                CACHE.put(key, new CacheEntry(cached, new AtomicLong(CACHE_CLOCK.incrementAndGet())));
                evictLruTo(ImmensaConfig.terrainCacheTiles());
                LOG.debug("Terrain Diffusion loaded {}x{} tile ({}, {}) from disk",
                        regionWidth, regionHeight, j1, i1);
                return CompletableFuture.completedFuture(cached);
            }
            return generateTileFuture(key, i1, j1, i2, j2, scale, cacheVariant, epoch, gameplayRequest);
        });
    }

    private CompletableFuture<HeightmapData> generateTileFuture(
            CacheKey key, int i1, int j1, int i2, int j2,
            int scale, int cacheVariant, long epoch, boolean gameplayRequest) {
        int regionWidth = j2 - j1;
        int regionHeight = i2 - i1;
        LOG.info("Terrain Diffusion ({}) queued uncached region: ({}, {})-({}, {}) size {}x{}",
                OnnxModel.getResolvedInferenceProvider(), j1, i1, j2, i2, regionWidth, regionHeight);

        CompletableFuture<RawTile> inference = CompletableFuture.supplyAsync(() -> {
            if (epoch != GENERATION_EPOCH.get()) {
                throw new CancellationException("Discarding stale queued terrain inference");
            }
            long inferenceStart = System.nanoTime();
            long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
            int hydrologyPadding = ImmensaConfig.hydrologyEnabled()
                    ? ImmensaConfig.hydrologyPadding() : 0;
            HeightmapData expanded = scale <= 1
                    ? handle1x(i1 - hydrologyPadding, j1 - hydrologyPadding,
                              i2 + hydrologyPadding, j2 + hydrologyPadding)
                    : handleUpsampled(i1 - hydrologyPadding, j1 - hydrologyPadding,
                                      i2 + hydrologyPadding, j2 + hydrologyPadding, scale);
            long computedWindowCountAfter = pipeline.getTotalComputedWindowCount();
            return new RawTile(expanded, hydrologyPadding, NATIVE_RESOLUTION / scale,
                    computedWindowCountAfter - computedWindowCountBefore,
                    System.nanoTime() - inferenceStart, epoch);
        }, gameplayRequest ? INFERENCE_EXECUTOR : PREFETCH_INFERENCE_EXECUTOR);

        return inference.thenApplyAsync(raw -> {
            if (raw.epoch != GENERATION_EPOCH.get()) {
                throw new CancellationException("World seed or terrain cache changed during generation");
            }
            long postprocessStart = System.nanoTime();
            float minimumCatchment = effectiveMinCatchmentKm2(raw.pixelSizeMeters);
            HeightmapData data = raw.padding == 0 ? raw.data : HydrologyProcessor.process(
                    raw.data, raw.padding, raw.pixelSizeMeters,
                    minimumCatchment,
                    ImmensaConfig.hydrologyMinLakeAreaKm2(),
                    ImmensaConfig.hydrologyMaxLakeDepthMeters(),
                    i1 - raw.padding, j1 - raw.padding, instanceSeed);
            if (raw.epoch != GENERATION_EPOCH.get()) {
                throw new CancellationException("World seed or terrain cache changed during post-processing");
            }
            LOG.info(
                    "Terrain Diffusion ({}) finished {}x{}: inference {} ms, post-process {} ms ({} new windows)",
                    OnnxModel.getResolvedInferenceProvider(), regionWidth, regionHeight,
                    TimeUnit.NANOSECONDS.toMillis(raw.inferenceNanos),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - postprocessStart),
                    raw.newlyComputedWindows);
            CACHE.put(key, new CacheEntry(data, new AtomicLong(CACHE_CLOCK.incrementAndGet())));
            evictLruTo(ImmensaConfig.terrainCacheTiles());
            if (ImmensaConfig.persistentTileCache()) {
                long seed = instanceSeed;
                CACHE_WRITE_EXECUTOR.execute(
                        () -> TerrainTileDiskCache.store(seed, cacheVariant, i1, j1, i2, j2, data));
            }
            return data;
        }, POSTPROCESS_EXECUTOR);
    }

    private void prefetchNeighbors(CacheKey center) {
        int radius = ImmensaConfig.prefetchRadius();
        if (radius <= 0) return;
        int tileHeight = center.i2 - center.i1;
        int tileWidth = center.j2 - center.j1;
        for (int ring = 1; ring <= radius; ring++) {
            for (int dr = -ring; dr <= ring; dr++) for (int dc = -ring; dc <= ring; dc++) {
                if (Math.max(Math.abs(dr), Math.abs(dc)) != ring) continue;
                if (PENDING.size() >= ImmensaConfig.prefetchQueueLimit()) return;
                CacheKey neighbor = new CacheKey(center.i1 + dr * tileHeight, center.j1 + dc * tileWidth,
                        center.i2 + dr * tileHeight, center.j2 + dc * tileWidth);
                if (CACHE.containsKey(neighbor) || PENDING.containsKey(neighbor)) continue;
                fetchHeightmapAsync(neighbor.i1, neighbor.j1, neighbor.i2, neighbor.j2, false)
                        .exceptionally(error -> null);
            }
        }
    }

    private static int cacheVariant(int scale) {
        int hash = 31 * TERRAIN_ALGORITHM_VERSION + scale;
        hash = 31 * hash + ImmensaConfig.hydrologyPadding();
        hash = 31 * hash + Float.floatToIntBits(ImmensaConfig.hydrologyMinCatchmentKm2());
        hash = 31 * hash + Float.floatToIntBits(ImmensaConfig.hydrologyMinLakeAreaKm2());
        hash = 31 * hash + Float.floatToIntBits(ImmensaConfig.hydrologyMaxLakeDepthMeters());
        hash = 31 * hash + Boolean.hashCode(ImmensaConfig.hydrologyScaleDensity());
        hash = 31 * hash + Float.floatToIntBits(
                ImmensaConfig.hydrologyRiverWidthMultiplier());
        hash = 31 * hash + ImmensaConfig.hydrologyNormalRiverMinWidthBlocks();
        hash = 31 * hash + ImmensaConfig.hydrologyNormalRiverMaxWidthBlocks();
        hash = 31 * hash + Boolean.hashCode(
                ImmensaConfig.hydrologyGreatRiversEnabled());
        hash = 31 * hash + Float.floatToIntBits(
                ImmensaConfig.hydrologyGreatRiverCatchmentMultiplier());
        hash = 31 * hash + ImmensaConfig.hydrologyGreatRiverMinWidthBlocks();
        hash = 31 * hash + ImmensaConfig.hydrologyGreatRiverMaxWidthBlocks();
        return hash;
    }

    static float effectiveMinCatchmentKm2(float pixelSizeMeters) {
        float configured = ImmensaConfig.hydrologyMinCatchmentKm2();
        return HydrologyProcessor.scaleAdjustedMinCatchmentKm2(
                configured, pixelSizeMeters, ImmensaConfig.hydrologyScaleDensity());
    }

    private static void evictLruTo(int maxSize) {
        int headroomHalf = MAX_CACHE_SIZE_HEADROOM / 2;
        if (CACHE.size() > maxSize + headroomHalf) {
            CACHE.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed.get()))
                .limit(MAX_CACHE_SIZE_HEADROOM)
                .map(Map.Entry::getKey)
                .forEach(CACHE::remove);
        }
    }

    // =========================================================================
    // Scale == 1: block coords == native pixel coords
    // =========================================================================

    private HeightmapData handle1x(int i1, int j1, int i2, int j2) {
        int H = i2 - i1, W = j2 - j1;
        int paddedH = H + 2, paddedW = W + 2;
        float[][] out = pipeline.get(i1 - 1, j1 - 1, i2 + 1, j2 + 1, true);
        float[] elevPadded = out[0];
        float[] elevFlat = cropChannels(elevPadded, 1, paddedH, paddedW, H, W);
        int climateChannels = out[1] == null ? 0 : out[1].length / (paddedH * paddedW);
        float[] climate = out[1] == null ? null
                : cropChannels(out[1], climateChannels, paddedH, paddedW, H, W);

        RegionalTerrainProcessor.Result regional = RegionalTerrainProcessor.process(
                elevFlat, climate, i1, j1, H, W, NATIVE_RESOLUTION, instanceSeed);
        float[] processedPadded = padEdges(regional.elevation(), H, W);
        short[] biomeFlat = BiomeClassifier.classify(regional.elevation(), regional.climate(),
                i1, j1, processedPadded, H, W, NATIVE_RESOLUTION);
        BiomeClassifier.applyRegionalTransitions(biomeFlat, regional.elevation(), regional.climate(),
                regional.landforms(), regional.geology(), H, W);
        return buildHeightmapData(regional, biomeFlat, H, W);
    }

    // =========================================================================
    // Scale > 1: pipeline at native res → bilinear upsample to block res
    // =========================================================================

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        int H = i2 - i1, W = j2 - j1;
        float pixelSizeM = NATIVE_RESOLUTION / scale;

        // Convert block coords to native pixel coords
        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);

        // 2-pixel native padding (1 for bilinear + 1 for slope)
        int i1p = i1n - 2, j1p = j1n - 2;
        int i2p = i2n + 2, j2p = j2n + 2;
        int nH = i2p - i1p, nW = j2p - j1p;

        float[][] out = pipeline.get(i1p, j1p, i2p, j2p, true);
        float[] elevNativeFlat    = out[0];
        float[] climateNativeFlat = out[1];

        // Crop offsets in the upsampled array
        int padUp   = 2 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1  = padUp + offsetI;
        int cropJ1  = padUp + offsetJ;

        int upH = nH * scale, upW = nW * scale;
        float[] elevSmooth = LaplacianUtils.bilinearResizeCrop(
                elevNativeFlat, 0, nH, nW, upH, upW,
                cropI1, cropJ1, H, W);
        float[] elevPadded = LaplacianUtils.bilinearResizeCrop(
                elevNativeFlat, 0, nH, nW, upH, upW,
                cropI1 - 1, cropJ1 - 1, H + 2, W + 2);

        // Upsample climate (4, nH, nW) → (4, H, W)
        float[] climate = upsampleClimate(
                climateNativeFlat, nH, nW, cropI1, cropJ1, H, W, upH, upW);

        float[] elevOut = addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);

        RegionalTerrainProcessor.Result regional = RegionalTerrainProcessor.process(
                elevOut, climate, i1, j1, H, W, pixelSizeM, instanceSeed);
        float[] processedPadded = padEdges(regional.elevation(), H, W);
        short[] biomeFlat = BiomeClassifier.classify(regional.elevation(), regional.climate(),
                i1, j1, processedPadded, H, W, pixelSizeM);
        BiomeClassifier.applyRegionalTransitions(biomeFlat, regional.elevation(), regional.climate(),
                regional.landforms(), regional.geology(), H, W);
        return buildHeightmapData(regional, biomeFlat, H, W);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private float[] addElevationNoise(float[] elevSmooth, float[] elevPadded,
                                       int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = elevSmooth.clone();
        float normFactor = 40f * pixelSizeM / NATIVE_RESOLUTION;
        float ampC = 100f * pixelSizeM / NATIVE_RESOLUTION;
        float ampF = 70f  * pixelSizeM / NATIVE_RESOLUTION;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];
                if (e < 0f) continue;

                float grad = slopeGradient[idx];
                float sf = Math.min(1f, grad / normFactor);
                sf = sf * sf * (float) Math.sqrt(sf);

                float nx = j1 + c, ny = i1 + r;
                elevOut[idx] = e
                        + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                        + ELEV_NOISE_FINE.GetNoise(nx, ny)   * ampF * sf;
            }
        }
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k/3) * pW + (c + k%3)];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }
                dx /= 8f; dy /= 8f;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        return result;
    }

    private static float[] upsampleClimate(float[] climNative, int nH, int nW,
                                            int cropI1, int cropJ1, int H, int W,
                                            int upH, int upW) {
        if (climNative == null) return null;
        float[] result = new float[4 * H * W];
        for (int ch = 0; ch < 4; ch++) {
            float[] crop = LaplacianUtils.bilinearResizeCrop(
                    climNative, ch * nH * nW, nH, nW, upH, upW,
                    cropI1, cropJ1, H, W);
            System.arraycopy(crop, 0, result, ch * H * W, crop.length);
        }
        return result;
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, short[] biomeFlat, int H, int W) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIds  = new short[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                float e = elevFlat[r * W + c];
                heightmap[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                biomeIds[r][c]  = biomeFlat[r * W + c];
            }
        return new HeightmapData(heightmap, biomeIds, W, H);
    }

    private static HeightmapData buildHeightmapData(RegionalTerrainProcessor.Result regional,
                                                     short[] biomeFlat, int H, int W) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIds = new short[H][W];
        short[][] landforms = new short[H][W];
        byte[][] geology = new byte[H][W];
        byte[][] soilDepth = new byte[H][W];
        byte[][] riverWidth = new byte[H][W];
        for (int r = 0; r < H; r++) for (int c = 0; c < W; c++) {
            int i = r * W + c;
            heightmap[r][c] = clampShort(regional.elevation()[i]);
            biomeIds[r][c] = biomeFlat[i];
            landforms[r][c] = regional.landforms()[i];
            geology[r][c] = regional.geology()[i];
            soilDepth[r][c] = regional.soilDepth()[i];
        }
        return new HeightmapData(heightmap, biomeIds, HeightmapData.emptyWaterSurface(H, W), new boolean[H][W],
                landforms, geology, soilDepth, riverWidth, W, H);
    }

    private static float[] padEdges(float[] source, int h, int w) {
        float[] padded = new float[(h + 2) * (w + 2)];
        int pw = w + 2;
        for (int r = -1; r <= h; r++) for (int c = -1; c <= w; c++) {
            int sourceR = Math.max(0, Math.min(h - 1, r));
            int sourceC = Math.max(0, Math.min(w - 1, c));
            padded[(r + 1) * pw + c + 1] = source[sourceR * w + sourceC];
        }
        return padded;
    }

    private static short clampShort(float value) {
        return (short) Math.max(Short.MIN_VALUE + 1,
                Math.min(Short.MAX_VALUE, (int) Math.floor(value)));
    }

    private static float[] cropChannels(float[] source, int channels, int sourceH, int sourceW, int H, int W) {
        if (channels == 1) {
            float[] result = new float[H * W];
            for (int r = 0; r < H; r++)
                System.arraycopy(source, (r + 1) * sourceW + 1, result, r * W, W);
            return result;
        }
        float[] result = new float[channels * H * W];
        for (int channel = 0; channel < channels; channel++)
            for (int r = 0; r < H; r++)
                System.arraycopy(source, channel * sourceH * sourceW + (r + 1) * sourceW + 1,
                        result, channel * H * W + r * W, W);
        return result;
    }

    private static ExecutorService boundedExecutor(int threads, int queueSize, String name, boolean blockOnFull) {
        AtomicLong threadId = new AtomicLong();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueSize), runnable -> {
                    Thread thread = new Thread(runnable, name + "-" + threadId.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        if (blockOnFull) {
            executor.setRejectedExecutionHandler((task, rejectedExecutor) -> {
                if (rejectedExecutor.isShutdown()) throw new RejectedExecutionException("Terrain executor is shut down");
                try {
                    rejectedExecutor.getQueue().put(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(e);
                }
            });
        } else {
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        }
        return executor;
    }

    private static ExecutorService bestEffortExecutor(int threads, int queueSize, String name) {
        AtomicLong threadId = new AtomicLong();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), runnable -> {
                    Thread thread = new Thread(runnable, name + "-" + threadId.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        return executor;
    }

    /**
     * Bounded deque executor: gameplay work enters at the front while speculative
     * prefetch enters at the back and is discarded rather than stalling callers.
     */
    private static final class BoundedPriorityExecutor extends AbstractExecutorService {
        private record PrioritizedTask(Runnable command, int priority, long sequence)
                implements Comparable<PrioritizedTask> {
            @Override public int compareTo(PrioritizedTask other) {
                int byPriority = Integer.compare(priority, other.priority);
                return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
            }
        }

        private final PriorityBlockingQueue<PrioritizedTask> queue = new PriorityBlockingQueue<>();
        private final Semaphore capacity;
        private final AtomicLong sequence = new AtomicLong();
        private final List<Thread> workers;
        private volatile boolean shutdown;

        BoundedPriorityExecutor(int threads, int capacity, String name) {
            this.capacity = new Semaphore(capacity);
            java.util.ArrayList<Thread> created = new java.util.ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                Thread worker = new Thread(this::work, name + "-" + (i + 1));
                worker.setDaemon(true);
                worker.start();
                created.add(worker);
            }
            workers = Collections.unmodifiableList(created);
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException("Terrain inference executor is shut down");
            try {
                capacity.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException(e);
            }
            if (shutdown) {
                capacity.release();
                throw new RejectedExecutionException("Terrain inference executor is shut down");
            }
            queue.add(new PrioritizedTask(command, 0, sequence.getAndIncrement()));
        }

        void executeLowPriority(Runnable command) {
            if (shutdown || !capacity.tryAcquire()) {
                throw new RejectedExecutionException("Terrain prefetch queue is full");
            }
            queue.add(new PrioritizedTask(command, 1, sequence.getAndIncrement()));
        }

        private void work() {
            while (!shutdown || !queue.isEmpty()) {
                try {
                    PrioritizedTask task = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        try {
                            task.command.run();
                        } finally {
                            capacity.release();
                        }
                    }
                } catch (InterruptedException e) {
                    if (shutdown) return;
                } catch (Throwable error) {
                    LOG.error("Uncaught terrain inference task failure", error);
                }
            }
        }

        @Override public void shutdown() { shutdown = true; workers.forEach(Thread::interrupt); }
        @Override public List<Runnable> shutdownNow() {
            shutdown();
            java.util.ArrayList<Runnable> remaining = new java.util.ArrayList<>();
            PrioritizedTask task;
            while ((task = queue.poll()) != null) {
                remaining.add(task.command);
                capacity.release();
            }
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && workers.stream().noneMatch(Thread::isAlive); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            for (Thread worker : workers) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                worker.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            return isTerminated();
        }
    }

    private static void invalidatePendingWork() {
        GENERATION_EPOCH.incrementAndGet();
        CACHE.clear();
        PREFETCH_CENTERS.clear();
        PENDING.values().forEach(future -> future.cancel(false));
        PENDING.clear();
    }
}
