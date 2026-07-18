package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.config.ImmensaConfig;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider.HeightmapData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Builds a deterministic overgrown transition inside selected surface caves. */
public final class SurfaceCaveDecorator {
    private static final int MAX_DECORATED_DEPTH = 58;

    private SurfaceCaveDecorator() {
    }

    public static void decorate(ChunkAccess chunk) {
        decorate(chunk, List.of());
    }

    /**
     * Runs after FEATURES and therefore decorates only natural cave terrain.
     * Structure boxes are expanded by the pipeline before being supplied here.
     */
    public static void decorate(ChunkAccess chunk, List<BoundingBox> protectedStructureBoxes) {
        if (!ImmensaConfig.cavesEnabled()
                || !ImmensaConfig.surfaceCaveEntrancesEnabled()) return;
        int chunkStartX = chunk.getPos().getMinBlockX();
        int chunkStartZ = chunk.getPos().getMinBlockZ();
        int tileSize = ImmensaConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int tileStartX = (chunkStartX >> tileShift) << tileShift;
        int tileStartZ = (chunkStartZ >> tileShift) << tileShift;
        long seed = LocalTerrainProvider.getSeed();
        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                tileStartZ, tileStartX, tileStartZ + tileSize, tileStartX + tileSize);
        if (data == null || data.heightmap == null) return;

        ImmensaEntranceSelector.Site entrance = ImmensaEntranceSelector.find(
                data, tileStartX, tileStartZ, seed);
        if (entrance == ImmensaEntranceSelector.Site.NONE) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int x = chunkStartX + localX;
                int z = chunkStartZ + localZ;
                if (!isWithinEntranceGrotto(x, z, entrance)) continue;

                int dataX = x - tileStartX;
                int dataZ = z - tileStartZ;
                if (dataX < 0 || dataX >= data.width || dataZ < 0 || dataZ >= data.height) continue;
                int groundY = HeightConverter.convertToMinecraftHeight(data.heightmap[dataZ][dataX]);
                int floorY = findInteriorFloor(chunk, pos, x, z, groundY);
                if (floorY == Integer.MIN_VALUE) continue;
                if (isProtected(x, floorY, z, protectedStructureBoxes)) continue;

                int depth = groundY - floorY;
                long hash = mix(seed, x, floorY, z);
                if (!coverFloor(chunk, pos, x, floorY, z, depth, hash)) continue;
                decorateFloor(chunk, pos, x, floorY, z, depth, hash,
                        localX, localZ, seed);
                decorateCeiling(chunk, pos, x, floorY, z, groundY, depth, hash);
            }
        }
    }

    static boolean isWithinEntranceGrotto(int x, int z,
                                           ImmensaEntranceSelector.Site entrance) {
        if (entrance == ImmensaEntranceSelector.Site.NONE) return false;
        double magnitude = Math.sqrt(entrance.slopeX() * entrance.slopeX()
                + entrance.slopeZ() * entrance.slopeZ());
        if (magnitude < 0.10) return false;
        double axisX = entrance.slopeX() / magnitude;
        double axisZ = entrance.slopeZ() / magnitude;
        double dx = x - entrance.x();
        double dz = z - entrance.z();
        double along = dx * axisX + dz * axisZ;
        double across = -dx * axisZ + dz * axisX;
        double scale = Math.max(0.45, Math.min(1.0, entrance.scale()));
        double maximumReach = Math.min(entrance.reach(), 112.0 * scale);
        if (along < -8.0 * scale || along > maximumReach) return false;
        double progress = Math.max(0.0, Math.min(1.0, along / Math.max(1.0, maximumReach)));
        double radius = (9.0 + progress * 21.0) * scale;
        return Math.abs(across) <= radius;
    }

    private static int findInteriorFloor(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                         int x, int z, int groundY) {
        int top = Math.min(chunk.getMaxY() - 4, groundY - 3);
        int bottom = Math.max(chunk.getMinY() + 3, groundY - MAX_DECORATED_DEPTH);
        boolean enteredCavity = false;
        int openHeight = 0;
        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) {
                enteredCavity = true;
                openHeight++;
                continue;
            }
            if (enteredCavity && openHeight >= 3 && state.getFluidState().isEmpty()) {
                return y;
            }
            enteredCavity = false;
            openHeight = 0;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean coverFloor(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                      int x, int floorY, int z, int depth, long hash) {
        pos.set(x, floorY, z);
        BlockState existingFloor = chunk.getBlockState(pos);
        if (!isNaturalCaveFloor(existingFloor)) return false;

        BlockState surface;
        if (depth <= 19) {
            surface = (hash & 7L) == 0L
                    ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
        } else {
            surface = (hash & 3L) == 0L
                    ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.MOSS_BLOCK.defaultBlockState();
        }
        pos.set(x, floorY, z);
        chunk.setBlockState(pos, surface, 0);
        if (floorY - 1 > chunk.getMinY()) {
            pos.set(x, floorY - 1, z);
            BlockState below = chunk.getBlockState(pos);
            if (!below.isAir() && below.getFluidState().isEmpty()) {
                chunk.setBlockState(pos, (hash & 15L) == 0L
                        ? Blocks.ROOTED_DIRT.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 0);
            }
        }
        return true;
    }

    static boolean isNaturalCaveFloor(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.DRIPSTONE_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY);
    }

    static boolean isProtected(int x, int y, int z, List<BoundingBox> boxes) {
        for (BoundingBox box : boxes) {
            if (box.isInside(x, y, z)) return true;
        }
        return false;
    }

    private static void decorateFloor(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                      int x, int floorY, int z, int depth, long hash,
                                      int localX, int localZ, long seed) {
        int plantY = floorY + 1;
        if (!isAir(chunk, pos, x, plantY, z)) return;

        if (localX >= 2 && localX <= 13 && localZ >= 2 && localZ <= 13
                && isTreeAnchor(x, z, seed) && (hash & 3L) == 0L
                && placeSmallTree(chunk, pos, x, plantY, z, hash)) {
            return;
        }

        int choice = (int) ((hash >>> 8) & 31L);
        BlockState plant = null;
        if (choice < 10) plant = Blocks.SHORT_GRASS.defaultBlockState();
        else if (choice < 15) plant = Blocks.FERN.defaultBlockState();
        else if (choice == 15) plant = Blocks.FLOWERING_AZALEA.defaultBlockState();
        else if (choice == 16) plant = Blocks.AZALEA.defaultBlockState();
        else if (choice == 17 && depth <= 24) plant = Blocks.DANDELION.defaultBlockState();
        else if (choice == 18 && depth <= 24) plant = Blocks.POPPY.defaultBlockState();
        else if (choice == 19 && depth <= 20) plant = Blocks.OXEYE_DAISY.defaultBlockState();
        else if (choice < 24 && depth > 16) plant = Blocks.MOSS_CARPET.defaultBlockState();
        if (plant != null) {
            pos.set(x, plantY, z);
            chunk.setBlockState(pos, plant, 0);
        }
    }

    private static boolean placeSmallTree(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                          int x, int baseY, int z, long hash) {
        int trunkHeight = 3 + (int) ((hash >>> 17) & 1L);
        int canopyY = baseY + trunkHeight;
        for (int y = baseY; y <= canopyY + 1; y++) {
            int radius = y >= canopyY - 1 ? 2 : 0;
            for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                if (!isAir(chunk, pos, x + dx, y, z + dz)) return false;
            }
        }
        for (int y = 0; y < trunkHeight; y++) {
            pos.set(x, baseY + y, z);
            chunk.setBlockState(pos, Blocks.OAK_LOG.defaultBlockState(), 0);
        }
        BlockState leaves = ((hash >>> 23) & 3L) == 0L
                ? Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState()
                : Blocks.AZALEA_LEAVES.defaultBlockState();
        for (int dy = -1; dy <= 1; dy++) {
            int radius = dy == 1 ? 1 : 2;
            for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                if (Math.abs(dx) == radius && Math.abs(dz) == radius && dy != 0) continue;
                int y = canopyY + dy;
                if (isAir(chunk, pos, x + dx, y, z + dz)) {
                    pos.set(x + dx, y, z + dz);
                    chunk.setBlockState(pos, leaves, 0);
                }
            }
        }
        return true;
    }

    private static void decorateCeiling(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                        int x, int floorY, int z, int groundY,
                                        int depth, long hash) {
        if (((hash >>> 29) & 15L) > (depth > 18 ? 3L : 1L)) return;
        int maximum = Math.min(groundY - 2, floorY + 18);
        int ceilingY = Integer.MIN_VALUE;
        for (int y = floorY + 4; y <= maximum; y++) {
            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (!state.isAir()) {
                if (state.getFluidState().isEmpty()) ceilingY = y;
                break;
            }
        }
        if (ceilingY == Integer.MIN_VALUE) return;

        int length = 1 + (int) ((hash >>> 37) & 3L);
        length = Math.min(length, ceilingY - floorY - 3);
        if (length <= 0) return;
        for (int offset = 1; offset <= length; offset++) {
            int y = ceilingY - offset;
            if (!isAir(chunk, pos, x, y, z)) break;
            pos.set(x, y, z);
            BlockState hanging = offset == length
                    ? Blocks.CAVE_VINES.defaultBlockState()
                    : Blocks.CAVE_VINES_PLANT.defaultBlockState();
            chunk.setBlockState(pos, hanging, 0);
        }
        if (((hash >>> 43) & 7L) == 0L && isAir(chunk, pos, x + 1, ceilingY - 1, z)) {
            pos.set(x + 1, ceilingY - 1, z);
            chunk.setBlockState(pos, Blocks.HANGING_ROOTS.defaultBlockState(), 0);
        }
    }

    private static boolean isTreeAnchor(int x, int z, long seed) {
        int cellX = Math.floorDiv(x, 11);
        int cellZ = Math.floorDiv(z, 11);
        long hash = mix(seed ^ 0x7f4a7c159e3779b9L, cellX, 0, cellZ);
        int anchorX = cellX * 11 + 2 + (int) ((hash >>> 8) & 7L);
        int anchorZ = cellZ * 11 + 2 + (int) ((hash >>> 16) & 7L);
        return x == anchorX && z == anchorZ;
    }

    private static boolean isAir(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        if (y < chunk.getMinY() || y > chunk.getMaxY()) return false;
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        if (x < startX || x > startX + 15 || z < startZ || z > startZ + 15) return false;
        pos.set(x, y, z);
        return chunk.getBlockState(pos).isAir();
    }

    private static long mix(long seed, int x, int y, int z) {
        long value = seed ^ (long) x * 0x632be59bd9b4e019L
                ^ (long) z * 0x9e3779b97f4a7c15L ^ (long) y * 0xc2b2ae3d27d4eb4fL;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
}
