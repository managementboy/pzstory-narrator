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
        T.ok("validated planner output is buffered and replaced",
                llm.contains("startBuffered(")
                        && llm.contains("result.replacement")
                        && llm.contains("result == null && req.bufferedOutput")
                        && api.contains("ValidatedNarrator.prepare(")
                        && api.contains("CompletionResult.success(rendered)"));
        T.ok("temporary live trace covers safe planner and controlled page",
                api.contains("LiveTrace.request(session.systemPrompt()")
                        && api.contains("LiveTrace.reply(plannerReply)")
                        && api.contains("CONTROLLED RENDERED PAGE"));
        T.ok("narrator modes use isolated LM Studio checkpoints",
                llm.contains("SCOPE_CLASSIC")
                        && llm.contains("SCOPE_SAFE")
                        && api.contains("startBufferedScoped(")
                        && api.contains("startScoped("));
        String history = read("src/de/fricke/pzstory/NarratorHistory.java");
        T.ok("story selection starts a hidden pre-page history seed",
                api.contains("NarratorHistory.ensureSeeded(")
                        && api.contains("historySeedStatus()")
                        && lua.contains("Knox history")
                        && history.contains("HISTORY_READY_V2"));
        T.ok("KnoxOS reports real boot telemetry without draining the stream",
                api.contains("knoxOsStatus()")
                        && llm.contains("statusJson(false)")
                        && lua.contains("renderKnoxOS")
                        && lua.contains("inputTokens")
                        && lua.contains("No input required")
                        && !lua.contains("BEGINNING"));
        T.ok("KnoxOS can correct provider and model before generation",
                lua.contains("or { \"SETUP\" }")
                        && lua.contains("if self.mode == \"setup\" then return end"));
        T.ok("stateless providers do not deadlock on history not needed",
                lua.contains("state == \"ready\" or state == \"not_needed\""));
        T.ok("failed history seed waits for an explicit KnoxOS retry",
                api.contains("retryHistorySeed()")
                        && history.contains("failedKey.equals(key)")
                        && history.contains("Llm.failedInScope(safeScope)")
                        && lua.contains("api(\"retryHistorySeed\")")
                        && lua.contains("choose RETRY or SETUP"));
        T.ok("SETUP selects every downloaded LM Studio LLM",
                api.contains("lmStudioModels()")
                        && api.contains("nextLmStudioModel()")
                        && lua.contains("local model")
                        && lua.contains("nextLmStudioModel")
                        && read("src/de/fricke/pzstory/LmStudioCatalog.java")
                                .contains("/api/v1/models"));
        T.ok("history seed is provider state, never a story page",
                read("src/de/fricke/pzstory/Campaign.java")
                        .contains("commitProviderSeed(")
                        && !method(history, "public static SeedStatus ensureSeeded")
                                .contains("commitGeneratedPage"));
        T.ok("first visible request waits for the hidden seed",
                method(api, "public static String requestStoryPage")
                        .contains("SeedStatus.SEEDING"));
        T.ok("fresh game opens, pauses and begins page one automatically",
                lua.contains("autoOpenPending = true")
                        && lua.contains("instance:open(true)")
                        && lua.contains("advanceAutomaticOpening()")
                        && lua.contains("self:writePage(\"\")"));
        T.ok("conversation workflow uses grounded Qwen planning",
                lua.contains("narrator(\"validated\")")
                        && !lua.contains("narrator(\"classic\")")
                        && api.contains("Campaign.repetitionGuidance(), Campaign.pageCount() + 1"));
        T.ok("later pages require a one-shot player note",
                api.contains("Tell the narrator what matters before continuing.")
                        && lua.contains("YOUR NOTE - required for the next page")
                        && lua.contains("tell the narrator what matters next")
                        && lua.contains("self.pendingPlayerNote")
                        && lua.contains("self.entry:setText(\"\")"));
        T.ok("classic mode has one corrective turn",
                api.contains("CompletionResult.retry(")
                        && llm.contains("req.repairCount == 0")
                        && lua.contains("CORRECTING PAGE")
                        && lua.contains("data.repairing == true"));
        T.ok("experimental narrator architecture remains behind the bridge",
                api.contains("setNarratorMode(String mode)")
                        && lua.contains("data.buffered == true"));
        T.ok("safe-mode notes cross the controlled planner boundary",
                api.contains("safe experimental narrator currently supports chronicler")
                        && api.contains("Campaign.PromptNotes capturedNotes")
                        && !api.contains("cannot consume notebook directions yet"));

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
        T.ok("console control cycles inbox, narrated history and story facts",
                probe.contains("PZStoryProbeSwitchInbox = switchInbox")
                        && probe.contains("RECENT HISTORY")
                        && probe.contains("PAGE \" .. event.narrated")
                        && probe.contains("STORY FACTS")
                        && probe.contains("decodeDiagnostic(\"factMemory\")"));
        T.ok("inbox presents bounded factual summaries",
                probe.contains("event.summary")
                        && probe.contains("if #overlay.events >= 6 then break end")
                        && probe.contains("if #summary > 88 then"));

        T.group("Testing Mode - guided Test Lab");
        T.ok("Test Lab has visible buttons and console controls",
                probe.contains("ensureLabPanel()")
                        && probe.contains("PZStoryTestLabToggle = toggleLab")
                        && probe.contains("PZStoryTestLabRun = runLabSuite"));
        T.ok("Test Lab does not steal physical function keys",
                !probe.contains("key == Keyboard.KEY_F4")
                        && !probe.contains("key == Keyboard.KEY_F3"));
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
            return Files.readString(Path.of(path), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
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
