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
        writeUtf8(target, text, null);
    }

    /**
     * Replaces {@code target} and, when requested, first rotates the previous
     * complete target to {@code backup}. A failure to preserve the old file
     * aborts before the live target is touched.
     */
    public static void writeUtf8(Path target, String text, Path backup)
            throws IOException {
        writeUtf8(target, text, backup, -1);
    }

    /** Same replacement, with a UTF-8 byte ceiling enforced before file writes. */
    public static void writeUtf8(Path target, String text, Path backup, int maxBytes)
            throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new IOException("target has no parent: " + target);
        Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Path backupTmp = backup == null ? null
                : backup.resolveSibling(backup.getFileName() + ".tmp");
        boolean moved = false;
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (maxBytes > 0 && bytes.length > maxBytes) {
                throw new IOException("UTF-8 document exceeds " + maxBytes
                        + " bytes (was " + bytes.length + ")");
            }
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }

            if (backup != null && Files.isRegularFile(target)) {
                Files.copy(target, backupTmp, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel channel = FileChannel.open(backupTmp,
                        StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                moveReplace(backupTmp, backup);
            }

            moveReplace(tmp, target);
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
            if (backupTmp != null) {
                try { Files.deleteIfExists(backupTmp); } catch (IOException ignored) { }
            }
        }
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            // Still a complete-file replacement because the temporary file was
            // closed and forced first; only the rename atomicity is unavailable.
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
