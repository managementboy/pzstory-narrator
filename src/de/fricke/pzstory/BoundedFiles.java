package de.fricke.pzstory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small, race-safe bounded reads for every player-controlled JSON file. */
public final class BoundedFiles {

    private BoundedFiles() {}

    /**
     * Reads UTF-8 without trusting a size sampled before the file was opened.
     * A file can grow between Files.size() and readAllBytes(); this loop stops
     * while reading and therefore keeps the memory ceiling true under races.
     */
    public static String readUtf8(Path path, int maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        try (InputStream in = Files.newInputStream(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream(
                     Math.min(maxBytes, 8192))) {
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                total += n;
                if (total > maxBytes) {
                    throw new IOException(path.getFileName() + " exceeds "
                            + maxBytes + " bytes");
                }
                out.write(buf, 0, n);
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString();
        }
    }
}
