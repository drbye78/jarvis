# Jarvis — Voice Assistant for Android 11 / HarmonyOS 2.0+

> **Status: in active development (pre-1.0).** Version `0.2.0`. APIs, behavior, and on-device storage may change between releases.

Always-listening voice assistant for Android 11 (API 30) / HarmonyOS 2.0+ devices.
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
   Credentials are stored encrypted in the Android Keystore
   (`EncryptedSharedPreferences`) on the device — **nothing secret is ever in
   the APK or in `local.properties`**. GigaChat creds are optional if you use
   the OpenAI-compatible provider instead (also configured in Settings).
4. **Wake word — two engines (hybrid).** In Settings → Wake word you choose
    the engine:
    - **Sherpa-ONNX (recommended, no account):** a fully on-device wake word
      using the bundled `gigaspeech` model that detects «Jarvis». No Picovoice
      key, no network — offline by design.
    - **Picovoice Porcupine:** built-in "Jarvis", or **load your own `.ppn`**
      trained in [Picovoice Console](https://console.picovoice.ai/) (a Console
      `.ppn` is bound to your Picovoice key). Requires a free Picovoice account.
    Switching engines and the sensitivity slider apply live while the assistant
    is running. NOTE: a custom Sherpa model cannot be loaded with the current
    AAR (it only loads the bundled asset); custom Sherpa wake words need a
    self-trained model + a different build — see RUNBOOK.
5. Launch Jarvis and follow the onboarding screen.

## Running tests
```bash
./gradlew testDebugUnitTest
```

## What Jarvis can do
- **Voice**: wake word «Джарвис», barge-in mid-answer, streaming recognition.
- **Chat**: GigaChat (or OpenAI-compatible provider) with 20-message context.
- **Music**: «Джарвис, включи Bohemian Rhapsody» — orders the installed player
  (Яндекс Музыка by default) to search and play; pause/resume/next/previous;
  «что играет?» reads the current track. See [ARCHITECTURE.md](ARCHITECTURE.md)
  (Music lane) for the strategy cascade and its honest fallbacks.
- **Alarms & timers**: set/cancel/list by voice or UI; ring over the lock
  screen; survive reboots.
- **Weather**: current conditions for any city (Open-Meteo).
- **Device control**: volume, brightness, Wi-Fi, Bluetooth, DND, screen off,
  open app, battery/time info.

## Docs
- [ARCHITECTURE.md](ARCHITECTURE.md) — component and concurrency model.
- [RUNBOOK.md](RUNBOOK.md) — troubleshooting, debugging, performance targets.

## Tech stack
- Kotlin 2.2.21 · Coroutines · Flow · kotlinx.serialization
- Picovoice Porcupine + Sherpa-ONNX (hybrid wake word: Porcupine with a Picovoice account, or fully offline Sherpa-ONNX with no account)
- Sber SaluteSpeech (streaming ASR + TTS via gRPC)
- Sber GigaChat or any OpenAI-compatible API (LLM via SSE, tool calling)
- Room (conversation + alarms) · Open-Meteo (weather)
- Material Components UI
