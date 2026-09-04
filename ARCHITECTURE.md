# Jarvis Voice Assistant — Architecture

> **Status: in active development (pre-1.0), version 0.2.0.**
> Target: Android 11 (API 30) / HarmonyOS 2.0+ (AOSP-based) — validated on Huawei MatePad SE 11
> minSdk 30: the build now matches the documented support window (A11); no backward compat below it
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
| `session/` | Validated state machine; SessionManager orchestrating streaming turns (job hand-offs under a monitor, seq-guarded supersede/cancel); TurnRunner (bounded tool loop; error turns end via reportFailure only); `SpeechPhrases` — locale-aware runtime spoken phrases (RU default + resource-backed values/values-en). |
| `tools/` | ToolContract + registry (timeouts incl. per-tool override, error capture) + real implementations. |
| `media/` | External player control (MUSIC lane): gateway contracts over MediaSession/MediaKeys, `MusicAppCatalog` (which player to target), `MusicPlaybackOrchestrator` — pure capability-gated strategy cascade (structured playFromSearch, MediaBrowser search/token lane, query-aware verification) with rich transport; `MediaBrowserGateway` + `AndroidMediaBrowserGateway` (bind/search/children); `MediaCapabilities`/`VoiceQuery`/`MediaDiagnostics` (pure models). Android adapters: `AndroidMediaGateway` (compat-wrapped controllers), `AndroidMediaBrowserGateway`. |
| `data/` | Room: messages (id-ordered, orphan-safe windowing) + alarms. |
| `service/` | Foreground service (permission gate, retryable init, watchdog semantics), boot receiver, ringing activity, notification listener. |
| `ui/` | Adapters for transcript and alarm lists. |

## Concurrency model

- **One microphone producer** — the only thread touching AudioRecord. Its
  lifecycle is lock-guarded (`producerLock`): overlapping `start()` calls
  can never launch duplicate producers, and the give-up path (50
  consecutive read failures) leaves honest state (`running=false`,
  `hasGivenUp()=true`) that the service watchdog revives on its 15-min ping.
- **One wake-word actor** — engine-agnostic `process()` behind a Mutex, 512-sample re-chunking (rebuilds serialized by `reconfigureMutex`).
  Teardown is bounded on BOTH waits: the actor join (1 s) and the
  engine-mutex acquisition (1.5 s) — a wedged native `process()` leaks the
  engine on purpose (releasing it mid-call is a use-after-free) instead of
  blocking the releasing thread forever.
- **One AudioTrack actor** — sentences serialized through a Channel; a
  generation counter makes `flush()` cancel current + queued playback.
- **Session children** — every session coroutine is a child of `sessionJob`;
  barge-in cancels the whole tree, and each transport cancels its call
  (OkHttp `call.cancel()`, gRPC cancellable `Context`). Cancellation is
  NEVER converted into a tool-error result (`ToolRegistry` and tools
  rethrow `CancellationException`).
- **Session job hand-offs under a monitor** — `SessionManager.controlLock`
  serializes every mutation of the session/detection/window jobs (binder
  thread vs coroutine races), with no suspension inside the guarded
  blocks. `startSession`/`cancelAll` bump the session sequence number
  BEFORE cancelling, so every guarded write of the interrupted turn
  (finish / failure / persistence) is dropped deterministically — a
  cancelled turn can never open a follow-up window afterwards.
- **Turn terminal-event ownership** — error turns end via `reportFailure`
  ONLY (ErrorOccurred → IDLE + error voice); clean turns end via `finish()`
  after the TTS drain. A trailing `finish()` after `reportFailure` emits a
  machine-rejected `LlmDone` and opens a phantom follow-up window — guarded
  by tests.
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
message. The window is additionally bounded by a **character budget**
(`historyMaxChars`, ~4 chars ≈ 1 token): oldest messages are dropped first,
the newest is always kept (truncated head+tail if it alone overflows) — a
budget cut that splits a pair is cleaned by the same position-independent
sanitizer that handles the message-count window. The tool loop is iterative
and bounded (`maxToolPasses = 5`);
each tool execution has a 15 s default timeout — a tool may override it via
`ToolContract.timeoutMs` (playMusic uses 30 s: cold-starting a player and
verifying playback takes that long).

## System prompt & dialogue policy

The system message is composed per LLM pass by `TimeAwareSystemPrompt`
(`session/SystemPrompt.kt`), not a hardcoded literal: identity + personality,
a live time line (clock, weekday, date — formats built per call so a device
timezone change is honored), a time-of-day hint (deep night → shorter
answers), and the dialogue policies from the dialogue-system audit: tool-first
routing, ONE clarifying question for ambiguous requests, confirmation before
irreversible actions unless the command is explicit, no technical details,
honest failure with an alternative, harm refusal. The music routing rules
live in the same prompt. Deliberately RU-only: the ASR is ru-RU and the
Salute voice pool is Russian; the EN UI translates the *interface*, not the
assistant's brain (RUNBOOK documents the honest caveat).

## LLM transient-failure retry

`TurnRunner` retries a failed LLM pass ONLY when the stream produced **zero
chunks** (re-emitting partial output would duplicate spoken sentences) and
the cause is transient: `IOException`, 5xx/429 (`LlmHttpException` — typed in
`llm/LlmClient.kt`, classified without message parsing), or a zero-output
timeout. 4xx and unknown exceptions fail fast. Budget:
`llmMaxRetries` (default 1) with linear backoff (`llmRetryBackoffMs`).

## Turn activity (status pill)

While THINKING, `TurnRunner` publishes what it is doing on
`SessionManager.turnActivity` (`StateFlow<TurnActivity?>`): `Thinking` per
LLM pass, `ToolRunning(tool)` before each execution. `MainActivity` renders
the per-tool label (`activity_tool_*` resources, RU+EN) instead of the generic
«Думаю…»; every terminal (finish / reportFailure / startSession / cancelAll)
clears the flow so a stale label never outlives its turn.

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
says «продолжи». With AEC off (the default), the wake word competes with
speaker output and loud music can mask it — pause-on-wake remains the
zero-config mitigation; the AEC modes (previous section) are the opt-in
fix.


## Echo cancellation (audio/aec)

Two user-selectable modes + off (Settings, default off; see RUNBOOK for the
device validation ladders):

- **HARDWARE (Phase A)** — `AudioRecordSource` captures through
  `VOICE_COMMUNICATION` and attaches the platform `AcousticEchoCanceler`
  (`AecProbe` records the attach outcome under `AecDiag`); the comms DSP
  applies AEC/NS/AGC to the whole mic lane (wake word + ASR both benefit).
  Device-dependent; wake-word accuracy must be validated per device.
- **SOFTWARE (Phase B)** — `AudioPipeline`'s producer passes every frame
  through `NlmsEchoCanceller` (single choke point: ring buffer AND frames
  flow get the same clean snapshot). Far-end references are electrical: the
  TTS tap (`StreamingAudioTrackPlayer.farEndTap` → `LinearResampler`
  24 kHz→16 kHz) and, opt-in with a MediaProjection consent, other apps'
  music (`PlaybackCaptureFarEndSource`, API 29+). The `FarEndMixer` paces
  all lanes onto the mic's time grid; `DelayAligner` (block
  cross-correlation) aligns bulk delay; NLMS adapts the echo path; DTD
  freezes adaptation during double-talk; a min-tracked residual floor drives
  the suppression gate; a divergence guard + freeze-reseed keep the filter
  honest across path changes. Bypass: far-end silent > 200 ms ⇒ bit-exact
  passthrough. Honest trade-off: soft near-end speech within
  `GATE_OPEN_FACTOR` (15×) of the residual floor is partially attenuated
  during double-talk — the knob and its device-tuning guidance live in the
  RUNBOOK. Lane-overflow drops in `FarEndMixer` are counted
  (`Stats.droppedFarEndFrames`) and logged under `AecDiag`.
- The canceller is intentionally an interface (`EchoCanceller`) — the
  documented drop-in slot for a native WebRTC AEC3 (none is Java-exposed on
  Maven as of 2026-09; see PLAN-AEC-FOLLOWUP §0).

## Follow-up window

`SessionStateMachine` gained `FOLLOW_UP_WINDOW`: SPEAKING → (reply drained,
spoke=true, feature on) → IDLE → `FollowUpWindowOpened` → window. Inside the
window, `SessionManager`'s collector feeds `EnergyVad` (adaptive-floor
onset detector; 200 ms lead-in absorbs the TTS tail, `forceSilent` recovers
a swallowed rising edge); speech onset fires a normal turn WITHOUT the wake
word; silence expires to IDLE. The wake word stays armed and supersedes the
window. `FollowUpWindowController` is a pure, virtual-clock state machine —
the session layer only applies its effects. The UI observes
`followUpProgress` (remaining fraction) for the orb's countdown arc. Every
spoken reply re-opens the window (chained conversation); mute/cancelAll
closes it.

## Voice stop without the wake word (FIXPLAN B)

Saying **«стоп» / "stop"** while the assistant THINKS or SPEAKS cancels the
active turn — no wake word, no repeat gesture. The stop phrase is spotted by
the SAME on-device KWS engine: the bundled gigaspeech model is English-BPE,
but Russian «стоп» and English "stop" are the same spoken word, so the
keyword `▁ST O P` (BPE produced with the repo's own `bpe.model`) serves both
product languages with zero extra models and no network.

- Engines are keyword-aware: `WakeWordEngine.phrases` + a matched-phrase
  index from `process()`; the detector routes stop phrases to
  `Detection.StopPhrase`, which passes the barge-in gate UNGATED.
- Routing is state-conditional in `SessionManager.handleStopPhrase`: only
  THINKING/SPEAKING cancel (a «стоп» inside a normal command is left alone).
  `stopActiveTurn()` bumps the session seq BEFORE cancelling (supersede-first),
  flushes the player, and returns to IDLE while the wake-word collector
  STAYS alive — the defining difference from `cancelAll()`.
- Porcupine-primary mode arms a dedicated stop lane (Sherpa,
  `keywords_stop.txt` asset) fed ONLY while THINKING/SPEAKING
  (`setStopLaneEnabled`) — zero idle CPU. Sherpa-primary needs no second
  engine: the stop phrase rides in the same keywords file.
- Toggle: Settings switch (`AppPrefs.voiceStopEnabled`), applied from the
  next turn; `JarvisConfig.voiceStopEnabled` is the master default.

## Custom Sherpa wake words (FIXPLAN C)

The old "asset-only AAR" limitation is lifted: the AAR's Kotlin constructor
is `KeywordSpotter(assetManager: AssetManager? = null, config)` — the
nullable asset path resolves to the native `newFromFile`, and the JNI export
was verified in `libsherpa-onnx-jni.so`. Filesystem models work.

- `SherpaModelStore` extracts the bundled model into `filesDir` once
  (version-marked; heals partial extractions).
- `BpeTokenizer` parses the sentencepiece `bpe.model` protobuf and encodes a
  word with max-score lattice Viterbi — verified byte-identical to
  sentencepiece BPE against this repo's model, and REJECTS inputs that
  would hit `<unk>` (digits, punctuation, Cyrillic), so a dead keyword can
  never be configured.
- A validated keyword is turned into a generated keywords file
  (`SherpaKeywords.toKeywordsFileContent`) and applied live via
  `reconfigureWakeWord()`. Blank = the bundled «Jarvis».
- User-supplied model directories (`sherpaOnnxPath` pref) are honored the
  same way (default CPU provider, generated keywords never written into the
  user's directory).

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
Timer tool uses `setExactAndAllowWhileIdle` one-shots. Identity is the DB
row id EVERYWHERE — AlarmManager request codes, the ringing notification
id and the full-screen-intent request code — so two near-simultaneous
alerts can never overwrite each other's notification extras. Schema v1
(pre-release) upgrades destructively; v2→v3 is a real migration.

## Lifecycle semantics

- **User stop** → `userStopped=true`, watchdog alarm cancelled in
  `onDestroy` → stays stopped.
- **System kill** → no `onDestroy` → watchdog survives → service revives.
- **Boot / package replace** → alarms re-armed from DB, `userStopped`
  cleared, service starts.
- **Init failure** (e.g. missing permission) → `initialized` stays false,
  actionable notification shown, watchdog retries.

## Graceful degradation matrix

Every failure mode has a defined, honest fallback — none of them is a
silent no-op or a crash:

| Failure | Degradation | Recovery |
|---|---|---|
| Offline / captive portal | Session start speaks the offline phrase; no ASR open | NetworkMonitor re-check next wake word |
| ASR open fails | 2 retries w/ backoff → error voice, IDLE | next wake word |
| LLM stream dies mid-turn | error voice, IDLE; partial sentence already spoken stays | next wake word |
| LLM times out (45 s) | error voice, IDLE | next wake word |
| Tool throws / hangs | JSON error result (isError) within 15 s (30 s playMusic) | same turn — LLM reacts |
| Barge-in during tool | cancellation propagates (never a fake tool error); completed subset persisted | new turn |
| TTS sentence fails | sentence dropped, rest of the answer still speaks | next turn |
| TTS drain exceeds 60 s | stragglers cancelled, turn ends | next turn |
| Mic source dies (50 fails) | producer exits honestly (`hasGivenUp`), notification shows idle | watchdog revive ≤ 15 min (never while muted) |
| Wake-word engine build fails | `DetectorState.Failed` + DetectorError → spoken reason | engine reconfigure / restart |
| Native process() wedges | detector degrades; release() bounded (leak, not UAF/ANR) | process restart |
| Room v1 install | destructive wipe (documented) | clean re-setup |
| OEM null service lookup | `as?` + log/instructive JSON error everywhere | n/a (per-call) |
| Token response w/o expiry | 5-min conservative cache + warning | refresh-on-401 |
| Malformed SSE chunk | skipped + logged; stream continues | n/a |
| AppGraph init fails | error TTS + idle notification, no `initialized` | watchdog retry |

## Build

Gradle 8.14.2 · AGP 8.11.1 · Kotlin 2.2.21 · KSP 2.2.21-2.0.5 · Room 2.8.4
gRPC 1.83.1 · protobuf-gradle-plugin 0.10.0 · OkHttp 4.12.0
Porcupine 3.0.0 · Sherpa-ONNX 1.13.6 (bundled AAR + gigaspeech KWS model) · Material Components · compileSdk 34 · minSdk 24 · targetSdk 30

The SaluteSpeech gRPC endpoint is config-driven (`JarvisConfig.saluteGrpcEndpoint`;
renamed from the misleading `llmEndpoint` — it NEVER drove the LLM lane, which is
configured by `gigaChatEndpoint` / the OpenAI-compatible base URL).

## Security

- **Per-user credentials, no shared secrets.** Provider keys (Picovoice, Sber
  Salute, GigaChat) are entered in-app via **Settings** and stored in
  `KeystoreVault` (AndroidKeyStore AES-256-GCM; the deprecated
  security-crypto library is gone). **Nothing secret is baked
  into `BuildConfig` or `local.properties`** — every install uses its owner's
  own credentials, so the APK is safe to distribute to colleagues.
- OAuth uses `Authorization: Basic base64(client_id:client_secret)` per
  Sber's spec; tokens are cached encrypted; secrets/tokens are never logged.
- HTTPS only (`usesCleartextTraffic=false`)
- R8 minification for release; rotating file logs (no tokens/logged secrets)
- `allowBackup=false` (Keystore key is device-bound; a restore can't decrypt
  the creds, so the user simply re-enters them)
- WakeLock released on power disconnect; notification listener reads nothing
- **No certificate pinning (deliberate, audit #31).** The Sber endpoints'
  certificate rotation schedule is unknown to us; a pin set that goes stale
  bricks EVERY install at once (no remote kill-switch exists in this app).
  With per-user credentials, no secrets in the APK, HTTPS-only and
  `usesCleartextTraffic=false`, MITM on a compromised device yields the
  attacker the same token material the device's own user already holds.
  Revisit ONLY if Sber publishes a pin-worthy stable intermediate CA and a
  rotation contract.
- **HTTP timeouts are total.** connect 10 s / read 60 s / whole-call 120 s
  (the call cap sits above every legit use — 45 s LLM cap, per-sentence TTS
  deadlines, 5–15 s credential probes — so it only fires on stuck calls).

## Tests

JVM unit suite (389 tests, all green; runs in CI on every push/PR):
wire DTOs (incl. non-null user content), SSE parser (incl. spec multi-line
assembly), state machine, sentence splitter, conversation windowing (incl.
char-budget trim), alarm
times + notification identity, tool registry (incl. cancellation
propagation), credential store, token manager, AEC DSP (delay aligner,
resampler, mixer incl. drop accounting, NLMS convergence + gate arithmetic),
follow-up controller + VAD, session orchestration with fakes (incl.
error-turn terminal semantics, cancelAll mid-turn, wedged-engine release,
producer give-up/revive), music cascade, router tool surface, system prompt
sections + time injection, turn-activity lifecycle, LLM retry semantics
(transient vs fatal, partial-output safety), RU/EN resource parity.
Run with `./gradlew testDebugUnitTest`.
