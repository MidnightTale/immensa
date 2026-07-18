package th.in.midnight_network.immensa.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrialChamberPlacementValidatorTest {
    @Test
    void acceptsMostlyBuriedChamberWithOneNaturalCaveIntersection() {
        assertTrue(TrialChamberPlacementValidator.isAcceptable(
                25, 44, 36, 18, 2));
    }

    @Test
    void rejectsFloatingChamberOverLavaMegacavern() {
        assertFalse(TrialChamberPlacementValidator.isAcceptable(
                25, 18, 12, 7, 20));
    }

    @Test
    void rejectsChamberWithoutEnoughRoofCover() {
        assertFalse(TrialChamberPlacementValidator.isAcceptable(
                25, 46, 22, 20, 0));
    }
}
