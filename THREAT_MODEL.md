# Jarvis — Threat Model

Scope: the Jarvis Android app (`com.jarvis.assistant`) as it actually exists in
this repository. Every claim below carries a `file:line` anchor so it can be
re-verified; where something is unknown it is stated as unknown rather than
assumed safe.

This is a living document: when a control changes, its entry changes here in the
same commit. It is the reference for **what the app defends against, what it
explicitly does not, and which operations need whose authorization.**

---

## 1. System model

Jarvis is an always-on, voice-first assistant for a **single-user, GMS-free
Android tablet** that is expected to be powered and on Wi-Fi. The appliance
assumption is deliberate and shapes the model: there is no multi-user story, no
remote management plane, and no account system.

```
mic → wake word (on-device) → ASR (cloud) → LLM (cloud) → tool dispatch
                                              ↑                    ↓
                                    memory / behaviour      device capabilities
```

Data flow and layering are in `ARCHITECTURE.md`; this document is the security
view of the same system.

### Assets worth protecting

| Asset | Where it lives |
|---|---|
| Microphone audio (live) | streamed to the configured ASR backend; never written to disk |
| Conversation transcript | `messages` table — `data/AppDatabase.kt:44-59`, entity `data/MessageEntity.kt:6-16` |
| Personal facts / memory | `user_facts`, `fact_fts`, `fact_vectors`, `entities`, `fact_entities` (`cognitive/data/`) |
| Behaviour history | `command_events`, `habit_rules`, `behavior_log`, `session_summaries` |
| Alarms / labels | `scheduled_alerts`, `ring_sessions` |
| Credentials | `util/KeystoreVault.kt` (ciphertext in `jarvis_secure_v2` prefs) |
| Logs | `filesDir/logs/jarvis.log` (+2 rotations) |
| Location | configured city (prefs) or a live fix; never persisted as a city name |

### Trust boundaries

1. **Device ↔ app**: everything under the app's uid is trusted; the app assumes
   a non-rooted device.
2. **App ↔ cloud LLM/ASR/TTS**: the model output is **untrusted input**.
3. **App ↔ other apps**: `ACTION_VIEW`/deep links out, MediaBrowserService in.
4. **Untrusted content → model context**: web-search prose, tool results, stored
   memory, and the transcript itself are all attacker-influenceable in principle.

---

## 2. Local attacker

What an attacker with local access can obtain, by access level:

### Filesystem access as the app's uid (e.g. rooted device, `run-as` on a debug build)
- **Transcript, memory, alarms, behaviour history are readable.** The Room DB is
  not encrypted (`data/AppDatabase.kt:102`, `jarvis.db`); SQLCipher is not used.
- **Logs are readable**, and they are persisted at INFO and above
  (`util/FileLoggingTree.kt:33`, `minPriority = Log.INFO`), 3 × 512 KiB.
  `util/LogScrubber.kt:39-45` redacts content-keyed values
  (`text|utterance|transcript|reply|…`) and quoted spans ≥6 chars, but this is a
  backstop, not a guarantee — the real convention is that content-bearing
  material is DEBUG-only (`AGENTS.md`).
- **Credential *ciphertext* is readable** (`jarvis_secure_v2`, `MODE_PRIVATE`,
  `util/KeystoreVault.kt:140`). Decryption requires the
  `AndroidKeyStore`-held master key (`jarvis_vault_master_v2`, `:135`), which is
  not extractable from a non-compromised TEE/StrongBox. **On a rooted device
  with the attacker able to run code as the app's uid, the Keystore can be
  *invoked* by that code**, so the practical guarantee degrades to "the key
  cannot be exfiltrated off-device", not "the secrets cannot be used".

### `adb` access (USB debugging)
- `android:allowBackup="false"` (`AndroidManifest.xml:84`) — `adb backup` yields
  nothing, and there are no `fullBackupContent`/`dataExtractionRules`.
- A release build is not debuggable, so `run-as` does not apply.
- `adb` nevertheless grants shell-level read of app-private storage on a
  rooted/eng build only; on a stock device it grants no more than the app itself.

### Backup / cloud-restore access
- Disabled (`allowBackup="false"`), and no Android Auto Backup rules exist.
- Consequence: a restore to a new device does **not** carry the vault. A
  ciphertext that somehow survives a restore is undecryptable on the new device
  and is dropped by the narrowed self-heal (see §5).

### Physical access to an unlocked device
- The app is not gated behind a lock of its own; anything the owner can do, a
  person holding the unlocked tablet can do, including «Забыть всё»
  (`MemoryInspectorActivity.kt:129-152`, which does confirm via an `AlertDialog`)
  and exporting memory to JSON via the SAF picker (`MemoryInspectorActivity.kt:50-57`).
- `AlarmRingingActivity` is `showWhenLocked`/`turnScreenOn`
  (`AndroidManifest.xml:140-141`) so an alarm can surface over the lock screen —
  deliberate, and its actions are authorized by the durable ring token
  (`tools/RingCoordinator.kt`), not by the lock state.

---

## 3. Remote attacker

### 3.1 Network-adjacent
- All app traffic is TLS. `usesCleartextTraffic="false"`
  (`AndroidManifest.xml:87`) and no `networkSecurityConfig` override exists, so
  cleartext is refused process-wide.
- Sber-facing hosts get a composite trust manager whose Минцифры fallback is
  **host-scoped** to `sber.ru` / `sberbank.ru` / `giga.chat`
  (`util/SberTrust.kt:63`). Every other host — including a user-configured
  OpenAI-compatible base URL — validates strictly against the platform store
  (`util/SberTrust.kt:29-44`). Yandex gRPC uses the system CAs
  (`di/AppGraph.kt:109-130`).
- No `<provider>` component exists, so there is no exported content provider.

### 3.2 Compromised LLM / ASR endpoint
- A malicious endpoint sees everything sent to it: the system prompt, the
  conversation history, tool definitions and their results, and (for ASR)
  microphone audio. This is **inherent to using a cloud model** and is the
  reason the appliance is single-user and the provider is owner-chosen.
- It cannot directly execute anything: model output becomes text (spoken and
  persisted) or **tool calls**, and tool calls traverse the authorization
  boundary in §4.

### 3.3 Prompt injection — the principal remote threat
Untrusted text can reach the model context from: web-search results (returned as
prose inside the LLM response), **tool results** (persisted to history and
re-sent — `session/TurnRunner.kt:679-684,539`), **stored memory facts**
(rendered verbatim into `<memory-context>`, `cognitive/prompt/MemorySection.kt:32-99`),
conversation summaries, and the transcript itself.

**The audit's position, which this project accepts:** *system-prompt policy is
not a security boundary* (`session/SystemPrompt.kt:57` warns that context is
data, not instructions — that is defence in depth, not a control).

What is enforced **outside the model**:
- **A non-LLM authorization policy** at the single dispatch choke point:
  `toolRegistry.executeResult` → `ToolAuthorization.decide`
  (`tools/ToolContract.kt:158-175`, `tools/ToolAuthorization.kt:105-132`).
- Risk classification for all 24 tools in one table (`tools/ToolRisks.kt:23-53`),
  cross-checked at registry init and pinned by test.
- `IRREVERSIBLE` (cancel alarm/timer, forget) requires a **voice** turn whose **own
  ASR text** matched a removal command (`tools/IrreversibleCommand.kt:77-84`),
  bound after ASR finalization and before any dispatch, with a fail-closed
  `false` baseline (`session/TurnRunner.kt:176` → rebind `:229-234`).
- `forget_fact` is separately gated by turn provenance + an explicit affirmative
  + TTL (`cognitive/CognitiveCoordinator.kt:541-551`).
- The memory `subject` is a closed vocabulary on both ingresses
  (`cognitive/extract/ExtractionContract.kt`), so a stored fact cannot smuggle
  an arbitrary subject into the prompt.

**Residual risk (honest):** the gate is **turn-granular, not per-argument**. If
the user genuinely says «отмени будильник», an injection riding that same turn
can steer the model to an *irreversible* tool the user did not name. And
`remember_fact` is `STATEFUL` (allowed on any voice turn), so injection can
still **persist** attacker-authored content into memory, which then re-enters
the prompt on later turns. Neither is solved by the current design.

### 3.4 Malicious app on the device
- Jarvis declares no exported content provider and no exported service; the
  foreground service is `exported="false"` (`AndroidManifest.xml:152`).
- The only exported entry points are `MainActivity` (launcher),
  `BootReceiver` (`RECEIVE_BOOT_COMPLETED`) and `JarvisDeviceAdmin`
  (`BIND_DEVICE_ADMIN`).
- `openApp`/music deep links hand a query to another app via `ACTION_VIEW`
  (`media/SearchLinks.kt:38-41`, `media/AndroidMediaGateway.kt:111-130`); a
  malicious target app could therefore observe what Jarvis was asked to search
  for. No browser search URL is constructed from user content.
- `JarvisNotificationListener` is declared but **reads nothing** — the class body
  is empty and states no notification data is read or stored
  (`service/JarvisNotificationListener.kt:5-8`). Notification-borne injection is
  therefore *not* a live vector today; if `onNotificationPosted` is ever
  implemented, this section must be revisited immediately.

### 3.5 Malicious tool / result content
Tool results re-enter history as `role:"tool"` content and are treated as data.
They are the same class of risk as §3.3 and are covered by the same boundary.

---

## 4. Authorization model

Every tool declares one of three risks (`tools/ToolRisks.kt:23-53`), and
`ToolAuthorization.decide` (`tools/ToolAuthorization.kt:105-132`) maps risk +
turn provenance to Allow/Deny:

| Risk | Rule | Examples |
|---|---|---|
| `READ_ONLY` | Always allowed | `getWeather`, `findPlace`, `getRoute`, `recall_facts`, `listAlarms`, `getNowPlaying` |
| `STATEFUL` | Denied with no bound turn context; allowed on a voice turn | `setAlarm`, `setVolume`, `setWifi`, `lockScreen`, `openApp`, `playMusic`, `remember_fact` |
| `IRREVERSIBLE` | Requires a voice turn whose own ASR text commanded a removal | `cancelAlarm`, `cancelTimer`, `forget_fact`¹ |

¹ `forget_fact` is exempt from the generic rule and requires *both* the
removal-command provenance **and** its own two-step confirmation
(`cognitive/CognitiveCoordinator.kt:541-551`): the user's own affirmative in the
**immediately-next** finalized utterance, bounded by turn age and a 5-minute TTL,
check-and-act atomic under a mutex, with the delete loop preceding the grant
release.

Grouped the way the audit asked:

- **Implicitly authorized** — `READ_ONLY` tools, and `STATEFUL` actions on a
  voice turn (the user spoke to the assistant; that is the consent).
- **Explicit user confirmation (spoken)** — `forget_fact` (affirmative in the
  next turn); `IRREVERSIBLE` removal tools (the removal command must be the
  user's own words).
- **Physical interaction required** — `.ppn` import (SAF picker,
  `SettingsDetailActivity.kt:190-198`); playback capture (MediaProjection consent,
  `:200-210`); `RECORD_AUDIO`/location runtime prompts
  (`OnboardingActivity.kt:95-99`, `SettingsDetailActivity.kt:89-92`); device-admin
  enrolment (`OnboardingActivity.kt:157-167`); «Забыть всё» `AlertDialog`
  (`MemoryInspectorActivity.kt:129-152`).
- **Never allowed** — there is no "wipe device", no remote-config or remote-command
  channel, no arbitrary-shell capability, and no exported IPC surface that accepts
  commands. The device-admin policy grants **only** `force-lock`
  (`res/xml/device_admin.xml:3-5`) — no password policy, no wipe.

---

## 5. Privacy — where data can leave the device

The complete egress list (see §1 of the inventory this was built from):

| Destination | Sent | Trigger |
|---|---|---|
| Sber OAuth (`ngw.devices.sberbank.ru:9443`) | client id/secret, scope | token refresh |
| GigaChat native v2 (`api.giga.chat`) | system prompt, history, tools | LLM turn |
| GigaChat embeddings (derived from the legacy host) | fact values/queries | embedding backfill |
| OpenAI-compatible base URL (owner-configured) | same as GigaChat | LLM turn |
| Yandex AI Studio (`ai.api.cloud.yandex.net/v1/responses`) | prompt, history, tools, `web_search` | LLM turn |
| Yandex AI Studio `/v1/models` | API key only (GET) | lazy folder discovery, once |
| Sber ASR/TTS (`smartspeech.sber.ru`) | **microphone audio** / TTS text | a turn |
| Yandex STT/TTS (`stt|tts.api.cloud.yandex.net`) | **microphone audio** / TTS text | a turn |
| Open-Meteo forecast + geocoding | lat/lon (or place name), fields | weather tool |
| Yandex MapKit (host in the native SDK) | search query / route endpoints + key | geo tools |
| `music.yandex.ru` search URL | search query, handed to another app | music tool |
| `yandex.ru` legal/maps URLs | fixed constants, handed to browser | About row |
| Memory JSON export | **full fact/entity export** | user-initiated SAF write |

Notes:
- **Microphone audio always leaves the device** when a cloud speech backend is
  selected; wake-word detection itself is on-device.
- **Location** leaves only as coordinates/place-name to the two weather hosts and
  as a route endpoint to MapKit; no city name is ever persisted from a GPS fix.
- **Web-search citations are deliberately discarded** and never spoken
  (`llm/GigaChatSseParser.kt`, `llm/YandexSseParser.kt`).
- The **memory export** is the one path by which the whole memory can leave in
  a user-chosen file; it is user-initiated only.
- The MapKit SDK's real backend host is **not present in source** (native `.so`);
  only the API-key header is set in Kotlin. This is an accepted, documented gap.

---

## 6. Explicitly out of scope / accepted

- **Rooted-device and physical-access attackers.** The appliance is assumed
  physically controlled. Unencrypted Room DB is accepted for a pre-1.0 personal
  device (DATABASE encryption is not implemented).
- **A malicious/compromised model provider.** Choosing the provider is the
  owner's trust decision; the app bounds what a provider can *do* (§4) without
  claiming to bound what it can *know*.
- **Multi-user / enterprise deployment.** No accounts, no roles.
- **MapKit key binding and release-build keys** — see `RUNBOOK.md`.

## 7. Open items (tracked, not yet closed)

1. **Turn-granular authorization** — tighten `IRREVERSIBLE` to per-argument
   intent, or require an in-turn confirmation for the specific action.
2. **`remember_fact` as a persistence vector** — consider marking durable memory
   writes as requiring provenance stronger than "a voice turn is active".
3. **Log retention** — INFO-and-above persistence is scrubbed but is still the
   richest local artifact; consider a shorter retention or an opt-out.
4. **Notification listener** — currently inert; if implemented, add an injection
   assessment before shipping.
5. **Database encryption at rest** — not implemented.

---

## References

- `AGENTS.md` — the enforced invariants (including the authorization boundary and
  the subject contract, which this document depends on).
- `ARCHITECTURE.md` — data flow and layering.
- `RUNBOOK.md` — operational troubleshooting and known limitations.
- `README.md` — setup and the permission surface.
