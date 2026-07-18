package th.in.midnight_network.immensa.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureCompatibilityTagsTest {
    private static final Path DATA = Path.of("src", "main", "resources", "data");
    private static final String COMPAT_PREFIX = "#immensa:compat/";

    @Test
    void compatibilityTagsAreAdditiveAndReferenceExistingCategories() throws Exception {
        Set<String> categories = new HashSet<>();
        Path categoryRoot = DATA.resolve(Path.of(
                "immensa", "tags", "worldgen", "biome", "compat"));
        try (Stream<Path> files = Files.walk(categoryRoot)) {
            files.filter(path -> path.toString().endsWith(".json"))
                    .forEach(path -> categories.add(categoryRoot.relativize(path)
                            .toString().replace('\\', '/').replaceFirst("\\.json$", "")));
        }
        assertFalse(categories.isEmpty(), "compatibility category layer is missing");

        try (Stream<Path> files = Files.walk(DATA)) {
            for (Path path : files.filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> path.toString().contains(
                            "tags" + java.io.File.separator + "worldgen" + java.io.File.separator + "biome"))
                    .toList()) {
                JsonObject tag = read(path);
                assertTrue(!tag.has("replace") || !tag.get("replace").getAsBoolean(),
                        () -> "compatibility tag must merge instead of replace: " + path);
                JsonArray values = tag.getAsJsonArray("values");
                assertNotNull(values, () -> "tag has no values array: " + path);
                assertFalse(values.isEmpty(), () -> "tag is empty: " + path);
                for (JsonElement element : values) {
                    String id = element.isJsonPrimitive()
                            ? element.getAsString()
                            : element.getAsJsonObject().get("id").getAsString();
                    if (id.startsWith(COMPAT_PREFIX)) {
                        assertTrue(categories.contains(id.substring(COMPAT_PREFIX.length())),
                                () -> "missing compatibility category " + id + " referenced by " + path);
                    }
                    if (element.isJsonObject() && id.startsWith("still_life:")) {
                        assertTrue(element.getAsJsonObject().has("required")
                                        && !element.getAsJsonObject().get("required").getAsBoolean(),
                                () -> "Still Life bridge must stay optional: " + path + " -> " + id);
                    }
                }
            }
        }
    }

    @Test
    void installedStructurePackEntryPointsAreBridged() {
        for (String relative : new String[] {
                "epic/tags/worldgen/biome/large_dungeon.json",
                "epic/tags/worldgen/biome/large_igloo.json",
                "betterarcheology/tags/worldgen/biome/collections/plains.json",
                "zhopo/tags/worldgen/biome/has_structure/deepslate_mineshaft.json",
                "zhopo/tags/worldgen/biome/has_structure/underwater_city.json",
                "mtr/tags/worldgen/biome/has_structure/overworld_biomes.json",
                "nova_structures/tags/worldgen/biome/collections/oceans.json",
                "minecraft/tags/worldgen/biome/has_structure/stronghold.json",
                "minecraft/tags/worldgen/biome/has_structure/trial_chambers.json"
        }) {
            assertTrue(Files.isRegularFile(DATA.resolve(relative)),
                    () -> "missing structure compatibility bridge: " + relative);
        }
    }

    private static JsonObject read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
