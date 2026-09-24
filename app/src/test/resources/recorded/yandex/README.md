# Yandex AI Studio (Responses API) fixtures

Real, sanitized server responses captured from
`https://ai.api.cloud.yandex.net/v1/responses` with a Yandex Cloud
service-account API key on 2026-09-24. They are the replay corpus for
`YandexSseParserTest` / `YandexWireTest` — the contract they encode was verified
by live calls, NOT taken from published docs.

| File | What it captures |
|---|---|
| `plain.json` | Non-stream plain answer (`output[]` message item; no top-level `output_text`) |
| `plain.sse` | Streamed plain answer: `response.created` → … → `response.output_text.delta`(×N) → `response.output_item.done` → `response.completed` |
| `search.sse` | Streamed **server-executed web search**: `web_search_call` item + `response.output_text.delta`(×N, incl. the model's own source line) + `url_citation` annotations on the final message item |
| `func.sse` | Streamed client **function call**: `response.function_call_arguments.delta` → `.done` → `response.output_item.done` (`item.type:"function_call"`, `call_id:"get_weather"`, `arguments:"{\"city\":\"Казань\"}"`) |

## Sanitization contract

- Server responses ONLY. No credentials, no `Authorization`/`Api-Key` headers,
  and no key material of any kind occur in any file.
- The owner's real folder id (a 20-char `b1g…` value) was replaced everywhere
  with the placeholder `b1gxxxxxxxxxxxxxxxxx` (same length, 20 chars). Response
  ids and UUIDs are retained — they are not sensitive.
- Prompts are fixed, non-personal probe phrases (a greeting / a weather
  question) — no user data can enter a fixture.
- The stream has **no `[DONE]` sentinel**; it ends after `response.completed`.

## Regeneration

Live capture (local only, requires a Yandex Cloud API key):

```sh
API=https://ai.api.cloud.yandex.net/v1
FOLDER=<your-folder-id>
curl -sS -N "$API/responses" \
  -H "Authorization: Api-Key $YANDEX_CLOUD_API_SECRET" \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt://'"$FOLDER"'/aliceai-llm/latest","input":"Ответь: Привет, мир!",'\
'"stream":true,"max_output_tokens":32,"temperature":0.1}' > plain.sse
```

Then replace the real folder id with `b1gxxxxxxxxxxxxxxxxx` before committing.
The `GET $API/models` response lists every `gpt://<folder>/…` model and is how
the client discovers the folder at runtime.
