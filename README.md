# Jarvis — Voice Assistant for Android 11 / HarmonyOS 2.0+

[![CI](https://github.com/drbye78/jarvis/actions/workflows/ci.yml/badge.svg)](https://github.com/drbye78/jarvis/actions/workflows/ci.yml)

> **Status: in active development (pre-1.0).** Version `0.2.0`. APIs, behavior, and on-device storage may change between releases.

Always-listening voice assistant for Android 11 (API 30) / HarmonyOS 2.0+ devices
(minSdk 30 — the build matches the documented support window).
The default build targets Russian (wake word «Джарвис», ASR/TTS language, UI); the
SaluteSpeech and GigaChat providers are multi-lingual. Streaming-first: live ASR,
streamed LLM with tool calling,
sentence-buffered TTS. Alarms and timers that actually ring. Real on-tablet
device control. Pluggable LLM provider (Sber GigaChat by default, or any
OpenAI-compatible endpoint).

## Prerequisites
- JDK 17
- Android SDK 34 (`sdk.dir` in `local.properties` or `ANDROID_HOME`)
- Gradle wrapper included: `./gradlew`

## Setup
1. (Build only) Set `sdk.dir` in `local.properties` (or use `ANDROID_HOME`):
   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```
   No provider secrets belong in `local.properties` — see step 3.
2. Build and install:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
3. **Enter provider credentials in-app.** On first launch, open **Settings**
   (gear button) and enter your own:
   - **Picovoice access key** (wake word)
   - **Sber Salute** client ID + secret (ASR/TTS)
   - **GigaChat** client ID + secret (LLM)
   The mandatory Salute/GigaChat pairs are **validated upfront in the panel as
   you type** (a live status row: valid / invalid / unreachable) and on every
   «Проверить ключи» press — a typo is caught in seconds, not at the next
   voice command.    Credentials are stored encrypted in the Android Keystore
   (`KeystoreVault`, AndroidKeyStore AES-GCM) on the device — **nothing secret is ever in
   the APK or in `local.properties`**. GigaChat creds are optional if you use
   the OpenAI-compatible provider instead (also configured in Settings).
   The UI ships in Russian and English (full `values-en`), and the runtime
   spoken phrases follow the locale too (see RUNBOOK for the honest
   English-voice caveat).
4. **Wake word — two engines (hybrid).** In Settings → Wake word you choose
    the engine:
    - **Sherpa-ONNX (recommended, no account):** a fully on-device wake word
      using the bundled `gigaspeech` model that detects «Jarvis» — or any
      English word you type in Settings (the app BPE-tokenizes it with the
      bundled model and refuses words it cannot encode). No Picovoice key,
      no network — offline by design.
    - **Picovoice Porcupine:** built-in "Jarvis", or **load your own `.ppn`**
      trained in [Picovoice Console](https://console.picovoice.ai/) (a Console
      `.ppn` is bound to your Picovoice key). Requires a free Picovoice account.
    Switching engines and the sensitivity slider apply live while the assistant
    is running. Custom Sherpa wake words are supported: type any English keyword
    in Settings (BPE-validated against the bundled model) or supply a custom
    Sherpa model directory. See RUNBOOK for details.
5. Launch Jarvis and follow the onboarding screen.

## Running tests
```bash
./gradlew testDebugUnitTest
```

CI runs the same suite plus `assembleDebug` on every push/PR (see the badge
above — includes the Git-LFS-tracked native assets).

## Upgrading from pre-release builds

Installs on schema v1 (the old `alarms` table, never exported) upgrade
destructively: alarms and chat history are wiped once, in exchange for a
non-crashing upgrade. v2→v3 and later upgrades migrate for real.

## Building a signed release APK

Release APKs are signed with a personal keystore (`app/release.keystore`).
Copy `local.properties.example` → `local.properties` and fill in the signing
properties (store path, password, alias). The keystore file must be present
in `app/` — it is `.gitignore`d and never committed.

```bash
./gradlew :app:assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

To verify the signature:
```bash
/path/to/Android/Sdk/build-tools/34.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Colleagues who want to build their own signed APK generate their own
keystore with `keytool` and update `local.properties` accordingly.

## What Jarvis can do
- **Voice**: wake word «Джарвис» (or a custom English keyword), voice stop
  (say «стоп» while the assistant thinks or speaks — it stops without the
  wake word), barge-in mid-answer, streaming recognition,
  **follow-up window** (opt-in: after each reply the mic stays open for
  2–12 s — keep talking without the wake word; the orb shows a countdown).
  The status pill shows **what the assistant is doing** while thinking
  («Ставлю будильник…», «Проверяю погоду…»), not a generic «Думаю…».
- **Time-aware assistant**: the system prompt carries the live clock, weekday
  and a time-of-day hint (at 3 a.m. answers get shorter), a stable personality,
  clarification of ambiguous requests, confirmation before irreversible
  actions, and harm-refusal rules. Transient LLM failures (5xx, connection
  resets, zero-output timeouts) are retried once automatically — partial
  answers are never re-emitted, so nothing is ever spoken twice.
- **Echo cancellation** (opt-in, Settings → «Эхоподавление»): *hardware*
  mode routes the mic through the tablet's comms DSP; *software* mode runs a
  built-in adaptive canceller against the assistant's own voice (electrical
  TTS reference) and, with a one-tap system consent, other apps' music —
  experimental, see RUNBOOK for the honest quality expectations and
  validation steps.
- **Chat**: GigaChat (or OpenAI-compatible provider) with a 20-message context
  bounded by a character budget — verbose tool results can no longer overflow
  the model's context window (the newest turn is always kept, truncated if
  needed).
- **Voice picker** (Settings → «Голос»): Mila by default, any other Salute
  voice ID by hand, with a «Проверить голос» preview button. Applies to the
  next spoken sentence — no restart.
- **Music**: «Джарвис, включи Bohemian Rhapsody», «включи альбом Группа
  крови», «включи музыку» — a capability-gated cascade drives the installed
  player (Яндекс Музыка by default): structured voice search with slots,
  MediaBrowser library search with deterministic `playFromMediaId`,
  permission-free session-token cold start, legacy intent, honest search
  screen fallback — playback is verified against what you asked for.
  Full transport: pause/resume/next/previous/stop, «промотай на минуту»,
  «сначала», «лайкни», «повтори трек», «перемешай», «быстрее/медленнее»
  (each gated on what the player actually supports — honest refusals,
  never silent no-ops). «что играет?» reads track, artist, queue position
  («третья из двенадцати»), repeat/shuffle state; «какие плейлисты есть» /
  «найди в музыке» browse the player's library. The spoken confirmation
  ducks external music. See [ARCHITECTURE.md](ARCHITECTURE.md) (Music
  lane) for the strategy cascade and its honest fallbacks, and
  [RUNBOOK.md](RUNBOOK.md) for `adb logcat -s MusicDiag` — the per-build
  capability dump.
- **Alarms & timers**: set/cancel/list by voice or UI; ring over the lock
  screen; survive reboots.
- **Weather**: current conditions for any city (Open-Meteo).
- **Device control**: volume, brightness, Wi-Fi, Bluetooth, DND, screen off,
  open app, battery/time info.

## License
[MIT](LICENSE)

## Docs
- [ARCHITECTURE.md](ARCHITECTURE.md) — component and concurrency model.
- [RUNBOOK.md](RUNBOOK.md) — troubleshooting, debugging, performance targets.

## Tech stack
- Kotlin 2.2.21 · Coroutines · Flow · kotlinx.serialization
- Picovoice Porcupine + Sherpa-ONNX (hybrid wake word: Porcupine with a Picovoice account, or fully offline Sherpa-ONNX with no account)
- Sber SaluteSpeech (streaming ASR + TTS via gRPC)
- Sber GigaChat or any OpenAI-compatible API (LLM via SSE, tool calling)
- Room (conversation + alarms) · Open-Meteo (weather)
- Material 3 UI (teal/amber day+night design system): home screen with a
  live voice orb (breathing/ripple/thinking/speaking animations), chat-style
  transcript, permission onboarding with status rows and start gating,
  settings with a «Музыка» default-player card (Яндекс / Звук / VK)
