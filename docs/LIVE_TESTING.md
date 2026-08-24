# Live-testing the safety branch

Use a copied save first. The branch is designed to fail closed, but it changes
campaign persistence, provider payloads and the Java/Lua bridge together, so a
disposable copy is the right place to prove the integration.

## 1. Prepare a recoverable save

1. Exit Project Zomboid completely.
2. Copy the entire save directory to a new test save or make an archive of it.
3. Keep a second copy outside `Zomboid/Saves/`.
4. Back up the save's `pzstory/campaign.json` separately if it already has a
   book you care about.

PZStory also rotates the previous valid campaign to `campaign.json.bak` on
every successful write. That is crash recovery, not a replacement for a save
backup.

## 2. Build the matching JAR

The Java and Lua halves must be built from the same checkout. Project Zomboid
does not reload Java when a save reloads, so close the game before rebuilding.

```sh
git switch safe-live-testing-2026-08
./tools/test.sh
PZ=/path/to/ProjectZomboid ./build.sh
./tools/verify.sh
```

The build needs JDK 25+, `projectzomboid.jar`, and `ZombieBuddy.jar`. Do not
install or test the branch if `build.sh` or `verify.sh` fails. In particular,
the verifier now checks both directions: every binary class must have source,
and every top-level source class must be present in the JAR. This prevents an
old binary from passing merely because all of *its* classes happen to have
source files.

For a byte-for-byte release check after rebuilding the tracked JAR:

```sh
PZ=/path/to/ProjectZomboid ./tools/rebuild-and-compare.sh
```

## 3. Install without mixing versions

1. Remove the previous test copy of `Zomboid/mods/PZStory/42/`.
2. Copy this checkout's complete `mod/42/` directory into it.
3. Confirm ZombieBuddy is installed and its launch option is active.
4. Start Project Zomboid afresh. Reloading a save is not enough after a JAR
   change.
5. Open the device with F7. It should report release `1.25.0-rc1`, bridge API
   `4`. A firmware mismatch means the install contains mixed files; stop there.

Begin with an Ollama or other loopback profile if available. It exercises the
same transactional page path without sending campaign data off the computer.

## 4. Inspect the provider-facing state

Optionally copy `dev/PZStory_Probe.lua` beside the production Lua file before
launch. F9 writes the exact provider-facing **live-state block** to
`console.txt`. It should contain story facts, but not `username`, exact `x/y/z`
coordinates, `roomId`, engine ids, `readErrors`, raw `stats`, raw health totals,
exact skill XP or exact Celsius temperature.

The preview is not the whole request. Campaign history, canon, notes, sandbox
rules and the narrator charter are also sent because they are needed to write
the next page. F10 performs a tiny provider connectivity test.

Treat `console.txt` as private anyway: the preview still contains the
character's name, inventory, wounds and surroundings.

## 5. Acceptance checks

| Check | Procedure | Pass condition |
|---|---|---|
| Normal page | Press WRITE and let a page finish | Status moves through checking/saving; exactly one archive page appears |
| Strict output | Use a deliberately weak/local model likely to omit a heading | Device reports invalid output; archive and campaign remain unchanged |
| STOP | Press STOP while text is streaming | Device says the unfinished reply was discarded; archive count does not change |
| Connection loss | Stop the local server or disconnect during a stream | No partial page, canon, TODO or last-state snapshot is saved |
| Save switch | Start a page, then leave/load the copied save | Old request is cancelled and cannot write into the newly loaded campaign |
| Direction lifetime | File a NEXT note, cause a failed/stopped page, then retry | Note survives the failure and is consumed only by a successful page |
| Persistence failure | On a disposable copy, make the campaign directory unwritable before WRITE | Valid reply reports save failure; in-memory archive remains unchanged |
| Recovery | On a disposable copy only, preserve both files and corrupt `campaign.json` | Original is copied to `campaign.json.corrupt-*`; valid `.bak` is recovered once |
| Duplicate bags | Carry two same-named bags and pick up another | Delta identifies the new bag by engine id; contents are not narrated as individually looted |
| Visibility | Stand in a room with occluded corners or zombies behind walls | Provider preview includes only currently visible squares; pursuers may remain urgent even just out of sight |

After every negative test, reopen the archive and compare page count, latest
page, canon/TODO and `campaign.json`. “The UI looked okay” is not sufficient;
the persisted story must also be unchanged.

## 6. Useful evidence for a bug report

Include:

- release and bridge API shown by the device;
- Project Zomboid and ZombieBuddy versions;
- provider `kind`, model id and whether it is local (never the key);
- the `[PZStory]` log lines around the failure;
- whether archive count or `campaign.json` changed;
- exact reproduction steps on the copied save.

Do not attach `profiles.json`, a raw save, `campaign.json`, or the full F9
preview unless you have reviewed and intentionally chosen to share their
contents.
