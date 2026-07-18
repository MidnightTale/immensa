package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.config.ImmensaConfig;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public class ImmensaDensityFunction implements DensityFunction {
    private static final int TILE_SIZE = ImmensaConfig.tileSize();
    private static final int TILE_SHIFT = Integer.numberOfTrailingZeros(TILE_SIZE);
    public static final MapCodec<ImmensaDensityFunction> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.BOOL.optionalFieldOf("caves", true)
                            .forGetter(function -> function.carveCaves)
            ).apply(instance, ImmensaDensityFunction::new));

    public static final KeyDispatchDataCodec<ImmensaDensityFunction> CODEC_HOLDER = KeyDispatchDataCodec.of(CODEC);
    private final boolean carveCaves;
    private final ThreadLocal<FillContext> sampleContext = ThreadLocal.withInitial(FillContext::new);

    public ImmensaDensityFunction() {
        this(true);
    }

    public ImmensaDensityFunction(boolean carveCaves) {
        this.carveCaves = carveCaves;
    }

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        return computeDensity(pos);
    }

    public double computeDensity(DensityFunction.FunctionContext context) {
        int x = context.blockX();
        int z = context.blockZ();
        int y = context.blockY();
        long worldSeed = LocalTerrainProvider.getSeed();
        FillContext tile = sampleContext.get();
        tile.update(x, z, worldSeed);
        HeightmapData data = tile.data;
        if (data == null || data.heightmap == null) {
            return -y;
        }

        int targetHeight = tile.targetHeight(x, z);
        double terrainDensity = targetHeight - y;
        if (!carveCaves) return terrainDensity;
        ImmensaEntranceSelector.Site entrance = tile.entrance;
        double caveDensity = ImmensaCaveSampler.apply(
                terrainDensity, x, y, z, targetHeight, worldSeed,
                entrance.x(), entrance.z(), entrance.surfaceY(),
                entrance.slopeX(), entrance.slopeZ(), entrance.scale(), entrance.reach());
        return ImmensaCliffSampler.apply(
                caveDensity, x, y, z, targetHeight, tile.landforms(x, z), worldSeed);
    }

    private static final class FillContext {
        int blockStartX, blockStartZ, blockEndX, blockEndZ;
        long worldSeed = Long.MIN_VALUE;
        HeightmapData data;
        ImmensaEntranceSelector.Site entrance = ImmensaEntranceSelector.Site.NONE;
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        int lastTargetHeight;

        void update(int x, int z, long worldSeed) {
            if (this.worldSeed != worldSeed
                    || x < blockStartX || x >= blockEndX
                    || z < blockStartZ || z >= blockEndZ) {
                this.init(x, z, worldSeed);
            }
        }

        void init(int x, int z, long worldSeed) {
            int tileX = x >> TILE_SHIFT;
            int tileZ = z >> TILE_SHIFT;

            this.blockStartX = tileX << TILE_SHIFT;
            this.blockStartZ = tileZ << TILE_SHIFT;
            this.blockEndX = blockStartX + TILE_SIZE;
            this.blockEndZ = blockStartZ + TILE_SIZE;

            this.data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
            this.entrance = ImmensaEntranceSelector.find(data, blockStartX, blockStartZ,
                    worldSeed);
            this.worldSeed = worldSeed;
            this.lastX = Integer.MIN_VALUE;
            this.lastZ = Integer.MIN_VALUE;
        }

        int targetHeight(int x, int z) {
            if (x != lastX || z != lastZ) {
                int localX = Math.max(0, Math.min(data.width - 1, x - blockStartX));
                int localZ = Math.max(0, Math.min(data.height - 1, z - blockStartZ));
                lastTargetHeight = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
                lastX = x;
                lastZ = z;
            }
            return lastTargetHeight;
        }

        short landforms(int x, int z) {
            int localX = Math.max(0, Math.min(data.width - 1, x - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, z - blockStartZ));
            return data.landforms[localZ][localX];
        }
    }

    @Override
    public void fillArray(double[] densities, DensityFunction.ContextProvider applier) {
        if (densities.length == 0) return;

        FillContext ctx = new FillContext();
        DensityFunction.FunctionContext pos = applier.forIndex(0);
        int x = pos.blockX();
        int z = pos.blockZ();
        int y = pos.blockY();
        long worldSeed = LocalTerrainProvider.getSeed();
        ctx.init(x, z, worldSeed);

        for (int i = 0; i < densities.length; i++) {
            pos = applier.forIndex(i);
            x = pos.blockX();
            z = pos.blockZ();
            y = pos.blockY();
            ctx.update(x, z, worldSeed);

            HeightmapData data = ctx.data;
            if (data == null || data.heightmap == null) {
                densities[i] = -y;
                continue;
            }

            int targetHeight = ctx.targetHeight(x, z);
            double terrainDensity = targetHeight - y;
            ImmensaEntranceSelector.Site entrance = ctx.entrance;
            if (!carveCaves) {
                densities[i] = terrainDensity;
                continue;
            }
            double caveDensity = ImmensaCaveSampler.apply(
                    terrainDensity, x, y, z, targetHeight, worldSeed,
                    entrance.x(), entrance.z(), entrance.surfaceY(),
                    entrance.slopeX(), entrance.slopeZ(), entrance.scale(), entrance.reach());
            densities[i] = ImmensaCliffSampler.apply(
                    caveDensity, x, y, z, targetHeight, ctx.landforms(x, z), worldSeed);
        }
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public double minValue() {
        return -64;
    }

    @Override
    public double maxValue() {
        return 1024;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC_HOLDER;
    }

}
