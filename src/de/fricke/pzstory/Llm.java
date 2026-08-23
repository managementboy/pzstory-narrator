package de.fricke.pzstory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
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

    /** Provider-controlled input limits. These are hard safety ceilings. */
    private static final int MAX_ERROR_BYTES  = 64 * 1024;
    private static final int MAX_SSE_BYTES    = 2 * 1024 * 1024;
    private static final int MAX_SSE_LINE     = 256 * 1024;
    private static final int MAX_OUTPUT_CHARS = 128 * 1024;

    public enum Status { IDLE, CONNECTING, STREAMING, DONE, ERROR, CANCELLED }

    private static final ExecutorService POOL =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PZStory-llm");
                t.setDaemon(true);   // must never keep the game from exiting
                return t;
            });

    private static HttpClient http;

    /**
     * One request, and everything belonging to it.
     *
     * THIS USED TO BE FIFTEEN STATIC FIELDS, and that was a correctness bug,
     * not a style one. The failure needed no unusual timing:
     *
     *   1. request A is streaming
     *   2. A is cancelled - the global status becomes CANCELLED
     *   3. B starts, clears the shared buffers, installs a new cancel flag
     *      and sets CONNECTING
     *   4. A's worker finally exits, sees CONNECTING, and sets it to DONE
     *   5. late bytes from A land in B's buffer; A's callback commits a page
     *      built from A's text against B's campaign
     *
     * Every mutation now names the request it belongs to and is dropped
     * unless that request is still the active one, so a dying worker cannot
     * touch its successor's state no matter when it wakes up.
     */
    private static final class Req {
        final long id;
        final Status[] status = { Status.CONNECTING };
        final StringBuilder full = new StringBuilder();
        final StringBuilder pending = new StringBuilder();
        String error;
        String failKind;
        int retryAfter;
        long startedAt = System.currentTimeMillis();
        long firstTokenAt;
        int inputTokens, cacheRead, cacheWrite, outputTokens;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** Set once a terminal condition is genuinely observed. See P1. */
        boolean sawTerminal;
        /** The campaign this request was started for. See Campaign.generation(). */
        final long generation;
        /** Live response body, so cancel() can shut it and free the worker. */
        volatile java.io.Closeable body;
        /** Guards run-at-most-once for the success callback. */
        final AtomicBoolean callbackRan = new AtomicBoolean(false);

        Req(long id, long generation) { this.id = id; this.generation = generation; }

        boolean isTerminal() {
            Status st = status[0];
            return st == Status.DONE || st == Status.ERROR || st == Status.CANCELLED;
        }
    }

    private static final Object LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** The request the device is currently showing. Null before the first one. */
    private static Req active;

    /**
     * True while a worker thread is still running, even after cancellation.
     *
     * Cancellation is a REQUEST, not an event: the worker may be blocked in a
     * socket read for some time afterwards. Treating the slot as free the
     * moment cancel() returned is exactly what let A and B overlap, so a new
     * request is refused until the previous worker has actually exited.
     */
    private static boolean workerBusy = false;

    private Llm() {}

    private static HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    // NEVER, not NORMAL. A redirect is an instruction from
                    // the far end to send the same request - including the
                    // x-api-key header and the whole game state - somewhere
                    // else, and HttpClient would follow it across origins
                    // without re-running any endpoint policy.
                    .followRedirects(HttpClient.Redirect.NEVER)
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

    public static String start(String system, String user,
                               java.util.function.BiConsumer<Long, String> onDone) {
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
     *               successfully, receiving the campaign generation captured
     *               for the request and the completed text DIRECTLY.
     *               Never run on error, cancellation, truncation, or after
     *               the campaign it was started for has been replaced. It is
     *               handed the text rather than calling Llm.text() so that a
     *               later request cannot substitute its own output underneath
     *               a callback that is already running.
     */
    public static String start(String system, String cached, String tail,
                               java.util.function.BiConsumer<Long, String> onDone) {
        final String user = tail;
        final String prefix = cached;
        final Req req;
        final Config.Profile p;
        synchronized (LOCK) {
            // Not "is the status terminal" - a cancelled request whose worker
            // is still unwinding must still hold the slot, or its dying gasp
            // lands in the next request's buffer.
            if (workerBusy) {
                return active != null && active.status[0] == Status.CANCELLED
                        ? "still stopping the last page - try again in a moment"
                        : "a page is already being written";
            }
            p = Config.active();
            if (p == null) return "no active profile - check profiles.json";
            if (!p.usable()) return "profile '" + p.name + "' is not usable: " + p.describe();

            req = new Req(SEQ.incrementAndGet(), Campaign.generation());
            active = req;
            workerBusy = true;
        }

        POOL.submit(() -> {
            try {
                run(req, p, system, prefix, user);
                // Read the outcome under the lock, then decide outside it.
                final boolean ok;
                final String text;
                synchronized (LOCK) {
                    ok = active == req
                            && req.status[0] == Status.DONE
                            && req.full.length() > 0;
                    text = req.full.toString();
                }
                if (!ok || onDone == null) return;

                // This early check avoids parsing a completed response after a
                // save change. It is deliberately NOT the correctness barrier:
                // Campaign.commitGeneratedPage() repeats the check while
                // holding Campaign's monitor, so reset() cannot fit between
                // the check and the mutations.
                if (req.generation != Campaign.generation()) {
                    Config.log("dropping a finished page: the save changed while it"
                            + " was being written (gen " + req.generation
                            + " -> " + Campaign.generation() + ")");
                    return;
                }
                if (!req.callbackRan.compareAndSet(false, true)) return; // at most once
                try {
                    onDone.accept(req.generation, text);
                } catch (Throwable t) {
                    Config.log("post-stream hook failed: " + t);
                }
            } finally {
                // The request slot includes its success commit. Releasing it
                // before the callback let a successor snapshot a half-written
                // campaign (page present, canon and state not yet present).
                synchronized (LOCK) { workerBusy = false; }
            }
        });
        return null;
    }

    /**
     * Asks the active request to stop.
     *
     * Returns immediately - the game thread must never wait on a socket. The
     * worker notices the flag at its next loop, and closing the response body
     * unblocks it out of a read that could otherwise sit on the single-thread
     * executor until the five-minute timeout.
     */
    public static void cancel() {
        Req r;
        synchronized (LOCK) {
            r = active;
            if (r == null || r.isTerminal()) return;
            r.cancelled.set(true);
            r.status[0] = Status.CANCELLED;
        }
        java.io.Closeable b = r.body;
        if (b != null) {
            try { b.close(); } catch (Throwable ignored) { }
        }
    }

    /** Invalidates any in-flight request. Called when a different save loads. */
    public static void invalidateForSaveChange() {
        cancel();
    }

    /**
     * Drains whatever has arrived since the last call.
     * Cheap enough to call every frame.
     */
    public static String poll() {
        synchronized (LOCK) {
            Json j = new Json().obj();
            Req r = active;
            if (r == null) {
                j.put("status", Status.IDLE.name());
                j.put("delta", "");
                j.put("chars", 0);
                j.put("done", true);
                return j.endObj().toString();
            }
            String delta = r.pending.toString();
            r.pending.setLength(0);
            j.put("status", r.status[0].name());
            j.put("delta", delta);
            j.put("chars", r.full.length());
            j.put("done", r.isTerminal());
            if (r.error != null) j.put("error", r.error);
            if (r.failKind != null) j.put("failKind", r.failKind);
            if (r.retryAfter > 0) j.put("retryAfter", r.retryAfter);
            if (r.startedAt > 0) j.put("elapsedMs", System.currentTimeMillis() - r.startedAt);
            if (r.firstTokenAt > 0) j.put("firstTokenMs", r.firstTokenAt - r.startedAt);
            if (r.inputTokens > 0) j.put("req.inputTokens", r.inputTokens);
            if (r.cacheRead > 0) j.put("req.cacheRead", r.cacheRead);
            if (r.cacheWrite > 0) j.put("req.cacheWrite", r.cacheWrite);
            if (r.outputTokens > 0) j.put("req.outputTokens", r.outputTokens);
            return j.endObj().toString();
        }
    }

    public static String text() {
        synchronized (LOCK) { return active == null ? "" : active.full.toString(); }
    }

    /** The current failure reason, or "". Typed accessor for the Lua bridge. */
    public static String error() {
        synchronized (LOCK) {
            return (active == null || active.error == null) ? "" : active.error;
        }
    }

    /** One stable word for the kind of failure, or "". */
    public static String failKind() {
        synchronized (LOCK) {
            return (active == null || active.failKind == null) ? "" : active.failKind;
        }
    }

    /** Seconds the provider asked us to wait, or 0. */
    public static int retryAfterSeconds() {
        synchronized (LOCK) { return active == null ? 0 : active.retryAfter; }
    }

    // ----------------------------------------------------------- the request

    private static void run(Req req, Config.Profile p, String system,
                            String prefix, String user) {
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
                    // A custom Gemini baseUrl used to bypass every check the
                    // OpenAI adapter had. It gets the identical policy now.
                    String base = (p.baseUrl == null || p.baseUrl.isBlank())
                            ? "https://generativelanguage.googleapis.com/v1beta"
                            : Endpoint.requireAllowed(p.baseUrl);
                    // The model id comes out of profiles.json and lands in the
                    // path. Unencoded, "x?key=..." or "../../y" would rewrite
                    // the endpoint; encodeSegment makes it inert.
                    rb.uri(URI.create(base + "/models/"
                            + Endpoint.encodeSegment(p.model)
                            + ":streamGenerateContent?alt=sse"))
                      .header("x-goog-api-key", p.apiKey);
                }
                case "openai-compatible" -> {
                    body = openaiBody(p, system, prefix, user);
                    // Note the policy is applied whether or not a key is set:
                    // the BODY is private game state - the survivor, their
                    // position, the player's notes, the whole campaign - and
                    // that must not cross a network in clear text even when
                    // there is no credential to steal.
                    rb.uri(URI.create(Endpoint.requireAllowed(p.baseUrl) + "/chat/completions"));
                    // Local servers usually want no key at all; sending an
                    // empty Authorization header upsets some of them.
                    if (p.apiKey != null && !p.apiKey.isBlank()) {
                        rb.header("authorization", "Bearer " + p.apiKey);
                    }
                }
                default -> {
                    fail(req, "unknown provider kind '" + p.kind + "'");
                    return;
                }
            }

            HttpRequest httpReq = rb
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            Config.log("request -> " + p.kind + "/" + p.model + " (" + body.length() + " bytes)");

            HttpResponse<InputStream> res =
                    client().send(httpReq, HttpResponse.BodyHandlers.ofInputStream());

            if (res.statusCode() != 200) {
                String errBody;
                try (InputStream errorStream = res.body()) {
                    errBody = readUtf8Bounded(errorStream, MAX_ERROR_BYTES);
                }
                int code = res.statusCode();
                req.failKind = kindOf(code, errBody);
                req.retryAfter = retryAfter(res, errBody);
                fail(req, "HTTP " + code + " " + explain(code)
                        + " - " + Config.redact(errBody.length() > 600
                            ? errBody.substring(0, 600) + "..." : errBody));
                return;
            }

            synchronized (LOCK) {
                if (req.status[0] == Status.CONNECTING) req.status[0] = Status.STREAMING;
            }

            req.body = res.body();
            readSse(req, res.body(), p.kind);

        } catch (Endpoint.Rejected e) {
            req.failKind = "endpoint";
            fail(req, e.getMessage());
        } catch (java.net.ConnectException e) {
            req.failKind = "network";
            fail(req, "cannot reach the provider - no network, or a firewall is blocking the game");
        } catch (java.net.UnknownHostException e) {
            req.failKind = "network";
            fail(req, "cannot look up the provider's address - no network");
        } catch (java.net.http.HttpTimeoutException e) {
            req.failKind = "timeout";
            fail(req, "timed out waiting for the model");
        } catch (ResponseTooLarge e) {
            req.failKind = "too_large";
            fail(req, e.getMessage());
        } catch (Throwable t) {
            fail(req, t.getClass().getSimpleName() + ": " + Config.redact(String.valueOf(t.getMessage())));
        } finally {
            synchronized (LOCK) {
                finish(req);
                long ms = System.currentTimeMillis() - req.startedAt;
                Config.log("stream finished: status=" + req.status[0]
                        + " chars=" + req.full.length()
                        + " in=" + req.inputTokens + " req.cacheRead=" + req.cacheRead
                        + " req.cacheWrite=" + req.cacheWrite + " out=" + req.outputTokens
                        + " ttft=" + (req.firstTokenAt > 0 ? (req.firstTokenAt - req.startedAt) : -1) + "ms"
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
    private static void readSse(Req req, InputStream in, String kind)
            throws Exception {
        if (!"anthropic".equals(kind)) {
            readSseOther(req, in, kind);
            return;
        }
        readSseAnthropic(req, in);
    }

    /**
     * Gemini and OpenAI-compatible streams.
     *
     * Both are line-oriented SSE with a JSON payload per `data:` line; only the
     * path to the text differs. Gemini puts it at
     * candidates[0].content.parts[0].text, OpenAI at choices[0].delta.content.
     * OpenAI also sends a literal [DONE] sentinel, which Gemini does not.
     */
    private static void readSseOther(Req req, InputStream in, String kind)
            throws Exception {
        boolean gemini = "gemini".equals(kind);
        int badEvents = 0;
        try (InputStream limited = new LimitedInputStream(in, MAX_SSE_BYTES,
                     "the provider stream exceeded " + MAX_SSE_BYTES + " bytes");
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(limited, StandardCharsets.UTF_8))) {
            String line;
            while ((line = readLineBounded(r, MAX_SSE_LINE)) != null) {
                if (req.cancelled.get()) { Config.log("stream cancelled by player"); return; }
                if (line.isEmpty() || !line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) continue;
                // "[DONE]" is the OpenAI protocol's end-of-stream sentinel and
                // the only thing that makes an OpenAI-compatible stream
                // complete. It was being skipped, so reaching it and reaching
                // a dropped socket looked identical.
                if ("[DONE]".equals(payload)) { req.sawTerminal = true; continue; }

                Map<String, Object> ev;
                try {
                    ev = JsonParse.parseObject(payload);
                } catch (Throwable t) {
                    // Do not skip malformed data indefinitely and then call the
                    // result a success: a stream that is mostly garbage has not
                    // delivered a page.
                    if (++badEvents > MAX_BAD_EVENTS) {
                        req.failKind = "protocol";
                        fail(req, "the provider sent " + badEvents + " unreadable events; "
                                + "giving up on this page");
                        return;
                    }
                    Config.log("skipped unparseable SSE payload (" + payload.length() + " bytes)");
                    continue;
                }

                Map<String, Object> err = JsonParse.map(ev, "error");
                if (err != null) {
                    req.failKind = kindOf(0, payload);
                    fail(req, "stream error: " + JsonParse.str(err, "message", payload));
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
                                    if (!t.isEmpty()) append(req, t);
                                }
                            }
                        }
                        // Gemini signals completion per candidate. STOP is the
                        // only success; everything else - MAX_TOKENS, SAFETY,
                        // RECITATION - means the page is not whole.
                        String fr = JsonParse.str(c0, "finishReason", "");
                        if (!fr.isEmpty()) {
                            if ("STOP".equals(fr)) {
                                req.sawTerminal = true;
                            } else if ("MAX_TOKENS".equals(fr)) {
                                req.failKind = "truncated";
                                fail(req, "the model hit its output ceiling and the "
                                        + "page was cut off, so nothing was saved. "
                                        + "Lower the page length in SETUP, or raise "
                                        + "maxTokens for this profile.");
                                return;
                            } else {
                                req.failKind = "refused";
                                fail(req, "the provider stopped this page early ("
                                        + safeWord(fr) + ") and it was not saved");
                                return;
                            }
                        }
                    }
                    Map<String, Object> um = JsonParse.map(ev, "usageMetadata");
                    if (um != null) {
                        synchronized (LOCK) {
                            req.inputTokens  = JsonParse.num(um, "promptTokenCount", req.inputTokens);
                            req.outputTokens = JsonParse.num(um, "candidatesTokenCount", req.outputTokens);
                            req.cacheRead    = JsonParse.num(um, "cachedContentTokenCount", req.cacheRead);
                        }
                    }
                } else {
                    Object ch = ev.get("choices");
                    if (ch instanceof java.util.List<?> l && !l.isEmpty()
                            && l.get(0) instanceof Map<?, ?> c0) {
                        Map<String, Object> d = JsonParse.map(c0, "delta");
                        if (d != null) {
                            String t = JsonParse.str(d, "content", "");
                            if (!t.isEmpty()) append(req, t);
                        }
                        String fr = JsonParse.str(c0, "finish_reason", "");
                        if (!fr.isEmpty()) {
                            if ("stop".equals(fr)) {
                                req.sawTerminal = true;
                            } else if ("length".equals(fr)) {
                                req.failKind = "truncated";
                                fail(req, "the model hit its output ceiling and the "
                                        + "page was cut off, so nothing was saved. "
                                        + "Lower the page length in SETUP, or raise "
                                        + "maxTokens for this profile.");
                                return;
                            } else {
                                req.failKind = "refused";
                                fail(req, "the provider stopped this page early ("
                                        + safeWord(fr) + ") and it was not saved");
                                return;
                            }
                        }
                    }
                    Map<String, Object> u = JsonParse.map(ev, "usage");
                    if (u != null) {
                        synchronized (LOCK) {
                            req.inputTokens  = JsonParse.num(u, "prompt_tokens", req.inputTokens);
                            req.outputTokens = JsonParse.num(u, "completion_tokens", req.outputTokens);
                        }
                    }
                }
            }
        }
    }

    private static void readSseAnthropic(Req req, InputStream in) throws Exception {
        try (InputStream limited = new LimitedInputStream(in, MAX_SSE_BYTES,
                     "the provider stream exceeded " + MAX_SSE_BYTES + " bytes");
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(limited, StandardCharsets.UTF_8))) {
            String line;
            while ((line = readLineBounded(r, MAX_SSE_LINE)) != null) {
                if (req.cancelled.get()) {
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
                            if (!t.isEmpty()) append(req, t);
                        }
                    }
                    case "message_start" -> {
                        Map<String, Object> m = JsonParse.map(ev, "message");
                        Map<String, Object> u = m == null ? null : JsonParse.map(m, "usage");
                        if (u != null) {
                            synchronized (LOCK) {
                                req.inputTokens = JsonParse.num(u, "input_tokens", 0);
                                req.cacheRead   = JsonParse.num(u, "cache_read_input_tokens", 0);
                                req.cacheWrite  = JsonParse.num(u, "cache_creation_input_tokens", 0);
                            }
                        }
                    }
                    case "message_delta" -> {
                        Map<String, Object> u = JsonParse.map(ev, "usage");
                        if (u != null) {
                            synchronized (LOCK) { req.outputTokens = JsonParse.num(u, "output_tokens", 0); }
                        }
                        // If the page hit the ceiling it was cut off, and the
                        // canon block will be missing. Say so rather than
                        // leaving a truncated page looking deliberate.
                        Map<String, Object> d2 = JsonParse.map(ev, "delta");
                        String stop = d2 == null ? "" : JsonParse.str(d2, "stop_reason", "");
                        // A page cut off at the ceiling has lost its CANON
                        // block and usually its last sentence. It used to be
                        // logged as a warning and committed anyway; it is now
                        // a hard failure, because a half page saved into the
                        // book is worse than no page at all.
                        if ("max_tokens".equals(stop)) {
                            req.failKind = "truncated";
                            fail(req, "the model hit its output ceiling and the page "
                                    + "was cut off mid-flow, so nothing was saved. "
                                    + "Lower the page length in SETUP, or raise "
                                    + "maxTokens for this profile.");
                            return;
                        }
                        if (!stop.isEmpty() && !"end_turn".equals(stop)
                                && !"stop_sequence".equals(stop)) {
                            req.failKind = "refused";
                            fail(req, "the model stopped early (" + safeWord(stop) + ")");
                            return;
                        }
                    }
                    case "message_stop" -> req.sawTerminal = true;
                    case "error" -> {
                        Map<String, Object> e = JsonParse.map(ev, "error");
                        req.failKind = kindOf(0, payload);
                        fail(req, "stream error: " + (e == null ? payload : JsonParse.str(e, "message", payload)));
                        return;
                    }
                    default -> { /* ping, content_block_start/stop */ }
                }
            }
        }
    }

    /** Appends streamed text - but only if this request is still the active one. */
    private static void append(Req req, String t) {
        boolean tooLarge = false;
        synchronized (LOCK) {
            if (active != req || req.isTerminal()) return;   // late bytes from a dead request
            if (req.full.length() + t.length() > MAX_OUTPUT_CHARS) {
                req.failKind = "too_large";
                tooLarge = true;
            } else {
                if (req.firstTokenAt == 0) req.firstTokenAt = System.currentTimeMillis();
                req.full.append(t);
                req.pending.append(t);
            }
        }
        if (tooLarge) {
            fail(req, "the provider produced more than " + MAX_OUTPUT_CHARS
                    + " characters; the page was discarded");
        }
    }

    /** Caps the length and strips control characters from a provider token. */
    private static String safeWord(String s) {
        if (s == null) return "?";
        String t = s.length() > 40 ? s.substring(0, 40) : s;
        return t.replaceAll("[^A-Za-z0-9_.:-]", "?");
    }

    /**
     * How many unreadable SSE events to tolerate before giving up.
     *
     * A handful is normal - providers send keep-alives and comment frames. A
     * hundred means the stream is not what we think it is, and continuing
     * would end in an EOF that looks like a clean finish.
     */
    private static final int MAX_BAD_EVENTS = 32;

    /** A checked failure used for every provider-controlled size ceiling. */
    private static final class ResponseTooLarge extends IOException {
        ResponseTooLarge(String message) { super(message); }
    }

    /**
     * Enforces a byte budget before BufferedReader or a byte accumulator gets
     * a chance to allocate from an untrusted response. One probe byte beyond
     * the limit distinguishes an exact-length response from an oversized one.
     */
    private static final class LimitedInputStream extends FilterInputStream {
        private int remaining;
        private final String message;

        LimitedInputStream(InputStream in, int limit, String message) {
            super(in);
            this.remaining = limit;
            this.message = message;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                int probe = super.read();
                if (probe < 0) return -1;
                throw new ResponseTooLarge(message);
            }
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining == 0) return read();
            int n = super.read(b, off, Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }

    private static String readUtf8Bounded(InputStream in, int maxBytes)
            throws IOException {
        try (InputStream limited = new LimitedInputStream(in, maxBytes,
                "the provider error response exceeded " + maxBytes + " bytes");
             ByteArrayOutputStream out = new ByteArrayOutputStream(
                     Math.min(maxBytes, 8192))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = limited.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * A bounded replacement for BufferedReader.readLine(). Checking a String
     * returned by readLine would be too late: the enormous allocation would
     * already have happened. CRLF and bare CR are both accepted.
     */
    private static String readLineBounded(BufferedReader reader, int maxChars)
            throws IOException {
        StringBuilder line = new StringBuilder(Math.min(maxChars, 1024));
        while (true) {
            int ch = reader.read();
            if (ch < 0) return line.isEmpty() ? null : line.toString();
            if (ch == '\n') return line.toString();
            if (ch == '\r') {
                reader.mark(1);
                int next = reader.read();
                if (next != '\n' && next >= 0) reader.reset();
                return line.toString();
            }
            if (line.length() >= maxChars) {
                throw new ResponseTooLarge(
                        "a provider stream event exceeded " + maxChars + " characters");
            }
            line.append((char) ch);
        }
    }

    private static void fail(Req req, String msg) {
        synchronized (LOCK) {
            if (active != req || req.isTerminal()) return;  // cancelled or done
            req.error = msg;
            req.status[0] = Status.ERROR;
        }
        Config.log("ERROR " + msg);
    }

    /**
     * Called once the worker leaves the read loop.
     *
     * Reaching the end of the stream is NOT success. The finally block used to
     * promote CONNECTING or STREAMING straight to DONE, so a dropped
     * connection, an EOF mid-sentence or a malformed stream all finished as
     * DONE and committed a partial page. A request is DONE only when the
     * provider actually signalled completion - see Req.sawTerminal.
     */
    private static void finish(Req req) {
        if (req.isTerminal()) return;
        if (req.sawTerminal && req.full.length() > 0) {
            req.status[0] = Status.DONE;
            return;
        }
        req.status[0] = Status.ERROR;
        if (req.error == null) {
            if (req.full.length() == 0) {
                req.failKind = "empty";
                req.error = "the provider returned nothing at all";
            } else {
                req.failKind = "truncated";
                req.error = "the stream ended before the page was finished ("
                        + req.full.length() + " characters); nothing was saved";
            }
        }
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
    private static final int THINKING_HEADROOM_REMOVED = 0;   // see anthropicBody

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

        // maxTokens is the player's cap on VISIBLE output and is honoured
        // exactly. Reasoning is a separate, opt-in budget: a model that thinks
        // spends those tokens against the same max_tokens field, so the two are
        // added only when the player has actually asked for thinking. The old
        // code added a flat 8000 to every request, which meant a setting
        // presented as a token limit was quietly 8000 higher than it said.
        int visible = Math.min(p.maxTokens, ceiling());
        int cap = visible + p.thinkingTokens;

        Json j = new Json().obj();
        j.put("model", p.model);
        j.put("max_tokens", cap);
        if (p.thinkingTokens > 0) {
            // The provider's documented field, rather than hoping a bigger
            // max_tokens leaves room. Explicit, and visible in the log.
            j.objKey("thinking");
            j.put("type", "enabled");
            j.put("budget_tokens", p.thinkingTokens);
            j.endObj();
        }
        j.put("stream", true);

        if (system != null && !system.isEmpty()) {
            // THE breakpoint. The system block is the charter, the world and
            // this campaign's fixed spine - identical on every page, so it hits
            // every time. The earlier design put it after the history, which
            // GROWS in the middle (canon, rooms seen, pages), so the prefix
            // diverged on every request: req.cacheWrite every page, req.cacheRead never.
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
