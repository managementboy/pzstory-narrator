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
- **The room** — furniture by type, items in plain sight, windows, doors,
  curtains, bodies. Never the inside of a closed container
- **Vehicles** — make and model, seat, engine, fuel, condition
- **Utilities** — whether the power and water are still on, and roughly how
  long they have left
- **The world's own rules** — the sandbox options this save was started with,
  so a page never builds dread about an infection that is switched off

Plus **the interval**: what changed since the last page. The snapshot is a
photograph; the delta is what the page is actually about.

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
[`docs/API_KEY_ANTHROPIC.md`](docs/API_KEY_ANTHROPIC.md).

A local model through Ollama needs no key and no account.

## Install

1. Install ZombieBuddy and add its launch option.
2. Copy `mod/42/` into `Zomboid/mods/PZStory/42/`.
3. Start the game once, enable **PZStory Narrator** in the mod list, restart.
4. Create `Zomboid/pzstory/profiles.json` with your key.
5. Load a save and press **F7**.

## Build from source

No Gradle, no downloads, no daemon:

```sh
./build.sh
```

You need **JDK 25 or newer** — Project Zomboid 42.20.3 compiles its own classes
to bytecode major 69, so an older compiler cannot read them. Edit the `LIB`
path in `build.sh` to point at your game folder.

The compiled `PZStory.jar` is committed under `mod/42/media/java/` so the mod
can be installed without a toolchain. If you would rather not trust a binary
from a stranger, delete it and run `./build.sh` — that is the whole point of
shipping the source beside it.

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
build.sh      javac + jar
```

`dev/PZStory_Probe.lua` adds F9 (dump a state snapshot to the console) and F10
(self-test the Java bridge). Drop it in beside `PZStoryBook.lua` if you are
working on the mod; leave it out if you are playing.

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
