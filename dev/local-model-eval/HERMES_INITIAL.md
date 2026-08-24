# Hermes 3 initial PZStory evaluation

Date: 2026-08-24  
PZStory checkout: `8cff231` (`2.0.0-alpha.8`, bridge API 11)  
Hermes model: `hermes3:8b`  
Hermes digest: `4f6b83f30b62bc3d0cf9be09266db222805ee815c8fd7d8b38f863f655be78b7`

## Result

Hermes 3 8B is **not suitable as the default PZStory narrator in its current
Ollama configuration**. It reduced the number of lexically detected grounding
violations relative to the interrupted Stheno baseline, but it produced no
fully valid or grounded reply in the initial comparison.

| Model / strategy | Cases | Structure-valid | Grounded | Mean score |
|---|---:|---:|---:|---:|
| Stheno partial production baseline | 13 | 1 | 0 | 18.5 |
| Hermes production baseline | 6 | 0 | 0 | 20.0 |
| Hermes compact + cold + ledger | 6 | 0 | 0 | 32.5 |
| Hermes compact + cold + one repair | 6 | 0 | 0 | 35.0 |

The Stheno run was stopped after thirteen checkpointed cases, so its row is a
partial baseline rather than a balanced head-to-head result. The Hermes pass
used six representative development scenes: sparse first page, motionless
bathroom, empty rural road, recent kill, visible water with no drinking action,
and first-day chronology.

## Observed Hermes failures

- Every reply violated the terminal contract. Most CANON entries omitted the
  required `-` bullet; one reply ran until the output cap before reaching CANON.
- The sparse garage acquired a window, fridge, secret map, prior dreams,
  invented employment and a heroic objective.
- A motionless bathroom acquired a window and medicine cabinet, and the TODO
  instructed the survivor to open one.
- The rural-road scene invented a stranger emerging from the trees, power-line
  history and prior kills.
- The recent-kill scene invented additional zombies, stairs, a roof, radio and
  movement through the house.
- The visible-water scene made the survivor drink and invented a window,
  cobwebs and years of ownership history.
- The first-day scene avoided explicit previous-night history more often, but
  still invented a house and windows and failed the output contract.
- Corrective retry did not reliably remove flagged facts and did not repair the
  bullet syntax.

The raw replies substantiate the deterministic flags; the zero-pass outcome is
not an artefact of the vocabulary alone.

## Interpretation

Hermes follows broad instructions more calmly than Stheno and earned a higher
mean score under the compact prompt. That is not enough for PZStory's closed
world requirement. Like Stheno, it completes plausible scenes when information
is absent. Its consistent CANON syntax failure also shows that the current
production wording is ambiguous for small local models: it says to prefix
CANON with a fact kind, while the parser additionally requires a dash.

The next useful experiment is not a larger blind run. First make the local
response contract literal (`- [kind] ...`, empty TODO or `- ...`) and add a
validated fact-selection stage. Then compare Hermes, base Llama 3.1 8B and any
new candidate on the same development split. Only models with repeated perfect
grounding should be run on the holdout split or in a real campaign.

## Artifacts

- Hermes raw records: `runs/hermes-initial/results.jsonl`
- Hermes generated summary: `runs/hermes-initial/summary.md`
- Interrupted Stheno records: `runs/stage1-dev/results.jsonl`
- Smoke record: `runs/smoke/results.jsonl`
- Synthetic corpus: `scenes.json`
- Harness: `../LocalModelBenchmark.java`

