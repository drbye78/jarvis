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
| `audio/` | Pipeline (single-copy invariant), ring buffer, `HybridWakeWordDetector` (engine-agnostic: Porcupine + Sherpa-ONNX; runtime-switchable engine via `reconfigure`/`reconfigureWakeWord`, thread-safe under a Mutex; `reconfigureMutex` serializes rebuilds; Sherpa loaded asset-relative), player (generations), and the Phase-5 etiquette pair: `AssistantAudioFocus` (duck-during-TTS state machine + `AndroidAudioFocusAdapter`) and `SpeechFeedback` (spoken cascade progress). |
| `session/` | Validated state machine; SessionManager orchestrating streaming turns; bounded tool loop. |
| `tools/` | ToolContract + registry (timeouts incl. per-tool override, error capture) + real implementations. |
| `media/` | External player control (MUSIC lane): gateway contracts over MediaSession/MediaKeys, `MusicAppCatalog` (which player to target), `MusicPlaybackOrchestrator` — pure capability-gated strategy cascade (structured playFromSearch, MediaBrowser search/token lane, query-aware verification) with rich transport; `MediaBrowserGateway` + `AndroidMediaBrowserGateway` (bind/search/children); `MediaCapabilities`/`VoiceQuery`/`MediaDiagnostics` (pure models). Android adapters: `AndroidMediaGateway` (compat-wrapped controllers), `AndroidMediaBrowserGateway`. |
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
never streams audio itself. The lane is **capability-driven**: vendor docs
are hints, `PlaybackState.getActions()` / `getRatingType()` / the
MediaBrowser connection result are ground truth, probed at runtime and
logged under the `MusicDiag` tag (`adb logcat -s MusicDiag` is the
first-line troubleshooting step; the dump answers the per-build questions
no static audit can).

### playMusic cascade (v2)

Every strategy verifies that what is playing **matches the request**
(`VoiceQuery` normalized token-overlap scoring + position-reset rule —
a player ignoring the command while the old track plays early can never
produce a confident lie). Structured requests (artist/album/playlist/
genre slots) are dispatched as the Assistant extras contract
(`EXTRA_MEDIA_FOCUS` entry types + slot extras); the flat text rides
along for extras-ignoring players.

In order:

1. **active_session** — target app has a live MediaSession with the
   `PLAY_FROM_SEARCH` bit → structured `playFromSearch` → verify.
2. **browser_media_id (S0)** — bind the player's MediaBrowserService
   (permission-free, BAL-immune), `onSearch()` results scored by the same
   matcher, best hit plays deterministically via `playFromMediaId`.
3. **browser_cold_start (S2)** — the bound service's session token
   dispatches `playFromSearch`; works even when the player refuses
   browsing (empty root still yields the token). Runs BEFORE any
   activity start — Android 10+ silently blocks background activity
   launches, and a bind is not an activity.
4. **cold_start** — launch the app, poll ≤ 8 s for its session,
   `playFromSearch` → verify (BAL caveat: reliable when Jarvis's UI is
   visible; outcomes phrased as attempts otherwise).
5. **legacy_intent** — the pre-session
   `android.media.action.MEDIA_PLAY_FROM_SEARCH` activity intent with
   `SearchManager.QUERY` + the same structured extras, verified by
   strong score only (no baseline exists).
6. **deep_link** — open the app's search screen
   (`yandexmusic://search?query=…` with `%20` encoding, fallback
   `music.yandex.ru/search/…`); reported as `search_opened` — the user
   taps the track; never claimed as success.
7. **launch_only** — honest «открыл приложение, запусти вручную».

One browser bind per attempt, disconnected in `finally` — no leaks.
Sessions without listener access still reach the browser lane (the token
path needs no permission); everything else degrades to the deep link
with an instructive error.

### Rich transport (controlPlayback, 12 actions)

`play|pause|toggle|next|previous|stop|seek|restart|like|repeat|shuffle|
speed` — every action gated by the session's capability bits (plus the
heart-rating type for `like`, plus the API-29 guard for `speed` —
minSdk is 24); unsupported actions get an honest Russian refusal naming
the limitation, never a silent no-op. The media-key fallback (works
without listener access) only covers the basic six — a media key cannot
seek/like/repeat. Session selection: named app → any playing session →
most recent; a named app with no live session is an instructive miss
rather than a command to a random player.

### Library lane (Tier 3)

`listPlaylists` (browser root children) and `searchLibrary` (browser
`onSearch`) return up to 10 items with their `mediaId`; a follow-up
`playMusic(mediaId, title)` plays the chosen item deterministically.
mediaIds are short-lived service identifiers — documented as
"use immediately".

Target resolution (`MusicAppCatalog`), in priority order: LLM hint pins
the brand (яндекс/звук/вк — per-request, always wins); the user's
preferred default player from Settings («Музыка» card, read lazily by
the composition root so changes apply without a restart —
uninstalled preferences degrade honestly to auto); else known packages
in priority order (ru.yandex.music → com.yandex.music → zvooq → vk);
else any launchable app with a music-looking label. The whole cascade
is pure Kotlin over gateway interfaces → fully JVM-tested
(`MusicOrchestratorTest`, `MusicAppCatalogTest`,
`MediaBrowserGatewayTest`, `VoiceQueryTest`, `TransportToolsTest`).

### Audio etiquette

Assistant TTS requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for the
duration of each spoken generation (first sentence → last drained
sentence; barge-in flush abandons immediately) — compliant players duck
to ~20% for the confirmation. Spoken progress («Секунду…» on a
predicted-long cascade, «Открываю плеер…» before launches) plays through
the same serialized player, so barge-in kills stale phrases.
`pauseMusicOnWake` (config, default off) pauses external audio at
session start for a clean listening window — no auto-resume; the user
says «продолжи». There is no acoustic echo cancellation: the wake word
competes with speaker output, and loud music can mask it — pause-on-wake
is the mitigation.

## UI design system

Theme: Material 3 (`Theme.Material3.DayNight.NoActionBar`, material
1.12) over a teal/amber token set — `values/colors.xml` +
`values-night/colors.xml` (24 day/night twins), status-bar follows the
mode via `values(-night)/bools.xml`, text appearances in `styles.xml`
(AppTitle / ScreenTitle / SectionHeader / Status / Hint). No hardcoded
color hex outside the token files: bubbles/pills are shape drawables
referencing `?attr/*`, so day/night is automatic everywhere.

Screens: home is the voice orb (`VoiceOrbView` — custom Canvas view,
four cheap animators: idle-breathe / listening-ripple / thinking-arcs /
speaking-glow, muted-flat; animators cancelled on detach) + a chat
transcript (`TranscriptAdapter` on `ListAdapter`/DiffUtil, system
prompt filtered, tool traffic as compact pills, auto-scroll on insert)
+ a control bar (mic mute / start-stop). The transcript owns its
scroll; the column is capped to 840dp on wide screens. Onboarding is a
declarative status-row list (`PermRow` data) with start gated on the
mandatory rows. Settings gained the «Музыка» card
(`preferredMusicPlayer`), alarms list/ringing follow the same tokens.

There is no XML-inflated custom-styled programmatic widget: row
controls in onboarding are framework TextViews with theme ripples
(programmatic MaterialButtons cannot take styles after construction).

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
