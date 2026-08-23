package de.fricke.pzstory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streaming language-model client.
 *
 * THREADING IS THE WHOLE POINT. The request runs on a background thread and
 * never touches the game loop; deltas land in a buffer that Lua drains with
 * poll() once a frame. Blocking the render thread for 20-60 seconds would
 * freeze Project Zomboid solid, so nothing here is ever called synchronously
 * from Lua except poll(), start() and cancel(), all of which return instantly.
 *
 * One request in flight at a time - a page at a time is the whole interaction.
 */
public final class Llm {

    public enum Status { IDLE, CONNECTING, STREAMING, DONE, ERROR, CANCELLED }

    private static final ExecutorService POOL =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PZStory-llm");
                t.setDaemon(true);   // must never keep the game from exiting
                return t;
            });

    private static HttpClient http;

    // --- state shared with the game thread; guarded by LOCK -----------------
    private static final Object LOCK = new Object();
    private static Status status = Status.IDLE;
    private static final StringBuilder full = new StringBuilder();
    private static final StringBuilder pending = new StringBuilder();
    private static String error = null;
    /** One stable word for the KIND of failure, for the device to theme. */
    private static String failKind = null;
    /** Seconds the provider asked us to wait, or 0 if it did not say. */
    private static int retryAfter = 0;
    private static long startedAt = 0;
    private static long firstTokenAt = 0;
    private static int inputTokens = 0;
    private static int cacheRead = 0;
    private static int cacheWrite = 0;
    private static int outputTokens = 0;
    private static AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private Llm() {}

    private static HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return http;
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Fires a request. Returns immediately.
     *
     * @return null on success, or a reason the request could not be started.
     */
    public static String start(String system, String user) {
        return start(system, "", user, null);
    }

    public static String start(String system, String user, Runnable onDone) {
        return start(system, "", user, onDone);
    }

    /**
     * @param cached the stable prefix of the user turn - canon and every page
     *               written so far. A cache breakpoint is placed after it, so
     *               the charter plus the whole book is billed at the cache-read
     *               rate on every page after the first.
     * @param tail   the volatile part: the live state and the player's voice.
     *               Changes every call, so it must come last.
     * @param onDone run on the worker thread when the stream completes
     *               successfully. Never run on error or cancel, so a failed
     *               page cannot be committed to the campaign.
     */
    public static String start(String system, String cached, String tail, Runnable onDone) {
        final String user = tail;
        final String prefix = cached;
        synchronized (LOCK) {
            if (status == Status.CONNECTING || status == Status.STREAMING) {
                return "a page is already being written";
            }
            Config.Profile p = Config.active();
            if (p == null) return "no active profile - check profiles.json";
            if (!p.usable()) return "profile '" + p.name + "' is not usable: " + p.describe();

            full.setLength(0);
            pending.setLength(0);
            error = null;
            failKind = null;
            retryAfter = 0;
            inputTokens = 0;
            outputTokens = 0;
            cacheRead = 0;
            cacheWrite = 0;
            startedAt = System.currentTimeMillis();
            firstTokenAt = 0;
            cancelFlag = new AtomicBoolean(false);
            status = Status.CONNECTING;

            final AtomicBoolean myCancel = cancelFlag;
            POOL.submit(() -> {
                run(p, system, prefix, user, myCancel);
                boolean ok;
                synchronized (LOCK) { ok = (status == Status.DONE && full.length() > 0); }
                if (ok && onDone != null) {
                    try {
                        onDone.run();
                    } catch (Throwable t) {
                        Config.log("post-stream hook failed: " + t);
                    }
                }
            });
            return null;
        }
    }

    public static void cancel() {
        synchronized (LOCK) {
            cancelFlag.set(true);
            if (status == Status.CONNECTING || status == Status.STREAMING) {
                status = Status.CANCELLED;
            }
        }
    }

    /**
     * Drains whatever has arrived since the last call.
     * Cheap enough to call every frame.
     */
    public static String poll() {
        synchronized (LOCK) {
            String delta = pending.toString();
            pending.setLength(0);
            Json j = new Json().obj();
            j.put("status", status.name());
            j.put("delta", delta);
            j.put("chars", full.length());
            j.put("done", status == Status.DONE || status == Status.ERROR || status == Status.CANCELLED);
            if (error != null) j.put("error", error);
            if (failKind != null) j.put("failKind", failKind);
            if (retryAfter > 0) j.put("retryAfter", retryAfter);
            if (startedAt > 0) j.put("elapsedMs", System.currentTimeMillis() - startedAt);
            if (firstTokenAt > 0) j.put("firstTokenMs", firstTokenAt - startedAt);
            if (inputTokens > 0) j.put("inputTokens", inputTokens);
            if (cacheRead > 0) j.put("cacheRead", cacheRead);
            if (cacheWrite > 0) j.put("cacheWrite", cacheWrite);
            if (outputTokens > 0) j.put("outputTokens", outputTokens);
            return j.endObj().toString();
        }
    }

    public static String text() {
        synchronized (LOCK) { return full.toString(); }
    }

    // ----------------------------------------------------------- the request

    private static void run(Config.Profile p, String system, String prefix, String user,
                            AtomicBoolean cancelled) {
        try {
            String body;
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .timeout(Duration.ofMinutes(5))          // whole-stream ceiling
                    .header("content-type", "application/json")
                    .header("accept", "text/event-stream");

            switch (p.kind) {
                case "anthropic" -> {
                    body = anthropicBody(p, system, prefix, user);
                    rb.uri(URI.create("https://api.anthropic.com/v1/messages"))
                      .header("x-api-key", p.apiKey)
                      .header("anthropic-version", "2023-06-01");
                }
                case "gemini" -> {
                    body = geminiBody(p, system, prefix, user);
                    String base = (p.baseUrl == null || p.baseUrl.isBlank())
                            ? "https://generativelanguage.googleapis.com/v1beta"
                            : trimSlash(p.baseUrl);
                    rb.uri(URI.create(base + "/models/" + p.model
                            + ":streamGenerateContent?alt=sse"))
                      .header("x-goog-api-key", p.apiKey);
                }
                case "openai-compatible" -> {
                    body = openaiBody(p, system, prefix, user);
                    if (p.baseUrl == null || p.baseUrl.isBlank()) {
                        fail("this profile needs a baseUrl");
                        return;
                    }
                    // A key sent over plaintext HTTP is a key given away to
                    // anything on the network path. Loopback is the honest
                    // exception: that is Ollama or LM Studio on this machine,
                    // where there is no path to sniff.
                    String u = p.baseUrl.toLowerCase();
                    if (u.startsWith("http://") && !p.apiKey.isEmpty()
                            && !(u.contains("//localhost") || u.contains("//127.0.0.1")
                                 || u.contains("//[::1]"))) {
                        fail("refusing to send an API key over plain http to "
                                + "a remote host - use https:// in profiles.json");
                        return;
                    }
                    rb.uri(URI.create(trimSlash(p.baseUrl) + "/chat/completions"));
                    // Local servers usually want no key at all; sending an
                    // empty Authorization header upsets some of them.
                    if (p.apiKey != null && !p.apiKey.isBlank()) {
                        rb.header("authorization", "Bearer " + p.apiKey);
                    }
                }
                default -> {
                    fail("unknown provider kind '" + p.kind + "'");
                    return;
                }
            }

            HttpRequest req = rb
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            Config.log("request -> " + p.kind + "/" + p.model + " (" + body.length() + " bytes)");

            HttpResponse<InputStream> res =
                    client().send(req, HttpResponse.BodyHandlers.ofInputStream());

            if (res.statusCode() != 200) {
                String errBody = new String(res.body().readAllBytes(), StandardCharsets.UTF_8);
                int code = res.statusCode();
                failKind = kindOf(code, errBody);
                retryAfter = retryAfter(res, errBody);
                fail("HTTP " + code + " " + explain(code)
                        + " - " + Config.redact(errBody.length() > 600
                            ? errBody.substring(0, 600) + "..." : errBody));
                return;
            }

            synchronized (LOCK) {
                if (status == Status.CONNECTING) status = Status.STREAMING;
            }

            readSse(res.body(), cancelled, p.kind);

        } catch (java.net.ConnectException e) {
            failKind = "network";
            fail("cannot reach the provider - no network, or a firewall is blocking the game");
        } catch (java.net.UnknownHostException e) {
            failKind = "network";
            fail("cannot look up the provider's address - no network");
        } catch (java.net.http.HttpTimeoutException e) {
            failKind = "timeout";
            fail("timed out waiting for the model");
        } catch (Throwable t) {
            fail(t.getClass().getSimpleName() + ": " + Config.redact(String.valueOf(t.getMessage())));
        } finally {
            synchronized (LOCK) {
                if (status == Status.STREAMING || status == Status.CONNECTING) status = Status.DONE;
                long ms = System.currentTimeMillis() - startedAt;
                Config.log("stream finished: status=" + status
                        + " chars=" + full.length()
                        + " in=" + inputTokens + " cacheRead=" + cacheRead
                        + " cacheWrite=" + cacheWrite + " out=" + outputTokens
                        + " ttft=" + (firstTokenAt > 0 ? (firstTokenAt - startedAt) : -1) + "ms"
                        + " total=" + ms + "ms");
            }
        }
    }

    /**
     * Reads the SSE stream.
     *
     * Anthropic's event order is message_start, then per content block
     * content_block_start / content_block_delta* / content_block_stop, then
     * message_delta (carrying stop_reason and output token count) and finally
     * message_stop. `ping` events appear anywhere and are ignored.
     *
     * We deliberately parse the `data:` payload only and ignore the `event:`
     * line - the payload carries its own "type", so relying on one source
     * rather than two removes a whole class of desync bug.
     */
    private static void readSse(InputStream in, AtomicBoolean cancelled, String kind)
            throws Exception {
        if (!"anthropic".equals(kind)) {
            readSseOther(in, cancelled, kind);
            return;
        }
        readSseAnthropic(in, cancelled);
    }

    /**
     * Gemini and OpenAI-compatible streams.
     *
     * Both are line-oriented SSE with a JSON payload per `data:` line; only the
     * path to the text differs. Gemini puts it at
     * candidates[0].content.parts[0].text, OpenAI at choices[0].delta.content.
     * OpenAI also sends a literal [DONE] sentinel, which Gemini does not.
     */
    private static void readSseOther(InputStream in, AtomicBoolean cancelled, String kind)
            throws Exception {
        boolean gemini = "gemini".equals(kind);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (cancelled.get()) { Config.log("stream cancelled by player"); return; }
                if (line.isEmpty() || !line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

                Map<String, Object> ev;
                try {
                    ev = JsonParse.parseObject(payload);
                } catch (Throwable t) {
                    Config.log("skipped unparseable SSE payload (" + payload.length() + " bytes)");
                    continue;
                }

                Map<String, Object> err = JsonParse.map(ev, "error");
                if (err != null) {
                    failKind = kindOf(0, payload);
                    fail("stream error: " + JsonParse.str(err, "message", payload));
                    return;
                }

                if (gemini) {
                    Object cands = ev.get("candidates");
                    if (cands instanceof java.util.List<?> l && !l.isEmpty()
                            && l.get(0) instanceof Map<?, ?> c0) {
                        Map<String, Object> content = JsonParse.map(c0, "content");
                        Object parts = content == null ? null : content.get("parts");
                        if (parts instanceof java.util.List<?> pl) {
                            for (Object pobj : pl) {
                                if (pobj instanceof Map<?, ?> pm) {
                                    String t = JsonParse.str(pm, "text", "");
                                    if (!t.isEmpty()) append(t);
                                }
                            }
                        }
                    }
                    Map<String, Object> um = JsonParse.map(ev, "usageMetadata");
                    if (um != null) {
                        synchronized (LOCK) {
                            inputTokens  = JsonParse.num(um, "promptTokenCount", inputTokens);
                            outputTokens = JsonParse.num(um, "candidatesTokenCount", outputTokens);
                            cacheRead    = JsonParse.num(um, "cachedContentTokenCount", cacheRead);
                        }
                    }
                } else {
                    Object ch = ev.get("choices");
                    if (ch instanceof java.util.List<?> l && !l.isEmpty()
                            && l.get(0) instanceof Map<?, ?> c0) {
                        Map<String, Object> d = JsonParse.map(c0, "delta");
                        if (d != null) {
                            String t = JsonParse.str(d, "content", "");
                            if (!t.isEmpty()) append(t);
                        }
                    }
                    Map<String, Object> u = JsonParse.map(ev, "usage");
                    if (u != null) {
                        synchronized (LOCK) {
                            inputTokens  = JsonParse.num(u, "prompt_tokens", inputTokens);
                            outputTokens = JsonParse.num(u, "completion_tokens", outputTokens);
                        }
                    }
                }
            }
        }
    }

    private static void readSseAnthropic(InputStream in, AtomicBoolean cancelled) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (cancelled.get()) {
                    Config.log("stream cancelled by player");
                    return;
                }
                if (line.isEmpty() || !line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

                Map<String, Object> ev;
                try {
                    ev = JsonParse.parseObject(payload);
                } catch (Throwable t) {
                    Config.log("skipped unparseable SSE payload (" + payload.length() + " bytes)");
                    continue;
                }

                String type = JsonParse.str(ev, "type", "");
                switch (type) {
                    case "content_block_delta" -> {
                        Map<String, Object> d = JsonParse.map(ev, "delta");
                        // text_delta only. thinking_delta and input_json_delta
                        // are not page text and must not reach the paper.
                        if (d != null && "text_delta".equals(JsonParse.str(d, "type", ""))) {
                            String t = JsonParse.str(d, "text", "");
                            if (!t.isEmpty()) append(t);
                        }
                    }
                    case "message_start" -> {
                        Map<String, Object> m = JsonParse.map(ev, "message");
                        Map<String, Object> u = m == null ? null : JsonParse.map(m, "usage");
                        if (u != null) {
                            synchronized (LOCK) {
                                inputTokens = JsonParse.num(u, "input_tokens", 0);
                                cacheRead   = JsonParse.num(u, "cache_read_input_tokens", 0);
                                cacheWrite  = JsonParse.num(u, "cache_creation_input_tokens", 0);
                            }
                        }
                    }
                    case "message_delta" -> {
                        Map<String, Object> u = JsonParse.map(ev, "usage");
                        if (u != null) {
                            synchronized (LOCK) { outputTokens = JsonParse.num(u, "output_tokens", 0); }
                        }
                        // If the page hit the ceiling it was cut off, and the
                        // canon block will be missing. Say so rather than
                        // leaving a truncated page looking deliberate.
                        Map<String, Object> d2 = JsonParse.map(ev, "delta");
                        String stop = d2 == null ? "" : JsonParse.str(d2, "stop_reason", "");
                        if ("max_tokens".equals(stop)) {
                            Config.log("WARNING page hit the token ceiling and was cut short");
                        }
                    }
                    case "error" -> {
                        Map<String, Object> e = JsonParse.map(ev, "error");
                        failKind = kindOf(0, payload);
                        fail("stream error: " + (e == null ? payload : JsonParse.str(e, "message", payload)));
                        return;
                    }
                    default -> { /* ping, message_stop, content_block_start/stop */ }
                }
            }
        }
    }

    private static void append(String t) {
        synchronized (LOCK) {
            if (firstTokenAt == 0) firstTokenAt = System.currentTimeMillis();
            full.append(t);
            pending.append(t);
        }
    }

    private static void fail(String msg) {
        synchronized (LOCK) {
            error = msg;
            status = Status.ERROR;
        }
        Config.log("ERROR " + msg);
    }

    /**
     * What KIND of failure this was, as one stable word.
     *
     * The device shows the player a themed page rather than an HTTP status, so
     * it needs a category it can switch on. The raw message still goes to the
     * log for us; the player gets a sentence they can act on.
     */
    private static String kindOf(int code, String body) {
        String b = body == null ? "" : body.toLowerCase();
        if (code == 429) return "rate";
        if (code == 401 || code == 403) return "auth";
        if (code == 404) return "model";
        if (code == 529 || code == 503 || code == 502) return "overload";
        if (code == 400) {
            // Anthropic reports an empty balance as a 400, not a 402.
            if (b.contains("credit") || b.contains("balance")
                    || b.contains("quota") || b.contains("billing")) return "credit";
            return "request";
        }
        if (b.contains("resource_exhausted") || b.contains("rate limit")
                || b.contains("quota")) return "rate";
        if (code >= 500) return "overload";
        return "unknown";
    }

    /**
     * How long to wait, in seconds, when the provider tells us.
     *
     * Three places carry it and they all disagree: the standard Retry-After
     * header, Gemini's RetryInfo block ("retryDelay": "26s") inside the error
     * JSON, and nothing at all. Zero means "we were not told".
     */
    private static int retryAfter(HttpResponse<?> res, String body) {
        try {
            var h = res.headers().firstValue("retry-after");
            if (h.isPresent()) {
                int n = Integer.parseInt(h.get().trim());
                if (n > 0) return Math.min(n, 3600);
            }
        } catch (Throwable ignored) { }
        try {
            if (body != null) {
                var m = java.util.regex.Pattern
                        .compile("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"")
                        .matcher(body);
                if (m.find()) return Math.min(Integer.parseInt(m.group(1)), 3600);
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    /** Turns a status code into something a player can act on. */
    private static String explain(int code) {
        return switch (code) {
            case 400 -> "(bad request - often 'credit balance too low')";
            case 401 -> "(bad or revoked API key)";
            case 403 -> "(key not permitted for this model)";
            case 404 -> "(unknown model id)";
            case 429 -> "(rate limited - wait a moment)";
            case 500, 502, 503 -> "(provider error)";
            case 529 -> "(provider overloaded - retry)";
            default -> "";
        };
    }

    /**
     * The output ceiling, scaled to the chosen page length.
     *
     * A fixed 520 truncated a page mid-sentence at "The yard". Truncation is
     * strictly worse than a page running long: a long page is merely wordy,
     * a cut one is broken AND loses its canon block. So the ceiling is a
     * backstop against runaway generation, not the thing that sets length -
     * the instruction in the prompt does that. Roughly 1.4 tokens per word,
     * doubled for slack, plus room for the title and canon.
     */
    private static int ceiling() {
        int words = Settings.words();
        return Math.max(1500, (int) (words * 2.6) + 160);
    }

    /**
     * Extra room for models that THINK before they write.
     *
     * Claude Sonnet 5 reasons first, and those tokens come out of max_tokens.
     * A page came back with out=680 against a cap of 680 and only 223
     * characters of prose: reasoning had eaten the whole allowance and the
     * page was guillotined mid-paragraph, losing its canon block with it.
     *
     * max_tokens is a CAP, not a reservation - an unused ceiling costs nothing
     * - so being frugal here buys us precisely nothing and risks the one
     * failure we already know is unrecoverable.
     */
    private static final int THINKING_HEADROOM = 8000;

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Combines the two user parts. Only Anthropic gets a cache breakpoint
     * between them; for the others the split has no meaning, so they are
     * simply concatenated in the same order.
     */
    private static String joined(String prefix, String user) {
        if (prefix == null || prefix.isBlank()) return user;
        return prefix + "\n" + user;
    }

    /**
     * Some system prompts are honoured weakly - notably by local chat
     * templates, which is why the profile carries a systemMode.
     */
    private static String withSystem(Config.Profile p, String system, String body) {
        if ("prepend_to_user".equals(p.systemMode) || "both".equals(p.systemMode)) {
            return system + "\n\n" + body;
        }
        return body;
    }

    private static boolean nativeSystem(Config.Profile p) {
        return !"prepend_to_user".equals(p.systemMode);
    }

    private static String geminiBody(Config.Profile p, String system, String prefix, String user) {
        Json j = new Json().obj();

        if (nativeSystem(p)) {
            j.objKey("systemInstruction");
            j.arrKey("parts");
            j.obj(); j.put("text", system); j.endObj();
            j.endArr();
            j.endObj();
        }

        j.arrKey("contents");
        j.obj();
        j.put("role", "user");
        j.arrKey("parts");
        j.obj(); j.put("text", withSystem(p, system, joined(prefix, user))); j.endObj();
        j.endArr();
        j.endObj();
        j.endArr();

        j.objKey("generationConfig");
        j.put("maxOutputTokens", Math.min(p.maxTokens, ceiling()));
        // Thinking models spend the output budget on reasoning before they
        // write a word. With a 520-token ceiling that can return an EMPTY
        // page, which looks like a broken adapter rather than a setting.
        j.objKey("thinkingConfig");
        j.put("thinkingBudget", 0);
        j.endObj();
        j.endObj();

        return j.endObj().toString();
    }

    private static String openaiBody(Config.Profile p, String system, String prefix, String user) {
        Json j = new Json().obj();
        j.put("model", p.model);
        j.put("stream", true);
        j.put("max_tokens", Math.min(p.maxTokens, ceiling()));

        j.arrKey("messages");
        if (nativeSystem(p)) {
            j.obj();
            j.put("role", "system");
            j.put("content", system);
            j.endObj();
        }
        j.obj();
        j.put("role", "user");
        j.put("content", withSystem(p, system, joined(prefix, user)));
        j.endObj();
        j.endArr();

        // Ask for usage on the final chunk. Servers that do not know this
        // option ignore it, which is exactly what the compatibility layer
        // promises to do with anything it does not recognise.
        j.objKey("stream_options");
        j.put("include_usage", true);
        j.endObj();

        return j.endObj().toString();
    }

    /**
     * Builds the request with a cache breakpoint after the stable prefix.
     *
     * Order matters and is the whole trick: charter (system), then canon and
     * every page written so far, then the live state. The first two never
     * change between pages and the third always does, so one breakpoint at the
     * end of the history means the entire book is billed at the cache-read
     * rate from the second page onward. Put the state first and nothing would
     * ever cache.
     *
     * The history is append-only, so each request's cached prefix is a literal
     * prefix of the next one - which is exactly the shape the cache wants.
     */
    private static String anthropicBody(Config.Profile p, String system, String prefix, String user) {
        boolean cache = !"off".equals(p.cacheTtl);
        String ttl = "1h".equals(p.cacheTtl) ? "1h" : null;

        // The profile's maxTokens is the player's idea of how long a PAGE may
        // run. It is not a reasoning budget, and applying it as one is what
        // truncated the first Sonnet page.
        int cap = Math.min(p.maxTokens, ceiling()) + THINKING_HEADROOM;

        Json j = new Json().obj();
        j.put("model", p.model);
        j.put("max_tokens", cap);
        j.put("stream", true);

        if (system != null && !system.isEmpty()) {
            // THE breakpoint. The system block is the charter, the world and
            // this campaign's fixed spine - identical on every page, so it hits
            // every time. The earlier design put it after the history, which
            // GROWS in the middle (canon, rooms seen, pages), so the prefix
            // diverged on every request: cacheWrite every page, cacheRead never.
            j.arrKey("system");
            j.obj();
            j.put("type", "text");
            j.put("text", system);
            if (cache) {
                j.objKey("cache_control");
                j.put("type", "ephemeral");
                if (ttl != null) j.put("ttl", ttl);
                j.endObj();
            }
            j.endObj();
            j.endArr();
        }

        j.arrKey("messages");
        j.obj();
        j.put("role", "user");
        j.arrKey("content");

        if (prefix != null && !prefix.isBlank()) {
            // No breakpoint here: this half grows every page and would only
            // ever be written, never read.
            j.obj();
            j.put("type", "text");
            j.put("text", prefix);
            j.endObj();
        }

        j.obj();
        j.put("type", "text");
        j.put("text", user);
        j.endObj();

        j.endArr();     // content
        j.endObj();     // message
        j.endArr();     // messages
        return j.endObj().toString();
    }
}
