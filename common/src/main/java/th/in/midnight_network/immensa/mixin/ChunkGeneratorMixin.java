package th.in.midnight_network.immensa.mixin;

import th.in.midnight_network.immensa.world.ImmensaBiomeSource;
import th.in.midnight_network.immensa.world.ImmensaGenerationPipeline;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks passes that require all vanilla structures and biome features to exist. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
    private void immensa$finishGeneration(
            WorldGenLevel world, ChunkAccess chunk,
            StructureManager structureAccessor, CallbackInfo ci) {
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        if (self.getBiomeSource() instanceof ImmensaBiomeSource) {
            ImmensaGenerationPipeline.afterFeatures(
                    world, chunk, structureAccessor);
        }
    }
}
