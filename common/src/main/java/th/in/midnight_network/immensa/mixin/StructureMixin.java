package th.in.midnight_network.immensa.mixin;

import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import th.in.midnight_network.immensa.world.ImmensaBiomeSource;
import th.in.midnight_network.immensa.world.TrialChamberPlacementValidator;
import com.mojang.datafixers.util.Either;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

/** Keeps surface structures off channels, wetlands, cliffs and unstable slopes. */
@Mixin(Structure.class)
public abstract class StructureMixin {
    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void immensa$validateTerrainSite(
            Structure.GenerationContext context,
            CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        if (!(context.biomeSource() instanceof ImmensaBiomeSource)) return;
        Optional<Structure.GenerationStub> result = cir.getReturnValue();
        if (result.isEmpty()) return;

        // Data-driven structure packs often provide their own slope adaptation
        // and footprint checks. Do not silently suppress those structures with
        // Terrain Diffusion's conservative vanilla-site filter. Replacement
        // packs that keep a minecraft:* structure id still retain the filter.
        Identifier structureId = context.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE)
                .getKey((Structure) (Object) this);
        if (structureId == null || !Identifier.DEFAULT_NAMESPACE.equals(structureId.getNamespace())) return;

        if (structureId.getPath().equals("trial_chambers")) {
            Structure.GenerationStub position = result.get();
            StructurePiecesBuilder pieces = position.getPiecesBuilder();
            if (pieces.isEmpty()) {
                cir.setReturnValue(Optional.empty());
                return;
            }
            int shift = TrialChamberPlacementValidator.chooseVerticalShift(
                    context, pieces.getBoundingBox());
            if (shift == TrialChamberPlacementValidator.REJECT) {
                cir.setReturnValue(Optional.empty());
                return;
            }
            if (shift != 0) pieces.offsetPiecesVertically(shift);
            BlockPos shiftedPosition = position.position().offset(0, shift, 0);
            cir.setReturnValue(Optional.of(new Structure.GenerationStub(
                    shiftedPosition, Either.right(pieces))));
            return;
        }

        // Only broad-footprint settlements need the conservative regional
        // stability gate. Applying it to every vanilla id makes monuments,
        // portals, temples, mineshafts and datapack replacements needlessly
        // rare in a high-relief Terrain Diffusion world.
        String structurePath = structureId.getPath();
        boolean needsStableSurface = structurePath.startsWith("village_")
                || structurePath.equals("pillager_outpost")
                || structurePath.equals("woodland_mansion");
        if (!needsStableSurface) return;

        // Deep structures have their own terrain constraints. Surface candidates
        // normally use y=0 or above and benefit from the regional stability map.
        int y = result.get().position().getY();
        if (y < 0) return;
        int x = context.chunkPos().getMiddleBlockX();
        int z = context.chunkPos().getMiddleBlockZ();
        if (!LocalTerrainProvider.getInstance().isStructureSiteSuitable(x, z)) {
            cir.setReturnValue(Optional.empty());
        }
    }
}
