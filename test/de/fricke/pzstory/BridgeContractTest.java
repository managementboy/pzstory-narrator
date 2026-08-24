package de.fricke.pzstory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Static contract checks for code that cannot load without the game runtime. */
public final class BridgeContractTest {

    public static void run() {
        String lua = read("mod/42/media/lua/client/PZStory/PZStoryBook.lua");
        String probe = read("dev/PZStory_Probe.lua");
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
        T.ok("Campaign Director is explicit opt-in",
                api.contains("setCampaignMode(String mode)")
                        && lua.contains("story mode")
                        && lua.contains("campaignMode"));
        T.ok("non-destructive Test Lab scenarios are exposed",
                api.contains("String testLabScenario(String scenario)"));
        T.ok("typed fact diagnostics are local and explicit",
                api.contains("String factMemory()")
                        && probe.contains("decodeDiagnostic(\"factMemory\")"));
        T.ok("setup/payoff diagnostics are local and explicit",
                api.contains("String threadMemory()")
                        && probe.contains("decodeDiagnostic(\"threadMemory\")")
                        && probe.contains("STORY THREADS"));
        T.ok("continuity evidence diagnostics keep counts local",
                api.contains("String continuityMemory()")
                        && probe.contains("decodeDiagnostic(\"continuityMemory\")")
                        && probe.contains("CONTINUITY EVIDENCE"));
        T.ok("local observer is exposed",
                api.contains("void observeWorld()")
                        && lua.contains("api(\"observeWorld\")"));
        T.ok("observer uses lightweight state and never starts a request",
                api.contains("StateReader.eventSnapshot()")
                        && !method(api, "public static void observeWorld()")
                                .contains("Llm.start"));
        T.ok("event snapshot includes held item and vehicle occupancy",
                read("src/de/fricke/pzstory/StateReader.java")
                        .contains("j.put(\"primaryHand\"")
                        && read("src/de/fricke/pzstory/StateReader.java")
                                .contains("eventInventory(j, p);\n        vehicle(j, p);"));
        T.ok("supported transient callbacks force a factual sample",
                api.contains("void observeNow()")
                        && lua.contains("Events.OnZombieDead")
                        && lua.contains("Events.OnPlayerAttackFinished")
                        && lua.contains("api(\"observeNow\")"));
        T.ok("transient callbacks never start a provider request",
                !method(lua, "local function observeTransient")
                        .contains("requestPage"));
        T.ok("action hooks record only successful completions",
                lua.contains("local function hookComplete")
                        && lua.contains("local result = original(self, ...)")
                        && lua.contains("if result == true then")
                        && lua.contains("api(\"recordAction\")"));
        for (String timedAction : new String[] {
                "TimedActions/ISCraftAction", "TimedActions/ISRepairClothing",
                "Vehicles/TimedActions/ISRepairEngine",
                "Farming/TimedActions/ISSeedActionNew",
                "Camping/TimedActions/ISLightFromPetrol"
        }) {
            T.ok(timedAction + " is explicitly loaded",
                    lua.contains("require \"" + timedAction + "\""));
        }
        for (String action : new String[] {
                "crafted", "repaired", "farmed", "fire_started", "item_used"
        }) {
            T.ok(action + " completion is covered",
                    lua.contains("\"" + action + "\""));
        }
        T.ok("door state is sampled before its successful toggle",
                lua.indexOf("local wasOpen = self.item and self.item:IsOpen()")
                        < lua.indexOf("local result = originalDoorComplete"));
        T.ok("action bridge uses pure allow-list policy",
                api.contains("ActionEventPolicy.resolve(action, detail)"));
        T.ok("request captures pending events before provider start",
                api.contains("EventJournal.Capture capturedEvents")
                        && api.contains("capturedEvents.ids"));
        T.ok("page commit consumes its exact event batch",
                read("src/de/fricke/pzstory/Campaign.java")
                        .contains("EVENTS.markNarrated(consumedEventIds"));
        T.ok("Gemini thought summaries cannot enter page text",
                llm.contains("pm.get(\"thought\")")
                        && llm.contains("j.put(\"includeThoughts\", false)"));

        T.group("Testing Mode - local diagnostics overlay");
        T.ok("F8 toggles Testing Mode",
                probe.contains("Keyboard.KEY_F8")
                        && probe.contains("toggleOverlay()"));
        T.ok("overlay reads local diagnostics only",
                probe.contains("decodeDiagnostic(\"worldMemory\")")
                        && probe.contains("decodeDiagnostic(\"eventJournal\")"));
        T.ok("raw place ids are compared but never rendered",
                probe.contains("place.id == memory.currentPlaceId")
                        && !method(probe, "local function drawOverlay()")
                                .contains("currentPlaceId"));
        T.ok("place visit count and pending events are visible",
                probe.contains("VISIT \" .. visits")
                        && probe.contains("PENDING INBOX"));
        T.ok("Director objective operations are visible without hidden plans",
                api.contains("String directorStatus()")
                        && probe.contains("decodeDiagnostic(\"directorStatus\")")
                        && probe.contains("DIRECTOR \" .. d.state:upper()")
                        && probe.contains("EVIDENCE \"")
                        && probe.contains("REVEALED \"")
                        && !method(api, "public static String directorStatus()")
                                .contains("fixedSpine"));
        T.ok("F5 cycles inbox, narrated history and story facts",
                probe.contains("Keyboard.KEY_F5")
                        && probe.contains("switchInbox()")
                        && probe.contains("RECENT HISTORY")
                        && probe.contains("PAGE \" .. event.narrated")
                        && probe.contains("STORY FACTS")
                        && probe.contains("decodeDiagnostic(\"factMemory\")"));
        T.ok("inbox presents bounded factual summaries",
                probe.contains("event.summary")
                        && probe.contains("if #overlay.events >= 6 then break end")
                        && probe.contains("if #summary > 88 then"));

        T.group("Testing Mode - guided Test Lab");
        T.ok("Test Lab has configurable keys",
                probe.contains("Keyboard.KEY_F4")
                        && probe.contains("Keyboard.KEY_F3")
                        && probe.contains("runLabSuite()"));
        T.ok("upgraded key profiles retain physical defaults",
                probe.contains("key == Keyboard.KEY_F4")
                        && probe.contains("key == Keyboard.KEY_F3")
                        && probe.contains("labKey > 0")
                        && probe.contains("labRunKey > 0"));
        T.ok("unattended harness has debug-console entry points",
                probe.contains("PZStoryTestLabToggle = toggleLab")
                        && probe.contains("PZStoryTestLabRun = runLabSuite"));
        T.ok("debug saves automatically run read-only checks",
                probe.contains("if debugEnabled() then")
                        && probe.contains("runQuickChecks()"));
        T.ok("Test Lab is gated by game debug mode",
                probe.contains("isDebugEnabled() == true")
                        && probe.contains("DEBUG MODE REQUIRED"));
        T.ok("quick checks never call a provider",
                method(probe, "local function runQuickChecks()")
                        .contains("providerPreview")
                        && !method(probe, "local function runQuickChecks()")
                                .contains("requestPage")
                        && !method(probe, "local function runQuickChecks()")
                                .contains("selfTest"));
        T.ok("walkthrough judges the real journal",
                probe.contains("local function eventCount(kind)")
                        && probe.contains("sawAfter(\"door_opened\")")
                        && probe.contains("sawAfter(\"vehicle_entered\")")
                        && probe.contains("eventCount(\"kill\")"));
        T.ok("weapon fixture uses Build 42 debug primitives",
                probe.contains("AddItem(\"Base.Axe\")")
                        && probe.contains("addZombiesInOutfit")
                        && probe.contains("two zombies spawned"));
        T.ok("Test Lab reports bounded visible results",
                probe.contains("[PZStoryTestLab]")
                        && probe.contains("if #lab.results > 12")
                        && probe.contains("Events.OnPostUIDraw.Add(drawLab)"));
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
