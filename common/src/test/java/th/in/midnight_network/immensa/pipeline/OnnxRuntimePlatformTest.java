package th.in.midnight_network.immensa.pipeline;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtProvider;
import th.in.midnight_network.immensa.config.ImmensaConfig;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxRuntimePlatformTest {
    @Test
    void universalRuntimeLoadsNativeLibraryForCurrentPlatform() {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        assertFalse(environment.getVersion().isBlank());
        assertTrue(OrtEnvironment.getAvailableProviders().contains(OrtProvider.CPU));

        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if ("universal".equals(ImmensaConfig.buildVariant()) && os.contains("win")) {
            assertTrue(OrtEnvironment.getAvailableProviders().contains(OrtProvider.DIRECT_ML));
        }
    }
}
