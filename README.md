# Jarvis — Voice Assistant for Android 10 / HarmonyOS 2.0+

[![CI](https://github.com/drbye78/jarvis/actions/workflows/ci.yml/badge.svg)](https://github.com/drbye78/jarvis/actions/workflows/ci.yml)

> **Status: in active development (pre-1.0).** Version `0.2.2`. APIs, behavior, and on-device storage may change between releases.

Always-listening voice assistant for Android 10+ (minSdk 29) / HarmonyOS 2.0+ devices.
The default build targets Russian (wake word «Джарвис», ASR/TTS language, UI); the
SaluteSpeech and GigaChat providers are multi-lingual. Streaming-first: live ASR,
streamed LLM with tool calling,
sentence-buffered TTS. Alarms and timers that actually ring. Real on-tablet
device control. Pluggable LLM provider — **Sber GigaChat** (native v2), **Yandex
AI Studio**, or any [OI]-compatible endpoint; the two native providers also do
built-in internet search for fresh facts.
Answers arbitrary questions and holds a conversation on any topic.

## Prerequisites
- JDK 17
- Android SDK 36 (`sdk.dir` in `local.properties` or `ANDROID_HOME`)
- Gradle wrapper included: `./gradlew`

## Setup
1. **Pull the Git LFS assets after cloning** (mandatory): the Sherpa-ONNX AAR
   (`app/libs/sherpa-onnx.aar`) and the bundled wake-word model
   (`app/src/main/assets/sherpa_kws/*`) are LFS-tracked, and CI fails on
   unreferenced assets > 1 MB — a fresh clone contains only LFS pointer files
   until you run:
   ```bash
   git lfs pull
   ```
2. (Build only) Set `sdk.dir` in `local.properties` (or use `ANDROID_HOME`):
   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```
   No provider secrets belong in `local.properties` — see step 3.
3. Build and install:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. **Enter provider credentials in-app.** On first launch, open
   **Настройки → Аккаунты и ключи** (gear button) and enter your own:
   - **Picovoice access key** (wake word)
   - **Sber Salute** client ID + secret (ASR/TTS), **or** a **Yandex
     SpeechKit v3** API key — pick the speech backend in
     **Настройки → Речь**; one choice drives recognition *and*
     synthesis. The same Yandex key also powers the **Yandex AI Studio** LLM
     backend (it then needs AI Studio access, not just SpeechKit)
   - **GigaChat** client ID + secret (LLM), **or** a Yandex Cloud API key for
     the **Yandex AI Studio** LLM — pick the provider in
     **Настройки → Помощник**
   The GigaChat pair (and the Salute pair while the Sber backend is active) is
   **validated upfront in the panel as you type** (a live status row: valid /
   invalid / unreachable) and on every «Проверить ключи» press — a typo is
   caught in seconds, not at the next
   voice command.    Credentials are stored encrypted in the Android Keystore
   (`KeystoreVault`, AES-256-GCM) on the device — **nothing secret is ever in
   the APK or in `local.properties`**. GigaChat creds are optional if you use
   the [OI]-compatible provider or **Yandex AI Studio** instead (both
   configured in **Настройки → Помощник**).
   The UI ships in Russian and English (full `values-en`), and the runtime
   spoken phrases follow the locale too (see RUNBOOK for the honest
   English-voice caveat).
5. **Wake word — two engines (hybrid).** In **Настройки → Слушание →
    Дополнительно** you choose
    the engine:
    - **Sherpa-ONNX (recommended, no account):** a fully on-device wake word
      using the bundled `gigaspeech` model that detects «Jarvis» — or any
      English word you type in Settings (the app BPE-tokenizes it with the
      bundled model and refuses words it cannot encode). No Picovoice key,
      no network — offline by design.
    - **Picovoice Porcupine:** built-in "Jarvis", or **load your own `.ppn`**
      trained in [Picovoice Console](https://console.picovoice.ai/) (a Console
      `.ppn` is bound to your Picovoice key). Requires a free Picovoice account.
      The app ships Porcupine **4.x**, and keyword files are version-bound: a
      `.ppn` trained for 3.x is rejected, so download a current one (Console
      now issues v4-format keywords). The built-in "Jarvis" keyword is
      unaffected. The **model** choice is separate from the engine: the
      built-in keyword requires selecting the **"Jarvis (встроенный)" /
      built-in** wake-word model radio, whereas the default model selection is
      **"Custom (bundled)"**, which points at a user-supplied `jarvis_ru.ppn`
      that the repo intentionally does not ship — so merely switching the
      engine to Porcupine without choosing the built-in model fails by design,
      and the detector asks for a key/`.ppn`.
    Switching engines and the sensitivity slider apply live while the assistant
    is running. Custom Sherpa wake words are supported — the app extracts
    models, BPE-tokenizes keywords, and loads via `newFromFile`.
6. Launch Jarvis and follow the onboarding screen.
7. **Maps & routes (optional).** **Настройки → Погода и карты →
   Дополнительно** takes a **Yandex MapKit
   Mobile SDK key** — a different credential from the Yandex Cloud API key
   (SpeechKit / AI Studio). Get it in the Yandex developer cabinet → **MapKit
   Mobile SDK**; it is bound to the app's package/SHA, so a debug build and a
   release build need their own key. It is stored in the Keystore like every
   other secret and read live, but **changing it requires a FULL app-process
   restart** — MapKit cannot be re-keyed inside a running process, and Стоп →
   Запустить is not enough.
8. **Weather (optional).** **Настройки → Погода и карты** sets the default city for weather
   questions; leave it empty to use the device location instead. A configured
   city always wins and needs no location access. To use auto-detect, tap
   **Allow location access** in that screen — the permission dialog lives there
   (weather runs in the background service, which cannot prompt), and access is
   coarse or fine. On a GMS-free, WiFi-only tablet GPS may yield nothing, so the
   configured city is the reliable path.

## Running tests
```bash
./gradlew testDebugUnitTest
```

CI runs the same suite plus `assembleDebug` on every push/PR (see the badge
above — includes the Git-LFS-tracked native assets). It also runs an R8
`assembleRelease` build, a detekt+ktlint `static-analysis` job, and an
advisory `lintDebug` job — all in `.github/workflows/ci.yml`, where the JVM
suite + `assembleDebug` are the push/PR gate.

## Integration testing (live speech/LLM services — local only)

CI never talks to Sber and holds no secrets. Live smoke tests run **locally
only** with your own credentials (owner decision #1): the credentials live in
a gitignored file, never in the repo, the chat, or the APK — the app itself
stores credentials only in the Android Keystore.

```bash
cp local.secrets.properties.example local.secrets.properties
# fill in: Salute Speech (ASR+TTS) and GigaChat (LLM/embeddings) OAuth
# client id/secret pairs — the same values the app asks for in Settings —
# and/or a Yandex Cloud API key (jarvis.yandex.apiKey), which drives the
# Yandex SpeechKit v3 smokes AND the Yandex AI Studio LLM smoke.
./gradlew :app:integrationTest        # live smoke tests (skips with a logged reason if creds are absent)
./gradlew :app:recordSaluteFixtures   # re-records the sanitized fixtures below
```

Environment variables (`JARVIS_SALUTE_CLIENT_ID`, `JARVIS_YANDEX_API_KEY`, …)
are accepted as a fallback; the properties file wins. Values are never printed
by Gradle or the tests.

What the live tests do (tiny, quota-aware payloads):
- **GigaChat**: OAuth fetch, a one-word `chatOnce` prompt capped at 16 tokens,
  a short streaming pass, one live **web-search** turn (a time-sensitive
  question must come back as grounded text), one embeddings call (1024-dim).
- **Yandex AI Studio LLM**: one plain `chatOnce`, one **web-search** turn (a
  time-sensitive question must come back as grounded text), and one
  function-tool turn — all on tiny, quota-aware payloads.
- **Salute / Yandex ASR**: one round trip on **synthetic silence** — asserts
  PROTOCOL HEALTH only (stream opens, authenticates, closes cleanly); an empty
  transcript is the expected outcome and is documented in the test. The Yandex
  variant is the only tier that can catch a vendored-proto mistake, since the
  in-process fakes would agree with a wrong field number.
- **Salute / Yandex TTS**: one synthesis round trip on a fixed probe phrase;
  asserts a non-empty audio payload.

`recordSaluteFixtures` re-captures the **sanitized** fixtures committed under
`app/src/test/resources/recorded/` (same file names, clean diff on
re-record): server responses only — no credentials or request headers, no
timestamps, no user audio or text (the recorder sends silence / a fixed probe
phrase). CI replays those fixtures through the in-process gRPC fakes
(`SaluteFixtureReplayTest`), so the recorded wire shapes stay exercised
without any secrets. See RUNBOOK "Integration testing" for troubleshooting.

## Upgrading from pre-release builds

Installs on any older schema version upgrade destructively: pre-1.0 has no
backward compatibility, so the database is wiped and recreated from the current
entities on first open (alarms and chat history included) in exchange for a
non-crashing upgrade. The schema is now **v2** — the single coordinated
Phase-2 bump (data indices/PKs/FKs, cognitive decay anchors, alert clock
domains plus the `ring_sessions` table). The real, data-preserving migration
chain starts at **v2→v3** if and when the schema freezes; both `1.json` and
`2.json` are exported.

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
/path/to/Android/Sdk/build-tools/<version>/apksigner verify --print-certs \
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
- **Echo cancellation** (opt-in, **Настройки → Слушание → Дополнительно**): *hardware*
  mode routes the mic through the tablet's comms DSP; *software* mode runs a
  built-in adaptive canceller against the assistant's own voice (electrical
  TTS reference) and, with a one-tap system consent, other apps' music —
  experimental, see RUNBOOK for the honest quality expectations and
  validation steps.
- **Chat**: GigaChat, Yandex AI Studio (or any [OI]-compatible provider) with a
  20-message context
  bounded by a character budget — verbose tool results can no longer overflow
  the model's context window (the newest turn is always kept, truncated if
  needed). The assistant answers general questions from its own knowledge and
  keeps a conversation going on any topic; for fresh or changing facts
  (news, rates, prices, "what's on now") it uses the provider's **built-in
  internet search**, which runs server-side and returns cited sources. The
  search results and citations themselves are never spoken — only the grounded
  answer.
- **GigaChat model** (**Настройки → Помощник**): pick the **GigaChat-3
  flavor** — Lightning (default, fastest), Pro (balanced) or Ultra (most
  capable). Sealed at service start like the speech backend, so it applies
  after a restart (the card says so).
- **Yandex AI Studio** (**Настройки → Помощник**): choose the Yandex
  model — **Alice AI** (default), **Alice AI Flash** (fast) or **YGPT 5
  Lite**. It reuses the **same API key as the Yandex speech engine** (the key
  needs AI Studio access, not just SpeechKit). The folder id is detected
  automatically from the key; a manual **Folder ID** override is available if
  the key cannot list models. Also sealed at service start.
- **Memory of facts** (**Настройки → Память**): a long-term cognitive memory
  that remembers things about you, with semantic search over those facts in
  its own section (**Настройки → Память → Дополнительно**). Cloud fact
  extraction is opt-in (`memory.autoExtract`, default off), and the whole
  subsystem can be switched off.
- **Proactive suggestions** (**Настройки → Инициатива**): opt-in spoken
  suggestions based on what Jarvis remembers — **off by default**.
- **Speech backend** (**Настройки → Речь**): **Sber
  SaluteSpeech** or **Yandex SpeechKit v3** — one choice covers
  recognition *and* synthesis. The two providers have separate credential
  fields and separate voice lists, and the selection takes effect after a
  service restart (each provider owns its own channel and auth scheme, so
  there is no live switch — the card says so).
- **Voice picker** (**Настройки → Речь**): the controls follow the selected
  speech backend. Sber: Mila by default, any other Salute voice ID by hand.
  Yandex: a dropdown of the documented v3 voices plus an optional role
  (neutral / good / strict / friendly / whisper / evil, or free text). Both
  have a «Проверить голос» preview that synthesizes through the *active*
  backend. Applies to the next spoken sentence — no restart.
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
- **Weather**: current conditions plus a **daily forecast up to 7 days** for
  any city (Open-Meteo). Weather questions default to your location: a city
  set in **Настройки → Погода и карты** (always wins), otherwise auto-detected GPS. Follow-up
  questions work naturally («а завтра?», «а в Сочи?»).
- **Maps & routes**: «Джарвис, найди аптеку рядом», «построй маршрут до
  Шереметьева» — organization/address search and public-transport or walking
  routes via **Yandex MapKit**. Transit answers include the line (bus/metro),
  the vehicle type, transfer points and stop counts, and «а пешком?» /
  «а на автобусе?» are follow-ups to the same route. No map is shown — Jarvis
  speaks the answer. Needs a MapKit key in **Настройки → Погода и карты →
  Дополнительно**. (Device
  verification is pending — see RUNBOOK.)
- **Device control**: volume, brightness, Wi-Fi, Bluetooth, DND, screen off,
  open app, battery/time info.

## License
[MIT](LICENSE)

## Data sources & attribution
Jarvis talks to a few external services with the credentials **you** supply.
Weather data comes from [Open-Meteo](https://open-meteo.com/), which is free for
non-commercial use and licensed [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/) —
attribution is required, hence this notice. Speech and LLM providers (Sber
SaluteSpeech, Yandex SpeechKit v3, GigaChat, Yandex AI Studio) are used under
your own accounts and their respective terms. Wake-word and on-device ASR/TTS
models run locally: Sherpa-ONNX (Apache-2.0) and Porcupine (Picovoice licence).

Map and route data comes from **Yandex MapKit**, used under your own MapKit key
and the [Yandex Maps terms](https://yandex.ru/legal/maps_termsofuse). Those
terms require on-screen attribution (the logo, an "Open in Maps" button and a
Terms link) that a screen-less voice assistant cannot render; Jarvis ships an
in-app attribution block instead, which is a known limitation rather than
compliance. The terms also cap free-tier use (1,000 unique users/day) and forbid
storing results beyond 30 days.

## Docs
- [ARCHITECTURE.md](ARCHITECTURE.md) — component and concurrency model.
- [RUNBOOK.md](RUNBOOK.md) — troubleshooting, debugging, performance targets.

## Tech stack
- Kotlin 2.2.21 · Coroutines · Flow · kotlinx.serialization
- Picovoice Porcupine + Sherpa-ONNX (hybrid wake word: Porcupine with a Picovoice account, or fully offline Sherpa-ONNX with no account)
- Sber SaluteSpeech **or** Yandex SpeechKit v3 (streaming ASR + TTS via gRPC)
- Sber GigaChat (native v2) **or** Yandex AI Studio — both with built-in internet search —
  or any [OI]-compatible API (LLM via SSE, tool calling)
- Room (conversation, alarms, memory/facts) · Open-Meteo (weather) · Yandex
  MapKit (`com.yandex.android:maps.mobile:4.45.0-full`) — place search +
  transit/walking routing (GMS excluded; see AGENTS.md)
- Material 3 UI (teal/amber day+night design system): home screen with a
  live voice orb (breathing/ripple/thinking/speaking animations), chat-style
  transcript, permission onboarding with status rows and start gating,
  settings organized as a category list («Помощник», «Речь», «Слушание»,
  «Погода и карты», «Память», «Инициатива», «Музыка», «Аккаунты и ключи») with
  a per-category detail screen (including a «Музыка» default-player screen:
  Яндекс / Звук / VK)
