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
   - **Engine:** Settings → Wake word. The **default is Sherpa-ONNX** (bundled,
     offline, no account, zero configuration) — a fresh install hears "Джарвис"
     out of the box. **Picovoice Porcupine** is opt-in and needs a free
     Picovoice key plus a keyword model.
   - Porcupine note: the repo does **NOT** ship a `jarvis_ru.ppn` asset — with
     Porcupine selected you must enter a valid Picovoice key; the built-in
     "Jarvis" keyword is resolved by the Picovoice SDK at runtime, and a
     custom `.ppn` (loaded via Settings) must match your key. If Porcupine
     fails to build, switch the engine back to Sherpa-ONNX.
   - If Sherpa fails to load you'll see a logged "Sherpa model failed to
     load" — the bundled assets under `app/src/main/assets/sherpa_kws/`
     must be present in the APK.
- Detector errors are SPOKEN (system TTS) and logged — a deaf-but-silent
  assistant is no longer possible.
- Sensitivity adjustable live in Settings (0–1 slider; applies immediately).

### "OAuth token request failed (HTTP 401)"
- Verify Sber credentials entered in **Settings** (gear button).
- Or switch Settings → provider to an OpenAI-compatible endpoint.

### "GigaChat request failed (HTTP ...)"
- Check credentials/scope (`GIGACHAT_API_PERS`) in **Settings**.
- Or switch Settings → Нейросеть (LLM) → **OpenAI-совместимый endpoint**: pick
  the radio, fill Base URL / model / API key, press **Сохранить**. The change
  takes effect after the next service restart (Стоп → Запустить on the home
  screen) — the provider client is built once, when the service starts.

### "Service keeps getting killed"
- Huawei PowerGenie: Settings → Apps → App launch → Jarvis → Manage manually
  → enable all three toggles.
- Battery optimization: don't optimize.
- The 15-minute watchdog revives the service after system kills. An explicit
  user Stop is respected (watchdog cancelled) until reboot or manual start.

### "Music doesn't pause when Jarvis talks"
- The assistant's TTS now requests transient-may-duck audio focus, so
  compliant players (Yandex Music included) duck their stream for the
  confirmation instead of talking over it. If a player ignores ducking the
  confirmation is still audible — cosmetic only.
- For a completely silent listening window, enable `pauseMusicOnWake` in
  the config (default off: music does NOT auto-resume — say «продолжи»).

### «Джарвис, включи <трек>» — first-line diagnostics

```bash
adb logcat -s MusicDiag
```

Every play attempt dumps the ground truth: each live session's action mask
 decoded (playFromSearch/seekTo/rating/repeat/shuffle/speed bits), rating
 type, queue presence, plus MediaBrowserService discovery. One «включи
 музыку» attempt on the tablet answers the per-build questions no static
 audit can: does this Yandex build honor playFromSearch? repeat/shuffle
 bits? heart rating? browser root? onSearch?

### «Джарвис, включи <трек>» — Jarvis opens search instead of playing
The cascade is capability-gated and degrades honestly through up to seven
strategies: live-session `playFromSearch` (structured extras when the user
named artist/album/playlist) → browser search + `playFromMediaId` →
browser session-token dispatch → app launch + poll → legacy
MEDIA_PLAY_FROM_SEARCH intent → search-screen deep link → launch-only.

1. Jarvis says «Включил…» — a strategy verified playback matching the
   request. Done. (`adb logcat -s MusicDiag` shows WHICH strategy —
   `active_session` / `browser_media_id` / `browser_cold_start` /
   `cold_start` / `legacy_intent`.)
2. Jarvis says «Секунду…» then plays — normal cold start (bind + verify
   can take a few seconds).
3. Jarvis says «открыл поиск — нажми на трек» — every strategy failed or
   was capability-skipped. Check:
   - Notification listener access granted for Jarvis (Settings → Special
     access → Notification access). Note: even WITHOUT it, the MediaBrowser
     token lane works — a refusal there means the player build gates it.
   - The player is LOGGED IN and started at least once.
   - Player app is up to date — vendors ship assistant integrations
     (playFromSearch / onSearch / browser service) per build.
4. Yandex Music package: current builds use `ru.yandex.music`, older
   sideloads `com.yandex.music` — both are matched. Other players (Звук,
   VK Music) are found by label; name the app in the command («включи X в
   Звуке») to pin it.

Status is always honest: `playing` (verified against the request),
`search_opened` (user must tap), `app_opened` (player on screen), `error`
(no player/no access).

### «Промотай/лайкни/повтори/перемешай» — Jarvis says the player doesn't support it
That is the capability gate working, not a bug: the session's action mask
(the MusicDiag dump) genuinely lacks the bit (or the rating type isn't
"heart", or the tablet is below Android 10 for speed). Older/odd players
publish minimal masks; nothing can be done from our side.

### «включи музыку» does nothing
The empty-query semantics need a player that advertises `playFromSearch`
(session in STOPPED state) or a paused session to resume. If neither
exists, Jarvis answers instructively instead of pretending.

### «включи в Звуке» — Zvuk specifics and the one-minute deep-link check
Zvuk (`com.zvooq.openplay`) works through the same cascade as everyone
else: transport controls need an active session, cold starts go through
the MediaBrowser token lane (Zvuk's official Android Auto support is the
strongest `playFromSearch`/browser-service signal of any RU player),
and the launch/legacy lanes cover the rest. The ONE thing Zvuk lacks
today is a deep-link entry: zvuk.com's web-search URL shape could not be
verified from the dev environment (geo/bot-blocked), and an unverified
link would make Jarvis claim «открыл поиск» while the user stares at a
wrong page — so `SearchLinks` deliberately returns nothing for Zvuk.

The one-minute on-device check that re-enables it:
1. Open zvuk.com in the tablet's browser, search any track, and look at
   the address bar: if the URL is a stable `/search?query=…`-shaped path
   (not a JS hash or a redirect chain), the shape is confirmed.
2. Check whether that URL opens the Zvuk APP (App Links) or stays in the
   browser. Only an app-resolving URL is worth adding as a link.
3. Add the entry to `SearchLinks.searchUris` for `com.zvooq.openplay`
   and flip the `zvuk intentionally has no unverified deep links` test in
   `SearchLinksTest` to pin the confirmed shape.

### "Alarms don't ring"
- Alarms fire via `setAlarmClock` — check the system alarm indicator appears.
- Do-not-disturb filters can silence alarms: check DND settings.
- Alarms survive reboots (BootReceiver re-arms them from Room).

## Debugging

```bash
# Logs (debug builds)
adb logcat -s Timber:*

# Music lane ground truth (capability table + browser discovery)
adb logcat -s MusicDiag

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

## Known limitations

- **Sherpa-ONNX startup is async (no ANR).** The engine build now runs off the
  main thread (`Dispatchers.Default`) — the detector starts in `Bootstrapping`
  and transitions to `Ready` once the bundled model is loaded, so startup no
  longer blocks the UI thread on Kirin 710A-class devices (fixes H1). There is
  a brief window where the assistant is "listening" but the wake word is not yet
  active until the model finishes loading (typically well under a second).
- **Custom Sherpa wake words are not supported.** The bundled sherpa-onnx AAR
  (v1.13.6) only exposes a non-null `AssetManager` constructor, which loads the
  model from APK assets — a user-supplied `.onnx` directory cannot be loaded and
  would crash natively. Custom wake words are available via **Picovoice
  Porcupine** (your own `.ppn` from Picovoice Console, bound to your free key). A
  self-trained Sherpa model would require a different AAR build.
- **On-device wake-word validation required.** The bundled "Jarvis" keyword was
  BPE-tokenized for the `gigaspeech` model and its tokens were verified against
  `tokens.txt`, but detection accuracy and the sensitivity→`keywordsThreshold`
  mapping should still be validated on the target hardware (Kirin 710A-class).
- **Binary size.** The Sherpa-ONNX AAR (~47 MB) and the bundled model (~17 MB)
  are tracked via Git LFS (run `git lfs pull` after clone).
- **Hands-free music start depends on the player app.** Jarvis drives external
  players through a capability-gated cascade (`playFromSearch`, MediaBrowser
  search/token, legacy intent); if the installed player build implements none
  of them, Jarvis honestly falls back to opening the app's search screen
  (`search_opened`) instead of pretending it played something. The MusicDiag
  logcat dump reveals per-build support on day one.
- **Background activity starts are restricted (Android 10+).** Launch/deep-link
  strategies (cold start, legacy intent, search screen) can be silently
  blocked when Jarvis's own UI is not visible — a foreground service is NOT
  an exemption. The browser bind is immune (it is not an activity); launch
  outcomes are phrased as attempts with a contingency instruction.
- **No acoustic echo cancellation (wake word vs loud music).** The mic hears
  the speaker: loud external playback can mask the wake word entirely.
  Ducking softens this; the full mitigation is `pauseMusicOnWake` (config,
  default off, no auto-resume).
- **Rich transport is player-dependent.** seek/like/repeat/shuffle/speed are
  gated on the session's action mask and rating type; media-key fallback only
  covers play/pause/next/previous/stop. Unsupported actions get an honest
  refusal naming the limitation.
- **Deep-link schemes are undocumented.** The `yandexmusic://` URI scheme is
  not published by Yandex; the `/search?query=` path is inferred from
  community sources and may not resolve on all builds. The
  `https://music.yandex.ru/search/…` fallback opens a browser page, not the
  app. Deep links are a last-resort honest fallback, not a reliable path —
  and Zvuk ships none until its shape is confirmed (see «включи в Звуке»
  above).
- **Playback verification is fuzzy, deliberately.** The request-vs-now-playing
  match uses weighted token overlap (title 0.65 / artist 0.35) with a strong
  threshold of 0.5. A cover, remix, or compilation featuring the requested
  artist can verify as "playing" even when it is not the exact recording the
  user meant. The alternative — reporting `search_opened` for every
  near-match — is worse; exact-match does not exist for unstructured search.
- **On-device capability validation is still pending.** The MusicDiag
  capability matrix (`adb logcat -s MusicDiag` after one play attempt) is
  designed to answer, on the target hardware and CURRENT player builds,
  whether `playFromSearch`, browser `onSearch`, repeat/shuffle bits, and
  heart rating are actually exposed. Until that dump is read, every
  capability is an assumption the cascade degrades gracefully around.
- **English locale is partial.** The i18n pass localized the newest strings
  (TTS phrases, now-playing, weather, device info); the rest of the UI —
  including the Settings «Музыка» card and onboarding rows — still renders
  the Russian defaults under an English locale, and the assistant itself
  always answers in Russian per the system prompt.
