package th.in.midnight_network.immensa.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NoiseSettingsSurfaceRuleTest {
    @Test
    void soilRulesOnlyRunAboveThePreliminarySurface() throws Exception {
        String resource = "/data/immensa/worldgen/noise_settings/terrain_diffusion.json";
        try (InputStream stream = NoiseSettingsSurfaceRuleTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "missing Terrain Diffusion noise settings");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(
                    stream, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray rootRules = root.getAsJsonObject("surface_rule").getAsJsonArray("sequence");

            // Only bedrock remains global. Stone/deepslate is applied later by
            // GeologicalStrataPlacer so it can vary continuously in 3D.
            assertEquals(2, rootRules.size());
            JsonObject bedrockRule = rootRules.get(0).getAsJsonObject();
            assertEquals("minecraft:bedrock_floor",
                    bedrockRule.getAsJsonObject("if_true")
                            .get("random_name").getAsString());
            JsonObject surfaceGuard = rootRules.get(1).getAsJsonObject();
            assertEquals("minecraft:above_preliminary_surface",
                    surfaceGuard.getAsJsonObject("if_true").get("type").getAsString());
            assertEquals(3, surfaceGuard.getAsJsonObject("then_run")
                    .getAsJsonArray("sequence").size());
        }
    }
}
