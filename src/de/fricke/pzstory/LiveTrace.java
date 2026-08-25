package de.fricke.pzstory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * TEMPORARY live-test trace. Remove after the local Qwen evaluation.
 *
 * The trace contains private campaign and live-state text, but never provider
 * credentials. Each request replaces the previous trace so it cannot grow
 * without bound.
 */
final class LiveTrace {
    private static final boolean ENABLED = true;
    private static final int MAX_SECTION_CHARS = 256 * 1024;
    private static boolean receivedDelta;
    private static int replyChars;

    private LiveTrace() { }

    private static Path file() {
        return Config.file().resolveSibling("qwen-live-trace.txt");
    }

    static void request(String system, String cached, String tail) {
        if (!ENABLED) return;
        synchronized (LiveTrace.class) {
            receivedDelta = false;
            replyChars = 0;
        }
        String user = safe(cached) + safe(tail);
        String text = "PZSTORY TEMPORARY LOCAL TRACE\n"
                + "This file contains private campaign and live-state text.\n\n"
                + "===== SYSTEM =====\n" + safe(system)
                + "\n\n===== USER =====\n" + user
                + "\n\n===== RAW REPLY =====\n";
        try {
            Files.writeString(file(), text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            Config.log("temporary live trace could not start: " + e.getMessage());
        }
    }

    static void reply(String raw) {
        boolean streamed;
        synchronized (LiveTrace.class) { streamed = receivedDelta; }
        // Unit fixtures and non-streaming providers still get the complete
        // reply here. Streaming providers have already written each delta.
        if (!streamed) appendReply(safe(raw));
        append("\n\n===== VALIDATION =====\n");
    }

    /** Makes the raw reply readable while the player is still waiting. */
    static void delta(String text) {
        if (!ENABLED || text == null || text.isEmpty()) return;
        String accepted;
        synchronized (LiveTrace.class) {
            int remaining = MAX_SECTION_CHARS - replyChars;
            if (remaining <= 0) return;
            accepted = text.length() <= remaining ? text : text.substring(0, remaining)
                    + "\n[trace truncated at " + MAX_SECTION_CHARS + " characters]";
            replyChars += Math.min(text.length(), remaining);
            receivedDelta = true;
        }
        appendReply(accepted);
    }

    static void validation(String result) {
        append(safe(result) + "\n");
    }

    private static void append(String value) {
        if (!ENABLED) return;
        try {
            Files.writeString(file(), value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Config.log("temporary live trace could not append: " + e.getMessage());
        }
    }

    private static void appendReply(String value) { append(value); }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() <= MAX_SECTION_CHARS
                ? value : value.substring(0, MAX_SECTION_CHARS)
                        + "\n[trace truncated at " + MAX_SECTION_CHARS + " characters]";
    }
}
