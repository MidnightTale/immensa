package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.config.ImmensaConfig;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider.HeightmapData;
import th.in.midnight_network.immensa.pipeline.TerrainMetadata;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Places hydrology and geological surface detail after the normal surface pass. */
public final class HydrologyWaterPlacer {
    private HydrologyWaterPlacer() {}

    public static void place(WorldGenRegion region, ChunkAccess chunk) {
        if (!ImmensaConfig.hydrologyEnabled()) return;
        int chunkStartX = chunk.getPos().getMinBlockX();
        int chunkStartZ = chunk.getPos().getMinBlockZ();
        int tileSize = ImmensaConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int tileStartX = (chunkStartX >> tileShift) << tileShift;
        int tileStartZ = (chunkStartZ >> tileShift) << tileShift;
        long worldSeed = LocalTerrainProvider.getSeed();
        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                tileStartZ, tileStartX, tileStartZ + tileSize, tileStartX + tileSize);
        if (data == null || data.waterSurface == null) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkStartX + localX;
            int worldZ = chunkStartZ + localZ;
            int dataX = worldX - tileStartX;
            int dataZ = worldZ - tileStartZ;
            int groundY = HeightConverter.convertToMinecraftHeight(data.heightmap[dataZ][dataX]);
            short landforms = data.landforms[dataZ][dataX];
            short waterMeters = data.waterSurface[dataZ][dataX];
            if (waterMeters == Short.MIN_VALUE) {
                placeDrySurface(chunk, pos, worldX, worldZ, groundY,
                        data.biomeIds[dataZ][dataX], landforms,
                        data.geology[dataZ][dataX], data.soilDepth[dataZ][dataX]);
                continue;
            }

            int waterY = HeightConverter.convertToMinecraftHeight(waterMeters);
            // Never bake transient weather into generated blocks. Chunks are
            // generated at different times; raising only those created during
            // rain permanently leaves one-block walls across a single lake.
            waterY = Math.min(waterY, chunk.getMaxY());
            if (waterY < groundY) continue;
            pos.set(worldX, groundY - 1, worldZ);
            chunk.setBlockState(pos, riverBed(
                    worldX, worldZ, data.lakeMask[dataZ][dataX], landforms, worldSeed), 0);
            for (int y = groundY; y <= waterY; y++) {
                pos.set(worldX, y, worldZ);
                // The heightmap already designates this volume as channel.
                // Replace stray surface blocks too; air-only filling leaves
                // grass shelves and holes that split a connected river.
                chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), 0);
                chunk.markPosForPostprocessing(pos);
            }
            if ((landforms & TerrainMetadata.BRIDGE_SITE) != 0 && waterY + 1 <= chunk.getMaxY()) {
                pos.set(worldX, waterY + 1, worldZ);
                chunk.setBlockState(pos, Blocks.OAK_PLANKS.defaultBlockState(), 0);
            } else if ((landforms & TerrainMetadata.PORT_SITE) != 0 && waterY + 1 <= chunk.getMaxY()) {
                pos.set(worldX, waterY + 1, worldZ);
                chunk.setBlockState(pos, Blocks.SPRUCE_PLANKS.defaultBlockState(), 0);
            }
        }
    }

    private static void placeDrySurface(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, int groundY,
                                        short biomeId, short landforms, byte geology, byte soilDepth) {
        int surfaceY = Math.min(chunk.getMaxY(), groundY - 1);
        if (surfaceY < chunk.getMinY()) return;
        pos.set(x, surfaceY, z);
        BlockState current = chunk.getBlockState(pos);
        if (current.isAir() || current.is(Blocks.WATER) || current.is(Blocks.LAVA)) return;

        int hash = mix(x, z);
        applyBiomeSurface(chunk, pos, x, z, surfaceY, biomeId, hash);
        pos.set(x, surfaceY, z);
        BlockState replacement = null;
        if ((landforms & TerrainMetadata.BRIDGE_SITE) != 0) {
            replacement = (hash & 1) == 0 ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
        } else if ((landforms & TerrainMetadata.WETLAND) != 0) {
            replacement = (hash & 3) == 0 ? Blocks.CLAY.defaultBlockState() : Blocks.MUD.defaultBlockState();
        } else if ((landforms & TerrainMetadata.SCREE) != 0) {
            replacement = (hash & 3) == 0 ? Blocks.GRAVEL.defaultBlockState() : exposedRock(geology, hash);
        } else if (geology == TerrainMetadata.GEO_VOLCANIC && (hash & 15) < 5) {
            replacement = (hash & 1) == 0 ? Blocks.BASALT.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
        } else if (geology == TerrainMetadata.GEO_LIMESTONE && soilDepth <= 2 && (hash & 7) < 3) {
            replacement = Blocks.CALCITE.defaultBlockState();
        } else if (geology == TerrainMetadata.GEO_GLACIAL && soilDepth <= 2 && (hash & 7) < 2) {
            replacement = Blocks.GRAVEL.defaultBlockState();
        }
        if (replacement != null) chunk.setBlockState(pos, replacement, 0);

    }

    private static void applyBiomeSurface(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z,
                                          int surfaceY, short biomeId, int hash) {
        BlockState top = null;
        BlockState under = null;
        int depth = 3;
        switch (biomeId) {
            case 5, 149, 150 -> {
                top = Blocks.SAND.defaultBlockState();
                under = Blocks.SANDSTONE.defaultBlockState();
            }
            case 26 -> {
                top = Blocks.RED_SAND.defaultBlockState();
                under = Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            }
            case 151 -> {
                top = (hash & 3) == 0 ? Blocks.STONE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
                under = Blocks.STONE.defaultBlockState();
                depth = 2;
            }
            case 6, 152 -> {
                if ((hash & 3) != 0) top = Blocks.MUD.defaultBlockState();
                under = Blocks.DIRT.defaultBlockState();
                depth = 2;
            }
            default -> { return; }
        }

        for (int layer = 0; layer < depth; layer++) {
            int y = surfaceY - layer;
            if (y < chunk.getMinY()) break;
            pos.set(x, y, z);
            BlockState existing = chunk.getBlockState(pos);
            if (existing.isAir() || existing.is(Blocks.WATER) || existing.is(Blocks.LAVA)) break;
            BlockState state = layer == 0 && top != null ? top : under;
            if (state != null) chunk.setBlockState(pos, state, 0);
        }
    }

    private static BlockState exposedRock(byte geology, int hash) {
        return switch (geology) {
            case TerrainMetadata.GEO_GRANITIC -> (hash & 1) == 0
                    ? Blocks.GRANITE.defaultBlockState() : Blocks.ANDESITE.defaultBlockState();
            case TerrainMetadata.GEO_LIMESTONE -> Blocks.CALCITE.defaultBlockState();
            case TerrainMetadata.GEO_VOLCANIC -> (hash & 1) == 0
                    ? Blocks.BASALT.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
            case TerrainMetadata.GEO_GLACIAL -> Blocks.TUFF.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    static BlockState riverBed(int x, int z, boolean lake, short landforms, long worldSeed) {
        return switch (riverBedMaterial(x, z, lake, landforms, worldSeed)) {
            case 0 -> Blocks.CLAY.defaultBlockState();
            case 1 -> Blocks.SAND.defaultBlockState();
            case 2 -> Blocks.GRAVEL.defaultBlockState();
            case 3 -> Blocks.MUD.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    static int riverBedMaterial(int x, int z, boolean lake, short landforms, long worldSeed) {
        // Smooth value noise creates sediment patches. The previous low four
        // bits of a linear x/z hash produced long repeating stripes underwater.
        double broad = bedNoise(x, z, 17, worldSeed ^ 0x6a09e667f3bcc909L);
        double detail = bedNoise(x, z, 5, worldSeed ^ 0xbb67ae8584caa73bL);
        double sediment = broad * 0.72 + detail * 0.28;
        if ((landforms & TerrainMetadata.DELTA) != 0) {
            return sediment > -0.28 ? 1 : 3;
        }
        if ((landforms & (TerrainMetadata.WETLAND | TerrainMetadata.OXBOW)) != 0) {
            return sediment < -0.32 ? 0 : 3;
        }
        if ((landforms & TerrainMetadata.WATERFALL) != 0) return 4;
        if ((landforms & TerrainMetadata.GREAT_RIVER) != 0) {
            if (sediment < -0.48) return 4;
            if (sediment < -0.08) return 2;
            if (sediment < 0.44) return 1;
            return 0;
        }
        if (lake) {
            if (sediment < -0.24) return 0;
            if (sediment > 0.34) return 2;
            return 1;
        }
        if (sediment > -0.12) return 2;
        if (sediment > -0.48) return 1;
        return 0;
    }

    private static double bedNoise(int x, int z, int scale, long seed) {
        int cellX = Math.floorDiv(x, scale);
        int cellZ = Math.floorDiv(z, scale);
        double fx = Math.floorMod(x, scale) / (double) scale;
        double fz = Math.floorMod(z, scale) / (double) scale;
        fx = fx * fx * (3.0 - 2.0 * fx);
        fz = fz * fz * (3.0 - 2.0 * fz);
        double top = lerp(hashUnit(seed, cellX, cellZ),
                hashUnit(seed, cellX + 1, cellZ), fx);
        double bottom = lerp(hashUnit(seed, cellX, cellZ + 1),
                hashUnit(seed, cellX + 1, cellZ + 1), fx);
        return lerp(top, bottom, fz);
    }

    private static double hashUnit(long seed, int x, int z) {
        long value = seed ^ (long) x * 0x9e3779b97f4a7c15L
                ^ (long) z * 0xc2b2ae3d27d4eb4fL;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-52 - 1.0;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int mix(int x, int z) {
        int hash = x * 0x1f1f1f1f ^ z * 0x6d2b79f5;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        return hash ^ hash >>> 15;
    }
}
