# Qwen 2.5 3B validated narrator evaluation

Date: 2026-08-25  
PZStory checkout: `8cff231` (`2.0.0-alpha.8`, bridge API 11)  
Model: `qwen2.5:3b-instruct`  
Digest: `357c53fb659c5076de1d65ccb0b397446227b71a42be9d1603d46168015c9e4b`

## Executive result

The experiment raised Qwen's deterministic development score from a best of
70.0 with prompt-and-repair free prose to **100.0 with a validated hybrid
pipeline**. The final development configurations produced 165/165 grounded,
structure-valid replies. A separately frozen nine-scene holdout produced a
95.6 machine score and 9/9 structure-valid replies.

The machine marked two holdout replies ungrounded because the literal
occupation `Fire Officer` matched the scorer's physical term `fire`. Manual
review confirmed that neither reply invented a fire and that **all 9/9 holdout
replies were closed-world safe**. One safe holdout reply scored 90 because the
catalog omitted nested bag contents and therefore missed the expected
hammer/bag focus.

This is an architectural result, not evidence that Qwen can safely write
unrestricted prose. Qwen plans with validated IDs; Java owns the words that
reach the story.

## Architecture

The `validated-catalog` pipeline:

1. Converts synthetic game state into an enumerated fact catalog.
2. Asks Qwen to choose four fact IDs plus controlled mood, title and TODO enums.
3. Parses the returned JSON and rejects unknown IDs or enum values.
4. Falls back to safe deterministic choices when the plan is malformed.
5. Renders the final terminal reply exclusively from state-backed slots and a
   fixed phrase bank.
6. Runs the normal `PageResult` terminal parser and grounding scorer against
   the rendered reply.

The model never gets to introduce an unvalidated noun, action or history claim
into the final page. Warm-planner failures therefore affect variation, not
factual safety.

## Reproducible results

| Run | Configuration | Cases | Structure | Machine grounded | Mean score | Planner valid |
|---|---|---:|---:|---:|---:|---:|
| Development seed stress | cold | 75 | 75 | 75 | 100.0 | 75 |
| Development diversity | cold | 45 | 45 | 45 | 100.0 | 42 |
| Development diversity | warm | 45 | 45 | 45 | 100.0 | 37 |
| Frozen holdout | cold | 9 | 9 | 7 | 95.6 | 9 |

The 11 malformed plans in the diversity run all produced safe 100-point final
replies through validated fallback. Cold development planning averaged roughly
0.4-0.6 seconds after model warm-up. Holdout mean wall time was 1.14 seconds,
including a five-second first-model-load case.

The holdout was run exactly once after the pipeline was selected. It was not
rerun or tuned. The raw machine result remains unchanged; manual interpretation
is documented separately rather than rewritten into the score.

## Player-facing quality

Manual quality review rated factual safety and structural reliability 5/5 but
overall player-facing writing only **2/5**. The renderer is clear but reads like
a state report:

- all nine holdout plans selected the same mood and TODO;
- seven of nine pages shared the same title;
- several full filler sentences appeared in every reply;
- occupation and traits were repeated as labels rather than expressed as
  character perspective;
- immediate threats and completed actions could collapse into a generic
  `completed change` notice;
- the first-page premise refers to records and evidence, breaking immersion.

The hybrid safety boundary is worth keeping. The current surface renderer is
not ready to replace PZStory's narrator.

## Known technical gaps

- Catalog extraction handles string inventory entries but not nested container
  objects such as bags holding item arrays.
- Threat fields such as zombies coming toward the survivor are not normalized
  into typed, mandatory-priority facts.
- The lexical scorer does not distinguish a state-backed occupation such as
  `Fire Officer` from an unsupported physical `fire`.
- Planner JSON becomes less reliable when example anchoring is removed, though
  safe fallback already contains that risk.
- Phrase-bank coverage and variation are too small for campaign-length prose.

## Recommendation

Do not merge this renderer into production yet. Continue with the validated
fact boundary and improve only the safe surface layer:

1. Normalize nested containers, threats and completed-change types.
2. Give immediate threats and important changes mandatory narrative priority.
3. Add multiple safe sentence forms for every typed fact.
4. Let Qwen select sentence-form and ordering IDs, never unrestricted words.
5. Add controlled situation- and character-sensitive interior phrases.
6. Validate against new development fixtures derived from these failure
   classes; preserve the completed holdout unchanged.
7. Require perfect factual safety plus at least 3.5/5 manual prose quality
   before an experimental in-game integration.

## Curated artifacts

- Final development seed stress:
  `runs/qwen25-3b-validated-dev-v2-seeds/`
- Final cold/warm diversity stress:
  `runs/qwen25-3b-validated-dev-v3-diversity/`
- Frozen holdout:
  `runs/qwen25-3b-validated-holdout-final-20260825/`
- Manual holdout review:
  `QWEN_VALIDATED_HOLDOUT_QUALITY_REVIEW.md`
- Original free-prose Qwen evaluation:
  `QWEN25_3B_INITIAL.md`
- Synthetic corpus: `scenes.json`
- Benchmark harness: `../LocalModelBenchmark.java`
