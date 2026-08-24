# PZStory 2.0 roadmap

Version 2.0 turns the truthful snapshot narrator into an event-aware long-form
story system. The core rule remains unchanged: the game is fact. Features that
invent or alter the world will be explicit opt-ins and will create validated
game objects before prose may refer to them.

## Milestone 1 — Event memory (`2.0.0-alpha.1`)

Implemented:

- Immutable, typed and bounded story events.
- Deterministic significance from 1 to 100.
- Lightweight five-second local observation without automatic provider calls.
- Detection of place changes, kills, bites and wounds, recovery, level gains,
  vehicle entry/exit and engine changes, utility failure, noise, pursuit,
  shelter changes, weather transitions, sleep and waking.
- Selection of at most twelve high-value events per page.
- Exact event-batch capture: events arriving during generation stay pending.
- Atomic page/event commit and complete rollback after disk failure.
- `campaign.json` schema 1 to schema 2 migration.
- Structured place identity, return visits and privacy-safe memory projection.
- Local diagnostics through `eventJournal()`, `worldMemory()` and the optional
  F11/F6 development probe and the F8 Testing Mode overlay.

Remaining before this milestone is promoted from alpha:

- Validate observer cost and event cadence inside a real Build 42 game.
- Promote the tested Event Inbox from Testing Mode into the normal device UI.

## Milestone 2 — Story memory

Implemented in `2.0.0-alpha.3`:

- Schema-3 bounded typed facts for world, biography, people, possessions,
  injuries, knowledge, beliefs, promises and unresolved threads.
- Provenance and confidence for game, player, media, narrator and migrated facts.
- Conservative positive/negative contradiction supersession with source authority.
- Lossless schema-1/schema-2 canon migration and atomic fact rollback.
- Recent title/opening guidance plus exact repetition rejection.
- Testing Mode Story Facts view with active/superseded status.

Remaining:

- Track weapons, vehicles, safehouses, injuries and routines across pages.
- Maintain deliberate setup/payoff records and prevent abandoned foreshadowing.

## Milestone 3 — Campaign Director

- Add an opt-in Director mode beside the current Chronicler mode.
- Generate and freeze a private campaign bible at the first-page transaction.
- Store a fixed truth, cast, settlement route, clues, revelations, acts and
  final resolution.
- Validate every destination against a local location registry.
- Keep exactly one major active objective.
- Detect arrival and evidence acquisition from the game.
- Give every objective success, failure, impossible and fail-forward paths.
- Keep planned facts hidden until their reveal conditions are met.

## Milestone 4 — Character and legacy

- Optional character-background questionnaire stored as protected player canon.
- Evidence-based psychological continuity and trait expression.
- Injury, scar, familiar weapon and familiar vehicle histories.
- Separate identities for multiple survivors in one world.
- Automatic factual death checkpoint, final page and campaign epilogue.
- Successor mode for a new survivor discovering the previous book.

## Milestone 5 — Diegetic device

- EVENT INBOX with no automatic paid generation.
- Timeline, places, people, evidence and active-thread screens.
- Search, bookmarks and chapter grouping in the archive.
- Optional physical Pilot 3000 inventory item, battery and condition.
- Markdown/HTML/EPUB campaign export.
- Separate interface language, story language, point of view and tense.
- Optional cached local/provider text-to-speech.

## Milestone 6 — Opt-in world direction

- Whitelisted PZStory notes, photographs, tapes and maps as real save objects.
- A dedicated story radio frequency with validated scheduled transmissions.
- Physical dead drops only in valid, unexplored locations.
- Diegetic map annotations instead of omniscient HUD markers.
- Constrained encounter proposals validated by Java before world mutation.
- Extension API for custom items, traits, vehicles, locations and event types.

## Non-negotiable constraints

- No automatic paid or remote request.
- No event is consumed by cancellation, invalid output or failed persistence.
- No exact coordinate or engine id enters provider-facing event or memory text.
- No model-generated command executes directly against the game.
- No world-changing feature is enabled by migration or by default.
- Every new store remains bounded, versioned, recoverable and save-specific.
- The committed Java JAR must match source, release metadata and bridge API.
