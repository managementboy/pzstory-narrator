# Live-testing PZStory 2.0 alpha

This alpha changes the campaign schema and continuously observes a small local
state sample. Use a copied save. Keep an untouched copy of the save and its
`pzstory/campaign.json` outside the game save directory.

## Build and install

The source release is `2.0.0-alpha.9` and bridge API is `12`. The tracked JAR
must be rebuilt from the exact checkout whenever production Java changes.

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
   now says `"schema":7`.
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

## Experimental validated narrator checks

1. In a Chronicler campaign with no pending notebook direction, open SETUP and
   change `narrator` from `classic` to `safe (experimental)`.
2. Press WRITE with Qwen 2.5 3B selected. While it runs, the device should say
   `planning safely`; planner JSON must never appear on the page.
3. Test a visible threat, a wound, a vehicle, failed utilities, and items inside
   two carried bags. Every concrete claim must be present in the live state or
   the captured event journal.
4. Repeat several pages from an unchanged location. Record repeated titles,
   sentences, TODOs, and any page rejected for repetition.
5. Queue a notebook direction or select Director mode. WRITE must refuse before
   contacting the provider, and the direction/event batch must remain pending.
6. Switch back to `classic`. Existing pages stay unchanged and the next page
   uses the normal streaming narrator again.

## Inspect local data

Optionally copy `dev/PZStory_Probe.lua` beside the production Lua file:

- F8: toggle Testing Mode. It labels the current place in the world with its
  visit count and shows up to six pending detected events with their factual
  summaries and significance.
- The game's F3, F4 and F5 speed controls remain untouched. The debug Lua
  console can call `PZStoryProbeSwitchInbox()` to cycle the Event Inbox when
  that diagnostic view is needed.
- F9: provider-facing state, with privacy minimisation.
- F10: provider connectivity test.
- F11: local event journal.
- F6: local world memory (moved from F12 to avoid common screenshot overlays).
- The guided Test Lab opens automatically in debug mode and is controlled by
  its visible buttons.

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

## Story-memory checks

1. Kill at least two zombies with the exact same held weapon. Continuity
   Evidence should promote that specific weapon only after the second kill.
2. Exit and re-enter the same vehicle twice. It may become familiar, but prose
   must not claim ownership merely from use.
3. Sleep twice in the same room. It may be called familiar shelter, never
   automatically safe or a safehouse.
4. Complete the same crafting, repair, farming or fire action three times at
   one place. It becomes a routine only on the third recorded completion.
5. When a page deliberately emits `[thread] setup short-key: description`,
   confirm Story Threads shows it OPEN. A later exact-key `payoff` or `abandon`
   entry must close it; unrelated keys must not.
