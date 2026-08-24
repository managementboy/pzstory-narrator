# Live-testing PZStory 2.0 alpha

This alpha changes the campaign schema and continuously observes a small local
state sample. Use a copied save. Keep an untouched copy of the save and its
`pzstory/campaign.json` outside the game save directory.

## Build and install

The source release is `2.0.0-alpha.3` and bridge API is `7`. The tracked JAR on
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
8. Acquire an item, craft something, repair an item, eat or drink, tend a crop,
   light a fire, and open then close a door. Testing Mode should show each
   completed action. Cancelled timed actions must not appear.

## Inspect local data

Optionally copy `dev/PZStory_Probe.lua` beside the production Lua file:

- F8: toggle Testing Mode. It labels the current place in the world with its
  visit count and shows up to six pending detected events with their factual
  summaries and significance.
- F5: switch the visible Event Inbox between pending events and recent history.
  Press again for structured Story Facts. Narrated events are labelled with
  the page that consumed them; facts show type, provenance, confidence and
  active/superseded status.
- F9: provider-facing state, with privacy minimisation.
- F10: provider connectivity test.
- F11: local event journal.
- F6: local world memory (moved from F12 to avoid common screenshot overlays).

F11 and F6 intentionally contain local stable ids and must not appear in the
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

The carried-item sampler is bounded to 256 items, 128 distinct labels and four
new-item events per observation. Moving the same item between carried bags must
not create an acquisition event.
