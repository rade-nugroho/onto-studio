package studio.onto.engine;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only record of where every axiom came from: imported from a file,
 * typed by hand, proposed by the assistant, or removed. Stored as JSON lines
 * next to the ontology so it survives restarts and can be inspected by hand.
 */
public final class EvidenceStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final List<Map<String, Object>> rows = new ArrayList<>();

    public EvidenceStore(Path file) {
        this.file = file;
        load();
    }

    public static Path defaultPath(Path ontologyFile) {
        Path parent = ontologyFile.toAbsolutePath().getParent();
        return (parent == null ? Path.of(".") : parent).resolve(".onto-studio").resolve("evidence.jsonl");
    }

    public synchronized void record(String action, String source, String axiom, List<String> entities,
                                    double confidence, Map<String, Object> extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", Instant.now().toString());
        row.put("action", action);
        row.put("source", source);
        row.put("author", author());
        row.put("axiom", axiom);
        row.put("entities", entities);
        row.put("confidence", confidence);
        if (extra != null) {
            row.putAll(extra);
        }
        rows.add(row);
        append(row);
    }

    /** Rows that mention this entity, newest first. */
    public synchronized List<Map<String, Object>> forEntity(String iri) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> row = rows.get(i);
            Object entities = row.get("entities");
            if (entities instanceof List<?> list && list.contains(iri)) {
                matches.add(row);
            }
        }
        return matches;
    }

    /** Rows for one exact axiom text, newest first. */
    public synchronized List<Map<String, Object>> forAxiom(String axiom) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (axiom.equals(rows.get(i).get("axiom"))) {
                matches.add(rows.get(i));
            }
        }
        return matches;
    }

    /** Recent action commits, newest first. */
    public synchronized List<Map<String, Object>> recentActions(int limit) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0 && matches.size() < limit; i--) {
            if ("action".equals(rows.get(i).get("action"))) {
                matches.add(rows.get(i));
            }
        }
        return matches;
    }

    /** The most recent import batch, which explains axioms nobody edited yet. */
    public synchronized Map<String, Object> lastImport() {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if ("import".equals(rows.get(i).get("action"))) {
                return rows.get(i);
            }
        }
        return null;
    }

    private static String author() {
        String configured = EnvFile.get("ONTO_STUDIO_AUTHOR");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return System.getProperty("user.name", "unknown");
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    rows.add(MAPPER.readValue(line, Map.class));
                }
            }
        } catch (IOException error) {
            // A corrupt evidence file must not block opening the ontology.
            System.err.println("[engine] Failed to read evidence from " + file + ": " + error.getMessage());
        }
    }

    private void append(Map<String, Object> row) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(row) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            // Evidence is best effort; the edit itself already succeeded.
            System.err.println("[engine] Failed to append evidence to " + file + ": " + error.getMessage());
        }
    }
}
