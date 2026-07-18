package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.config.ImmensaConfig;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider.HeightmapData;
import th.in.midnight_network.immensa.pipeline.TerrainMetadata;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Replaces Minecraft's planar stone/deepslate gradient with broad geological
 * provinces, folded contacts and a narrow three-dimensional mixed transition.
 */
public final class GeologicalStrataPlacer {
    private static final int MIN_PROCESS_Y = -63;
    private static final int MAX_PROCESS_Y = 56;

    private GeologicalStrataPlacer() {
    }

    public static void place(ChunkAccess chunk) {
        if (!ImmensaConfig.dynamicStrataEnabled()) return;

        int chunkStartX = chunk.getPos().getMinBlockX();
        int chunkStartZ = chunk.getPos().getMinBlockZ();
        int tileSize = ImmensaConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int tileStartX = (chunkStartX >> tileShift) << tileShift;
        int tileStartZ = (chunkStartZ >> tileShift) << tileShift;
        long seed = LocalTerrainProvider.getSeed();
        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                tileStartZ, tileStartX, tileStartZ + tileSize, tileStartX + tileSize);
        if (data == null || data.geology == null) return;

        int bottomY = Math.max(chunk.getMinY(), MIN_PROCESS_Y);
        int topY = Math.min(chunk.getMaxY(), MAX_PROCESS_Y);
        if (bottomY > topY) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int worldX = chunkStartX + localX;
                int worldZ = chunkStartZ + localZ;
                int dataX = worldX - tileStartX;
                int dataZ = worldZ - tileStartZ;
                byte geology = data.geology[dataZ][dataX];
                int contactY = contactY(worldX, worldZ, geology, seed);
                int blend = ImmensaConfig.strataBlendBlocks();
                int definitelyDeep = contactY - blend - 5;
                int definitelyStone = contactY + blend + 5;

                for (int y = bottomY; y <= topY; y++) {
                    pos.set(worldX, y, worldZ);
                    BlockState existing = chunk.getBlockState(pos);
                    if (!existing.is(Blocks.STONE)
                            && !existing.is(Blocks.DEEPSLATE)) continue;

                    boolean deepslate;
                    if (y <= definitelyDeep) {
                        deepslate = true;
                    } else if (y >= definitelyStone) {
                        deepslate = false;
                    } else {
                        deepslate = isDeepslate(worldX, y, worldZ,
                                geology, seed);
                    }
                    if (deepslate && existing.is(Blocks.STONE)) {
                        chunk.setBlockState(pos, Blocks.DEEPSLATE.defaultBlockState(), 0);
                    } else if (!deepslate && existing.is(Blocks.DEEPSLATE)) {
                        chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), 0);
                    }
                }
            }
        }
    }

    static int contactY(int x, int z, byte geology, long seed) {
        int relief = ImmensaConfig.strataReliefBlocks();
        double province = valueNoise2D(x, z, 920,
                seed ^ 0x243f6a8885a308d3L);
        double folds = valueNoise2D(x, z, 280,
                seed ^ 0x13198a2e03707344L);
        double local = valueNoise2D(x, z, 88,
                seed ^ 0xa4093822299f31d0L);
        double displacement = province * relief * 0.58
                + folds * relief * 0.32
                + local * relief * 0.10;
        double geologyOffset = switch (geology) {
            case TerrainMetadata.GEO_GRANITIC -> 7.0;
            case TerrainMetadata.GEO_LIMESTONE -> -7.0;
            case TerrainMetadata.GEO_VOLCANIC -> 13.0;
            case TerrainMetadata.GEO_GLACIAL -> 3.0;
            default -> -2.0;
        };
        return (int) Math.round(clamp(2.0 + displacement + geologyOffset,
                -34.0, 42.0));
    }

    static boolean isDeepslate(int x, int y, int z, byte geology, long seed) {
        if (y <= -52) return true;
        if (y >= 52) return false;
        int contact = contactY(x, z, geology, seed);
        int blend = ImmensaConfig.strataBlendBlocks();

        // 3D lithology noise prevents the contact from following one exact
        // height contour. A second warped sheet creates occasional stone
        // windows below the contact and deepslate tongues above it.
        double coarse = valueNoise3D(x, y, z, 19,
                seed ^ 0x082efa98ec4e6c89L);
        double fine = valueNoise3D(x, y, z, 7,
                seed ^ 0x452821e638d01377L);
        double warpedContact = contact + coarse * blend * 0.82
                + fine * blend * 0.34;
        return y <= warpedContact;
    }

    private static double valueNoise2D(int x, int z, int scale, long seed) {
        int cellX = Math.floorDiv(x, scale);
        int cellZ = Math.floorDiv(z, scale);
        double fx = smooth(Math.floorMod(x, scale) / (double) scale);
        double fz = smooth(Math.floorMod(z, scale) / (double) scale);
        double top = lerp(signed(seed, cellX, 0, cellZ),
                signed(seed, cellX + 1, 0, cellZ), fx);
        double bottom = lerp(signed(seed, cellX, 0, cellZ + 1),
                signed(seed, cellX + 1, 0, cellZ + 1), fx);
        return lerp(top, bottom, fz);
    }

    private static double valueNoise3D(int x, int y, int z, int scale, long seed) {
        int cellX = Math.floorDiv(x, scale);
        int cellY = Math.floorDiv(y, scale);
        int cellZ = Math.floorDiv(z, scale);
        double fx = smooth(Math.floorMod(x, scale) / (double) scale);
        double fy = smooth(Math.floorMod(y, scale) / (double) scale);
        double fz = smooth(Math.floorMod(z, scale) / (double) scale);

        double lowerTop = lerp(signed(seed, cellX, cellY, cellZ),
                signed(seed, cellX + 1, cellY, cellZ), fx);
        double lowerBottom = lerp(signed(seed, cellX, cellY, cellZ + 1),
                signed(seed, cellX + 1, cellY, cellZ + 1), fx);
        double upperTop = lerp(signed(seed, cellX, cellY + 1, cellZ),
                signed(seed, cellX + 1, cellY + 1, cellZ), fx);
        double upperBottom = lerp(signed(seed, cellX, cellY + 1, cellZ + 1),
                signed(seed, cellX + 1, cellY + 1, cellZ + 1), fx);
        return lerp(lerp(lowerTop, lowerBottom, fz),
                lerp(upperTop, upperBottom, fz), fy);
    }

    private static double signed(long seed, int x, int y, int z) {
        long value = seed ^ (long) x * 0x632be59bd9b4e019L
                ^ (long) y * 0x94d049bb133111ebL
                ^ (long) z * 0x9e3779b97f4a7c15L;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-52 - 1.0;
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
