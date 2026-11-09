package studio.onto.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal plugin catalog. Entries are pinned by SHA-256 and signed with an
 * HMAC key so a curated list can be shared without trusting the transport.
 */
public final class PluginCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALGORITHM = "HmacSHA256";

    public record Entry(String url, String sha256, String signature) {
    }

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public PluginCatalog(Path file) {
        this.file = file;
        load();
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home", "."), ".onto-studio", "plugins", "catalog.json");
    }

    public synchronized Map<String, Entry> all() {
        return Map.copyOf(entries);
    }

    public synchronized Entry get(String name) {
        return entries.get(name);
    }

    public synchronized Entry add(String name, String url, String sha256) {
        String key = key();
        if (key == null) {
            throw new IllegalStateException("Set ONTO_STUDIO_CATALOG_KEY to curate the catalog");
        }
        Entry entry = new Entry(url, sha256, sign(key, name, url, sha256));
        entries.put(name, entry);
        save();
        return entry;
    }

    /** True when the stored signature matches the pinned url and digest. */
    public synchronized boolean verify(String name, Entry entry) {
        String key = key();
        if (key == null || entry == null) {
            return false;
        }
        String expected = sign(key, name, entry.url(), entry.sha256());
        return expected.equalsIgnoreCase(entry.signature());
    }

    private static String key() {
        String value = EnvFile.get("ONTO_STUDIO_CATALOG_KEY");
        return value == null || value.isBlank() ? null : value;
    }

    private static String sign(String key, String name, String url, String sha256) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal((name + "|" + url + "|" + sha256).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            entries.putAll(MAPPER.readValue(file.toFile(), new TypeReference<Map<String, Entry>>() {
            }));
        } catch (IOException ignored) {
            // A corrupt catalog must not block the engine.
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), entries);
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
