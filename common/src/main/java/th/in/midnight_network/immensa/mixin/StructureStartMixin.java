package th.in.midnight_network.immensa.mixin;

import th.in.midnight_network.immensa.world.AncientCityCavernCarver;
import th.in.midnight_network.immensa.world.ImmensaBiomeSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives Ancient Cities a complete chamber rather than burying their open plazas. */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @Shadow @Final private Structure structure;
    @Shadow @Final private PiecesContainer pieceContainer;

    @Inject(method = "placeInChunk", at = @At("HEAD"))
    private void immensa$clearAncientCityChamber(
            WorldGenLevel world, StructureManager structureAccessor,
            ChunkGenerator chunkGenerator, RandomSource random, BoundingBox chunkBox,
            ChunkPos chunkPos, CallbackInfo ci) {
        if (!(chunkGenerator.getBiomeSource() instanceof ImmensaBiomeSource)
                || pieceContainer.isEmpty()) {
            return;
        }
        Identifier id = world.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE)
                .getKey(structure);
        if (id == null || !id.equals(Identifier.withDefaultNamespace("ancient_city"))) return;

        AncientCityCavernCarver.carve(
                world, pieceContainer, chunkBox, world.getSeed());
    }
}
