# Jarvis — Capability Thesis & Product Strategy

**Status:** internal strategy document (not user-facing). Companion to
`AGENTS.md` (ramp-up), `ARCHITECTURE.md` (how it is built), `THREAT_MODEL.md`
(what it defends), `RUNBOOK.md` (operating it).

**Scope:** what Jarvis should become, which capabilities are worth adding, and
which are traps. Organised on three axes — **Awareness** (notices what matters,
§6), **Agency** (acts in the room, within pre-authorised consequence tiers, §7),
and **Availability** (reliability and honest degradation, §3.3). Proactivity is
the cross-cutting behavioural property, disciplined by §11 — not a fourth
category. **Passive awareness** remains the deepest single analysis (§6).

**Date:** 2026. Version reference: Jarvis `0.2.2` (pre-1.0).

---

## 0. TL;DR

1. **Jarvis's niche is the controllable, extensible, GMS-free voice appliance.**
   Siri/Gemini are becoming more capable and less controllable; Alexa+ is
   controllable but vendor-bound. Jarvis is what runs on the hardware those
   ecosystems abandon, with **user-controlled dependencies** — the user owns the
   keys, not a vendor account, and which third-party clouds get used is *their*
   call (see the honesty note in §1.1: this is control over dependencies, not
   ownership of them — the ASR/TTS/LLM are rented clouds).
2. **The product is organised on three axes: Awareness, Agency, Availability.**
   *Awareness* — notice what matters (passive awareness, §6); *Agency* — do the
   right thing in the room, within pre-authorised consequence tiers (smart-home
   control, §7); *Availability* — be reliably there and degrade honestly when a
   dependency is down (§3.3). **Proactivity is a behavioural property that cuts
   across Awareness and Agency — not a separate feature category** — and the
   `BehaviorArbiter` suppression firewall stays a first-class component (§11).
3. **The success metric is attention eliminated, not actions taken.** Jarvis is
   useful when the user has to think about it *less* — briefings replace manual
   checking, alerts pre-empt a question. Do not measure "AI actions fired."
4. **The capability filter.** For any proposed feature ask: *does this make
   Jarvis more useful as an always-present household agent, or merely more
   capable?* Capability breadth is not the goal; a "random enterprise MCP tool"
   is not product-defining.
5. **MCP is the extensibility bet, but not the autonomous-data path.** MCP tools
   are `EXTERNAL` and only run on a voice turn; scheduled/autonomous features
   must use first-party `READ_ONLY` data instead. Agency is always bounded by the
   risk tiers (§7.4), never by model discretion.
6. **Passive awareness is the awareness flagship** (§6): notifications + feeds +
   email, delivered as *pull → digest → interrupt*, triaged deterministically,
   gated by the existing arbiter, and summarised on a **tool-free** pass so
   untrusted content can never become an action.
7. **Smart-home control is the action flagship** (§7): a **unified
   multi-provider surface** over a normalized model — **Home Assistant**
   (LAN REST/WS), **Yandex Smart Home** (user IoT API), and **SmartLife/Tuya**
   (Cloud OpenAPI) are **equal first-class providers** behind one model. Jarvis
   is **not a hub** (no add/remove devices, rooms, config, or automations) and
   is **BYOC**: no app-owned vendor `client_id`/secret/project ships; the user
   supplies every credential via the app and it lives in the Keystore.
   **Tier writes by the normalized action** — pre-authorise reversible low-harm
   actions, always confirm locks/garage/alarm/oven/unknown. Do **not** attempt
   GMS-free Matter commissioning.
8. **Refuse**: accessibility screen-scraping, screen OCR, user-account scraping
   of private messengers, on-device ASR/TTS, speaker ID, sound-event detection,
   and anything platform-gated. These fight the platform or the hardware.
9. **First concrete step: the Routine/Briefing spine**, immediately followed by
   the passive-awareness read model. The cheap standalone win is the `LAN`
   server kind, which unblocks the LAN half of the smart-home work (a LAN Home
   Assistant); the Yandex/Tuya direct-cloud providers do not need it.

---

## 1. Product context (verified)

The strategy is constrained by *this* device, not a generic phone.

| Property | Fact | Consequence |
|---|---|---|
| Form factor | Huawei AGS6-W09 tablet, always-on, foreground service | It is an appliance, not a handheld |
| OS | Android 10 / API 29 (HarmonyOS 2.0-class), `targetSdk 36` | Newer Android guards apply only if it runs on a modern phone; design for both |
| Ecosystem | **No Google Play Services** | No FusedLocation, no GeofencingClient, no Play Integrity, no Cast, no packaged Google Sign-In |
| Connectivity | WiFi; has **GPS hardware** | Real fixes available; network provider also works (sub-second once warmed) |
| Motion | **Both** — carried around the home **and** used in a vehicle | Location-triggered behaviour is viable; "stationary home" assumptions are wrong |
| Compute | Kirin 710A-class, single far-field mic | No local ML beyond wake word; no speaker ID; no sound-event detection |
| Keys | User-supplied API keys, stored in the Android Keystore | No vendor account; provider-swappable; account-free |
| ASR/TTS | **Cloud** (Sber SaluteSpeech / Yandex SpeechKit v3) | ⚠️ Jarvis is **not** an offline assistant; see §2 |

### 1.1 The "local-first" correction (important)

A recurring mischaracterisation — including in earlier drafts of this document —
is that Jarvis runs speech locally. **It does not.** Both ASR clients
(`speech/asr/SberStreamingAsr`, `speech/asr/YandexStreamingAsr`) and both TTS
clients (`speech/tts/SaluteSpeechTts`, `speech/tts/YandexSpeechTts`) are cloud
streaming clients. What is genuinely local:

- the **wake word** (`audio/HybridWakeWordDetector`: Sherpa-ONNX or Porcupine),
- the **voice-stop** phrase,
- the **memory** store and all on-device state.

**Therefore the honest pitch is not "works without the cloud."** It is **"you own
the keys and the pipe"** — more precisely, **user-controlled dependencies**:
key-bound (not account-bound), account-free, provider-swappable, and
egress-transparent. Note this is *control over dependencies*, not ownership of
them: the ASR/TTS/LLM are rented clouds and can fail. Any roadmap item that
assumes local ASR/TTS (e.g. "build offline dictation") is misguided on this
hardware — but **graceful degradation is a first-class pillar** (§3.3), not an
afterthought.

### 1.2 Who this is for (the market question, answered)

The strategy is not "build an open-source assistant for everyone." It is
**Russian-first, single-primary-user, and honest about that**:

- **The first user is the owner.** Jarvis is tailored to one person's actual
  daily needs; upcoming smart-home work is the sharpest expression of that, not a
  generic feature. A product built for one demanding user with a real home and a
  real information diet is validated *in situ* — there is no need to invent a
  market to justify a capability.
- **Russian-centric is a deliberate setting-lock, not a limitation to fix.** The
  language, the LLM/speech/calendar/map providers (GigaChat, Yandex
  SpeechKit/MapKit/Calendar), and the Russian smart-home mix (§7.3) are chosen
  because the target user is Russian-speaking. Building and validating the
  capability domains against that setting is enough for now; other locales and
  geographies come *after* the domains and features are proven, not before.
- **The concept is a digital being / servant / wingman** — an AI that augments a
  person, helps with everyday tasks, stays aware across many media and sources,
  and keeps track of what matters to family and friends. This is a coherent
  product **if and only if it delivers on its promises and is genuinely helpful**
  (§0.3: attention eliminated; §3.3: actually there).

**Concrete deployment (validates the whole thesis):** the owner runs a
heterogeneous home — a **LAN Home Assistant** alongside native **Yandex Smart
Home** and **Smart Life/Tuya** accounts, spanning **50+ devices** across many
ecosystems at once — Yandex, Google Home, SmartThings, Smart Life/Tuya, Xiaomi,
Philips Hue, Electrolux, Polaris, and more (§7.0). That is the exact home Jarvis
is built to run: HA reaches most of the local mix, while the Yandex- and
Tuya-native devices are reached directly through their own user APIs (§7.2),
which is why the **unified multi-provider smart-home path (R4)** is the product's
action flagship and why the awareness lanes (`BehaviorArbiter`, digests) have a
real signal to act on.

**Consequence for the roadmap:** "will other people adopt this?" is explicitly
*not* the validation gate. The gate is "does it actually reduce this user's
attention burden and work reliably enough to trust?" Broadening to new locales is
a later productization step, and should not dilute the Russian-first capability
work in the meantime.

---

## 2. Competitive landscape (2026)

Researched from official sources; see `lib-2` provenance in the session log.

### 2.1 What the major assistants do

- **Amazon Alexa+** — the standout. Proactive ("start your commute early",
  deal alerts, daily briefings), voice-authored/weather-based/per-person routines
  with voice-recognition triggers, Echo Show ambient widgets, explicit memory of
  facts/documents/emails — **and the only mainstream assistant that is a
  documented MCP client** (spec `2025-11-25` + MCP Apps).
- **Apple Siri AI** (Sept 2026) — reactive/conversational; personal context,
  onscreen awareness, synced history. **Proactivity is the documented gap** —
  surfaces like Notify Me, but no unprompted spoken briefing. Extensibility is
  **App Intents schemas** + Foundation Models hosting third-party LLMs; on-device
  AFM + Private Cloud Compute. Announce Notifications is AirPods/CarPlay-only and
  locked-screen only.
- **Google Gemini** (replaced Assistant on Android Sept 2026; Nest not migrated)
  — "Gemini Intelligence": command-gated multi-step app automation, screen/image
  context, generative widgets. Routine parity contested; migration criticised for
  reliability/latency regressions.
- **Samsung Bixby** — delegates to Gemini + Perplexity; Notification Highlights +
  Call Screening; SmartThings for home.
- **Microsoft Copilot** — productivity assistant; not an always-on appliance
  benchmark.

### 2.2 Table-stakes vs differentiators vs broken

**Table-stakes:** cloud-LLM conversational quality; a wake word; persistent
memory; a third-party action surface; Matter/Thread smart-home control;
permission-gated notification access.

**Differentiators:** Alexa+ proactivity + MCP; Apple on-device privacy + App
Intents; Google screen/app automation + generative UI; Samsung multi-agent
delegation.

**Commonly broken across all:** *true* proactivity (unprompted spoken help is
rare and usually reduced to notifications/widgets); hallucination/reliability;
regional and hardware gating.

### 2.3 What is platform-gated (cannot be contested)

- System-wide hotword and the **default-assistant role** (OS-reserved).
- Deep OS / screen / cross-app automation.
- GMS-dependent Google features; Apple's Announce Notifications.
- Privileged notification access (`ACCESS_NOTIFICATIONS` is privileged in AOSP;
  normal apps use the user-granted listener).
- Hardware moats: far-field mic arrays and smart displays.

---

## 3. Differentiation thesis

> **Siri/Gemini are becoming more capable and less controllable; Alexa+ is
> controllable but vendor-bound. Jarvis is the controllable, extensible,
> GMS-free alternative — and proactivity + passive awareness are where it can
> lead rather than follow.**
>
> **The design philosophy in one line:** *the best assistant is the one you think
> about least.* Judge every capability by **attention eliminated** — how much
> manual checking, remembering, or fiddling it removes — and by whether it makes
> Jarvis **more useful as an always-present household agent, not merely more
> capable** (§0).

Three structural advantages the commercial assistants cannot copy:

1. **Key-bound, not account-bound.** Secrets live in `util/KeystoreVault`; the
   user picks the LLM (`ProviderSettings.Type`: GigaChat / Yandex / [OI]),
   the speech backend, and the weather provider. No vendor can de-platform it.
2. **Runs where Play Services are absent.** MapKit is GMS-stripped by dependency
   exclusion and verified on-device; the GMS-free Huawei/HarmonyOS hardware is
   precisely what the big ecosystems deprecate. The constraint is the market.
3. **An MCP host.** Siri/Gemini are schema-locked (the *other* developer must
   adopt their schema). Alexa+ is the only big MCP client. Jarvis ships a full
   MCP client (`mcp/`) with a trust model ahead of most hosts.

### 3.1 Jobs Jarvis wins

- Hands-free control of a home/car appliance with no account and no vendor:
  reboot-surviving alarms/timers, weather (two keyless providers with failover),
  music transport across arbitrary players, device control, geo/routing.
- **A disciplined proactivity engine** — mined habits with probation, a
  seven-gate suppression matrix, quota, quiet hours, and mute/retire that
  statistics cannot override (`cognitive/behavior/`).
- **User-configured capability expansion** via MCP — no app update, no vendor
  approval.
- **Regional/linguistic fit** — Russian-first with `values-en` parity, Yandex
  Calendar/MapKit/SpeechKit/GigaChat as first-class choices.

### 3.2 Jobs Jarvis should not contest

World-knowledge QA (delegate to a user-key LLM); screen/image context; broad
smart-home mesh (be a unified multi-provider client, don't become a hub);
default-assistant / hotword / privileged-notification roles; multi-user identity
(no mic array).

### 3.3 Reliability and graceful degradation (a first-class pillar)

Jarvis is a 24/7 appliance whose ASR, TTS, and LLM are **rented clouds** (§1.1),
so *being there* is a product property, not a given. This is the axis the 2026
review under-weighted, and it is load-bearing for an always-on box:

- **Local path first where it exists.** Wake word, voice-stop, memory, alarms,
  timers, and device control are on-device and must keep working when the
  network is down. A router or provider outage must not brick the appliance.
- **Degrade honestly, never silently.** When a dependency is unreachable, say so
  (a short spoken status) rather than failing into silence; fail over where the
  architecture already supports it (two weather providers; swappable LLM/speech
  backends on restart).
- **Recover without a restart.** The FGS watchdog already revives a wedged audio
  producer; provider outages should surface as a transient error and retry, not
  demand a reboot.
- **Name the failed dependency.** Because dependencies are user-selected, the
  degradation message should say *which* one failed so the user can swap a key
  or provider.

**How to measure it:** wake-word → reply latency on a healthy network, and
"wake word + local commands still respond" on an unhealthy one. Both belong in
the acceptance criteria for every phase (§12 gate).

---

## 4. Current capability inventory

Grounding for "what to add" — what already exists.

**Voice** — custom wake word (Sherpa/Porcupine), local wake only, cloud ASR/TTS,
barge-in (single-shot), voice stop, follow-up window, orb tap-to-stop, dynamic
assistant name.

**LLM** — three selectable backends with server-executed web search; time-aware
system prompt; transient-failure retry.

**Memory** — extracted facts, semantic recall, decay, opt-in proactive
suggestions, behaviour/habit engine, suppressible, freeze-not-delete.

**Tools (26 surface: 23 base + 3 cognitive)** — alarms/timers (exact-alarm,
ring sessions, reboot-surviving), weather (two providers, hourly, feels-like,
proxy), geo (MapKit findPlace/getRoute/getCurrentLocation), device control
(volume/brightness/wifi/bt/dnd/lock/openApp/getDeviceInfo), music (external
players, rich transport), memory tools.

**MCP (shipped this cycle)** — user-configured remote + local servers; tools
join the LLM surface dynamically (`ToolRegistry.dynamicTools`); untrusted by
construction (`ToolRisk.EXTERNAL`, VOICE-turn only); WRITE tools gated by an
explicit spoken confirmation on the next turn (`WriteConfirmation` /
`WriteBinding`, no token through the model); SSRF-guarded twice; per-server auth
in the Keystore.

**Proactivity infrastructure** — `HabitDetector` (mined clusters, probation),
`BehaviorArbiter` (ordered suppression matrix), `speakProactively` (guarded
mini-session with barge-in), `AlarmScheduler` (exact alarms surviving reboot and
grant revoke).

**Device surface gaps** — several declared permissions are unused
(`WAKE_LOCK`, `VIBRATE`, `USE_FULL_SCREEN_INTENT`, `ACCESS_WIFI_STATE`,
`ACCESS_NOTIFICATION_POLICY`); `NotificationListenerService` is a 9-line **stub**
(media-ducking token only); no torch, no ringer mode, no screen control, no local
quick answers, no notes/lists, no email/calendar/RSS.

---

## 5. Roadmap — feasible and worth adding

Ranked by **(user value) × (leverage of existing code) × (feasibility without GMS
/ on a Kirin 710A / single mic)**. Size: S ≤ days, M ≤ 2 weeks, L ≥ 3 weeks.

| # | Capability | Rank | Size | Why |
|---|---|---|---|---|
| R1 | **Routine & Briefing spine** | **P0** | L | Turns "responds" into "acts at the right time" — the Siri gap |
| R2 | **Calendar reader (CalDAV-native)** | **P0** | M | Feeds briefings + commute; must be first-party `READ_ONLY` (§8) |
| R3 | **MCP packs + `LAN` server kind** | **P0** | M | Prerequisite for reaching a LAN Home Assistant and local servers; today `McpUrlPolicy` rejects `192.168.x.x` |
| R4 | **Unified smart-home subsystem** (§7) | **P0** | L | The action flagship — a first-party `home/` subsystem over a **provider registry** (HA REST/WS, Yandex IoT, Tuya OpenAPI) with a normalized risk model, not MCP; see §7.2.1 |
| R5 | **Passive awareness** (§6) | **P0/P1** | L | The awareness flagship; see §6 |
| R6 | **Email awareness + confirmation-gated reply** | P1 | L | Clean IMAP/SMTP + app passwords; on-thesis |
| R7 | **Commute / "leave now"** | P1 | M | Alexa+'s headline; MapKit has no traffic-aware arrival (accuracy cap) |
| R8 | **Local device miscellany** (§5.1) | P1 | S–M | Cheap, local, high daily value |
| R9 | **OTA / self-update** | P2 | M | Required before external distribution |
| R10 | Presence/geofence routines | P2 | L | Fights the platform (no GMS geofencing, no `location` FGS type) |
| R11 | Ambient idle dashboard | P3 | M | Screen-off device; worsens the accepted MapKit attribution risk |
| R12 | Speaker ID / sound events | **Reject** | L | Need a mic array |
| R13 | **Optional external management** — web UI + REST, disabled by default (§14) | P2 | L | Config pain grows with R4; 3 modes (off / localhost / LAN w/ idle-close), HTTPS + password, config-only |

### 5.1 Local device miscellany (cheap wins)

The current device surface is 8 tools; several permissions are already declared
but unused. These are mostly local, deterministic, and instant — the difference
between "AI gadget" and "appliance":

- **Torch** on/off (`CameraManager.setTorchMode`).
- **Ringer mode** (silent/vibrate/sound) — `ACCESS_NOTIFICATION_POLICY` already declared.
- **Screen control** (off / stay-awake) — `WAKE_LOCK`, `USE_FULL_SCREEN_INTENT` declared.
- **Battery / storage / network health** — concise appliance facts.
- **Local quick answers** (time, date, math, unit conversion, countdown) — no cloud.
- **Notes / shopping list** (local Room) — a beloved appliance feature.
- **"Отзовись"** (sound/vibrate) — find the tablet.

**Governing principle:** a wingman living in the appliance should be *fast and
local*. Today every command routes through a cloud turn; an obvious command
(«фонарик», «сколько заряда», «тише») deserves a local fast-path, not a model
round-trip. This is a design decision (deterministic local intent vs. model), not
merely a tool addition.

---

## 6. Passive awareness — comprehensive analysis (the flagship)

> The appliance's superpower is that it is **always there and can notice things
> for you**. Passive awareness is the capability that turns "assistant you talk
> to" into "assistant that has your back."

### 6.1 What it is

Noticing events from the user's information streams and surfacing the ones that
matter — briefly, at the right moment, without being a nag. Three feeds of
increasing depth:

1. **Notifications** — universal, on-device, shallow.
2. **Feeds / news / social** — public or opt-in content, medium depth.
3. **Email** — the highest-value personal stream, clean via IMAP/SMTP.

### 6.2 The delivery model — order matters

The commercial failure mode is per-event push (early Assistant/Gemini nagging).
Ship in this order:

| Mode | What | Why |
|---|---|---|
| **Pull** («что нового?») | User asks; read an active-notification / inbox snapshot; answer | Cheapest, zero-annoyance, proves the pipeline |
| **Digest** | Silently accumulate; speak one summary at a natural moment («пока тебя не было: 3 сообщения, доставка, письмо от банка») | The wingman model — concise, batched |
| **Interrupt** | Announce a genuinely time-critical event immediately (incoming call, doorbell, alarm) | Highest value, highest risk — narrow allowlist only |

### 6.3 Source feasibility

#### 6.3.1 Notifications (foundation)

- The `NotificationListenerService` is currently a **9-line stub** used only as a
  media-ducking token (`service/JarvisNotificationListener.kt`). Building it out
  is the foundation.
- Readable: package, id, tag, post time, extras (`EXTRA_TITLE`, `EXTRA_TEXT`,
  `EXTRA_BIG_TEXT`, `EXTRA_SUB_TEXT`, `EXTRA_TEXT_LINES`, `EXTRA_INFO_TEXT`),
  `category`, `priority`, `actions`.
- **Not** readable: full article bodies, message history, anything not posted as
  a notification.
- Group summaries carry `FLAG_GROUP_SUMMARY`; children via
  `getActiveNotifications()` — enabling dedup/grouping.
- **Android 15+** redacts OTP content from untrusted listeners — irrelevant on
  the API-29 target, but design for the modern-phone case (targetSdk 36).

#### 6.3.2 Feeds / news / social

- **RSS/Atom (+ RSSHub)** — the cleanest universal path. Open, auth-less, no app
  review, covers most Russian sites. Community MCP readers exist but are
  **stdio** (see §6.3.5), so a small **native** HTTP+XML client is the right
  shape. **Recommended first** for articles/news.
- **Feedly** — an official remote MCP server exists, **but it is
  threat-intelligence-scoped and requires an Advanced plan**; it is *not* a
  generic "read my boards" surface. Free consumer OAuth is unconfirmed. Treat as
  a niche paid integration, not the news foundation.
- **VK** — `wall.get` / community content is clean via a **community/service
  token**. **Personal messages are closed**: the `messages` scope is not granted
  to new applications, and the Implicit-Flow path was disabled June 2024.
- **Telegram** — the **Bot API** is clean (channels/groups the bot is in, bot
  DMs, Telegram Business opt-in). **Personal DMs via TDLib/MTProto are
  ToS-grey** → refuse.
- **MAX (МАХ)** — a real Bot API (`platform-api2.max.ru`), but gated to **RF
  legal entities / ИП / self-employed**, moderated, and **no user-account API**
  and no MCP. Integrable only as a moderated bot.
- **Generic share intent** (`ACTION_SEND` / `ACTION_PROCESS_TEXT`) — the
  **no-integration baseline**: the user shares an article/message to Jarvis. No
  permissions, no ToS gate, covers every app.

#### 6.3.3 Email

**The two direct answers, from verified sources:**

- **"Read my inbox via the Mail.ru app" — not possible.** No mainstream mail app
  exposes message bodies through a ContentProvider. The only documented mail
  provider is Gmail's (`GmailContract`), and it exposes **label name + unread
  count only** — no messages — and requires the Gmail app (absent here).
- **"Reply via the Mail.ru app" — only a user-tapped hand-off.** `ACTION_SENDTO`
  with `mailto:` (or `ACTION_SEND`) prefills To/Subject/Body into the client's
  compose screen; **the user taps Send.** There is no programmatic send through
  the app, and firing the notification's reply `RemoteInput` unattended is
  fragile and dangerous — refuse.

**The real architecture — native IMAP (read) + SMTP (send):**

| Provider | IMAP / SMTP | App password? | Notes |
|---|---|---|---|
| **Mail.ru / VK Mail** | `imap.mail.ru:993` / `smtp.mail.ru:465` | **Required**, protocol-scopable | Best fit; ordinary password rejected |
| **Yandex Mail** | `imap.yandex.ru:993` / `smtp.yandex.ru:465` | **Yes**, per-app (activates 2–3 h) | Best fit; verify hosts (help page restructure) |
| **Gmail** | `imap.gmail.com:993` / `smtp.gmail.com:587` | De-emphasised; needs 2SV | Prefer XOAUTH2 / Gmail REST (Custom-Tab PKCE, GMS-free) |
| **Outlook / M365** | `outlook.office365.com:993` / `smtp-mail.outlook.com:587` | **No** (basic auth disabled) | **OAuth required** (Graph or XOAUTH2) |
| **Rambler** | `imap.rambler.ru:993` / `smtp.rambler.ru:465` | No app-password concept | Account password; weak posture; mid-migration |
| **Nextcloud / Mailcow** | your host | n/a (own creds) | Simplest for self-hosted |

**Least-privilege insight:** Mail.ru's app password is **scope-able** — mint a
**send-only** credential for the composer and a separate **read-only** one for
awareness. This maps directly onto the `ToolRisk` / `WriteConfirmation` model.

**Transport note:** read via **WorkManager periodic fetch**, *not* a persistent
IMAP IDLE socket — a long-lived background socket fights Doze/App Standby and the
always-on foreground-service constraints.

#### 6.3.4 On-device mechanisms — the honest verdict

| Mechanism | Verdict |
|---|---|
| Notification stream | ✅ Foundation; shallow (title/text/actions only) |
| Share/intent handoff | ✅ Clean no-integration baseline |
| ContentProvider read | ❌ No mail/messenger app exports content |
| AccessibilityService screen-read | ❌ Refuse — privileged, fragile, Play-policy-hostile, tapjacking surface |
| MediaProjection + OCR | ❌ Refuse — captures secrets on screen; the AEC grant is single-use and cannot be repurposed |
| User-account scraping | ❌ Refuse — ToS violation + credential hazard |

#### 6.3.5 The MCP transport reality

An on-device MCP client can reach **remote HTTP(S)** servers but **not stdio**
servers (a mobile app cannot spawn `npx`/`uvx` subprocesses). The community
Telegram/VK/RSS MCP servers are predominantly **stdio**, so they are **not
directly usable** from Jarvis. Conclusion: for these sources, build **small native
clients**, not MCP config. MCP remains the right layer for *remote* servers
(e.g. a user-hosted gateway, smart-home hub, or a future remote Feedly-grade
service).

### 6.4 Triage — what makes an event "notable"

Deterministic, user-configured rules — **not** a black-box classifier:

- **Source allowlist** (per-app, user-set) — never "all apps."
- **Category** — `CATEGORY_CALL`, `CATEGORY_ALARM`, `CATEGORY_MESSAGE`,
  `CATEGORY_EVENT` are reliable OS signals.
- **Priority** — `IMPORTANCE_HIGH`, `isOngoing`, group-summary vs child.
- **Dedup/grouping** — collapse N messages from one source into one line.
- **Context gates** — the existing `ArbiterContext` fields (`quietHoursActive`,
  `dndActive`, `mediaActive`, `sessionIdle`, `quotaLeft`, `cooldownOk`,
  `notRecentlyDelivered`, `batteryOk`, `recentInteraction`).

An LLM "summarise every notification" pass is the wrong path — egress, latency,
cost, and hallucinated nags. Deterministic triage; at most a text-only
rephrase.

### 6.5 Security (the sharpest constraint)

Passive awareness gives Jarvis the **lethal trifecta** (Willison, 2025):
**(1)** private data + **(2)** untrusted content + **(3)** ability to
communicate/act. Every feed, mail body, or notification title is
attacker-writable. OWASP **LLM01:2025 Prompt Injection** is the #1 LLM risk, and
**indirect** injection is exactly this.

**Non-negotiable rules:**

1. **Content is data, never instructions.** Denote it as untrusted; it must never
   reach the system-prompt authority layer.
2. **Summarise on a tool-free pass.** Reading/summarising must not expose action
   or send tools to that pass. A malicious mail body or feed item must never be
   able to trigger a tool.
3. **Never auto-act on content.** Replying/sending is confirmation-gated
   (`IRREVERSIBLE`-class, bound to the user's own utterance) — reuse
   `WriteConfirmation` / `AffirmativeUtterance`.
4. **Never relay external content on a proactive/scheduled turn** — that would
   be an injection-to-speaker carrier. Autonomous speech is composed from
   first-party `READ_ONLY` data only.
5. **Content-bearing material is DEBUG-only and never persisted**
   (`SpeechContentLoggingTest` rule); notification/mail text is not stored.
6. **Redact OTP/2FA/banking** by package + keyword, independently of Android 15.
7. **Least privilege** — send-only credential for the composer; read-only for
   awareness; never the primary account password.

### 6.6 Proposed architecture (build-ready shape)

- A pure **`NotificationEventSource`** mapping `StatusBarNotification` → a small
  domain `NotableEvent(package, category, priority, title, text, time,
  hasReplyAction)`. JVM-testable.
- A pure **`NotableEventPolicy`**: `announceNow | digest | ignore`, fed by the
  existing `ArbiterContext` + the user allowlist. JVM-testable.
- A **`NotificationEventSink`** building out `onNotificationPosted` /
  `onListenerConnected`, handing notable events to `speakProactively`.
- A pure **`MailSummaryReader`** over an IMAP client; summarise on a tool-free
  pass; never auto-act.
- A small **RSS/Atom reader** (native HTTP+XML) for news/articles.
- A **share-target Activity** (`ACTION_SEND` / `ACTION_PROCESS_TEXT`) as the
  universal no-integration entry.
- A **`getNotices`** / **"что нового?"** `READ_ONLY` tool for pull.
- **Settings**: per-app allowlist, category toggles, "announce immediately vs
  digest," redaction list, per-account mail credentials.

### 6.7 Risks and unknowns

- **EMUI notification-access quirks** — needs on-device validation.
- **"Notable" tuning** — start narrow; expect a first pass of real usage to tune.
- **Permission unease** — notification access is a scary grant; Settings UX must
  state exactly what is and is not read.
- **Yandex IMAP host page** 404'd during research — verify endpoints before
  shipping.
- **Feedly / MAX / VK** findings are partly *negative* search results — one
  confirmation pass before committing.

---

## 7. Smart-home control — comprehensive analysis (the action flagship)

> A device that lives in the home, stays on, and already has a voice loop should
> control that home. This is the capability people buy an Echo for, and it is the
> natural counterpart to passive awareness: it *notices* the house, and it *acts
> on* the house.

### 7.0 The concrete target — one normalized model over a heterogeneous home

The design is anchored to the owner's actual deployment, not an abstraction:

- **A large home (50+ devices) drawn from many ecosystems at once** — Yandex
  Smart Home, Google Home, SmartThings, Smart Life/Tuya, Xiaomi Home, Philips
  Hue, and appliance brands such as Electrolux and Polaris, among others.
- **Three ecosystems are reachable directly by Jarvis, as equal first-class
  providers.** A **LAN Home Assistant** is one of them (and already aggregates
  much of the mix via its own integrations); the **Yandex Smart Home user IoT
  API** reaches Yandex-native homes; the **SmartLife/Tuya Cloud OpenAPI**
  reaches Tuya devices. Jarvis speaks **one normalized model** across all three
  behind a single typed tool surface — it does **not** assume HA is the only
  bridge, and it does **not** become a hub.
- **The user supplies every credential (BYOC).** HA is already key-based (a
  pasted long-lived access token); Yandex and Tuya are **user-owned** OAuth
  clients / cloud projects. The app ships **no** vendor `client_id`, secret, or
  project, and can reach nothing the user has not personally configured
  (§7.3).
- **Scale shapes the design.** 50+ entities across providers means entity
  resolution, room/kind grouping, and disambiguation («свет на кухне» → which of
  several lights) are first-class — not a nice-to-have. Because no two
  ecosystems share device ids, identity is explicitly namespaced
  (`"ha:light.kitchen"`, `"yandex:<id>"`, `"tuya:<id>"`), which makes a
  **single typed, enumerable control surface** far more valuable than per-device
  special-casing.

**Strategic consequence:** a **first-party `home/` subsystem over a provider
registry** is the right investment — one typed client per ecosystem, normalized
risk, one resolver — rather than forcing control through the intent-shaped MCP
surface (§7.2) or assuming any single hub. Providers **coexist**: the user may
run HA + Yandex + Tuya at once, each enabled by its own config entry (the MCP
config-list idiom, not a radio).

**Second consequence — first-party state reads unlock autonomous awareness
(§6/§8).** An MCP connection is `EXTERNAL` by policy and therefore
**voice-turn-only**: it can never feed proactive speech. The `home/` providers
are not forced into that class — their state reads are first-party `READ_ONLY`
data, which is exactly what an autonomous digest needs. So the same native
integration that gives typed control (**Agency**) also gives a home-state feed
for proactive notices like «стиральная машина закончила» (**Awareness**),
without weakening the MCP boundary. v1 awareness is **HA-only** because only HA
offers true WebSocket `subscribe_events` push; Yandex and Tuya are poll-only,
with undocumented / quota-metered limits (§7.2.1). The boundary still holds where
it must: *control* stays a `CONTROLLED` action, voice-initiated, never
autonomously triggered; only reads become awareness-eligible.

### 7.1 Why it is feasible (and where the difficulty really is)

Jarvis has **no Zigbee/Z-Wave/Thread radio and no GMS**. That rules out being a
hub, but **not** control. The problem splits cleanly:

| Sub-problem | Difficulty | Notes |
|---|---|---|
| **Connectivity** | **Easy** | Talk to a provider over the network (LAN hub or cloud API), or hit WiFi devices directly. No radio needed. |
| **Semantics** | **Hard** | Entity resolution across **50+ mixed-ecosystem devices** (§7.0) is a first-class problem, not a convenience — «свет на кухне» must bind to a concrete `(provider, nativeId)` handle deterministically. §7.2.1. |
| **Risk tiering** | **Hard** | The real design problem — §7.4. |

### 7.2 Recommended architecture — a provider registry of typed clients

Jarvis talks **directly** to each supported ecosystem through a typed client,
unified by one normalized `home/` model (§7.2.1). **Home Assistant is one of
three providers, not the universal bridge** — and it is **not routed through the
MCP lane** (see below).

1. **`HomeProviderRegistry`.** It holds the user's **enabled `HomeProviderConfig`s**
   (the MCP config-list idiom) and builds **one backend per config** via an
   exhaustive `when(HomeProviderId)` with **no `else`** — a fourth provider is a
   compile error until wired. Providers **coexist**: the user may run HA +
   Yandex + Tuya simultaneously. There is no "select one" radio.
2. **Home Assistant — first-party, native REST/WS, not MCP.** HA ships a built-in
   MCP server (`mcp_server`, HA 2025.2, `/api/mcp`, Streamable HTTP stateless,
   LLAT/OAuth), but its surface is the **Assist/LLM intent API** —
   `homeassistant__GetLiveContext` and `intent__HassTurnOn` / `HassTurnOff` /
   `HassSetPosition` with **natural-language slots** (`name`/`domain`/`area`),
   **not** typed `domain.service` + `entity_id` + `service_data`. One
   `intent__HassTurnOn` turns on a lamp *and* locks a lock, and Jarvis never sees
   the resolved entity — so MCP is **untierable by construction** and **cannot
   feed awareness** (`EXTERNAL` → voice-turn-only). HA is therefore a **dedicated
   backend** (§7.2.1) that owns registry + resolution + tiering over HA's
   **native REST/WS** API (`POST /api/services/<domain>/<service>`, WS registry
   commands, `subscribe_events`), with **one** auth (a HA long-lived access token
   in the Keystore). *(Rejected alternatives: delegating to
   `conversation/process` is untierable and executes with no dry-run — rejected
   for control, optional read-only fallback only; a custom HA component is
   version-coupled and moves Jarvis logic into HA — rejected for v1.)*
3. **Yandex Smart Home — first-party typed client over the user IoT API**
   (`api.iot.yandex.net`, §7.3.1). Cloud-routed (there is no Yandex LAN API),
   OAuth `iot:view`/`iot:control`, and **BYOC** — the user supplies their **own**
   OAuth client; no app-owned client ships. It is the one **sanctioned inbound**
   path into a user's Yandex devices/groups/scenarios, and Yandex's own
   Zigbee/Matter devices are **not** importable into HA — so this is the only way
   to reach them. **Gated on G0.**
4. **SmartLife / Tuya — first-party typed client over the Cloud OpenAPI**
   (HMAC-signed, **BYOC** — the user's own cloud project, §7.3.2). It is the
   second direct-cloud provider, **gated on G1 and G3**.
5. **Do not own Matter/Thread.** Matter **commissioning on Android is GMS-gated**
   (HA documents that the GMS-free app flavour cannot add Matter devices; its
   source hard-disables the Play-Services commissioning client). Control-only
   without GMS is possible only by embedding the `connectedhomeip` CHIP SDK — a
   research project, not a feature. Let **HA (or the vendor app) be the Matter
   controller**: commission once with any phone/Apple device, then drive it
   through the provider that owns it. Thread devices need a border router, which
   Jarvis does not have.
6. **Do not build cloud bridges for Google Home / Alexa / Apple Home.** All three
   are cloud-to-cloud (HomeKit is Apple-platforms-only). They are out as
   GMS-free local control.

**Boundary: the MCP lane stays, for non-home external servers.** None of the
three home providers traverses `mcp/`; the MCP lane is not the HA integration and
not the home integration at all. It remains the transport for user-hosted and
third-party external servers (§9).

Also: **IR** (ACs/TVs) needs an actual emitter — probe
`ConsumerIrManager.hasIrEmitter()` at runtime (many tablets lack one; otherwise
use a LAN IR bridge). **BLE** works GMS-free via `BluetoothGatt` for devices with
a known profile. **RF** requires an external bridge. Direct-LAN control of
speaker-less devices (Shelly, Tasmota/ESPHome, Hue) is **not v1**: where those
matter they are reached through a provider that already integrates them (almost
always HA).

### 7.2.1 The `home/` subsystem — bounded design

A first-party package (`home/`) modeled on `geo/` (a narrow capability) plus a
`cognitive/`-style coordinator. **Android-free pure core; transport at the
edge.** It is deliberately **not** the sealed single-selectable-provider pattern:
the registry holds several **coexisting** providers, so it is the MCP
config-list idiom plus a `Selecting*`-style exhaustive `when(HomeProviderId)`,
not a Settings radio.

- **Provider identity & model.** `HomeProviderId { HOME_ASSISTANT("ha"),
  YANDEX("yandex"), TUYA("tuya") }`; all three are **equal first-class** in the
  model. A `HomeBackend` SPI exposes `discover()`, `readState(keys)` and
  `apply(action)`, returning typed `HomeResult`/`HomeError` (**never
  exceptions**). Because Yandex returns **per-capability `action_result.status
  DONE|ERROR`** and Tuya can partially fail, `HomeResult` carries a **`PARTIAL`**
  outcome — a top-level "ok" must **never** be reported as blanket success.
- **Identity is `(provider, nativeId)`.** No two ecosystems share device ids, so
  the wire form is namespaced (`"ha:light.kitchen"`, `"yandex:<id>"`,
  `"tuya:<id>"`). `discover()` returns these handles; control **accepts only
  handles present in the live catalog** — a model-invented id is rejected before
  any transport call.
- **Provider-neutral vocabulary.** `DeviceKind`, `Capability`, `ActionVerb`,
  `HomeAction`, `HomeState`. Each backend has a **pure, total capability-mapper**
  — HA `domain`/`device_class`; Yandex `devices.capabilities.*` /
  `properties.float`; Tuya per-device **DP codes read from `/specification`**
  (never hardcoded, G1); anything unmapped → `UNKNOWN`. The mapper is the **only
  constructor** of `DeviceKind`/`Capability`, which is what makes "unknown → T2"
  structural rather than a runtime hope.
- **Transport split, at the edge.** Each backend owns its own transport —
  HA **REST** (`POST /api/services/<domain>/<service>`, `GET /api/states`,
  `/api/services`) for the state snapshot and **all writes** plus **WebSocket**
  (`/api/websocket`) for the **registries** (entity/area/device — WS-only) and
  `subscribe_events`; Yandex/Tuya signed HTTPS. Bounded backoff + reconnect;
  reads serve last cache and degrade honestly, writes return typed
  `Unreachable`/`AuthError`. One HA LLAT in the Keystore (prefer a **limited HA
  user**, not a full-account token).
- **The resolver is the hard part and must be deterministic — the model never
  picks the entity id.** A pure `HomeEntityResolver` binds Russian speech →
  concrete handles, **fail-closed**, in a fixed order: **explicit alias → exact
  normalized provider name → room+kind composition → token substring →
  `NotFound`**. On a **control** call, ambiguity/`NotFound` fails closed; **read**
  tools may return candidates so the assistant can ask a one-line disambiguation
  question. Aliases are **seeded** from provider names and **learned only** from
  explicit user edits/disambiguation — never from implicit success. It owns a
  **tiny normalizer** and does **not** depend on `tools/AppAliases`. Config is
  stored as prefs JSON blobs mirroring `McpServerConfigCodec`
  (`Empty`/`Invalid`/`Ok`, **never throw**): `homeProviders`, `homeEntities`,
  `homeAliases`, `homeGrants`, `homeAwarenessEnabled` (**no Room bump**).
- **Authorization seam (the crux, §7.4).** Reads are issued as **`READ_ONLY`**
  (first-party data that cannot mutate, and required so autonomous/scheduled
  awareness reads work — `CONTROLLED` would deny a non-VOICE turn). **Writes are
  `CONTROLLED`** (voice-turn-only). There are **two tools**, and **zero change to
  the crown-jewel confirmation seam**:
  - **`homeControl`** (`CONTROLLED`, no confirmation marker): executes **only
    T1** when a matching **entity-level grant** exists; otherwise it returns a
    normal `{outcome:"requires_confirmed_control", target, action}` result
    (never a silent success, never an error).
  - **`homeConfirmControl`** (`CONTROLLED` **and** `ConfirmedTool`,
    `confirmationDomain="home"`, `confirmationAction="control"`): the registry's
    existing `confirmationGate` forces the next-turn ASR affirmative; on
    confirmation it **re-resolves and re-classifies** the target, then executes.
    Because the tier is **re-derived from the *resolved real device***, the
    model's tool choice is never the boundary — a prompt-injected fast-path lock
    call is refused and routed to confirmation. A recommended,
    **non-authorizing** `ConfirmationLabel` addition makes the challenge name the
    true target (it improves the wording only; the tier and decision are always
    re-derived).
- **Grants are entity-level, never area-wide**, keyed
  `(provider, nativeId, capability, verb)`, **default none (deny-by-default)**,
  persisted as the `homeGrants` JSON blob, `LIVE`, revocable. A T1 action with
  no matching grant falls back to `homeConfirmControl` (T2-style). The tier is
  **re-derived** at execution, so an unknown kind/capability/verb is T2.
- **Bound it hard — do NOT build a parallel HA or become a hub.** In scope:
  discover/list, read state, and a typed control action (including a
  scene/script **invocation**, always T2). **Out of scope:** adding/removing
  devices, room/config management, automations, dashboards/Lovelace,
  history/logbook, energy, backups, add-ons/Supervisor, provider config,
  template rendering. The capability filter (§0) applies with force here.
- **Awareness v1 is HA-only.** HA is the only provider with true WS
  `subscribe_events` push; Yandex and Tuya are **poll-only** (undocumented limits
  / quota-metered). Opt-in curated `homeEntities`, **default off**; the
  projection is a content-free `HomeNotice` (no free-text attributes, names, or
  raw payloads) carrying `(provider, nativeId, kind, oldState, newState, atMs)`.
  Interesting transitions only (appliance done, door opened), debounced and
  capped, routed through `BehaviorArbiter` → `ProactivePresenter` →
  `SessionManager.speakProactively` (§6 pull→digest→interrupt), and logged to the
  existing `behavior_log` table (**no Room bump**). Reads stay `READ_ONLY`;
  control stays `CONTROLLED`, voice-initiated, never autonomous.
- **Phasing.** **H1** provider registry + HA REST/WS + LLAT + registries +
  curated `homeEntities` + **read tools** + the two control tools
  (T2-confirm-only) + `SettingsCategory.HOME` (proves transport, auth, confirm
  path; keeps prompt size bounded at 50+ devices). **H2** full resolver/aliases.
  **H3** T1 grants. **H4** awareness. **H5** **Yandex** (gated on G0). **H6**
  **Tuya** (gated on G1/G3).
- **Settings.** New `SettingsCategory.HOME` + detail screen + controller (factory
  `when` is compile-forced) + **host-owned list/edit Activities** for
  providers/aliases/grants/awareness. Secrets go through an **argument-keyed**
  `homeSecret(providerId, field)` accessor (`SecretVault.homeSecretKey`),
  **excluded from `SettingsInventory` reflection** like `mcpSecret`. Provider
  set/URLs/credentials → `SERVICE_RESTART`; aliases/grants/entities/awareness →
  `LIVE`. The reserved export `home` section is populated. The LAN URL policy
  (§9) governs a LAN HA URL.

### 7.3 The Russian ecosystem (verified 2026)

Russian-first means the vendor clouds matter. **Three inbound paths are real and
first-class** — **Home Assistant** (local, §7.2), **Yandex Smart Home**
(§7.3.1) and **SmartLife / Tuya** (§7.3.2) — and the rest are outbound-only
exposure targets. Home Assistant remains the aggregation point for much of the
local mix, but it is **not** the only provider. All three are **BYOC**: the user
supplies the credentials; the app ships none.

- **Yandex Smart Home — two APIs, both directions.** (a) As a **provider**,
  Yandex calls *your* HTTPS endpoint (skill moderation; a *private* skill skips
  it) — the inverse direction, and not how a local app controls devices. (b)
  **The user-facing IoT API** (`api.iot.yandex.net`, OAuth scopes
  `iot:view`/`iot:control`) lets a third-party OAuth app **read and command a
  user's Yandex home** (rooms, groups, devices, scenarios). This is the **one
  sanctioned inbound control path** into Yandex homes, and the reason to add
  Yandex as a first-class provider (§7.2). **BYOC:** the app ships **no**
  app-owned Yandex OAuth client — the user registers their own app and pastes
  their `client_id` (and any `client_secret` their app requires), or a pasted
  long-lived token; see §7.3.1.
- **Sber Smart Home — open, but B2B-gated.** Sber has a public platform with
  **Cloud-to-Cloud** (Sber → vendor webhook) and **MQTT-to-Cloud** (an
  integrator's Sber controller → Sber) paths, but admission requires a **legal
  entity/ИП**, certification, and (for MQTT) hardware purchase. It has **no
  consumer/user API** comparable to Yandex's, so it is **effectively closed to an
  individual self-hosted app** — not "no public API," but not user-keyable
  either.
- **VK / Маруся — outbound-only.** It has a smart-home surface (Маруся app, VK
  Капсула) but exposes only a provider-direction skill/app platform; no confirmed
  inbound API. Treat it as a **target to expose HA to**, not a control source.
- **Matter in Russia — Wi-Fi only, no Thread.** Yandex supports **Matter over
  Wi-Fi** on its Stations/Hub and sells certified Matter devices, but explicitly
  **does not support Thread** ("К … хабу Яндекса … не получится подключить
  устройства … на протоколе Thread"). Sber/VK: no Matter found. Matter-over-Wi-Fi
  is therefore a viable *partial* vendor-neutral path; **Thread is not** — do not
  market it.
- **The outbound bridge: `dext0r/yandex_smart_home` (Yaha Cloud).** The mature
  community integration exposes HA entities to **both Alice and Маруся** (direct
  or cloud mode; HA ≥ 2025.12). It is *outbound* (assistants control HA); there is
  **no inbound path** to import Yandex/Sber/VK clouds into HA. **No HA integration
  and no MCP server exist for the user API** (`api.iot.yandex.net`) — any Jarvis
  integration is greenfield.
- **SmartLife / Tuya — a first-class, feasibility-gated, BYOC provider
  (§7.3.2).** The de-facto Russian home for many (grey-imported, large installed
  base). Jarvis reaches it **directly** through the **Tuya Cloud OpenAPI**
  (HMAC-signed, **BYOC** — the user supplies their **own** cloud project
  `client_id`/`secret`, region and uid; the app ships none). It is **gated on
  G1** (project + QR + `/specification` + quota) and **G3** (whether HA's own
  Tuya integration already suffices — if it does, direct Tuya ships only where
  HA cannot reach a device).
- **Xiaomi Mi Home — HA-only.** Official (`xiaomi_miio`) plus **unofficial
  local** paths (MIoT/UDP token extraction) that carry **ToS risk**; reach
  Xiaomi through HA, **not** as its own Jarvis provider.
- **Wiren Board** — open Debian/MQTT controllers; consumable via MQTT (a real
  local-control candidate). Rubetek, iRidi, Larnitech, and the telecom hubs
  (Ростелеком/МТС/Beeline) are unverified/vendor-locked — do not build on them.

#### 7.3.1 Yandex user API — integration shape and the blocking gate

Verified 2026 from the Yandex platform docs. This determines whether the one
sanctioned Russian-cloud path is usable at all.

- **Verdict: a native first-party tool, not an MCP server.** The API is a bespoke
  OAuth2/REST surface with per-capability result semantics and a fixed-redirect
  auth dance; wrapping it as generic MCP adds a hop and loses typed result
  handling. It sits naturally beside the geo/weather lanes: a `HomeBackend`
  client + the shared `homeControl` tools (§7.2.1), token in `KeystoreVault`.
- **BYOC.** The app ships **no** Yandex OAuth client. The user registers their
  own API-access app at `oauth.yandex.ru` and supplies its `client_id` (plus a
  `client_secret` only if their app type requires one), or pastes a long-lived
  token; all stored via `SecretVault`. Nothing is reachable until they do.
- **Auth (OAuth 2.0 code flow).** App at `oauth.yandex.ru`; authorize →
  `?code` (TTL 10 min) → `POST /token`. **PKCE is supported** (`S256`), so the
  code exchange needs no `client_secret` — consistent with "no secrets in the
  APK". ⚠️ *Refresh* may still require `client_id`+`client_secret`; confirm before
  relying on a true public client end-to-end.
- **Headless onboarding is the UX sharp edge.** The API-access app type's
  redirect is **fixed to `https://oauth.yandex.ru/verification_code`** and cannot
  be changed, so a tablet with no browser flow must **show the URL/QR and accept
  a pasted code**, or use the manual debug token (explicitly provided for
  "checking your app works").
- **⚠️ Gate G0 (blocking): individual eligibility is UNKNOWN.** Yandex returns
  `unauthorized_client` when an app is rejected/pending **moderation**. The docs
  require no legal entity, but verification is emphasized, and **nothing states
  whether an individual/self-hosted app can hold `iot:view`/`iot:control`**. This
  is not answerable from the docs — it must be **tested by registering an app and
  requesting the scopes** (debug-token path) *before any code is written*. If the
  answer is no, this path is closed and we default to HA + LAN.
- **Model & semantics.** Capabilities `on_off` / `range` / `mode` / `toggle` /
  `color_setting` / `video_stream`; properties `float` / `event`. Actions POST to
  `/v1.0/devices/actions` (and `/groups/{id}/actions`,
  `/scenarios/{id}/actions`). The result is **synchronous but per-capability**:
  `action_result.status` = `DONE`|`ERROR` (partial success is representable), so
  the parser must iterate every capability and never report a blanket success
  from a top-level `status:"ok"`. `DONE` reflects **cloud acceptance**, not
  physical actuation — reconcile with a follow-up `GET`.
- **Risk tiering (§7.4).** T0 read = `user/info`, `devices/{id}`, `groups/{id}`;
  T1 = `on_off`/`range`/`color_setting`/`mode` on lights/sockets/TV/vacuum/etc.;
  T2 = `devices.types.cooking.*` (kettle/multicooker/grill), `iron`,
  `thermostat` extremes, **every scenario invocation** (a benign-named "good
  night" can lock doors — confirm the *invocation*, never the name), and
  `DELETE /devices/{id}`. **No lock/garage/alarm device class was found** in the
  user-API docs — if T2 lines ever depend on them, verify they exist, or they
  are simply unreachable via this API (which is itself a safety plus).
- **Failure modes to test:** scope rejection; per-capability partial failure;
  mid-session 403 after the user revokes access (degrade honestly); refresh
  without a browser; cloud-state ≠ physical-state; **rate limits entirely
  undocumented** (probe a burst before relying on the API).

#### 7.3.2 Tuya / SmartLife user Cloud API — integration shape and the G1/G3 gates

- **Verdict: a first-party typed `HomeBackend`, not an MCP server.** A bespoke
  HMAC-signed REST surface with per-DP result semantics, mirroring the Yandex
  lane.
- **BYOC.** The app ships **no** Tuya cloud project. The user creates their own
  project in the Tuya IoT Platform and supplies `client_id` / `client_secret`,
  the region/endpoint, and their `uid` (bound to the SmartLife app account), all
  via `SecretVault` (§7.2.1). Nothing is reachable until they do.
- **Signing is unavoidable and client-side.** Every call carries an HMAC-SHA256
  signature over a canonical string (`client_id`, `t` timestamp ms, `nonce`,
  `stringToSign`, with `sign = HMAC-SHA256(client_secret, stringToSign)` and
  `sign_method`), plus an access token where the endpoint requires one. Document
  the exact canonical recipe — it is easy to get subtly wrong and the failure is
  an opaque error code.
- **Discovery & mapping.** `GET /v1.0/users/{uid}/devices` lists devices; per
  device, `GET /v1.0/devices/{device_id}/specification` yields the **DP code
  map**, which is the **only** legitimate source of `Capability`/`ActionVerb`
  for that device — DP codes are per-device and must **never** be hardcoded.
- **⚠️ Gate G1 (blocking): project + QR + spec + quota.** Confirm an individual
  (not an RF legal entity) can create a project, that the SmartLife account can
  be QR-bound, that `/specification` returns usable DP codes, and what the
  API/quota terms actually are — by calling the API with a real project
  *before any code is written*.
- **⚠️ Gate G3 (blocking, a product decision).** Test whether HA's Tuya
  integration already reaches enough of the deployment. **Decision: direct Tuya
  is gated behind G3** — ship HA first, and add direct Tuya only where HA cannot
  reach a device.

**Verdict for Russia:** the pragmatic stack is a **provider registry of three
direct typed clients — Home Assistant (REST/WS) + the Yandex user API + the Tuya
OpenAPI — over one normalized model**, with HA reaching the Xiaomi/Zigbee/Wi-Fi
mix it already integrates. Sber and VK vendor clouds are **outbound-only** for
Jarvis. All three providers are **BYOC**.

### 7.4 The risk-tiering problem (the crux)

The current write model is a **binary**: `EXTERNAL` reads run on a voice turn;
`EXTERNAL_WRITE` requires an explicit spoken affirmative on the **next turn**.
That is right for a Jira ticket and **absurd for «включи свет»** — nobody will
say «да» across two turns to turn on a lamp. But relaxing it uniformly is
dangerous, because the same tool surface includes **locks, garage doors, ovens,
and alarms**, where an injected or hallucinated call is **physical**.

**Tier by the normalized action, derived and enforced at the one choke point —
never by trusting the model to pick a tier.** The classifier's input is the
provider-agnostic `HomeAction(key, kind, capability, verb, level)`, *after* each
backend's pure mapper has already translated its native taxonomy into
`DeviceKind`/`Capability`/`ActionVerb`. **There is deliberately no per-provider
tier table** — provider taxonomy is mapped once, up front:

- **Tier 0 — Read.** Always allowed (states, sensors, discovery).
- **Tier 1 — Reversible, low-harm.** The remaining mapped writes on
  light/socket/switch/fan/media/vacuum, climate **within a safe band**, and
  `cover` that is a blind/curtain/shade. **Pre-authorisable** by an explicit
  Settings grant keyed `(provider, nativeId, capability, verb)` —
  **entity-level, never area-wide** — revocable, **default none**. Executes
  immediately. Worst case from injection: a light turns on — annoying, not
  dangerous.
- **Tier 2 — Safety-critical / hard-to-reverse.** Locks, alarm panels,
  garage/door/gate `cover`s **and any `cover` with an unknown kind**,
  oven/kettle/water-heater/cooking appliances, `UNLOCK`, `OPEN`/`CLOSE`,
  `TARGET_TEMPERATURE` outside the safe band or non-numeric, **any unknown
  `DeviceKind`/`Capability`/`ActionVerb`**, and **every scene/script
  invocation** — a benign-named "good night" can lock doors, so confirm the
  *invocation*, never the name. **Always confirm, never pre-authorise.** The
  assistant must **speak the concrete action and target** before asking; the
  affirmative stays next-turn and ASR-derived.
- **Precedence: T0 → T2 → T1; anything unknown → T2** (fail closed). The mapper
  is the **only** constructor of `DeviceKind`/`Capability`, so an unmapped native
  value can never tier as T1.
- **Tier 3 (optional) — LAN-only.** Mark local servers so their tools can never
  be reached on a remote path (MCP lane only; the first-party home providers are
  network-typed by construction).

**Fail closed on ambiguity:** if a kind/capability cannot be determined, treat it
as Tier 2. Default new servers to `READ` (already the case).

**Tier 1 needs a *typed* signal — which generic MCP does not provide.** Tiering
requires knowing the concrete device class/action (`light.turn_on` on
`light.kitchen` vs `lock.unlock` on `lock.front_door`). A generic MCP tool is an
opaque name with a JSON schema, and server `annotations` are explicitly
untrusted — so **deriving a tier from a tool name would be model/discovery
discretion, which the philosophy forbids**. Concretely: HA's MCP `intent__HassTurnOn`
turns on a lamp *and* locks a lock in the same tool, so it cannot be tiered at
all. Consequences:
- **Over generic MCP (and MCP-hosted HA control), v1 is Tier 0 reads + Tier 2
  confirmation for every write.** This is a strict, safe subset — but it means
  «включи свет» is a two-turn «да», which is not the end state.
- **Tier 1 is only achievable over a first-party surface with typed
  entity/action metadata** — hence the `home/` providers' normalized
  `HomeAction`. Tier 1 pre-authorisation is therefore **coupled to a typed
  integration, not to any transport.**

### 7.5 Security

- **Prompt injection is the adversary.** With pre-authorisation, poisoned web /
  feed / MCP content could otherwise turn on lights, open blinds, or set a
  thermostat. Mitigate: pre-authorise **only Tier 1**, scope grants to **concrete
  entities** (not "all lights"), **never** pre-authorise lock/garage/alarm/oven.
- **Keep confirmation model-blind.** The existing design has deliberately **no
  token/nonce the model can echo** and binds the confirmation to the user's own
  ASR text. Do not weaken this for Tier 2 — a model-authored `"confirmed": true`
  must remain worthless.
- **Bind to what was spoken.** For Tier 2, the challenge must name the target and
  action, and the confirmation is accepted only for the *immediately* following
  utterance; this prevents a stale «да» from a different question authorising an
  unlock.
- **Harden the spoken target across providers.** A T2 challenge must name the
  **provider** as well as the resolved target (e.g. «Яндекс, кухня, выключить
  свет»), because two ecosystems can carry same-sounding names. The recommended
  `ConfirmationLabel` addition is **non-authorizing** — it only improves the
  challenge text; the tier and the decision are always re-derived from the
  resolved `HomeAction`.
- **BYOC means the user owns the blast radius.** Each provider's credentials are
  the user's own; a token revoked in the vendor console must degrade honestly
  (403/401 → `AuthError`/`Unavailable`, never a crash and never a silent
  success).

### 7.6 Risks and unknowns

- **G0 — Yandex provider (blocking).** Whether an individual/self-hosted app can
  hold `iot:view`/`iot:control`, the `iot:*` token TTL, the refresh contract
  (does it need a `client_secret`?), and the entirely **undocumented rate
  limits** (§7.3.1). If G0 fails, the Yandex provider is closed and we default to
  HA.
- **G1 — Tuya provider (blocking).** Individual project creation, SmartLife QR
  binding, usable `/specification` DP codes, and the real API/quota terms
  (§7.3.2).
- **G2 — HA LAN (blocking).** LAN HTTPS, the WS registries, and
  `subscribe_events` push actually working on the target appliance/network
  (§7.2.1).
- **G3 — HA-vs-direct-Tuya (blocking, product).** Whether HA's Tuya integration
  already reaches enough of the deployment; direct Tuya is gated behind this
  (§7.3.2).
- **EMUI / LAN quirks**, mDNS resolution (`homeassistant.local` often will not
  resolve via Android's system resolver — prefer a literal IP and `NsdManager`).
- **Token scope / BYOC** — a HA long-lived token is full-account unless a limited
  user is created; the Yandex and Tuya credentials are the user's own and can
  401/403 mid-session (degrade honestly).
- **Tuya/Xiaomi local paths are unofficial** and ToS-fragile — product risk;
  neither is a v1 Jarvis surface.
- **Per-capability partial failure** — Yandex `DONE|ERROR` per capability and
  Tuya partial failures must surface as `PARTIAL`, never a blanket success.
- **IR emitter presence** on the target tablet is unverified.

---

## 8. The architectural boundary that shapes everything

**MCP tools are `EXTERNAL` and only run on a VOICE turn.**
`ToolAuthorization.decide` allows `EXTERNAL` (and `EXTERNAL_WRITE`) **only** when
the bound turn's origin is `TurnOrigin.VOICE`; `PROACTIVE`/`SCHEDULED`/null are
denied. This is correct and must not be weakened casually.

**Consequences:**

- A **scheduled briefing or commute alert cannot use an MCP calendar.** It must
  use a first-party `READ_ONLY` tool (hence R2 is *native* CalDAV, not MCP).
- **Autonomous speech is composed from first-party data** (weather, alarms,
  native calendar) with deterministic templates (`ProactivePresenter` already
  makes no LLM call), optionally rephrased text-only with **no tools**.
- If you ever want external data on an autonomous turn, it requires a
  **deliberate, scoped "read grant"** — a routine binds the *specific*
  `(server, tool)` READ the user selected, revocable in Settings, results never
  entering an LLM that can call tools. Make this decision deliberately or not at
  all.

---

## 9. MCP as the strategic bet

**Verdict: yes — as the *interactive extension* strategy, not the
autonomous-data strategy.**

**Why right:** no lock-in; user keys; no vendor approval; capability grows by
configuration, not release; GMS-free by construction; can run local (loopback)
for privacy-sensitive servers. Jarvis can match Alexa+ on extensibility while
owning the data path.

**Where it must not be used:** autonomous/scheduled data (policy-denied). The
**LAN case** is still the strongest reason for the kind — a first-party `home/`
subsystem and other local servers need to reach `192.168.x.x` — but it is
*currently blocked*: `McpUrlPolicy` rejects private hosts for REMOTE and allows
only loopback for LOCAL, so a Home Assistant at `192.168.x.x` cannot be reached.
**Add the `LAN` kind first**; it is the prerequisite for the whole smart-home
group.

**The `LAN` kind is a relaxation, so make it a *scoped* one** — not a blanket
"allow private IPs":

- **Allow RFC-1918 only** (`10/8`, `172.16/12`, `192.168/16`) **and IPv6 ULA**
  (`fc00::/7`) — nothing else.
- **Still reject — explicitly** — link-local (`169.254/16`, `fe80::/10`, which
  is cloud-metadata territory), the metadata address `169.254.169.254`,
  `0.0.0.0` / `::` (currently classified `PRIVATE`; LAN must not inherit them),
  and any public literal. Loopback stays covered by the existing LOCAL rule.
- **Hostname vs public-literal needs care.** The pure policy cannot tell a
  hostname (`homeassistant.local`) from a public IP literal — both classify as
  `PUBLIC`. LAN must allow the *hostname* (deferring to the connect-time guard)
  while rejecting a *public IP literal*; add an `isIpLiteral(host)` helper (an
  address-class enum split would perturb REMOTE's truth table for no gain).
- **Enforce twice:** at the literal-URL layer (`McpUrlPolicy`) **and** at connect
  time (resolve, then require **every** address to be private — the inversion of
  the REMOTE all-public rule, which is what defeats DNS rebinding /
  `10.0.0.5.nip.io`). Classify from the `InetAddress` **bytes**, not the
  `hostAddress` string (mapped IPv6 `::ffff:10.0.0.5` breaks string parsing).
  This changes *reachability*, never *trust*: LAN tools stay
  `EXTERNAL`/`EXTERNAL_WRITE`, VOICE-turn-only, confirmation-gated.
- **Disable redirects outright.** `StreamableHttpMcpClient` currently follows
  OkHttp redirects while `validateRedirect` is **dead code (never called)** — a
  pre-existing REMOTE SSRF reliance, not just a LAN concern. Set
  `followRedirects(false)` / `followSslRedirects(false)` on the per-server client
  (never the shared one); make `validateRedirect` LAN-aware as
  defense-in-depth. An MCP endpoint should never cross hosts.
- **TLS: HTTPS-first, decided.** LAN requires `https` and the app-wide cleartext
  flag stays off (the loopback-only exception is unchanged). A **self-signed HA
  cert is the foreseeable friction** — v1 fails closed with an actionable message
  (use the cert's hostname; don't rely on an IP SAN). Per-server **SPKI pinning**
  (vault `mcpCertPin:<serverId>` → SHA-256 of the leaf SPKI, per-server client
  only, system trust first, no global trust-all) is a **separate, security-reviewed
  lane gated on device availability** — do not ship trust-all-adjacent code
  unreviewed.

**Host advantages a third party can exploit:** many servers (breadth), user keys
(no rev-share/account), local execution (privacy), a live tool surface
(`McpBudget` + dynamic discovery), and a trust model (`EXTERNAL` /
`EXTERNAL_WRITE` / `WriteConfirmation`, no token through the model) ahead of most
hosts.

**Gating factor:** trust UX and curation. Ship **packs/templates** or the moat
stays theoretical.

---

## 10. What NOT to build

**Platform-gated (hard refuse):** default-assistant role / system hotword;
privileged notification access; Siri-style Announce Notifications / App Intents /
OAuth-through-Play; cross-app agentic automation and screen/image context.

**Fights the hardware:** speaker ID / Voice ID; sound-event detection;
on-device ASR/TTS/LLM.

**Fights the platform (GMS-free, always-on):** geofencing via GMS
(`GeofencingClient`) or a `location` foreground-service type (would break the
always-on boot/idle start path); being a smart-home **hub** (no radio, GMS-gated
Matter **commissioning** — but smart-home *control* through a provider registry
is **in scope**, §7); Cast / multi-room over GMS / wearables.

**Attractive but wrong:** a general automation/rules SaaS; an ambient map/visual
dashboard (screen-off + legal risk); a proactive LLM narrator (hallucinated nags
+ injection-to-speech).

---

## 11. Proactivity done well (avoiding "annoying")

The repo already encodes the right answer — extend, don't replace:

1. **Never let the model decide to speak.** Deterministic fire path
   (`HabitDetector` → `ProactivePresenter` templates, no LLM call).
2. **A layered suppression firewall**, not a single switch (`BehaviorArbiter`:
   opt-in default OFF, quiet hours, DND, battery, session-idle, media-playing,
   72 h cooldown, 2/day quota, 24 h freshness).
3. **Refine presence** — mined habits require recent interaction; user-scheduled
   routines substitute explicit scheduling as the consent signal.
4. **Consent is behavioural and reversible** — accept/reject ladder
   (3 → muted, 6 → retired); statistics never resurrect a retired rule.
5. **Separate "expected" from "surprising"** — a user-scheduled briefing may be
   assertive; a mined habit stays probationary and conservative.
6. **Autonomous speech is non-external** (first-party `READ_ONLY` only).

---

## 12. Sequencing

**Phase 1 — "It acts at the right time."** R1 Routine/Briefing spine + one hero
template **«Утренняя сводка»** (time + weather + alarms; calendar once R2 lands);
then R2 (native CalDAV). Reuses alarms, tools, `speakProactively`, settings,
memory; no new permission, no hardware.

**Phase 2 — "It runs the house."** R3 MCP packs + the **`LAN` kind** (still the
prerequisite for reaching a LAN HA and local servers), then **R4 — the
first-party `home/` subsystem over a provider registry** (§7.2.1): HA REST/WS
first, then **Yandex IoT** and **Tuya OpenAPI** behind their feasibility gates,
with registry + deterministic resolver + normalized risk tiering, phased **H1**
(HA + read tools + T2-confirm) → **H2/H3** (resolver, grants) → **H4**
(awareness) → **H5/H6** (Yandex/Tuya, gated), because the target is a 50+
multi-ecosystem home (§7.0) and the intent-shaped MCP surface cannot be tiered.
MCP remains the transport for **non-home external** servers. In parallel the
awareness pillar R5 (pull → digest → interrupt) and R6 email.

**Phase 3 — "It ships and stays private."** R9 OTA before external distribution;
R7 commute, R8 local device miscellany; R10 presence only if a low-power
WiFi/dock design is proven; revisit R12 only if the hardware changes.

**Gate for every phase:** new tools declare a `ToolRisk`; new strings land in
both locales (`ResourceParityTest`); new settings join `SettingsInventory`;
autonomous paths use first-party `READ_ONLY` data and never relay external
content; proactivity routes through `BehaviorArbiter`; **local commands (wake,
stop, alarms, timers, device control) still work with the network down** (§3.3).

---

## 13. Highest-leverage next step

**R1 — the Routine/Briefing spine**, immediately followed by **R2** (native
CalDAV calendar) so briefings are genuinely useful. It is the only change that
alters the product category, leverages the most existing code, needs no GMS and
no hardware, and is the foundation for commute alerts — while keeping the
capability that feeds it inside the safe, first-party `READ_ONLY` boundary.

The **cheap standalone win** available now is **R3's `LAN` kind**, since
Home Assistant / local MCP servers are currently unreachable *by design* — and it
unblocks the LAN half of the smart-home group (§7).

---

## 14. Management surface (R13) — optional external control

Jarvis stays a **self-sufficient app**: it configures and runs itself entirely
on-device and needs **no external configuration to function**. The external
management surface is an **optional convenience**, **disabled by default**, that
a user turns on deliberately — from the app UI **or by voice**. It is an
*inbound* surface on a device that holds the vault and may one day drive locks,
so the design is blast-radius-first.

Current surface: **9 category screens, 42 inventory entries, 44 reflected
members, 35 plain pref keys, 9 secret accessors, 1 composite blob**
(`mcpServers`); R4 (§7.2.1) adds a 10th category plus `homeProviders` /
`homeEntities` / `homeAliases` / `homeGrants` / `homeAwarenessEnabled` and an
argument-keyed `homeSecret(providerId, field)` vault accessor (HA LLAT, Yandex
OAuth credentials, Tuya project secret), and a 50+-device **multi-provider** home
makes aliases/grants the hardest thing to edit by hand. That is the pain this
solves.

### 14.1 The mode model (intent vs runtime)

Two concepts, deliberately separated:

- **`ManagementMode`** — persisted user *intent*: `DISABLED` (default) |
  `LOCALHOST` | `LAN`. A prefs key (added to `SettingsInventory`).
- **`managementActive`** — **in-memory only, never persisted**. The socket is
  open iff active.

This is what makes the LAN policy structural rather than a special case:

| mode | on process start | reachable from | idle auto-close |
|---|---|---|---|
| **DISABLED** | inactive | — | n/a |
| **LOCALHOST** | **auto-activates** (loopback only; the sole route in is an authorized `adb forward`) | `127.0.0.1` | none |
| **LAN** | **inactive — requires deliberate re-enable** (UI or voice) | the specific LAN IPv4 | yes, `managementIdleTimeoutMs` (default 15 min) |

A reboot always drops LAN because `active` was never on disk. LOCALHOST
auto-activating is safe (no external exposure). The idle timer is refreshed only
by **authenticated** requests; `/health` and unauthenticated probes do not count.
On expiry the listener closes, `active=false`, and the mode pref still reads
"LAN" (showing "stopped"). `managementMode`/`managementPort` → **SERVICE_RESTART**
(bind change; the server is graph/FGS-owned); `managementIdleTimeoutMs` and
password change → **LIVE**.

### 14.2 Stack (verified)

- **HTTPS server: Ktor 3.6.0 + Netty.** ⚠️ **Not CIO — Ktor's CIO server engine
  throws on any HTTPS connector** (server TLS exists only on Netty/Jetty; Jetty
  is avoided because Ktor 3.x's Jetty targets Java-11 APIs, risky on ART/API 29).
  Ktor is chosen over NanoHTTPD (abandoned since 2016 — a supply-chain risk in a
  security feature) and raw `ServerSocket` (hand-rolled HTTP) because
  `testApplication` lets most of the surface be **JVM-tested with no device**.
  Ktor-on-Android is **not officially supported** — treat it as a spike.
- **TLS cert: BouncyCastle `bcpkix` self-signed**, generated on first enable
  (Android has **no public cert-builder API**; `sun.security.x509` is absent).
  ⚠️ Do **not** use Ktor's `buildKeyStore` — it is documented testing-only
  (1024-bit/SHA1/3-day). SAN must include `localhost`, `127.0.0.1`, and the LAN
  IP. **Key storage: a PKCS#12 blob in `SecretVault`** (software key) is the safe
  default — a non-exportable **AndroidKeyStore** key cannot sign the BC-built
  cert and Ktor's `sslConnector` needs `KeyStore`+alias; AndroidKeyStore-backed
  TLS is a later hardening experiment, not v1. Show the cert **SHA-256
  fingerprint** in the app UI so the browser warning can be verified. **No
  trust-all, no hostname-verifier bypass.**
- **Password KDF: Argon2id via BouncyCastle `Argon2BytesGenerator`**
  (`bcprov`; pure Java, no JNA/JNI), OWASP params (m=46 MiB, t=1, p=1; 19 MiB
  variant on constrained CPUs), 16-byte salt, 32-byte tag, compared with
  `MessageDigest.isEqual` (constant-time). PBKDF2 is the zero-dep fallback but
  OWASP wants 600k iterations (slow on Kirin) and it is less ASIC-resistant.
  Initial password = 20 chars from an unambiguous alphabet via `SecureRandom`
  (avoid `getInstanceStrong()` on Android), shown on request, changeable, stored
  only as a hash + salt; a random session id for the browser is separate.
- **Static UI assets:** the SPA lives in `assets/web/` and is served by a small
  `get("{path...}")` handler streaming `AssetManager` — **`staticResources()`
  reads classpath, not Android assets**, so it is not used directly.

### 14.3 Auth, sessions, and browser-attack hardening

- **REST:** `Authorization: Bearer <password>` (verified via Argon2id per
  request; low rate). **Web UI:** login form → `HttpOnly; Secure; SameSite=Strict`
  session cookie; the server stores `SHA-256(sessionId)+expiry` in memory.
  `POST /sessions` lets a script exchange the password once for a short-lived
  token. Password change **revokes all sessions**.
- **Rate-limit + lockout** on failed auth (per-IP and global backoff); always
  constant-time compare.
- **DNS-rebinding / CSRF** (needed even with HTTPS + password, because a LAN
  browser can be attacked): **bind to the specific interface** (never `0.0.0.0`),
  a **strict `Host` allow-list** (the actual rebinding defense), reject repeated
  `Host`/`Origin`/`Sec-Fetch-Site` headers, and the **Go `CrossOriginProtection`
  algorithm** — safe methods always allowed; `Sec-Fetch-Site: same-origin` **or
  `none`** allowed (`none` is a user navigation — ⚠️ *not* a reject), otherwise
  fall back to Origin-must-equal-Host. No CORS. Strict CSP
  (`default-src 'self'`; no inline/CDN). Never trust `X-Forwarded-*`.

### 14.4 REST API — config only, never actions

**Architecture: extract a pure management core; the API and UI are clients.**
The durable layer stays `AppPrefs` + the vault — the appliance must configure
itself with the server dead. `manage/` mirrors `mcp/` (pure core + thin
Android-touching transport). **No runtime reflection over `AppPrefs`** (release
is R8-minified): an explicit `ManagementBindings` map (type + getter + setter per
key), with a unit test asserting it covers **every** `SettingsInventory.entries`
key so a new setting fails the build until bound.

`/api/v1`: `GET /status` (state, mode+active, `pendingPolicies`, versions);
`GET /settings` + `GET/PUT /settings/{key}` (secrets rejected here); `GET
/secrets` (metadata `{key, set}` only) + `PUT/DELETE /secrets/{key}`
(write-only); `/mcp/servers[/{id}]` CRUD via the codec; `GET /export` +
`POST /import`; `POST /password/change`; `POST/DELETE /sessions`; `POST
/management/mode`. Composite blobs are typed sub-resources (never raw-blob
writes). "Stored but not yet active" is surfaced via `policy` per setting and
`pendingPolicies` in `/status`.

**The API is config-only and never crosses `ToolAuthorization`/`ToolRisk`'s
action choke point** — it cannot call tools, home control, alarms, or music.
Mapping an API caller to a `TurnOrigin` would either be denied (EXTERNAL needs
VOICE) or would silently create a non-voice action path, weakening the
consequence-tier model (§7.4). Action endpoints are refused by design.

### 14.5 Web UI

A **static SPA in `assets/web/`** — hand-written HTML + ES-module JS + CSS, **no
frontend build step** (it ships in the APK, must run offline under a strict CSP,
and must not add a Node toolchain to an Android CI). Screens mirror the settings
categories (rendered dynamically from `/settings`), plus Status, Secrets
(write-only), MCP servers, Management mode, Export/Import, and Password. The
ApplyPolicy banner is driven by `/status.pendingPolicies`. **@designer owns the
visual system**; the API contract and asset packaging are frozen by this design.

### 14.6 Export / import

**The export is ALWAYS encrypted and opaque — there is no plaintext path.** A
user passphrase is required to export and to import; the on-disk artifact is a
single **AES-256-GCM envelope** (key derived from the passphrase by Argon2id,
16-byte salt, random 12-byte nonce) with only a small **cleartext versioned
header** (`{format, formatVersion, appVersion, kdf{...}, nonce, ciphertext}`) so
import can recognise and parse it. Plaintext settings never touch disk — config
is opaque to the user and to anything that finds the file. The plaintext
document it wraps is versioned JSON with a **reserved `home` section from day
one** (`{settings, mcpServers, home}`). Import requires the passphrase, validates
every key against `ManagementBindings`, type-checks, and **applies settings
atomically** (one editor commit); on validation failure it writes nothing. Entry
points: Settings → MANAGEMENT via SAF (host-owned intents) and the REST
`/export`/`/import`.

**Secrets in export — decision:** the envelope is always encrypted, so the only
question is *what* goes inside. **Default excludes secrets**; an explicit
**opt-in "include secrets"** adds them to the same encrypted envelope (the
friction of re-entering ~10 secrets + unbounded `mcpSecret(id)` on a device
migration is real), with a count + warning and never automatic/backgrounded. The
trade is explicit: exported secrets leave the device-bound vault's protection and
rely on the passphrase alone. This supersedes the earlier "bulk export stays
impossible" line — portable secret backup is a deliberate, opt-in risk, but it is
never a *plaintext* risk because the container is always encrypted.

### 14.7 Voice enable/disable (confirmation-gated)

Opening a network-reachable surface by voice is security-sensitive, so **enabling
LAN** routes through the existing next-turn **`WriteConfirmation` /
`AffirmativeUtterance`** gate (no token through the model) — *not*
`IrreversibleCommand` ("enable" is not a removal verb). **Disabling is always
allowed on a voice turn** (fail-safe direction). **LOCALHOST enable** may skip
the two-turn dance (loopback is adb-gated) but is logged. Spoken strings via
`SpeechPhrases` (both locales). UI enable is direct (on-device possession
authenticates the owner).

### 14.8 Phasing

- **P0** — `manage/` pure core (`ManagementMode`, `ManagementBindings`,
  `ManagementCore`, `PasswordHasher`, `SessionStore`, `ExportCodec`), cert+TLS,
  Ktor Netty server on `127.0.0.1`, minimal login + settings UI, SAF
  export/import, `SettingsCategory.MANAGEMENT`. The **TLS/Ktor-on-EMUI spike is
  the first risk to retire** (device-validate early; NanoHTTPD behind the same
  `ManagementHttpServer` interface is the fallback).
- **P1** — full SPA, LAN mode + idle auto-close, Host/Origin/`Sec-Fetch-Site`/CSP
  hardening, voice tools + `SpeechPhrases`, pending-policy banner.
- **P2** — `home*` screens (depends on R4), memory export, cert-rotation UX,
  optional mTLS experiment.

**Independence:** P0's core + export/import + TLS are **independent of R4** and
can start now; only P2's home screens depend on R4 (reserve the export `home`
section immediately).

**Risks / walk-away:** an always-on LAN listener holding the vault (mitigated by
default-off, deliberate enable, idle-close, specific-interface bind, write-only
secrets, config-only, rate-limit, strict CSP, minimal deps); Ktor-Netty on
EMUI/API 29 (unproven — the spike); self-signed browser UX; EMUI Doze/Wi-Fi
behavior for an idle listener. **Walk away if** Ktor (and the NanoHTTPD
fallback) prove unstable on-device, the LAN listener cannot reliably
auto-close, or anyone asks to export secrets without a passphrase or to expose
action endpoints (both refused).

---

## Appendix A — open questions

- Confirm Yandex IMAP/SMTP endpoints against the live help page.
- Confirm the Feedly consumer OAuth path (vs Enterprise-only token).
- Confirm MAX developer access for a non-RF-entity (likely blocked).
- **G0 — Yandex provider gate (blocking).** Confirm a Yandex OAuth app can
  obtain `iot:view`/`iot:control` without a legal entity or moderation rejection
  *(§7.3.1)*; resolve it by registering an app and requesting the scopes *before
  writing any code*. Also observe the `iot:*` token TTL and whether refresh needs
  a `client_secret` (public/PKCE client end-to-end?), and **probe the
  undocumented rate limits**.
- **G1 — Tuya provider gate (blocking).** Confirm an individual can create a
  project, QR-bind the SmartLife account, get usable `/specification` DP codes,
  and what the real API/quota terms are *(§7.3.2)*.
- **G2 — HA LAN gate (blocking).** Confirm the target HA is reachable over LAN
  HTTPS, the WS registries enumerate entity/area/device, and `subscribe_events`
  pushes on the target network/appliance *(§7.0/§7.2.1)*; decide how entity
  resolution disambiguates across 50+ mixed-ecosystem devices.
- **G3 — HA-vs-direct-Tuya (blocking, product).** Decide whether HA's Tuya
  integration already reaches enough of the deployment; direct Tuya is gated
  behind this *(§7.3.2)*.
- Sber Matter/Thread support and the VK smart-home developer API — both
  unverified (§7.3).
- Decide the local fast-path boundary (deterministic intent vs. LLM) for obvious
  commands.
- Decide whether to ever allow a scoped external "read grant" for autonomous
  turns (§8).
- **Management surface (§14):** retire the risky spike first — **Ktor‑Netty on
  EMUI/API 29** (unproven; NanoHTTPD fallback), self-signed cert + SAN +
  fingerprint, `adb forward` + browser warning, LAN bind + reachability, idle
  auto-close, and EMUI Doze/Wi‑Fi behavior for an idle listener. Confirm the
  export format/scope and the include-secrets opt‑in.
- On-device validation of EMUI notification access and GPS lock quality indoors.

## Appendix B — source provenance

Competitive landscape: `lib-2` (2026 assistant research). Strategy synthesis:
`ora-3`. Content-source + email feasibility: `lib-3`. Russian smart-home
ecosystem + Yandex user-API deep-dive: `lib-4`. Product-philosophy review:
external critique (2026), folded in §0/§3. MCP protocol/design: `lib-1` / `ora-1`
/ `ora-2`. Config-surface recon: `exp-1`; management-surface prior art: `lib-5`;
management architecture: `ora-2`; Android embedded-HTTPS/crypto stack: `lib-6`.
All session findings are reproducible from the cited official sources;
uncertainty is flagged inline above.
