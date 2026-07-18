package th.in.midnight_network.immensa.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

/**
 * Owns Terrain Diffusion's chunk-stage ordering.
 *
 * <p>Noise and surface are produced by Minecraft first. The post-carver phase
 * then normalizes base aquifer fluids and places regional surface hydrology so
 * structures and biome features see the final terrain. After every vanilla and
 * modded feature has run, protected underground structures are repaired and
 * conservative surface-cave decoration is applied only to natural blocks.</p>
 */
public final class ImmensaGenerationPipeline {
    private ImmensaGenerationPipeline() {
    }

    /** CARVERS tail: all density caves and vanilla carvers are final. */
    public static void afterCarvers(WorldGenRegion region, ChunkAccess chunk) {
        GeologicalStrataPlacer.place(chunk);
        CaveFluidDrainer.drain(chunk);
        HydrologyWaterPlacer.place(region, chunk);
    }

    /** FEATURES tail: structures, ores, springs and biome decoration are final. */
    public static void afterFeatures(WorldGenLevel world, ChunkAccess chunk,
                                     StructureManager structureAccessor) {
        Registry<Structure> registry = world.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE);
        List<StructureStart> starts = structureAccessor.startsForStructure(
                chunk.getPos(), structure -> true);
        List<BoundingBox> protectedPieces = new ArrayList<>();
        BoundingBox chunkBox = chunkBox(chunk);

        for (StructureStart start : starts) {
            Identifier id = registry.getKey(start.getStructure());
            for (StructurePiece piece : start.getPieces()) {
                BoundingBox protectedBox = piece.getBoundingBox().inflatedBy(3);
                if (protectedBox.intersects(chunkBox)) {
                    protectedPieces.add(protectedBox);
                }
            }
            if (Identifier.withDefaultNamespace("ancient_city").equals(id)) {
                AncientCityCavernCarver.finish(
                        world, new PiecesContainer(start.getPieces()),
                        chunkBox, world.getSeed());
            }
        }

        PolarIceFieldPlacer.place(chunk);
        SurfaceCaveDecorator.decorate(chunk, protectedPieces);
    }

    private static BoundingBox chunkBox(ChunkAccess chunk) {
        int x = chunk.getPos().getMinBlockX();
        int z = chunk.getPos().getMinBlockZ();
        return new BoundingBox(x, chunk.getMinY() + 1, z,
                x + 15, chunk.getMaxY(), z + 15);
    }
}
