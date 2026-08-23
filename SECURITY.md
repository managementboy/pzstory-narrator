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
never logged. `Config.redact()` runs over **every** line the mod prints, in two
passes: the exact keys currently loaded, then a generic sweep for `sk-…` and
`AIza…` shapes in case a provider echoes one back inside an error body. This is
why `console.txt` is safe to paste into a bug report — but skim it anyway
before you do.

## Audit, August 2026

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
