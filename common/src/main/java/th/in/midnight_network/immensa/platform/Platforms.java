package th.in.midnight_network.immensa.platform;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ServiceLoader;

/**
 * Locates the active {@link Platform} implementation. Loader modules register
 * theirs via {@code META-INF/services}; when no provider is present (plain
 * unit tests) a safe fallback rooted at the working directory is used instead.
 * Lookup never throws.
 */
public final class Platforms {
    private static final Platform PLATFORM = load();

    private Platforms() {
    }

    public static Platform get() {
        return PLATFORM;
    }

    private static Platform load() {
        try {
            for (Platform platform : ServiceLoader.load(Platform.class, Platforms.class.getClassLoader())) {
                return platform;
            }
        } catch (Throwable ignored) {
            // Fall through to the default implementation below.
        }
        return new FallbackPlatform();
    }

    /**
     * Used when no loader is on the classpath (unit tests, standalone tools).
     * Everything stays under the current working directory.
     */
    private static final class FallbackPlatform implements Platform {
        private final Path gameDir = Paths.get("").toAbsolutePath();
        private final Path configDir = gameDir.resolve("config");

        @Override
        public Path configDir() {
            return configDir;
        }

        @Override
        public Path gameDir() {
            return gameDir;
        }

        @Override
        public boolean isModLoaded(String modid) {
            return false;
        }
    }
}
