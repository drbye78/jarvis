# Jarvis Voice Assistant — Architecture

> **Status: in active development (pre-1.0), version 0.2.0.**
> Target: Android 11 (API 30) / HarmonyOS 2.0+ (AOSP-based) — validated on Huawei MatePad SE 11
> Always WiFi · Always charging
> Default build targets Russian (wake word, ASR/TTS language, UI); providers are multi-lingual
> targetSdk 30 (appliance profile) with Android 14+ guards · compileSdk 34

## Data flow

```
Mic → AudioRecordSource → AudioPipeline (single producer, one copy per frame)
   ├─ HybridWakeWordDetector (engine-agnostic wake word: Porcupine OR Sherpa-ONNX; single actor, 320→512 re-chunk)
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
| `audio/` | Pipeline (single-copy invariant), ring buffer, `HybridWakeWordDetector` (engine-agnostic: Porcupine + Sherpa-ONNX; runtime-switchable engine via `reconfigure`/`reconfigureWakeWord`, thread-safe under a Mutex; `reconfigureMutex` serializes rebuilds; Sherpa loaded asset-relative), player (generations). |
| `session/` | Validated state machine; SessionManager orchestrating streaming turns; bounded tool loop. |
| `tools/` | ToolContract + registry (timeouts incl. per-tool override, error capture) + real implementations. |
| `media/` | External player control (MUSIC lane): gateway contracts over MediaSession/MediaKeys, `MusicAppCatalog` (which player to target), `MusicPlaybackOrchestrator` — pure strategy cascade with playback verification. Android adapter: `AndroidMediaGateway`. |
| `data/` | Room: messages (id-ordered, orphan-safe windowing) + alarms. |
| `service/` | Foreground service (permission gate, retryable init, watchdog semantics), boot receiver, ringing activity, notification listener. |
| `ui/` | Adapters for transcript and alarm lists. |

## Concurrency model

- **One microphone producer** — the only thread touching AudioRecord.
- **One wake-word actor** — engine-agnostic `process()` behind a Mutex, 512-sample re-chunking (rebuilds serialized by `reconfigureMutex`).
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
each tool execution has a 15 s default timeout — a tool may override it via
`ToolContract.timeoutMs` (playMusic uses 30 s: cold-starting a player and
verifying playback takes that long).

## Music lane (external player control)

`playMusic` orders an **installed player app** to search and play — Jarvis
never streams audio itself. Control goes through the documented
assistant→media-app path: with notification-listener access,
`MediaSessionManager.getActiveSessions()` +
`MediaController.TransportControls.playFromSearch()`
(the same API Google Assistant uses).

Whether a player implements `onPlayFromSearch` is up to the app, so the
cascade **verifies playback actually started** and degrades honestly:

1. **active_session** — target app has a live MediaSession → `playFromSearch`
   → verify (was-idle→playing, or title changed, or position near track
   start; baseline snapshotted BEFORE dispatch).
2. **cold_start** — no session: launch the app, poll ≤ 8 s for its session,
   `playFromSearch` → verify.
3. **deep_link** — open the app's search screen for the query
   (`yandexmusic://search?query=…`, fallback `music.yandex.ru/search/…`);
   reported as `search_opened` — the user taps the track; never claimed as
   success.

Transport commands (`controlPlayback`: play/pause/toggle/next/previous/
stop) target the app's live session, fall back to global media keys
(`dispatchMediaKeyEvent`, works without listener access). `getNowPlaying`
reads session metadata for «что играет?».

Target resolution (`MusicAppCatalog`): LLM hint pins the brand (яндекс/звук/
вк); else known packages in priority order (ru.yandex.music → com.yandex.music
→ zvooq → vk); else any launchable app with a music-looking label. The whole
cascade is pure Kotlin over gateway interfaces → fully JVM-tested
(`MusicOrchestratorTest`).

Ducking interplay: a Jarvis session pauses external music (existing duck
logic); after a track switch, unduck resumes whatever is current. The spoken
confirmation may overlap briefly with the just-started track — both use the
music stream; no transient audio-focus is requested yet (follow-up).

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
Porcupine 3.0.0 · Sherpa-ONNX 1.13.6 (bundled AAR + gigaspeech KWS model) · Material Components · compileSdk 34 · minSdk 24 · targetSdk 30

LLM endpoint is config-driven (`JarvisConfig.llmEndpoint`) rather than hardcoded.

## Security

- **Per-user credentials, no shared secrets.** Provider keys (Picovoice, Sber
  Salute, GigaChat) are entered in-app via **Settings** and stored in
  `EncryptedSharedPreferences` (Android Keystore). **Nothing secret is baked
  into `BuildConfig` or `local.properties`** — every install uses its owner's
  own credentials, so the APK is safe to distribute to colleagues.
- OAuth uses `Authorization: Basic base64(client_id:client_secret)` per
  Sber's spec; tokens are cached encrypted; secrets/tokens are never logged.
- HTTPS only (`usesCleartextTraffic=false`)
- R8 minification for release; rotating file logs (no tokens/logged secrets)
- `allowBackup=false` (Keystore key is device-bound; a restore can't decrypt
  the creds, so the user simply re-enters them)
- WakeLock released on power disconnect; notification listener reads nothing

## Tests

JVM unit suite: wire DTOs, SSE parser, state machine,
sentence splitter, conversation windowing, alarm times, tool registry,
and session orchestration with fakes (including the tool-loop wire-format
regression). Run with `./gradlew testDebugUnitTest`.
