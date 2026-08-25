# Provider profiles

PZStory reads `profiles.json` from `Zomboid/pzstory/`, outside both the mod and
the save. The file can contain several providers; the active one is selectable
on the device without exposing its key.

Start from this shape and replace the model ids and keys with ones available to
your accounts or local server:

```json
{
  "activeProfile": "local",
  "profiles": {
    "claude": {
      "kind": "anthropic",
      "model": "YOUR-ANTHROPIC-MODEL-ID",
      "apiKey": "sk-ant-REPLACE-ME",
      "maxTokens": 1800,
      "maxInputChars": 300000,
      "maxRequestBytes": 1000000,
      "thinkingTokens": 0,
      "cacheTtl": "1h"
    },
    "gemini": {
      "kind": "gemini",
      "model": "YOUR-GEMINI-MODEL-ID",
      "apiKey": "REPLACE-ME",
      "maxTokens": 1800,
      "maxInputChars": 300000,
      "maxRequestBytes": 1000000,
      "thinkingTokens": 0
    },
    "openai": {
      "kind": "openai-compatible",
      "model": "YOUR-OPENAI-MODEL-ID",
      "apiKey": "sk-REPLACE-ME",
      "baseUrl": "https://api.openai.com/v1",
      "maxTokens": 1800,
      "maxInputChars": 300000,
      "maxRequestBytes": 1000000,
      "openAiTokenField": "max_tokens",
      "streamUsage": true
    },
    "local": {
      "kind": "openai-compatible",
      "model": "YOUR-OLLAMA-MODEL",
      "apiKey": "",
      "baseUrl": "http://127.0.0.1:11434/v1",
      "maxTokens": 1800,
      "maxInputChars": 48000,
      "maxRequestBytes": 500000,
      "systemMode": "prepend_to_user",
      "streamUsage": false
    },
    "lm-studio-stateful": {
      "kind": "lmstudio-stateful",
      "model": "pzstory-qwen2.5-3b",
      "apiKey": "",
      "baseUrl": "http://127.0.0.1:1234",
      "maxTokens": 1800,
      "maxInputChars": 48000,
      "maxRequestBytes": 500000,
      "systemMode": "native"
    }
  }
}
```

JSON does not permit comments or trailing commas. If an edit is invalid,
PZStory keeps the last valid configuration in memory and reports the error on
the device and in `console.txt`.

## Fields

| Field | Meaning | Default / allowed values |
|---|---|---|
| `kind` | Request and streaming protocol | `anthropic`, `gemini`, `openai-compatible`, `lmstudio-stateful` |
| `model` | Provider model id | Required; copy it exactly from the provider/server |
| `apiKey` | Credential sent only to that profile's endpoint | Required for Anthropic/Gemini; may be empty for loopback |
| `baseUrl` | API root, without the final request path | Required for OpenAI-compatible and LM Studio stateful; optional Gemini override |
| `maxTokens` | Visible-output ceiling | 256–32,000; 2,000 by default |
| `maxInputChars` | System + history + current-turn character ceiling | 24,000–1,000,000; 300,000 by default |
| `maxRequestBytes` | Final UTF-8 JSON request-body ceiling | 131,072–2,000,000; 1,000,000 by default |
| `thinkingTokens` | Explicit reasoning allowance | 0–24,000; off by default; used by Anthropic and Gemini |
| `systemMode` | How the narrator charter is delivered | `native`, `prepend_to_user`, `both` |
| `cacheTtl` | Anthropic prompt-cache policy | `1h`, `5m`, `off` |
| `openAiTokenField` | Output-cap property for compatible APIs | `max_tokens` or `max_completion_tokens` |
| `streamUsage` | Request usage figures in the final OpenAI stream event | `false` by default; enable only if the server supports it |

`maxInputChars` is checked before encoding and `maxRequestBytes` afterwards.
Both limits matter: non-ASCII text and JSON escaping can make the request body
larger than its source prompt.

## Stateful LM Studio

`lmstudio-stateful` uses LM Studio's native `/api/v1/chat` endpoint. The first
turn is an invisible, pre-page Knox Event chronology seed started after the
player chooses a story type. It gives the narrator dated dramatic context but
creates no page, canon, task, premise, or survivor memory. The first accepted
page branches from that private setup turn; later requests send the new live
turn with `previous_response_id` instead of repeating the fixed charter,
chronology, and archive. PZStory stores that id inside the save and scopes it to
the exact profile, model, and narrator protocol. Classic prose can therefore
never continue from Safe mode's constrained planner conversation. Changing
narrator mode before page one starts a separate seeded chain for that mode.

The chronology is date-gated. Future events are available to the narrator for
dramatic restraint but are forbidden as present facts before their game day,
and narrator knowledge is never automatically survivor knowledge. The
infection's origin remains unconfirmed.

The candidate id is committed in the same disk transaction as the validated
page. STOP, malformed output, a changed save, or a failed campaign write never
advances the conversation checkpoint. Writing a page with another provider
clears the old checkpoint because that page is absent from its conversation.
If a Classic reply is structurally malformed, PZStory permits one corrective
turn chained from that rejected draft. Only the corrected response id is
committed, and only if the corrected page validates and saves atomically.

LM Studio still has a finite model context. Stateful transport removes repeated
request payloads and preserves conversational continuity; it does not make the
context infinite or replace PZStory's authoritative campaign store.

`thinkingTokens` is never silently added. Some Anthropic models require a
minimum reasoning budget when thinking is enabled; use a provider-supported
value or leave it at zero. OpenAI-compatible reasoning models differ: when a
model requires `max_completion_tokens`, select that field and treat
`maxTokens` as the provider's total completion cap.

## Endpoint rules

- Remote endpoints must use HTTPS.
- Plain HTTP is accepted only for literal loopback hosts: `localhost`,
  `127.0.0.1`, or `[::1]`.
- User information, query strings, fragments, malformed ports and deceptive
  hostnames such as `localhost.example.com` are rejected.
- Redirects are never followed, because doing so could forward both the key
  and the game-state request to a different origin.

If a local server rejects the request shape, first keep `streamUsage` false,
try `systemMode: "prepend_to_user"`, and confirm whether it expects
`max_tokens` or `max_completion_tokens`.

## What leaves the game

The request contains the narrator charter, relevant sandbox rules, campaign
history and canon, player notes, and a minimised live-state block. The state
projection removes the account username, exact map coordinates, engine ids,
reader diagnostics, raw vitals/stat telemetry, exact skill XP and exact
temperature. It retains facts needed for prose, including the survivor's name
and pronouns, visible surroundings, inventory, injuries, moodles and broad
skill bands. The change summary is derived locally from the raw snapshots and
uses broad elapsed-time, movement, skill-progress and kill bands rather than
forwarding their exact counters.

Use a loopback profile when none of this should leave the computer. The F9
development probe described in `dev/README.md` prints the exact live-state
projection for inspection; it does not print keys.

## Experimental local Stheno setup

`Llama-3.1-8B-Stheno-v3.4` is a creative-writing and roleplay fine-tune that
can run locally through Ollama. On an 8 GB graphics card, use its
[Q4_K_M GGUF](https://huggingface.co/bartowski/Llama-3.1-8B-Stheno-v3.4-GGUF/blob/main/Llama-3.1-8B-Stheno-v3.4-Q4_K_M.gguf).
Install the model and create PZStory's 16K-context alias:

```powershell
# First place Llama-3.1-8B-Stheno-v3.4-Q4_K_M.gguf in ollama/.
ollama create pzstory-stheno -f ollama/PZStory-Stheno.Modelfile
```

Use the GGUF file from the linked Bartowski/Hugging Face conversion. Import it
directly: the similarly named community Ollama package uses a generic roleplay
wrapper rather than Llama 3.1's chat template. The supplied Modelfile restores
the proper system/user message boundaries and does not add a roleplay system
message of its own.

Then use `pzstory-stheno:latest` as the local profile's `model`. Keep
`maxInputChars` at 48000 and `systemMode` at `native`; PZStory's strict terminal
reply validator protects campaign state when a small model omits or reorders a
required section.

Stheno is technically compatible, but it is not currently approved as the
default PZStory narrator. In the synthetic acceptance scene it repeatedly
invented unseen furnishings, past events and survivor actions even after a
clean GGUF import with the correct chat template and low temperature. Keep a
known-good profile available and treat Stheno as an experimental prose model
until it passes the acceptance check repeatedly. Structural failures are
discarded, but no parser can reliably identify every plausible-sounding
invented world detail.

Developers can run `dev/LocalModelAcceptance.java` against an installed local
model. The check uses PZStory's production charter, prompt builder and terminal
reply validator with a synthetic state; it never reads a save or an API key.

The PZStory source remains CC0, but the Stheno weights are separately licensed
CC BY-NC 4.0. They are not included with the mod. Players install the model
themselves, and commercial users must choose a model whose licence permits
their intended use.
