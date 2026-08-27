# Jarvis Voice Assistant — Architecture (v4)

> Target: Huawei MatePad SE 11 · HarmonyOS 2.0 (AOSP 10/11) · Kirin 710A
> Always WiFi · Always charging · Russian language (ru-RU)
> targetSdk 30 (appliance profile) with Android 14+ guards · compileSdk 34

## Data flow

```
Mic → AudioRecordSource → AudioPipeline (single producer, one copy per frame)
  ├─ PorcupineDetector (wake word, single actor, 320→512 re-chunk)
   └─ SessionManager (delegates each turn to TurnRunner)
        ├─ SberStreamingAsr (bidi gRPC; live audio up, partials/EOU down)
       ├─ ConversationManager (Room; 20-msg window, tool-pair-safe)
       ├─ LlmClient (GigaChat | OpenAI-compatible; SSE; wire DTOs)
       │    └─ ToolRegistry → alarms/timers · weather · 8 device tools
       └─ SaluteSpeechTts (gRPC, cancellable Context, deadline)
            └─ StreamingAudioTrackPlayer (single actor, generation-based flush)
```

## Layers

| Package | Responsibility |
|---------|----------------|
| `model/` | Pure domain types (Message, ToolCall, ChatRequest, LlmChunk, states). No serialization annotations. |
| `wire/` | OpenAI-protocol DTOs with `@SerialName` snake_case + mappers. The only code that shapes request JSON. |
| `llm/` | `SseParser` (pure), `SseLlmClient` (shared SSE transport with correct cancellation), GigaChat / OpenAI-compatible profiles, `TokenManager` (mutex-serialized OAuth refresh). |
| `speech/asr/` | `StreamingAsrClient` / `AsrStream` — bidi streaming ASR; server-side EOU. |
| `speech/tts/` | `TtsClient` (SaluteSpeech, cancellable + deadline) and `TtsPlayer` contract. |
| `audio/` | Pipeline (single-copy invariant), ring buffer, Porcupine detector (error-surfacing), player (generations). |
| `session/` | Validated state machine; SessionManager orchestrating streaming turns; bounded tool loop. |
| `tools/` | ToolContract + registry (timeouts, error capture) + real implementations. |
| `data/` | Room: messages (id-ordered, orphan-safe windowing) + alarms. |
| `service/` | Foreground service (permission gate, retryable init, watchdog semantics), boot receiver, ringing activity, notification listener. |
| `ui/` | Adapters for transcript and alarm lists. |

## Concurrency model

- **One microphone producer** — the only thread touching AudioRecord.
- **One Porcupine actor** — `process()` behind a Mutex, 512-sample re-chunking.
- **One AudioTrack actor** — sentences serialized through a Channel; a
  generation counter makes `flush()` cancel current + queued playback.
- **Session children** — every session coroutine is a child of `sessionJob`;
  barge-in cancels the whole tree, and each transport cancels its call
  (OkHttp `call.cancel()`, gRPC cancellable `Context`).
- **State** — a single `StateFlow` per session state machine, observed by the
  notification, ducking and UI.

## Barge-in

Wake word is accepted in **every** state (IDLE, LISTENING, THINKING, SPEAKING).
Detection flows through `Flow<Detection>.gatedBy(BargeInPolicy.from(config), stateMachine.state)`:
in SPEAKING it cancels the active turn; in the other states it is still accepted so
the user can barge in at any time. `BargeInPolicy.postAcceptCooldownMs` (default 600 ms)
debounces self-retrigger from the wake word's trailing audio. On barge-in:
`player.flush()` (generation bump kills current + queued TTS) → `sessionJob.cancel()`
(kills ASR feeder, LLM SSE call, TTS contexts via structured cancellation) → new
session. `CancelTimerTool` cancels a snoozed alarm's pending one-shot timer so a
snooze isn't interrupted. A superseded (barge-in'd) turn discards its partial
tool-history writes to keep the conversation coherent.

## Tool protocol

OpenAI-compatible, serialized through the wire layer: assistant
`tool_calls` (with ids) → tool results with `tool_call_id`. History windowing
keeps assistant+tool pairs together and never leaves a leading orphan tool
message. The tool loop is iterative and bounded (`maxToolPasses = 5`);
each tool execution has a 15 s timeout.

## Alarms

`AlarmManager.setAlarmClock` + Room persistence + full-screen ringing
activity (showWhenLocked/turnScreenOn), looping alarm sound + vibration,
Dismiss/Snooze, 5-minute auto-timeout, daily re-arm, boot re-scheduling.
Timer tool uses `setExactAndAllowWhileIdle` one-shots.

## Lifecycle semantics

- **User stop** → `userStopped=true`, watchdog alarm cancelled in
  `onDestroy` → stays stopped.
- **System kill** → no `onDestroy` → watchdog survives → service revives.
- **Boot / package replace** → alarms re-armed from DB, `userStopped`
  cleared, service starts.
- **Init failure** (e.g. missing permission) → `initialized` stays false,
  actionable notification shown, watchdog retries.

## Build

Gradle 8.14.2 · AGP 8.11.1 · Kotlin 2.2.21 · KSP 2.2.21-2.0.5 · Room 2.8.4
gRPC 1.83.1 · protobuf-gradle-plugin 0.10.0 · OkHttp 4.12.0
Porcupine 3.0.0 · Material Components · compileSdk 34 · minSdk 24 · targetSdk 30

LLM endpoint is config-driven (`JarvisConfig.llmEndpoint`) rather than hardcoded.

## Security

- OAuth tokens + provider API keys in EncryptedSharedPreferences
- HTTPS only (`usesCleartextTraffic=false`)
- R8 minification for release; rotating file logs (no tokens logged)
- `allowBackup=false`
- WakeLock released on power disconnect; notification listener reads nothing

## Tests

JVM unit suite (see PLAN.md §2.9): wire DTOs, SSE parser, state machine,
sentence splitter, conversation windowing, alarm times, tool registry,
and session orchestration with fakes (including the tool-loop wire-format
regression). Run with `./gradlew testDebugUnitTest`.
