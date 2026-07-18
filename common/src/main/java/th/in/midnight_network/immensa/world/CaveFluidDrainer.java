package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.config.ImmensaConfig;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Replaces Minecraft's uniform sea-level fluid fill with sparse cave hydrology.
 *
 * <p>Noise caves are created before the carver stage. With aquifers disabled,
 * Minecraft fills every negative-density block below sea level with water and
 * every block below Y=-54 with lava. Draining at the end of carving leaves the
 * terrain/ocean shell intact. Springs, lakes and Still Life decorations run in
 * later feature stages, so their smaller intentional pools are preserved. A
 * deterministic second pass adds selected groundwater and magma basins on real
 * cavern floors, without flooding whole tunnel networks.</p>
 */
public final class CaveFluidDrainer {
    private static final double[] DIRECTION_X = {
            1.0, 0.7071067811865476, 0.0, -0.7071067811865476,
            -1.0, -0.7071067811865476, 0.0, 0.7071067811865476
    };
    private static final double[] DIRECTION_Z = {
            0.0, 0.7071067811865476, 1.0, 0.7071067811865476,
            0.0, -0.7071067811865476, -1.0, -0.7071067811865476
    };

    private CaveFluidDrainer() {
    }

    public static void drain(ChunkAccess chunk) {
        if (!ImmensaConfig.drainBaseCaveFluids()) return;
        boolean deepLavaPools = ImmensaConfig.deepLavaPoolsEnabled();
        boolean cavePools = ImmensaConfig.cavePoolsEnabled();

        int chunkStartX = chunk.getPos().getMinBlockX();
        int chunkStartZ = chunk.getPos().getMinBlockZ();
        int tileSize = ImmensaConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int tileStartX = (chunkStartX >> tileShift) << tileShift;
        int tileStartZ = (chunkStartZ >> tileShift) << tileShift;
        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                tileStartZ, tileStartX, tileStartZ + tileSize, tileStartX + tileSize);
        if (data == null || data.heightmap == null) return;

        int bottomY = chunk.getMinY();
        int topY = chunk.getMaxY();
        long worldSeed = LocalTerrainProvider.getSeed();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int worldX = chunkStartX + localX;
                int worldZ = chunkStartZ + localZ;
                int dataX = worldX - tileStartX;
                int dataZ = worldZ - tileStartZ;
                int groundY = HeightConverter.convertToMinecraftHeight(data.heightmap[dataZ][dataX]);
                int networkLavaSurface = deepLavaPools
                        ? ImmensaCaveSampler.deepLavaSurfaceY(worldX, worldZ, worldSeed)
                        : Integer.MIN_VALUE;
                boolean preserveBaseLava = deepLavaPools
                        && keepDeepLava(worldX, worldZ, worldSeed);

                // Four solid blocks beneath the terrain shell protect oceans,
                // rivers and lake beds. Base fluids never occur above Y=62 here.
                int drainTop = Math.min(topY, Math.min(62, groundY - 4));
                for (int y = bottomY; y <= drainTop; y++) {
                    pos.set(worldX, y, worldZ);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.is(Blocks.LAVA)
                            && preserveBaseLava) {
                        continue;
                    }
                    if (state.is(Blocks.WATER) || state.is(Blocks.LAVA)) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
                    }
                }
                if (networkLavaSurface != Integer.MIN_VALUE) {
                    fillDeepLavaColumn(chunk, pos, worldX, worldZ,
                            networkLavaSurface, worldSeed);
                }
            }
        }
        if (cavePools) {
            placeCavePools(chunk, data, tileStartX, tileStartZ, worldSeed, deepLavaPools);
        }
    }

    /**
     * Fills the open cavern segment containing the shared lava surface down to
     * its real solid floor. The old per-Y density test could accept the top of
     * an ellipsoid and reject its lower half, creating unsupported lava sheets.
     */
    private static void fillDeepLavaColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                           int x, int z, int surfaceY, long worldSeed) {
        if (surfaceY <= chunk.getMinY() || surfaceY > chunk.getMaxY()) return;
        if (!ImmensaCaveSampler.isDeepLavaInterior(
                x, surfaceY, z, worldSeed)) return;

        pos.set(x, surfaceY, z);
        BlockState surface = chunk.getBlockState(pos);
        if (!isReplaceableFluidColumn(surface)) return;

        int floorY = Integer.MIN_VALUE;
        for (int y = surfaceY - 1; y >= chunk.getMinY(); y--) {
            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (isReplaceableFluidColumn(state)) continue;
            floorY = y;
            break;
        }
        if (floorY == Integer.MIN_VALUE) return;

        BlockState lava = Blocks.LAVA.defaultBlockState();
        for (int y = floorY + 1; y <= surfaceY; y++) {
            pos.set(x, y, z);
            if (!isReplaceableFluidColumn(chunk.getBlockState(pos))) return;
        }
        for (int y = floorY + 1; y <= surfaceY; y++) {
            pos.set(x, y, z);
            chunk.setBlockState(pos, lava, 0);
        }
    }

    private static boolean isReplaceableFluidColumn(BlockState state) {
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
    }

    private static void placeCavePools(ChunkAccess chunk, HeightmapData data,
                                       int tileStartX, int tileStartZ, long worldSeed,
                                       boolean deepLavaPools) {
        int chunkStartX = chunk.getPos().getMinBlockX();
        int chunkStartZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int worldX = chunkStartX + localX;
                int worldZ = chunkStartZ + localZ;
                int dataX = worldX - tileStartX;
                int dataZ = worldZ - tileStartZ;
                int groundY = HeightConverter.convertToMinecraftHeight(data.heightmap[dataZ][dataX]);

                CavePool water = cavePoolAt(worldX, worldZ, worldSeed, false);
                if (water != null
                        && !ImmensaCaveSampler.isDeepLavaInterior(
                                worldX, water.surfaceY, worldZ, worldSeed)) {
                    placePoolColumn(chunk, pos, worldX, worldZ, groundY, water);
                }
                if (deepLavaPools) {
                    CavePool lava = cavePoolAt(worldX, worldZ, worldSeed, true);
                    if (lava != null) {
                        placePoolColumn(chunk, pos, worldX, worldZ, groundY, lava);
                    }
                }
            }
        }
    }

    /** Places one fixed-level pool column only where a genuine open cavern floor exists. */
    private static void placePoolColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                        int x, int z, int groundY, CavePool pool) {
        int surfaceY = pool.surfaceY;
        if (surfaceY <= chunk.getMinY() + 1
                || surfaceY >= chunk.getMaxY() - 2
                || surfaceY > groundY - 18) {
            return;
        }

        // Pools need open headroom. This rejects narrow tunnels and prevents a
        // fluid layer from being hidden directly beneath a low ceiling.
        for (int y = surfaceY; y <= surfaceY + 2; y++) {
            pos.set(x, y, z);
            if (!chunk.getBlockState(pos).isAir()) return;
        }

        int minimumFloor = Math.max(chunk.getMinY(), surfaceY - pool.maxDepth);
        int floorY = Integer.MIN_VALUE;
        for (int y = surfaceY - 1; y >= minimumFloor; y--) {
            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.is(Blocks.WATER) || state.is(Blocks.LAVA)) return;
            floorY = y;
            break;
        }
        if (floorY == Integer.MIN_VALUE) return;

        BlockState fluid = pool.lava
                ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
        for (int y = floorY + 1; y <= surfaceY; y++) {
            pos.set(x, y, z);
            if (!chunk.getBlockState(pos).isAir()) return;
        }
        for (int y = floorY + 1; y <= surfaceY; y++) {
            pos.set(x, y, z);
            chunk.setBlockState(pos, fluid, 0);
        }
        pos.set(x, floorY, z);
        BlockState naturalFloor = chunk.getBlockState(pos);
        if (isNaturalPoolFloor(naturalFloor)) {
            double sediment = valueNoise(x, z, pool.lava ? 8 : 7,
                    pool.siteHash ^ 0x1f83d9abfb41bd6bL);
            BlockState replacement;
            if (pool.lava) {
                replacement = sediment > 0.72
                        ? Blocks.MAGMA_BLOCK.defaultBlockState()
                        : Blocks.BASALT.defaultBlockState();
            } else {
                replacement = sediment > 0.18
                        ? Blocks.CLAY.defaultBlockState()
                        : Blocks.GRAVEL.defaultBlockState();
            }
            chunk.setBlockState(pos, replacement, 0);
        }
    }

    private static boolean isNaturalPoolFloor(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.TUFF) || state.is(Blocks.DIRT)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY);
    }

    /**
     * Returns the strongest globally seeded basin covering a column. Fixed site
     * levels make neighboring chunks share a physically level water surface.
     */
    static CavePool cavePoolAt(int x, int z, long worldSeed, boolean lava) {
        int cellSize = lava ? 176 : 112;
        int cellX = Math.floorDiv(x, cellSize);
        int cellZ = Math.floorDiv(z, cellSize);
        CavePool best = null;
        double bestStrength = 0.0;
        long salt = lava ? 0xbb67ae8584caa73bL : 0x3c6ef372fe94f82bL;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int siteCellX = cellX + dx;
                int siteCellZ = cellZ + dz;
                long hash = mix64(worldSeed ^ salt
                        ^ (long) siteCellX * 0x9e3779b97f4a7c15L
                        ^ (long) siteCellZ * 0xc2b2ae3d27d4eb4fL);
                int active = lava ? 2 : 3;
                int divisor = lava ? 16 : 8;
                if (Long.remainderUnsigned(hash, divisor) >= active) continue;

                double centerX = siteCellX * (double) cellSize + cellSize * 0.5
                        + signedBits(hash >>> 9, cellSize * 0.24);
                double centerZ = siteCellZ * (double) cellSize + cellSize * 0.5
                        + signedBits(hash >>> 25, cellSize * 0.24);
                double radiusLong = (lava ? 18.0 : 16.0) + ((hash >>> 41) & 15L);
                double radiusWide = (lava ? 11.0 : 10.0) + ((hash >>> 46) & 11L);
                int direction = (int) ((hash >>> 54) & 7L);
                double offsetX = x - centerX;
                double offsetZ = z - centerZ;
                double along = offsetX * DIRECTION_X[direction]
                        + offsetZ * DIRECTION_Z[direction];
                double across = -offsetX * DIRECTION_Z[direction]
                        + offsetZ * DIRECTION_X[direction];
                double distance = Math.sqrt(along * along / (radiusLong * radiusLong)
                        + across * across / (radiusWide * radiusWide));
                double shoreline = valueNoise(x, z, lava ? 13 : 9,
                        hash ^ 0xa54ff53a5f1d36f1L) * 0.14;
                double strength = 1.0 - distance + shoreline;
                if (strength <= bestStrength) continue;

                int levelRange = lava ? 29 : 52;
                int minimumLevel = lava ? -50 : -6;
                int surfaceY = minimumLevel + (int) ((hash >>> 48) & 63L) % levelRange;
                int maximumDepth = 1 + (int) Math.round(Math.min(1.0, strength)
                        * (lava ? 5.0 : 8.0));
                bestStrength = strength;
                best = new CavePool(surfaceY, maximumDepth, lava, strength, hash);
            }
        }
        return best;
    }

    static boolean keepDeepLava(int x, int z, long worldSeed) {
        double broad = valueNoise(x, z, 42, worldSeed ^ 0x510e527fade682d1L);
        double detail = valueNoise(x, z, 13, worldSeed ^ 0x9b05688c2b3e6c1fL);
        return broad * 0.78 + detail * 0.22 > 0.48;
    }

    private static double valueNoise(int x, int z, int scale, long seed) {
        int cellX = Math.floorDiv(x, scale);
        int cellZ = Math.floorDiv(z, scale);
        double fx = Math.floorMod(x, scale) / (double) scale;
        double fz = Math.floorMod(z, scale) / (double) scale;
        fx = fx * fx * (3.0 - 2.0 * fx);
        fz = fz * fz * (3.0 - 2.0 * fz);
        double top = lerp(hashUnit(seed, cellX, cellZ),
                hashUnit(seed, cellX + 1, cellZ), fx);
        double bottom = lerp(hashUnit(seed, cellX, cellZ + 1),
                hashUnit(seed, cellX + 1, cellZ + 1), fx);
        return lerp(top, bottom, fz);
    }

    private static double hashUnit(long seed, int x, int z) {
        long value = seed ^ (long) x * 0x9e3779b97f4a7c15L
                ^ (long) z * 0xc2b2ae3d27d4eb4fL;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-52 - 1.0;
    }

    private static double signedBits(long bits, double amplitude) {
        return (((bits & 0xffffL) / 32767.5) - 1.0) * amplitude;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static record CavePool(int surfaceY, int maxDepth, boolean lava,
                           double strength, long siteHash) {
    }
}
