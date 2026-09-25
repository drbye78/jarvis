# Jarvis — Runbook

> **Status: in active development (pre-1.0), version 0.2.2.** Procedures may change as the app evolves.
> All latency figures below are TARGETS to be measured on the actual device,
> not marketing numbers. Replace them with your measurements.

## First run

1. Install the APK, open Jarvis → onboarding screen appears.
2. Grant **микрофон** and the **оптимизация батареи** exemption (both
   mandatory). Optionally grant notification-listener access (media
   transport / music control), write-settings (brightness tool), DND access,
   device admin (screen-off tool), and **location access** (weather
   auto-detect — Settings → «Погода»; a configured city needs no grant).
3. Press **Запустить Джарвиса**.

## Common issues

### "Assistant never responds to the wake word"
- Check the persistent notification says «Ожидание».
- Release builds: `adb shell run-as com.jarvis.assistant cat files/logs/jarvis.log`
  (debug builds: plain `adb logcat`; tags are class names, so
  `| grep -i <Class>` narrows it).
   - **Engine:** Settings → Wake word. The **default is Sherpa-ONNX** (bundled,
     offline, no account, zero configuration) — a fresh install hears "Джарвис"
     out of the box. **Picovoice Porcupine** is opt-in and needs a free
     Picovoice key plus a keyword model.
   - Porcupine note: the repo does **NOT** ship a `jarvis_ru.ppn` asset — with
     Porcupine selected you must enter a valid Picovoice key; the built-in
     "Jarvis" keyword is resolved by the Picovoice SDK at runtime, and a
     custom `.ppn` (loaded via Settings) must match your key. If Porcupine
     fails to build, switch the engine back to Sherpa-ONNX.
   - **Keyword files are bound to the SDK major version.** The app ships
     Porcupine **4.x**, so a `.ppn` trained for 3.x is rejected at runtime
     ("file belongs to a different version of the library") — re-download it
     from [Picovoice Console](https://console.picovoice.ai/), which now issues
     v4-format keywords. The built-in "Jarvis" keyword is unaffected (the 4.x
     AAR ships a regenerated keyword + params file).
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

### Yandex SpeechKit selected but the assistant stays silent / errors
- Settings → «Движок речи (ASR + TTS)» → **Yandex**. The choice is SEALED
  when the service starts
  (each provider owns its own channel and auth scheme), so after switching you
  must restart: Стоп → Запустить on the home screen. The card states this.
- The Salute card is **hidden and not probed** while Yandex is active — the
  probe is stopped, not merely hidden, so your Sber OAuth credentials are no
  longer sent to Sber's token endpoint. Switching back to Sber re-probes.
- A wrong/expired key surfaces as gRPC **`UNAUTHENTICATED`** (16); a key whose
  service account lacks `ai.speechkit-stt.user` / `ai.speechkit-tts.user`
  surfaces as **`PERMISSION_DENIED`** (7); exhausted quota is
  **`RESOURCE_EXHAUSTED`** (8). All three are logged with the status code only.
- `INVALID_ARGUMENT` on a streaming call is almost always a **vendored-proto**
  problem, not a user error (the in-process fakes cannot catch it) — see
  "Integration testing" below.
- The Yandex key is a **single** value (no folder id, no id/secret pair, never
  expires): Yandex Cloud console → service account → API keys → create, then
  paste the **secret** into Settings. The **same** key also drives the Yandex
  AI Studio LLM when that provider is selected — for that it needs AI Studio
  access, not just SpeechKit (see the Yandex LLM section below).

### "GigaChat request failed (HTTP ...)"
The LLM lane talks the **GigaChat native v2 contract** at
`https://api.giga.chat/v2/chat/completions`. It differs from the old endpoint
on every axis (parts-array `content`, `tools`/`tool_config`, `messages[]`
responses, named `event:` stream lines), so an old-style error usually means
credentials, not the wire shape.

- Check credentials/scope (`GIGACHAT_API_PERS`) in **Settings**.
- **HTTP 400 on every turn** — almost always the model id. Settings →
  «Нейросеть (LLM)» → pick one of the offered **GigaChat-3** flavors (the
  legacy `GigaChat-Pro` is not in the native model list; the selector only
  offers valid ids).
- **`Trust anchor for certification path not found`** — `api.giga.chat` chains
  to the Минцифры Sub CA. `util/SberTrust.kt` bundles those roots and must list
  `giga.chat` in its host-scoped allowlist; a build that drops that line cannot
  reach the API on the device (the system trust store rejects the chain).
- Or switch Settings → Нейросеть (LLM) → **the [OI]-compatible radio**: fill
  Base URL / model / API key, press **Сохранить**. The change takes effect
  after the next service restart (Стоп → Запустить on the home screen) — the
  provider client is built once, when the service starts.

### "Yandex AI Studio request failed (HTTP ...)"
The Yandex LLM lane talks the **AI Studio Responses API** at
`https://ai.api.cloud.yandex.net/v1/responses` with
`Authorization: Api-Key <key>` — the **same key as the Yandex speech engine**
(`SecretVault.KEY_YANDEX_API_KEY`), so there is no second secret to enter.

- **HTTP 401** — the key is not authorized for AI Studio. SpeechKit and AI
  Studio are separate grants: a key scoped only to SpeechKit (e.g.
  `yc.ai.speechkitStt/Tts.execute`) is rejected here. Recreate/re-scope the key
  with `yc.ai.foundationModels.execute` (or `yc.ai.languageModels.execute`).
- **HTTP 403** — the service account is missing the `ai.languageModels.user`
  role (add `ai.assistants.editor` for web search).
- **HTTP 400 `Invalid model URI` / `Unknown folder`** — the folder could not be
  resolved. The client normally discovers it from `GET /v1/models`; if the key
  cannot list models, set **Settings → Нейросеть (LLM) → Folder ID** manually.
  The folder must be the **service account's own folder**.
- **No trust-store error expected**: `ai.api.cloud.yandex.net` chains to a
  **public GlobalSign** root, so Yandex is deliberately NOT in
  `SberTrust.SBER_APEX_DOMAINS` (unlike `giga.chat`). A trust error here means
  something else rewrote the trust config.
- Or switch Settings → Нейросеть (LLM) → **GigaChat** or the [OI]-compatible
  radio; the choice takes effect after the next service restart.

### "Модель Yandex отвечает не тем / хочу другую"
Settings → Нейросеть (LLM) → **Yandex AI Studio** offers Alice AI (default),
Alice AI Flash (fast) and YandexGPT 5 Lite. The model list is what the
**service** publishes per folder (`GET /v1/models`); a stored id the folder no
longer serves falls back to the default rather than failing the turn.

### "Ответы без свежих данных / поиск в интернете не срабатывает"
Web search is a **server-executed built-in**: the request declares
`tools:[{"web_search":{}}]` with `tool_config:{"mode":"auto"}` and the model
decides when to search. If grounded answers never appear:

- The **model must be a native-contract id** (the GigaChat-3 flavors). On a
  legacy endpoint the same request silently ignores `tools` and the model
  honestly says it has no real-time access — that is the symptom to look for.
- Search adds latency (~1.7 s measured) and inflates the request (search
  results are injected server-side), but the existing 45 s LLM budget covers it.
- `tool_execution` progress and `inline_data.sources` are **never spoken** —
  only the grounded answer is. If you hear "web_search" or a URL, that is a
  parser regression, not a service problem.

### «Какая погода?» — не понимает город / отвечает не для моего города
Weather questions default to a location: the city set in **Settings → «Погода»**
always wins; if that is empty, the device location is used.

- **Configured city is the reliable path.** The target tablet is GMS-free
  (no Play Services) and often WiFi-only, so `NETWORK_PROVIDER` frequently
  returns nothing — and GPS hardware may be absent entirely. If weather keeps
  answering for the wrong place, set the city explicitly in Settings.
- **«Не удалось определить местоположение»** — no city is configured AND
  location access is denied or no fix was obtained within ~6 s. Either set a
  city, or grant location access with the card's button (the permission dialog
  can only appear in Settings — the assistant runs in a service, which cannot
  prompt).
- **No city is ever spoken for a GPS position.** Open-Meteo has no reverse
  geocoding and the app adds no third-party service, so a detected position is
  reported as «текущее местоположение». That is by design, not a bug.
- **Forecast days**: the tool returns current conditions plus up to 7 daily
  rows. A follow-up like «а завтра?» is answered from the previous result — no
  second call, so it is instant.

### «Найди аптеку» / «Построй маршрут» — не находит или отвечает ошибкой
The geo tools are backed by **Yandex MapKit** (not the Yandex Cloud key) and
none of this path is device-verified yet — see the MapKit smoke checklist below.
First checks:

- **No key:** Jarvis says «Не настроен ключ Яндекс.Карт (MapKit)…». Add a
  **MapKit Mobile SDK key** in Settings → «Карты» (Yandex developer cabinet →
  MapKit Mobile SDK). The SpeechKit/AI Studio key does NOT work here — MapKit
  has its own key, and it is bound to the app's package/SHA (a debug build and a
  release build need their own).
- **Key changed / “key already set”:** MapKit allows `setApiKey` only ONCE per
  process, so a changed key answers «Ключ Яндекс.Карт изменился…». Стоп →
  Запустить is NOT enough — fully kill and relaunch the app process.
- **No location for a route:** «Построй маршрут» needs an origin. With no
  configured city and no location permission/fix the tool answers honestly
  («Укажи город в настройках»). Set a city or grant location access; the
  configured city needs no GPS and is the reliable path on the GMS-free,
  WiFi-only tablet. (`findPlace` is lenient — a query that names its own place,
  e.g. «аптека в Москве», is searched even without a default location.)
- **Transit answer has no line names:** check `adb logcat | grep -iE
  "MapKit|UnsatisfiedLink"` — a native/library failure surfaces there. Line
  names (bus/metro) come from the SDK's masstransit data; an answer without
  them means the mapper never read the SDK.

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

Offline note: the automatic credential probe is the only *background* network
call the settings panel makes (the memory benchmark and «Проверить голос» are
user-triggered); the app itself works offline with cached tokens.

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
 type, queue presence, plus the discovered MediaBrowserService table
 (`pkg(label)`). One «включи музыку» attempt on the tablet answers the
 per-build questions no static audit can: does this Yandex build honor
 playFromSearch? repeat/shuffle bits? heart rating? Browser root/onSearch
 outcomes log separately under a `BrowserDiag:` prefix — grep
 `adb logcat | grep BrowserDiag` for those.

### «Джарвис, включи <трек>» — Jarvis opens search instead of playing
The cascade is capability-gated and degrades honestly through up to seven
strategies: live-session `playFromSearch` (structured extras when the user
named artist/album/playlist) → browser search + `playFromMediaId` →
browser session-token dispatch → app launch + poll → legacy
MEDIA_PLAY_FROM_SEARCH intent → search-screen deep link → launch-only.

1. Jarvis says «Включил…» — a strategy verified playback matching the
   request. Done. (The chosen strategy is in the `playMusic` tool result
   JSON — `active_session` / `browser_media_id` / `browser_cold_start` /
   `cold_start` / `legacy_intent`; `adb logcat -s MusicDiag` shows the
   capability tables and skip reasons, not the strategy.)
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
4. Player packages: Yandex Music is `ru.yandex.music` (older sideloads
   `com.yandex.music`), Zvuk is `com.zvooq.openplay`, VK Music is
   `com.uma.musicvk` — all three are matched by brand token, so «включи X в
   Звуке» / «в ВК» / «в Яндексе» pin the player. **`com.vk.music` is NOT a
   package** — it is VK Music's internal code namespace (its launcher class is
   `com.vk.music.screens.main.MainActivity`, but the applicationId is
   `com.uma.musicvk`). Older builds stored `com.vk.music` as the preferred
   player; that value is still accepted and canonicalized on write. A player
   not in the known set is still found by its label.

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
- **Alarms and timers on Android 12+:** exact scheduling needs
  `SCHEDULE_EXACT_ALARM` ("Alarms & reminders" in system settings;
  denied-by-default from Android 14 on fresh installs of sideloaded APKs). A
  revoked/absent grant degrades BOTH alarms and timers: `SystemAlertArmer`
  falls back to an inexact path (`setWindow` for timers,
  `setAndAllowWhileIdle` for alarms) and posts a one-time low-importance
  notification ("Точность будильников и таймеров ограничена"), whose note
  warns that alarms and timers may ring up to 10 minutes late. Revoking the
  grant also deletes already-armed exact alarms — `setAlarmClock` is NOT
  exempt from the permission — and they are re-armed on the next
  reconcile/boot.

## Debugging

```bash
# Logs (debug builds; tags are class names)
adb logcat
# or narrow: adb logcat | grep -i <Class>

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

## Integration testing (live services + recorded fixtures)

CI is credential-free by construction: live tests run **locally only**, and CI
replays **sanitized recorded fixtures** (owner decision #1).

```bash
# One-time setup
cp local.secrets.properties.example local.secrets.properties
# fill in the Salute + GigaChat OAuth client id/secret pairs (same values
# the app asks for in Settings; scopes SALUTE_SPEECH_PERS / GIGACHAT_API_PERS)
# optionally add a Yandex Cloud API key (jarvis.yandex.apiKey) — it powers the
#   Yandex ASR/TTS smokes AND the Yandex AI Studio LLM smoke (same key;
#   needs AI Studio access, not just SpeechKit)

./gradlew :app:integrationTest        # live smoke tests (GigaChat + Yandex LLM + Salute/Yandex ASR/TTS)
./gradlew :app:recordSaluteFixtures   # re-record sanitized fixtures into app/src/test/resources/recorded/
```

Behavior without credentials:

- `:app:testDebugUnitTest` (the CI gate) never touches the network — the
  `*LiveSmokeTest*` classes are EXCLUDED from it in `app/build.gradle.kts`, so
  the suite stays green. (`:app:integrationTest` is the tier that compiles
  them in and skips them through JUnit assumptions without credentials.)
- `:app:integrationTest` / `:app:recordSaluteFixtures` print a skip reason
  listing the MISSING KEY NAMES (values are never printed) and exit green.

Yandex live tests (`YandexAsrLiveSmokeTest`, `YandexTtsLiveSmokeTest`) are the
only tier that can catch a **vendored-proto mistake**: the in-process fakes
agree with whatever field numbers the client sends, so a mis-vendored message
is invisible to the JVM suite. A live `INVALID_ARGUMENT` therefore points at
the protos, while `UNAUTHENTICATED` points at the key and `PERMISSION_DENIED`
at a missing `ai.speechkit-stt.user` / `ai.speechkit-tts.user` role on the
service account.

What gets recorded and the privacy note: only **server responses** land in a
fixture — no credentials, no request headers, no timestamps. The recorder
sends synthetic silence (ASR) and a fixed probe phrase (TTS), so no user
audio or text can enter a fixture; ASR error entries are reduced to the gRPC
status code and exception class name. Review the diff before committing.

Common issues:

- **`OAuth token request failed (HTTP 401)` during `integrationTest`** — wrong
  client id/secret in `local.secrets.properties`, or the pair does not belong
  to the listed scope (Salute pair for `jarvis.salute.*`, GigaChat pair for
  `jarvis.gigachat.*`).
- **Live tests skipped inside `integrationTest`** — only one service's
  credentials are present; the other class's `@Before` assumption skipped it.
  Provide all four Sber keys plus the optional Yandex key to run everything.
- **Embeddings smoke fails with HTTP 4xx** — the GigaChat account has no
  embeddings entitlement (the app degrades to the lexical embedder; the live
  smoke reports it honestly).
- **Re-recorded fixtures diff heavily** — recordings are not deterministic
  (transcripts/audio vary run to run); commit the fresh set as a whole. File
  names are stable (`asr_silence_ru.json`, `tts_mila_probe.json`).
- **CI replay test fails after a fixture change** — the committed fixture must
  satisfy the sanitization contract (see
  `app/src/test/java/com/jarvis/assistant/integration/SaluteFixtures.kt`):
  server responses only, no timestamps, error entries carry status code +
  exception class name.

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
   The canceller's internal convergence stats (delay estimate, residual
   error, divergence flag) are NOT logged, so convergence cannot be watched
   from logcat — judge it by this barge-in behaviour.
3. **Music lane (optional, experimental):** Settings → «Захват музыки» →
   «Разрешить захват звука» → system consent dialog (once per service run).
   Start music in a player, then:
   ```
   adb logcat -s AecDiag | grep "playback capture"
   # "playback capture started" on success.
   ```
   The lane's frame counter is not logged, so a silent lane (player opted
   out of capture or the projection died) shows only as the absence of fresh
   capture output — the wake-word-through-music case then needs
   `pauseMusicOnWake`.
4. Recovery after moving the tablet / volume changes: the freeze-reseed
   logic re-adapts within ~3 s and the divergence guard resets pathological
   state — neither is logged, so expect normal barge-in to resume on its own.
5. **Тихая речь при музыке (честный трейд-офф):** residual-гейт сохраняет
   двойной разговор, но тихий голос во время громкой музыки может частично
   подавляться (до `MIN_GATE` = 0.15 ≈ −16.5 дБ) на ~3 с, пока пол не подтянется.
   Если тихую речь «съедает» — по порядку предпочтения: удлинить окно
   продолжения и говорить громче; отключить ПО-эхоподавление и включить
   pause-on-wake; поднять `GATE_OPEN_FACTOR` в `NlmsEchoCanceller` (15 по
   умолчанию — больше = гейт открывается охотнее = речь слышнее, но
   остаточное эхо выше; значение подобрано на синтетике, на устройстве
   мерить ERLE и разборчивость, см. цель выше).
6. **Потеря far-end кадров:** `AecDiag` логирует переполнение очереди
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

Settings → «Голос» shows the controls for the **active speech backend**
(Settings → «Движок речи (ASR + TTS)»):

- **Sber:** Mila (`May_24000`) is the only voice ID verified against the Salute
  synthesis pool by this project; the card also accepts a free-text Salute
  voice ID for advanced users.
- **Yandex:** a dropdown of the documented v3 ru-RU voices (`marina` default)
  plus an optional **role**. The role suggestions follow the selected voice
  (`VoiceCatalog.yandexRolesFor`) — `marina` offers neutral / whisper /
  friendly, `alena` offers neutral / good — because the service rejects a
  voice/role pair it does not support. The field is an editable combo, not a
  closed list: a voice whose roles the docs do not list (e.g. `filipp`, the
  `*_ru` voices) keeps the full vocabulary (neutral / good / strict / friendly
  / whisper / evil), and free text is always allowed. Voice and role are packed
  in-band as `"<voice>:<role>"` by `YandexVoiceSpec`; an empty role collapses
  to the bare voice. A role glued into the speaker name is a silent failure, so
  both directions live on that one class.

«Проверить голос» speaks one sample sentence through the real synthesis+player
lane using the **active** backend (previewing the other backend's voice would
hand its client an ID it cannot speak), and requires a running assistant —
otherwise the toast says so. The voice is resolved **per spoken sentence**, so
a change applies immediately — no service restart. If a custom Salute ID
produces silence or a logcat `TTS stream error`, the ID is not in the pool for
your account/endpoint: return to Mila. The system prompt language (Russian)
does not change with the voice.

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

## Memory subsystem (Phase 1): E2E scenarios and the extraction gate

The informal probe protocol from the cognitive review is replaced by these
scripted, reproducible scenarios and the fixture-based extraction gate.

### E2E scenario: memory basics (Appendix D)

Precondition: fresh `jarvis.db` (uninstall or «Забыть всё» after backing up),
Sherpa engine, voice stop enabled, memory ON in Settings → Память.

1. Say «Джарвис, меня зовут Алексей, я люблю фильмы Тарковского» → the
   assistant acknowledges. `remember_fact` fires (pill «Запоминаю…»); the
   fact lands in `user_facts` with `origin=EXPLICIT` immediately.
2. Power-cycle the device, reopen the app (or just keep it running — memory
   survives both).
3. «Джарвис, как меня зовут?» → answers «Алексей» WITHOUT re-asking (the
   `<memory-context>` block carries the profile line).
4. «Джарвис, что ты обо мне помнишь?» → lists both facts; low-confidence
   facts are labelled «не уверен».
5. «Джарвис, забудь, что я люблю Тарковского» → the assistant lists the
   candidate and asks to confirm → «Да» → the fact is marked FORGOTTEN
   (visible in the Inspector with the «забыт» status; never silently
   deleted).
6. Repeat (3) for the forgotten fact → the assistant states it does not
   remember that particular thing (honest refusal; the name still works).
7. Settings → Память → Показать память → both rows visible with marks →
   «Забыть всё» + confirm → repeat (4) → honest «ничего не помню».

Pass: all seven observations hold, no crashes, and the stop-phrase behavior
is unchanged throughout the scenario. Turn the «Долговременная память»
switch OFF mid-session and repeat (4) → the assistant must not inject
memory content (kill-switch degrades to the pre-cognitive prompt,
snapshot-tested).

### E2E scenario: proactive suggestion, accept and reject paths (Phase 2)

Precondition: Settings → «Проактивность» switch ON (default OFF — flipping
it is the point of the scenario), quiet hours as shipped (23:00–08:00),
quota 2/day, battery charging or > 15%, no headphones/media playing.

Seed the habit (day 1–6): for six consecutive evenings between 19:00 and
21:00 say «Джарвис, включи джаз» and let it play. Each successful
`playMusic` writes one `command_events` row (check `adb shell` dump or the
behavior tables; utterances are NOT stored — only the `q:джаз`
fingerprint).

Day 7, ~20:00, assistant IDLE for ≥ 2 minutes, someone interacted with the
device within the last 4 hours:

1. Within 15 minutes the behaviour ticker evaluates the rule → all gates
   green → FIRED. The assistant asks: «Ты обычно слушаешь „джаз" в это
   время. Включить?» WITHOUT playing anything (`behavior_log` row
   `FIRED`, `habit_rules.lastFiredAt` stamped).
2. **Accept path:** stay silent for the turn to drain, then say (no wake
   word — the follow-up window opened) «да, включи». The normal tool path
   runs `playMusic`; `habit_rules.acceptCount` becomes 1 and the rule is
   ACTIVE.
3. **Reject path (fresh seed or a second rule):** when the assistant
   proposes again, answer «нет» in the follow-up window. After the THIRD
   rejection across sessions the rule goes MUTED for 30 days — no more
   jazz proposals (verified in the tables; unmute happens automatically
   after 30 days, or via «Забыть всё»).
4. **Busy guard:** start music manually in Яндекс Музыка, force a rule
   evaluation (wait for the next tick) — the decision must be DEFERRED
   (`media`), and the suggestion must NOT fire while playback is active.
5. **Quiet hours:** temporarily set quiet 20:00–21:00 in Settings, wait for
   a tick at 20:30 — `BLOCKED(quiet_hours)`, nothing spoken.
6. **Live toggle:** flip «Проактивность» OFF — the next tick is a no-op
   (one flow read), regardless of table contents.

Pass: all six observations; the assistant NEVER auto-executes a tool from a
suggestion; every decision (including refusals) appears in `behavior_log`
(non-FIRED rows throttled to ≤ 1 per rule per hour by design).

### Extraction gate (autoExtract default decision)

`memory.autoExtract` ships DEFAULT OFF. The §10.1 gate — precision ≥ 0.85,
recall ≥ 0.7, zero hallucinations — is now enforced over the full 40-fixture
set (Appendix C format) by `ExtractionEvalTest` (JVM, CI-runnable) over
recorded GigaChat responses run through the real validator + normalizer; the
default flip is deferred until the §10.6 device-side measurements. To extend
the set, add `app/src/test/resources/cognitive/eval/fixtures/fixture_NNN.json`
(dialogue + recorded response + expected/forbidden facts) and re-run
`./gradlew :app:testDebugUnitTest --tests "*ExtractionEvalTest"`.

### E2E scenario: semantic recall + relation questions (Phase 3)

Precondition: fresh `jarvis.db` or an existing store; Sherpa engine;
Settings → Память → memory enabled. The eval gate already decided the
DEFAULT (vectors OFF — see CHANGELOG, the §10.2 negative result); this
scenario exercises the shipped features that do NOT depend on that
verdict, plus the opt-in vector path.

1. «Джарвис, запомни: мой начальник Иванов» → acknowledge; inspector shows
   the RELATION fact (subject user, predicate boss).
2. «Джарвис, кто мой начальник?» → the answer names Иванов (the
   relation-question boost promotes the fact even with zero lexical
   overlap between «начальник» and the stored value).
3. Settings → Память → Семантический поиск по памяти → «Проверить качество
   поиска»: the result line shows the local engine numbers and, when the
   account has embeddings entitlement, the cloud branch. STATIC probe strings
   are sent for the cloud branch — never user facts (the on-screen note says
   so).
4. Selector «На устройстве» → «Построить векторы памяти» → the dialog
   states on-device-only → accept → progress line counts up to the ACTIVE
   fact count; re-press resumes if interrupted.
5. Toggle selector to «Выключено», repeat step 2 → the answer still names
   Иванов (relation recall is vector-independent) and prompts stay
   byte-identical to the Phase 2 path (no vector channel).
6. «Забыть всё» → inspector empty; vector rows and the entity index are
   gone with everything else (the wipe covers the vector/entity tables).

Pass: all six observations, no crashes, quiet-hours/proactive behaviour of
Phase 2 unchanged throughout. ON-DEVICE TODO (honest gap): cloud
vector-build wall time per 100 facts and the gather-latency delta with a
populated `fact_vectors` table — measure on the MatePad and record in the
CHANGELOG Phase 3 performance block.

## Geography (MapKit) — on-device smoke checklist

**Status: PASSED on device (2026-09-25, AGS6-W09, real MapKit key).** Items 1-9 and
11 are covered by the automated `MapKitLiveSmokeTest`
(`app/src/androidTest/java/com/jarvis/assistant/geo/MapKitLiveSmokeTest.kt`,
4/4 pass, self-skips when no key is entered) plus the release/R8 `-dontwarn`
check. It lives in `androidTest`, NOT the JVM suite, because MapKit 4.45.0 ships
Java 21 bytecode while the toolchain is Java 17 — no MapKit class can be loaded
in a unit test (`UnsupportedClassVersionError`).

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.jarvis.assistant.geo.MapKitLiveSmokeTest \
  com.jarvis.assistant.test/androidx.test.runner.AndroidJUnitRunner
```

This smoke earned its keep: it found FOUR defects the JVM suite could not, three
of which would have shipped (wrong init thread, a native crash from no-arg
`RouteOptions()`, and transit sections fanned out into sequential rides).

Prereqs: a **MapKit Mobile SDK key** in Settings → «Карты» (Yandex developer
cabinet → MapKit Mobile SDK); the Yandex Cloud key does NOT work here. MapKit
keys are bound to the app's package/SHA, so a debug build and a release build
need separate keys. To watch the lane:

```bash
adb logcat -c
adb logcat | grep -iE "MapKit|maps-mobile|UnsatisfiedLink|Geo|dalvikvm"
```

1. **Native `.so` load (no `UnsatisfiedLinkError`).** Trigger one `findPlace`.
   Expected: no `UnsatisfiedLinkError`, no native abort, the service stays
   alive, and `libmaps-mobile.so` is in the loaded list.
2. **Headless init from the Service (no `MapView`).** MapKit is normally
   initialized by an app that then shows a `MapView`; this app is screen-less
   and calls only `setLocale` → `setApiKey` → `initialize`. This path is
   **undocumented upstream** and must be confirmed: a search and a route must
   both work from the foreground service with no Activity/MapView ever created.
3. **`onStart()` / `onStop()` lifecycle.** `onStart()` IS now called exactly once,
   immediately after `initialize()`. Upstream documents it as the remedy for LATE
   initialization (anything other than `Application.onCreate` — exactly this lazy
   Service path); it is a foreground notification, not the request pipeline, so it
   is harmless if unnecessary. `onStop()` is deliberately NEVER called: this is an
   always-on assistant, and with no `MapView` a "backgrounded" state would only
   risk stalling an in-flight request. Do not add an `onStop()` without a paired
   `onStart()`.
4. **Play Integrity / attestation on a GMS-less device.** The target
   (Huawei/HarmonyOS) has no Play Services, and the dependency EXCLUDES
   `com.google.android.play:integrity`. Confirm the backend does not require
   attestation — the residual risk is `requestAttestKey()` failing if it ever
   does.
5. **Set-once key across a SERVICE restart.** Start, run a search (key applied),
   then Стоп → Запустить (same process, graph rebuilt). Search must still work
   and logcat must NOT say «API key is already set».
6. **`KeyChanged` path (full APP restart applies the new key).** Change the key
   in Settings: the next geo call must answer the «restart the app» message
   (`GeoError.KEY_CHANGED`), NOT crash. Kill the app process fully and relaunch —
   the new key must now be live.
7. **Live organization search.** «Джарвис, найди аптеку рядом» → a real
   organization/address with coordinates.
8. **Live transit route with line names/transfers.** «Джарвис, построй маршрут
   до <место> на транспорте» → duration, transfers and leg-by-leg line
   names (bus/metro), vehicle type, stop count. This is the whole reason for
   MapKit — an answer without line names means the mapper is not reading the
   SDK.
9. **Walking route.** «а пешком?» → a walking route (pedestrian router), no
   transit legs.
10. **Release build + R8.** `./gradlew :app:assembleRelease` must succeed and
    the release APK must run search/routing — the `-dontwarn` rules cover the
    two excluded GMS artifacts; a release-only `NoClassDefFoundError` means the
    exclusions/ProGuard rules drifted.
11. **APK size / ABI check.** If you change ABIs or the MapKit version:
    ```bash
    unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "lib/.+maps-mobile.so"
    unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "lib/(arm64-v8a|x86_64|armeabi-v7a|x86)/"
    ```
    Expected: `libmaps-mobile.so` only under `arm64-v8a` + `x86_64`; debug APK
    ≈182 MB (was ≈164 MB before MapKit; arm64 `.so` 36,094,376 B, x86_64
    39,290,992 B).

## Known limitations

- **Yandex Maps attribution is voice-only — a known legal risk the owner
  accepted (NOT compliance).** The Yandex Maps terms require the "Open in Maps"
  button, a Terms link, the copyright notice and the logo **on the map/screen**;
  a screen-less voice assistant cannot render any of them. The shipped
  mitigation is a Settings attribution block (a Yandex Maps data notice, a Terms
  link to `https://yandex.ru/legal/maps_termsofuse`, and an "open in Yandex
  Maps" action to `https://yandex.ru/maps`). Note also the terms' caching limit
  (results must not be stored beyond 30 days) and the free-tier cap (1,000
  unique users/day).
- **MapKit key validity is PROVEN for the debug build.** A real key was exercised
  on-device (init `Ready`, live search, walking and transit routing). Still
  untested: a RELEASE-build key (MapKit binds keys per package/SHA, so a release
  key may differ) and the `KeyChanged` path after a key swap.
- **Play Integrity attestation is untested by construction.** The device has no
  Play Services at all (`com.google.android.play` absent), and the dependency
  excludes the artifact, so `requestAttestKey()` can never fire here — the
  residual risk is that the backend might one day *require* it. Everything on the
  search/routing path works without it.
- **Route responses carry no arrival time.** MapKit left `TravelEstimation` empty
  on every live route, so `arrival_text` is null and `getRoute` does not promise
  one. Duration, transfers, line names, vehicle types, stop counts and transfer
  points are populated.
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
  dir), the detector surfaces `DetectorState.Failed` with the reason, and the
  user sees the actionable "deaf" state guidance. To watch the build, use
  `adb logcat | grep -iE "Sherpa|Wake-word|HybridWakeWordDetector"`.


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
- **Binary size.** The Sherpa-ONNX AAR (~47 MB) and the bundled model (~5.3 MB)
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
  Threading note: the browser bind MUST construct `MediaBrowserCompat` on a
  Looper thread. The tool lane runs on `Dispatchers.IO`, so
  `AndroidMediaBrowserGateway.connect()` hops the construction to the main
  looper itself — if that hop is ever removed, `connect()` silently returns
  null for every player (the throw is swallowed by `runCatching`, so nothing
  looks broken) and the whole S0/S2 browser strategy stops running. The device
  test `browserConnect_worksFromLooperlessProductionThread` pins this.
- **Acoustic echo cancellation is opt-in, default OFF (wake word vs loud
  music).** The mic otherwise hears the speaker: loud external playback can
  mask the wake word entirely. Enable it in Settings → «Эхоподавление», or
  use `pauseMusicOnWake` (config, default off, no auto-resume).
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
- **On-device capability is now MEASURED (Huawei AGS6-W09, API 29).** The
  MusicDiag matrix was read after one play attempt; per-player findings:
  - Yandex Music (`ru.yandex.music`): advertises 1 `MEDIA_PLAY_FROM_SEARCH`
    activity and a `MediaBrowserService`; publishes a PAUSED MediaSession on
    launch.
  - Zvuk (`com.zvooq.openplay`): 1 legacy activity and a
    `MediaBrowserService`, but publishes NO MediaSession until playback
    actually starts.
  - VK Music (`com.uma.musicvk`): **0** legacy `MEDIA_PLAY_FROM_SEARCH`
    activities and a `MediaBrowserService`; its live action mask lacks
    `STOP`/`SET_RATING`/`SET_REPEAT_MODE`/`SET_SHUFFLE_MODE`, so honest
    refusals are expected there.
  The browser bind itself is exercised by the device test
  `browserConnect_worksFromLooperlessProductionThread` (see above). Per-player
  capability still varies by app build/version.
- **English locale: UI is fully localized, runtime speech is not.** Every
  user-facing string resource now has an English twin (values-en, 358 keys
  incl. the credential-validation and behavior-setting rows), so the whole UI — Settings,
  onboarding, alarms, music card — renders in English under an English locale.
  Runtime spoken/system messages (turn failures, music outcome details,
  wake-word engine errors) remain hardcoded Russian, and the assistant always
  answers in Russian per the system prompt; localizing those requires plumbing
  a string provider through the session pipeline.
