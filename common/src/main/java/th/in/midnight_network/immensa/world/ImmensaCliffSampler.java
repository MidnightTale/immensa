package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.config.ImmensaConfig;
import th.in.midnight_network.immensa.pipeline.TerrainMetadata;

/**
 * Converts selected steep heightfield faces into restrained three-dimensional
 * rock ledges. The preliminary surface remains stable for structures and biome
 * rules; only final density receives the projecting cap and shallow undercut.
 */
final class ImmensaCliffSampler {
    private ImmensaCliffSampler() {
    }

    static double apply(double terrainDensity, int x, int y, int z,
                        int targetHeight, short landforms, long worldSeed) {
        if (!ImmensaConfig.cliffOverhangsEnabled()
                || (landforms & TerrainMetadata.CLIFF) == 0) {
            return terrainDensity;
        }
        // Even at maximum configured strength the feature occupies only this
        // shallow surface band. Most vertical density samples exit here.
        if (y < targetHeight - 32 || y > targetHeight + 16) {
            return terrainDensity;
        }

        boolean major = (landforms & TerrainMetadata.MAJOR_CLIFF) != 0;
        int direction = TerrainMetadata.cliffDirection(landforms);
        int highX = direction == TerrainMetadata.CLIFF_EAST ? 1
                : direction == TerrainMetadata.CLIFF_WEST ? -1 : 0;
        int highZ = direction == TerrainMetadata.CLIFF_SOUTH ? 1
                : direction == TerrainMetadata.CLIFF_NORTH ? -1 : 0;
        double across = x * (double) highX + z * (double) highZ;
        double along = x * (double) -highZ + z * (double) highX;

        // Broad face provinces prevent every steep slope from acquiring the
        // same shelf. A smaller along-face modulation breaks cliff lips into
        // connected natural projections without per-block hash noise.
        double province = valueNoise(x, z, 220.0, worldSeed ^ 0x4a39b70d1c2e8f65L);
        double face = valueNoise(along, across * 0.31, 62.0,
                worldSeed ^ 0x9e3779b97f4a7c15L);
        double presence = smootherStep((province + (major ? 0.28 : 0.08)) / 0.58)
                * smootherStep((face + 0.48) / 0.72);
        if (presence < 0.18) return terrainDensity;

        double strength = ImmensaConfig.cliffOverhangStrength();
        double detail = valueNoise(x, z, 27.0, worldSeed ^ 0xd1b54a32d192ed03L);
        int capRise = Math.max(2, (int) Math.round(
                (major ? 7.0 : 4.0) * strength * (0.78 + presence * 0.34)
                        + detail * 1.4));
        int undercutDepth = Math.max(3, (int) Math.round(
                (major ? 13.0 : 7.0) * strength * (0.72 + presence * 0.38)
                        - detail * 1.8));

        int capBottom = targetHeight - 2;
        int capTop = targetHeight + capRise;
        if (y >= capBottom && y <= capTop) {
            double edgeDistance = Math.min(y - capBottom, capTop - y);
            // Positive independent density adds a projecting stone lip even
            // where the original heightfield column is already air.
            double ledgeDensity = 0.45 + Math.max(0.0, edgeDistance)
                    * (major ? 0.34 : 0.28);
            terrainDensity = Math.max(terrainDensity, ledgeDensity * presence);
        }

        // Only strong coherent portions receive an undercut. Keeping two solid
        // layers beneath the lip guarantees attachment to the upper rock mass.
        int notchTop = targetHeight - 3;
        int notchBottom = notchTop - undercutDepth;
        if (major && presence > 0.42 && y >= notchBottom && y <= notchTop) {
            double edgeDistance = Math.min(y - notchBottom, notchTop - y);
            double notchDensity = -(0.42 + Math.max(0.0, edgeDistance) * 0.24)
                    * presence;
            terrainDensity = Math.min(terrainDensity, notchDensity);
        }
        return terrainDensity;
    }

    private static double valueNoise(double x, double z, double wavelength, long seed) {
        double gx = x / wavelength, gz = z / wavelength;
        long x0 = fastFloor(gx), z0 = fastFloor(gz);
        double tx = smooth(gx - x0), tz = smooth(gz - z0);
        double a = randomSigned(x0, z0, seed);
        double b = randomSigned(x0 + 1, z0, seed);
        double c = randomSigned(x0, z0 + 1, seed);
        double d = randomSigned(x0 + 1, z0 + 1, seed);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    private static double randomSigned(long x, long z, long seed) {
        long value = seed ^ x * 0x632be59bd9b4e019L ^ z * 0x9e3779b97f4a7c15L;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static long fastFloor(double value) {
        long whole = (long) value;
        return value < whole ? whole - 1 : whole;
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double smootherStep(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * clamped
                * (clamped * (clamped * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
