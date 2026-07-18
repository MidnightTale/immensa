package th.in.midnight_network.immensa.config;

import th.in.midnight_network.immensa.platform.Platforms;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ImmensaConfig {
    private static final String FILE_NAME = "immensa.properties";
    private static final String RESOURCE_PATH = "/" + FILE_NAME;
    private static final Properties PROPERTIES = new Properties();
    private static final String BUILD_VARIANT = readBuildVariant();
    private static final boolean DEFAULT_OFFLOAD_MODELS = true;
    private static final boolean DEFAULT_VALIDATE_MODEL = true;
    private static final int DEFAULT_EXPLORER_PORT = 19801;
    private static final int DEFAULT_TILE_SIZE = 256;
    private static final int DEFAULT_HYDROLOGY_PADDING = 128;

    static {
        loadDefaults();
        Path configPath = resolveConfigPath();
        if (configPath != null) {
            loadOverrides(configPath);
        }
    }

    private ImmensaConfig() {
    }

    /** Inference device: "cpu", "gpu", or "auto" (try GPU then fall back to CPU). */
    public static String inferenceDevice() {
        String device = readString("inference.device", "auto");
        // On the CPU build "gpu" is meaningless (no dedicated GPU provider), so treat it as "auto":
        // tries CoreML on macOS, falls back to CPU elsewhere.
        if ("cpu".equals(BUILD_VARIANT)) {
            return "auto";
        }
        return device;
    }

    /** Runtime bundle type embedded in this mod jar. */
    public static String buildVariant() {
        return BUILD_VARIANT;
    }

    /** Whether to offload inactive models from VRAM between pipeline stages. */
    public static boolean offloadModels() {
        return readBoolean("inference.offload_models", DEFAULT_OFFLOAD_MODELS);
    }

    /** TCP port for the local terrain explorer HTTP server. */
    public static int explorerPort() {
        return readInt("explorer.port", DEFAULT_EXPLORER_PORT);
    }

    /** Whether to validate SHA-256 for pre-existing local model files before use. */
    public static boolean validateModel() {
        return readBoolean("validate_model", DEFAULT_VALIDATE_MODEL);
    }

    /** Initial coarse-pixel radius for spawn land search (NxN region centered at origin). */
    public static int spawnSearchInitialSize() {
        return readInt("spawn_search.initial_size", 16);
    }

    /** Maximum coarse-pixel region size for spawn land search before giving up. */
    public static int spawnSearchMaxSize() {
        return readInt("spawn_search.max_size", 128);
    }

    /** Region side length in blocks. Must be a positive power of 2 (128, 256, 512, ...). */
    public static int tileSize() {
        int configuredTileSize = readInt("tile_size", DEFAULT_TILE_SIZE);
        if (configuredTileSize <= 0 || !isPowerOfTwo(configuredTileSize)) {
            System.err.println("Invalid tile_size: " + configuredTileSize + ", using default " + DEFAULT_TILE_SIZE);
            return DEFAULT_TILE_SIZE;
        }
        return configuredTileSize;
    }

    public static boolean hydrologyEnabled() {
        return readBoolean("hydrology.enabled", true);
    }

    /** Extra block-space context used to keep drainage networks continuous across tiles. */
    public static int hydrologyPadding() {
        return Math.max(8, Math.min(512,
                readInt("hydrology.context_padding", DEFAULT_HYDROLOGY_PADDING)));
    }

    public static float hydrologyMinCatchmentKm2() {
        return Math.max(0.01f, readFloat("hydrology.min_catchment_km2", 8.0f));
    }

    /**
     * Visual width multiplier applied after physical hydraulic geometry.
     * Real-world 30 m raster channels are too thin to read as Minecraft rivers
     * without modest block-space exaggeration.
     */
    public static float hydrologyRiverWidthMultiplier() {
        return Math.max(0.5f, Math.min(3.0f,
                readFloat("hydrology.river_width_multiplier", 2.15f)));
    }

    /** Minimum bankfull width of retained ordinary rivers. */
    public static int hydrologyNormalRiverMinWidthBlocks() {
        return Math.min(hydrologyNormalRiverMaxWidthBlocks(), Math.max(8, Math.min(24,
                readInt("hydrology.normal_river_min_width_blocks", 12))));
    }

    /** Maximum bankfull width of ordinary rivers before great-river promotion. */
    public static int hydrologyNormalRiverMaxWidthBlocks() {
        return Math.max(16, Math.min(48,
                readInt("hydrology.normal_river_max_width_blocks", 32)));
    }

    /** Rare ocean-bound trunk channels that grow into basin-scale rivers. */
    public static boolean hydrologyGreatRiversEnabled() {
        return readBoolean("hydrology.great_rivers", true);
    }

    /** Drainage threshold relative to an ordinary visible river. */
    public static float hydrologyGreatRiverCatchmentMultiplier() {
        return Math.max(3.0f, Math.min(16.0f,
                readFloat("hydrology.great_river_catchment_multiplier", 6.0f)));
    }

    public static int hydrologyGreatRiverMinWidthBlocks() {
        return Math.max(32, Math.min(96,
                readInt("hydrology.great_river_min_width_blocks", 64)));
    }

    public static int hydrologyGreatRiverMaxWidthBlocks() {
        int minimum = hydrologyGreatRiverMinWidthBlocks();
        return Math.max(minimum, Math.min(120,
                readInt("hydrology.great_river_max_width_blocks", 120)));
    }

    /** Keep approximately consistent visible river density across world scales. */
    public static boolean hydrologyScaleDensity() {
        return readBoolean("hydrology.scale_density", true);
    }

    /** Minimum enclosed-water area; filters noisy puddles and tiny DEM pits. */
    public static float hydrologyMinLakeAreaKm2() {
        return Math.max(0.0f, readFloat("hydrology.min_lake_area_km2", 0.12f));
    }

    public static float hydrologyMaxLakeDepthMeters() {
        return Math.max(1.0f, Math.min(300.0f,
                readFloat("hydrology.max_lake_depth_m", 300.0f)));
    }

    public static int hydrologyRainRiseBlocks() {
        return Math.max(0, Math.min(4, readInt("hydrology.rain_lake_rise_blocks", 1)));
    }

    /** Multi-scale shore-fast ice, pack ice and floes over cold surface water. */
    public static boolean polarIceEnabled() {
        return readBoolean("polar_ice.enabled", true);
    }

    public static float polarIceFrozenOceanCoverage() {
        return Math.max(0.35f, Math.min(0.98f,
                readFloat("polar_ice.frozen_ocean_coverage", 0.86f)));
    }

    public static float polarIceColdOceanCoverage() {
        return Math.max(0.0f, Math.min(0.75f,
                readFloat("polar_ice.cold_ocean_coverage", 0.34f)));
    }

    public static int polarIceMaxThickness() {
        return Math.max(1, Math.min(4,
                readInt("polar_ice.max_thickness_blocks", 3)));
    }

    /**
     * Approximate maximum seabed depth reached by frozen-ocean pack ice.
     * Broad world noise bends this margin so it never forms one uniform ring.
     */
    public static float polarIceOffshoreReachMeters() {
        return Math.max(250f, Math.min(1_800f,
                readFloat("polar_ice.offshore_reach_meters", 850f)));
    }

    /** Replace the flat vanilla stone/deepslate layer with geological strata. */
    public static boolean dynamicStrataEnabled() {
        return readBoolean("geology.dynamic_strata", true);
    }

    /** Maximum broad vertical displacement of the stone/deepslate boundary. */
    public static int strataReliefBlocks() {
        return Math.max(8, Math.min(40,
                readInt("geology.strata_relief_blocks", 28)));
    }

    /** Half-thickness of the irregular mixed stone/deepslate transition. */
    public static int strataBlendBlocks() {
        return Math.max(3, Math.min(18,
                readInt("geology.strata_blend_blocks", 10)));
    }

    /** True three-dimensional ledges and undercuts on major exposed rock faces. */
    public static boolean cliffOverhangsEnabled() {
        return readBoolean("terrain.cliff_overhangs", true);
    }

    public static float cliffOverhangStrength() {
        return Math.max(0.25f, Math.min(1.75f,
                readFloat("terrain.cliff_overhang_strength", 1.0f)));
    }

    /** Multi-scale caves carved directly into the Terrain Diffusion density field. */
    public static boolean cavesEnabled() {
        return readBoolean("caves.enabled", true);
    }

    /** Controls cave volume without changing the size of the terrain itself. */
    public static float caveAbundance() {
        return Math.max(0.25f, Math.min(2.0f, readFloat("caves.abundance", 1.0f)));
    }

    public static boolean caveTunnelsEnabled() {
        return readBoolean("caves.tunnels", true);
    }

    public static boolean massiveCavernsEnabled() {
        return readBoolean("caves.massive_caverns", true);
    }

    public static boolean surfaceCaveEntrancesEnabled() {
        return readBoolean("caves.surface_entrances", true);
    }

    /**
     * Remove the generator's global below-sea fluid fill from underground voids.
     * Feature-placed springs and pools run later and are intentionally preserved.
     */
    public static boolean drainBaseCaveFluids() {
        return readBoolean("caves.drain_base_fluids", true);
    }

    /** Place sparse floor-following water and lava basins after blanket fluids are drained. */
    public static boolean cavePoolsEnabled() {
        return readBoolean("caves.pools", true);
    }

    /** Preserve sparse low-level lava basins while draining blanket cave fluids. */
    public static boolean deepLavaPoolsEnabled() {
        return readBoolean("caves.deep_lava_pools", true);
    }

    /** CPU workers used for biome and hydrology post-processing after serialized inference. */
    public static int postprocessThreads() {
        int automatic = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        return Math.max(1, Math.min(32, readInt("performance.postprocess_threads", automatic)));
    }

    /** Bounded number of terrain tiles waiting for serialized model inference. */
    public static int inferenceQueueSize() {
        return Math.max(4, Math.min(256, readInt("performance.inference_queue_size", 32)));
    }

    public static int terrainCacheTiles() {
        return Math.max(16, Math.min(512, readInt("performance.cache_tiles", 96)));
    }

    /** Persist completed post-processed terrain tiles between game sessions. */
    public static boolean persistentTileCache() {
        return readBoolean("performance.persistent_tile_cache", true);
    }

    /** Number of neighboring tile rings to predictively generate. */
    public static int prefetchRadius() {
        return Math.max(0, Math.min(2, readInt("performance.prefetch_radius", 1)));
    }

    /** Prefetch pauses above this number so visible gameplay requests always win. */
    public static int prefetchQueueLimit() {
        return Math.max(1, Math.min(64, readInt("performance.prefetch_queue_limit", 4)));
    }

    private static void loadDefaults() {
        boolean loadedFromResource = false;
        try (InputStream in = ImmensaConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in != null) {
                PROPERTIES.load(in);
                loadedFromResource = true;
            }
        } catch (IOException e) {
            System.err.println("Failed to load default config from resource: " + e.getMessage());
        }

        if (!loadedFromResource) {
            PROPERTIES.setProperty("inference.device", "auto");
            PROPERTIES.setProperty("validate_model", String.valueOf(DEFAULT_VALIDATE_MODEL));
            PROPERTIES.setProperty("tile_size", String.valueOf(DEFAULT_TILE_SIZE));
        }
    }

    private static String readString(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? value.trim().toLowerCase() : defaultValue;
    }

    private static Path resolveConfigPath() {
        try {
            return Platforms.get().configDir().resolve(FILE_NAME);
        } catch (RuntimeException e) {
            System.err.println("Platform config directory unavailable: " + e.getMessage());
            return null;
        }
    }

    private static void loadOverrides(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) {
                    Properties overrides = new Properties();
                    overrides.load(in);
                    PROPERTIES.putAll(overrides);
                }
            } else {
                writeConfig(configPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to read config file: " + e.getMessage());
        }
    }

    private static void writeConfig(Path configPath) {
        try (InputStream defaultConfigInputStream = ImmensaConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (defaultConfigInputStream != null) {
                Files.copy(defaultConfigInputStream, configPath);
                return;
            }
            System.err.println("Default config resource not found: " + RESOURCE_PATH);
        } catch (IOException e) {
            System.err.println("Failed to copy default config resource: " + e.getMessage());
        }
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? Boolean.parseBoolean(value.trim()) : defaultValue;
    }

    private static int readInt(String key, int defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid int for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private static float readFloat(String key, float defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid float for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private static boolean isPowerOfTwo(int value) {
        return (value & (value - 1)) == 0;
    }

    private static String readBuildVariant() {
        try (InputStream in = ImmensaConfig.class.getResourceAsStream("/build-variant.properties")) {
            if (in == null) return "unknown";
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("build.variant", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

}
