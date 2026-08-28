# AGENTS.md — Jarvis (Android voice assistant)

Compact ramp-up for agents. Every line is something easy to miss.

## Build & verify
- Single Gradle module `:app` (root `settings.gradle.kts` includes only `:app`). Use the wrapper: `./gradlew ...`.
- Build APK: `./gradlew :app:assembleDebug`
- JVM unit tests (no device needed): `./gradlew :app:testDebugUnitTest`
- Single test class: `./gradlew :app:testDebugUnitTest --tests "com.jarvis.assistant.PorcupineDetectorTest"`
- **Gate before claiming done:** `./gradlew :app:assembleDebug :app:testDebugUnitTest`
- Instrumentation tests (`androidTest`) need a device/emulator; the gate above does not.
- The Gradle daemon is not guaranteed to persist between tool calls — the first build after a shell reset is cold (~1–2 min). Don't assume warm incremental builds.
- No CI in-repo (`.github/` absent): verify locally before pushing.

## SDK / toolchain pins (verified in build files)
- `compileSdk 34`, `minSdk 24`, `targetSdk 30` — the low `targetSdk` is **intentional** (Android 11 / HarmonyOS 2.0 appliance profile). **Do not bump `targetSdk` to "fix" the lint warning** — `lint` is configured and would flag `ExpiredTargetSdkVersion`; it is deliberately disabled in `app/build.gradle.kts`. Android 14+ foreground-service/permission guards are handled in code but untested on 14+.
- Kotlin 2.2.21, AGP 8.11.1, JVM 17. KSP generates Room code; protobuf + gRPC generate Sber Salute Speech stubs into `build/generated/java/generate*Proto`.

## Architecture (non-obvious)
- Manual DI, no Hilt/Dagger. `di/AppGraph` is the composition root; `service/JarvisForegroundService.onStartCommand` builds it **on the main thread**. `GraphHolder` holds the running instance. Construct detectors/engines only through AppGraph.
- Wake word is owned by `audio/HybridWakeWordDetector` (engine-agnostic). It selects **Sherpa-ONNX** (bundled, fully offline, no account) or **Picovoice Porcupine** at runtime via `GraphHolder.graph.reconfigureWakeWord()`.
- Flow: `AudioPipeline` → detector actor (one, under a Mutex) → `session/SessionManager` (state machine) → ASR/TTS/LLM.
- `contracts/WakeWordDetector.state` must be readable synchronously: a failed init is surfaced as `DetectorState.Failed`, not only via the event flow (see M1 comments). `SessionManager.startListening` routes `Failed` to a deaf-state error.

## Critical gotchas (would be missed)
- **Sherpa-ONNX native crash trap.** The bundled AAR (`app/libs/sherpa-onnx.aar`, v1.13.6) exposes only a **non-null `AssetManager`** constructor, which loads the model from APK **assets via relative paths (Mode A)**. Passing an absolute `filesDir`/SAF path CRASHES natively (`AAssetManager_open` → `SHERPA_ONNX_EXIT`). Do NOT add custom-Sherpa model loading from user storage — it cannot work with this AAR. Custom wake words go through Porcupine `.ppn`.
- **`assets/sherpa_kws/keywords.txt` must be BPE-tokenized** for the bundled `gigaspeech` model (tokens verified against `tokens.txt`). Hand-written `▁J A R V I S` fails silently (no detection). Regenerate with `sherpa-onnx-cli text2token`; never hand-edit.
- **Never build the wake-word engine on the main thread.** The detector builds async on `Dispatchers.Default` (starts `Bootstrapping` → `Ready`/`Failed`). A synchronous build in the constructor reintroduces an ANR on Kirin 710A-class devices. Keep the `engineBuildDispatcher = Dispatchers.Unconfined` injection in the unit tests so the synchronous-contract assertions stay valid.
- **No secrets in the APK.** Credentials (Picovoice key, Sber/GigaChat tokens) are entered in Settings and stored only in the Android Keystore via `EncryptedSharedPreferences` (`security-crypto`). Do not hardcode keys or move them to build config; API clients read them at runtime.

## Conventions
- Russian is the default UI/config language (target users; "Джарвис"). Keep user-facing strings in `res/values/strings.xml`.
- Version: `0.2.0`, pre-1.0 (in-development).
- Large binaries are committed (no Git LFS): `app/libs/sherpa-onnx.aar` (~47 MB) and `app/src/main/assets/sherpa_kws/*` (~17 MB). Don't `.gitignore` them.

## References
- `README.md` (setup/usage), `RUNBOOK.md` (troubleshooting + Known limitations), `ARCHITECTURE.md` (data flow, layers, security). This file is the quick-start; those are the spec.
