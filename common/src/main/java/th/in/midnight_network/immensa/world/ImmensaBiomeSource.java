package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.config.ImmensaConfig;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider;
import th.in.midnight_network.immensa.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import th.in.midnight_network.immensa.platform.Platforms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Map.entry;

public class ImmensaBiomeSource extends BiomeSource {
    private static final ResourceKey<Biome> FOREST_SPARSE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("immensa", "forest_sparse"));
    private static final ResourceKey<Biome> TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("immensa", "taiga_sparse"));
    private static final ResourceKey<Biome> SNOWY_TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("immensa", "snowy_taiga_sparse"));
    private static final ResourceKey<Biome> LIMESTONE_CATHEDRAL = immensaBiome("limestone_cathedral");
    private static final ResourceKey<Biome> CRYSTAL_GROTTO = immensaBiome("crystal_grotto");
    private static final ResourceKey<Biome> SUBTERRANEAN_WETLANDS = immensaBiome("subterranean_wetlands");
    private static final ResourceKey<Biome> VOLCANIC_CHAMBERS = immensaBiome("volcanic_chambers");
    private static final ResourceKey<Biome> ECHOING_ABYSS = immensaBiome("echoing_abyss");
    private static final ResourceKey<Biome> ALPINE_GALLERIES = immensaBiome("alpine_galleries");
    private static final boolean STILL_LIFE_LOADED = Platforms.get().isModLoaded("mr_still_life");
    private static final int TILE_SIZE = ImmensaConfig.tileSize();
    private static final int TILE_SHIFT = Integer.numberOfTrailingZeros(TILE_SIZE);

    public static final MapCodec<ImmensaBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, instance.stable(ImmensaBiomeSource::new)));


    private HolderGetter<Biome> biomeLookup;
    private volatile Map<Short, Holder<Biome>> biomeIdMap = null;
    private volatile Map<String, Holder<Biome>> caveBiomeMap = Map.of();
    private final ThreadLocal<TileContext> tileContext = ThreadLocal.withInitial(TileContext::new);

    public ImmensaBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private void requireBiomeIdMap() {
        if (biomeIdMap != null) return;
        synchronized (this) {
            if (biomeIdMap != null) return;
            Map<String, Holder<Biome>> caves = new HashMap<>();
            caves.put("dripstone", biomeLookup.getOrThrow(Biomes.DRIPSTONE_CAVES));
            caves.put("lush", biomeLookup.getOrThrow(Biomes.LUSH_CAVES));
            caves.put("deep_dark", biomeLookup.getOrThrow(Biomes.DEEP_DARK));
            caves.put("limestone_cathedral", biomeLookup.getOrThrow(LIMESTONE_CATHEDRAL));
            caves.put("crystal_grotto", biomeLookup.getOrThrow(CRYSTAL_GROTTO));
            caves.put("subterranean_wetlands", biomeLookup.getOrThrow(SUBTERRANEAN_WETLANDS));
            caves.put("volcanic_chambers", biomeLookup.getOrThrow(VOLCANIC_CHAMBERS));
            caves.put("echoing_abyss", biomeLookup.getOrThrow(ECHOING_ABYSS));
            caves.put("alpine_galleries", biomeLookup.getOrThrow(ALPINE_GALLERIES));
            if (STILL_LIFE_LOADED) {
                caves.put("barren", stillLifeBiome("barren_caves"));
                caves.put("frozen", stillLifeBiome("frozen_caves"));
                caves.put("glowing", stillLifeBiome("glowing_caves"));
                caves.put("haunted", stillLifeBiome("haunted_depths"));
                caves.put("infested", stillLifeBiome("infested_tunnels"));
                caves.put("mushroom", stillLifeBiome("mushroom_caves"));
                caves.put("pale", stillLifeBiome("pale_hollow"));
                caves.put("scorched", stillLifeBiome("scorched_caves"));
            }
            // Publish dependent cave entries before the volatile biome map.
            caveBiomeMap = Map.copyOf(caves);
            biomeIdMap = STILL_LIFE_LOADED ? stillLifeBiomeMap() : vanillaBiomeMap();
        }
    }

    private Map<Short, Holder<Biome>> vanillaBiomeMap() {
        return Map.ofEntries(
                    entry((short) 1, this.biomeLookup.getOrThrow(Biomes.PLAINS)),
                    entry((short) 3, this.biomeLookup.getOrThrow(Biomes.SNOWY_PLAINS)),
                    entry((short) 5, this.biomeLookup.getOrThrow(Biomes.DESERT)),
                    entry((short) 6, this.biomeLookup.getOrThrow(Biomes.SWAMP)),
                    entry((short) 7, this.biomeLookup.getOrThrow(Biomes.RIVER)),
                    entry((short) 8, this.biomeLookup.getOrThrow(Biomes.FOREST)),
                    entry((short) 11, this.biomeLookup.getOrThrow(Biomes.FROZEN_RIVER)),
                    entry((short) 15, this.biomeLookup.getOrThrow(Biomes.TAIGA)),
                    entry((short) 16, this.biomeLookup.getOrThrow(Biomes.SNOWY_TAIGA)),
                    entry((short) 17, this.biomeLookup.getOrThrow(Biomes.SAVANNA)),
                    entry((short) 19, this.biomeLookup.getOrThrow(Biomes.WINDSWEPT_HILLS)),
                    entry((short) 23, this.biomeLookup.getOrThrow(Biomes.JUNGLE)),
                    entry((short) 26, this.biomeLookup.getOrThrow(Biomes.BADLANDS)),
                    entry((short) 29, this.biomeLookup.getOrThrow(Biomes.MEADOW)),
                    entry((short) 31, this.biomeLookup.getOrThrow(Biomes.GROVE)),
                    entry((short) 32, this.biomeLookup.getOrThrow(Biomes.SNOWY_SLOPES)),
                    entry((short) 33, this.biomeLookup.getOrThrow(Biomes.FROZEN_PEAKS)),
                    entry((short) 35, this.biomeLookup.getOrThrow(Biomes.STONY_PEAKS)),
                    entry((short) 41, this.biomeLookup.getOrThrow(Biomes.WARM_OCEAN)),
                    entry((short) 44, this.biomeLookup.getOrThrow(Biomes.OCEAN)),
                    entry((short) 46, this.biomeLookup.getOrThrow(Biomes.COLD_OCEAN)),
                    entry((short) 48, this.biomeLookup.getOrThrow(Biomes.FROZEN_OCEAN)),
                    entry((short) 149, this.biomeLookup.getOrThrow(Biomes.BEACH)),
                    entry((short) 150, this.biomeLookup.getOrThrow(Biomes.SNOWY_BEACH)),
                    entry((short) 151, this.biomeLookup.getOrThrow(Biomes.STONY_SHORE)),
                    entry((short) 152, this.biomeLookup.getOrThrow(Biomes.MANGROVE_SWAMP)),
                    entry((short) 153, this.biomeLookup.getOrThrow(Biomes.FLOWER_FOREST)),
                    entry((short) 154, this.biomeLookup.getOrThrow(Biomes.OLD_GROWTH_PINE_TAIGA)),
                    entry((short) 108, this.biomeLookup.getOrThrow(FOREST_SPARSE)),
                    entry((short) 115, this.biomeLookup.getOrThrow(TAIGA_SPARSE)),
                    entry((short) 116, this.biomeLookup.getOrThrow(SNOWY_TAIGA_SPARSE))
            );
    }

    /**
     * Keeps Terrain Diffusion's terrain/climate classification while allowing
     * the locally installed Still Life pack to provide biome decoration.
     */
    private Map<Short, Holder<Biome>> stillLifeBiomeMap() {
        return Map.ofEntries(
                entry((short) 1, stillLifeBiome("mixed_forest_steppe")),
                entry((short) 3, stillLifeBiome("snowy_tundra")),
                entry((short) 5, stillLifeBiome("arid_desert")),
                entry((short) 6, stillLifeBiome("temperate_swamp")),
                entry((short) 7, stillLifeBiome("temperate_river")),
                entry((short) 8, stillLifeBiome("temperate_forest")),
                entry((short) 11, stillLifeBiome("arctic_river")),
                entry((short) 15, stillLifeBiome("evergreen_taiga")),
                entry((short) 16, stillLifeBiome("cold_taiga")),
                entry((short) 17, stillLifeBiome("grassland_savanna")),
                entry((short) 19, stillLifeBiome("wooded_highlands")),
                entry((short) 23, stillLifeBiome("tropical_rainforest")),
                entry((short) 26, stillLifeBiome("arid_highlands")),
                entry((short) 29, stillLifeBiome("alpine_heathlands")),
                entry((short) 31, stillLifeBiome("larch_woodlands")),
                entry((short) 32, stillLifeBiome("snowy_boreal_alpine_tundra")),
                entry((short) 33, stillLifeBiome("snowy_peaks")),
                entry((short) 35, stillLifeBiome("barren_peaks")),
                entry((short) 41, stillLifeBiome("tropical_shallow_ocean")),
                entry((short) 44, stillLifeBiome("temperate_shallow_ocean")),
                entry((short) 46, stillLifeBiome("cold_shallow_ocean")),
                entry((short) 48, stillLifeBiome("arctic_shallow_ocean")),
                entry((short) 149, stillLifeBiome("temperate_beach")),
                entry((short) 150, stillLifeBiome("arctic_beach")),
                entry((short) 151, stillLifeBiome("taiga_beach")),
                entry((short) 152, stillLifeBiome("mangrove_marsh")),
                entry((short) 153, stillLifeBiome("warm_temperate_clearings")),
                entry((short) 154, stillLifeBiome("old_growth_temperate_forest")),
                entry((short) 108, stillLifeBiome("warm_temperate_woodlands")),
                entry((short) 115, stillLifeBiome("larch_woodlands")),
                entry((short) 116, stillLifeBiome("cold_taiga_clearings"))
        );
    }

    private Holder<Biome> stillLifeBiome(String path) {
        return biomeLookup.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("still_life", path)));
    }

    private static ResourceKey<Biome> immensaBiome(String path) {
        return ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("immensa", path));
    }

    @Override
    public Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIdMap();
        return Stream.concat(biomeIdMap.values().stream(), caveBiomeMap.values().stream()).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIdMap();
        Holder<Biome> defaultEntry = biomeIdMap.get((short) 1);

        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockZ = QuartPos.toBlock(z);

        int blockStartX = (blockX >> TILE_SHIFT) << TILE_SHIFT;
        int blockStartZ = (blockZ >> TILE_SHIFT) << TILE_SHIFT;
        long seed = LocalTerrainProvider.getSeed();
        TileContext context = tileContext.get();
        HeightmapData data;
        if (context.data != null && context.seed == seed
                && context.blockStartX == blockStartX && context.blockStartZ == blockStartZ) {
            data = context.data;
        } else {
            data = LocalTerrainProvider.getInstance().fetchHeightmap(
                    blockStartZ, blockStartX,
                    blockStartZ + TILE_SIZE, blockStartX + TILE_SIZE);
            context.seed = seed;
            context.blockStartX = blockStartX;
            context.blockStartZ = blockStartZ;
            context.data = data;
        }
        if (data != null && data.biomeIds != null) {
            int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));
            short biomeId = data.biomeIds[localZ][localX];
            if (!caveBiomeMap.isEmpty()) {
                int blockY = QuartPos.toBlock(y);
                int surfaceY = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
                // Cave biomes follow actual underground depth, including caves inside
                // Terrain Diffusion's tall mountain ranges above vanilla sea level.
                if (blockY < surfaceY - 12) {
                    Holder<Biome> cave = caveBiome(
                            biomeId, data.geology[localZ][localX],
                            blockX, blockY, blockZ, surfaceY, seed);
                    if (cave != null) return cave;
                }
            }
            Holder<Biome> entry = biomeIdMap.get(biomeId);
            if (entry != null) return entry;
        }

        return defaultEntry;
    }

    /** Per worldgen worker cache; avoids a concurrent-map and atomic-clock hit per quart sample. */
    private static final class TileContext {
        long seed = Long.MIN_VALUE;
        int blockStartX;
        int blockStartZ;
        HeightmapData data;
    }

    private Holder<Biome> caveBiome(short surfaceBiome, byte geology,
                                            int x, int y, int z, int surfaceY, long seed) {
        String selected = CaveBiomeSelector.select(
                surfaceBiome, geology, x, y, z, surfaceY, seed, STILL_LIFE_LOADED);
        return selected == null ? null : caveBiomeMap.get(selected);
    }

    /**
     * Concentric-ring structures call this overload for every planned stronghold
     * during world bootstrap. Vanilla samples a 224-block square around each ring
     * point. For an AI-backed biome source that would synchronously enqueue dozens
     * of remote 256x256 terrain tiles before spawn can even start, leaving the UI
     * apparently stuck on "Loading terrain".
     *
     * Stronghold placement only consumes the returned position, and Terrain
     * Diffusion's compatibility tags allow strongholds in every emitted land and
     * cave biome. Returning the original ring point when any compatible biome is
     * present preserves deterministic vanilla rings without generating remote
     * terrain. The WorldView overload remains inherited for explicit /locate biome
     * searches, where an actual spatial search is expected.
     */
    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(
            int x, int y, int z, int radius, int blockCheckInterval,
            Predicate<Holder<Biome>> predicate, RandomSource random, boolean findClosest,
            Climate.Sampler noiseSampler) {
        Holder<Biome> compatible = possibleBiomes().stream()
                .filter(predicate)
                .findFirst()
                .orElse(null);
        return compatible == null ? null : Pair.of(new BlockPos(x, y, z), compatible);
    }

}

