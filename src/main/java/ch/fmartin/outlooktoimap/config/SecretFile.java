package ch.fmartin.outlooktoimap.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a password kept alone in a file, such as a mounted container secret. */
public final class SecretFile {

    private SecretFile() {}

    /** The file's content without surrounding whitespace, so a trailing newline does no harm. */
    public static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
