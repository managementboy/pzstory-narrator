# Gemini 3.5 Flash partial PZStory evaluation

Date: 2026-08-24  
PZStory checkout: `8cff231` (`2.0.0-alpha.8`, bridge API 11)  
Provider/model: native Gemini API / `gemini-3.5-flash`  
Credential profile: `gemini` (the credential itself was never persisted)

## Result

Gemini 3.5 Flash is markedly better than the local models at PZStory's output
contract, but the tested prompt variants are **still not grounded enough for
automatic campaign writing**.

All twelve completed replies passed the terminal heading/bullet parser. None
passed the deterministic closed-world grounding check. Manual review confirmed
that the zero-grounded result is real and also found unsupported history and
actions that the lexical scorer does not yet detect.

| Strategy | Cases | Structure-valid | Grounded | Mean score |
|---|---:|---:|---:|---:|
| Production prompt | 6 | 6 | 0 | 60.0 |
| Compact/cold with literal ledger | 6 | 6 | 0 | 77.5 |

The planned compact/cold repair row was not completed. The active Gemini
project reached its free-tier limit of 20 requests per day while the paired
repair calls were running. The separately configured `gemini-openai` profile
uses a different credential, but Google rejected that credential as invalid.
No billing or credential settings were changed.

## What improved

- Gemini obeyed the exact response structure in every completed case, even
  with the full production prompt. Qwen 2.5 3B had failed all six production
  prompt structures and needed the compact literal contract to reach 6/6.
- The compact grounding ledger raised Gemini's mean deterministic score from
  60.0 to 77.5. On the same six scenes, Qwen's compact/cold score was 62.5.
- Replies were coherent, correctly named and generally focused on salient
  state. The model reported no hidden thought tokens with thinking budget zero.

## Grounding failures

The production prompt caused broad plausible-scene completion. Examples
included an invented drifting biography and family situation, a radio cordon,
a vehicle objective, a refrigerator, fences, roads and additional house
context. It also repeatedly converted inventory into a current action by
placing the hammer, bandage or knife in the survivor's hand.

The compact ledger reduced the number of lexical violations but did not solve
the behavior. It still invented biography (waking to a dead world and past
ranger duties), unplayed actions (gripping tools, looking into a mirror or at
objects), larger locations such as a house or forest, and intentions built on
those new facts. The first-page premise was especially prone to manufacturing
backstory even though the prompt explicitly forbids it.

Several deterministic scores are optimistic. For example, the compact road
reply scored 85 while inventing past ranger work, gripping the axe and looking
down the road in addition to the detected `forest`. Acceptance therefore still
requires raw-reply review; the scorer is a failure detector, not proof of
grounding.

## Usage and operational notes

The twelve saved cases contain 47,847 input tokens and 2,614 output tokens
(50,461 total). At the published standard paid-tier prices on the test date,
those saved tokens would be about USD 0.10; this project was on the free tier.
Wall-clock latency was usually about two to three seconds, but one saved case
includes quota waiting and must not be treated as model latency.

Java on this PC needed the Windows trusted-root store because the bundled JDK
did not recognize the machine's TLS interception chain. Certificate validation
remained enabled. The harness now supports native Gemini, environment-only
credentials, token accounting, resumable cases, quota-aware retries and
proactive request spacing.

Only the synthetic benchmark scenes were sent. No Project Zomboid save,
campaign, player notes or API credential was written into the artifacts.

## Recommendation

Do not switch the production narrator to Gemini on the strength of these
prompts alone. Gemini is the strongest model tested so far for syntax and
general instruction following, but prose generation still turns sparse state
into plausible fiction.

The next useful experiment is the same one indicated by the Qwen results:
force a first stage to select explicit fact IDs and permitted action IDs,
validate that selection in Java, and generate prose from only the approved
facts. The repair variant can be finished after quota reset, but prompt-only
repair is unlikely to provide the hard grounding guarantee the mod needs.

## Artifacts

- Raw completed records: `runs/gemini35-flash-initial-20260824/results.jsonl`
- Generated summary: `runs/gemini35-flash-initial-20260824/summary.md`
- Run metadata: `runs/gemini35-flash-initial-20260824/metadata.json`
- Synthetic corpus: `scenes.json`
- Harness: `../LocalModelBenchmark.java`
