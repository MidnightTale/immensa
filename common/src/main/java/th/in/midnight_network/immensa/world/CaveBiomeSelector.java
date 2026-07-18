package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.pipeline.TerrainMetadata;

/** Selects broad, deterministic underground biome provinces. */
final class CaveBiomeSelector {
    private static final long GEOLOGY_SALT = 0x6a09e667f3bcc909L;
    private static final long MOISTURE_SALT = 0xbb67ae8584caa73bL;
    private static final long DEEP_SALT = 0x3c6ef372fe94f82bL;
    private static final long VARIETY_SALT = 0xa54ff53a5f1d36f1L;

    private CaveBiomeSelector() {
    }

    /**
     * @return a key understood by ImmensaBiomeSource, or {@code null}
     * when the surface biome should continue through this underground region.
     */
    static String select(short surfaceBiome, byte geology,
                         int x, int y, int z, int surfaceY,
                         long worldSeed, boolean stillLifeLoaded) {
        int depth = surfaceY - y;
        if (depth < 14) return null;

        // The long wavelengths deliberately produce recognizable cave regions
        // instead of changing decoration every few quart samples.
        double rockProvince = valueNoise3d(x, y, z, 224, worldSeed ^ GEOLOGY_SALT);
        double moistureProvince = valueNoise3d(x, y, z, 192, worldSeed ^ MOISTURE_SALT);
        double deepProvince = valueNoise3d(x, y, z, 288, worldSeed ^ DEEP_SALT);
        double variety = valueNoise3d(x, y, z, 144, worldSeed ^ VARIETY_SALT);

        boolean wetSurface = isWetSurface(surfaceBiome);
        boolean coldSurface = isColdSurface(surfaceBiome);
        boolean aridSurface = isAridSurface(surfaceBiome);

        // Terrain Diffusion's own cave ecosystems are tied to broad geological
        // provinces. They remain available with or without Still Life installed.
        // High mountain galleries are selected before generic rock biomes so a
        // Still Life surface does not turn every alpine chamber into barren caves.
        if (surfaceY >= 300 && y >= 64 && depth >= 50 && variety > -0.55) {
            return "alpine_galleries";
        }
        if (geology == TerrainMetadata.GEO_LIMESTONE && depth >= 58 && depth <= 260
                && rockProvince > -0.18 && variety > 0.18) {
            return "limestone_cathedral";
        }
        if (geology == TerrainMetadata.GEO_GRANITIC && depth >= 42 && depth <= 260
                && rockProvince > 0.26 && variety > 0.28) {
            return "crystal_grotto";
        }
        if (wetSurface && y >= -44 && depth >= 34 && depth <= 180
                && moistureProvince > 0.24 && variety > -0.12) {
            return "subterranean_wetlands";
        }
        if (geology == TerrainMetadata.GEO_VOLCANIC && y <= 32 && depth >= 56
                && rockProvince > -0.30 && variety > -0.18) {
            return "volcanic_chambers";
        }

        // Deep dark is governed by absolute underground altitude as well as
        // overburden. Tall mountains no longer turn shallow high caves into it.
        if (y <= 8 && depth >= 72 && deepProvince > 0.46
                && geology != TerrainMetadata.GEO_VOLCANIC) {
            return "deep_dark";
        }

        // A quieter transition biome around deep-dark provinces: sparse sculk,
        // fossils and lichen, but no blanket sculk coverage or ancient-city mood.
        if (y <= -18 && depth >= 88 && deepProvince > 0.16 && variety < 0.34
                && geology != TerrainMetadata.GEO_VOLCANIC) {
            return "echoing_abyss";
        }

        // Limestone provinces strongly favour karst/dripstone. Smaller pockets
        // can occur in sedimentary and dry highland rock too.
        boolean karstRock = geology == TerrainMetadata.GEO_LIMESTONE;
        if (depth >= 26 && depth <= 210
                && ((karstRock && rockProvince > -0.38)
                || ((aridSurface || geology == TerrainMetadata.GEO_SEDIMENTARY)
                && rockProvince > 0.68))) {
            return "dripstone";
        }

        // Lush caves follow wet surface catchments but are broad, uncommon
        // subterranean ecosystems rather than a copy of every forest above.
        if (y >= -36 && depth >= 18 && depth <= 150
                && ((wetSurface && moistureProvince > -0.28)
                || moistureProvince > 0.76)) {
            return "lush";
        }

        if (!stillLifeLoaded) return null;

        if (y <= -24 && depth >= 92) {
            if (variety > 0.34) return "haunted";
            if (variety < -0.36) return "infested";
        }
        if ((geology == TerrainMetadata.GEO_VOLCANIC || aridSurface)
                && depth >= 38 && rockProvince > -0.34) {
            return variety > 0.30 ? "scorched" : "barren";
        }
        if (coldSurface || geology == TerrainMetadata.GEO_GLACIAL) {
            return moistureProvince > 0.56 ? "glowing" : "frozen";
        }
        if (wetSurface) {
            if (variety > 0.40) return "glowing";
            if (variety < -0.18) return "mushroom";
        }
        if (depth >= 48 && variety > 0.73) return "pale";
        if (depth >= 76 && variety < -0.66) return "infested";
        return "barren";
    }

    private static boolean isWetSurface(short biome) {
        return switch (biome) {
            case 6, 8, 23, 152, 153 -> true; // swamp/forest/jungle/mangrove/flower forest
            default -> false;
        };
    }

    private static boolean isColdSurface(short biome) {
        return switch (biome) {
            case 3, 11, 16, 31, 32, 33, 48, 116, 150 -> true;
            default -> false;
        };
    }

    private static boolean isAridSurface(short biome) {
        return switch (biome) {
            case 5, 17, 26, 35 -> true;
            default -> false;
        };
    }

    /** Smooth trilinear value noise in [-1, 1]. */
    private static double valueNoise3d(int x, int y, int z, int scale, long seed) {
        int cellX = Math.floorDiv(x, scale);
        int cellY = Math.floorDiv(y, scale);
        int cellZ = Math.floorDiv(z, scale);
        double fx = smooth(Math.floorMod(x, scale) / (double) scale);
        double fy = smooth(Math.floorMod(y, scale) / (double) scale);
        double fz = smooth(Math.floorMod(z, scale) / (double) scale);

        double x00 = lerp(hashUnit(seed, cellX, cellY, cellZ),
                hashUnit(seed, cellX + 1, cellY, cellZ), fx);
        double x10 = lerp(hashUnit(seed, cellX, cellY + 1, cellZ),
                hashUnit(seed, cellX + 1, cellY + 1, cellZ), fx);
        double x01 = lerp(hashUnit(seed, cellX, cellY, cellZ + 1),
                hashUnit(seed, cellX + 1, cellY, cellZ + 1), fx);
        double x11 = lerp(hashUnit(seed, cellX, cellY + 1, cellZ + 1),
                hashUnit(seed, cellX + 1, cellY + 1, cellZ + 1), fx);
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz);
    }

    private static double hashUnit(long seed, int x, int y, int z) {
        long value = seed ^ (long) x * 0x9e3779b97f4a7c15L
                ^ (long) y * 0xd1b54a32d192ed03L
                ^ (long) z * 0xc2b2ae3d27d4eb4fL;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-52 - 1.0;
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double a, double b, double amount) {
        return a + (b - a) * amount;
    }
}
