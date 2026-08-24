# Jarvis — Runbook

## Common issues

### "AudioRecord failed to initialize"
- Check that `RECORD_AUDIO` permission is granted (Settings → Apps → Jarvis → Permissions)
- On HarmonyOS: check that no other app is using the microphone
- Try rebooting the device

### "OAuth token request failed (HTTP 401)"
- Verify your Sber credentials in `local.properties` are correct
- Check that the Sber Developer Portal has your device registered
- Tokens expire after ~1 hour; check network connectivity

### "GigaChat request failed (HTTP ...)"
- Verify `GIGACHAT_CLIENT_ID` and `GIGACHAT_CLIENT_SECRET`
- Check that the GigaChat API is accessible from your network
- Verify the OAuth scope is `GIGACHAT_API_PERS`

### "Wake word not detected"
- Verify `jarvis_ru.ppn` is in `app/src/main/assets/`
- Check that the Picovoice access key is valid
- Sensitivity is 0.6 — adjust in `PorcupineDetector.kt` if needed
- Background noise may interfere; try in a quieter environment

### "Service keeps getting killed"
- Huawei PowerGenie: Settings → Apps → App launch → Jarvis → Manage manually → enable all
- Battery optimization: Settings → Apps → Jarvis → Battery → Don't optimize
- Check that the persistent notification is visible (if not, service was killed)
- The 15-minute restart alarm should bring it back; wait up to 15 minutes

### "Ducking not working (music doesn't pause)"
- Settings → Sound & vibration → Notification access → enable Jarvis
- As fallback, the app sends a media-key pause event (works with most players)

## Debugging
```bash
# View all Jarvis logs
adb logcat -s Timber:*

# View only errors
adb logcat -s Timber:* *:E

# Check service status
adb shell dumpsys activity services com.jarvis.assistant

# Check notification listener status
adb shell settings get secure enabled_notification_listeners
```

## Performance monitoring
- Total round-trip latency (wake-word → TTS start): ~1.3–2.2 seconds
- Wake-word latency: <200ms
- ASR latency: 500–1200ms
- LLM time-to-first-token: 800–2000ms
- TTS latency: 100–300ms
- If latency exceeds these ranges, check network (permanent WiFi required)

## Recovery procedures
1. **App not responding**: Kill from system settings, relaunch from launcher
2. **Tokens seem stale**: Restart the app (tokens are fetched fresh on cold start)
3. **Conversation history corrupted**: Clear app data (Settings → Apps → Jarvis → Storage → Clear Data)
