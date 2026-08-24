package de.fricke.pzstory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Crash-resistant replacement of small UTF-8 state files. */
public final class AtomicFiles {

    private AtomicFiles() {}

    public static void writeUtf8(Path target, String text) throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new IOException("target has no parent: " + target);
        Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        boolean moved = false;
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }

            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Still a complete-file replacement because the temporary
                // file was closed and forced first; only the rename's atomicity
                // is unavailable on this filesystem.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;

            // Persist the directory entry where the platform permits opening
            // a directory as a channel. Windows commonly refuses; the data
            // file itself has already been forced, so that is a safe fallback.
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException ignored) { }
        } finally {
            if (!moved) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
        }
    }
}
