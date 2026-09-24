# GigaChat native (/v2) fixtures

Real, sanitized responses captured from `https://api.giga.chat/v2/chat/completions`
with a `GIGACHAT_API_PERS` token on 2026-09-24. They are the replay corpus for
`GigaChatSseParserTest` / `GigaChatNativeWireTest` — the contract they encode was
verified by live calls, NOT taken from the published docs (which describe the
legacy `/v1` contract and never mention `web_search`).

| File | What it captures |
|---|---|
| `plain.json` | Non-stream plain answer (`messages[]` envelope, no tools) |
| `search.json` | Non-stream **web_search** turn: `inline_data.sources` with url+title |
| `func.json` | Client-function call: `content:[{function_call:{name,arguments}}]` + `finish_reason:"function_call"` |
| `plain.sse` | Streamed plain answer (named `event:` lines + `data: [DONE]`) |
| `search.sse` | Streamed web_search: `response.tool.in_progress` → `response.tool.completed` → `response.message.delta` (sources, then text) → `response.message.done` |

## Sanitization contract

- Server responses ONLY. No credentials, no `Authorization` headers, no tokens
  (verified: the OAuth token does not occur in any file).
- The model build suffix (`:2.0.30.01`) was stripped from `"model"` values so a
  re-record is a clean diff and the fixture is not pinned to a moving build.
- Prompts are fixed, non-personal probe phrases (currency rate / "работает?" /
  a weather question) — no user data can enter a fixture.

## Regeneration

Live capture (local only, requires GigaChat credentials):

```sh
T=$(curl -sS --cacert russian_sub.pem -X POST \
  'https://ngw.devices.sberbank.ru:9443/api/v2/oauth' \
  -H "Authorization: Basic $GIGACHAT_API_KEY" -H "RqUID: $(uuidgen)" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "scope=$GIGACHAT_API_SCOPE" | jq -r .access_token)

API=https://api.giga.chat/v2/chat/completions
# ... POST a body from the table above with "stream":true for the .sse files
```

The `--cacert` must be the Минцифры Russian Trusted Root/Sub CA bundle: the
system store rejects `api.giga.chat` (its leaf chains to `Russian Trusted Sub CA`).
In the app this is handled by `util/SberTrust.kt` (`giga.chat` is in the
host-scoped allowlist); on the command line use the PEM extracted from that file.
