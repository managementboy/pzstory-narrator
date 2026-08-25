# Qwen validated-catalog holdout quality review

Date: 2026-08-25  
Frozen run: `runs/qwen25-3b-validated-holdout-final-20260825`  
Model: `qwen2.5:3b-instruct`

## Verdict

The validated-catalog pipeline is **closed-world safe but not yet suitable as
the player-facing narrator**. Its safety architecture generalizes; its surface
writing does not yet provide the atmosphere, character voice or variation
expected from PZStory.

Machine results were 9/9 structurally valid, 7/9 grounded and 95.6 mean score.
Manual review found that both grounding failures were scorer false positives:
the state-provided occupation `Fire Officer` triggered the physical vocabulary
term `fire`. All nine replies were actually closed-world safe. One safe reply
missed its expected narrative focus because nested bag inventory was not
extracted.

## Quality assessment

| Dimension | Rating | Finding |
|---|---:|---|
| Factual safety | 5/5 | No unsupported event, action or physical fact in manual review. |
| Structural reliability | 5/5 | Every terminal reply parsed correctly. |
| Readability | 3/5 | Clear and grammatical, but often reads like a state report. |
| Scene focus | 2/5 | Visible facts survive, but important changes and threats can become generic. |
| Atmosphere | 2/5 | A restrained mood is present, but it is identical across scenes. |
| Character voice | 1/5 | Occupation and traits are repeated rather than expressed as perspective. |
| Variety | 1/5 | Titles, mood, TODO and filler are overwhelmingly repeated. |
| Overall player-facing quality | 2/5 | Safe prototype, not immersive narrative. |

The pages were narrowly clustered at 66-72 words, averaging 68.3. Qwen chose
`watchful` for all nine scenes and `patience` for all nine TODOs. Seven titles
were `The Quiet Present`; the other two were `A Narrow Certainty`.

The following lines appeared in every reply:

- `The moment feels watchful and tense.`
- `The moment permits no assumptions beyond this evidence.`
- `Uncertainty remains, but it receives no invented shape.`
- `let patience govern the next decision`

Two more filler sentences appeared in seven or eight replies. Sparse scenes
could even repeat the same filler twice within one page.

## Important misses

- The pursuit scene reduced several approaching zombies to `A completed change
  is recorded in the current evidence`, removing the scene's actual urgency.
- The bag-transfer scene omitted both bags and the hammer because the generic
  catalog handles string inventories but not nested container objects.
- The lit-campfire and car-exit changes were also represented only as generic
  completion notices.
- The first-page premise is safe but openly discusses `the available record`
  and `current evidence`, which breaks immersion.
- `Present and visible:` and `Time survived is` expose the data structure rather
  than narrating the survivor's experience.

## Recommended next design

Keep the validated fact boundary. Do not return to unrestricted prose. Improve
the layer after validation:

1. Normalize all production state shapes, including nested containers, threats
   and completed-change types, into typed facts.
2. Give critical changes and immediate threats mandatory narrative priority.
3. Build several safe sentence forms for each typed fact instead of emitting
   data labels such as `Present and visible`.
4. Let Qwen select sentence-form, mood, focus and ordering IDs; keep all emitted
   words inside validated phrase banks and state-backed slots.
5. Add controlled interior-response phrases keyed by occupation, traits and
   situation so characters feel different without invented biography.
6. Test these improvements on new development fixtures modelled on the failure
   classes. Preserve this holdout result and do not tune or rerun against it.

Before production integration, require continued perfect factual safety plus a
manual writing-quality rating of at least 3.5/5, meaningful coverage of every
critical change, and substantially lower phrase repetition.
