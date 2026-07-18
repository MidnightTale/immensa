package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.config.ImmensaConfig;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Builds deterministic polar sea ice after biome features have finished.
 *
 * <p>Broad value noise controls a finite ice margin. Domain-warped, nested
 * Voronoi plates transition from large shore-fast slabs to small offshore floes
 * and open ocean without any chunk-local random state, so the pattern remains
 * continuous across chunk and tile borders.</p>
 */
public final class PolarIceFieldPlacer {
    private static final short FROZEN_RIVER = 11;
    private static final short COLD_OCEAN = 46;
    private static final short FROZEN_OCEAN = 48;
    private static final int SEA_LEVEL = 63;

    record IceSample(boolean ice, int thickness, int snowLayers,
                     boolean packed, double coverage, int floeScale) {
        static final IceSample OPEN_WATER =
                new IceSample(false, 0, 0, false, 0.0, 0);
    }

    private PolarIceFieldPlacer() {
    }

    public static void place(ChunkAccess chunk) {
        if (!ImmensaConfig.polarIceEnabled()) return;

        int chunkStartX = chunk.getPos().getMinBlockX();
        int chunkStartZ = chunk.getPos().getMinBlockZ();
        int tileSize = ImmensaConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int tileStartX = (chunkStartX >> tileShift) << tileShift;
        int tileStartZ = (chunkStartZ >> tileShift) << tileShift;
        long seed = LocalTerrainProvider.getSeed();
        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                tileStartZ, tileStartX, tileStartZ + tileSize, tileStartX + tileSize);
        if (data == null || data.biomeIds == null) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int worldX = chunkStartX + localX;
                int worldZ = chunkStartZ + localZ;
                int dataX = worldX - tileStartX;
                int dataZ = worldZ - tileStartZ;
                short biome = data.biomeIds[dataZ][dataX];
                short terrainMeters = data.heightmap[dataZ][dataX];
                short hydrologySurface = data.waterSurface[dataZ][dataX];

                int waterY;
                float depthMeters;
                if (hydrologySurface != Short.MIN_VALUE) {
                    if (biome != FROZEN_RIVER) continue;
                    waterY = HeightConverter.convertToMinecraftHeight(hydrologySurface);
                    depthMeters = Math.max(1f, hydrologySurface - terrainMeters);
                } else {
                    if ((biome != FROZEN_OCEAN && biome != COLD_OCEAN)
                            || terrainMeters >= 0) continue;
                    waterY = SEA_LEVEL;
                    depthMeters = Math.max(1f, -terrainMeters);
                }
                if (waterY < chunk.getMinY()
                        || waterY >= chunk.getMaxY()) continue;
                waterY = findSurfaceWaterY(chunk, pos, worldX, worldZ, waterY);
                if (waterY == Integer.MIN_VALUE) continue;

                IceSample sample = sample(worldX, worldZ, biome, depthMeters, seed);
                pos.set(worldX, waterY, worldZ);
                BlockState surface = chunk.getBlockState(pos);
                pos.set(worldX, waterY + 1, worldZ);
                BlockState above = chunk.getBlockState(pos);

                if (!sample.ice()) {
                    // Vanilla's freeze pass would otherwise turn the designed
                    // open leads into one unbroken ice sheet. Never puncture
                    // packed/blue iceberg structures.
                    if (surface.is(Blocks.ICE) && isClearAbove(above)) {
                        pos.set(worldX, waterY, worldZ);
                        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), 0);
                        if (above.is(Blocks.SNOW)) {
                            pos.set(worldX, waterY + 1, worldZ);
                            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
                        }
                    }
                    continue;
                }

                if (!surface.is(Blocks.WATER) && !surface.is(Blocks.ICE)) {
                    continue;
                }
                BlockState ice = sample.packed()
                        ? Blocks.PACKED_ICE.defaultBlockState()
                        : Blocks.ICE.defaultBlockState();
                int placed = 0;
                for (int layer = 0; layer < sample.thickness(); layer++) {
                    int y = waterY - layer;
                    if (y < chunk.getMinY()) break;
                    pos.set(worldX, y, worldZ);
                    BlockState existing = chunk.getBlockState(pos);
                    if (!existing.is(Blocks.WATER) && !existing.is(Blocks.ICE)) break;
                    chunk.setBlockState(pos, ice, 0);
                    placed++;
                }
                if (placed == 0 || sample.snowLayers() <= 0) continue;
                pos.set(worldX, waterY + 1, worldZ);
                if (chunk.getBlockState(pos).isAir()) {
                    chunk.setBlockState(pos, Blocks.SNOW.defaultBlockState()
                            .setValue(SnowLayerBlock.LAYERS, sample.snowLayers()), 0);
                }
            }
        }
    }

    static IceSample sample(int x, int z, short biome, float depthMeters, long seed) {
        double baseCoverage;
        if (biome == FROZEN_OCEAN) {
            baseCoverage = ImmensaConfig.polarIceFrozenOceanCoverage();
        } else if (biome == COLD_OCEAN) {
            baseCoverage = ImmensaConfig.polarIceColdOceanCoverage();
        } else if (biome == FROZEN_RIVER) {
            baseCoverage = Math.max(0.78,
                    ImmensaConfig.polarIceFrozenOceanCoverage() - 0.04);
        } else {
            return IceSample.OPEN_WATER;
        }

        if (biome == FROZEN_RIVER) {
            return sampleFrozenRiver(x, z, baseCoverage, seed);
        }

        // Seabed depth is a stable coast-to-open-water proxy already available
        // from the generated terrain. Bend the pack front over kilometre scales
        // so it forms bays, tongues and large open-water interruptions instead
        // of expanding across every cold-ocean column.
        double marginNoise = valueNoise(x, z, 1_900,
                seed ^ 0x6a09e667f3bcc909L);
        double frontNoise = valueNoise(x, z, 720,
                seed ^ 0xbb67ae8584caa73bL);
        double configuredReach = ImmensaConfig.polarIceOffshoreReachMeters();
        double biomeReach = biome == COLD_OCEAN
                ? configuredReach * 0.62 : configuredReach;
        double reachMeters = biomeReach * (0.72 + marginNoise * 0.25
                + frontNoise * 0.10);
        if (depthMeters >= reachMeters) return IceSample.OPEN_WATER;

        double offshore = smootherStep(clamp(
                (depthMeters - 18.0) / Math.max(1.0, reachMeters - 18.0),
                0.0, 1.0));
        boolean shoreFast = biome == FROZEN_OCEAN && depthMeters < 105f
                && marginNoise > -0.50;

        double nearCoverage = biome == COLD_OCEAN
                ? Math.min(0.72, baseCoverage + 0.27)
                : Math.min(0.95, baseCoverage + 0.08);
        double farCoverage = biome == COLD_OCEAN ? 0.04 : 0.10;
        double coverage = lerp(nearCoverage, farCoverage,
                Math.pow(offshore, 0.78));
        coverage += valueNoise(x, z, 410,
                seed ^ 0x1f83d9abfb41bd6bL) * lerp(0.07, 0.14, offshore);
        coverage += valueNoise(x, z, 125,
                seed ^ 0x5be0cd19137e2179L) * 0.045;
        coverage = clamp(coverage, 0.015, shoreFast ? 0.97 : 0.88);
        if (shoreFast) coverage = Math.max(coverage, 0.91);

        // The pack physically fragments offshore: broad slabs at the coast,
        // medium plates through the marginal zone, then small sparse floes.
        int nearScale = biome == COLD_OCEAN ? 38 : 48;
        int farScale = biome == COLD_OCEAN ? 8 : 10;
        double fragmentation = smootherStep(clamp(
                (depthMeters - 24.0) / Math.max(1.0, biomeReach * 0.82 - 24.0),
                0.0, 1.0));
        int plateScale = Math.max(farScale, (int) Math.round(
                lerp(nearScale, farScale, Math.pow(fragmentation, 0.92))));
        double warpAmount = plateScale * lerp(1.45, 0.72, offshore);
        double warpedX = x + valueNoise(x, z, 330,
                seed ^ 0x3c6ef372fe94f82bL) * warpAmount;
        double warpedZ = z + valueNoise(x, z, 330,
                seed ^ 0xa54ff53a5f1d36f1L) * warpAmount;
        double primaryEdge = voronoiInterior(warpedX, warpedZ, plateScale,
                seed ^ 0x510e527fade682d1L);

        // Secondary fractures gradually appear toward open water. Taking the
        // minimum only offshore prevents a perfect single-scale tessellation.
        int fractureScale = Math.max(6, (int) Math.round(plateScale * 0.48));
        double secondaryEdge = voronoiInterior(
                warpedX + plateScale * 0.37, warpedZ - plateScale * 0.29,
                fractureScale, seed ^ 0x9b05688c2b3e6c1fL);
        double fractureMix = smootherStep((offshore - 0.26) / 0.60);
        double edge = Math.min(primaryEdge,
                lerp(1.0, secondaryEdge * 1.18, fractureMix));
        double crackThreshold = lerp(0.265, 0.018, coverage);
        // Long zero-contours cut a few winding leads across many plates. This
        // removes the visual impression of isolated identical Voronoi rings.
        double rift = Math.abs(valueNoise(x, z, 285,
                seed ^ 0x5f1d36f1a54ff53aL));
        double riftWidth = lerp(0.012, 0.105, offshore);
        boolean ice = edge > crackThreshold && rift > riftWidth;
        if (!ice) return new IceSample(false, 0, 0, false, coverage, plateScale);

        double thicknessNoise = valueNoise(x, z, 54,
                seed ^ 0xa54ff53a5f1d36f1L);
        int thickness = 1;
        if (coverage > 0.68 && thicknessNoise > -0.28) thickness++;
        if (shoreFast && thicknessNoise > 0.18) thickness++;
        thickness = Math.min(thickness,
                ImmensaConfig.polarIceMaxThickness());

        long hash = mix(seed ^ 0x510e527fade682d1L, x, z);
        int snowLayers = biome == COLD_OCEAN
                ? (((hash >>> 9) & 3L) == 0 ? 1 : 0)
                : 1 + (int) ((hash >>> 12) & (shoreFast ? 3L : 1L));
        boolean packed = thickness >= 2 || shoreFast;
        return new IceSample(true, thickness, snowLayers, packed, coverage, plateScale);
    }

    private static IceSample sampleFrozenRiver(int x, int z, double coverage, long seed) {
        int plateScale = 28;
        double warpedX = x + valueNoise(x, z, 210,
                seed ^ 0x3c6ef372fe94f82bL) * 18.0;
        double warpedZ = z + valueNoise(x, z, 210,
                seed ^ 0xa54ff53a5f1d36f1L) * 18.0;
        double edge = voronoiInterior(warpedX, warpedZ, plateScale,
                seed ^ 0x510e527fade682d1L);
        coverage = clamp(coverage + valueNoise(x, z, 180,
                seed ^ 0x1f83d9abfb41bd6bL) * 0.06, 0.72, 0.94);
        if (edge <= lerp(0.265, 0.018, coverage)) {
            return new IceSample(false, 0, 0, false, coverage, plateScale);
        }
        double thicknessNoise = valueNoise(x, z, 54,
                seed ^ 0xa54ff53a5f1d36f1L);
        int thickness = thicknessNoise > -0.28 ? 2 : 1;
        thickness = Math.min(thickness,
                ImmensaConfig.polarIceMaxThickness());
        long hash = mix(seed ^ 0x510e527fade682d1L, x, z);
        int snowLayers = 1 + (int) ((hash >>> 12) & 1L);
        return new IceSample(true, thickness, snowLayers,
                thickness >= 2, coverage, plateScale);
    }

    private static boolean isClearAbove(BlockState state) {
        return state.isAir() || state.is(Blocks.SNOW);
    }

    /**
     * Vanilla oceans top out one block below their configured sea level while
     * Terrain Diffusion hydrology stores an explicit surface height. Resolve
     * the real top water/ice block locally so both systems meet without seams.
     */
    private static int findSurfaceWaterY(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                         int x, int z, int preferredY) {
        int top = Math.min(chunk.getMaxY() - 1, preferredY + 1);
        int bottom = Math.max(chunk.getMinY(), preferredY - 3);
        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (!isWaterOrIce(state)) continue;
            pos.set(x, y + 1, z);
            if (!isWaterOrIce(chunk.getBlockState(pos))) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isWaterOrIce(BlockState state) {
        return state.is(Blocks.WATER) || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE);
    }

    /**
     * Difference between the two closest jittered Voronoi sites, normalized by
     * cell size. Zero lies on a crack; larger values lie inside a plate.
     */
    private static double voronoiInterior(double x, double z, int scale, long seed) {
        int cellX = (int) Math.floor(x / scale);
        int cellZ = (int) Math.floor(z / scale);
        double nearest = Double.POSITIVE_INFINITY;
        double second = Double.POSITIVE_INFINITY;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                long hash = mix(seed, cx, cz);
                double jitterX = 0.16 + unit(hash) * 0.68;
                double jitterZ = 0.16 + unit(hash ^ 0x9e3779b97f4a7c15L) * 0.68;
                double siteX = (cx + jitterX) * scale;
                double siteZ = (cz + jitterZ) * scale;
                double ddx = x - siteX;
                double ddz = z - siteZ;
                double distance = ddx * ddx + ddz * ddz;
                if (distance < nearest) {
                    second = nearest;
                    nearest = distance;
                } else if (distance < second) {
                    second = distance;
                }
            }
        }
        return (Math.sqrt(second) - Math.sqrt(nearest)) / scale;
    }

    private static double valueNoise(int x, int z, int scale, long seed) {
        int cellX = Math.floorDiv(x, scale);
        int cellZ = Math.floorDiv(z, scale);
        double fx = smooth(Math.floorMod(x, scale) / (double) scale);
        double fz = smooth(Math.floorMod(z, scale) / (double) scale);
        double top = lerp(signed(seed, cellX, cellZ),
                signed(seed, cellX + 1, cellZ), fx);
        double bottom = lerp(signed(seed, cellX, cellZ + 1),
                signed(seed, cellX + 1, cellZ + 1), fx);
        return lerp(top, bottom, fz);
    }

    private static double signed(long seed, int x, int z) {
        return unit(mix(seed, x, z)) * 2.0 - 1.0;
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

    private static long mix(long seed, int x, int z) {
        long value = seed ^ (long) x * 0x632be59bd9b4e019L
                ^ (long) z * 0x9e3779b97f4a7c15L;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double smootherStep(double value) {
        double t = clamp(value, 0.0, 1.0);
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
