package studio.onto.engine.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read/write {@code .onto-studio/mapping.json} next to the open ontology.
 */
public final class ConnectorMapping {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final List<ClassMapping> mappings = new ArrayList<>();

    public ConnectorMapping(Path file) {
        this.file = file;
        load();
    }

    public static Path defaultPath(Path ontologyFile) {
        Path parent = ontologyFile.toAbsolutePath().getParent();
        return (parent == null ? Path.of(".") : parent).resolve(".onto-studio").resolve("mapping.json");
    }

    public synchronized List<ClassMapping> all() {
        return List.copyOf(mappings);
    }

    public synchronized Optional<ClassMapping> byClassIri(String classIri) {
        if (classIri == null) {
            return Optional.empty();
        }
        return mappings.stream().filter(mapping -> classIri.equals(mapping.classIri())).findFirst();
    }

    public synchronized void put(ClassMapping mapping) {
        mappings.removeIf(existing -> existing.classIri().equals(mapping.classIri()));
        mappings.add(mapping);
        save();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, Object> root = MAPPER.readValue(file.toFile(), new TypeReference<>() {
            });
            Object raw = root.get("mappings");
            if (!(raw instanceof List<?> list)) {
                return;
            }
            mappings.clear();
            for (Object item : list) {
                ClassMapping mapping = MAPPER.convertValue(item, ClassMapping.class);
                if (mapping.classIri() != null && !mapping.classIri().isBlank()) {
                    mappings.add(mapping);
                }
            }
        } catch (IOException error) {
            System.err.println("[engine] Failed to read connector mappings from " + file + ": " + error.getMessage());
        }
    }

    private void save() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("mappings", mappings);
            writeJsonAtomic(file, root);
        } catch (IOException error) {
            System.err.println("[engine] Failed to write connector mappings to " + file + ": " + error.getMessage());
        }
    }

    private static void writeJsonAtomic(Path file, Object value) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent != null ? parent : Path.of("."),
                file.getFileName().toString(), ".tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClassMapping(
            String classIri,
            String service,
            String connectionName,
            String actionId,
            String idField,
            Map<String, String> fieldMap,
            Map<String, Object> actionInput) {
    }
}