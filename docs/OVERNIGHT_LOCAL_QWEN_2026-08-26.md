# Local Qwen overnight evidence — 26 August 2026

## Decision

Keep Qwen 2.5 3B as a fast, local **closed-world planner** for the Conspiracy
scenario. Do not use this model as the unrestricted author of production prose
on the current PC. Java renders the page from observed game facts and uses a
deterministic fallback whenever Qwen's plan is incomplete.

This is the best current balance of atmosphere, grounding, latency, and local
operation. It is ready for a fresh-game live acceptance test, not yet for a
stable-version rename.

The unusable Qwen 2.5 7B download was removed. LM Studio now lists only the
loaded 1.93 GB Qwen 2.5 3B narrator model plus the small embedding model.

## Evidence

- Full deterministic suite: **638 passed, 0 failed**, including 256 hostile or
  malformed planner replies that could neither break page structure nor leak a
  planner-controlled token into displayed prose.
- The documented no-game-JAR CI launcher and a second complete compilation
  against the installed Build 42 game classes both independently finished at
  **638 passed, 0 failed** after the sandbox-option test was moved behind a
  pure-Java boundary.
- Final local-model stress run: **80/80 structurally valid**, **80/80 grounded**,
  mean score **100**, mean planner latency **0.5 seconds**.
- An additional independent-seed long run completed **400/400 structurally
  valid and grounded** with no visible list-style regression. Planner latency
  was 0.451 seconds mean, 0.530 seconds at p95, 0.566 seconds at p99, and
  0.880 seconds maximum.
- Across the larger run, Qwen returned strict JSON in **400/400** cases and
  selected all four requested fact IDs in **227/400** cases. Partial valid
  selections are now retained and completed from deterministic fact priority;
  unknown, duplicated, or malformed selections still fall back completely.
- LM Studio's native stateful transport passed a two-turn continuity probe:
  both turns returned stored response IDs, `previous_response_id` was accepted,
  the checkpoint advanced, and both exact-response instructions were obeyed.
- Final live-like sequence gate: **60/60 pages passed** across ten independent
  six-page stored-response chains. Every chain began with the hidden Knox
  history seed; no title or opening repeated; mean planner latency was
  **0.61 seconds**.
- The stricter atmosphere/continuity corpus required multiple scene beats and
  paragraph structure per page—not only absence of hallucinations—and passed
  **80/80**.
- Earlier unrestricted Classic comparison: only **0/6** outputs passed the
  grounding heuristic and the prose invented tools, travel, actions, and
  biography. That route is not acceptable for local Qwen 3B.

Primary artifacts:

- `dev/local-model-eval/runs/overnight-long-stress/summary.md`
- `dev/local-model-eval/runs/overnight-long-stress/results.jsonl`
- `dev/local-model-eval/runs/overnight-final-acceptance/summary.md`
- `dev/local-model-eval/runs/overnight-final-acceptance/results.jsonl`
- `dev/local-model-eval/runs/overnight-stateful-final-acceptance/summary.md`
- `dev/local-model-eval/runs/overnight-stateful-final-acceptance/results.jsonl`
- `dev/local-model-eval/runs/overnight-conspiracy-safe-acceptance/summary.md`
- `dev/local-model-eval/runs/overnight-conspiracy-safe-acceptance/results.jsonl`
- `dev/local-model-eval/runs/overnight-conspiracy-quality-gate/summary.md`
- `dev/local-model-eval/runs/overnight-stateful-sequence/summary.md`
- `dev/local-model-eval/runs/overnight-stateful-sequence/results.jsonl`
- `dev/local-model-eval/runs/overnight-baseline-six/summary.md`

## Repairs included in the installed test build

- The one-scenario workflow now selects the grounded narrator instead of
  silently forcing Classic prose.
- Player notes cross the controlled planner boundary and shape shelter, food,
  perimeter, suspicion, or evidence-focused pages.
- Only the three approved opening tasks are created; the narrator cannot add a
  fourth generic task.
- Sandbox settings use translated selected values rather than internal `Z...`
  identifiers.
- Sandbox-option normalization is isolated from the game-dependent state
  reader, so the same regression is covered by the lightweight CI suite.
- The temporary raw trace updates while Qwen is replying.
- Completed actions read as story prose (`has moved`, `has acquired`, `has
  killed`) and telemetry such as “the action is complete” is removed.
- Recorded character pronouns remain authoritative.
- Opening pursuit now ends urgently instead of suggesting calm observation.
- Grammar repairs cover unemployed characters and `one ...` object phrases.
- Controlled chapter titles receive Roman sequence markers, and repeated scene
  openings receive a grounded local lead instead of being rejected at save.
- Continuation pages now weave visible objects into grounded atmosphere rather
  than presenting them as a status list, and consecutive completed actions use
  the character's recorded pronouns even when game telemetry shortens the name.

The workspace and installed JAR have SHA-256:
`06BCA193D88ADE67EB4D3011F235D1ECE747DBF3603939AB2C178247A145CF4D`.

## Completion audit

| Requirement | Authoritative evidence | Result |
|---|---|---|
| Remove unusable Qwen 7B | LM Studio catalog lists only Qwen 2.5 3B plus the embedding model | complete |
| Grounded, safe local narration | 80-case quality gate and 256 hostile-reply tests | complete |
| Multi-page continuity and atmosphere | final 60-page stateful sequence gate; zero repeated titles/openings; explicit anti-list prose regression | complete |
| Practical local latency | 400-turn p95 0.530 s, p99 0.566 s, max 0.880 s; stateful mean 0.61 s | complete |
| Deterministic code integrity | lightweight and game-class suites both 638/638; release verifier clean | complete |
| Install exactly what was tested | workspace and installed JAR hashes match the value above | complete |
| Preserve work without publishing | branch remains local, dirty artifacts retained, no push or release rename | complete |
| Live-play acceptance | requires the next fresh-game session | pending by design |

## Known limitations and next acceptance gate

- Safe prose is intentionally more bounded and somewhat more templated than
  the nicest unrestricted Qwen passages. Live play must judge whether that
  trade-off feels good over several pages.
- Qwen still omits some requested fact IDs in 43.25% of the 400-turn planner
  turns. Missing
  slots are completed locally, so this does not affect safety or availability.
- The F8 Testing Mode overlay remains close to unreadable at the tested display
  scale. It needs a proper opaque panel, wrapping, and scale-aware layout after
  the narrator acceptance test.
- No release rename and no Git push were performed. Those remain explicitly
  gated on successful live testing.
- The overnight branch remains at commit `5fa5b42`; all 59 working-tree
  entries and evaluation artifacts are preserved locally and unpushed.

For the next test, fully restart Project Zomboid, start a fresh Sandbox game,
and let KnoxOS open the first page. Then play and submit notes for the three
opening goals: safe house, food for a few days, and clearing nearby blocks.
