# Jarvis — Russian Voice Assistant for Huawei MatePad

Always-listening Russian-language voice assistant for Huawei MatePad SE 11 (HarmonyOS 2.0).

## Prerequisites
- JDK 17
- Android SDK 34 (set `sdk.dir` in `local.properties` or `ANDROID_HOME` env)
- Gradle wrapper (included): `./gradlew`

## Setup
1. Copy `local.properties.example` to `local.properties` and set your Android SDK path:
   ```
   sdk.dir=/path/to/Android/Sdk
   ```
2. Add your API credentials to `local.properties`:
   ```properties
   PICOVOICE_KEY=your_picovoice_access_key
   SALUTE_CLIENT_ID=your_sber_salute_client_id
   SALUTE_CLIENT_SECRET=your_sber_salute_client_secret
   GIGACHAT_CLIENT_ID=your_sber_gigachat_client_id
   GIGACHAT_CLIENT_SECRET=your_sber_gigachat_client_secret
   ```
3. Generate the wake-word model:
   - Go to [Picovoice Console](https://console.picovoice.ai/)
   - Create a custom keyword for "Джарвис" (Jarvis)
   - Download `jarvis_ru.ppn`
   - Place it in `app/src/main/assets/jarvis_ru.ppn`
4. Build and install:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## Device configuration
After installing the app:
1. Launch Jarvis from the app drawer
2. Grant `RECORD_AUDIO` permission when prompted
3. Open the notification listener settings and enable Jarvis (required for music ducking)
4. Disable battery optimization for Jarvis
5. **Huawei-specific**: Go to Settings → Apps → App launch → Jarvis → Manage manually → enable all three toggles (auto-launch, secondary launch, run in background)

## Running tests
```bash
./gradlew testDebugUnitTest
```

## Architecture
See [ARCHITECTURE.md](ARCHITECTURE.md) for the full architecture document.

## Tech stack
- Kotlin 2.2.21 · Coroutines · Flow
- Picovoice Porcupine (wake-word) · Silero VAD
- Sber SaluteSpeech (ASR + TTS via gRPC)
- Sber GigaChat (LLM via SSE, tool-calling)
- Room (conversation + alarm persistence)
- Open-Meteo (weather, free, no API key)
