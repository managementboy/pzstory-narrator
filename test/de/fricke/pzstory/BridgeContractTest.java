package de.fricke.pzstory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Static contract checks for code that cannot load without the game runtime. */
public final class BridgeContractTest {

    public static void run() {
        String lua = read("mod/42/media/lua/client/PZStory/PZStoryBook.lua");
        String llm = read("src/de/fricke/pzstory/Llm.java");
        String api = read("src/de/fricke/pzstory/StoryAPI.java");

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

        T.group("Lua/Java bridge - safe generation lifecycle");
        for (String status : new String[] { "RECEIVED", "COMMITTING" }) {
            T.ok("Java declares " + status, llm.contains(status));
            T.ok("Lua handles " + status,
                    lua.contains("status == \"" + status + "\""));
        }
        T.ok("streaming page exposes STOP", lua.contains("labels = { \"STOP\" }"));
        T.ok("STOP invokes Java cancellation", lua.contains("api(\"cancelPage\")"));
        T.ok("cancelled output is visibly discarded",
                lua.contains("status == \"CANCELLED\"")
                        && lua.contains("Nothing was saved to this story"));
        T.ok("invalid replies get a distinct fault page",
                lua.contains("invalid_output = { \"UNREADABLE PAGE\""));
        T.ok("save failures get a distinct fault page",
                lua.contains("save = { \"STORAGE FAILURE\""));
        T.ok("rejected notes cannot start a paid page",
                lua.contains("local accepted = result == \"kept as canon\"")
                        && lua.contains("if not accepted then"));
        T.ok("failed task edits are not reported as saved",
                lua.contains("self.statusLine = \"could not save that change\""));

        T.group("Story request - validation and privacy boundary");
        T.ok("provider state is projected",
                api.contains("NarrativeState.fromRaw(state)"));
        T.ok("raw state is retained only for local delta",
                api.contains("Delta.between(Campaign.lastState(), state)"));
        T.ok("terminal reply uses strict parser",
                api.contains("PageResult.parse(all, firstPage, targetWords)"));
        T.ok("save failure is not completion",
                api.contains("stored ? null : Llm.CompletionResult.failure"));
        T.ok("privacy preview is exposed",
                api.contains("String providerPreview()"));
        T.ok("local observer is exposed",
                api.contains("void observeWorld()")
                        && lua.contains("api(\"observeWorld\")"));
        T.ok("observer uses lightweight state and never starts a request",
                api.contains("StateReader.eventSnapshot()")
                        && !method(api, "public static void observeWorld()")
                                .contains("Llm.start"));
        T.ok("request captures pending events before provider start",
                api.contains("EventJournal.Capture capturedEvents")
                        && api.contains("capturedEvents.ids"));
        T.ok("page commit consumes its exact event batch",
                read("src/de/fricke/pzstory/Campaign.java")
                        .contains("EVENTS.markNarrated(consumedEventIds"));
        T.ok("Gemini thought summaries cannot enter page text",
                llm.contains("pm.get(\"thought\")")
                        && llm.contains("j.put(\"includeThoughts\", false)"));
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) return "";
        int next = source.indexOf("\n    public static", start + signature.length());
        return next < 0 ? source.substring(start) : source.substring(start, next);
    }
}
