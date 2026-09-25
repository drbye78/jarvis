# Changelog

All notable changes to Jarvis are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
semver (pre-1.0: breaking changes bump the minor).

## [Unreleased]

### Changed — Settings redesign: category list + reusable detail screens
- **The 1955-line single-scroll Settings screen is now a category LIST →
  detail flow.** `SettingsActivity` is a thin host (header, pending-restart
  banner, a `RecyclerView` of eight `SettingsCategory` rows and an About row),
  and ONE reusable `SettingsDetailActivity` inflates a category's
  `screen_settings_<id>.xml` and binds its controller. The old 12
  `settings_card_*.xml` and the intermediate `settings_list_host.xml` are
  deleted. Adding a screen now touches only the registry + the factory, so the
  screen stops growing with every new setting — the point of the split.
- **Eight controllers replace the inline `setup*` methods.** `Brain`,
  `Speech`, `Listening`, `WeatherMaps`, `Memory`, `Proactivity`, `Music`,
  `Accounts` each became a small class under `settings/controller/` over a
  frozen 3-arg seam `(callbacks, prefs, host)`, wired by
  `SettingsControllerFactory` with an **exhaustive `when` with no `else`** — a
  new category is a compile error until its controller exists (the same idiom
  as the LLM-provider `when` in `AppGraph`). `AppPrefs`/`CredentialsStore` are
  read/written by the controller; every lifecycle, navigation, permission or
  Activity-result consequence goes through the narrow `SettingsHost` (the only
  host implementor is `SettingsDetailActivity`), so those stay in one place.
- **Advanced settings move behind «Дополнительно».** Each detail screen shows
  its essential controls and hides the long tail behind a disclosure row
  (`settingsAdvancedToggle`/`settings_advanced_show`). On «Слушание» the row is
  always present (7 entries — engine, model, imported `.ppn`, Sherpa keyword,
  sensitivity, AEC mode, follow-up window); on «Помощник»/«Речь» it appears
  only when the active provider/backend actually has advanced entries; on
  «Аккаунты и ключи» there is deliberately NO disclosure, because the whole
  screen is access keys and hiding them would empty it.
- **When a change applies is now stated once, by `ApplyPolicy`.** The old
  screen explained restart semantics in four ad-hoc hint strings with no single
  source of truth. `settings/ApplyPolicy` (`LIVE` / `SERVICE_RESTART` /
  `APP_RESTART`) + `ApplyPolicies` (the key→policy map) replace them, and
  `PendingChanges` (process-lifetime, deliberately NOT persisted) drives the
  shared restart banner; the strongest pending policy wins (`APP_RESTART` >>
  `SERVICE_RESTART`). **V1 is instruction-only: the banner tells the user a
  restart is needed and never relaunches the process** (there is intentionally
  no action button). There is deliberately no async/later policy — recording a
  fire-and-forget action (vector build, extraction backfill) as a pending
  restart would instruct the user to restart for something a restart cannot
  affect.
- **The [OI]-compatible API key lives on «Аккаунты и ключи» while its URL/model
  live on «Помощник».** This mirrors GigaChat (whose secret already lived with
  the keys): the «Помощник» screen shows a read-only «Ключ задан / Ключ не
  задан» status row per provider that taps through to the keys screen. Because
  the shared `onSaveLlmProviderSettings` writes all four values, each screen
  passes the OTHER screen's stored values back unchanged — a blank would
  silently erase the sibling field.
- **Dead code removed.** The `sherpaOnnxPath` pref (no writer, no UI, no test
  reference — an unreachable branch) and the unused `ApplyPolicy.ASYNC` value
  (nothing maps to it) are gone.
- **Test guards re-specified for the split.** `SettingsLayoutTest` now asserts
  per-screen id closure, no duplicate/global ids across screens and hosts,
  explicit `<include>` sizing, host-completeness and no orphan
  `screen_settings_*.xml`; new `SettingsCategoryTest` pins the registry and
  `SettingsInventoryTest` reflects over `AppPrefs` + `CredentialsStore` so a
  dropped setting fails the build. `SettingsActivity` went 1955 → ~195 lines.
  There is no Settings instrumentation test; the nightly `androidTest`
  `LayoutBoundsTest` still hardcodes the list host's `settingsRoot`/
  `settingsColumn` ids and the 760 dp cap (preserved), while
  `llmProviderGroup`/`speechBackendGroup` moved to the BRAIN/SPEECH detail
  screens.

### Added — Geography: place search + transit/walking routing (Yandex MapKit)
- **Two new voice tools: `findPlace` (organization/address/place search) and
  `getRoute` (public transport or walking).** The advertised tool surface grows
  19 → 21. `getRoute` returns duration, transfers and leg-by-leg
  detail — line name (bus/metro), vehicle type, transfer point and stop count —
  which the HTTP Maps APIs cannot name; that line-level detail is the entire
  reason the Yandex MapKit Android SDK
  (`com.yandex.android:maps.mobile:4.45.0-full`) was chosen. No map is ever
  rendered: the tools return structured JSON (`GeoJson`) and the LLM speaks the
  answer. Follow-ups («а пешком?», «а на автобусе?») are handled by conversation
  history — the LLM re-calls `getRoute` with the same destination and a new mode;
  the tool description carries that instruction deliberately.
- **New `geo/` lane: a capability, not a provider.** `GeoToolClient` is a narrow
  interface with exactly one implementation (`YandexMapKitGeoClient`) and no
  Settings radio — it mirrors `WeatherClient`/`OpenMeteoWeatherClient`, not the
  sealed-provider pattern used for user-selectable LLM/speech backends. All
  `com.yandex.*` imports stay under `geo/mapkit/**`; every MapKit `Error`
  becomes a typed `GeoResult` (`NO_KEY`/`KEY_CHANGED`/`PERMISSION_DENIED`/
  `UNAVAILABLE`/`NOT_FOUND`/`FAILED`) so no MapKit type escapes, and
  `CancellationException` is always rethrown (barge-in never becomes a fake tool
  error).
- **The location subsystem was extracted out of `tools/weather/` into a shared
  `location/` package and made weather-agnostic.** `tools/weather/` no longer
  exists; `location/` owns `LocationProvider`/`LocationFix`, `ResolvedLocation`,
  `LocationOutcome`, `LocationResolver`/`DefaultLocationResolver` and the
  GMS-free `AndroidLocationProvider` (`LocationManager` only). Weather was
  migrated onto it, and weather and geo share ONE resolver, so the policy is
  single-sourced: a **configured location always wins** (no GPS/permission),
  only a blank configured value falls back to a bounded device fix, and every
  failure degrades to a typed `LocationOutcome` so a tool answers honestly
  instead of inventing a city.
- **GMS-FREE is preserved by dependency exclusion.** `-full` transitively pulls
  `play-services-location` and `play:integrity`, which contradicts the GMS-less
  Huawei/HarmonyOS target. The AAR embeds no GMS (0 `com/google/android/gms`
  entries in classes.jar); only 8 classes reference it, all in
  `com.yandex.runtime.{sensors,attestation_storage}.internal`, and zero in the
  `search/`/`transport/` packages — so both artifacts are `exclude`d.
  `proguard-rules.pro` carries `-dontwarn com.google.android.gms.**` /
  `-dontwarn com.google.android.play.**` for the still-referenced, now-missing
  classes. `-full` is mandatory: `-lite` ships zero search/transport classes.
- **Process-scoped MapKit init + a full-restart key rule.** `MapKitFactory.setApiKey`
  may be called only once per process, and `AppGraph` is rebuilt on every
  service start — so the initializer lives in a process-level singleton
  (`MapKitInitializerProvider`, the `AlarmSchedulerProvider`/`RingCoordinatorProvider`
  idiom), not in the graph. A **changed Maps key applies only after a full
  app-process restart** — stricter than the "sealed at graph construction"
  rule for LLM/speech providers. A changed value returns `KEY_CHANGED`, never a
  crash.
- **New secret: `SecretVault.KEY_MAPKIT_API_KEY` (`mapkit_api_key`).** The
  MapKit Mobile SDK key is separate from the Yandex Cloud (SpeechKit / AI
  Studio) key, stored in the Keystore and read live so it can be set after
  construction.
- **ABIs trimmed for size.** MapKit adds `libmaps-mobile.so` (arm64 ≈36 MB) and
  its consumer ProGuard rules forbid shrinking it, so `ndk.abiFilters` is
  `arm64-v8a` + `x86_64` only. Measured debug APK: **164 MB → 182 MB** (arm64
  `.so` 36,094,376 B; x86_64 39,290,992 B).
- **Privacy/telemetry.** `ArgFingerprints` records only `getRoute`→`mode:*` and
  `findPlace`→`q:*` — never `origin`/`destination`/`near`, so street addresses
  do not reach `command_events`. Geo tools are not in `habitEligibleTools`.
- **New tests**: `GeoToolsTest`, `GeoJsonTest`, `MapKitInitializerTest`,
  `MapKitInitializerProviderTest`, `MapKitRouteMapperTest` (route narration via
  the MapKit-free `RouteView`/`SectionView`/`TransportView` projection — MapKit
  4.45.0 is Java 21 bytecode vs the Java 17 toolchain, so no MapKit class loads
  in a JVM test), and `DefaultLocationResolverTest` (replaces the removed
  `WeatherLocationResolverTest`).
- **Attribution / licensing (owner-accepted residual risk, NOT compliance).**
  The Yandex Maps terms require the «Open in Maps» button, Terms link, copyright
  and logo **on the map/screen**; a screen-less assistant cannot satisfy that as
  written. The shipped mitigation is a Settings attribution block (a Yandex Maps
  data notice, a Terms link to `https://yandex.ru/legal/maps_termsofuse`, and an
  "open in Yandex Maps" action to `https://yandex.ru/maps`). The terms also cap
  free-tier use (1,000 unique users/day) and forbid storing results beyond 30
  days.
- **On-device behavior VERIFIED (AGS6-W09, real key, 2026-09-25).** Native `.so`
  load, headless init from the Service with no `MapView`, live organization
  search, pedestrian routing and masstransit routing (with real bus/metro line
  numbers, stop counts and transfer points) all pass through the new
  `MapKitLiveSmokeTest` in the androidTest tier (4/4, self-skips without a key).
  That smoke found four defects the JVM suite could not, three of which would
  have shipped: `initialize()` must run on the UI thread (it had run on IO), the
  no-arg `RouteOptions()`/`TransitOptions()` constructors abort the process
  (`GetBooleanField(null)`), a masstransit section's `transports` are the
  alternative lines that serve it (emitting each as a sequential ride told the
  user to board three buses in a row), and a blank transfer name became
  `Transfer(to="")`. `near` is now also a bounded search area — as a ranking hint
  alone, «аптека» near Moscow resolved to Almaty. Route responses carry no
  arrival time: MapKit left `TravelEstimation` empty on every live route.

### Added — Weather conversations: forecast + location-aware defaults (Open-Meteo)
- **Weather questions now cover forecasts, not just current conditions.** The
  `getWeather` tool returns current conditions **plus up to 7 daily rows**
  (date, weekday, condition, min/max, precipitation sum + probability, max
  wind), so «а завтра?», «а в выходные?» and «на неделю?» are answerable.
- **Location defaults: configured city wins, else GPS.** A new
  Settings → «Погода» card sets the default city; when it is empty the device
  location is used. Every failure degrades honestly to a typed outcome
  (`PermissionDenied` / `Unavailable`) → a spoken hint to set a city or grant
  access, never an invented city.
- **GMS-free by necessity.** There is no `play-services-location`, so the
  location is acquired with the framework `LocationManager` (`AndroidLocationProvider`),
  API-29-safe (last-known-if-fresh, else a bounded one-shot; `getCurrentLocation`
  is API 30+, so API 29 uses `requestSingleUpdate` with a guaranteed
  `removeUpdates`). Cancellation-safe: a barge-in never leaks a listener.
- **The configured city is the primary path, not a fallback.** The target
  tablet is GMS-free and often WiFi-only: `NETWORK_PROVIDER` frequently returns
  nothing and GPS hardware may be absent, so Settings → «Погода» is the
  reliable route and the card is built as a first-class control.
- **No reverse geocoding — and no new egress.** Open-Meteo has no reverse
  endpoint (verified 404); naming a GPS position would require a third-party
  service, which this app deliberately does not add. A detected position is
  spoken as «текущее местоположение». The only new network traffic is
  coordinates/city → `api.open-meteo.com` (free, keyless, CC-BY 4.0).
- **No `location` foreground-service type — deliberate.** On API 34+ reading
  location while backgrounded would need it, but passing the type to
  `startForeground` requires the permission at that instant; the always-on
  boot/idle path has none, so it would throw and kill the assistant, and
  revoking the permission can stop a running typed FGS. Weather is a foreground
  interaction, so permission is requested from the Settings card (the service
  cannot show a dialog). Access is **coarse + fine**.
- **Tool surface**: `getWeather` keeps its name (so habit detection and the
  proactive labels are untouched) but `location` becomes **optional** and a
  `days` (1–7) parameter is added. Follow-up guidance lives in the tool
  description, not the system prompt (whose size cap is pinned). The behavior
  fingerprint also stops reading the never-sent `city` key and now reads
  `location`.
- **Protocol details pinned by tests**: `daily=` always sends `timezone=auto`
  (without it «завтра» shifts to GMT); the column-oriented parallel arrays are
  parsed by index so a gap cannot mispair a date with another day's value;
  WMO code **97** (heavy thunderstorm) no longer falls into the «облачно»
  default. Config gains `openMeteo*` URLs, `weatherForecastDays`,
  `weatherGpsFixTimeoutMs`, `weatherLastKnownMaxAgeMs` and a per-tool
  `weatherToolTimeoutMs` (20 s > the 15 s registry default, since a turn may
  wait for GPS then do geocode + forecast).

### Added — Yandex AI Studio LLM as a third provider (Responses API + web search)
- **A third LLM backend: Yandex AI Studio.** Settings → «Нейросеть (LLM)» now
  offers **Sber GigaChat**, **Yandex AI Studio**, or any [OI]-compatible
  endpoint. The Yandex backend speaks the **Responses API**
  (`https://ai.api.cloud.yandex.net/v1/responses`) and, like GigaChat, does
  **server-executed web search**, so fresh-fact questions are answered from the
  web without any client round-trip.
- **Reuses the existing Yandex speech key — no second secret.** Auth is
  `Authorization: Api-Key <key>` over the same `SecretVault.KEY_YANDEX_API_KEY`
  as SpeechKit v3, read live per request (no OAuth/`TokenManager`). Caveat
  surfaced in Settings: the key must carry AI Studio access, not just
  SpeechKit — a speech-only-scoped key is rejected with 401.
- **The folder id is discovered, not configured.** The Responses model URI is
  `gpt://<folder>/<model>/latest`, so the client resolves the folder from
  `GET /v1/models` (the 2nd URI segment), `@Volatile`-caching it; resolution is
  lazy on the first call and NEVER in the constructor. A blank-allowed manual
  **Folder ID** override exists for keys that cannot list models. Discovery
  failure is a typed error (→ the normal "не смог ответить" voice), never a crash
  and never a silently empty answer.
- **Protocol is entirely its own** (`YandexAiStudioClient` + `YandexSseParser` +
  `wire/YandexWireDtos.kt`), verified against the live service:
  `input[]`/`instructions` (not `messages[]`); **flattened** tool specs
  (`{"type":"function","name",…}`) rather than the [OI]-nested or GigaChat
  `functions.specifications` shapes; the `event:` line merely duplicates
  `data.type`; there is **no `[DONE]`** (`response.completed` is terminal, with
  an EOF `finish()` fallback); and errors are **RFC-7807** (`{title,status,detail}`).
  The client reuses the shared `SseStream`, so the cancellation-correct
  transport is not forked a third time.
- **Client function calls round-trip.** A tool call arrives as one
  `FunctionCallComplete` whose `ToolCall.id` carries the response's `call_id`
  **verbatim** (never regenerated), which is what the follow-up
  `function_call_output` pairs on. `TurnRunner` needed **zero changes**: a
  web-search turn is `Text` + `Done` with no pending tool calls, and a tool
  turn is the existing loop.
- **No custom trust store needed.** `ai.api.cloud.yandex.net` chains to a
  **public GlobalSign** root, so — unlike `api.giga.chat` — Yandex is
  deliberately NOT added to `SberTrust.SBER_APEX_DOMAINS`.
- **Citations stay internal (product decision).** Both native providers return
  url+title citations; the assistant is voice-first, so URLs are never spoken
  and no citation carrier was added. As part of this, the consumerless
  `GigaChatSseParser.sources` accessor and its two now-unused source DTOs were
  **deleted** rather than kept for a UI that does not exist.
- **Config wiring bug fixed (structural).** `JarvisForegroundService` used to
  hand-copy `ProviderSettings.DEFAULT.copy(...)`, which silently DROPPED
  `gigaChatModel` — making the GigaChat model radio inert in production (and
  which would have dropped the new Yandex fields too). The service now reads
  prefs through the single `AppPrefs.loadProviderSettings()` path, so a field
  added to `ProviderSettings` can no longer be forgotten; `AppPrefsProviderSettingsTest`
  is the round-trip guard.
- **Settings** (`settings_card_llm_provider.xml`): a third provider radio and a
  `yandexBlock` (model radio — Alice AI / Alice AI Flash / YandexGPT 5 Lite —
  plus the optional Folder ID field), sealed at graph construction like every
  other provider choice. New strings in BOTH locales.
- **New tests**: `YandexSseParserTest` (replays real recorded SSE fixtures;
  pins the one-`Done` latch, `call_id` preservation, lane separation),
  `YandexWireTest` (MockWebServer request shape + folder-resolution order),
  `AppPrefsProviderSettingsTest` (all-field round-trip). A live
  `YandexLlmLiveSmokeTest` ran against the real service (plain + web-search +
  function-tool) and passed.
- **Fixtures** (`app/src/test/resources/recorded/yandex/`): sanitized real
  responses (plain / search / function-call, JSON + SSE) with a provenance
  README. No credentials and no user data; the real folder id is replaced.

### Added — GigaChat native API + built-in web search: arbitrary Q&A and open-topic conversation
- **The assistant now answers arbitrary questions and holds a conversation on
  any topic, grounded by GigaChat's server-executed `web_search`.** The LLM is
  already called for every non-blank utterance — what was missing was the
  *capability*: the legacy contract the app spoke had no web search, so a
  question about fresh facts got an honest "no internet access" refusal.
- **Migrated the GigaChat client to the unified native contract** at
  `https://api.giga.chat/v2/chat/completions` (`GigaChatNativeClient`). This is
  a different protocol on every axis, verified against the live service:
  `content` is an **array of parts** (`[{text}]`); tools are
  `tools:[{"web_search":{}},{"functions":{"specifications":[…]}}]` with
  `tool_config:{"mode":"auto"}` (the [OI]-style `tools:[{type:"function"}]`
  shape is rejected with HTTP 400); the response envelope is **`messages[]`**
  (not `choices[]`); and the stream carries **named `event:` lines**
  (`response.tool.in_progress` → `response.tool.completed` →
  `response.message.delta` → `response.message.done`). Sampling moves under
  `model_options`. The legacy `GigaChatClient` is removed; the
  [OI]-compatible provider and `SseParser` are untouched.
- **`web_search` results never reach the speaker.** The built-in runs
  server-side; results arrive as `inline_data.sources` (url + title) and
  progress as `tool_execution` parts. `GigaChatSseParser` ignores both and
  emits only real assistant text, so progress narration or URLs can never be
  spoken. Client function calls still round-trip as `role:"tool"` +
  `content:[{function_result:{name,result}}]`.
- **`TurnRunner` needed zero changes**: a search turn arrives as `Text` +
  `Done` with no pending tool calls, so it takes the existing plain-answer
  branch. Client-tool loops, `maxToolPasses` and the unknown-function path are
  unchanged.
- **Prompt policy** (`SystemPrompt`) now tells the model to answer general
  questions from its own knowledge, keep any topic going, and use the internet
  search for fresh or changing facts — while keeping every existing
  safety / irreversible-action / honesty rule. Still Ru-only and still under
  the pinned prompt-size cap.
- **Settings → «Нейросеть (LLM)» gains the GigaChat-3 flavor** (Lightning /
  Pro / Ultra, default **Lightning** — the fastest search-capable model;
  ~1.7 s on a search turn). Sealed at graph construction like every other
  provider choice, so the card carries the same restart note.
- **`SberTrust` gains `giga.chat`.** `api.giga.chat` chains to the Минцифры
  Russian Trusted Sub CA, so the bundled anchors must be offered for that host
  or the device cannot reach the API at all (the system trust store rejects
  it). Still host-scoped and label-exact.
- **New tests**: `GigaChatSseParserTest` (replays real recorded SSE
  fixtures — asserts lane separation, source capture, exactly-one `Done`),
  `GigaChatNativeWireTest` (MockWebServer: emitted body shape + recorded
  responses), `SseStreamTest` (transport event capture, `[DONE]`, EOF flush),
  plus settings-mapping / prompt / trust-scoping cases. A live
  `web search answers a time-sensitive question` smoke runs in the local
  integration tier and passed against the real service.
- **Fixtures** (`app/src/test/resources/recorded/gigachat/`): sanitized real
  responses (plain / search / function-call, JSON + SSE) with a provenance
  README. No credentials, no user data, model build suffix stripped.

### Added — Yandex SpeechKit v3 as a selectable speech backend (ASR + TTS)
- **Second speech provider.** Settings → «Движок речи (ASR + TTS)» chooses **Sber SaluteSpeech** or
  **Yandex SpeechKit v3**; one choice drives recognition *and* synthesis. The
  choice is sealed at graph construction (each provider owns its channel and
  auth scheme), so it applies after a service restart — the card says so, and
  the session lane needed **zero edits** (`SessionManager`/`TurnRunner`
  reference no provider type).
- **Auth: a single API key.** `Authorization: Api-Key <key>` (case-sensitive
  scheme), stored in the Keystore like every other secret. No OAuth, no IAM
  token exchange, no `x-folder-id` — the service account's folder is implied.
- **Vendored v3 protos** (`app/src/main/proto/yandex_stt_v3*.proto`,
  `yandex_tts_v3*.proto`) with slim service protos so the heavy
  `google/api` + `yandex/cloud` import chain is not pulled in. The clients
  raise gRPC's 4 MB inbound ceiling (16 MB) and pin
  `RawAudio(LINEAR16_PCM, 24 kHz)` for TTS — the service default is 22.05 kHz
  **with a WAV header**, which the 24 kHz headerless playback chain would emit
  as wrong-pitch noise and which would poison the AEC far-end reference.
- **Voice + optional role** for Yandex (dropdown of the documented v3 ru-RU
  voices, `marina` by default; editable-combo role with presets), packed
  in-band as `"<voice>:<role>"` by the new `YandexVoiceSpec` — the single
  source of truth for both directions, since a drift fails silently.
- **Per-backend voice catalog with per-voice roles.** `VoiceCatalog` is keyed
  by provider (`SBER_VOICES` / `YANDEX_VOICES`) instead of being one flat list,
  and every Yandex entry carries the roles the v3 docs list *for that voice*,
  so the role dropdown stops suggesting pairs the service rejects (`marina`
  offers neutral / whisper / friendly, `alena` neutral / good). Roles stay an
  editable combo — a voice whose roles the docs do not list keeps the full
  vocabulary, and free text is always allowed.
- **Live verification tier** (`YandexAsrLiveSmokeTest`, `YandexTtsLiveSmokeTest`,
  local-only, self-skipping) — the only tier that can catch a vendored-proto
  mistake, since the in-process fakes agree with any field number the client
  sends.
- **Salute probing is gated by the backend.** With Yandex active the Salute
  fields are hidden *and their OAuth probe is stopped*, so Sber credentials are
  no longer sent to Sber's token endpoint on behalf of a provider the app was
  told not to call; switching back to Sber re-probes.

### Fixed — VK Music was unreachable: `com.vk.music` is a code namespace, not a package
- **The preferred-player radio never targeted a real app.** The catalog, the
  manifest `<queries>` allowlist and `SettingsMapping.Player.VK` all used
  `com.vk.music` — which is VK Music's internal Java/Kotlin **code namespace**
  (`com.vk.music.screens.main.MainActivity`), not an installed package. Verified
  on device: `com.vk.music` resolves to nothing while VK Music's real
  applicationId `com.uma.musicvk` does. So a VK preference silently degraded to
  the auto priority (Yandex) and the «вк» hint could not target it.
- Corrected to `com.uma.musicvk` in the resolver catalog, the `<queries>`
  allowlist, the Settings mapping and the pref doc comment. The legacy
  `com.vk.music` value an older build persisted is still **accepted** on read
  and canonicalized on write, so an existing install does not lose its choice
  (same precedent as the legacy Yandex id).
- Device-verified, not just unit-tested: a new instrumentation test asserts
  `getLaunchIntentForPackage("com.uma.musicvk")` is non-null (which also proves
  the `<queries>` entry works under package-visibility rules) and that
  `com.vk.music` is not launchable.

### Fixed — the MediaBrowser lane was silently dead in production (Looper-less thread)
- **A whole strategy tier never ran.** `AndroidMediaBrowserGateway.connect()`
  constructs `MediaBrowserCompat`, whose constructor creates a
  `CallbackHandler extends android.os.Handler` through the **no-arg** `Handler()`
  — which throws on a thread without a `Looper`. Production reaches `connect()`
  from `TurnRunner`'s `Dispatchers.IO` tool lane (`FunctionRouter` → `MusicTools`
  → `MusicPlaybackOrchestrator.runBrowserLane`), and the throw was swallowed by
  the construction's `runCatching { … }.getOrNull()` into a plain `null`. The
  lane therefore answered "not installed / refused" for every player: the S0
  search-by-mediaId and S2 cold-start-through-session-token paths were
  unreachable, and the cascade always fell through to the launch/legacy lanes.
- **Device-verified before the fix**, on Huawei AGS6-W09 (API 29):
  `connect()` on `Dispatchers.IO` returned null for all three installed players
  while the same call on `Dispatchers.Main` connected to Zvuk — so this was a
  threading defect, not player behaviour (Zvuk advertises
  `AndroidAutoMediaBrowserService`).
- Fixed by hopping only the construction to the main looper
  (`withContext(Dispatchers.Main)`) inside `connect()`. The scope is deliberately
  narrow: `MediaControllerCompat(Context, Token)` builds **no** Handler, so
  `activeControllers()` and the whole transport lane were never affected, and the
  session's remaining calls are field reads or Binder IPC. No dispatcher is
  injected — a JVM test cannot reach this class (`MediaBrowserCompat` is a
  framework type), so the hop is pinned on-device instead.

### Added — device-tier media/transport coverage (first for this subsystem)
- **The media lane had ZERO device tests** — every cascade, capability-gate and
  honesty behaviour was asserted only against JVM fakes. New
  `MediaTransportDeviceTest` (10 tests, all self-skipping when the environment
  lacks a player or a live session) exercises the real stack on hardware:
  package visibility per player, `hasNotificationListenerAccess()` agreeing with
  an independent read of `Settings.Secure` on a real OEM ROM (the M-6
  flattened-ComponentName tolerance), real `getActiveSessions()` enumeration and
  capability decoding, and — on a genuinely rich live mask — the M-3/M-7
  capability-gate honesty path (LIKE fail-open on an unknown mask, honest
  refusal when the real rating type is not heart, and never a *confirmed*
  success when the session does not publish `SET_RATING`).
- Honest scope recorded in the class KDoc: on the target API-29 device the
  `<queries>` allowlist is not *enforced*, so T1 proves the package ids resolve
  rather than that visibility filtering works; and the "publishes a
  PlaybackState" proxy is definitionally tied to the decoded mask because no
  public accessor exposes the framework state.

### Fixed — REMEDIATION_PLAN N7: CLOUD vectors are purged when the memory cloud toggle is turned off
- **Privacy:** `memory.cloudEnabled=false` previously only *stopped reading* cloud
  vectors — the embeddings of user facts stayed in Room forever. Turning the switch
  off now deletes every CLOUD vector space: a reactive watcher
  (`CognitiveCoordinator.startCloudPurgeWatch()`, wired in `AppGraph.start()`) purges
  the moment the setting flips false, and the nightly `vectorMaintenance()` backstop
  covers a disable that happened while the app was killed. Every engine space other
  than the on-device `LOCAL_ID` is removed, so stale/renamed cloud ids are caught too.
  The log line is content-free (space/row counts only). Covered by the new
  `CloudVectorPurgeTest` (live flip + cold-start backstop).

### Fixed — power receiver registered with an explicit export flag
- **Hardening:** `JarvisForegroundService.registerPowerReceiver()` registered its
  `ACTION_POWER_CONNECTED`/`DISCONNECTED` receiver bare. At target 36 an unflagged
  dynamic registration is deliverable by any app on the device, and `AGENTS.md`
  already claimed the Android 14+ guards were handled — the code was the part that
  was wrong. It now goes through `ContextCompat.registerReceiver(...,
  RECEIVER_NOT_EXPORTED)`, which is a no-op below API 33 as far as the receiver's
  delivery of the two system broadcasts is concerned (the pre-33 path gates the
  receiver behind androidx's own `signature`-level
  `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, supplied by the
  `androidx.core` manifest, so no manifest edit is required).
- New `RegisterReceiverGuardTest` pins the invariant for the whole module: every
  `registerReceiver(` in main sources must either declare an export flag or be the
  `registerReceiver(null, filter)` sticky-broadcast retrieval. Proven non-vacuous —
  it fails on the pre-fix call and on a `flags = 0` variant.

### Fixed — importing a wake-word `.ppn` could fail silently and destructively
- **The copy is now verify-then-replace.** The old inline copy in
  `SettingsActivity.onActivityResult` did `openInputStream(uri)?.use { … }` and
  then persisted `customWakeWordPath` + `wakeWordModel = "custom_user"`
  unconditionally — so a null stream skipped the copy entirely while still
  pointing the detector at a missing or stale file, with no error surfaced. A
  `copyTo` `IOException` (disk full, provider error) escaped the callback
  uncaught on the main thread, and a re-import that failed partway left a
  truncated model where a working one had been.
- New `audio/WakeWordImport` is the single definition of that install: the copy
  lands in a sibling `*.tmp`, is verified non-empty, and only then replaces the
  destination, so a failed import installs **nothing** and leaves the previous
  model intact. The result is an explicit `Copied` / `NoSource` / `Failed`
  outcome — the caller persists the prefs only on `Copied` and otherwise shows a
  new `error_ppn_import` toast and logs a content-free reason. Non-persistable
  URI grants and a failure to release them are now reported instead of throwing.
- New `WakeWordImportTest` (9) covers every failure path; proven non-vacuous —
  restoring the old copy semantics fails 4 of them.

### Changed — P1 hardening (data & privacy)
- **Room: single destructive-upgrade schema at v2 (pre-1.0)**: the pre-release
  migration chain is deleted and upgrades go through
  `fallbackToDestructiveMigration(dropAllTables = true)` — a database from an
  older versioned build is wiped on first open (owner-accepted pre-release).
  The schema is now **v2**, the single coordinated Phase-2 bump (data
  indices/PKs/FKs, cognitive decay anchors, alert clock domains + the new
  `ring_sessions` table); both `1.json` and `2.json` are exported, and the
  real, data-preserving migration chain starts only at the v2→v3 freeze.
- **Notification-id bands split**: assistant notifications own ids 1–999
  (foreground service 1–3, alarm-degrade notice 4); ringing alarms use
  `10_000 + rowId`, with the AlarmManager request code kept as the raw rowId —
  ringing notification and alarm request are now equal by construction via a
  shared helper (`util/NotificationIds.kt`).
- **OAuth token parse failures sanitized**: they are rethrown WITHOUT their
  cause and carry only the cause TYPE in the message — raw
  `kotlinx.serialization` messages quote the offending response literal
  (tokens!), and used to reach the rotating log file via the upstream
  `TurnRunner` ERROR stack trace, where `LogScrubber` has no pattern for
  JSON fragments.
- **One alarm scheduler per process (P2-A wired)**: `AppGraph` now installs
  the graph-owned scheduler into `AlarmSchedulerProvider` at construction, so
  the voice lane and the alarms UI / ringing screen share the SAME armer —
  the one-time «exact alarms denied» note can no longer re-post per lane.
- **Memory-tool failures sanitized**: `remember_fact` / `recall_facts` /
  `forget_fact` now report the exception CLASS, never its message — Room and
  serialization messages quote the user's own stored facts, and the failure
  detail flows into the LLM tool-result JSON and the spoken failure string.

### Changed — REMEDIATION_PLAN Phase 9–10: platform target, 16 KB prebuilts, dependencies, CI
- **`targetSdk`/`compileSdk` 34 → 36 (Android 16)**, landed as two verified steps
  (34→35, then 35→36) so a platform regression could not hide behind the second
  jump. The `lint { disable += "ExpiredTargetSdkVersion" }` suppression is gone —
  it existed only because the app *was* behind target. One real source break from
  Android 16: `MediaProjectionManager.getMediaProjection()` is now `@Nullable`, so
  `audio/aec/PlaybackCaptureFarEndSource.kt` gained an explicit null check (honest
  `AecDiag` log + return) instead of feeding null into the capture configuration —
  the software-AEC far-end lane degrades, it does not crash. minSdk stays 29.
- **Porcupine 3.0.0 → 4.0.2 (16 KB page-size compliance).** `libpv_porcupine.so`
  3.0.0 was loaded at `0x1000` alignment on **all four ABIs**, i.e. non-compliant;
  4.0.2 is aligned at `0x4000` on `arm64-v8a`/`x86_64` and also passes the manual
  RELRO check. The Java API used here is unchanged (4.x `Builder` is a superset and
  `BuiltInKeyword.JARVIS` still exists). **Breaking for custom keywords:** Porcupine
  4.x version-binds `.ppn` files, so a keyword trained for 3.x is rejected at
  runtime — re-download it from Picovoice Console (now v4-format). Documented in
  `README.md` + `RUNBOOK.md`. 4.0.1 is pom-only on Maven Central (no AAR) — do not pin it.
  The rest of the 16 KB work passes the documented checks: every 64-bit `.so` has LOAD
  align `2**14` with congruent `vaddr`/`offset`, `zipalign -c -P 16` verifies, and
  Google's `check_elf_alignment.sh` reports success. The requirement covers 64-bit
  ABIs only (`armeabi-v7a`/`x86` are out of scope), so no ABI filter was added.
  **RELRO (N10):** the vendored `app/libs/sherpa-onnx.aar` libraries initially failed the
  documented **RELRO** check on both 64-bit ABIs — `check_elf_alignment.sh` cannot detect
  this (it inspects only the first LOAD segment). Root cause is upstream (sherpa-onnx /
  ONNX Runtime link with `-Wl,-z,max-page-size` but not `-common-page-size`). **The three
  `libsherpa-onnx-*.so` are now rebuilt and compliant** (see the Phase 11 entry below);
  `libonnxruntime.so` remains non-compliant, accepted and documented.
- **protobuf pinned `3.25.3` → `3.25.9`** (declared `protobuf` + `protoc` together, so
  they cannot diverge). The declared 3.25.3 was never the version in use — grpc-protobuf
  1.83.1 already forced the runtime to 3.25.9, which is past the CVE-2024-7254 fix floor
  (3.25.5) — so this removes latent drift rather than fixing an active exposure. Chose
  3.25.9 over 4.x because grpc-java 1.83.1 explicitly does **not** support protobuf 4.x
  yet (not ABI-compatible; protobuf#17247, grpc-java#11015) and 3.25.9 carries no
  unfixed advisories. The `javax.annotation:javax.annotation-api` `compileOnly`
  dependency was removed: grpc-java ≥ 1.74.0 generates stubs with `@generated=omit`, so
  no generated source references it anymore (verified: zero hits across all 82
  generated files).
- **GitHub Actions pinned to commit SHAs** (all 25 `uses:` lines) with the tag kept as
  a trailing comment, so a moved tag can no longer change what CI runs. Annotated tags
  were dereferenced to their commit. No action major versions were bumped.

### Changed — REMEDIATION_PLAN Phase 11: vendored sherpa 16 KB RELRO fix (N10)
- **Rebuilt the three `libsherpa-onnx-*.so` with both 16 KB linker flags.** The vendored
  `app/libs/sherpa-onnx.aar` (v1.13.6) shipped libraries that failed the documented
  RELRO check `(GNU_RELRO.VirtAddr + MemSiz) % 0x4000 == 0` on both 64-bit ABIs while
  still passing every automated check — `check_elf_alignment.sh` inspects only the first
  LOAD segment, so the misalignment was invisible to it. The upstream CMake passes
  `-Wl,-z,max-page-size=16384` but never `-Wl,-z,common-page-size=16384`, which aligns
  LOAD yet leaves the RELRO end unaligned. The libs were rebuilt from v1.13.6 source
  with the missing flag added (`CMakeLists.txt:207`), using NDK r28 / clang 19 with the
  same configuration as the upstream Android CI (`BUILD_SHARED_LIBS=ON`,
  `SHERPA_ONNX_ENABLE_C_API=ON`) against the already-pinned ORT 1.27.1 prebuilt. All six
  affected libraries (3 libs × `arm64-v8a`/`x86_64`) now report RELRO remainder `0x0`
  and LOAD align `2**14`.
- **The AAR was repacked surgically:** exactly those six `.so` entries changed; every
  other entry — `classes.jar`, `AndroidManifest.xml`, `R.txt`, `proguard.txt`,
  `aar-metadata.properties`, all four `libonnxruntime.so` and both 32-bit ABI
  directories — is byte-identical to the previous revision. Export surfaces were
  compared to rule out an ABI break: the JNI and C-API symbol sets are identical, and
  the only C++-API difference is 7 statically-linked libc++ COMDAT internals that the
  JNI library never references (`DT_NEEDED` lists no `libc++_shared.so` in either
  revision, so there is no shared-runtime coupling).
- **Accepted residual:** `libonnxruntime.so` stays formally RELRO-non-compliant on all
  four ABIs. It is a downloaded upstream prebuilt (`csukuangfj/onnxruntime-libs`
  v1.27.1); no released version fixes it (1.30.0 fails on all four ABIs), `patchelf`
  cannot rewrite RELRO, and a full ORT source build exceeds this machine's RAM. Its
  over-protection slack contains no sections, so bionic is over-protecting padding — a
  formal non-compliance rather than a demonstrated crash. Play's documented gate still
  accepts the bundle (enforcement Feb 1 2027). Tracked as N10.

### Changed — Phase 1 follow-through
- **A timed-out TTS sentence is now treated as NEVER SPOKEN**: when a
  sentence's synthesis deadline fires, the follow-up window no longer opens
  for it — the window exists so the user can keep talking after something
  they HEARD, and a sentence that never made it out of the speaker must not
  open one (the assistant previously sat in a "keep talking" state after a
  silently failed reply).

## [0.2.1] — 2026-09

### Fixed — wake-word engine crashed the process on first listen (device)
- The bundled int8 KWS encoder was a broken static-batch re-export: its
  `/downsample/Reshape_1` baked a constant shape that never matched the
  runtime frame count, so the first `process()` aborted the whole process
  with `Ort::Exception` (SIGABRT) — the app silently closed right after
  "start listening" on the AGS6-W09 target device. Replaced with the official
  int8 encoder from `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`
  (dynamic shapes; tokens/bpe/decoder/joiner were already byte-identical).
  Post-mortem documented in `SherpaKwsEngine.kt`.
- Fixed the mic (mute) button doing nothing when the graph is down (stopped
  or still bootstrapping): it now always responds, and a mute toggled while
  no graph is up is applied when the graph binds.

REMEDIATION_PLAN Phases 0–5: privacy hardening, doc-truth pass, failure-lane
test suites, local-only live-service tier, correctness landmines, cognitive
eval completion, formatting pass. See the entries below (released as 0.2.1;
in-development pre-1.0 line).

### Added — REMEDIATION_PLAN Phase 4: extraction eval completed to the full 40-fixture set (§10.1)
- Authored the remaining 26 RU dialogue fixtures (015–040): explicit remember,
  self-facts, third parties (subject ≠ user), corrections, negations, noise
  probes, sensitive, multi-turn — Appendix C format, self-contained recorded
  responses, parse-valid through the real validator (evidence anchoring,
  provenance). `ExtractionEvalTest` now pins **40** and the §10.1 gate
  (precision ≥ 0.85 / recall ≥ 0.7 / zero hallucinations) **passes on the
  full set**.
- `memory.autoExtract` stays default OFF for now: the harness measures the
  local validation pipeline against recorded responses; the default flip is
  deferred until the §10.6 device-side measurements (owner, P4.2).
- COGNITIVE_PLAN §10.2: documented the P4.3 upgrade path for the vector-recall
  negative result (real-transcript corpus ≥ 200 pairs, held-out RRF re-tuning,
  Kirin 710A latency budget) before `RetrievalGate.LOCAL_BRANCH_SHIPS` may flip.

### Added — REMEDIATION_PLAN Phase 2: live-service integration tier (local-only) + recorded fixtures
- **Local-only live smoke tests** (`./gradlew :app:integrationTest`): GigaChat
  OAuth/chatOnce/streaming/embeddings; Salute ASR round trip on synthetic
  silence (protocol health) and TTS round trip on a fixed probe phrase.
  Credentials come from a gitignored `local.secrets.properties`
  (`local.secrets.properties.example` committed) or `JARVIS_*` env vars;
  values are never printed. Tests skip cleanly (JUnit assumptions) wherever
  credentials are absent — CI is unaffected by construction.
- **Recording pipeline** (`./gradlew :app:recordSaluteFixtures`): writes
  SANITIZED fixtures (server responses only — no credentials, headers,
  timestamps, or user audio/text) under `app/src/test/resources/recorded/`;
  CI replays the committed fixtures through the in-process gRPC fakes
  (`SaluteFixtureReplayTest`) so recorded wire shapes stay exercised without
  any secrets. Committed seed fixtures are hand-written synthetic
  placeholders until the owner records real ones locally.

### Fixed — Room v7: alarm snooze drift
- **`MIGRATION_6_7`** adds an `anchorTimeMillis` column to `scheduled_alerts`
  (backfilled from each row's `triggerAtMillis`) so the next daily occurrence
  is computed from the original recurring time, not the snoozed time;
  exported schema 7.json + JVM migration test coverage.

### Fixed — Android 10 (minSdk 29) correctness pass
The target appliance (Huawei AGS6-W09, HarmonyOS 2.0) reports API 29, so
`minSdk` is 29. A full lint (`NewApi`) sweep found **no unguarded API 30+
calls**, but four genuine Android 10 behavioral gaps were found and fixed:
- **Background-started microphone was silenced on Android 10** (while-in-use
  rule): every non-activity start (BootReceiver on boot / app update, the
  watchdog and maintenance alarms, START_STICKY recreation) used to promote
  the service to a foreground service whose `AudioRecord` returns zeros with
  no error — the assistant looked alive but never heard the wake word.
  Background-originated starts now post a high-priority "tap to activate"
  notification instead (`JarvisForegroundService.postActivationPrompt`);
  the tap opens `MainActivity` with `EXTRA_ACTIVATE_ASSISTANT`, and the
  pipeline starts from a user-present context — the only start flavor
  Android 10 rewards with a working microphone (and the only one Android 12+
  permits at all, which the old boot path violated with a
  `ForegroundServiceStartNotAllowedException` crash).
- **AEC playback-capture lane was dead on arrival on Android 10**: the
  platform refuses to build a capture `AudioRecord` unless the app runs a
  foreground service with the `mediaProjection` type. The manifest now
  declares `microphone|mediaProjection` (+ the API 34+
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission), and the Settings flow
  routes through `JarvisForegroundService.startPlaybackCapture`, which
  promotes the FGS type at runtime (explicit `startForeground` union on
  API 34+; manifest-inherited types on 29–33).
- **Lint errors** (pre-existing, now fixed so `:app:lintDebug` runs clean):
  `WrongConstant` in `AndroidMediaGateway.setShuffleMode` (pass the
  `PlaybackStateCompat` constants directly), `MissingPermission` annotations
  on `AudioRecordSource.start` / `PlaybackCaptureFarEndSource.start` with
  documented upstream gates.
- Known platform limits documented in code: `setExactAndAllowWhileIdle`
  needs no special permission on Android 10 but does on Android 12+
  (`SCHEDULE_EXACT_ALARM` — not requested; timers/alarms are Android-10
  correct, Android-12+ devices will need the permission or a fallback).

### Added — COGNITIVE_PLAN Phase 3 (semantic recall, strictly gated)
- **Room v6 — semantic tables** (§11): `fact_vectors` (one L2-normalized
  float32 embedding per fact, stamped with the engine id + dim),
  `entities` + `fact_entities` (the two-table entity model derived from
  ACTIVE RELATION facts, rebuilt idempotently in nightly maintenance);
  explicit `MIGRATION_5_6` (new tables only) + exported schema 6.json +
  JVM migration contract tests.
- **Embedding engine seam** (§11): `EmbeddingEngine` with two production
  engines — `LexicalEmbedder` (on-device signed-hashing bag-of-stems over
  the same RU token stream the FTS index uses; deterministic, zero egress,
  256 dims) and `GigaChatEmbedder` (OpenAI-style `/api/v1/embeddings` with
  the shared OAuth transport, 1024 dims, entitlement probe with an honest
  Ok/Denied/Transient verdict). A neural on-device model was evaluated and
  REJECTED at design time: +15–25 MB APK and 100 ms-scale inference on the
  Kirin 710A vs the §7.2 40 ms gather budget.
- **§10.2 retrieval gate — RECORDED NEGATIVE RESULT for the local branch**:
  50 hand-authored RU query→fact fixtures; the hybrid (RRF fusion of the
  lexical lane with the local cosine channel) scored recall@5 = 0.800 vs
  the lexical baseline 0.980 — a −18.4 % regression, far below the ≥ +15 %
  ship threshold. Verdict recorded in
  `app/src/test/resources/cognitive/eval/retrieval/results-baseline.json`
  and enforced by `RetrievalEvalTest` + the `RetrievalGate.LOCAL_BRANCH_SHIPS`
  constant: **vectors ship OFF by default**. Consequences per plan §11:
  the cheap, useful part ships (relation-question recall + entity tables),
  and the AUTO selector stays fail-closed until an on-device benchmark
  proves a winner.
- **User-visible `memory.embedder` selector** (§12.4-3, Settings
  «Память» → «Семантический поиск»): Авто / Облако / На устройстве /
  Выключено, default AUTO — resolved live per turn via `EmbedderSelection`
  (benchmark winner from `memory_meta`, else the CI gate verdict, else
  OFF; every unavailable branch fails closed). A live-toggle regression
  test pins the no-restart semantics; `PrefsFlow` pushes the change.
- **On-device benchmark** («Проверить качество поиска»): runs the §10.2
  eval over STATIC SYNTHETIC probes compiled into the app — never user
  facts, so the cloud branch needs no privacy dialog — writes the winner
  to `memory_meta` and shows per-engine numbers in Settings. This is the
  path that can flip AUTO to the CLOUD GigaChat branch on entitled
  accounts (CI cannot measure it).
- **Relation-question recall** («кто мой начальник?»): a conservative RU/EN
  synonym table maps question heads onto the extraction predicate
  vocabulary; matching ACTIVE RELATION facts get the same flat +0.3 boost
  an FTS hit gets. Works directly on the gathered fact snapshots — no
  entity-table read on the hot path, no derivation-lag corruption surface.
- **Opt-in vector backfill** (§12.4-4): chunked (128 local / 16 cloud),
  resumable, progress surfaced in Settings, cloud branch gated by the
  §9.2 egress switch AND a privacy dialog (fact values egress — disclosed
  in plain language); nightly maintenance GCs stale vectors and tops up
  new facts only for the engine the user actually built with.
- **Hygiene**: removed a stray debug `println` from `wipeAll` and debug
  prints from the Phase 2 test fakes (they had slipped past the detekt
  rule via… nothing: caught in the Phase 3 pass).

### Measured — Phase 3 performance report (plan §10.6)
- APK: 161 084 875 bytes (Phase 2: 159 888 770 — delta ≈ +1.14 MB; the
  rejection of the onnxruntime-based local embedder is what kept this at
  1 MB instead of 15–25 MB).
- Test suite: 609 JVM tests / 0 failures (Phase 2 baseline: 568); detekt
  clean, 0 baseline additions.
- Device-side vector-build time (cloud, per 100 facts) and gather-latency
  delta on hardware require the physical MatePad — tracked in RUNBOOK
  (honest gaps: not measurable in CI).

### Added — COGNITIVE_PLAN Phase 2 (temporal context + behaviour)
- **Room v5 — behaviour tables** (2.1): `command_events` (slot-fingerprint
  telemetry, no utterance content), `habit_rules`, `behavior_log` (30-day
  retention) and `session_summaries`; explicit `MIGRATION_4_5` (new tables
  only) + exported schema + JVM/androidTest migration coverage.
- **Command telemetry** (2.1): `CommandEventRecorder` behind the
  `ToolRegistry` execution observer — every tool execution writes one row
  (tool, normalized `argsFingerprint`, ok, latency, origin); habit
  recomputation fires on every 10th event.
- **Habit mining** (2.2): `HabitDetector` clusters VOICE/ok events into
  2-hour buckets (≥5 supports over a 14-day window, allowlist: playMusic,
  getWeather, getNowPlaying, listPlaylists, searchLibrary); rules go
  PROBATION → ACTIVE (first accept, or a fired suggestion aged out clean) →
  MUTED (3 rejections, 30 days) → RETIRED (6 lifetime rejections). Recompute
  never resurrects a muted/retired rule. Nightly maintenance via an inexact
  ~03:30 `AlarmManager` alarm (`ACTION_RUN_COGNITIVE_MAINTENANCE`) plus an
  opportunistic run on service start when the last one is > 20 h old (§9.1).
- **Arbitration** (2.3): the `BehaviorArbiter` gate matrix — enabled +
  quiet hours (23:00–08:00 default) → DND/battery → session IDLE → no media
  → presence within 4 h → 72 h cooldown + daily quota → 24 h per-suggestion
  freshness. Media/busy sessions DEFER (re-checked by the 15-min ticker);
  every decision is logged (non-FIRED rows throttled to ≤1/rule/hour).
- **Proactive delivery** (2.4): `SessionManager.speakProactively` — a
  guarded mini-session (IDLE-only re-check, seq bump, IDLE → SPEAKING →
  IDLE, suggestion persisted with the `proactive` marker BEFORE synthesis,
  `TtsSpeechFeedback`-style focus bracketing, stop lane armed by the
  SPEAKING state) followed by a forced follow-up window: «да, включи» runs
  the normal tool path and reinforces the rule; a short explicit «нет»
  counts as a rejection. Deterministic RU/EN templates — no LLM call.
  **Ships DEFAULT OFF** (§12.4-1), Settings «Проактивность» card exposes
  the switch, quiet hours and the daily quota.
- **Summaries** (2.5): `Summarizer` — summarize-before-prune (the doomed
  range is captured BEFORE the retention delete; the cloud call is
  fire-and-forget on the cognitive scope; the `lastSummarizedMessageId`
  cursor advances only after a successful commit), a nightly DAILY digest
  (once per epoch day, ≥2 sessions), and a ≤ 600-char `<summary-context>`
  prompt section (presence-gated; cloud-gated per §9.2 as the one new
  egress class).

### Measured — Phase 2 performance/battery report (plan §10.6)
- APK: 159 888 770 bytes (Phase 1: 159 631 038 — delta ≈ +0.25 MB, budget
  ≤ 0.5 MB: met).
- Test suite: 568 JVM tests / 0 failures (Phase 1 baseline: 511); detekt
  clean, 0 baseline additions.
- Device-side RSS / TTFT / overnight-drain numbers require the physical
  MatePad; tracked in RUNBOOK Appendix F (procedure) — to be measured on
  hardware and recorded here (honest gaps: not measurable in CI).

### Fixed — Phase 2 drive-by
- `CognitiveCoordinator`'s default `inTransaction` wrapper (`{ it }`) merely
  RETURNED the block instead of invoking it — the transaction body never ran
  under the default (production wiring was correct; caught by the new
  `CognitiveBehaviorTest`).
- Daily-quota accounting was stale within a single arbitration pass (two
  rules could both fire at quota=1); now tracked per-pass.


### Added — COGNITIVE_PLAN Phase 1 (memory core)
- **Room v4 — cognitive tables** (1.1): `user_facts` (+ `fact_fts` external
  FTS4 index written pre-tokenized via the Russian-aware `SearchTokenizer`),
  `extraction_queue` (exactly-once per message) and `memory_meta`; explicit
  `MIGRATION_3_4` (new tables only, sync triggers included, existing data
  untouched) + exported schema + JVM/androidTest migration coverage.
- **`CognitiveCoordinator`** (1.2): one coordinator owning the read path
  (`gather` ≤ 40 ms, degrade-quiet), the write path (durable queue → batched
  GigaChat extraction, 3 turns/call, 90 s idle flush, 30 s 429 backoff,
  quarantine after 3 attempts, RUNNING-row crash recovery) and maintenance
  math (confidence decay with 60-day half-life ranking, 500-fact cap,
  90-day supersession retention). All switches consumed reactively from
  `PrefsFlow` — toggles apply from the next turn, no restart.
- **Memory tools** (1.5): `remember_fact` / `recall_facts` / `forget_fact`
  with the `MemoryOutcome` honesty contract (WRITTEN / MERGED /
  NEEDS_CLARIFICATION / FAILED / DISABLED / two-step FORGET with a
  stateless confirmation token), ToolStrings RU/EN + status pills.
- **`PromptComposer` + `PromptContext`** (1.6): per-turn context, one memory
  gather per turn started the moment ASR finalizes (hidden in LLM TTFT),
  deterministic ≤ 1 200-char `<memory-context>` section with drop-lowest
  budget rule; with memory disabled the prompt is BYTE-IDENTICAL to the
  pre-cognitive baseline (snapshot-tested).
- **Ingest hook** (1.7): every persisted user message is gated by the
  offline `ExtractionGate` heuristic (explicit memory verbs, first-person
  self-statements, likes/dislikes, life-fact patterns) and enqueued
  fire-and-forget; PROACTIVE-origin turns are never ingested.
- **Settings «Память» + Memory Inspector** (1.8): the four §12.4 switches
  (memory, auto-extract, cloud, sensitive-visible), opt-in one-shot backfill
  of the retained dialogue with a privacy note, and an inspector screen with
  per-item delete, JSON export (SAF) and «Забыть всё» (cognitive tables
  only — history untouched). Sensitive facts are visible-but-marked.
- **Extraction eval harness + starter fixtures** (1.9): 14 annotated RU
  dialogues run through the real validator/normalizer in CI; the §10.1 gate
  (precision ≥ 0.85, recall ≥ 0.7, zero hallucinations) — `memory.autoExtract`
  stays DEFAULT OFF until the full 40-fixture set passes it.
- On-disk message retention raised to 200 rows (the LLM window stays 20) so
  the opt-in backfill has material to work on (1.9).

## [0.2.0] — 2026-09-05

Phase 0 of COGNITIVE_PLAN.md ("Debt, Foundations, Guardrails") on top of the
audit-remediation (FIXPLAN) work.

### Fixed
- **Voice-stop live toggle**: the Settings switch now rebuilds the live
  wake-word engine (`onVoiceStopToggled` → `reconfigureWakeWord()`) and the
  stop-phrase handler re-checks the live preference before cancelling a turn
  — the toggle works in all 4 engine×toggle combinations without a restart,
  with regression tests (COGNITIVE_PLAN 0.2).
- **Stop-lane rebuild race**: the dedicated stop lane is re-evaluated after
  every primary-engine swap (`armStopLaneIfNeeded` at the tail of
  `buildAndSwap`); a lane that becomes redundant mid-build is released, and a
  superseded lane can no longer leak (COGNITIVE_PLAN 0.3).
- **KeystoreVault self-heal narrowed** (0.4): only key-material failures
  (`GeneralSecurityException`/`ProviderException`/`IOException`/truncated
  entry) are healed; a destructive heal is budgeted once per process — a
  second undecryptable entry in the same process returns null without
  deleting (systemic keystore failure stops destroying data); anything else
  propagates.
- **Hermetic weather tests**: `OpenMeteoWeatherClient` gained base-URL seams
  and `WeatherClientTest` now runs against MockWebServer only — the suite
  no longer silently calls the live open-meteo endpoints (which hung
  network-restricted environments and made CI non-deterministic).

### Removed
- Unreferenced fp32 encoder `sherpa_kws/encoder-epoch-12-avg-2-chunk-16-left-64.onnx`
  (~11 MB) — the APK ships only the int8 encoder the code loads (0.5).
- Dead `security-crypto` version-catalog entries (the library has been gone
  since the KeystoreVault migration) (0.5).

### Added
- detekt + ktlint formatting gate (`config/detekt/`), CI `static-analysis`
  job, `ForbiddenMethodCall` for `println`/`android.util.Log`, checked-in
  baseline; CI asset-audit step failing on unreferenced assets > 1 MB (0.6).
- `PrefsFlow`: reactive StateFlow wrappers over `AppPrefs` for all wake-word,
  voice-stop and follow-up settings — the foundation for the Cognitive Core's
  live switches (0.7).
- `LlmClient.chatOnce` (non-streaming convenience) and `withLlmRetry`
  (bounded transient-failure retry with backoff) for the Cognitive Core's
  queue workers (0.8).
- `AGENTS.md` truth pass: corrected the stale Sherpa "asset-only AAR" claim
  (custom `newFromFile` loading IS supported since FIXPLAN C), the minSdk
  24/30 contradiction and the EncryptedSharedPreferences mention; added the
  Cognitive subsystem conventions (0.1).

## [0.1.0] — 2026-08

Initial tracked state and audit remediation (FIXPLAN): streaming SaluteSpeech
ASR/TTS + GigaChat SSE LLM with native tool calling, hybrid Sherpa-ONNX /
Porcupine wake word with custom-keyword support, wake-word-free voice stop,
follow-up window, AEC lanes, alarms/timers, KeystoreVault secrets, honest
tool outcomes, 396 JVM tests.
