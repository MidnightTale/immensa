package th.in.midnight_network.immensa.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceCaveDecoratorTest {
    private static final ImmensaEntranceSelector.Site ENTRANCE =
            new ImmensaEntranceSelector.Site(
                    100, 200, 120, 1.0, 0.0, 1.0, 154.0);

    @Test
    void followsTheEntranceIntoTheHillside() {
        assertTrue(SurfaceCaveDecorator.isWithinEntranceGrotto(100, 200, ENTRANCE));
        assertTrue(SurfaceCaveDecorator.isWithinEntranceGrotto(160, 214, ENTRANCE));
        assertTrue(SurfaceCaveDecorator.isWithinEntranceGrotto(208, 220, ENTRANCE));
    }

    @Test
    void doesNotPaintTheWholeUnderground() {
        assertFalse(SurfaceCaveDecorator.isWithinEntranceGrotto(80, 200, ENTRANCE));
        assertFalse(SurfaceCaveDecorator.isWithinEntranceGrotto(140, 250, ENTRANCE));
        assertFalse(SurfaceCaveDecorator.isWithinEntranceGrotto(230, 200, ENTRANCE));
        assertFalse(SurfaceCaveDecorator.isWithinEntranceGrotto(100, 200,
                ImmensaEntranceSelector.Site.NONE));
    }

    @Test
    void expandedStructureVolumesAreNeverDecorated() {
        List<BoundingBox> protectedBoxes = List.of(
                new BoundingBox(90, -30, 190, 110, 20, 210));
        assertTrue(SurfaceCaveDecorator.isProtected(
                100, 0, 200, protectedBoxes));
        assertFalse(SurfaceCaveDecorator.isProtected(
                120, 0, 200, protectedBoxes));
    }
}
