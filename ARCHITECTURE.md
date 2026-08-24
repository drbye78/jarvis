# Jarvis Voice Assistant — Architecture

> Target: Huawei MatePad SE 11 · HarmonyOS 2.0 (AOSP 10/11) · Kirin 710A
> Always WiFi · Always charging · Russian language (ru-RU)
> Streaming-first · Barge-in · Ducking · Thread-safe

## Data flow
Mic → AudioRecordSource → AudioPipeline (single producer, SharedFlow + RingBuffer)
├─ PorcupineDetector (wake-word, single actor, 512-sample re-chunk via SampleAccumulator)
└─ VadAnalyzer (Silero VAD, speech collection)
   └─ SessionManager (orchestrator)
      ├─ SaluteSpeechASR (gRPC bidi → AsrResult)
      ├─ ConversationManager (Room DB, 20-msg history)
      ├─ GigaChatClient (SSE, tool-call accumulation)
      ├─ ToolRegistry → AlarmTool / WeatherTool / DeviceControlTool
      └─ SaluteSpeechTTS (gRPC → StreamingAudioTrackPlayer)

## Key components
- **AudioPipeline**: single producer reads AudioRecord; emits via MutableSharedFlow + RingBuffer(8)
- **PorcupineDetector**: single actor re-chunks 320→512 samples, processes under Mutex
- **VadAnalyzer**: Silero VAD, 512-sample frames, drains RingBuffer for pre-subscription recovery
- **SessionStateMachine**: pure event-reducing state machine over StateFlow (IDLE→LISTENING→THINKING→SPEAKING)
- **SessionManager**: thin orchestrator translating streams into SessionEvents, executing SessionActions
- **GigaChatClient**: SSE parser, incremental tool-call accumulation (id/name/arguments by index)
- **ToolRegistry**: pluggable tool framework (AlarmScheduler, WeatherClient, DeviceControlAdapter interfaces)

## DI / Composition
Single `AppGraph` composition root (manual constructor injection). JarvisForegroundService holds one `graph: AppGraph?` field.

## Concurrency model
- **Single microphone producer** (1 coroutine, NEVER 2 threads on AudioRecord)
- **Single Porcupine actor** (1 coroutine, process() behind Mutex)
- **Single AudioTrack actor** (1 coroutine, serialized writes via Channel)
- **Session coroutines**: children of sessionJob, auto-cancelled by SupervisorJob on barge-in
- **State**: MutableStateFlow (single source of truth, observable by notification/ducking)

## Barge-in
Wake-word detection in ALL states (IDLE/LISTENING/THINKING/SPEAKING). 600ms cooldown prevents self-retrigger. Detection → cancel current session → flush player → start new session.

## Tool protocol
OpenAI-compatible: assistant tool_calls (with id) persisted → tool results with tool_call_id + name → no synthetic user turns. Room v3 schema with name/tool_calls_json/tool_call_id columns.

## Ducking
StateFlow-driven: duck on LISTENING/SPEAKING/THINKING state entry, unduck on IDLE. Via MediaSessionManager (requires Notification Listener) with media-key fallback.

## Build
Gradle 8.14.2 · AGP 8.11.1 · Kotlin 2.2.21 · KSP 2.2.21-2.0.5 · Room 2.8.4 · grpc 1.83.1
protobuf-gradle-plugin 0.10.0 · compileSdk 34 · minSdk 24 · targetSdk 30

## Security
- OAuth tokens in EncryptedSharedPreferences (security-crypto 1.1.0 stable)
- HTTPS only (useTransportSecurity, usesCleartextTraffic=false)
- R8 minification for release
- allowBackup=false
- WakeLock released on power disconnect

## Tests
Unit: AlarmToolTest (5), DeviceControlToolTest (2), WeatherToolTest (2) — 9 tests passing.
ID: tools via interfaces (AlarmScheduler, WeatherClient, DeviceControlAdapter).
