# Development notes

Nothing in here is needed to play. It is kept because it is needed to keep
working on the mod.

## `PZStory_Probe.lua`

When Project Zomboid is launched with `-debug`, **F4** opens the guided
PZStory Test Lab and **F3** runs the selected suite. F4 cycles Quick Checks,
Event Walkthrough and Continuity Walkthrough, then closes the lab. Quick
Checks validates the live bridge and all local diagnostic stores without a
provider request. The walkthroughs watch the real event journal and advance
only after ordinary gameplay hooks fire. The continuity fixture equips one
axe and spawns two nearby zombies; the player still performs both real kills.

The lab refuses to prepare fixtures outside game debug mode. Use a copied or
disposable save: spawned items and zombies are normal save-world objects.
For unattended harnesses whose synthetic function keys are not accepted by
the game, the debug Lua console exposes `PZStoryTestLabToggle()` and
`PZStoryTestLabRun()` with the same debug-mode safeguards.
The read-only Quick Checks also run automatically after a save loads in game
debug mode, so an unattended smoke test needs no synthetic key input.

Drop it beside `PZStoryBook.lua` in the mod's Lua folder to get six keys:

- **F8** — toggle the local Testing Mode overlay and pending Event Inbox.
- **F5** — cycle pending events, recent history, structured facts, deliberate
  threads and local continuity evidence.

- **F9** — dump the minimised, provider-facing live-state block to
  `console.txt`. This is the fastest way to answer "what did the narrator
  actually see?" without logging exact coordinates, account names or raw
  diagnostics. It still contains private story facts; review it before sharing.
- **F10** — self-test the Java bridge and print the loaded JAR version.
- **F11** — dump the 2.0 local event journal. This contains stable local ids;
  it is never provider input and should be treated as private.
- **F6** — dump structured place memory, including the local identities used
  to distinguish same-named rooms.

Left out of the shipped mod because a player does not need debug keybinds,
and function keys collide with other mods or screenshot overlays.

## `context-audit.html`

A line-by-line audit of everything the narrator is and is not told about
Project Zomboid, checked against the game's own files rather than from memory —
`javap` against `projectzomboid.jar`, extraction from `media/radio/RadioData.xml`,
the sandbox presets, and the map folder. Most of it has since been fixed; the
findings are marked. Useful as a record of *how* things were verified, and of
what is still open.

## The one fact that costs the most time

**Lua reloads when a save loads. Java only reloads when Project Zomboid
restarts.** During development the two will silently drift and you will debug a
prompt that is already fixed. The device guards against it — `NEEDS_API` in
`PZStoryBook.lua` must match `Version.API`, and a mismatch shows a FIRMWARE
MISMATCH page instead of throwing once a frame. Release metadata is checked
separately by `tools/verify.sh`.

## Verify, don't recall

Every fact about the game in this codebase was read out of the game, not
remembered. `javap -cp projectzomboid.jar zombie.some.Class` before writing
against an API; the game's own Lua in `media/lua/` when an engine method's
meaning is ambiguous. That discipline caught, among others:

- `haveElectricity()` is a *generator*, not the mains — `hasGridPower()` is the
  mains. Reading the wrong one reported the power dead on day one.
- `Stats.getNumVisibleZombies()` feeds the music system and reads zero with a
  crowd on screen. The zombie list has to be walked directly.
- `getIsVisible()` instantiates the element if it does not exist, and a fresh
  `UIElement` defaults to visible — so the first keypress of a session was
  being swallowed.
