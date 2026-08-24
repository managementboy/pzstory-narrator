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
      "maxInputChars": 24000,
      "maxRequestBytes": 500000,
      "systemMode": "prepend_to_user",
      "streamUsage": false
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
| `kind` | Request and streaming protocol | `anthropic`, `gemini`, `openai-compatible` |
| `model` | Provider model id | Required; copy it exactly from the provider/server |
| `apiKey` | Credential sent only to that profile's endpoint | Required for Anthropic/Gemini; may be empty for loopback |
| `baseUrl` | API root, without `/chat/completions` | Required for OpenAI-compatible; optional Gemini override |
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
