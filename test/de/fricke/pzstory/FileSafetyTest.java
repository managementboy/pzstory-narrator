package de.fricke.pzstory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded reads and complete-file replacement, using a real temporary tree. */
public final class FileSafetyTest {

    public static void run() {
        T.group("BoundedFiles - limit is enforced while reading");
        Path root = null;
        try {
            root = Files.createTempDirectory("pzstory-file-test-");
            Path input = root.resolve("input.json");
            Files.writeString(input, "éé", StandardCharsets.UTF_8);
            T.eq("UTF-8 content survives", "éé", BoundedFiles.readUtf8(input, 4));
            try {
                BoundedFiles.readUtf8(input, 3);
                T.ok("byte ceiling rejects multibyte overflow", false);
            } catch (IOException expected) {
                T.ok("byte ceiling rejects multibyte overflow",
                        String.valueOf(expected.getMessage()).contains("exceeds 3 bytes"));
            }
            Files.write(input, new byte[] { (byte) 0xc3, 0x28 });
            try {
                BoundedFiles.readUtf8(input, 4);
                T.ok("malformed UTF-8 is rejected", false);
            } catch (IOException expected) {
                T.ok("malformed UTF-8 is rejected", true);
            }

            T.group("AtomicFiles - replacement is complete and repeatable");
            Path target = root.resolve("nested/state.json");
            AtomicFiles.writeUtf8(target, "{\"generation\":1}");
            T.eq("first write", "{\"generation\":1}", Files.readString(target));
            AtomicFiles.writeUtf8(target, "{\"generation\":2,\"ok\":true}");
            T.eq("replacement write", "{\"generation\":2,\"ok\":true}",
                    Files.readString(target));
            T.ok("temporary file removed",
                    !Files.exists(target.resolveSibling("state.json.tmp")));

            T.group("AtomicFiles - last-known-good rotation");
            Path backed = root.resolve("nested/campaign.json");
            Path backup = root.resolve("nested/campaign.json.bak");
            AtomicFiles.writeUtf8(backed, "old", backup);
            AtomicFiles.writeUtf8(backed, "new", backup);
            T.eq("new target installed", "new", Files.readString(backed));
            T.eq("previous target retained", "old", Files.readString(backup));
            AtomicFiles.writeUtf8(backed, "newest", backup);
            T.eq("backup advances one generation", "new", Files.readString(backup));

            T.group("AtomicFiles - byte ceiling precedes replacement");
            try {
                AtomicFiles.writeUtf8(backed, "too large", backup, 3);
                T.ok("oversized replacement is rejected", false);
            } catch (IOException expected) {
                T.ok("oversized replacement is rejected",
                        String.valueOf(expected.getMessage()).contains("exceeds 3 bytes"));
            }
            T.eq("oversized write leaves target intact", "newest",
                    Files.readString(backed));
            T.eq("oversized write leaves backup intact", "new",
                    Files.readString(backup));
        } catch (Throwable t) {
            T.ok("temporary-file fixture completed: " + t, false);
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
