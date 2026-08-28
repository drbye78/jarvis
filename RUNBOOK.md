# Jarvis — Runbook

> **Status: in active development (pre-1.0), version 0.2.0.** Procedures may change as the app evolves.
> All latency figures below are TARGETS to be measured on the actual device,
> not marketing numbers. Replace them with your measurements.

## First run

1. Install the APK, open Jarvis → onboarding screen appears.
2. Grant **микрофон** (mandatory). Optionally grant notification-listener
   access (music ducking), battery-optimization exemption (mandatory for
   always-on), write-settings (brightness tool), DND access, device admin
   (screen-off tool).
3. Press **Запустить Джарвиса**.

## Common issues

### "Assistant never responds to the wake word"
- Check the persistent notification says «Ожидание».
- Release builds: `adb shell run-as com.jarvis.assistant cat files/logs/jarvis.log`
  (debug builds: `adb logcat -s Timber:*`).
- Verify the chosen wake-word model is valid and the Picovoice key entered in
  **Settings** is correct. The default bundled `jarvis_ru.ppn` ships in
  `app/src/main/assets/`; a custom `.ppn` must match your Picovoice key.
- Detector errors are SPOKEN (system TTS) and logged — a deaf-but-silent
  assistant is no longer possible.
- Sensitivity adjustable live in Settings (0–1 slider; applies immediately).

### "OAuth token request failed (HTTP 401)"
- Verify Sber credentials entered in **Settings** (gear button).
- Or switch Settings → provider to an OpenAI-compatible endpoint.

### "GigaChat request failed (HTTP ...)"
- Check credentials/scope (`GIGACHAT_API_PERS`) in **Settings**.
- If you switched providers, verify base URL ends with `/v1` and the API key
  is set — the Apply button restarts the service with the new profile.

### "Service keeps getting killed"
- Huawei PowerGenie: Settings → Apps → App launch → Jarvis → Manage manually
  → enable all three toggles.
- Battery optimization: don't optimize.
- The 15-minute watchdog revives the service after system kills. An explicit
  user Stop is respected (watchdog cancelled) until reboot or manual start.

### "Music doesn't pause when Jarvis talks"
- Enable the notification listener for Jarvis (onboarding screen or system
  settings). The media-key fallback now also RESUMES playback afterwards.

### "Alarms don't ring"
- Alarms fire via `setAlarmClock` — check the system alarm indicator appears.
- Do-not-disturb filters can silence alarms: check DND settings.
- Alarms survive reboots (BootReceiver re-arms them from Room).

## Debugging

```bash
# Logs (debug builds)
adb logcat -s Timber:*

# Logs (release builds — rotating files)
adb shell run-as com.jarvis.assistant ls files/logs/
adb shell run-as com.jarvis.assistant cat files/logs/jarvis.log

# Service status
adb shell dumpsys activity services com.jarvis.assistant

# Notification listener status
adb shell settings get secure enabled_notification_listeners

# Run unit tests
./gradlew testDebugUnitTest
```

## Performance targets (to be measured on-device)

| Stage | Target |
|-------|--------|
| Wake word → session start | < 300 ms |
| Session start → ASR stream open | < 500 ms |
| End of speech (server EOU) → final transcript | 300–800 ms |
| Transcript → LLM first token | 800–2000 ms |
| First sentence → TTS audio start | 300–600 ms |
| **Total: end of speech → first audio** | **~1.5–2.5 s** |

Streaming ASR means these numbers no longer grow with utterance length.

## Recovery procedures

1. **App not responding** — kill from system settings, relaunch.
2. **Provider misconfigured** — Settings → switch back to GigaChat → Apply.
3. **Conversation history corrupted** — Settings → Apps → Jarvis → Storage →
   Clear Data (wipes history and alarms; destructive by design).
