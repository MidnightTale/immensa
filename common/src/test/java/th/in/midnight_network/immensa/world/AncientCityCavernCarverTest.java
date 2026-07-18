package th.in.midnight_network.immensa.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AncientCityCavernCarverTest {
    private static final int FLOOR = -52;
    private static final long SEED = 923847123L;
    private static final List<BoundingBox> PIECES = List.of(
            new BoundingBox(-40, -53, -18, -8, -18, 18),
            new BoundingBox(10, -53, -16, 42, -8, 16),
            new BoundingBox(-8, -53, -10, 10, -32, 10)
    );

    @Test
    void everyPieceGetsClearanceAndItsOwnFoundation() {
        AncientCityCavernCarver.ColumnProfile left =
                AncientCityCavernCarver.columnProfile(PIECES, -24, 0, FLOOR, SEED);
        AncientCityCavernCarver.ColumnProfile right =
                AncientCityCavernCarver.columnProfile(PIECES, 26, 0, FLOOR, SEED);

        assertTrue(left.carve());
        assertTrue(right.carve());
        assertTrue(left.ceilingY() >= -18 + 11);
        assertTrue(right.ceilingY() >= -8 + 11);
        assertEquals(-54, left.foundationTopY());
        assertEquals(-54, right.foundationTopY());
    }

    @Test
    void nearbyPieceVolumesJoinAcrossAStreet() {
        AncientCityCavernCarver.ColumnProfile street =
                AncientCityCavernCarver.columnProfile(PIECES, 1, 0, FLOOR, SEED);
        assertTrue(street.carve());
        assertTrue(street.ceilingY() > FLOOR + 20);
    }

    @Test
    void emptyBoundingRectangleCornersRemainNaturalRock() {
        AncientCityCavernCarver.ColumnProfile emptyCorner =
                AncientCityCavernCarver.columnProfile(PIECES, -40, 50, FLOOR, SEED);
        assertFalse(emptyCorner.carve());
        assertEquals(Integer.MIN_VALUE, emptyCorner.foundationTopY());
    }

    @Test
    void roofVariesSmoothlyInsteadOfBecomingOneFlatSlab() {
        int first = AncientCityCavernCarver.columnProfile(
                PIECES, -35, -5, FLOOR, SEED).ceilingY();
        int second = AncientCityCavernCarver.columnProfile(
                PIECES, -20, 5, FLOOR, SEED).ceilingY();
        int third = AncientCityCavernCarver.columnProfile(
                PIECES, 20, -6, FLOOR, SEED).ceilingY();

        assertTrue(Math.abs(first - second) < 20);
        assertNotEquals(first, third);
    }
}
