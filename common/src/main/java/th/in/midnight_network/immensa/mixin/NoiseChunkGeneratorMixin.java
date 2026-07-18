package th.in.midnight_network.immensa.mixin;

import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import th.in.midnight_network.immensa.world.ImmensaBiomeSource;
import th.in.midnight_network.immensa.world.ImmensaGenerationPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {
    /** Prevents the delayed call from wrapping itself a second time. */
    private static final ThreadLocal<Boolean> IMMENSA_ASYNC_BYPASS =
            ThreadLocal.withInitial(() -> false);

    @Inject(method = "createBiomes(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), cancellable = true)
    private void immensa$awaitTileBeforeBiomes(
            RandomState noiseConfig, Blender blender, StructureManager structures, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        if (IMMENSA_ASYNC_BYPASS.get()
                || !(self.getBiomeSource() instanceof ImmensaBiomeSource)) {
            return;
        }

        CompletableFuture<ChunkAccess> delayed = LocalTerrainProvider.getInstance()
                .prewarmBlockAsync(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ())
                .thenCompose(ignored -> {
                    IMMENSA_ASYNC_BYPASS.set(true);
                    try {
                        return self.createBiomes(noiseConfig, blender, structures, chunk);
                    } finally {
                        IMMENSA_ASYNC_BYPASS.remove();
                    }
                });
        cir.setReturnValue(delayed);
    }

    @Inject(method = "fillFromNoise(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), cancellable = true)
    private void immensa$awaitTileBeforeNoise(
            Blender blender, RandomState noiseConfig, StructureManager structures, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        if (IMMENSA_ASYNC_BYPASS.get()
                || !(self.getBiomeSource() instanceof ImmensaBiomeSource)) {
            return;
        }

        CompletableFuture<ChunkAccess> delayed = LocalTerrainProvider.getInstance()
                .prewarmBlockAsync(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ())
                .thenCompose(ignored -> {
                    IMMENSA_ASYNC_BYPASS.set(true);
                    try {
                        return self.fillFromNoise(blender, noiseConfig, structures, chunk);
                    } finally {
                        IMMENSA_ASYNC_BYPASS.remove();
                    }
                });
        cir.setReturnValue(delayed);
    }

    // 1.21.1 signature carries the carving step as a trailing parameter.
    @Inject(method = "applyCarvers(Lnet/minecraft/server/level/WorldGenRegion;JLnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/GenerationStep$Carving;)V",
            at = @At("TAIL"))
    private void immensa$placeHydrology(WorldGenRegion region, long seed, RandomState noiseConfig,
                                                    BiomeManager biomeAccess, StructureManager structures,
                                                    ChunkAccess chunk, GenerationStep.Carving carvingStep,
                                                    CallbackInfo ci) {
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        if (self.getBiomeSource() instanceof ImmensaBiomeSource) {
            ImmensaGenerationPipeline.afterCarvers(region, chunk);
        }
    }
}
