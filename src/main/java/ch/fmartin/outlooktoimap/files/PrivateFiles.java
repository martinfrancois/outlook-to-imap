package ch.fmartin.outlooktoimap.files;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Creates directories and files that only their owner can read. The state directory holds the
 * logins and the receipts, which other users on the host must not read. On a file system without
 * POSIX modes, such as on Windows, every method here works but sets no mode.
 */
public final class PrivateFiles {

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");

    private PrivateFiles() {}

    /** Whether this file system has POSIX modes, so the other methods set them. */
    public static boolean supported() {
        return POSIX;
    }

    /** Creates a directory and its missing parents, each new one with mode 700. */
    public static void createDirectories(Path directory) throws IOException {
        if (POSIX) {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(DIRECTORY));
        } else {
            Files.createDirectories(directory);
        }
    }

    /** Creates an empty file with a unique name in the directory, with mode 600. */
    public static Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
        FileAttribute<?>[] attributes = POSIX
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE)}
                : new FileAttribute<?>[0];
        return Files.createTempFile(directory, prefix, suffix, attributes);
    }

    /** Gives an existing file mode 600. */
    public static void restrict(Path file) throws IOException {
        if (POSIX) {
            Files.setPosixFilePermissions(file, FILE);
        }
    }
}
