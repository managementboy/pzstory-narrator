# Security

This mod holds API keys and sends your game state to a third party, so it is
worth being explicit about what it does and what was checked.

## What it sends, and where

On every page: a JSON snapshot of your character and surroundings, the pages
written so far in this save, and the prompt. It goes to **exactly one place** —
the provider named in the active profile in `Zomboid/pzstory/profiles.json`.
There is no telemetry, no analytics, no second endpoint, and no phone-home. The
only outbound requests in the codebase are the ones in `Llm.java`, to the
`baseUrl` you configured.

If you would rather nothing left your machine, point an `openai-compatible`
profile at Ollama or LM Studio on localhost. It works the same way.

## Where the keys live

`Zomboid/pzstory/profiles.json` — outside the mod folder, outside the repo, and
outside your save, so copying a save or zipping the mod never carries a key
with it. `.gitignore` refuses `profiles.json`, `settings.json`, `*.key` and
`.env` in case anyone drops one in the tree.

Keys are never written into a page, never stored in the campaign file, and
never intentionally logged. `Config.redact()` runs over every production log
record in two passes: the exact keys currently loaded, then a generic sweep for
`sk-…` and `AIza…` shapes in case a provider echoes one back inside an error
body. Control characters are escaped and records are length-limited so an
untrusted error cannot forge additional log lines.

The optional development probe can print a complete snapshot when you press
F9. That snapshot contains the character name, exact position and inventory.
Always review `console.txt` before sharing it; redaction is a defence in depth,
not a promise that every possible private value or future provider key format
can be identified automatically.

## Audit, August 2026 — second pass (release 1.24.0)

An external review of `c097a5f` found more than the first pass did. Fixed in
1.24.0:

- **Release/binary integrity.** `Main.java` said `1.23.0-notnow` while
  `mod.info`, the Lua and the committed jar all said `1.23.1`. A clean build
  from the committed source therefore produced firmware the committed Lua
  rejected, breaking the "build it yourself" promise. There is now a single
  `Version` class with a **release** version and a separate **bridge API**
  version, `tools/verify.sh` fails the build if any of them disagree, and the
  jar is built deterministically so a reviewer can reproduce it byte for byte.
- **Bridge version matching was a prefix test.** A jar reporting `1.23.10`
  satisfied a Lua requiring `1.23.1`. It is now exact equality on an integer
  API version.
- **Loopback detection was bypassable.** `baseUrl.contains("//localhost")` and
  friends accepted `http://localhost.evil.example`,
  `http://127.0.0.1@evil.example` and `http://evil.example/path//localhost`,
  all of which resolve somewhere other than your machine. Replaced with
  `Endpoint`, which parses to a `java.net.URI` and tests the parsed host
  against a literal allow-list.
- **Remote plaintext is now refused even with no API key.** The request body is
  your character, your position, your notes and your campaign; that must not
  cross a network in clear text whether or not a credential rides along.
- **Custom Gemini endpoints were unchecked.** They now get the identical
  policy, and the model id is percent-encoded so `?`, `#` or `..` in
  `profiles.json` cannot rewrite the URL.
- **Redirects are no longer followed.** `Redirect.NORMAL` would re-send the
  `x-api-key` header and the whole game state to whatever a 30x named. Now
  `Redirect.NEVER`.
- **Request lifecycle.** All request state lived in static fields, so a
  cancelled request could finish into its successor's buffers and commit its
  page. Each request now carries an immutable id and its own state, and every
  mutation is dropped unless that request is still the active one.
- **Cross-save corruption.** A request in flight when a different save loaded
  would write the old book's page into the new one. `Campaign` now has a
  generation counter, captured at request start and rechecked before commit.
- **Truncated pages were committed.** Reaching the end of a stream was treated
  as success, so an EOF mid-sentence, a dropped connection or a `max_tokens`
  stop all saved a partial page with no canon block. Completion is now
  provider-specific: Anthropic needs `message_stop`, OpenAI-compatible needs
  `[DONE]` or `finish_reason=stop`, Gemini needs `finishReason=STOP`. Anything
  else fails with a player-facing reason and saves nothing.
- **`StoryAPI.log()` bypassed redaction.** It wrote to stdout directly; it now
  routes through `Config.log()`.
- **Cost control.** Anthropic requests sent `min(maxTokens, ceiling) + 8000`,
  so a setting presented as a token limit was silently 8,000 higher. `maxTokens`
  is now honoured exactly as the visible-output cap, and reasoning is an
  explicit opt-in `thinkingTokens` field that defaults to 0.

### Still open at 1.24.0

Reported by the same review and **not yet fixed** — see the repository issues:
bounded reads for files, SSE events and accumulated output; transactional
config reload; `ATOMIC_MOVE` and corrupt-file preservation in the campaign
store; count limits on directions and standing notes; the `SEEN.size() > 400`
off-by-one; inventory traversal budget; and replacing the remaining regex
parsing of JSON strings in Lua.

## Audit, August 2026 — first pass

Reviewed: `Llm.java` (network, TLS, error handling), `Config.java` (key
loading, redaction), `Json.java` / `JsonParse.java` (serialisation), and every
path that writes to disk.

**Two issues found and fixed:**

- **Unbounded recursion in the JSON parser.** `value() → object()/array() →
  value()` had no depth limit, so a deeply nested document overflowed the Java
  stack and crashed the game rather than failing cleanly. The mod parses three
  things it does not fully control — the campaign store, `profiles.json`, and
  whatever a provider streams back — so a hostile or corrupt input in any of
  them was a denial of service. Now capped at 200 levels, which no honest JSON
  reaches.
- **API keys could be sent over plaintext HTTP.** The `openai-compatible`
  adapter accepts any `baseUrl`, and an `http://` URL with a key attached hands
  that key to anything on the network path. It now refuses, unless the host is
  loopback — Ollama on `127.0.0.1` has no path to sniff.

**Checked and sound:**

- **TLS** — `java.net.http.HttpClient` with default settings, so certificates
  are validated. Nothing disables verification anywhere.
- **String escaping** — `Json.str()` escapes quotes, backslashes, newlines,
  tabs and all control characters below `0x20`. A player note containing a
  quote cannot corrupt `campaign.json`.
- **File writes** — the campaign store writes to a temp file and moves it into
  place, so a crash mid-write cannot leave a half-written book. All paths come
  from the game's own `ZomboidFileSystem`; none is user-controlled, so there is
  no traversal surface.
- **Input bounds** — notes cap at 500 characters, to-do items at 160, the list
  at 40 entries, canon and the learned lists at 40 each.
- **Threading** — one request in flight at a time; all shared state is behind a
  single lock; the worker thread is a daemon so it can never keep the game from
  exiting. Nothing blocks the render thread.
- **Failure handling** — every section of the state reader runs under its own
  `try`/`catch`, so one unexpected null costs that section and not the
  snapshot. The post-stream hook runs **only** on success, so a failed request
  cannot half-commit a page.

**Known and accepted:**

- **Prompt injection is inherent.** Item names, room names and anything else
  the game generates are interpolated into the prompt. A mod that adds an item
  called `"Ignore previous instructions"` could influence a page. The blast
  radius is one page of fiction in a single-player game, so this is not
  defended against.
- **The committed jar.** `mod/42/media/java/PZStory.jar` is a binary in the
  repository, for people who want to install without a JDK. Build it yourself
  with `./build.sh` if you would rather not run someone else's binary; the
  source beside it is the whole point.

## Reporting something

Open an issue. This is a hobby project with no security team and no SLA — see
the no-warranty section of the [README](README.md).
