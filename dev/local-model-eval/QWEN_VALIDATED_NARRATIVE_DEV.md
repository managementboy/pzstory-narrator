# Qwen validated narrative renderer — development evaluation

Date: 2026-08-25
Base result: `QWEN25_3B_VALIDATED_FINAL.md`
Model: `qwen2.5:3b-instruct`
Selected run: `runs/qwen25-3b-narrative-dev-v3-final`
Development corpus: `scenes-v2.json`

## Result

The second controlled renderer preserves the validated fact boundary while
raising manual writing quality from approximately 2/5 to **3.5/5 on the
development set**. It remains a constrained hybrid: Qwen selects fact, mood,
title and TODO IDs; Java validates those choices and owns every emitted phrase.

Five seeds across 18 development scenes produced:

| Metric | Result |
|---|---:|
| Cases | 90 |
| Structure-valid | 90/90 |
| Grounded | 90/90 |
| Mean score | 100.0 |
| Valid Qwen plans | 83/90 |
| Safe deterministic fallbacks | 7/90 |
| Mean planner time | 0.67 seconds |

No final reply lost safety when Qwen returned malformed planning JSON.

## New failure-class fixtures

Three development-only scenes were added after the original holdout was frozen:

- nested bag inventory with an item transferred between two containers;
- approaching zombies with no completed survivor action;
- a `Fire Officer` occupation in a scene containing no physical fire.

All fifteen original development scenes and all three new scenes passed across
all five seeds. They live in `scenes-v2.json`; the original `scenes.json` and
the completed holdout run were not changed or rerun.

## Improvements

- Nested inventory objects now become typed container facts. Empty and
  non-empty carried bags are rendered explicitly.
- Approaching threats become mandatory-priority facts instead of generic
  `completed change` notices.
- Important completed changes receive situation-specific safe sentences.
- State-backed occupations are masked only during lexical physical-fact
  scoring, so `Fire Officer` no longer masquerades as an invented fire.
- The first-page premise no longer discusses records, evidence or prompt
  constraints.
- Visible facts are grouped into prose instead of repeated `Present and
  visible:` labels.
- Controlled occupation/trait responses provide limited character perspective
  without adding biography.
- Expanded title, TODO, opening and filler phrase banks reduce repetition.

## Variation measurements

Across 90 replies:

- 30 distinct titles; the most common title appeared in 14.4% of replies;
- 11 distinct TODO lines; the most common appeared in 20.0%;
- the most repeated full PAGE sentence appeared in 22.2%;
- all measured full PAGE sentences stayed below the 25% repetition target.

This is a substantial improvement over the frozen holdout renderer, where one
mood and TODO appeared in every reply and several full filler sentences appeared
in nearly every page.

## Manual quality assessment

| Dimension | Earlier renderer | Narrative renderer |
|---|---:|---:|
| Factual safety | 5/5 | 5/5 |
| Structural reliability | 5/5 | 5/5 |
| Readability | 3/5 | 4/5 |
| Scene focus | 2/5 | 4/5 |
| Atmosphere | 2/5 | 3.5/5 |
| Character voice | 1/5 | 2.5/5 |
| Variety | 1/5 | 3.5/5 |
| Overall player-facing quality | 2/5 | 3.5/5 |

The pages remain recognizably template-controlled, and occupation-sensitive
voice is still broad rather than individual. They are now coherent survival
vignettes rather than state reports, with immediate threats and inventory
changes preserved in the prose.

## Scope and next decision

This result validates development behavior only. It does not revise the frozen
holdout score and must not be presented as a second holdout pass.

Before integration, the evaluation-only normalization and renderer should be
designed as production classes with unit tests against the full game-state
schema. The safest product path is an experimental narrator mode behind a
setting, retaining the existing narrator as default until an in-game campaign
test confirms continuity and player-facing quality.
