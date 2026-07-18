package th.in.midnight_network.immensa.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightConverterTest {
    private static final float NATIVE_RESOLUTION = 30.0f;
    private static final int[] WORLD_TOP_Y = {0, 399, 735, 1071, 1407, 1743, 1967};

    @Test
    void ordinaryTerrainIsNotCompressed() {
        assertEquals(63 + (int) (6_000 / 15.0),
                convert(6_000, 2));
    }

    @Test
    void extremeTerrainRoundsOffBelowEveryWorldCeiling() {
        for (int scale = 1; scale <= WorldScaleManager.MAX_SCALE; scale++) {
            int extremeY = convert(Short.MAX_VALUE, scale);
            assertTrue(extremeY <= WORLD_TOP_Y[scale] - 24,
                    "scale " + scale + " must retain summit and decoration headroom: " + extremeY);
        }
    }

    @Test
    void highMountainsRemainTallAndMonotonicAtScaleTwo() {
        int sixKm = convert(6_000, 2);
        int eightKm = convert(8_000, 2);
        int tenKm = convert(10_000, 2);
        int sixteenKm = convert(16_000, 2);

        assertTrue(sixKm < eightKm);
        assertTrue(eightKm < tenKm);
        assertTrue(tenKm < sixteenKm);
        assertTrue(sixKm > 450, "scale-two mountains should remain massive");
        assertTrue(sixteenKm < WORLD_TOP_Y[2] - 20,
                "regional uplift must taper before the scale-two ceiling");
    }

    @Test
    void extremeMassifReliefDoesNotCollapseIntoAFlatTable() {
        int lowerShoulder = convert(8_400, 2);
        int summit = convert(10_900, 2);
        int fourteenKm = convert(14_000, 2);

        assertTrue(summit - lowerShoulder >= 70,
                "2.5 km of extreme relief must remain visibly mountainous: "
                        + lowerShoulder + ".." + summit);
        assertTrue(fourteenKm >= 700,
                "scale-two supermassifs should be able to rise above Y700: " + fourteenKm);
        assertTrue(fourteenKm < WORLD_TOP_Y[2] - 20,
                "summits still need safe sky headroom: " + fourteenKm);
    }

    @Test
    void generatedTenKilometreSummitsUseScaleTwoVerticalSpace() {
        int summit = convert(10_500, 2);
        assertTrue(summit >= 650,
                "scale-two supermassif peaks should rise far beyond ordinary Y300 uplands: "
                        + summit);
        assertTrue(summit < WORLD_TOP_Y[2] - 24,
                "high peaks still need a safe non-flat cap below the build ceiling: " + summit);
    }

    private static int convert(int meters, int scale) {
        return HeightConverter.convertToMinecraftHeight(
                (short) meters, scale, NATIVE_RESOLUTION);
    }
}
