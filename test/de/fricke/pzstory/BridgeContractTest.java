package de.fricke.pzstory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Static contract checks for code that cannot load without the game runtime. */
public final class BridgeContractTest {

    public static void run() {
        String lua = read("mod/42/media/lua/client/PZStory/PZStoryBook.lua");
        String llm = read("src/de/fricke/pzstory/Llm.java");

        T.group("Lua/Java bridge - structured JSON boundary");
        T.ok("shared strict decoder is exported",
                lua.contains("PZStoryJSONDecode = jsonDecode"));
        T.ok("production bridge decodes complete payloads",
                lua.contains("pcall(jsonDecode, raw)"));
        T.ok("raw JSON is not regex-extracted", !lua.contains("raw:match("));

        T.group("Lua/Java bridge - poll field names");
        for (String key : new String[] {
                "inputTokens", "cacheRead", "cacheWrite", "outputTokens"
        }) {
            T.ok("Java emits " + key,
                    llm.contains("j.put(\"" + key + "\""));
            T.ok("Lua reads " + key,
                    lua.contains("data." + key));
        }
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
