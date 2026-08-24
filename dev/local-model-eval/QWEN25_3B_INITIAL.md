# Qwen 2.5 3B Instruct initial PZStory evaluation

Date: 2026-08-24  
PZStory checkout: `8cff231` (`2.0.0-alpha.8`, bridge API 11)  
Model: `qwen2.5:3b-instruct`  
Digest: `357c53fb659c5076de1d65ccb0b397446227b71a42be9d1603d46168015c9e4b`

## Result

Qwen 2.5 3B Instruct is the most promising local candidate tested so far, but
it is **not grounded enough to become PZStory's default narrator**.

The first pass exposed a contract ambiguity: small models copied explanatory
parentheses onto heading lines or omitted the required CANON/TODO hyphens. A
literal final output template fixed that completely. The corrected constrained
pass produced twelve structurally valid replies out of twelve.

| Strategy | Cases | Structure-valid | Grounded | Mean score | Mean time |
|---|---:|---:|---:|---:|---:|
| Production prompt | 6 | 0 | 0 | 25.0 | 4.3 s |
| Compact/cold with literal contract | 6 | 6 | 0 | 62.5 | 1.9 s |
| Compact/cold with one repair | 6 | 6 | 0 | 70.0 | 3.9 s |

Qwen was far faster than both 8B candidates and considerably more responsive
to the explicit response template. Corrective retry also improved its mean
grounding score. Nevertheless, every reply retained at least one unsupported
physical fact or unplayed action.

Examples included a zombie and prior killing in the sparse garage; a window,
drawer and taking action in the motionless bathroom; a cabin, traffic, backpack
and prior killing on the empty road; a house and new hand action after the
recorded hallway kill; and invented furniture or prior killing in the
first-day/no-action scenes.

One initially reported perfect reply was manually rejected: it invented a
backpack, taking the axe out, a prior branch killing and contemplated violence.
That exposed a scorer bug where neutral `they` action fixtures did not detect
`he`/`she` forms. The scorer now normalizes those pronouns and the corrected
run reports no grounded passes.

## Interpretation

The 3B model has enough instruction-following ability for PZStory's strict
heading syntax when shown a literal template. It does not have enough reliable
closed-world discipline for unrestricted prose. Its speed makes it a useful
candidate for a constrained fact-selection or verification role, rather than
the final narrator.

The next worthwhile Qwen experiment would force it to select fact identifiers
in structured JSON before writing prose, validate that selection in Java, and
give the prose stage only the approved facts. Another blind prompt-only run is
unlikely to remove the remaining hallucinations.

## Artifacts

- Corrected raw run: `runs/qwen25-3b-corrected/results.jsonl`
- Corrected summary: `runs/qwen25-3b-corrected/summary.md`
- Pre-correction contract run: `runs/qwen25-3b-contract/results.jsonl`
- Initial production/compact run: `runs/qwen25-3b-initial/results.jsonl`
- Synthetic corpus: `scenes.json`
- Harness: `../LocalModelBenchmark.java`

