package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.pipeline.TerrainMetadata;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaveBiomeSelectorTest {
    private static final long SEED = 0x5eedc0de1234L;

    @Test
    void protectsTheSurfaceAndKeepsDeepDarkAtRealDepth() {
        assertNull(CaveBiomeSelector.select((short) 1, TerrainMetadata.GEO_LIMESTONE,
                0, 110, 0, 120, SEED, true));

        for (int x = -2048; x <= 2048; x += 64) for (int z = -2048; z <= 2048; z += 64) {
            String highMountain = CaveBiomeSelector.select((short) 35, TerrainMetadata.GEO_GRANITIC,
                    x, 240, z, 720, SEED, true);
            assertTrue(!"deep_dark".equals(highMountain),
                    "high-altitude mountain caves must not become deep dark");
        }
    }

    @Test
    void createsDripstoneLushAndDeepDarkProvinces() {
        int dripstone = 0;
        int lush = 0;
        int deepDark = 0;
        int samples = 0;
        for (int x = -3072; x <= 3072; x += 48) for (int z = -3072; z <= 3072; z += 48) {
            if ("dripstone".equals(CaveBiomeSelector.select(
                    (short) 1, TerrainMetadata.GEO_LIMESTONE,
                    x, 24, z, 128, SEED, false))) dripstone++;
            if ("lush".equals(CaveBiomeSelector.select(
                    (short) 23, TerrainMetadata.GEO_GRANITIC,
                    x, 36, z, 128, SEED, false))) lush++;
            if ("deep_dark".equals(CaveBiomeSelector.select(
                    (short) 1, TerrainMetadata.GEO_GRANITIC,
                    x, -32, z, 128, SEED, false))) deepDark++;
            samples++;
        }
        assertTrue(dripstone > samples * 0.35,
                "limestone should produce substantial dripstone provinces: " + dripstone);
        assertTrue(lush > samples * 0.20,
                "wet catchments should produce substantial lush provinces: " + lush);
        assertTrue(deepDark > samples * 0.06 && deepDark < samples * 0.35,
                "deep dark should be present but uncommon: " + deepDark);
    }

    @Test
    void stillLifeAddsSeveralCreativeCaveFamilies() {
        Set<String> biomes = new HashSet<>();
        short[] surfaces = {1, 3, 5, 6, 8, 23, 26, 35};
        byte[] rocks = {
                TerrainMetadata.GEO_SEDIMENTARY, TerrainMetadata.GEO_GRANITIC,
                TerrainMetadata.GEO_LIMESTONE, TerrainMetadata.GEO_VOLCANIC,
                TerrainMetadata.GEO_GLACIAL
        };
        for (short surface : surfaces) for (byte rock : rocks) {
            for (int y : new int[]{48, 0, -36}) {
                for (int x = -2048; x <= 2048; x += 128) {
                    for (int z = -2048; z <= 2048; z += 128) {
                        String selected = CaveBiomeSelector.select(
                                surface, rock, x, y, z, 144, SEED, true);
                        if (selected != null) biomes.add(selected);
                    }
                }
            }
        }
        assertTrue(biomes.contains("dripstone"));
        assertTrue(biomes.contains("lush"));
        assertTrue(biomes.contains("deep_dark"));
        assertTrue(biomes.contains("frozen"));
        assertTrue(biomes.contains("scorched"));
        assertTrue(biomes.contains("glowing"));
        assertTrue(biomes.contains("mushroom"));
        assertTrue(biomes.contains("haunted"));
        assertTrue(biomes.contains("infested"));
        assertTrue(biomes.contains("pale"));
    }

    @Test
    void immensaCaveBiomesExistWithoutStillLife() {
        Set<String> biomes = new HashSet<>();
        short[] surfaces = {1, 6, 8, 23, 26};
        byte[] rocks = {
                TerrainMetadata.GEO_SEDIMENTARY, TerrainMetadata.GEO_GRANITIC,
                TerrainMetadata.GEO_LIMESTONE, TerrainMetadata.GEO_VOLCANIC
        };
        for (short surface : surfaces) for (byte rock : rocks) {
            for (int y : new int[]{32, -24, -48}) {
                for (int x = -4096; x <= 4096; x += 96) {
                    for (int z = -4096; z <= 4096; z += 96) {
                        String selected = CaveBiomeSelector.select(
                                surface, rock, x, y, z, 160, SEED, false);
                        if (selected != null) biomes.add(selected);
                    }
                }
            }
        }
        assertTrue(biomes.contains("limestone_cathedral"));
        assertTrue(biomes.contains("crystal_grotto"));
        assertTrue(biomes.contains("subterranean_wetlands"));
        assertTrue(biomes.contains("volcanic_chambers"));
        assertTrue(biomes.contains("echoing_abyss"));
    }

    @Test
    void giantMountainsReceiveBroadAlpineGalleryBiomes() {
        int alpine = 0;
        int samples = 0;
        for (int x = -3072; x <= 3072; x += 64) {
            for (int z = -3072; z <= 3072; z += 64) {
                String selected = CaveBiomeSelector.select(
                        (short) 35, TerrainMetadata.GEO_GRANITIC,
                        x, 165, z, 620, SEED, true);
                if ("alpine_galleries".equals(selected)) alpine++;
                samples++;
            }
        }
        assertTrue(alpine > samples * 0.45 && alpine < samples * 0.98,
                "alpine galleries should cover broad but varied mountain provinces: " + alpine);
        assertTrue(!"alpine_galleries".equals(CaveBiomeSelector.select(
                        (short) 35, TerrainMetadata.GEO_GRANITIC,
                        0, 165, 0, 260, SEED, true)),
                "ordinary hills must not receive the giant-mountain biome");
    }

    @Test
    void neighboringQuartSamplesUsuallyRemainInTheSameProvince() {
        int transitions = 0;
        int samples = 0;
        for (int x = -4096; x <= 4096; x += 32) for (int z = -4096; z <= 4096; z += 32) {
            String first = CaveBiomeSelector.select((short) 23, TerrainMetadata.GEO_GRANITIC,
                    x, 20, z, 140, SEED, true);
            String neighbor = CaveBiomeSelector.select((short) 23, TerrainMetadata.GEO_GRANITIC,
                    x + 4, 20, z + 4, 140, SEED, true);
            if (!java.util.Objects.equals(first, neighbor)) transitions++;
            samples++;
        }
        assertTrue(transitions < samples * 0.06,
                "cave biomes should form broad regions, not quart-scale speckles: " + transitions);
    }
}
