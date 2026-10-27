package ch.fmartin.outlooktoimap.microsoft;

import ch.fmartin.outlooktoimap.files.PrivateFiles;
import com.microsoft.aad.msal4j.ITokenCacheAccessAspect;
import com.microsoft.aad.msal4j.ITokenCacheAccessContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps one account's MSAL token cache in a file readable only by its owner. Every refresh hands
 * out a new refresh token, so the file is rewritten after each change; the rename makes a crash
 * leave either the old file or the new one, never half of one.
 */
// Not final, so a test can make a write fail through a subclass.
class TokenCacheFile implements ITokenCacheAccessAspect {

    private final Path file;

    TokenCacheFile(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    boolean exists() {
        return Files.exists(file);
    }

    @Override
    public void beforeCacheAccess(ITokenCacheAccessContext context) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            context.tokenCache().deserialize(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read token cache " + file, e);
        }
    }

    @Override
    public void afterCacheAccess(ITokenCacheAccessContext context) {
        if (context.hasCacheChanged()) {
            write(context.tokenCache().serialize());
        }
    }

    private void write(String content) {
        try {
            Path directory = file.getParent();
            PrivateFiles.createDirectories(directory);
            // A unique name, because a dry run or authorize can refresh the same login while the
            // service does.
            Path temporary = PrivateFiles.createTempFile(directory, file.getFileName() + ".", ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                // Writing the file in place could leave half a token cache after a crash, and the account would need a new login, see docs/adr/0008-device-code-sign-in-with-a-private-token-file-per-account.md
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write token cache " + file, e);
        }
    }
}
