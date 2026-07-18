package th.in.midnight_network.immensa.platform;

import java.nio.file.Path;

/**
 * Loader-neutral view of the small slice of platform services common code
 * needs. Implementations are supplied per loader through
 * {@code META-INF/services/th.in.midnight_network.immensa.platform.Platform}.
 */
public interface Platform {
    /** Directory holding mod configuration files. */
    Path configDir();

    /** Root game directory (e.g. the {@code .minecraft} directory). */
    Path gameDir();

    /** Whether the given mod id is present in the current instance. */
    boolean isModLoaded(String modid);
}
