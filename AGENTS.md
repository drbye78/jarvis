# AGENTS.md — Jarvis (Android voice assistant)

Compact ramp-up for agents. Every line is something easy to miss.

## Build & verify
- Single Gradle module `:app` (root `settings.gradle.kts` includes only `:app`). Use the wrapper: `./gradlew ...`.
- Build APK: `./gradlew :app:assembleDebug`
- JVM unit tests (no device needed): `./gradlew :app:testDebugUnitTest` (~430 tests; refresh this count when you add a batch)
- Single test class: `./gradlew :app:testDebugUnitTest --tests "com.jarvis.assistant.PorcupineDetectorTest"`
- **Gate before claiming done:** `./gradlew :app:assembleDebug :app:testDebugUnitTest`
- Instrumentation tests (`androidTest`) need a device/emulator; the gate above does not.
- **Resource parity is test-enforced** (`ResourceParityTest`): a string key added to `values/strings.xml` but not `values-en/` (or vice versa) FAILS the suite. Same for `phrase_*` and `activity_tool_*` groups. Add new keys to BOTH locales in the same change.
- The system prompt is built per LLM pass by `session/TimeAwareSystemPrompt` (identity + live time + dialogue policies). Tests pin it with a fixed clock + `TimeZone.setDefault` in `@Before`/`@After`; the formats are constructed PER CALL so a timezone set after class-load is honored — do not cache `SimpleDateFormat` singletons there.
- The Gradle daemon is not guaranteed to persist between tool calls — the first build after a shell reset is cold (~1–2 min). Don't assume warm incremental builds.
- **CI exists**: `.github/workflows/ci.yml` runs the full JVM suite + `assembleDebug` on every push to `main` and every PR (LFS checkout included). A red check means the suite is broken — fix before merging. Several test files define MULTIPLE top-level test classes (e.g. `AlarmAndRegistryTest.kt` → `AlarmTimesTest` + `ToolRegistryTest`) — run by class, not file.
- NOTE: several tests are real-time budgeted (bounded waits on latches/polling, e.g. the wedged-engine release test ~2.5 s). They are deterministic but not instant; don't "optimize" them into thread-yield assertions.

## SDK / toolchain pins (verified in build files)
- `compileSdk 34`, `minSdk 30`, `targetSdk 30` — the low `targetSdk` is **intentional** (Android 11 / HarmonyOS 2.0 appliance profile). **Do not bump `targetSdk` to "fix" the lint warning** — `lint` is configured and would flag `ExpiredTargetSdkVersion`; it is deliberately disabled in `app/build.gradle.kts`. Android 14+ foreground-service/permission guards are handled in code but untested on 14+.
- Kotlin 2.2.21, AGP 8.11.1, JVM 17. minSdk 30 (A11: matches the Android 11 / HarmonyOS 2.0 support claim — the historical "minSdk 24" in older docs was wrong). KSP generates Room code; protobuf + gRPC generate Sber Salute Speech stubs into `build/generated/java/generate*Proto`.

## Architecture (non-obvious)
- Manual DI, no Hilt/Dagger. `di/AppGraph` is the composition root; `service/JarvisForegroundService.onStartCommand` builds it **on a background dispatcher** and completes `graphReady` (`CompletableDeferred`) — await it instead of polling `GraphHolder`. `GraphHolder` holds the running instance. Construct detectors/engines only through AppGraph.
- Wake word is owned by `audio/HybridWakeWordDetector` (engine-agnostic, KEYWORD-AWARE since FIXPLAN B: engines expose `phrases`, `process()` returns the matched index; stop phrases become `Detection.StopPhrase`). It selects **Sherpa-ONNX** (bundled, fully offline, no account) or **Picovoice Porcupine** at runtime via `GraphHolder.graph.reconfigureWakeWord()`. `release()` is bounded on BOTH the actor join and the engine-mutex acquisition (a wedged native `process()` leaks the engine on purpose — freeing it mid-call is a use-after-free).
- **Voice stop (FIXPLAN B)**: «стоп»/"stop" cancels an active turn via `SessionManager.stopActiveTurn()` — seq bump BEFORE cancel, player flush, wake collector KEPT alive (unlike `cancelAll`). Routing is state-conditional (THINKING/SPEAKING only); the gate passes StopPhrase ungated everywhere.
- **Secrets (A3)**: everything secret goes through `util/SecretVault`; production = `KeystoreVault` (AndroidKeyStore AES-GCM). security-crypto/EncryptedSharedPreferences is GONE — do not reintroduce it.
- Flow: `AudioPipeline` → detector actor (one, under a Mutex) → `session/SessionManager` (state machine) → ASR/TTS/LLM.
- `contracts/WakeWordDetector.state` must be readable synchronously: a failed init is surfaced as `DetectorState.Failed`, not only via the event flow (see M1 comments). `SessionManager.startListening` routes `Failed` to a deaf-state error.
- **Turn terminal-event ownership** (do not regress): error turns end via `SessionManager.reportFailure` ONLY (ErrorOccurred → IDLE + error voice) — never call `finish()` after `reportFailure`, or the machine rejects LlmDone from IDLE and a follow-up window opens after a failed turn. Clean turns end via `finish()` after the TTS drain. Both `startSession` and `cancelAll` bump the session seq BEFORE cancelling, which guard-drops every stale write of the interrupted turn.
- **Session/pipeline job hand-offs run under monitors** (`SessionManager.controlLock`, `AudioPipeline.producerLock`) with NO suspension inside the guarded blocks — keep it that way when editing.
- **AEC (Phase A/B)**: `AudioPipeline` runs one `EchoCanceller` per mic frame when SOFTWARE mode is on; far-end lanes feed `FarEndMixer` (TTS electrical tap + API-29 playback capture). Lane-overflow drops are counted + logged under `AecDiag` — if `droppedFarEndFrames` grows, the far-end producer is mis-paced, not the canceller.
- **AudioPipeline give-up + watchdog revive**: after 50 consecutive read failures the producer exits with `hasGivenUp() = true`; the service's 15-min watchdog ping revives it (never while muted). Do not add other auto-revive paths — the flag exists precisely to distinguish "source failing" from "user stopped".

## Critical gotchas (would be missed)
- **Sherpa-ONNX loading modes (know the difference).** The bundled AAR (`app/libs/sherpa-onnx.aar`, v1.13.6) exposes TWO constructors: `KeywordSpotter(assetManager, config)` loads from APK **assets via RELATIVE paths (Mode A, `newFromAsset`)**, and `KeywordSpotter(null, config)` loads from the **filesystem (Mode B, `newFromFile`)** — this is how FIXPLAN C ships custom keywords and extracted/user models. Mixing the modes is the real trap: relative asset paths into `newFromFile`, or absolute `filesDir`/SAF paths into Mode A, crash natively (`AAssetManager_open` → `SHERPA_ONNX_EXIT`). Custom wake words go through `audio/SherpaModelStore` (model extraction) + `BpeTokenizer` (BPE keyword files) + `newFromFile` — that path is supported and tested; the old "do NOT add custom-Sherpa loading" claim applied to a pre-FIXPLAN-C AAR understanding and is obsolete.
- **`assets/sherpa_kws/keywords.txt` must be BPE-tokenized** for the bundled `gigaspeech` model (tokens verified against `tokens.txt`). Hand-written `▁J A R V I S` fails silently (no detection). Regenerate with `sherpa-onnx-cli text2token`; never hand-edit.
- **Never build the wake-word engine on the main thread.** The detector builds async on `Dispatchers.Default` (starts `Bootstrapping` → `Ready`/`Failed`). A synchronous build in the constructor reintroduces an ANR on Kirin 710A-class devices. Keep the `engineBuildDispatcher = Dispatchers.Unconfined` injection in the unit tests so the synchronous-contract assertions stay valid.
- **No secrets in the APK.** Credentials (Picovoice key, Sber/GigaChat tokens) are entered in Settings and stored only in the Android Keystore via `util/KeystoreVault` (AES-256-GCM, zero dependencies). Do not hardcode keys or move them to build config; API clients read them at runtime. (The earlier `EncryptedSharedPreferences`/security-crypto claim was stale — that library is removed from the catalog and must not be reintroduced.)

## Conventions
- Russian is the default UI/config language (target users; "Джарвис"). Keep user-facing strings in `res/values/strings.xml`.
- **Runtime spoken phrases go through `session/SpeechPhrases`** (RU literals as the JVM fallback, `AndroidSpeechPhrases` resolving `phrase_*` resources in production) — never hardcode a spoken string in the session lane. The LLM system prompt stays Russian by product decision.
- Alarm/timer identity is the DB row id everywhere: AlarmManager request codes AND the ringing notification id / full-screen-intent request code (`AlarmReceiver.ringingNotificationId`). Parity by construction — do not introduce a second scheme.
- `getSystemService(...) as X` is FORBIDDEN — use `as?` with an honest degradation path (JSON error, skip + log, or fallback behavior). Odd OEM ROMs can return null.
- Room: v1 (pre-release) upgrades destructively (`fallbackToDestructiveMigrationFrom(1)`); v2→v3 is a real migration. New schema bumps MUST add a real migration + exported schema json.
- Version: `0.2.0`, pre-1.0 (in-development).
- Large binaries are tracked via Git LFS: `app/libs/sherpa-onnx.aar` (~47 MB) and `app/src/main/assets/sherpa_kws/*` (~6.5 MB after the fp32 encoder was dropped — CI fails on unreferenced assets > 1 MB, so never add a model file nothing loads). Don't `.gitignore` them. `git lfs pull` is required after clone (CI does this automatically).

## Cognitive subsystem conventions (COGNITIVE_PLAN 0.1)

Binding from Phase 1 onward — the full contract lives in `COGNITIVE_PLAN.md` (§2, Appendix B of the plan):
- Memory tools must return structured outcomes (`MemoryOutcome`), never bare success strings; user-facing strings go through `ToolStrings` + `ResourceParityTest`.
- Cognitive config is consumed reactively (`util/PrefsFlow`) — never snapshotted at graph build time. Every new setting ships with a live-toggle regression test.
- Cognitive coroutines run on the coordinator's own supervised scope, catch only IO/serialization errors, and ALWAYS rethrow `CancellationException`.
- Never log fact content outside DEBUG; prompt sections have fixed char budgets enforced by the composer.
- Schema changes require an exported Room schema + migration test + CHANGELOG entry. The turn's hot path is sacred: cognitive reads budget ≤ 40 ms, writes are fire-and-forget into Room-backed queues.
- Cloud calls are gated, batched, capped, and honestly degradable — `memory.cloudEnabled=false` must yield zero new egress classes (privacy inventory in the plan §9.2).

## References
- `README.md` (setup/usage), `RUNBOOK.md` (troubleshooting + Known limitations), `ARCHITECTURE.md` (data flow, layers, security). This file is the quick-start; those are the spec.
