# Getting an API key for PZStory — Anthropic (Claude)

PZStory writes your chapters by sending the current state of your game to a
language model and streaming the reply onto the page. The mod does not include
a model and does not talk to any server of ours. **You bring your own key**,
the mod talks directly to the provider you choose, and nothing passes through
anyone else.

This file covers Anthropic. Other providers (OpenAI, Google Gemini, and any
local model served through an OpenAI-compatible API such as Ollama or
LM Studio) are configured the same way — see `PROVIDERS.md`.

---

## 1. What this costs

Pay-as-you-go, per token, no subscription. A single chapter sends the rules,
your campaign so far and the current game state, and gets back roughly 300
words.

Very roughly, per page:

| Model | API id | Approx. cost per page |
|---|---|---|
| Claude Haiku 4.5 | `claude-haiku-4-5-20251001` | under 1 cent |
| Claude Sonnet 5 | `claude-sonnet-5` | 1–3 cents |
| Claude Opus 5 | `claude-opus-5` | 4–8 cents |

Treat those as order-of-magnitude, not a quote — the real number depends on how
long your campaign has run. Two things bring it down a lot: the stable parts of
the prompt are cached between pages, and you only pay when you actually ask for
a page. **Sonnet is the sensible default.** Start there.

If you would rather spend nothing at all, skip this file and run a local model
instead. PZStory supports that, and it costs only your GPU.

---

## 2. Create the key

1. Go to **[platform.claude.com](https://platform.claude.com)** and sign in, or
   create an account. This is the developer console — it is a *separate
   account system* from the Claude chat app and from any Claude subscription
   you may already pay for. A Claude Pro or Max plan does **not** give you API
   access, and API spend is billed separately.
2. Open **Account Settings → API keys**
   ([platform.claude.com/settings/keys](https://platform.claude.com/settings/keys)).
3. Click **Create key**.
   - **Name** it for what it does: `pzstory`. If it ever misbehaves you want to
     know which key to revoke.
   - **Workspace**: if you are offered one, make a workspace called `pzstory`.
     Spend limits are set per workspace, which is the easiest way to make sure
     a runaway loop can never cost more than you intended.
   - **Expiry**: pick one. A key that expires in 90 days is a key that cannot
     leak forever.
4. **Copy the key immediately.** It is shown once. It looks like
   `sk-ant-api03-…`. If you lose it, delete it and make another — there is no
   way to read it back.
5. Visit **Billing** and add a payment method or buy credits. You may get a
   small amount of free usage; when it runs out, calls simply start failing
   with a `credit balance is too low` error rather than doing anything
   surprising.

Optional but recommended: on the **Limits** page, set a monthly spend cap.

---

## 3. Tell PZStory about the key

Create this file (make the folder if it does not exist):

```
C:\Users\<your-windows-username>\Zomboid\pzstory\profiles.json
```

On Linux and macOS the same folder lives under `~/Zomboid/pzstory/`.

```json
{
  "activeProfile": "claude",
  "profiles": {
    "claude": {
      "kind": "anthropic",
      "model": "claude-sonnet-5",
      "apiKey": "sk-ant-api03-REPLACE-ME",
      "maxTokens": 1200,
      "maxInputChars": 300000,
      "maxRequestBytes": 1000000
    }
  }
}
```

`maxTokens` caps visible output. Input is also billable and a campaign grows
for as long as its save exists, so `maxInputChars` limits the system prompt,
live snapshot and retained story history together. `maxRequestBytes` is the
final UTF-8 JSON-body ceiling after provider-specific encoding. The defaults
are intentionally generous; raise either only when the chosen model's context
window and the account's spending limit can support it.

Save it, start Project Zomboid, and pick the profile in
**Options → Mods → PZStory**. The in-game menu lists profile *names* only — the
key itself is never displayed in game, so it cannot end up in a screenshot or a
stream.

### Why the key lives there and not in the mod folder

`Zomboid\pzstory\` is outside the mod directory on purpose. Anything inside
`Zomboid\mods\PZStory\` can be swept up by a Workshop upload, a mod backup or a
git commit. Keeping the key one level out means the mod folder stays safe to
share and safe to publish.

---

## 4. Keeping the key safe

- **Never paste it into a screenshot, a stream, a Discord message or a bug
  report.** A key is a password that spends your money.
- **Never put it in the mod folder** or anywhere inside a git repository. If
  you fork PZStory, check that `profiles.json` is in `.gitignore`.
- **If it leaks, revoke it.** Delete the key in the console and create a new
  one; the old one stops working instantly. Revoking is free and takes ten
  seconds — never "wait and see".
- One key per purpose. A key named `pzstory` that you can delete without
  breaking anything else is worth the extra thirty seconds.

---

## 5. When it does not work

| What you see | What it usually means |
|---|---|
| `401` / `authentication_error` | Key is wrong, was revoked, or has expired. Make a new one. |
| `400 credit balance is too low` | Add credits or a payment method in Billing. |
| `429` / `rate_limit_error` | Too many requests too quickly. Wait, or raise limits in the console. |
| `529` / `overloaded_error` | Anthropic's side is busy. Retry. |
| The book opens but stays blank | The mod cannot reach the network at all — check a firewall before blaming the key. |

PZStory logs every failure to `Zomboid\console.txt` with the tag `[PZStory]`,
**with the key redacted**. That log is safe to paste into a bug report; the
`profiles.json` file is not.

---

## 6. Technical detail (only if you are curious)

The mod calls `POST https://api.anthropic.com/v1/messages` with the headers
`x-api-key`, `anthropic-version: 2023-06-01` and `content-type:
application/json`, and sets `"stream": true`. The reply arrives as server-sent
events; the text you watch appear on the page is the `text_delta` field of each
`content_block_delta` event, and the page is finished when `message_stop`
arrives.

No telemetry, no analytics, no third-party server. The only outbound
connection PZStory makes is to the provider named in your active profile.
