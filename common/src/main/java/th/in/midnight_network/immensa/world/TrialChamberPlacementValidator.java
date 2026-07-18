package th.in.midnight_network.immensa.world;

import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Keeps Trial Chambers embedded in rock instead of suspended in megacaverns. */
public final class TrialChamberPlacementValidator {
    public static final int REJECT = Integer.MIN_VALUE;
    private static final int[] VERTICAL_OFFSETS = {0, -8, 8, -16, 16, -24, 24};

    private TrialChamberPlacementValidator() {
    }

    /**
     * Finds the closest safe vertical placement for a generated chamber layout.
     * The sampled envelope must have a strong foundation and rock cover while
     * tolerating a small cave or tunnel intersection that can become an entrance.
     */
    public static int chooseVerticalShift(Structure.GenerationContext context, BoundingBox box) {
        int[] xs = sampleAxis(box.minX(), box.maxX());
        int[] zs = sampleAxis(box.minZ(), box.maxZ());
        NoiseColumn[][] columns = new NoiseColumn[zs.length][xs.length];
        for (int zi = 0; zi < zs.length; zi++) {
            for (int xi = 0; xi < xs.length; xi++) {
                columns[zi][xi] = context.chunkGenerator().getBaseColumn(
                        xs[xi], zs[zi], context.heightAccessor(), context.randomState());
            }
        }

        int bestShift = REJECT;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int shift : VERTICAL_OFFSETS) {
            int minY = box.minY() + shift;
            int maxY = box.maxY() + shift;
            if (minY - 8 < context.heightAccessor().getMinBuildHeight() + 2
                    || maxY + 10 > context.heightAccessor().getMaxBuildHeight() - 2) {
                continue;
            }

            int supportSolid = 0;
            int coverSolid = 0;
            int bodySolid = 0;
            int liquids = 0;
            int samples = xs.length * zs.length;
            int middleY = minY + (maxY - minY) / 2;
            for (NoiseColumn[] row : columns) {
                for (NoiseColumn column : row) {
                    BlockState supportNear = column.getBlock(minY - 2);
                    BlockState supportDeep = column.getBlock(minY - 8);
                    BlockState coverNear = column.getBlock(maxY + 2);
                    BlockState coverDeep = column.getBlock(maxY + 10);
                    BlockState body = column.getBlock(middleY);
                    supportSolid += solid(supportNear) ? 1 : 0;
                    supportSolid += solid(supportDeep) ? 1 : 0;
                    coverSolid += solid(coverNear) ? 1 : 0;
                    coverSolid += solid(coverDeep) ? 1 : 0;
                    bodySolid += solid(body) ? 1 : 0;
                    liquids += liquid(supportNear) ? 1 : 0;
                    liquids += liquid(supportDeep) ? 1 : 0;
                    liquids += liquid(body) ? 1 : 0;
                }
            }

            if (!isAcceptable(samples, supportSolid, coverSolid, bodySolid, liquids)) {
                continue;
            }
            double score = supportSolid * 3.0 + coverSolid * 2.0 + bodySolid
                    - liquids * 8.0 - Math.abs(shift) * 0.35;
            if (score > bestScore) {
                bestScore = score;
                bestShift = shift;
            }
        }
        return bestShift;
    }

    static boolean isAcceptable(int samples, int supportSolid, int coverSolid,
                                int bodySolid, int liquids) {
        return supportSolid * 100 >= samples * 2 * 80
                && coverSolid * 100 >= samples * 2 * 65
                && bodySolid * 100 >= samples * 55
                && liquids * 100 <= samples * 3 * 8;
    }

    private static boolean solid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private static boolean liquid(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    private static int[] sampleAxis(int min, int max) {
        int insetMin = Math.min(max, min + 4);
        int insetMax = Math.max(insetMin, max - 4);
        int span = insetMax - insetMin;
        return new int[] {
                insetMin,
                insetMin + span / 4,
                insetMin + span / 2,
                insetMin + span * 3 / 4,
                insetMax
        };
    }
}
