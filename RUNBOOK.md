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
- Verify Sber credentials entered in **Settings** (gear button). The settings
  panel validates them upfront as you type: a red **«Неверные ключи»** status
  row under the Salute/GigaChat fields means the pair really is wrong — fix it
  there instead of debugging the runtime error.
- Or switch Settings → provider to an OpenAI-compatible endpoint.

### "GigaChat request failed (HTTP ...)"
- Check credentials/scope (`GIGACHAT_API_PERS`) in **Settings**.
- Or switch Settings → Нейросеть (LLM) → **OpenAI-совместимый endpoint**: pick
  the radio, fill Base URL / model / API key, press **Сохранить**. The change
  takes effect after the next service restart (Стоп → Запустить on the home
  screen) — the provider client is built once, when the service starts.

### "Модель иногда подвисает / ошибка сети, но со второй попытки отвечает"
That is the built-in transient-failure retry doing its job: a failed LLM pass
that produced **zero output** is retried once automatically (connection
resets, HTTP 5xx/429, zero-output timeouts). It is NOT retried when partial
output was already spoken (that would duplicate sentences), and 4xx
(bad credentials) fails immediately — see ARCHITECTURE.md «LLM
transient-failure retry». A turn still fails with the spoken error phrase
after the retry budget is exhausted; the user just re-invokes the wake word.

### "Не удалось проверить: нет связи с сервером" (settings validation)
The settings panel probes the Sber OAuth endpoint live while you type (debounced,
~1 probe per pause, plus the **«Проверить ключи»** button and a probe on every
open/save). The amber status means *no verdict*, not *bad credentials*:

- No internet / captive portal / DNS failure → the probe could not reach
  `ngw.devices.sberbank.ru:9443`. Saving still works — the pair is stored and
  validated again next time the panel opens.
- HTTP 5xx or 429 → Sber side; try the button again later.
- A **red** row (HTTP 401/403/4xx) is a real rejection: the Client ID/Secret
  pair (or its scope grant) is wrong. Only Salute and GigaChat pairs are
  probed — they are the mandatory pair. The Picovoice key is optional
  (Porcupine engine only) and is validated by engine init, not probed.

Offline note: the probe is the ONLY network call the settings panel makes;
the app itself works offline with cached tokens.

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


## Echo cancellation (Phase A + Phase B)

All modes are **opt-in, default OFF** (Settings → «Эхоподавление»).

### Phase A — hardware mode

1. Settings → Эхоподавление → «Аппаратное». The probe row tells you whether
   `AcousticEchoCanceler.isAvailable()` on THIS device is true.
   (For the first seconds after Start the service bootstraps on a background
   thread — the row may show «service not running» until the graph is up;
   re-open the card after ~5 s.)
2. Restart the service (mode change rebuilds the AudioRecord). The static
   probe line must be visible **before** restart; the runtime attach outcome
   lands in logcat:
   ```
   adb logcat -s AecDiag
   # expected: hwAec=attached static=true
   ```
3. **Validate wake-word accuracy in comm mode** (the honest risk): play
   normal-level music from any player, then say «Джарвис» 10× from 2 m.
   Compare with AEC off. Sherpa is fairly robust, but the platform NS/AGC in
   VOICE_COMMUNICATION mode can shift the mic characteristics — if detection
   degrades, keep AEC off and use `pauseMusicOnWake` or Phase B.
4. ASR check: with hardware AEC on, run a turn WHILE music plays — the
   transcript should be clean.

### Phase B — software mode (built-in canceller)

What it does: an in-process NLMS adaptive filter (96 ms tail) with
cross-correlation bulk-delay alignment, double-talk detection (adaptation
freeze), divergence guard, and a residual suppression gate. Far-end
references: (a) own TTS — electrical tap of the player's PCM (always on in
software mode), (b) other apps' music — playback capture (optional,
see below).

**It is not WebRTC AEC3** — no Java-exposed APM exists on Maven (checked
2026-09: stream-webrtc-android wraps the *framework* AEC inside its own
pipeline and exposes no standalone APM). Expected suppression on a linear
echo path is 20–35 dB; cheap tablet speakers add nonlinearity the filter
cannot model. The `EchoCanceller` interface is the drop-in slot if a native
AEC3 becomes linkable.

1. Settings → Эхоподавление → «Программное», restart the service.
2. Verify the own-TTS lane: say the wake word; while the answer SPEAKS,
   say «Джарвис» (barge-in). With the tap working, the wake word should be
   recognisable during playback; without it, the answer's own echo masks it.
3. Watch convergence:
   ```
   adb logcat -s AecDiag
   # MusicDiag-style: delay estimate should lock near the true path delay
   # and stay there; errorToFloor ≈ 1 during echo-only spans.
   ```
4. **Music lane (optional, experimental):** Settings → «Захват музыки» →
   «Разрешить захват звука» → system consent dialog (once per service run).
   Start music in a player, then:
   ```
   adb logcat -s AecDiag | grep captureLane
   # frames=0 while music plays ⇒ the player opted out of capture or the
   # projection died — nothing we can do; the wake-word-through-music case
   # then needs pauseMusicOnWake.
   ```
5. Recovery after moving the tablet / volume changes: the freeze-reseed
   logic re-adapts within ~3 s; the divergence guard resets pathological
   state (logged as `hwAec=...` never changes — watch `diverged=true`).
6. **Тихая речь при музыке (честный трейд-офф):** residual-гейт сохраняет
   двойной разговор, но тихий голос во время громкой музыки может частично
   подавляться (до `MIN_GATE` ≈ −8 дБ) на ~3 с, пока пол не подтянется.
   Если тихую речь «съедает» — по порядку предпочтения: удлинить окно
   продолжения и говорить громче; отключить ПО-эхоподавление и включить
   pause-on-wake; поднять `GATE_OPEN_FACTOR` в `NlmsEchoCanceller` (15 по
   умолчанию — больше = гейт открывается охотнее = речь слышнее, но
   остаточное эхо выше; значение подобрано на синтетике, на устройстве
   мерить ERLE и разборчивость, см. цель выше).
7. **Потеря far-end кадров:** `AecDiag` логирует переполнение очереди
   каждой полосы («far-end lane '...' overflow: dropped oldest N frames»).
   Растущий счётчик = темп производителя полосы не совпадает с
   потреблением — страдает именно опорный сигнал (качество AEC), а не
   фильтр; смотреть pacing полосы, а не параметры NLMS.

### Follow-up window (Продолжение диалога)

Settings → «Продолжение диалога»: toggle + window length 2–12 s (default 5 s,
applies LIVE, no restart). After each spoken reply the orb switches to
ripples + a shrinking countdown arc; just keep talking — no wake word needed.
The window closes after silence; the wake word always works too (and
supersedes the window).

Honest limits: the VAD is energy-based — under loud music it can false-fire
(suppress with AEC + capture lane, or pause-on-wake) or miss soft speech
(lengthen the window). A 200 ms lead-in after each reply absorbs the TTS
tail. Chained conversation: every spoken reply re-opens the window.

### Voice selection (Голос)

Settings → «Голос»: Mila (`May_24000`) is the only voice ID verified against
the Salute synthesis pool by this project; the card also accepts a free-text
Salute voice ID for advanced users. «Проверить голос» speaks one sample
sentence through the real synthesis+player lane (requires a running
assistant — otherwise the toast says so). The voice is resolved **per spoken
sentence**, so a change applies immediately — no service restart. If a custom
ID produces silence or a logcat `TTS stream error`, the ID is not in the pool
for your account/endpoint: return to Mila. The system prompt language
(Russian) does not change with the voice.

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
- **Voice stop on-device validation (FIXPLAN B).** The stop phrase (`▁ST O P`)
  is BPE-canonical for the bundled model, but its false-accept/false-reject
  behavior at speaker volume is a hardware question. Ladder: (1) wake word,
  start any answer; (2) say «стоп» mid-answer at 1 m — the answer must stop
  within ~0.5 s and the orb return to idle; (3) say «стоп» while IDLE —
  nothing may happen; (4) play music loudly and confirm the wake word still
  works and «стоп» is not triggered BY the music; (5) with voice stop OFF in
  Settings, step 2 must NOT stop the answer.
- **Custom Sherpa wake words (FIXPLAN C).** Only words the bundled BPE model
  can fully encode are accepted (Settings validates with the real tokenizer
  and shows ✗ for digits/punctuation/Cyrillic). After applying, run the same
  false-accept ladder as above. If the engine build fails (bad custom model
  dir), the detector surfaces `DetectorState.Failed` with the reason — check
  `adb logcat -s JarvisWake`.


- **Sherpa-ONNX startup is async (no ANR).** The engine build now runs off the
  main thread (`Dispatchers.Default`) — the detector starts in `Bootstrapping`
  and transitions to `Ready` once the bundled model is loaded, so startup no
  longer blocks the UI thread on Kirin 710A-class devices (fixes H1). There is
  a brief window where the assistant is "listening" but the wake word is not yet
  active until the model finishes loading (typically well under a second).
- ~~Custom Sherpa wake words are not supported~~ **LIFTED (FIXPLAN C).** The
  AAR's nullable-asset constructor routes to native `newFromFile`, so the
  extracted bundled model (or a user-supplied model dir) loads from the
  filesystem with a GENERATED keywords file. Settings accepts any English
  keyword the bundled BPE model can encode. Porcupine `.ppn` remains an
  alternative engine.
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
- **English locale: UI is fully localized, runtime speech is not.** Every
  user-facing string resource now has an English twin (values-en, 135 keys
  incl. the new credential-validation rows), so the whole UI — Settings,
  onboarding, alarms, music card — renders in English under an English locale.
  Runtime spoken/system messages (turn failures, music outcome details,
  wake-word engine errors) remain hardcoded Russian, and the assistant always
  answers in Russian per the system prompt; localizing those requires plumbing
  a string provider through the session pipeline.
