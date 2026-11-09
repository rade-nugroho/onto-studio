package studio.onto.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Classification markings per entity. Stored next to the ontology so an export
 * can be filtered without touching the model itself.
 */
public final class MarkingStore {

    public static final Set<String> ALLOWED = Set.of("public", "internal", "restricted");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<String, String> markings = new LinkedHashMap<>();

    public MarkingStore(Path file) {
        this.file = file;
        load();
    }

    public static Path defaultPath(Path ontologyFile) {
        Path parent = ontologyFile.toAbsolutePath().getParent();
        return (parent == null ? Path.of(".") : parent).resolve(".onto-studio").resolve("markings.json");
    }

    public synchronized String get(String iri) {
        return markings.getOrDefault(iri, "public");
    }

    public synchronized void set(String iri, String marking) {
        if (!ALLOWED.contains(marking)) {
            throw new IllegalArgumentException("Unknown marking: " + marking);
        }
        if ("public".equals(marking)) {
            markings.remove(iri);
        } else {
            markings.put(iri, marking);
        }
        save();
    }

    public synchronized Map<String, String> all() {
        return Map.copyOf(markings);
    }

    public synchronized boolean isRestricted(String iri) {
        return "restricted".equals(get(iri));
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            markings.putAll(MAPPER.readValue(file.toFile(), new TypeReference<Map<String, String>>() {
            }));
        } catch (IOException ignored) {
            // A corrupt marking file must not block opening the ontology.
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), markings);
        } catch (IOException ignored) {
            // Best effort; the in-memory marking still applies for this session.
        }
    }
}
