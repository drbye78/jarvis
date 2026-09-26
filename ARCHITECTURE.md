# Jarvis Voice Assistant — Architecture

> **Status: in active development (pre-1.0), version 0.2.2.**
> Target: Android 10+ (minSdk 29) / HarmonyOS 2.0+ (AOSP-based) — validated on Huawei MatePad SE 11
> minSdk 29: HarmonyOS 2.0 devices report API 29
> Always WiFi · Always charging
> Default build targets Russian (wake word, ASR/TTS language, UI); providers are multi-lingual
> targetSdk 36 (Android 16) with Android 14+ guards in code · compileSdk 36

## Data flow

```
Mic → AudioRecordSource → AudioPipeline (single producer, one copy per frame)
   ├─ HybridWakeWordDetector (engine-agnostic wake word: Porcupine OR Sherpa-ONNX; single actor, 320→512 re-chunk)
   └─ SessionManager (delegates each turn to TurnRunner)
        ├─ StreamingAsrClient (bidi gRPC, provider-neutral: Sber Salute OR Yandex v3; live audio up, partials/EOU down)
       ├─ ConversationManager (Room; 20-msg window, tool-pair-safe)
       ├─ LlmClient (GigaChat native v2 | Yandex AI Studio Responses | [OI]-compatible; SSE; wire DTOs)
       │    └─ ToolRegistry → alarms/timers · weather · geo (findPlace/getRoute) · 8 device tools
       └─ TtsClient (gRPC, cancellable Context, deadline: Sber Salute OR Yandex v3)
            └─ StreamingAudioTrackPlayer (single actor, generation-based flush)
```

## Layers

| Package | Responsibility |
|---------|----------------|
| `model/` | Pure domain types (Message, ToolCall, ChatRequest, LlmChunk, states). No serialization annotations. |
| `wire/` | OpenAI-protocol DTOs with `@SerialName` snake_case + mappers. The only code that shapes request JSON. |
| `llm/` | `SseParser` (pure, [OI] contract), `SseLlmClient` (shared SSE transport with correct cancellation), `GigaChatNativeClient` (unified `api.giga.chat/v2`: `tools`/`tool_config`, `messages[]` envelope, named `event:` stream, server-side `web_search`), `YandexAiStudioClient` (AI Studio Responses: `input[]`/`instructions`, flattened tools, lazy cached folder id, server-side `web_search`), `OpenAiCompatClient` (any [OI]-compatible base URL), `TokenManager` (mutex-serialized OAuth refresh). |
| `speech/asr/` | `StreamingAsrClient` / `AsrStream` — bidi streaming ASR; server-side EOU. Implementations: `SberStreamingAsr` (Salute OAuth) and `YandexStreamingAsr` (Yandex v3, `Api-Key`). |
| `speech/tts/` | `TtsClient` (cancellable + deadline) and `TtsPlayer` contract. Implementations: `SaluteSpeechTts` and `YandexSpeechTts` (Yandex v3, 24 kHz `RawAudio`); voice/role packing via `YandexVoiceSpec`. `VoiceCatalog` is the per-backend voice catalog and the single source of truth for the Settings «Голос» card (`SBER_VOICES`, `YANDEX_VOICES`, `yandexRolesFor()`), keyed by backend because the two providers have disjoint voice namespaces. |
| `audio/` | Pipeline (single-copy invariant), ring buffer, `HybridWakeWordDetector` (engine-agnostic: Porcupine + Sherpa-ONNX; runtime-switchable engine via `reconfigure`/`reconfigureWakeWord`, thread-safe under a Mutex; `reconfigureMutex` serializes rebuilds; Sherpa loads BOTH ways per FIXPLAN C — bundled models asset-relative (`newFromAsset`), custom/extracted models from the filesystem (`newFromFile` via `SherpaModelStore`)), player (generations), and the Phase-5 etiquette pair: `AssistantAudioFocus` (duck-during-TTS state machine + `AndroidAudioFocusAdapter`) and `SpeechFeedback` (spoken cascade progress). |
| `session/` | Validated state machine; SessionManager orchestrating streaming turns (job hand-offs under a monitor, seq-guarded supersede/cancel); TurnRunner (bounded tool loop; error turns end via reportFailure only); `SpeechPhrases` — locale-aware runtime spoken phrases (RU default + resource-backed values/values-en). |
| `tools/` | ToolContract + registry (timeouts incl. per-tool override, error capture) + real implementations. Weather: `WeatherTool` (Open-Meteo, current + 7-day daily) over a `WeatherClient`, with the default location resolved by the shared `location/` subsystem. Geo: `findPlace` / `getRoute` (`GeoTools.kt`) over `geo/GeoToolClient`. |
| `geo/` | Geography capability: `GeoModels` (`GeoPoint`/`GeoPlace`/`GeoLeg`/`GeoRoute`/`GeoError`/`GeoResult`), `GeoToolClient` (narrow interface, one impl), `GeoJson` (pure domain→JSON), and `geo/mapkit/**` (`MapKitFactoryBridge`, `MapKitInitializer` + `MapKitInitializerProvider`, `MapKitSearch`, `MapKitRouting`, `MapKitRouteMapper`, `YandexMapKitGeoClient`). `com.yandex.*` imports exist ONLY under `geo/mapkit/**`; no MapKit type escapes. |
| `location/` | Shared, weather-agnostic location subsystem (extracted from the former `tools/weather/`): `LocationProvider`/`LocationFix`, `ResolvedLocation`, `LocationOutcome`, `LocationResolver`/`DefaultLocationResolver` (configured location wins, else a bounded device fix), and the GMS-free `AndroidLocationProvider` (framework `LocationManager` only). Weather and geo share ONE resolver. |
| `media/` | External player control (MUSIC lane): gateway contracts over MediaSession/MediaKeys, `MusicAppCatalog` (which player to target), `MusicPlaybackOrchestrator` — pure capability-gated strategy cascade (structured playFromSearch, MediaBrowser search/token lane, query-aware verification) with rich transport; `MediaBrowserGateway` + `AndroidMediaBrowserGateway` (bind/search/children); `MediaCapabilities`/`VoiceQuery`/`MediaDiagnostics` (pure models). Android adapters: `AndroidMediaGateway` (compat-wrapped controllers), `AndroidMediaBrowserGateway`. Threading invariant: `AndroidMediaBrowserGateway.connect()` must construct `MediaBrowserCompat` on a Looper thread and therefore hops to `Dispatchers.Main` internally — the production tool lane is `Dispatchers.IO`, and without that hop every bind silently returns null (the throw is swallowed by `runCatching`), so the whole browser strategy is dead. Pinned only on-device. |
| `data/` | Room v2: messages (id-ordered, orphan-safe windowing) + alarms + user_facts (cognitive memory) + extraction_queue + memory_meta (cognitive bookkeeping: schema revision, cursors, counters) + fact_fts (FTS4) + command_events + habit_rules + behavior_log + session_summaries + fact_vectors + entities + fact_entities + ring_sessions (durable ring state, `RingSessionEntity`). |
| `service/` | Foreground service (permission gate, retryable init, watchdog semantics), boot receiver, ringing activity, notification listener. |
| `ui/` | Adapters for transcript and alarm lists. |
| `MemoryInspectorActivity` (app root) | Memory Inspector: fact list with provenance marks (sensitive/contested) + confidence/status lines, per-item delete, JSON export via SAF, «Забыть всё» wipe of the cognitive tables; honest read-only empty state when the service graph isn't running. |

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
`ToolContract.timeoutMs` (playMusic uses 50 s: cold-starting a player and
verifying playback takes that long).

## Geography lane (Yandex MapKit)

`findPlace` and `getRoute` answer place/organization search and public-transport
+ walking routes by voice. **No map is ever rendered** — the assistant is
screen-less, so the tools return structured JSON (via `GeoJson`) that the LLM
turns into speech. The backend is the Yandex MapKit Android SDK
(`com.yandex.android:maps.mobile:4.45.0-full`). Transit answers carry leg-by-leg
detail — the line (bus/metro), the vehicle type, the transfer point and the
number of stops — which the HTTP Maps APIs cannot name; that line-level detail
is the entire reason MapKit was chosen over them.

Lane flow: `TurnRunner` → tool (`tools/GeoTools.kt`) → `geo/GeoToolClient`
(narrow interface) → `geo/mapkit/YandexMapKitGeoClient` → `MapKitSearch` /
`MapKitRouting` → `MapKitRouteMapper` → domain `GeoRoute` → `GeoJson`.

- **A narrow interface with one impl, not a sealed provider.** `GeoToolClient`
  mirrors `WeatherClient`/`OpenMeteoWeatherClient`: exactly one implementation
  and no Settings radio, because the user does not choose a geo backend.
  Contrast the LLM/speech backends, which ARE sealed `when` providers (a new one
  is a compile error until wired) — that machinery is deliberately not spent on
  a capability with a single provider.
- **The location subsystem was extracted out of the weather lane.** The former
  `tools/weather/` package is gone; `location/` now owns `LocationProvider`,
  `LocationFix`, `ResolvedLocation`, `LocationOutcome`, `LocationResolver`,
  `DefaultLocationResolver` and the GMS-free `AndroidLocationProvider`
  (`LocationManager` only — no Play Services, no `FusedLocationProviderClient`).
  Weather was migrated onto it, and weather and geo share ONE resolver instance,
  so the default-location policy is single-sourced.
- **Configured location wins — no GPS, no permission needed.** Only a blank
  configured value falls back to a bounded device fix, and every failure
  degrades to a typed `LocationOutcome` (`Resolved`/`PermissionDenied`/
  `Unavailable`) so a tool answers honestly instead of inventing a city.
  `findPlace` is deliberately lenient: an unresolved default degenerates to an
  unconstrained search, because a query that names its own place («аптека в
  Москве») stays answerable. `getRoute` is strict: a route genuinely needs an
  origin, so an unresolved location is an honest error.
- **Reverse-geocoding asymmetry — honest, and per-subsystem.** MapKit has a
  reverse-geocode seam (`GeoToolClient.resolveLabel` → `MapKitSearch.reverse`),
  so a coordinate CAN be named; Open-Meteo has none and the app adds no
  third-party egress, so weather must always say «текущее местоположение» for a
  GPS fix. The same device fix may therefore be nameable for a route and unnamed
  for weather — the subsystems' real capability difference, not a bug.
- **Egress is the SDK's own calls, and attribution is a known limitation.** The
  only new egress is MapKit's requests to Yandex (search, routing); no new HTTP
  client was added. The Yandex Maps terms require the «Open in Maps» button, the
  Terms link, the copyright notice and the logo **on the map/screen**; a
  screen-less assistant cannot satisfy that as written, so the owner accepted
  the residual legal risk and shipped a Settings attribution block (a Yandex
  Maps data notice, a Terms link to `https://yandex.ru/legal/maps_termsofuse`,
  and an "open in Yandex Maps" action to `https://yandex.ru/maps`). Record this
  as a **known limitation / legal risk the owner accepted**, not as compliance.
  The terms also cap free-tier use (1,000 unique users/day) and forbid storing
  results beyond 30 days.
- **GMS-FREE is preserved by dependency exclusion.** `play-services-location`
  and `play:integrity` are `exclude`d; the AAR embeds no GMS and the
  search/transport call paths reference none (see AGENTS.md). `-full` is
  mandatory — `-lite` ships zero search/transport classes. `assembleRelease`
  needs the `-dontwarn` rules for the two excluded groups.

## System prompt & dialogue policy

The system message is composed per LLM pass by `TimeAwareSystemPrompt`
(`session/SystemPrompt.kt`), not a hardcoded literal: identity + personality,
a live time line (clock, weekday, date — formats built per call so a device
timezone change is honored), a time-of-day hint (deep night → shorter
answers), and the dialogue policies from the dialogue-system audit: tool-first
routing, ONE clarifying question for ambiguous requests, confirmation before
irreversible actions unless the command is explicit, no technical details,
honest failure with an alternative, harm refusal — plus the open-topic policy:
answer general knowledge from the model itself, keep any conversation going,
and use the built-in internet search for fresh or changing facts. The music
routing rules live in the same prompt. Deliberately RU-only: the ASR is ru-RU and both
speech backends default to Russian voices (the Salute pool; Yandex `marina`);
the EN UI translates the *interface*, not the assistant's brain (RUNBOOK
documents the honest caveat).

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
heart-rating type for `like`, plus the API-29 guard for `speed` — still
required at runtime: minSdk is 29 (HarmonyOS 2.0 devices report API 29),
so API-29 devices hit the guard); unsupported actions get
an honest Russian refusal naming
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
in priority order (ru.yandex.music → com.yandex.music →
com.zvooq.openplay → com.uma.musicvk — the last being VK Music's real
applicationId, since `com.vk.music` is only its code namespace); else
any launchable app with a music-looking label. The whole cascade
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
- The custom-keyword path is the ONLY model source: the `sherpaOnnxPath` pref
  (user-supplied model directory) was removed by the settings redesign — it had
  no writer, no UI and no test reference, so the branch was unreachable.

## UI design system

Theme: Material 3 (`Theme.Material3.DayNight.NoActionBar`, material
1.12) over a teal/amber token set — `values/colors.xml` +
`values-night/colors.xml` (24 day/night twins), status-bar follows the
mode via `values(-night)/bools.xml`, text appearances in `styles.xml`
(AppTitle / ScreenTitle / SectionHeader / Status / Hint). No hardcoded
color hex outside the token files: bubbles/pills are shape drawables
referencing `?attr/*`, so day/night is automatic everywhere.

Screens: home is the voice orb (`VoiceOrbView` — custom Canvas view,
state-driven animators: idle-breathe / listening-ripple / thinking-arcs /
speaking-glow, muted-flat; animators cancelled on detach). While the mic is
actually open the orb also PULSES WITH THE SPEAKER'S VOICE, and a wake-word
detection plays a brief expand-and-flash cue. Both are fed by signals that
already existed — `AudioPipeline.frames` (one 20 ms PCM frame per emission) and
`Detection.WakeWord` — so nothing in the capture loop, AEC or wake-word path
changed. The envelope maths lives in `ui/AudioLevel` (pure, pinned by
`AudioLevelTest`): a perceptual dBFS map so a quiet room reads as a true zero,
plus a fast-attack/slow-release filter so it tracks syllables instead of
flickering between them. The cue is keyed off the DETECTION, not the
IDLE→LISTENING edge — the follow-up window and VAD also enter LISTENING, so a
state edge would acknowledge a wake word that was never spoken. Cost and
reduced motion are both gated: metering is subscribed only for capture states,
skipped while the window is not started, posted at ~30 fps rather than the
50 fps capture rate, and with animators disabled the level pins to zero and the
cue is skipped entirely. + a chat
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

### Settings screen architecture (category list → detail)

The 1955-line single-scroll `SettingsActivity` was split by a strangler
rewrite into a **category LIST host** plus ONE reusable **detail host**:

- **`SettingsActivity`** (`activity_settings.xml`) is the entry point
  `MainActivity`/`OnboardingActivity` already launch: a header, the
  pending-restart banner, a `RecyclerView` of `SettingsCategory.entries`
  and an About row (a dialog, not a screen). It owns no setting.
- **`SettingsDetailActivity`** (`activity_settings_detail.xml`) inflates
  one `screen_settings_<id>.xml` into `settingsDetailContent`, selected by
  a `SettingsCategory` extra, and binds that screen's controller. It is the
  ONLY `SettingsHost` implementor, so navigation, permission launchers, the
  `.ppn`/playback-capture results, the 45 s bounded `awaitAssistantGraph()`
  wait, the column cap and the banner all live in one place.

**`SettingsCategory` IS the screen registry.** The enum's `.entries` is the
list the adapter renders; each entry carries its `titleRes`/`subtitleRes`
and `layoutRes`, and its `id` doubles as the `screen_settings_<id>.xml`
filename and the `settings_cat_<id>` string suffix, so the three names
cannot drift. `SettingsControllerFactory.create(category, callbacks, prefs,
host)` is a pure dispatch over an **exhaustive `when` with NO `else`** — a
new category is a compile error until its controller is wired, the same
idiom as the LLM-provider `when` in `AppGraph`. Each of the eight
controllers (`settings/controller/`) is a small class over the frozen 3-arg
seam `(callbacks, prefs, host)`; it binds its own root and reaches the
outside world only through the narrow `SettingsHost` interface — it must
not call `findViewById` outside its screen, start an Intent, or touch a
permission API.

**When a change applies** is stated once, by `settings/ApplyPolicy` +
`ApplyPolicies` (key→policy) instead of scattered hint strings: `LIVE`
(applied on next use, no banner), `SERVICE_RESTART` (sealed at `AppGraph`
construction — LLM provider type/model/URLs, speech backend, AEC mode, the
[OI] key), and `APP_RESTART` (the MapKit key, settable once per process).
`PendingChanges` is the in-memory (deliberately NOT persisted) set that
drives the banner; the strongest pending policy wins, and **V1 is
instruction-only — the banner never relaunches the process**.

The split is guarded structurally: `SettingsLayoutTest` closes
registry↔layout↔include↔controller ids and forbids duplicate/orphan screen
layouts; `SettingsInventoryTest` reflects over `AppPrefs` +
`CredentialsStore` and fails the build if any persisted setting is neither
in `SettingsInventory.entries` nor the explicit `nonSettingsKeys`
allow-list.

## Alarms

`AlarmManager.setAlarmClock` + Room persistence + full-screen ringing
activity (showWhenLocked/turnScreenOn), looping alarm sound + vibration,
Dismiss/Snooze, 5-minute auto-timeout, daily re-arm, boot re-scheduling.
Timer tool uses `setExactAndAllowWhileIdle` one-shots. Identity is the DB
row id EVERYWHERE — AlarmManager request codes, the ringing notification
id and the full-screen-intent request code — so two near-simultaneous
alerts can never overwrite each other's notification extras. Schema v2
(pre-release) upgrades destructively (`fallbackToDestructiveMigration(dropAllTables = true)`);
the real, data-preserving migration chain starts at v2→v3.

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
| Tool throws / hangs | JSON error result (isError) within 15 s (50 s playMusic) | same turn — LLM reacts |
| Weather: no location + permission denied | typed `PermissionDenied` → spoken hint to set a city / grant access | grant in Settings → «Погода», or set a city |
| Weather: no fix within ~6 s (no GPS/net provider) | typed `Unavailable` → spoken hint to set a city (never an invented city) | set a city in Settings |
| Geo: no MapKit key configured | typed `GeoError.NO_KEY` → spoken hint to add the key in Settings («Карты») | add a MapKit key |
| Geo: key changed after init | typed `GeoError.KEY_CHANGED` → spoken hint to **fully restart the app** | full app-process restart |
| Geo: route with no usable location | typed `PERMISSION_DENIED`/`UNAVAILABLE` → spoken hint to set a city / grant access | set a city or grant access |
| Geo: nothing found | typed `NOT_FOUND` → tool-specific "nothing found" answer (place vs route) | refine the query |
| Geo: MapKit native/service failure | typed `FAILED` → generic "map service unavailable" | retry next turn |
| Barge-in during tool | cancellation propagates (never a fake tool error); completed subset persisted | new turn |
| TTS sentence fails | sentence dropped, rest of the answer still speaks | next turn |
| TTS drain exceeds 60 s | stragglers cancelled, turn ends | next turn |
| Mic source dies (50 fails) | producer exits honestly (`hasGivenUp`), notification shows idle | watchdog revive ≤ 15 min (never while muted) |
| Wake-word engine build fails | `DetectorState.Failed` + DetectorError → spoken reason | engine reconfigure / restart |
| Native process() wedges | detector degrades; release() bounded (leak, not UAF/ANR) | process restart |
| Older Room schema (pre-1.0) install | destructive wipe (documented) | clean re-setup |
| OEM null service lookup | `as?` + log/instructive JSON error everywhere | n/a (per-call) |
| Token response w/o expiry | 5-min conservative cache + warning | refresh-on-401 |
| Malformed SSE chunk | skipped + logged; stream continues | n/a |
| AppGraph init fails | error TTS + idle notification, no `initialized` | watchdog retry |

## Build

Gradle 8.14.2 · AGP 8.11.1 · Kotlin 2.2.21 · KSP 2.2.21-2.0.5 · Room 2.8.4
gRPC 1.83.1 · protobuf-gradle-plugin 0.10.0 · OkHttp 4.12.0
Porcupine 4.0.2 · Sherpa-ONNX 1.13.6 (bundled AAR + gigaspeech KWS model) · Yandex MapKit 4.45.0-full (GEO lane) · Material Components · compileSdk 36 · minSdk 29 · targetSdk 36

The SaluteSpeech gRPC endpoint is config-driven (`JarvisConfig.saluteGrpcEndpoint`;
renamed from the misleading `llmEndpoint` — it NEVER drove the LLM lane, which is
configured by `gigaChatNativeEndpoint` / `yandexAiStudioEndpoint` / the [OI]-compatible
base URL).

## Security

- **Per-user credentials, no shared secrets.** Provider keys (Picovoice, Sber
  Salute, GigaChat, Yandex Cloud API key) are entered in-app via
  **Settings** and stored in `KeystoreVault` (AndroidKeyStore AES-256-GCM; the
  deprecated security-crypto library is gone). The Yandex key is a
  non-expiring API key with no folder id (`SecretVault.KEY_YANDEX_API_KEY`); the
  **same** key authenticates both SpeechKit v3 and the AI Studio LLM (which
  resolves the folder from `GET /v1/models` rather than a header).
  **Nothing secret is baked into `BuildConfig` or `local.properties`** — every
  install uses its owner's own credentials, so the APK is safe to distribute
  to colleagues. The **MapKit Mobile SDK key** is a separate secret
  (`SecretVault.KEY_MAPKIT_API_KEY`, `mapkit_api_key`) — not the Yandex Cloud
  key — read live so it can be set after construction; a changed value returns
  `GeoError.KEY_CHANGED` rather than crashing, and only a full app-process
  restart applies it (MapKit cannot be re-keyed in-process).
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
- **Минцифры trust roots are host-scoped (`util/SberTrust.kt`).** The two
  published Russian Trusted Root/Sub CA PEMs are embedded and installed via a
  host-scoped trust manager, so those bundled anchors apply ONLY to `sber.ru`,
  `*.sber.ru`, `sberbank.ru` and `*.sberbank.ru`. Every other host — including
  the Yandex endpoints, which deliberately use the platform store — validates
  against the system trust store only. Label matching is label-exact (no
  suffix tricks).
- **HTTP timeouts are total.** connect 10 s / read 60 s / whole-call 120 s
  (the call cap sits above every legit use — 45 s LLM cap, per-sentence TTS
  deadlines, 5–15 s credential probes — so it only fires on stuck calls).

## Tests

JVM unit suite (1147 tests, all green; runs in CI on every push/PR). The live
smoke tier (Sber + Yandex + GigaChat, `integration/**/*LiveSmokeTest`) shares
`src/test` but is excluded from the gate task and runs only through
`:app:integrationTest`, which self-skips when credentials are absent:
wire DTOs (incl. non-null user content), SSE parser (incl. spec multi-line
assembly), the GigaChat native `/v2` parser + transport (`GigaChatSseParserTest`
replays real recorded SSE fixtures, `SseStreamTest`, `GigaChatNativeWireTest`),
the Yandex AI Studio Responses parser + transport (`YandexSseParserTest` and
`YandexWireTest` replay real recorded fixtures; folder discovery, `call_id`
preservation and the one-`Done` latch are pinned),
weather (`WeatherClientTest` pins `timezone=auto`, index-aligned daily columns,
the coords-bypasses-geocoding path and the 1–7 day clamp; `DefaultLocationResolverTest`
pins configured-wins, never-throw and cancellation propagation),
geography (`GeoToolsTest` pins the typed `GeoError` → message mapping, the
lenient `findPlace` origin and the strict `getRoute` origin; `GeoJsonTest` pins
the JSON shape; `MapKitInitializerTest` + `MapKitInitializerProviderTest` pin the
once-per-process `KEY_CHANGED` semantics and the process-scoped memoization;
`MapKitRouteMapperTest` covers route narration through the MapKit-free
projection — MapKit 4.45.0 is Java 21 bytecode and CANNOT be loaded in a JVM
unit test, so the SDK bindings in `MapKitRouteMapper.map(List<Route>)` are
covered by `MapKitLiveSmokeTest` in the androidTest tier instead — 4/4 on the
target device with a real key),
state machine, sentence splitter, conversation windowing (incl.
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

Beyond the JVM suite there is an **instrumentation tier** under
`app/src/androidTest` (including `MediaTransportDeviceTest`, 16 self-skipping
`@Test`s) that needs a device/emulator and is NOT part of the push/PR gate. It
runs in the separate nightly `.github/workflows/android-test.yml`
(`connectedDebugAndroidTest`, 03:20 UTC) plus manual dispatch; `ci.yml` runs
the JVM suite on push-to-main/PR.
