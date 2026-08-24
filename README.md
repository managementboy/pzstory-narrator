# PZStory Narrator

An in-game AI narrator for **Project Zomboid Build 42**.

Press a key. The game pauses, a 1993 handheld opens on screen, and a language
model writes the next page of your story — live, from the actual state of your
save. Not a summary. A page of prose about the person you are playing, in the
house you are standing in, about the thing that just happened to you.

The device is a **Premium Tech. Pilot 3000**: a slate-grey PDA with a reflective
LCD, drawn entirely from primitives so there is nothing to ship and nothing to
keep in sync with an art file. It has a to-do list, a notebook, a settings page
and an archive of every page you have been given.

---

## The idea

> The player is the reader of the book. The narrator is the voice telling the
> reader about the story. The game is what cannot be changed about the world.

Three rules follow from that, and everything else in the prompt serves them:

1. **The game is fact.** The narrator may not invent an object, a room, a
   person or an item the state did not give it. When it does not know, it
   writes around the gap instead of filling it.
2. **The player is intent.** Anything you write in the notebook is an
   instruction, and it is obeyed without argument.
3. **The narrator is voice only.** It may invent motive, memory, mood and
   atmosphere. It may not invent world, and it never tells you what to do.

Almost every bad page this project produced turned out to be the model behaving
reasonably given information it had not been given. The fix was nearly always
in the state reader, not the prose.

## What the narrator can see

Read live from the running game, every time you press WRITE:

- **Who they are** — name, pronouns, occupation, and every trait with its real
  description and whether it was chosen or endured
- **How they feel** — all 26 moodles by name and tier, which is the game's own
  judgement about what is worth noticing
- **The body** — per-part wounds, pain, stiffness, bandages, splints, and real
  infection separated from a false alarm the survivor cannot tell apart
- **The dead** — counted directly and reported in bands, plus the ones already
  down nearby
- **Noise** — every active world sound, so an alarm going off is the page
- **The room** — furniture and floor items on squares currently visible from
  the survivor's position, plus windows, doors, curtains and bodies. Never an
  unseen corner or the inside of a closed container
- **Vehicles** — make and model, seat, engine, fuel, condition
- **Utilities** — whether the power and water are still on, and roughly how
  long they have left
- **The world's own rules** — the sandbox options this save was started with,
  so a page never builds dread about an infection that is switched off

Plus **the interval**: what changed since the last page. The snapshot is a
photograph; the delta is what the page is actually about.

## Version 2.0 alpha

The 2.0 development line adds memory of events between pages. A lightweight
local observer records factual transitions such as kills, wounds, pursuit,
vehicle use, utility failure, important noises, skill improvement and movement
between stable places. It never starts a provider request by itself.

Events remain pending until a valid generated page and the event
acknowledgements are saved in one transaction. STOP, provider failure, invalid
output, a save switch or a disk error consumes none of them. Events arriving
while a page is streaming belong to the next page.

The same schema adds structured place memory. Stable room/building identities
remain local; providers receive only human-readable labels and qualitative
familiarity. Existing schema-1 campaigns migrate on their next successful
write.

This is `2.0.0-alpha.6`, the automated-testing milestone—not the completed 2.0
feature set. Campaign Director mode, grounded objectives, character memory,
the PDA timeline and opt-in world artifacts are tracked in
[`docs/V2_ROADMAP.md`](docs/V2_ROADMAP.md). Use a copied save and follow
[`docs/V2_ALPHA_TESTING.md`](docs/V2_ALPHA_TESTING.md).

## Requirements

- Project Zomboid **Build 42** (developed against 42.20.3)
- [**ZombieBuddy**](https://github.com/zed-0xff/ZombieBuddy) 2.3.0+, which is
  what lets a mod ship Java. Needs a Steam launch option — see its README
- An API key for one provider (see below), or a local model

## Providers

| Kind | Works with |
|---|---|
| `anthropic` | Claude, with prompt caching |
| `gemini` | Google AI Studio |
| `openai-compatible` | OpenAI, OpenRouter, Ollama, LM Studio, and anything else speaking that API |

Keys live in `Zomboid/pzstory/profiles.json`, which is **outside this repo and
outside your save**. They are never logged, never written into a page, and
never sent anywhere except the provider you configured. See
[`docs/PROVIDERS.md`](docs/PROVIDERS.md) for complete profile examples and
[`docs/API_KEY_ANTHROPIC.md`](docs/API_KEY_ANTHROPIC.md) for key setup.

A local model through Ollama needs no key and no account.

Before a request leaves the game, its live-state block is minimised: account
name, exact coordinates, engine ids, diagnostic errors, raw health/stat
telemetry and exact skill XP are removed. Narrative facts such as pronouns,
room name, visible objects, injuries and broad ability remain. Campaign pages,
canon, player notes, sandbox rules and the narrator prompt are still sent
because they are necessary to continue the story. A local provider keeps all
of that on the same machine.

## Install

1. Install ZombieBuddy and add its launch option.
2. Copy `mod/42/` into `Zomboid/mods/PZStory/42/`.
3. Start the game once, enable **PZStory Narrator** in the mod list, restart.
4. Create `Zomboid/pzstory/profiles.json` with your key.
5. Load a save and press **F7**.

## Build from source

No Gradle, no downloads, no daemon:

```sh
PZ=/path/to/ProjectZomboid ./build.sh
```

On a source or release-candidate branch, rebuild before copying `mod/42/` into
the game. The Lua bridge intentionally refuses an older committed JAR instead
of silently running without the branch's safety fixes.

The build is **deterministic for the same JDK and game-library toolchain** —
sorted inputs and a pinned timestamp — so two builds of the same source produce
a byte-identical jar and you can check the committed binary against your own.
Set `SOURCE_DATE_EPOCH` to pin a different stamp. It ends by running
`tools/verify.sh`, which refuses to ship a jar whose version disagrees with
`mod.info` or the Lua, or which omits any source class.

You need **JDK 25 or newer** — Project Zomboid 42.20.3 compiles its own classes
to bytecode major 69, so an older compiler cannot read them. Edit the `LIB`
path in `build.sh` to point at your game folder.

Before committing a release binary, run:

```sh
PZ=/path/to/ProjectZomboid ./tools/rebuild-and-compare.sh
```

That command builds to an isolated temporary path, verifies the rebuilt
archive, and then performs an exact byte comparison with the committed jar. A
source change that was not reflected in the binary, an unexpected compiler
version, entry-order drift, or any other packaging difference makes it fail.

The compiled `PZStory.jar` is committed under `mod/42/media/java/` so the mod
can be installed without a toolchain. If you would rather not trust a binary
from a stranger, delete it and run `./build.sh` — that is the whole point of
shipping the source beside it.

### Versions

Two numbers, because they answer different questions. **Release**
(`2.0.0-alpha.6`) is for people and changes whenever anything ships. **Bridge
API** (`5`) changes
when production Lua depends on a new Java method, payload, status or semantic,
and Lua compares it for exact equality — so a cosmetic release no longer
forces a firmware mismatch, and an incompatible pairing can no longer load.
Both live in
`src/de/fricke/pzstory/Version.java` and are verified at build time.

> **Java only reloads when Project Zomboid restarts.** Lua reloads when a save
> loads. During development the two *will* drift, so the device checks the JAR
> version on open and refuses to run against a mismatch rather than throwing
> once a frame.

## Layout

```
src/          Java source — the state reader, the prompt, the model client
mod/42/       The mod as players install it (Lua + the built jar)
docs/         Player-facing setup
dev/          Development tooling. Not needed to play
test/         Unit tests - pure Java, no game jars needed
tools/        test.sh (unit tests) and verify.sh (release integrity)
build.sh      javac + jar, then verify
```

`dev/PZStory_Probe.lua` adds F4/F3 (debug-only guided Test Lab), F8/F5
(Testing Mode and Event Inbox), F9
(provider-facing live-state projection), F10
(Java bridge self-test), F11 (local event journal) and F6 (local world memory).
Drop it in beside `PZStoryBook.lua` if you are working on the mod; leave it out
if you are playing. F11/F6 contain local stable ids and should be treated as
private diagnostics.

## Tests

```sh
./tools/test.sh     # unit tests; no Project Zomboid jars required
./tools/verify.sh   # version integrity, jar contents, secret scan
./tools/rebuild-and-compare.sh # rebuild and byte-compare the release jar
```

The first two run in CI. Anything that touches `zombie.*` cannot be unit-tested
without the proprietary jars and is verified in-game instead — see
`dev/README.md`.

For a disposable-save checklist covering rebuild, install, privacy inspection,
cancellation and cross-save tests, see [`docs/LIVE_TESTING.md`](docs/LIVE_TESTING.md).

## Licence

**[CC0 1.0 Universal](LICENSE)** — public domain, as far as the law allows.

This was written by Elkin with Claude. No copyright is claimed. Take it, fork
it, ship it in your own mod, sell it, rename it, do not credit anyone. If your
jurisdiction will not let an author waive their rights (Germany, for one), CC0
falls back to an unconditional free licence that gets you to the same place.

### No warranty

**THE WORK IS PROVIDED AS-IS, WITHOUT WARRANTY OF ANY KIND**, express or
implied, including but not limited to the warranties of merchantability,
fitness for a particular purpose, accuracy and non-infringement. In no event
shall anyone be liable for any claim, damages or other liability arising from
the use of this software.

Two specific things worth saying plainly, because this is not ordinary
software:

- **It sends your game state to a third party.** Every page ships a snapshot of
  your character and surroundings to whichever provider you configured. That is
  the entire mechanism. If you are not comfortable with it, run a local model —
  the `openai-compatible` adapter works with Ollama and nothing leaves your
  machine.
- **It costs money if you use a paid provider.** A page is a few thousand input
  tokens and a few hundred out. Prompt caching makes later pages much cheaper,
  but nobody here is watching your bill.

## Thanks

To **zed-0xff** for ZombieBuddy, without which a Java mod would not be possible
at all, and to The Indie Stone for a game whose world model is detailed enough
that a narrator has something true to say about it.
