package th.in.midnight_network.immensa.world;

import th.in.midnight_network.immensa.pipeline.TerrainMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmensaCliffSamplerTest {
    @Test
    void ordinarySlopesRemainPureHeightfields() {
        double density = ImmensaCliffSampler.apply(
                -8.0, 120, 208, -90, 200, TerrainMetadata.SCREE, 812731L);
        assertEquals(-8.0, density);
    }

    @Test
    void majorFacesProduceAttachedLedgesAndShallowUndercuts() {
        short cliff = TerrainMetadata.withCliff(
                TerrainMetadata.SCREE, TerrainMetadata.CLIFF_EAST, true);
        boolean foundProfile = false;
        for (int z = -640; z <= 640 && !foundProfile; z += 8) {
            for (int x = -640; x <= 640; x += 8) {
                double ledge = ImmensaCliffSampler.apply(
                        -4.0, x, 204, z, 200, cliff, 812731L);
                double undercut = ImmensaCliffSampler.apply(
                        7.0, x, 193, z, 200, cliff, 812731L);
                if (ledge > 0.0 && undercut < 0.0) {
                    foundProfile = true;
                    break;
                }
            }
        }
        assertTrue(foundProfile,
                "a coherent major cliff province needs both a projecting lip and air beneath it");
    }

    @Test
    void overhangDensityIsDeterministic() {
        short cliff = TerrainMetadata.withCliff(
                (short) 0, TerrainMetadata.CLIFF_NORTH, true);
        double first = ImmensaCliffSampler.apply(
                4.0, 341, 188, -719, 196, cliff, 99187L);
        double second = ImmensaCliffSampler.apply(
                4.0, 341, 188, -719, 196, cliff, 99187L);
        assertEquals(first, second);
    }
}
