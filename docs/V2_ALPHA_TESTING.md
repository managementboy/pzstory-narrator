# Live-testing PZStory 2.0 alpha

This alpha changes the campaign schema and continuously observes a small local
state sample. Use a copied save. Keep an untouched copy of the save and its
`pzstory/campaign.json` outside the game save directory.

## Build and install

The source release is `2.0.0-alpha.1` and bridge API is `5`. The tracked JAR on
the development branch is not usable until it has been rebuilt from this exact
checkout.

```sh
git switch version-2.0-development
./tools/test.sh
PZ=/path/to/ProjectZomboid ./build.sh
./tools/verify.sh
```

Close Project Zomboid before replacing the mod. Copy the complete `mod/42/`
directory and restart the game; loading another save does not reload Java.

## First-pass acceptance checks

1. Open a copied schema-1 campaign. Confirm all old pages, canon, notes and
   tasks remain. Make one harmless task edit, exit and confirm `campaign.json`
   now says `"schema":2`.
2. Play for several minutes without opening the PDA. Kill one zombie, change
   rooms, enter a vehicle or sustain a wound, then press WRITE. The page should
   centre the most important event rather than merely describe the current
   room.
3. Press STOP during generation. Press WRITE again. The same important event
   must still be available; cancellation must not consume it.
4. Start WRITE, then cause another event while text streams. The late event
   must remain for the following page.
5. Disconnect a local provider or use invalid output. Confirm the archive and
   pending events are unchanged.
6. Switch copied saves while a request is active. The old event batch must not
   enter the new save.
7. Revisit two same-named rooms. Prose may recognise familiarity but must not
   carry furniture or objects from one room into the other.

## Inspect local data

Optionally copy `dev/PZStory_Probe.lua` beside the production Lua file:

- F9: provider-facing state, with privacy minimisation.
- F10: provider connectivity test.
- F11: local event journal.
- F12: local world memory.

F11 and F12 intentionally contain local stable ids and must not appear in the
provider payload or generated page. They are debugging data; review them before
sharing `console.txt`.

## Performance check

The observer performs no network work and is throttled to one lightweight
sample every five real seconds. Low-value changes are checkpointed at most once
per minute; decisive events are saved immediately, and every WRITE flushes the
journal before contacting a provider. Test with a large inventory, a crowded
street and fast-forward. Report visible stutter, repeated identical events,
missed high-value events, or unusual campaign-file growth with reproduction
steps.
