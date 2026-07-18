package th.in.midnight_network.immensa.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldScaleHydrologyTest {
    @Test
    void visibleDrainageDensityCompensatesForWorldScale() {
        assertEquals(32f, HydrologyProcessor.scaleAdjustedMinCatchmentKm2(8f, 30f, true), 0.001f);
        assertEquals(8f, HydrologyProcessor.scaleAdjustedMinCatchmentKm2(8f, 15f, true), 0.001f);
        assertEquals(8f / 9f, HydrologyProcessor.scaleAdjustedMinCatchmentKm2(8f, 5f, true), 0.001f);
    }
}
