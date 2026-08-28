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
1. Copy `local.properties.example` to `local.properties` and set:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   PICOVOICE_KEY=your_picovoice_access_key
   SALUTE_CLIENT_ID=your_sber_salute_client_id
   SALUTE_CLIENT_SECRET=your_sber_salute_client_secret
   GIGACHAT_CLIENT_ID=your_sber_gigachat_client_id
   GIGACHAT_CLIENT_SECRET=your_sber_gigachat_client_secret
   ```
   (GigaChat credentials are optional if you plan to use the
   OpenAI-compatible provider — configure it in the app's Settings instead.)
2. Download the wake-word model from
   [Picovoice Console](https://console.picovoice.ai/) — create a custom
   keyword for "Джарвис" — and place `jarvis_ru.ppn` in
   `app/src/main/assets/`.
3. Build and install:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. Launch Jarvis and follow the onboarding screen.

## Running tests
```bash
./gradlew testDebugUnitTest
```

## What Jarvis can do
- **Voice**: wake word «Джарвис», barge-in mid-answer, streaming recognition.
- **Chat**: GigaChat (or OpenAI-compatible provider) with 20-message context.
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
- Picovoice Porcupine (wake word)
- Sber SaluteSpeech (streaming ASR + TTS via gRPC)
- Sber GigaChat or any OpenAI-compatible API (LLM via SSE, tool calling)
- Room (conversation + alarms) · Open-Meteo (weather)
- Material Components UI
