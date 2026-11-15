package studio.onto.engine;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic file write helpers using a sibling temporary file and rename.
 */
final class AtomicFiles {

    private AtomicFiles() {
    }

    static void writeJson(Path file, ObjectMapper mapper, Object value) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent != null ? parent : Path.of("."),
                file.getFileName().toString(), ".tmp");
        try {
            mapper.writeValue(tmp.toFile(), value);
            moveReplacing(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static void writeBytes(Path file, byte[] bytes) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent != null ? parent : Path.of("."),
                file.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes);
            moveReplacing(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
