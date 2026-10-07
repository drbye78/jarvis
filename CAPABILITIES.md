# Jarvis — Capabilities & Actions Registry

A single, **code-derived** reference for what Jarvis can and cannot do: every
voice action, the external service behind it, the credential it needs, and its
honest limitations. Use it to answer "can Jarvis do X?" without reading the
source.

**Scope.** Everything here is derived from the code at the revision noted
below, not from prose. Where a claim is code-enforced it cites the file.

| | |
|---|---|
| **Snapshot** | `main` @ `1980196` (v0.2.2, pre-1.0) |
| **Sources of truth** | `tools/ToolRisks.kt` (tool names + risk) · `settings/SettingsInventory.kt` (settings) · `settings/ApplyPolicies.kt` (apply policy) · `config/JarvisConfig.kt` (endpoints/providers) |
| **Keep it honest** | When you add/remove a tool, setting, or provider, update this file in the same change. `ToolRisksTest` pins the tool set; `SettingsInventoryTest` pins the setting set — if a table below drifts from those, this doc is wrong. |

---

## 1. At a glance

- **Voice-first, Russian by default.** Wake word → streaming ASR → LLM (with
  tools + built-in web search) → streaming TTS. No screen interaction is
  required for any action listed here.
- **34 built-in tools** in 7 groups, plus a **dynamic MCP surface** (0..N
  user-configured server tools) — §2.
- **Capabilities without tools**: weather, maps/routes, music, alarms, smart
  home, memory, MCP — exercised through the tools but with provider/credential
  setup of their own — §3.
- **Runs GMS-free** (Huawei/HarmonyOS class, API 29+) — §4.
- **Everything user-facing is a setting** with a defined apply policy; the ones
  that need a restart are the usual source of confusion — §5.4.

---

## 2. Tool surface (the LLM's action space)

Authoritative name set and risk class: `tools/ToolRisks.kt` (`byName`, 34
entries). The registry cross-checks every registered tool's compiled risk
against that table and fails loudly on mismatch (`ToolContract.kt`). The three
memory tools live in `cognitive/tools/MemoryTools.kt`; the rest in
`tools/*.kt`; assembly in `tools/FunctionRouter.kt`.

Risk classes (`ToolRisk`), all enforced at the single choke point
`ToolRegistry.executeResult`:

| Risk | Meaning | Gate |
|---|---|---|
| `READ_ONLY` | No local mutation | Allowed whenever a turn is bound |
| `STATEFUL` | Mutates local app/device state | Needs a bound turn context (denied on an absent context) |
| `IRREVERSIBLE` | Destroys something | The turn's **own ASR text** must command a removal (`IrreversibleCommand`); fail-closed baseline |
| `CONTROLLED` | Voice-turn-only local control | Allowed only on a `TurnOrigin.VOICE` turn; a `ConfirmedTool` additionally needs the two-turn affirmative |
| `EXTERNAL` / `EXTERNAL_WRITE` | MCP server tools | Voice-turn only; writes need the exact-call spoken confirmation |

**Confirmation mechanisms** (they are NOT one mechanism):

| Mechanism | Applies to | How it works |
|---|---|---|
| `IrreversibleCommand` | `cancelAlarm`, `cancelTimer` | The user's ASR text must itself contain a removal verb (whole-token, no negation, no `?`) |
| `WriteConfirmation` (two-turn) | `enableLanManagement`, `homeConfirmControl`, MCP `EXTERNAL_WRITE` tools | Exact call proposed on the immediately-preceding turn + an explicit affirmative in the user's ASR text; matched by a SHA-256 of canonical args, **no token/nonce on the wire** |
| `ForgetConfirmation` (own gate) | `forget_fact` | Candidate list, then an explicit affirmative on the **immediately-next** turn. Deliberately **exempt** from `IrreversibleCommand` (`ToolAuthorization.permitsIrreversible` returns true for it) so the confirm turn is not double-blocked |
| Grant self-gate (no two-turn) | `homeControl` | T1 executes only if an entity grant matches exactly; otherwise returns `requires_confirmed_control` |

> **Note:** `CONTROLLED` alone does **not** imply a two-turn affirmative.
> `enableLocalManagement`, `disableManagement` and `homeControl` are
> `CONTROLLED` without it; only `ConfirmedTool` implementers opt in
> (`ToolRegistry.confirmationGate`).

### Group counts

| Group | Tools | Risk mix |
|---|---|---|
| A. Alarms & timers | 5 | 2 STATEFUL, 2 IRREVERSIBLE, 1 READ_ONLY |
| B. Weather & geo | 4 | 4 READ_ONLY |
| C. Device control | 8 | 7 STATEFUL, 1 READ_ONLY |
| D. Music | 6 | 3 STATEFUL, 3 READ_ONLY |
| E. Cognitive memory | 3 | 1 STATEFUL, 1 READ_ONLY, 1 IRREVERSIBLE |
| F. Management (R13) | 3 | 3 CONTROLLED |
| G. Smart home (R4) | 5 | 3 READ_ONLY, 2 CONTROLLED |
| **Total static** | **34** | |
| H. MCP (dynamic) | 0..N | EXTERNAL / EXTERNAL_WRITE |

### A. Alarms & timers

| Tool | Does | Example | Risk | Confirmation | Dep / limitations |
|---|---|---|---|---|---|
| `setAlarm` | Clock-time alarm with optional label/repeat | «поставь будильник на 7:30» | STATEFUL | none | On-device Room + AlarmManager; exact-alarm grant to arm; `repeat_daily` defaults true |
| `cancelAlarm` | Cancel alarm(s) by label substring, or all | «отмени будильник на подъём» | IRREVERSIBLE | `IrreversibleCommand` | On-device; label match is `contains`; matches enabled+disabled |
| `listAlarms` | List alarms **and** timers, with status | «какие будильники стоят?» | READ_ONLY | none | On-device (shared table — shows timers too) |
| `setTimer` | Countdown timer | «таймер на 5 минут» | STATEFUL | none | On-device; `minutes` 1..1440; survives reboot |
| `cancelTimer` | Cancel timer(s) by label substring, or all | «отмени таймер на чай» | IRREVERSIBLE | `IrreversibleCommand` | On-device; substring match |

### B. Weather & geo

| Tool | Does | Example | Risk | Dep / limitations |
|---|---|---|---|---|
| `getWeather` | Current + ~12 h hourly + 1–7 day daily forecast | «какая погода?», «а завтра?» | READ_ONLY | Project EOL (default, keyless) or Open-Meteo (keyless); failover only on NETWORK-class failure; Open-Meteo DPI-blocked from RU networks; units/derived fields handled per provider; `days` 1..7; configured city wins over GPS |
| `findPlace` | Find org/address/place; `near`/`near_user` bias | «найди аптеку рядом» | READ_ONLY | Yandex **MapKit** key; `near` is a ranking **bias, not a radius**; `near_user` degrades to configured city without a fix |
| `getRoute` | Route with duration, transfers, leg-by-leg transit detail | «как добраться до Шереметьево?» | READ_ONLY | MapKit key; **no arrival time** (MapKit leaves it empty); `transit`/`walking` only; origin required (fatal if unresolved) |
| `getCurrentLocation` | The device's actual position + best-effort label | «где я?» | READ_ONLY | Framework LocationManager (GPS then network); reverse-geocode label via MapKit; **fail-closed**; never invents a city |

### C. Device control

| Tool | Does | Example | Risk | Dep / limitations |
|---|---|---|---|---|
| `setVolume` | Set a stream volume 0–100 | «громкость на 50» | STATEFUL | On-device AudioManager; DND/policy `SecurityException` → Unavailable |
| `setBrightness` | Set screen brightness 0–100 | «яркость на 30» | STATEFUL | Needs `WRITE_SETTINGS`; degrades with a grant instruction; forces manual mode |
| `setWifi` | Wi-Fi on/off | «включи вайфай» | STATEFUL | Android 10+ often only opens the panel (`panel_opened`) |
| `setBluetooth` | Bluetooth on/off | «включи блютуз» | STATEFUL | API 33+ opens the settings screen instead |
| `setDnd` | Do-Not-Disturb on/off | «включи не беспокоить» | STATEFUL | API 35+ opens the settings screen; below that needs policy access |
| `lockScreen` | Screen off now | «выключи экран» | STATEFUL | Needs device-admin (`JarvisDeviceAdmin`, force-lock only); honest error otherwise |
| `openApp` | Launch app by spoken RU/transliterated name | «открой ютуб», «ВК Музыка» | STATEFUL | On-device + `AppAliases`; from background FGS may silently drop → reports `attempted` |
| `getDeviceInfo` | Battery + current time | «какой заряд?» | READ_ONLY | On-device; battery may be `-1` → "н/д" |

### D. Music

All six set `deduplicateFailedRetries = true` (the only tools that do): a
terminal failure on an identical call is returned from cache instead of
re-executed, so a cascade that exhausted every strategy cannot be retried into
`Слишком много шагов`.

| Tool | Does | Example | Risk | Dep / limitations |
|---|---|---|---|---|
| `playMusic` | Search installed player and play a track/slot request | «включи Bohemian Rhapsody» | STATEFUL | On-device player; 50 s budget, cold-start cascade ~42.5 s worst case; title→`query`, artist/album/playlist/genre→own slots; terminal `outcome=needs_user_action`/`failed` must not be retried |
| `controlPlayback` | Transport control | «пауза», «промотай на минуту» | STATEFUL | On-device active session; unknown action/mode is an honest error, never defaulted |
| `getNowPlaying` | Current track/state/queue | «что играет?» | READ_ONLY | Needs an active media session |
| `setMusicPlayer` | Set default player pref | «поставь по умолчанию Звук» | STATEFUL | On-device pref; unknown name fails without touching the pref; persists even if not installed (with a note) |
| `listPlaylists` | List playlists/sections (≤10) | «какие плейлисты есть» | READ_ONLY | On-device; returned `mediaId`s are short-lived — play immediately |
| `searchLibrary` | Search the library (returns, does not play) | «найди в музыке X» | READ_ONLY | On-device; same short-lived `mediaId` constraint |

### E. Cognitive memory

| Tool | Does | Example | Risk | Confirmation | Dep / limitations |
|---|---|---|---|---|---|
| `remember_fact` | Persist a long-term fact about the user | «запомни, что меня зовут Алексей» | STATEFUL | none | On-device Room; `subject` is closed vocabulary (`user`) — an unsanctioned subject is **rejected**, not coerced; people encoded as a relation with the name in `value` |
| `recall_facts` | Ranked recall with confidence | «что ты обо мне помнишь?» | READ_ONLY | none | On-device |
| `forget_fact` | Two-step forget (list, then delete) | «забудь, что …» → «да» | IRREVERSIBLE | `ForgetConfirmation` (own gate) | On-device; an unrelated/`нет`/question reply re-lists; no wire token |

### F. Management (voice-controlled config console)

| Tool | Does | Example | Risk | Confirmation | Dep / limitations |
|---|---|---|---|---|---|
| `enableLocalManagement` | Enable console on localhost only | «включи локальное управление» | CONTROLLED | none (adb-gated) | On-device loopback; not network-reachable |
| `enableLanManagement` | Enable console on the LAN over HTTPS | «включи управление по сети» | CONTROLLED | **`ConfirmedTool`** (two-turn) | On-device TLS listener; first call returns `needs_confirmation`; bind failure is a content-free error |
| `disableManagement` | Disable console + stop listener | «выключи управление» | CONTROLLED | none (fail-safe) | On-device; immediate |

### G. Smart home

| Tool | Does | Example | Risk | Confirmation | Dep / limitations |
|---|---|---|---|---|---|
| `homeListDevices` | List discovered devices | «какие умные устройства есть?» | READ_ONLY | none | HA or Tuya (Yandex = not implemented); capped at 60 with `truncated`; HA re-point needs service restart |
| `homeFindDevices` | Resolve spoken name/room → device(s) | «найди свет на кухне» | READ_ONLY | none | Deterministic `HomeResolver`; READ may surface candidates; the model never picks an id itself |
| `getHomeState` | Read normalized capability values | «какой свет на кухне?» | READ_ONLY | none | Never raw provider attributes; unknown kind/capability → UNKNOWN |
| `homeControl` | Fast-path control (T1 only, grant-gated) | «включи свет на кухне» | CONTROLLED | grant self-gate | T2/ungranted → `requires_confirmed_control` (not an error); Tuya locks have no cloud unlock DP; Tuya trial ≤10 devices; region must match account |
| `homeConfirmControl` | Confirmed execution (re-resolves + re-classifies) | after «да, включи» | CONTROLLED | **`ConfirmedTool`** (two-turn) | Tier always re-derived from the live device; grants are convenience, not the boundary |

### H. Dynamic MCP surface

| Tool | Does | Risk | Confirmation | Dep / limitations |
|---|---|---|---|---|
| `mcp_<hash>_<tool>` | Invoke a tool discovered on a user-configured MCP server | `EXTERNAL` (read) / `EXTERNAL_WRITE` (write) | reads: voice-turn only. writes: + `WriteConfirmation` two-turn exact-call affirmative | Server configured in Settings → «MCP-серверы»; namespaced so it cannot shadow a built-in; URL policy + connect-time DNS SSRF guard; not cross-checked against `ToolRisks` (by design) |

---

## 3. Capabilities & providers (non-tool)

| Capability | Provider(s) | Selected / configured | Credential slot | Cost | Caveats |
|---|---|---|---|---|---|
| Wake word | **Sherpa-ONNX** (default, offline) / Picovoice Porcupine | Settings → «Слушание» | Sherpa: none; Porcupine: `picovoiceKey` | Sherpa free; Porcupine free tier | Repo ships no `.ppn`; Porcupine needs a user-supplied key + model |
| ASR | Sber SaluteSpeech (default) / Yandex SpeechKit v3 | `speechBackend` (sealed at graph build) | Sber `saluteClientId`/`saluteClientSecret`; Yandex `yandexApiKey` | Paid cloud, own account | **Mic audio always egresses** on a turn |
| TTS | Sber SaluteSpeech (default) / Yandex SpeechKit v3 | same `speechBackend`; voice is LIVE | same as ASR | Paid cloud | Yandex must request 24 kHz headerless (code pins it); a rejected voice retries once with the backend default |
| LLM | **GigaChat native v2** (default) / [OI]-compatible / **Yandex AI Studio** | Settings → «Помощник» (sealed at graph build) | GigaChat `gigaChatClientId`/`Secret`; [OI] `openAiApiKey`; Yandex `yandexApiKey` | GigaChat/Yandex paid; [OI] depends | GigaChat & Yandex do **server-side** web search; [OI] does not |
| Cloud embeddings (semantic memory) | GigaChat Embeddings | Settings → «Память» (`memoryEmbedder`, `memoryCloudEnabled`) | GigaChat OAuth | Paid | Sends **fact values/queries**; hard-off when cloud memory is disabled |
| Weather | **Project EOL MCP** (default, keyless) / Open-Meteo (keyless) | Settings → «Погода и карты» (LIVE) | none | Free | Open-Meteo DPI-blocked from RU networks; optional proxy applies **only** to Open-Meteo |
| Geo / maps | Yandex MapKit `4.45.0-full` | Settings → «Погода и карты → Дополнительно» | `mapKitApiKey` (distinct from the Cloud key) | Free tier capped (1,000 unique users/day) | **Process-scoped init** — a changed key needs a full app restart |
| Smart home — Home Assistant | Home Assistant (self-hosted) | Settings → «Умный дом» | per-connection LLAT | Free | `https`/`wss` only; process-scoped client → host re-point needs a service restart |
| Smart home — Tuya | Tuya Cloud OpenAPI | Settings → «Умный дом» | Access ID/Secret + region/UID | Trial free | **Trial caps at 10 controllable devices**, no commercial use; region must match the account |
| Smart home — Yandex | — | — | — | — | **Not implemented** (no backend) |
| External tools | User MCP servers | Settings → «MCP-серверы» | per-server secret | user-run | Voice-turn only; SSRF-guarded; write tools need spoken confirmation |
| Remote config console | Jarvis's own embedded HTTPS server | Settings → «Управление» | management password + TLS P12 | n/a | Off by default; loopback or LAN only; **config only** — cannot run tools |
| Device location | Android framework `LocationManager` (no GMS) | permission + `weatherLocation` | none | Free | No `location` FGS type by design; configured city wins for implicit asks |
| Open-Meteo proxy | user-supplied HTTP/SOCKS proxy | Settings → «Погода и карты» | `openMeteoProxy` | user-provided | Never proxies LLM/TTS/gRPC |

### Network egress (what leaves the device, to whom)

| Destination | Data | Trigger |
|---|---|---|
| Sber OAuth (`ngw.devices.sberbank.ru`) | client id/secret, scope | token refresh |
| GigaChat native (`api.giga.chat`) | prompt, history, tools | LLM turn |
| GigaChat legacy host | fact values / queries | cloud embedding backfill |
| [OI] base URL (owner-set) | prompt, history, tools | LLM turn |
| Yandex AI Studio (`ai.api.cloud.yandex.net`) | prompt, history, tools | LLM turn |
| Sber ASR/TTS (`smartspeech.sber.ru`) | **mic audio** / synthesized text | a turn |
| Yandex STT/TTS (`*.api.cloud.yandex.net`) | **mic audio** / synthesized text | a turn |
| Open-Meteo / Project EOL | lat/lon or place name | weather tool |
| Yandex MapKit (native host) | search query / route endpoints | geo tools |
| User MCP servers | tool calls + args | voice turn only |
| Home Assistant (LAN) / Tuya Cloud | entity reads/commands | home tools / awareness |
| `music.yandex.ru` search URL | search query handed to another app | music tool |

Wake-word detection is fully on-device. Full egress model: `THREAT_MODEL.md`.

---

## 4. Hard device & platform requirements

- **minSdk 29** (HarmonyOS 2.0 / Android 10-class, e.g. Huawei AGS6-W09),
  compile/target **36**. Kotlin 2.2.21, JVM 17.
- **ABIs:** `arm64-v8a` + `x86_64` only.
- **GMS-free by construction:** MapKit excludes `play-services-location` and
  `play-integrity`; location uses the framework provider; no GMS is bundled.
- **GPS present** on the target tablet (framework provider; GPS then network).
- **MapKit adds ~36 MB** (arm64 `.so`) and requires the `-full` artifact.
- MapKit `4.45.0` is Java-21 bytecode → MapKit classes cannot load in JVM unit
  tests (device smoke only).
- **Git LFS required** after clone (Sherpa AAR + KWS assets ~53 MB total).

---

## 5. Settings reference

11 categories (`settings/SettingsCategory.kt`), 50 settings
(`settings/SettingsInventory.kt`). Apply policy is declared **only** in
`settings/ApplyPolicies.kt`; anything not listed there is **LIVE**.

### 5.1 Live vs restart — the short version

- **LIVE (applies on next use, no banner):** wake-word engine/model/keyword/
  sensitivity, voice stop, follow-up window, TTS voice/role/speed, weather
  provider/city/proxy, all memory/proactivity/music settings, all home settings
  except `homeProviders`, most account keys, MCP list.
- **SERVICE_RESTART (sealed at `AppGraph` construction):** LLM provider +
  models + folder + base URL + model, speech backend, AEC mode, management
  mode/port, `homeProviders`, the `openAiApiKey`.
- **APP_RESTART (once per process; a service restart is NOT enough):**
  `mapKitApiKey`.

The pending-restart banner is **instruction-only** — it never relaunches the
process. `PendingChanges` is in-memory (not persisted), so a process death
resolves it correctly.

### 5.2 Category → settings

| Category | Settings (key) |
|---|---|
| «Помощник» (BRAIN) | `providerType`, `gigaChatModel`, `yandexModel`, `yandexFolderId`, `openAiBaseUrl`, `openAiModel` |
| «Речь» (SPEECH) | `speechBackend`, `ttsVoice`, `yandexTtsVoice`, `yandexTtsRole`, `yandexTtsSpeed` |
| «Слушание» (LISTENING) | `voiceStopEnabled`, `followUpEnabled`, `followUpWindowMs`, `wakeWordEngine`, `wakeWordModel`, `customWakeWordPath`, `sherpaCustomKeyword`, `wakeSensitivity`, `aecMode` |
| «Погода и карты» (WEATHER_MAPS) | `weatherProvider`, `weatherLocation`, `mapKitApiKey`, `openMeteoProxy` |
| «Память» (MEMORY) | `memoryEnabled`, `memoryAutoExtract`, `memoryCloudEnabled`, `memorySensitiveVisible`, `memoryEmbedder` |
| «Инициатива» (PROACTIVITY) | `behaviorEnabled`, `behaviorQuietStart`, `behaviorQuietEnd`, `behaviorDailyQuota` |
| «Музыка» (MUSIC) | `preferredMusicPlayer` |
| «Аккаунты и ключи» (ACCOUNTS) | `picovoiceKey`, `saluteClientId`, `saluteClientSecret`, `gigaChatClientId`, `gigaChatClientSecret`, `yandexApiKey`, `openAiApiKey` |
| «MCP-серверы» (MCP) | `mcpServers` |
| «Управление» (MANAGEMENT) | `managementMode`, `managementPort`, `managementIdleTimeoutMs` |
| «Умный дом» (HOME) | `homeProviders`, `homeEntities`, `homeAliases`, `homeGrants`, `homeAwarenessEnabled` |

### 5.3 Behaviour that is compiled-in, not a setting

- **Barge-in is single-shot by default** (`JarvisConfig.bargeInSingleShot =
  true`): one wake word during a reply cancels it. There is **no UI toggle**;
  set the constant false to restore the repeat-within-1200 ms gesture.
- **Follow-up arming** uses the TTS-drain tail gate (decay to the room floor
  for 3 frames, 800 ms fallback), not a fixed lead-in.

### 5.4 Non-tool user-facing features

| Feature | What it does | Configured | Activation | Limitation |
|---|---|---|---|---|
| Assistant name follows keyword | Custom Sherpa keyword becomes the prompt identity, header, idle pill, hint | «Слушание» | LIVE | Porcupine keeps «Джарвис» (a `.ppn` phrase cannot be read back) |
| Custom Sherpa keyword | English word replacing the bundled one | «Слушание» | LIVE (BPE-validated, engine rebuild) | English only; digits/punctuation/Cyrillic rejected |
| Voice stop | «стоп» cancels THINKING/SPEAKING | «Слушание» | LIVE | Only THINKING/SPEAKING; ignored in IDLE |
| Follow-up window | Mic stays open 2–12 s after a reply | «Слушание» | LIVE | Arms only after the tail decays; speech over the tail is dropped |
| AEC | off / hardware DSP / software NLMS | «Слушание» | **SERVICE_RESTART** | Software playback capture needs a one-time MediaProjection consent per service start |
| Proactive suggestions | Mines habits and speaks a proposal; nothing auto-executes | «Инициатива» | LIVE | Off by default; only eligible tools mined; quota-capped |
| Long-term memory | Local facts + semantic recall; auto-extract opt-in | «Память» | LIVE | **`memoryEnabled=false` FREEZES memory** (no decay/archive/vector work); only «Забыть всё» deletes |
| Home awareness | Opt-in content-free home notices | «Умный дом» | LIVE | HA-only; empty curated set subscribes to nothing; own cap, cannot consume the habit quota |
| Config export/import | Always-encrypted backup via SAF | «Управление» | immediate | Argon2id + AES-256-GCM; no plaintext path |
| Clear chat | Deletes only the `messages` table | Home header | immediate | Memory/facts untouched |
| Primary control | Start / stop / resume | Home | immediate | `userStopped` outranks a running graph; explicit start clears it |

---

## 6. Known limitations (one honest list)

- **MapKit routing gives no arrival time** — the SDK left `TravelEstimation`
  empty on every live route; `findPlace` `near` is a ranking bias, not a radius.
- **Open-Meteo is DPI-blocked from Russian networks**; Project EOL is the
  default. Failover only on network-class failures.
- **`api.open-meteo.com` needs `timezone=auto`** or day boundaries shift.
- **Yandex key scope:** one key drives SpeechKit *and* AI Studio, but a
  SpeechKit-only key 401s on the LLM.
- **Sber TLS** chains to the Минцифры CA, absent from stock Android; anchored
  host-scoped for `sber.ru`/`sberbank.ru`/`giga.chat` only (sub-CA expires
  2027-03).
- **MapKit key change needs a full app-process restart** (once-per-process
  `setApiKey`).
- **Tuya:** region must match the account; free trial ≤10 controllable devices;
  residential locks have **no cloud unlock DP** (reported unsupported, never
  guessed).
- **Home Assistant:** `https`/`wss` only; host/token re-point needs a service
  restart.
- **`getRoute` is `transit`/`walking` only** (no driving).
- **Device control is best-effort on modern Android:** Wi-Fi/Bluetooth/DND may
  open a system panel instead of toggling; `openApp` from the background may be
  silently dropped.
- **Voice is Russian by default** (product decision); the system prompt stays
  Russian.
- **MCP tools are untrusted by design:** voice-turn only, namespaced, and write
  tools require the two-turn spoken confirmation with no wire nonce.

---

## 7. Adding a capability (maintainer checklist)

A new **tool**: add it to `tools/ToolRisks.kt` (`byName`) and register it in
`FunctionRouter`; `ToolRisksTest` will fail until the name set matches. Add a
row to §2.
A new **setting**: add it to `settings/SettingsInventory.kt` (or the explicit
`nonSettingsKeys` allow-list) and, if it is not LIVE, to
`settings/ApplyPolicies.kt`. Add it to §5.
A new **provider**: wire it in `AppGraph`'s exhaustive `when` (no `else`) and
document it in §3. A new **speech/LLM backend** is a compile error until wired
— keep it that way.
