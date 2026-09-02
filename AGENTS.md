# AGENTS.md — Jarvis (Android voice assistant)

Compact ramp-up for agents. Every line is something easy to miss.

## Build & verify
- Single Gradle module `:app` (root `settings.gradle.kts` includes only `:app`). Use the wrapper: `./gradlew ...`.
- Build APK: `./gradlew :app:assembleDebug`
- JVM unit tests (no device needed): `./gradlew :app:testDebugUnitTest` (~352 tests)
- Single test class: `./gradlew :app:testDebugUnitTest --tests "com.jarvis.assistant.PorcupineDetectorTest"`
- **Gate before claiming done:** `./gradlew :app:assembleDebug :app:testDebugUnitTest`
- Instrumentation tests (`androidTest`) need a device/emulator; the gate above does not.
- The Gradle daemon is not guaranteed to persist between tool calls — the first build after a shell reset is cold (~1–2 min). Don't assume warm incremental builds.
- **CI exists**: `.github/workflows/ci.yml` runs the full JVM suite + `assembleDebug` on every push to `main` and every PR (LFS checkout included). A red check means the suite is broken — fix before merging. Several test files define MULTIPLE top-level test classes (e.g. `AlarmAndRegistryTest.kt` → `AlarmTimesTest` + `ToolRegistryTest`) — run by class, not file.
- NOTE: several tests are real-time budgeted (bounded waits on latches/polling, e.g. the wedged-engine release test ~2.5 s). They are deterministic but not instant; don't "optimize" them into thread-yield assertions.

## SDK / toolchain pins (verified in build files)
- `compileSdk 34`, `minSdk 24`, `targetSdk 30` — the low `targetSdk` is **intentional** (Android 11 / HarmonyOS 2.0 appliance profile). **Do not bump `targetSdk` to "fix" the lint warning** — `lint` is configured and would flag `ExpiredTargetSdkVersion`; it is deliberately disabled in `app/build.gradle.kts`. Android 14+ foreground-service/permission guards are handled in code but untested on 14+.
- Kotlin 2.2.21, AGP 8.11.1, JVM 17. KSP generates Room code; protobuf + gRPC generate Sber Salute Speech stubs into `build/generated/java/generate*Proto`.

## Architecture (non-obvious)
- Manual DI, no Hilt/Dagger. `di/AppGraph` is the composition root; `service/JarvisForegroundService.onStartCommand` builds it **on a background dispatcher** and completes `graphReady` (`CompletableDeferred`) — await it instead of polling `GraphHolder`. `GraphHolder` holds the running instance. Construct detectors/engines only through AppGraph.
- Wake word is owned by `audio/HybridWakeWordDetector` (engine-agnostic). It selects **Sherpa-ONNX** (bundled, fully offline, no account) or **Picovoice Porcupine** at runtime via `GraphHolder.graph.reconfigureWakeWord()`. `release()` is bounded on BOTH the actor join and the engine-mutex acquisition (a wedged native `process()` leaks the engine on purpose — freeing it mid-call is a use-after-free).
- Flow: `AudioPipeline` → detector actor (one, under a Mutex) → `session/SessionManager` (state machine) → ASR/TTS/LLM.
- `contracts/WakeWordDetector.state` must be readable synchronously: a failed init is surfaced as `DetectorState.Failed`, not only via the event flow (see M1 comments). `SessionManager.startListening` routes `Failed` to a deaf-state error.
- **Turn terminal-event ownership** (do not regress): error turns end via `SessionManager.reportFailure` ONLY (ErrorOccurred → IDLE + error voice) — never call `finish()` after `reportFailure`, or the machine rejects LlmDone from IDLE and a follow-up window opens after a failed turn. Clean turns end via `finish()` after the TTS drain. Both `startSession` and `cancelAll` bump the session seq BEFORE cancelling, which guard-drops every stale write of the interrupted turn.
- **Session/pipeline job hand-offs run under monitors** (`SessionManager.controlLock`, `AudioPipeline.producerLock`) with NO suspension inside the guarded blocks — keep it that way when editing.
- **AEC (Phase A/B)**: `AudioPipeline` runs one `EchoCanceller` per mic frame when SOFTWARE mode is on; far-end lanes feed `FarEndMixer` (TTS electrical tap + API-29 playback capture). Lane-overflow drops are counted + logged under `AecDiag` — if `droppedFarEndFrames` grows, the far-end producer is mis-paced, not the canceller.
- **AudioPipeline give-up + watchdog revive**: after 50 consecutive read failures the producer exits with `hasGivenUp() = true`; the service's 15-min watchdog ping revives it (never while muted). Do not add other auto-revive paths — the flag exists precisely to distinguish "source failing" from "user stopped".

## Critical gotchas (would be missed)
- **Sherpa-ONNX native crash trap.** The bundled AAR (`app/libs/sherpa-onnx.aar`, v1.13.6) exposes only a **non-null `AssetManager`** constructor, which loads the model from APK **assets via relative paths (Mode A)**. Passing an absolute `filesDir`/SAF path CRASHES natively (`AAssetManager_open` → `SHERPA_ONNX_EXIT`). Do NOT add custom-Sherpa model loading from user storage — it cannot work with this AAR. Custom wake words go through Porcupine `.ppn`.
- **`assets/sherpa_kws/keywords.txt` must be BPE-tokenized** for the bundled `gigaspeech` model (tokens verified against `tokens.txt`). Hand-written `▁J A R V I S` fails silently (no detection). Regenerate with `sherpa-onnx-cli text2token`; never hand-edit.
- **Never build the wake-word engine on the main thread.** The detector builds async on `Dispatchers.Default` (starts `Bootstrapping` → `Ready`/`Failed`). A synchronous build in the constructor reintroduces an ANR on Kirin 710A-class devices. Keep the `engineBuildDispatcher = Dispatchers.Unconfined` injection in the unit tests so the synchronous-contract assertions stay valid.
- **No secrets in the APK.** Credentials (Picovoice key, Sber/GigaChat tokens) are entered in Settings and stored only in the Android Keystore via `EncryptedSharedPreferences` (`security-crypto`). Do not hardcode keys or move them to build config; API clients read them at runtime.

## Conventions
- Russian is the default UI/config language (target users; "Джарвис"). Keep user-facing strings in `res/values/strings.xml`.
- **Runtime spoken phrases go through `session/SpeechPhrases`** (RU literals as the JVM fallback, `AndroidSpeechPhrases` resolving `phrase_*` resources in production) — never hardcode a spoken string in the session lane. The LLM system prompt stays Russian by product decision.
- Alarm/timer identity is the DB row id everywhere: AlarmManager request codes AND the ringing notification id / full-screen-intent request code (`AlarmReceiver.ringingNotificationId`). Parity by construction — do not introduce a second scheme.
- `getSystemService(...) as X` is FORBIDDEN — use `as?` with an honest degradation path (JSON error, skip + log, or fallback behavior). Odd OEM ROMs can return null.
- Room: v1 (pre-release) upgrades destructively (`fallbackToDestructiveMigrationFrom(1)`); v2→v3 is a real migration. New schema bumps MUST add a real migration + exported schema json.
- Version: `0.2.0`, pre-1.0 (in-development).
- Large binaries are tracked via Git LFS: `app/libs/sherpa-onnx.aar` (~47 MB) and `app/src/main/assets/sherpa_kws/*` (~17 MB). Don't `.gitignore` them. `git lfs pull` is required after clone (CI does this automatically).

## References
- `README.md` (setup/usage), `RUNBOOK.md` (troubleshooting + Known limitations), `ARCHITECTURE.md` (data flow, layers, security). This file is the quick-start; those are the spec.
