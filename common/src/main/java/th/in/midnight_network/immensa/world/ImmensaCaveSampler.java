package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.config.ImmensaConfig;
import th.in.midnight_network.immensa.pipeline.FastNoiseLite;

/**
 * Fast, deterministic cave density shared by scalar and batched worldgen.
 *
 * <p>The low-frequency field forms chambers, the intersection of two signed
 * noise sheets forms long connected tunnels, and sparse hillside mouths give a
 * small number of those networks natural surface entrances. Instances are
 * immutable after publication, so C2ME can sample them from many threads.</p>
 */
public final class ImmensaCaveSampler {
    static final int BEDROCK_PROTECTION_Y = -61;
    private static final int MEGA_CHAMBER_CELL_SIZE = 512;
    private static final int MOUNTAIN_GALLERY_CELL_SIZE = 512;
    private static final int DEEP_NETWORK_CELL_SIZE = 1536;
    private static final int FORMATION_CELL_SIZE = 96;
    private static final int SURFACE_RIFT_CELL_SIZE = 384;
    private static final long MEGA_CHAMBER_SALT = 0x3c79ac492ba7b653L;
    private static final long MOUNTAIN_GALLERY_SALT = 0x1f83d9abfb41bd6bL;
    private static final long DEEP_NETWORK_SALT = 0x8f4d3c2b1a0976e5L;
    private static final long FORMATION_SALT = 0x6a09e667f3bcc909L;
    private static final long SURFACE_RIFT_SALT = 0x510e527fade682d1L;
    private static final double[] DIRECTION_X = {
            1.0, 0.7071067811865476, 0.0, -0.7071067811865476,
            -1.0, -0.7071067811865476, 0.0, 0.7071067811865476
    };
    private static final double[] DIRECTION_Z = {
            0.0, 0.7071067811865476, 1.0, 0.7071067811865476,
            0.0, -0.7071067811865476, -1.0, -0.7071067811865476
    };

    // The properties file is loaded once at startup. Cache these values here so
    // concurrent density sampling never contends on Properties' synchronized map.
    private static final boolean ENABLED = ImmensaConfig.cavesEnabled();
    private static final float ABUNDANCE = ImmensaConfig.caveAbundance();
    private static final boolean TUNNELS = ImmensaConfig.caveTunnelsEnabled();
    private static final boolean MASSIVE_CAVERNS = ImmensaConfig.massiveCavernsEnabled();
    private static final boolean SURFACE_ENTRANCES = ImmensaConfig.surfaceCaveEntrancesEnabled();

    private static volatile SeededNoise seededNoise = createNoise(Long.MIN_VALUE);
    private static final ThreadLocal<ColumnNoiseCache> COLUMN_CACHE =
            ThreadLocal.withInitial(ColumnNoiseCache::new);

    private ImmensaCaveSampler() {
    }

    public static double apply(double terrainDensity, int x, int y, int z,
                               int surfaceY, long worldSeed) {
        return apply(terrainDensity, x, y, z, surfaceY, worldSeed,
                0, 0, 0, 0.0, 0.0, 1.0, 0.0);
    }

    /** Compatibility overload for callers without an entrance tile-clearance value. */
    public static double apply(double terrainDensity, int x, int y, int z,
                               int surfaceY, long worldSeed,
                               int entranceX, int entranceZ, int entranceSurfaceY,
                               double entranceSlopeX, double entranceSlopeZ,
                               double entranceScale) {
        return apply(terrainDensity, x, y, z, surfaceY, worldSeed,
                entranceX, entranceZ, entranceSurfaceY,
                entranceSlopeX, entranceSlopeZ, entranceScale,
                128.0 * entranceScale);
    }

    public static double apply(double terrainDensity, int x, int y, int z,
                               int surfaceY, long worldSeed,
                               int entranceX, int entranceZ, int entranceSurfaceY,
                               double entranceSlopeX, double entranceSlopeZ,
                               double entranceScale, double entranceReach) {
        if (!ENABLED
                || terrainDensity < 0.0
                || y <= BEDROCK_PROTECTION_Y) {
            return terrainDensity;
        }

        int depth = surfaceY - y;
        if (depth < 0) return terrainDensity;

        SeededNoise noise = noiseFor(worldSeed);
        ColumnNoiseCache column = COLUMN_CACHE.get();
        column.update(noise, x, z, worldSeed);
        double caveDensity = Double.POSITIVE_INFINITY;
        double massiveVoidDensity = Double.POSITIVE_INFINITY;

        // Each cave family receives a smoothly varying geological cap. Fixed
        // depth cutoffs produced a perfectly flat ceiling beneath level terrain.
        double cavernShell = column.cavernShell;
        double chamberShell = column.chamberShell;
        double tunnelShell = column.tunnelShell;
        double genericCavernActivity = genericCavernActivity(y, surfaceY);
        if (MASSIVE_CAVERNS && genericCavernActivity > 0.015
                && depth >= cavernShell - 8.0) {
            double genericCavern = cavernDensity(noise, x, y, z, depth, ABUNDANCE)
                    + (1.0 - genericCavernActivity) * 11.0;
            massiveVoidDensity = shellBlend(genericCavern, depth, cavernShell, 1.35);
        }
        if (MASSIVE_CAVERNS && depth >= chamberShell - 8.0) {
            massiveVoidDensity = Math.min(massiveVoidDensity,
                    shellBlend(megaChamberDensity(noise, x, y, z, surfaceY, worldSeed),
                            depth, chamberShell, 1.45));
        }
        if (MASSIVE_CAVERNS && surfaceY >= 260 && y >= 56 && depth >= 34) {
            // High mountains use sparse purpose-built galleries. They replace
            // the dense worm field that was removed from the upper rock mass.
            double mountainShell = Math.max(34.0, chamberShell - 7.0);
            massiveVoidDensity = Math.min(massiveVoidDensity,
                    shellBlend(mountainGalleryDensity(
                                    noise, x, y, z, surfaceY, worldSeed),
                            depth, mountainShell, 1.30));
        }
        caveDensity = massiveVoidDensity;
        if (massiveVoidDensity < 0.0) {
            // Sparse structural rock grows only inside large open chambers.
            // Later tunnel noise may punch windows through it, producing arches
            // and cheese-like openings instead of featureless empty halls.
            caveDensity = Math.max(caveDensity,
                    formationSolidDensity(noise, x, y, z, surfaceY, worldSeed));
        }
        double tunnelActivity = tunnelActivity(y, surfaceY);
        if (TUNNELS && tunnelActivity > 0.015 && depth >= tunnelShell - 8.0) {
            caveDensity = Math.min(caveDensity,
                    shellBlend(tunnelDensity(noise, x, y, z, depth,
                                    tunnelActivity, ABUNDANCE),
                            depth, tunnelShell, 1.25));
        }
        double deepNetworkDensity = Double.POSITIVE_INFINITY;
        if (MASSIVE_CAVERNS && y <= 48) {
            deepNetworkDensity = deepLavaNetworkDensity(noise, x, y, z, worldSeed);
            caveDensity = Math.min(caveDensity, deepNetworkDensity);
            if (deepNetworkDensity < 0.0) {
                // Preserve sparse deepslate columns and arches inside the lava
                // halls instead of leaving a completely empty reservoir shell.
                caveDensity = Math.max(caveDensity,
                        formationSolidDensity(noise, x, y, z, surfaceY, worldSeed));
            }
        }
        if (SURFACE_ENTRANCES && entranceSurfaceY >= 68 && depth >= 1) {
            caveDensity = Math.min(caveDensity,
                    hillsideEntranceDensity(noise, x, y, z,
                            entranceX, entranceZ, entranceSurfaceY,
                            entranceSlopeX, entranceSlopeZ, entranceScale,
                            entranceReach, worldSeed));
        }
        if (SURFACE_ENTRANCES && surfaceY >= 68) {
            caveDensity = Math.min(caveDensity,
                    surfaceRiftDensity(noise, x, y, z, surfaceY, worldSeed));
        }

        // Blend every cave into an irregular deep floor before the bedrock band.
        // A hard Y cutoff created the perfectly flat plane seen at the world bottom.
        double deepFloorY = deepNetworkDensity < 3.0
                ? column.deepLavaFloor(noise)
                : column.bottomFloor(noise);
        caveDensity = Math.max(caveDensity, (deepFloorY - y) * 1.6);

        return Math.min(terrainDensity, caveDensity);
    }

    /** Smoothly closes general caves into the protected surface shell. */
    private static double shellBlend(double density, int depth, double minimumDepth,
                                     double blocksPerDepth) {
        return Math.max(density, (minimumDepth - depth) * blocksPerDepth);
    }

    static double surfaceShellDepth(int x, int z, double baseDepth, long worldSeed) {
        return surfaceShellDepth(noiseFor(worldSeed), x, z, baseDepth, 0.0);
    }

    private static double surfaceShellDepth(SeededNoise noise, int x, int z,
                                            double baseDepth, double offset) {
        double broad = noise.cavern.GetNoise((float) (x * 0.62 + offset), 0.0f,
                (float) (z * 0.62 - offset * 0.37));
        double detail = noise.detail.GetNoise((float) (x * 0.48 - offset * 0.23), 0.0f,
                (float) (z * 0.48 + offset));
        return Math.max(12.0, baseDepth + broad * 10.5 + detail * 4.0);
    }

    static double formationSolidDensity(int x, int y, int z, int surfaceY, long worldSeed) {
        return formationSolidDensity(noiseFor(worldSeed), x, y, z, surfaceY, worldSeed);
    }

    /** Paired irregular columns, occasional broken shafts and a connecting rock arch. */
    private static double formationSolidDensity(SeededNoise noise, int x, int y, int z,
                                                 int surfaceY, long worldSeed) {
        int cellX = Math.floorDiv(x, FORMATION_CELL_SIZE);
        int cellZ = Math.floorDiv(z, FORMATION_CELL_SIZE);
        long hash = mix64(worldSeed ^ FORMATION_SALT
                ^ (long) cellX * 0xd1b54a32d192ed03L
                ^ (long) cellZ * 0xabc98388fb8fac03L);
        // One quarter of chamber cells stay completely open. The rest contain
        // a formation, but its small footprint preserves the cavern's scale.
        if ((hash & 3L) == 0L) return Double.NEGATIVE_INFINITY;

        double centerX = cellX * (double) FORMATION_CELL_SIZE + 48.0
                + signedBits(hash >>> 8, 12.0);
        double centerZ = cellZ * (double) FORMATION_CELL_SIZE + 48.0
                + signedBits(hash >>> 22, 12.0);
        int direction = (int) ((hash >>> 36) & 7L);
        double axisX = DIRECTION_X[direction], axisZ = DIRECTION_Z[direction];
        double sideX = -axisZ, sideZ = axisX;
        double separation = 25.0 + ((hash >>> 40) & 15L);
        double firstX = centerX - sideX * separation * 0.5;
        double firstZ = centerZ - sideZ * separation * 0.5;
        double secondX = centerX + sideX * separation * 0.5;
        double secondZ = centerZ + sideZ * separation * 0.5;

        double phase = ((hash >>> 48) & 255L) * (Math.PI * 2.0 / 255.0);
        double lean = Math.sin(y * 0.034 + phase) * 1.8;
        double rockNoise = noise.detail.GetNoise(x * 0.88f, y * 0.72f, z * 0.88f);
        double firstRadius = 5.0 + ((hash >>> 44) & 7L) * 0.62;
        double secondRadius = 4.5 + ((hash >>> 52) & 7L) * 0.58;
        double first = pillarSolidDensity(x, y, z,
                firstX + axisX * lean, firstZ + axisZ * lean,
                firstRadius, phase, rockNoise);
        double second = pillarSolidDensity(x, y, z,
                secondX - axisX * lean * 0.72, secondZ - axisZ * lean * 0.72,
                secondRadius, phase + 1.9, rockNoise);

        // Some columns are naturally broken, leaving aligned stumps rather than
        // an implausible forest of uninterrupted cylinders.
        double archY = surfaceY - 48.0 - ((hash >>> 57) & 63L);
        if ((hash & 16L) != 0L && Math.abs(y - (archY - 18.0)) < 6.0) {
            second = Double.NEGATIVE_INFINITY;
        }

        double arch = Double.NEGATIVE_INFINITY;
        if ((hash & 12L) != 0L) {
            // A rough horizontal capsule joins the pillars. Negating the cave
            // gallery SDF produces solid rock inside the span.
            arch = -segmentGalleryDensity(x, y, z,
                    firstX, archY, firstZ, secondX, archY + signedBits(hash >>> 28, 3.0), secondZ,
                    6.0 + ((hash >>> 33) & 3L), 3.5 + ((hash >>> 31) & 3L) * 0.55)
                    + rockNoise * 1.15;
        }
        return Math.max(arch, Math.max(first, second));
    }

    private static double pillarSolidDensity(double x, double y, double z,
                                              double centerX, double centerZ,
                                              double baseRadius, double phase,
                                              double rockNoise) {
        double ribbing = Math.sin(y * 0.115 + phase) * 0.55;
        double radius = baseRadius + ribbing + rockNoise * 1.05;
        double dx = x - centerX, dz = z - centerZ;
        return radius - Math.sqrt(dx * dx + dz * dz);
    }

    static double bottomFloorY(int x, int z, long worldSeed) {
        return bottomFloorY(x, z, noiseFor(worldSeed));
    }

    private static double bottomFloorY(int x, int z, SeededNoise noise) {
        double broad = noise.cavern.GetNoise(x * 0.72f, 0.0f, z * 0.72f);
        double rough = noise.detail.GetNoise(x * 0.55f, 0.0f, z * 0.55f);
        return -53.0 + broad * 5.5 + rough * 2.25;
    }

    static int deepLavaSurfaceY(int x, int z, long worldSeed) {
        // All connected arteries share one equilibrium surface. Varying this
        // per column created terraced lava walls and floating source sheets.
        return -40;
    }

    static boolean isDeepLavaInterior(int x, int y, int z, long worldSeed) {
        if (y <= BEDROCK_PROTECTION_Y || y > deepLavaSurfaceY(x, z, worldSeed)) return false;
        return deepLavaNetworkDensity(noiseFor(worldSeed), x, y, z, worldSeed) < -1.25;
    }

    static double deepLavaNetworkDensity(int x, int y, int z, long worldSeed) {
        return deepLavaNetworkDensity(noiseFor(worldSeed), x, y, z, worldSeed);
    }

    private static double deepLavaFloorY(int x, int z, SeededNoise noise) {
        double broad = noise.cavern.GetNoise(x * 0.43f, 0.0f, z * 0.43f);
        double rough = noise.detail.GetNoise(x * 0.67f, 0.0f, z * 0.67f);
        return -57.0 + broad * 2.2 + rough * 1.15;
    }

    private static double cavernDensity(SeededNoise noise, int x, int y, int z,
                                         int depth, float abundance) {
        // Two scales create Minecraft-like "cheese" porosity: continent-sized
        // lobes form the main halls and smaller lobes bite windows into their walls.
        float chamber = noise.cavern.GetNoise(x, y * 1.08f, z);
        float cheese = noise.cheese.GetNoise(x, y * 1.22f, z);
        float detail = noise.detail.GetNoise(x, y, z);
        double shallowProtection = Math.max(0.0, (30.0 - depth) / 30.0) * 0.16;
        double largeThreshold = 0.41 - (abundance - 1.0) * 0.07
                + shallowProtection + detail * 0.025;
        double cheeseThreshold = 0.56 - (abundance - 1.0) * 0.06
                + shallowProtection * 1.15 - detail * 0.02;
        double largeHall = (largeThreshold - chamber) * 46.0;
        double wallWindows = (cheeseThreshold - cheese) * 32.0;
        return Math.min(largeHall, wallWindows);
    }

    private static double tunnelDensity(SeededNoise noise, int x, int y, int z,
                                         int depth, double activity, float abundance) {
        // A broad province gate prevents the two noise sheets from producing a
        // uniform world-spanning bowl of spaghetti. The gate becomes much more
        // selective with altitude, leaving only rare ascending branches.
        double depthRamp = smoothstep((depth - 18.0) / 52.0);
        double provinceSignal = noise.cavern.GetNoise(x * 0.27f, y * 0.16f, z * 0.27f);
        double highAltitude = smoothstep((y - 64.0) / 112.0);
        double provinceThreshold = 0.06 + highAltitude * 0.34;
        double province = smoothstep((provinceSignal - provinceThreshold) / 0.24);
        if (province < 0.045) return Double.POSITIVE_INFINITY;

        float a = noise.tunnelA.GetNoise(x, y * 0.82f, z);
        float b = noise.tunnelB.GetNoise(x, y * 0.68f, z);
        float detail = noise.detail.GetNoise(x * 1.7f, y * 1.25f, z * 1.7f);

        // A second, slower signal changes passage diameter inside a province.
        // Most tunnels are 4-7 blocks across and navigable in survival; uncommon
        // trunks widen into much larger galleries near junctions.
        double widthSignal = noise.cheese.GetNoise(x * 0.42f, y * 0.24f, z * 0.42f);
        double width = 0.72 + smoothstep((widthSignal + 0.18) / 0.78) * 0.58;
        double effectiveActivity = depthRamp * activity;
        double radius = 0.050 + effectiveActivity * (0.050 + width * 0.038)
                + (abundance - 1.0) * 0.016;
        radius *= 0.76 + province * 0.24;
        radius = Math.max(0.044, Math.min(0.145, radius));
        return (a * a + b * b - radius * radius) * 230.0 + detail * 0.18;
    }

    static double tunnelDensityAt(int x, int y, int z, int surfaceY, long worldSeed) {
        double activity = tunnelActivity(y, surfaceY);
        if (activity <= 0.015) return Double.POSITIVE_INFINITY;
        return tunnelDensity(noiseFor(worldSeed), x, y, z,
                Math.max(0, surfaceY - y), activity, ABUNDANCE);
    }

    static double tunnelActivityAt(int y, int surfaceY) {
        return tunnelActivity(y, surfaceY);
    }

    /**
     * Absolute geological layers replace a worm field repeated through the
     * entire height of very tall mountains. Branching peaks around/below the
     * mountain base, then fades through one middle karst band.
     */
    private static double tunnelActivity(int y, int surfaceY) {
        double activity;
        if (y >= 208) activity = 0.0;
        else if (y >= 160) activity = lerp(0.0, 0.045,
                1.0 - smoothstep((y - 160.0) / 48.0));
        else if (y >= 112) activity = lerp(0.20, 0.045,
                smoothstep((y - 112.0) / 48.0));
        else if (y >= 64) activity = lerp(0.64, 0.20,
                smoothstep((y - 64.0) / 48.0));
        else if (y >= 16) activity = lerp(1.0, 0.64,
                smoothstep((y - 16.0) / 48.0));
        else if (y >= -24) activity = 1.0;
        else activity = lerp(0.42, 1.0,
                    Math.max(0.0, Math.min(1.0, (y + 56.0) / 32.0)));

        if (surfaceY >= 500 && y > 48) {
            // Huge mountains retain a few natural high passages, but no longer
            // repeat the full tunnel network through hundreds of vertical blocks.
            activity *= lerp(0.08, 1.0,
                    1.0 - smoothstep((y - 48.0) / 88.0));
        }
        return Math.max(0.0, Math.min(1.0, activity));
    }

    /** Upper giant mountains stay mostly solid except for rare mega-vaults. */
    private static double genericCavernActivity(int y, int surfaceY) {
        if (surfaceY < 500 || y <= 112) return 1.0;
        if (y >= 240) return 0.0;
        return 1.0 - smoothstep((y - 112.0) / 128.0);
    }

    /** Rare elongated skylights and collapsed fissures that work on flat terrain. */
    private static double surfaceRiftDensity(SeededNoise noise, int x, int y, int z,
                                             int surfaceY, long worldSeed) {
        int depth = surfaceY - y;
        if (depth < 0 || depth > 58) return Double.POSITIVE_INFINITY;
        int cellX = Math.floorDiv(x, SURFACE_RIFT_CELL_SIZE);
        int cellZ = Math.floorDiv(z, SURFACE_RIFT_CELL_SIZE);
        long hash = mix64(worldSeed ^ SURFACE_RIFT_SALT
                ^ (long) cellX * 0xd1b54a32d192ed03L
                ^ (long) cellZ * 0xabc98388fb8fac03L);
        if ((hash & 3L) != 0L) return Double.POSITIVE_INFINITY;

        double centerX = cellX * (double) SURFACE_RIFT_CELL_SIZE + 192.0
                + signedBits(hash >>> 8, 82.0);
        double centerZ = cellZ * (double) SURFACE_RIFT_CELL_SIZE + 192.0
                + signedBits(hash >>> 23, 82.0);
        int direction = (int) ((hash >>> 39) & 7L);
        double axisX = DIRECTION_X[direction], axisZ = DIRECTION_Z[direction];
        double sideX = -axisZ, sideZ = axisX;
        double progress = Math.min(1.0, depth / 42.0);
        double drift = depth * 0.24;
        double curve = Math.sin(progress * Math.PI) * signedBits(hash >>> 48, 5.0);
        double dx = x - (centerX + axisX * drift + sideX * curve);
        double dz = z - (centerZ + axisZ * drift + sideZ * curve);
        double along = dx * axisX + dz * axisZ;
        double across = -dx * axisZ + dz * axisX;
        double longRadius = 9.0 + ((hash >>> 52) & 7L) + progress * 11.0;
        double wideRadius = 3.4 + ((hash >>> 55) & 3L) * 0.75 + progress * 7.0;
        double roughness = noise.detail.GetNoise(x * 0.94f, y * 0.68f, z * 0.94f) * 1.75;
        double fissure = (Math.sqrt(along * along / (longRadius * longRadius)
                + across * across / (wideRadius * wideRadius)) - 1.0)
                * Math.min(longRadius, wideRadius) + roughness;

        // The rift leans into an offset terminal vault rather than becoming a
        // round vertical sinkhole or stopping at another horizontal plane.
        double roomDepth = 39.0;
        double roomX = centerX + axisX * roomDepth * 0.24;
        double roomZ = centerZ + axisZ * roomDepth * 0.24;
        double terminal = orientedEllipsoidDensity(
                x - roomX, y - (surfaceY - roomDepth), z - roomZ,
                axisX, axisZ, 27.0, 17.0, 16.0) + roughness * 0.65;
        return Math.min(fissure, terminal);
    }

    private static double hillsideEntranceDensity(SeededNoise noise, int x, int y, int z,
                                                   int entranceX, int entranceZ,
                                                   int entranceSurfaceY,
                                                   double slopeX, double slopeZ,
                                                   double entranceScale,
                                                   double entranceReach,
                                                   long worldSeed) {
        double slopeMagnitude = Math.sqrt(slopeX * slopeX + slopeZ * slopeZ);
        if (slopeMagnitude < 0.10) return Double.POSITIVE_INFINITY;
        double scale = Math.max(0.45, Math.min(1.0, entranceScale));
        double dx = x - entranceX;
        double dz = z - entranceZ;
        double maximumReach = Math.max(72.0 * scale,
                Math.min(154.0 * scale, entranceReach));
        double boundingReach = maximumReach + 38.0 * scale;
        if (dx * dx + dz * dz > boundingReach * boundingReach) {
            return Double.POSITIVE_INFINITY;
        }

        // The gradient points uphill. The negative direction is exposed air;
        // the positive direction drives horizontally into the hillside.
        double axisX = slopeX / slopeMagnitude;
        double axisZ = slopeZ / slopeMagnitude;
        double along = dx * axisX + dz * axisZ;
        if (along < -18.0 * scale || along > maximumReach + 34.0 * scale) {
            return Double.POSITIVE_INFINITY;
        }
        double across = -dx * axisZ + dz * axisX;

        // Curve and descend into the protected-depth cave field. Every primitive
        // closes before the tile boundary, so a neighbouring tile can never cut
        // the passage off with a vertical plane.
        long bendHash = mix64(worldSeed ^ (long) entranceX * 0x9e3779b97f4a7c15L
                ^ (long) entranceZ * 0xc2b2ae3d27d4eb4fL);
        double bendSign = (bendHash & 1L) == 0L ? -1.0 : 1.0;
        double progress = Math.max(0.0, Math.min(1.0, along / maximumReach));
        double curve = bendSign * Math.sin(progress * Math.PI) * 9.0 * scale;
        double centerLineY = entranceSurfaceY - 6.5 * scale
                - along * (0.17 + 0.08 * progress);
        double vertical = y - centerLineY;
        double interior = smoothstep(Math.min(1.0,
                (along + 7.0 * scale) / (48.0 * scale)));
        double terminalTaper = smoothstep(Math.max(0.0,
                Math.min(1.0, (maximumReach - along) / (30.0 * scale))));
        double width = (6.5 + interior * 7.0) * scale
                * (0.30 + terminalTaper * 0.70);
        double height = (5.0 + interior * 6.5) * scale
                * (0.40 + terminalTaper * 0.60);
        double roughness = noise.detail.GetNoise(x * 1.1f, y * 1.25f, z * 1.1f) * 2.2
                + noise.cheese.GetNoise(x * 0.72f, y * 0.84f, z * 0.72f) * 1.25;
        double curvedAcross = across - curve;
        double passage = (Math.sqrt(curvedAcross * curvedAcross / (width * width)
                + vertical * vertical / (height * height)) - 1.0)
                * Math.min(width, height) + roughness;

        // Offset overlapping vaults replace the old single smooth blind bowl.
        // Different floor and ceiling levels form shelves and a readable route
        // down into the ordinary underground cave field.
        double firstAlong = maximumReach * 0.43;
        double firstCurve = bendSign * Math.sin(0.43 * Math.PI) * 9.0 * scale;
        double firstX = entranceX + axisX * firstAlong - axisZ * firstCurve;
        double firstZ = entranceZ + axisZ * firstAlong + axisX * firstCurve;
        double firstY = entranceSurfaceY - 7.0 * scale - firstAlong * 0.19;
        double firstVault = orientedEllipsoidDensity(
                x - firstX, y - firstY, z - firstZ, axisX, axisZ,
                31.0 * scale, 17.0 * scale, 23.0 * scale) + roughness * 0.75;

        double deepAlong = maximumReach * 0.72;
        double deepCurve = bendSign * Math.sin(0.72 * Math.PI) * 9.0 * scale;
        double deepX = entranceX + axisX * deepAlong - axisZ * deepCurve;
        double deepZ = entranceZ + axisZ * deepAlong + axisX * deepCurve;
        double deepY = entranceSurfaceY - 8.0 * scale - deepAlong * 0.245;
        double deepVault = orientedEllipsoidDensity(
                x - deepX, y - deepY, z - deepZ, axisX, axisZ,
                38.0 * scale, 23.0 * scale, 28.0 * scale) + roughness * 0.62;

        double connector = segmentGalleryDensity(x, y, z,
                firstX, firstY, firstZ, deepX, deepY, deepZ,
                10.0 * scale, 8.0 * scale) + roughness * 0.55;
        return Math.min(Math.min(passage, connector), Math.min(firstVault, deepVault));
    }

    private static double smoothstep(double value) {
        double t = Math.max(0.0, Math.min(1.0, value));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    /**
     * A sparse near-bedrock province made from several cathedral-sized vaults
     * and broad connecting galleries. Neighbouring cells are evaluated so no
     * chamber or connector is clipped at an arbitrary region boundary.
     */
    private static double deepLavaNetworkDensity(SeededNoise noise, int x, int y, int z,
                                                  long worldSeed) {
        int cellX = Math.floorDiv(x, DEEP_NETWORK_CELL_SIZE);
        int cellZ = Math.floorDiv(z, DEEP_NETWORK_CELL_SIZE);
        double density = Double.POSITIVE_INFINITY;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                density = Math.min(density, deepLavaProvinceDensity(
                        x, y, z, cellX + dx, cellZ + dz, worldSeed));
            }
        }
        if (!Double.isFinite(density)) return density;

        // Large-scale distortion makes walls and ceilings geological instead
        // of revealing the primitive ellipsoids used to guarantee open space.
        double distortion = noise.cavern.GetNoise(x * 0.44f, y * 0.58f, z * 0.44f) * 7.5
                + noise.detail.GetNoise(x * 0.38f, y * 0.52f, z * 0.38f) * 3.2;
        return density + distortion;
    }

    private static double deepLavaProvinceDensity(int x, int y, int z,
                                                   int cellX, int cellZ, long worldSeed) {
        long hash = deepProvinceHash(worldSeed, cellX, cellZ);
        // Roughly half of the provinces exist: enormous when found, but not a
        // single lava cavern covering the entire underground world.
        if (!deepProvinceActive(hash)) return Double.POSITIVE_INFINITY;

        double centerX = deepProvinceCenterX(cellX, hash);
        double centerZ = deepProvinceCenterZ(cellZ, hash);
        double centerY = deepProvinceCenterY(hash);
        int direction = (int) ((hash >>> 52) & 7L);
        double axisX = DIRECTION_X[direction], axisZ = DIRECTION_Z[direction];
        double sideX = -axisZ, sideZ = axisX;

        double mainLong = 190.0 + ((hash >>> 44) & 31L);
        double mainWide = 112.0 + ((hash >>> 39) & 31L);
        double mainHigh = 38.0 + ((hash >>> 57) & 7L);
        double main = orientedEllipsoidDensity(
                x - centerX, y - centerY, z - centerZ,
                axisX, axisZ, mainLong, mainHigh, mainWide);

        double aX = centerX - axisX * 310.0 + sideX * 74.0;
        double aZ = centerZ - axisZ * 310.0 + sideZ * 74.0;
        double aY = centerY - 5.0;
        double vaultA = orientedEllipsoidDensity(
                x - aX, y - aY, z - aZ,
                axisX, axisZ, 168.0, 34.0, 92.0);

        double bX = centerX + axisX * 335.0 - sideX * 88.0;
        double bZ = centerZ + axisZ * 335.0 - sideZ * 88.0;
        double bY = centerY + 3.0;
        double vaultB = orientedEllipsoidDensity(
                x - bX, y - bY, z - bZ,
                axisX, axisZ, 178.0, 37.0, 98.0);

        // A side branch prevents the network reading as one straight tube.
        double cX = centerX + axisX * 52.0 + sideX * 292.0;
        double cZ = centerZ + axisZ * 52.0 + sideZ * 292.0;
        double cY = centerY - 2.0;
        double vaultC = orientedEllipsoidDensity(
                x - cX, y - cY, z - cZ,
                sideX, sideZ, 154.0, 31.0, 82.0);

        double galleryA = segmentGalleryDensity(x, y, z,
                aX, aY, aZ, centerX, centerY, centerZ, 34.0, 22.0);
        double galleryB = segmentGalleryDensity(x, y, z,
                centerX, centerY, centerZ, bX, bY, bZ, 38.0, 24.0);
        double galleryC = segmentGalleryDensity(x, y, z,
                centerX, centerY, centerZ, cX, cY, cZ, 31.0, 20.0);
        double density = Math.min(Math.min(main, Math.min(vaultA, vaultB)),
                Math.min(vaultC, Math.min(galleryA, Math.min(galleryB, galleryC))));

        // Link each active node to the next active node east and south. Searching
        // across one skipped cell turns isolated 1.5 km provinces into multi-km
        // tunnel networks without covering the entire deep world in lava.
        int eastStep = nextActiveProvince(worldSeed, cellX, cellZ, 1, 0);
        if (eastStep > 0) {
            long eastHash = deepProvinceHash(worldSeed, cellX + eastStep, cellZ);
            density = Math.min(density, deepLavaArteryDensity(x, y, z,
                    centerX, centerY, centerZ,
                    deepProvinceCenterX(cellX + eastStep, eastHash),
                    deepProvinceCenterY(eastHash), deepProvinceCenterZ(cellZ, eastHash),
                    hash ^ eastHash ^ 0x243f6a8885a308d3L));
        }
        int southStep = nextActiveProvince(worldSeed, cellX, cellZ, 0, 1);
        if (southStep > 0) {
            long southHash = deepProvinceHash(worldSeed, cellX, cellZ + southStep);
            density = Math.min(density, deepLavaArteryDensity(x, y, z,
                    centerX, centerY, centerZ,
                    deepProvinceCenterX(cellX, southHash),
                    deepProvinceCenterY(southHash), deepProvinceCenterZ(cellZ + southStep, southHash),
                    hash ^ southHash ^ 0x13198a2e03707344L));
        }
        return density;
    }

    private static int nextActiveProvince(long worldSeed, int cellX, int cellZ,
                                          int stepX, int stepZ) {
        for (int step = 1; step <= 2; step++) {
            if (deepProvinceActive(deepProvinceHash(worldSeed,
                    cellX + stepX * step, cellZ + stepZ * step))) {
                return step;
            }
        }
        return 0;
    }

    private static double deepLavaArteryDensity(double x, double y, double z,
                                                 double ax, double ay, double az,
                                                 double bx, double by, double bz,
                                                 long hash) {
        double margin = 340.0;
        if (x < Math.min(ax, bx) - margin || x > Math.max(ax, bx) + margin
                || z < Math.min(az, bz) - margin || z > Math.max(az, bz) + margin
                || y < Math.min(ay, by) - 48.0 || y > Math.max(ay, by) + 48.0) {
            return Double.POSITIVE_INFINITY;
        }
        double vx = bx - ax, vz = bz - az;
        double length = Math.sqrt(vx * vx + vz * vz);
        double baseT = ((x - ax) * vx + (z - az) * vz) / (length * length);
        double baseDx = x - (ax + vx * Math.max(0.0, Math.min(1.0, baseT)));
        double baseDz = z - (az + vz * Math.max(0.0, Math.min(1.0, baseT)));
        if (baseDx * baseDx + baseDz * baseDz > margin * margin) {
            return Double.POSITIVE_INFINITY;
        }
        double sideX = -vz / length, sideZ = vx / length;
        double bend = signedBits(hash >>> 7, Math.min(260.0, length * 0.13));
        double firstX = lerp(ax, bx, 0.34) + sideX * bend;
        double firstZ = lerp(az, bz, 0.34) + sideZ * bend;
        double secondX = lerp(ax, bx, 0.68) - sideX * bend * 0.58;
        double secondZ = lerp(az, bz, 0.68) - sideZ * bend * 0.58;
        double firstY = lerp(ay, by, 0.34) + signedBits(hash >>> 25, 5.0);
        double secondY = lerp(ay, by, 0.68) + signedBits(hash >>> 39, 5.0);
        double horizontalRadius = 43.0 + ((hash >>> 51) & 15L);
        double verticalRadius = 25.0 + ((hash >>> 56) & 7L);
        return Math.min(segmentGalleryDensity(x, y, z,
                        ax, ay, az, firstX, firstY, firstZ,
                        horizontalRadius, verticalRadius),
                Math.min(segmentGalleryDensity(x, y, z,
                                firstX, firstY, firstZ, secondX, secondY, secondZ,
                                horizontalRadius * 1.08, verticalRadius * 1.05),
                        segmentGalleryDensity(x, y, z,
                                secondX, secondY, secondZ, bx, by, bz,
                                horizontalRadius, verticalRadius)));
    }

    private static long deepProvinceHash(long worldSeed, int cellX, int cellZ) {
        return mix64(worldSeed ^ DEEP_NETWORK_SALT
                ^ (long) cellX * 0xd1b54a32d192ed03L
                ^ (long) cellZ * 0xabc98388fb8fac03L);
    }

    private static boolean deepProvinceActive(long hash) {
        return (hash & 3L) < 2L;
    }

    private static double deepProvinceCenterX(int cellX, long hash) {
        return cellX * (double) DEEP_NETWORK_CELL_SIZE + 768.0
                + signedBits(hash >>> 8, 150.0);
    }

    private static double deepProvinceCenterZ(int cellZ, long hash) {
        return cellZ * (double) DEEP_NETWORK_CELL_SIZE + 768.0
                + signedBits(hash >>> 22, 150.0);
    }

    private static double deepProvinceCenterY(long hash) {
        return -23.0 + signedBits(hash >>> 36, 6.0);
    }

    private static double segmentGalleryDensity(double x, double y, double z,
                                                  double ax, double ay, double az,
                                                  double bx, double by, double bz,
                                                  double horizontalRadius,
                                                  double verticalRadius) {
        double vx = bx - ax, vy = by - ay, vz = bz - az;
        double lengthSquared = vx * vx + vy * vy + vz * vz;
        double t = ((x - ax) * vx + (y - ay) * vy + (z - az) * vz) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        double dx = x - (ax + vx * t);
        double dy = y - (ay + vy * t);
        double dz = z - (az + vz * t);
        double normalized = Math.sqrt((dx * dx + dz * dz)
                / (horizontalRadius * horizontalRadius)
                + dy * dy / (verticalRadius * verticalRadius));
        return (normalized - 1.0) * Math.min(horizontalRadius, verticalRadius);
    }

    private static double signedBits(long bits, double amplitude) {
        return (((bits & 0x3fffL) / 8191.5) - 1.0) * amplitude;
    }

    private static double megaChamberDensity(SeededNoise noise, int x, int y, int z,
                                             int surfaceY, long worldSeed) {
        int cellX = Math.floorDiv(x, MEGA_CHAMBER_CELL_SIZE);
        int cellZ = Math.floorDiv(z, MEGA_CHAMBER_CELL_SIZE);
        long hash = mix64(worldSeed ^ MEGA_CHAMBER_SALT
                ^ (long) cellX * 0xd1b54a32d192ed03L
                ^ (long) cellZ * 0xabc98388fb8fac03L);
        // Includes the offset vaults' full reach; no cell-boundary clipping.
        int margin = 150;
        int span = MEGA_CHAMBER_CELL_SIZE - margin * 2;
        double centerX = cellX * MEGA_CHAMBER_CELL_SIZE + margin + ((hash >>> 8) % span);
        double centerZ = cellZ * MEGA_CHAMBER_CELL_SIZE + margin + ((hash >>> 24) % span);
        double centerDepth = 82.0 + ((hash >>> 40) & 63L);
        double centerY = surfaceY - centerDepth;
        double dx = x - centerX;
        double dz = z - centerZ;
        double dy = y - centerY;

        int direction = (int) ((hash >>> 56) & 7L);
        double axisX = DIRECTION_X[direction];
        double axisZ = DIRECTION_Z[direction];
        double sideX = -axisZ;
        double sideZ = axisX;
        double longRadius = 76.0 + ((hash >>> 48) & 31L);
        double highRadius = 34.0 + ((hash >>> 59) & 15L);
        double wideRadius = 58.0 + ((hash >>> 53) & 31L);

        // Three asymmetric overlapping vaults guarantee a real explorable space,
        // while different ceiling heights avoid the look of a single ellipsoid.
        double main = orientedEllipsoidDensity(dx, dy, dz, axisX, axisZ,
                longRadius, highRadius, wideRadius);
        double upper = orientedEllipsoidDensity(
                dx - axisX * longRadius * 0.42 - sideX * wideRadius * 0.18,
                dy + highRadius * 0.26,
                dz - axisZ * longRadius * 0.42 - sideZ * wideRadius * 0.18,
                axisX, axisZ, longRadius * 0.72, highRadius * 0.76, wideRadius * 0.68);
        double lower = orientedEllipsoidDensity(
                dx + axisX * longRadius * 0.38 - sideX * wideRadius * 0.30,
                dy - highRadius * 0.22,
                dz + axisZ * longRadius * 0.38 - sideZ * wideRadius * 0.30,
                axisX, axisZ, longRadius * 0.68, highRadius * 0.82, wideRadius * 0.74);
        double distortion = noise.cavern.GetNoise(x * 0.62f, y * 0.72f, z * 0.62f) * 7.0
                + noise.detail.GetNoise(x * 0.55f, y * 0.70f, z * 0.55f) * 3.5;
        return Math.min(main, Math.min(upper, lower)) + distortion;
    }

    static double mountainGalleryDensityAt(int x, int y, int z,
                                            int surfaceY, long worldSeed) {
        if (surfaceY < 260 || y < 56 || surfaceY - y < 34) {
            return Double.POSITIVE_INFINITY;
        }
        return mountainGalleryDensity(noiseFor(worldSeed),
                x, y, z, surfaceY, worldSeed);
    }

    /**
     * Large, sparse cave systems designed specifically for thick mountain rock.
     * Each active province contains a sloping fault gallery, asymmetric vaults,
     * a side chamber and sometimes a tall fracture. Neighbour cells are sampled
     * so no primitive ends at a generation boundary.
     */
    private static double mountainGalleryDensity(SeededNoise noise,
                                                  int x, int y, int z,
                                                  int surfaceY, long worldSeed) {
        if (surfaceY < 260 || y < 56 || surfaceY - y < 34) {
            return Double.POSITIVE_INFINITY;
        }
        int cellX = Math.floorDiv(x, MOUNTAIN_GALLERY_CELL_SIZE);
        int cellZ = Math.floorDiv(z, MOUNTAIN_GALLERY_CELL_SIZE);
        double density = Double.POSITIVE_INFINITY;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            density = Math.min(density, mountainGalleryCellDensity(
                    x, y, z, cellX + dx, cellZ + dz, worldSeed));
        }
        if (!Double.isFinite(density)) return density;

        double distortion = noise.cavern.GetNoise(
                x * 0.58f, y * 0.44f, z * 0.58f) * 4.8
                + noise.detail.GetNoise(
                x * 0.41f, y * 0.48f, z * 0.41f) * 2.1;
        return density + distortion;
    }

    private static double mountainGalleryCellDensity(double x, double y, double z,
                                                       int cellX, int cellZ,
                                                       long worldSeed) {
        long hash = mix64(worldSeed ^ MOUNTAIN_GALLERY_SALT
                ^ (long) cellX * 0xd1b54a32d192ed03L
                ^ (long) cellZ * 0xabc98388fb8fac03L);
        // Roughly half the mountain provinces remain solid. A found system is
        // substantial, but mountain interiors retain meaningful rock mass.
        if ((hash & 3L) >= 2L) return Double.POSITIVE_INFINITY;

        double centerX = cellX * (double) MOUNTAIN_GALLERY_CELL_SIZE + 256.0
                + signedBits(hash >>> 8, 72.0);
        double centerZ = cellZ * (double) MOUNTAIN_GALLERY_CELL_SIZE + 256.0
                + signedBits(hash >>> 22, 72.0);
        double centerY = 105.0 + ((hash >>> 36) & 255L) * 0.92;
        if (Math.abs(x - centerX) > 330.0 || Math.abs(z - centerZ) > 330.0
                || Math.abs(y - centerY) > 105.0) {
            return Double.POSITIVE_INFINITY;
        }

        int direction = (int) ((hash >>> 52) & 7L);
        double axisX = DIRECTION_X[direction], axisZ = DIRECTION_Z[direction];
        double sideX = -axisZ, sideZ = axisX;
        double slope = signedBits(hash >>> 45, 29.0);

        double ax = centerX - axisX * 128.0 + sideX * 18.0;
        double az = centerZ - axisZ * 128.0 + sideZ * 18.0;
        double ay = centerY - slope;
        double bx = centerX + axisX * 142.0 - sideX * 24.0;
        double bz = centerZ + axisZ * 142.0 - sideZ * 24.0;
        double by = centerY + slope * 0.72;
        double horizontalRadius = 17.0 + ((hash >>> 57) & 7L);
        double verticalRadius = 11.0 + ((hash >>> 60) & 3L) * 1.7;
        double faultGallery = segmentGalleryDensity(x, y, z,
                ax, ay, az, bx, by, bz, horizontalRadius, verticalRadius);

        double mainVault = orientedEllipsoidDensity(
                x - centerX, y - centerY, z - centerZ,
                axisX, axisZ,
                74.0 + ((hash >>> 40) & 15L),
                27.0 + ((hash >>> 55) & 7L),
                46.0 + ((hash >>> 48) & 15L));

        double sideCenterX = centerX + sideX * 102.0 + axisX * 28.0;
        double sideCenterZ = centerZ + sideZ * 102.0 + axisZ * 28.0;
        double sideCenterY = centerY - 12.0 + signedBits(hash >>> 28, 11.0);
        double sideVault = orientedEllipsoidDensity(
                x - sideCenterX, y - sideCenterY, z - sideCenterZ,
                sideX, sideZ, 59.0, 21.0, 37.0);
        double sideLink = segmentGalleryDensity(x, y, z,
                centerX, centerY, centerZ,
                sideCenterX, sideCenterY, sideCenterZ, 15.0, 10.0);

        // Only some systems receive a tall tectonic fracture. Its offset vault
        // shape avoids the perfectly circular vertical holes seen previously.
        double fracture = Double.POSITIVE_INFINITY;
        if ((hash & 8L) != 0L) {
            double fractureX = centerX - axisX * 46.0 - sideX * 31.0;
            double fractureZ = centerZ - axisZ * 46.0 - sideZ * 31.0;
            fracture = orientedEllipsoidDensity(
                    x - fractureX, y - (centerY + 17.0), z - fractureZ,
                    axisX, axisZ, 43.0, 54.0, 19.0);
        }
        return Math.min(faultGallery,
                Math.min(mainVault, Math.min(sideVault, Math.min(sideLink, fracture))));
    }

    private static double orientedEllipsoidDensity(double dx, double dy, double dz,
                                                    double axisX, double axisZ,
                                                    double longRadius, double highRadius,
                                                    double wideRadius) {
        double along = dx * axisX + dz * axisZ;
        double across = -dx * axisZ + dz * axisX;
        double normalized = Math.sqrt(
                along * along / (longRadius * longRadius)
                        + dy * dy / (highRadius * highRadius)
                        + across * across / (wideRadius * wideRadius));
        return (normalized - 1.0) * Math.min(highRadius, Math.min(longRadius, wideRadius));
    }

    private static SeededNoise noiseFor(long worldSeed) {
        SeededNoise current = seededNoise;
        if (current.seed == worldSeed) return current;
        synchronized (ImmensaCaveSampler.class) {
            current = seededNoise;
            if (current.seed != worldSeed) {
                current = createNoise(worldSeed);
                seededNoise = current;
            }
            return current;
        }
    }

    private static SeededNoise createNoise(long worldSeed) {
        long mixed = mix64(worldSeed);
        FastNoiseLite cavern = noise((int) mixed, 1.0f / 148.0f, 3);
        FastNoiseLite cheese = noise((int) (mixed >>> 32) ^ 0x243f6a88, 1.0f / 74.0f, 2);
        FastNoiseLite tunnelA = noise((int) (mixed >>> 32) ^ 0x51ed270b, 1.0f / 47.0f, 1);
        FastNoiseLite tunnelB = noise((int) mixed ^ 0x68bc21eb, 1.0f / 53.0f, 1);
        FastNoiseLite detail = noise((int) (mixed >>> 32) ^ 0x02e5be93, 1.0f / 21.0f, 1);
        return new SeededNoise(worldSeed, cavern, cheese, tunnelA, tunnelB, detail);
    }

    /**
     * Density interpolation visits many Y values for one X/Z column. Keep the
     * expensive 2-D geological fields on the sampling thread and reuse them for
     * the full column without locks or cross-worker mutable state.
     */
    private static final class ColumnNoiseCache {
        long seed = Long.MIN_VALUE;
        int x = Integer.MIN_VALUE;
        int z = Integer.MIN_VALUE;
        double cavernShell;
        double chamberShell;
        double tunnelShell;
        double bottomFloor = Double.NaN;
        double deepLavaFloor = Double.NaN;

        void update(SeededNoise noise, int x, int z, long seed) {
            if (this.seed == seed && this.x == x && this.z == z) return;
            this.seed = seed;
            this.x = x;
            this.z = z;
            cavernShell = surfaceShellDepth(noise, x, z, 33.0, 0.0);
            chamberShell = surfaceShellDepth(noise, x, z, 25.0, 911.0);
            tunnelShell = surfaceShellDepth(noise, x, z, 28.0, -733.0);
            bottomFloor = Double.NaN;
            deepLavaFloor = Double.NaN;
        }

        double bottomFloor(SeededNoise noise) {
            if (Double.isNaN(bottomFloor)) bottomFloor = bottomFloorY(x, z, noise);
            return bottomFloor;
        }

        double deepLavaFloor(SeededNoise noise) {
            if (Double.isNaN(deepLavaFloor)) deepLavaFloor = deepLavaFloorY(x, z, noise);
            return deepLavaFloor;
        }
    }

    private static FastNoiseLite noise(int seed, float frequency, int octaves) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        noise.SetFrequency(frequency);
        noise.SetRotationType3D(FastNoiseLite.RotationType3D.ImproveXZPlanes);
        if (octaves > 1) {
            noise.SetFractalType(FastNoiseLite.FractalType.FBm);
            noise.SetFractalOctaves(octaves);
            noise.SetFractalLacunarity(2.0f);
            noise.SetFractalGain(0.52f);
        }
        return noise;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record SeededNoise(long seed, FastNoiseLite cavern, FastNoiseLite cheese,
                               FastNoiseLite tunnelA, FastNoiseLite tunnelB,
                               FastNoiseLite detail) {
    }
}
