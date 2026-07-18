package th.in.midnight_network.immensa.world;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

/** Creates a natural piece-aware cavern around an Ancient City. */
public final class AncientCityCavernCarver {
    static final int PIECE_INFLUENCE = 26;
    private static final int PIECE_CLEARANCE_INFLUENCE = 18;
    private static final int PIECE_ROOF_CLEARANCE = 11;
    private static final int EDGE_HEADROOM = 3;
    private static final int FOUNDATION_DEPTH = 7;

    private AncientCityCavernCarver() {
    }

    public static void carve(WorldGenLevel world, PiecesContainer children,
                             BoundingBox chunkBox, long worldSeed) {
        process(world, children, chunkBox, worldSeed, true);
    }

    /**
     * Final FEATURES-stage repair. It removes only fluids that later biome
     * features introduced into the city chamber and restores short foundations;
     * it never carves new solid blocks, so decoration cannot recreate flat cuts.
     */
    public static void finish(WorldGenLevel world, PiecesContainer children,
                              BoundingBox chunkBox, long worldSeed) {
        process(world, children, chunkBox, worldSeed, false);
    }

    private static void process(WorldGenLevel world, PiecesContainer children,
                                BoundingBox chunkBox, long worldSeed,
                                boolean clearSolidTerrain) {
        List<BoundingBox> pieceBoxes = children.pieces().stream()
                .map(StructurePiece::getBoundingBox)
                .filter(box -> box.intersects(
                        chunkBox.minX() - PIECE_INFLUENCE,
                        chunkBox.minZ() - PIECE_INFLUENCE,
                        chunkBox.maxX() + PIECE_INFLUENCE,
                        chunkBox.maxZ() + PIECE_INFLUENCE))
                .toList();
        if (pieceBoxes.isEmpty()) return;

        BoundingBox cityBox = children.calculateBoundingBox();
        int minX = Math.max(chunkBox.minX(), cityBox.minX() - PIECE_INFLUENCE);
        int maxX = Math.min(chunkBox.maxX(), cityBox.maxX() + PIECE_INFLUENCE);
        int minZ = Math.max(chunkBox.minZ(), cityBox.minZ() - PIECE_INFLUENCE);
        int maxZ = Math.min(chunkBox.maxZ(), cityBox.maxZ() + PIECE_INFLUENCE);
        int floorY = Math.max(world.getMinY() + 2, cityBox.minY() + 1);
        if (minX > maxX || minZ > maxZ) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                ColumnProfile profile = columnProfile(pieceBoxes, x, z, floorY, worldSeed);
                if (!profile.carve()) continue;

                int ceilingY = Math.min(world.getMaxY() - 1, profile.ceilingY());
                for (int y = floorY; y <= ceilingY; y++) {
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    boolean unwantedFluid = state.is(Blocks.WATER)
                            || state.is(Blocks.LAVA);
                    if (!state.is(Blocks.BEDROCK)
                            && (unwantedFluid || (clearSolidTerrain && !state.isAir()))) {
                        world.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }

                // Only reinforce columns directly beneath actual structure
                // pieces. This stops lava/void breakthroughs without creating
                // another giant rectangular platform beneath the whole city.
                if (profile.foundationTopY() != Integer.MIN_VALUE) {
                    int foundationBottom = Math.max(world.getMinY() + 1,
                            profile.foundationTopY() - FOUNDATION_DEPTH + 1);
                    for (int y = foundationBottom; y <= profile.foundationTopY(); y++) {
                        pos.set(x, y, z);
                        BlockState state = world.getBlockState(pos);
                        if ((state.isAir() || !state.getFluidState().isEmpty())
                                && !state.is(Blocks.BEDROCK)) {
                            world.setBlock(pos, Blocks.DEEPSLATE.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
    }

    static ColumnProfile columnProfile(List<BoundingBox> pieceBoxes, int x, int z,
                                       int floorY, long worldSeed) {
        double nearestDistance = Double.POSITIVE_INFINITY;
        int requiredCeiling = floorY + EDGE_HEADROOM;
        int foundationTopY = Integer.MIN_VALUE;

        double broadNoise = valueNoise(worldSeed ^ 0x6a09e667f3bcc909L, x, z, 47);
        double detailNoise = valueNoise(worldSeed ^ 0xbb67ae8584caa73bL, x, z, 19);
        int roofVariation = (int) Math.round(broadNoise * 8.0 + detailNoise * 3.0);

        for (BoundingBox piece : pieceBoxes) {
            double distance = horizontalDistance(piece, x, z);
            nearestDistance = Math.min(nearestDistance, distance);

            if (distance <= PIECE_CLEARANCE_INFLUENCE) {
                double blend = 1.0 - smoothstep(distance / PIECE_CLEARANCE_INFLUENCE);
                int fullClearance = piece.maxY() + PIECE_ROOF_CLEARANCE
                        + Math.max(0, roofVariation);
                int blended = floorY + EDGE_HEADROOM
                        + (int) Math.round((fullClearance - floorY - EDGE_HEADROOM) * blend);
                requiredCeiling = Math.max(requiredCeiling, blended);
            }

            if (distance == 0.0) {
                foundationTopY = Math.max(foundationTopY, piece.minY() - 1);
            }
        }

        if (nearestDistance > PIECE_INFLUENCE) {
            return new ColumnProfile(false, floorY - 1, Integer.MIN_VALUE);
        }

        // The broad chamber follows the union of nearby pieces rather than the
        // city's rectangular bounding box. Its roof undulates at two smooth
        // scales, then closes gradually into natural rock at the outside edge.
        double edgeBlend = 1.0 - smoothstep(nearestDistance / PIECE_INFLUENCE);
        int naturalHeadroom = Math.max(24, 39 + roofVariation);
        int organicCeiling = floorY + EDGE_HEADROOM
                + (int) Math.round((naturalHeadroom - EDGE_HEADROOM) * edgeBlend);
        int ceiling = Math.max(requiredCeiling, organicCeiling);
        return new ColumnProfile(true, Math.max(floorY, ceiling), foundationTopY);
    }

    private static double horizontalDistance(BoundingBox box, int x, int z) {
        int dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
        int dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    private static double valueNoise(long seed, int x, int z, int cellSize) {
        int cellX = Math.floorDiv(x, cellSize);
        int cellZ = Math.floorDiv(z, cellSize);
        double tx = fade(Math.floorMod(x, cellSize) / (double) cellSize);
        double tz = fade(Math.floorMod(z, cellSize) / (double) cellSize);
        double v00 = corner(seed, cellX, cellZ);
        double v10 = corner(seed, cellX + 1, cellZ);
        double v01 = corner(seed, cellX, cellZ + 1);
        double v11 = corner(seed, cellX + 1, cellZ + 1);
        return lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), tz);
    }

    private static double corner(long seed, int x, int z) {
        long hash = mix64(seed
                ^ (long) x * 0xd1b54a32d192ed03L
                ^ (long) z * 0xabc98388fb8fac03L);
        return ((hash >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static double fade(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double smoothstep(double value) {
        value = Math.max(0.0, Math.min(1.0, value));
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    record ColumnProfile(boolean carve, int ceilingY, int foundationTopY) {
    }
}
