# Development notes

Nothing in here is needed to play. It is kept because it is needed to keep
working on the mod.

## `PZStory_Probe.lua`

Drop it beside `PZStoryBook.lua` in the mod's Lua folder to get two keys:

- **F9** — dump the complete state snapshot to `console.txt`. This is the
  fastest way to answer "what did the narrator actually see?", which is the
  question behind almost every bad page.
- **F10** — self-test the Java bridge and print the loaded JAR version.

Left out of the shipped mod because a player does not need two debug keybinds,
and F9/F10 collide with other mods.

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
prompt that is already fixed. The device guards against it — `NEEDS_JAVA` in
`PZStoryBook.lua` must match `Main.VERSION`, and a mismatch shows a FIRMWARE
MISMATCH page instead of throwing once a frame — but you still have to
remember to bump both.

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
