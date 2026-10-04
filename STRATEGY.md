# Jarvis — Capability Thesis & Product Strategy

**Status:** internal strategy document (not user-facing). Companion to
`AGENTS.md` (ramp-up), `ARCHITECTURE.md` (how it is built), `THREAT_MODEL.md`
(what it defends), `RUNBOOK.md` (operating it).

**Scope:** what Jarvis should become, which capabilities are worth adding, and
which are traps. The centrepiece is **passive awareness** — the capability most
aligned with an always-on appliance — analysed in depth in §6.

**Date:** 2026. Version reference: Jarvis `0.2.2` (pre-1.0).

---

## 0. TL;DR

1. **Jarvis's niche is the controllable, extensible, GMS-free voice appliance.**
   Siri/Gemini are becoming more capable and less controllable; Alexa+ is
   controllable but vendor-bound. Jarvis is what runs on the hardware those
   ecosystems abandon, with the user owning the keys and the integrations.
2. **Two capabilities lead: proactivity (it acts at the right time) and passive
   awareness (it notices what matters).** Both exploit the "always-on" property
   that phone assistants structurally cannot, and both are local-first.
3. **MCP is the extensibility bet, but not the autonomous-data path.** MCP tools
   are `EXTERNAL` and only run on a voice turn; scheduled/autonomous features
   must use first-party `READ_ONLY` data instead.
4. **Passive awareness is the flagship candidate** (§6): notifications + feeds +
   email, delivered as *pull → digest → interrupt*, triaged deterministically,
   gated by the existing arbiter, and summarised on a **tool-free** pass so
   untrusted content can never become an action.
5. **Refuse**: accessibility screen-scraping, screen OCR, user-account scraping
   of private messengers, on-device ASR/TTS, speaker ID, sound-event detection,
   and anything platform-gated. These fight the platform or the hardware.
6. **First concrete step: the Routine/Briefing spine**, immediately followed by
   the passive-awareness read model.

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
the keys and the pipe"**: key-bound (not account-bound), account-free,
provider-swappable, and egress-transparent. Any roadmap item that assumes local
ASR/TTS (e.g. "build offline dictation") is misguided on this hardware.

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
smart-home mesh (bridge via MCP, don't become a hub); default-assistant /
hotword / privileged-notification roles; multi-user identity (no mic array).

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
| R2 | **Calendar reader (CalDAV-native)** | **P0** | M | Feeds briefings + commute; must be first-party `READ_ONLY` (§7) |
| R3 | **MCP packs + `LAN` server kind** | **P0** | M | Unblocks Home Assistant; today `McpUrlPolicy` rejects `192.168.x.x` |
| R4 | **Commute / "leave now"** | P1 | M | Alexa+'s headline; MapKit has no traffic-aware arrival (accuracy cap) |
| R5 | **Passive awareness** (§6) | **P0/P1** | L | The flagship; see §6 |
| R6 | **Email awareness + confirmation-gated reply** | P1 | L | Clean IMAP/SMTP + app passwords; on-thesis |
| R7 | **Local device miscellany** (§5.1) | P1 | S–M | Cheap, local, high daily value |
| R8 | **OTA / self-update** | P2 | M | Required before external distribution |
| R9 | Presence/geofence routines | P2 | L | Fights the platform (no GMS geofencing, no `location` FGS type) |
| R10 | Ambient idle dashboard | P3 | M | Screen-off device; worsens the accepted MapKit attribution risk |
| R11 | Speaker ID / sound events | **Reject** | L | Need a mic array |

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

## 7. The architectural boundary that shapes everything

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

## 8. MCP as the strategic bet

**Verdict: yes — as the *interactive extension* strategy, not the
autonomous-data strategy.**

**Why right:** no lock-in; user keys; no vendor approval; capability grows by
configuration, not release; GMS-free by construction; can run local (loopback)
for privacy-sensitive servers. Jarvis can match Alexa+ on extensibility while
owning the data path.

**Where it must not be used:** autonomous/scheduled data (policy-denied); the
LAN smart-home case is *currently broken* (`McpUrlPolicy` rejects private hosts
for REMOTE, loopback-only for LOCAL) — **add a `LAN` kind before pitching smart
home via MCP**.

**Host advantages a third party can exploit:** many servers (breadth), user keys
(no rev-share/account), local execution (privacy), a live tool surface
(`McpBudget` + dynamic discovery), and a trust model (`EXTERNAL` /
`EXTERNAL_WRITE` / `WriteConfirmation`, no token through the model) ahead of most
hosts.

**Gating factor:** trust UX and curation. Ship **packs/templates** or the moat
stays theoretical.

---

## 9. What NOT to build

**Platform-gated (hard refuse):** default-assistant role / system hotword;
privileged notification access; Siri-style Announce Notifications / App Intents /
OAuth-through-Play; cross-app agentic automation and screen/image context.

**Fights the hardware:** speaker ID / Voice ID; sound-event detection;
on-device ASR/TTS/LLM.

**Fights the platform (GMS-free, always-on):** geofencing via GMS
(`GeofencingClient`) or a `location` foreground-service type (would break the
always-on boot/idle start path); broad smart-home hub / Matter-Thread; Cast /
multi-room / wearables.

**Attractive but wrong:** a general automation/rules SaaS; an ambient map/visual
dashboard (screen-off + legal risk); a proactive LLM narrator (hallucinated nags
+ injection-to-speech).

---

## 10. Proactivity done well (avoiding "annoying")

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

## 11. Sequencing

**Phase 1 — "It acts at the right time."** R1 Routine/Briefing spine + one hero
template **«Утренняя сводка»** (time + weather + alarms; calendar once R2 lands);
then R2 (native CalDAV). Reuses alarms, tools, `speakProactively`, settings,
memory; no new permission, no hardware.

**Phase 2 — "It notices what matters."** R5 passive awareness (pull → digest →
interrupt), R6 email, R3 MCP packs + `LAN` kind, R4 commute alerts, R7 local
device miscellany.

**Phase 3 — "It ships and stays private."** R8 OTA before external distribution;
R9 presence only if a low-power WiFi/dock design is proven; revisit R11 only if
the hardware changes.

**Gate for every phase:** new tools declare a `ToolRisk`; new strings land in
both locales (`ResourceParityTest`); new settings join `SettingsInventory`;
autonomous paths use first-party `READ_ONLY` data and never relay external
content; proactivity routes through `BehaviorArbiter`.

---

## 12. Highest-leverage next step

**R1 — the Routine/Briefing spine**, immediately followed by **R2** (native
CalDAV calendar) so briefings are genuinely useful. It is the only change that
alters the product category, leverages the most existing code, needs no GMS and
no hardware, and is the foundation for commute alerts — while keeping the
capability that feeds it inside the safe, first-party `READ_ONLY` boundary.

The **cheap standalone win** available now is **R3's `LAN` kind**, since
Home Assistant / local MCP servers are currently unreachable *by design*.

---

## Appendix A — open questions

- Confirm Yandex IMAP/SMTP endpoints against the live help page.
- Confirm the Feedly consumer OAuth path (vs Enterprise-only token).
- Confirm MAX developer access for a non-RF-entity (likely blocked).
- Decide the local fast-path boundary (deterministic intent vs. LLM) for obvious
  commands.
- Decide whether to ever allow a scoped external "read grant" for autonomous
  turns (§7).
- On-device validation of EMUI notification access and GPS lock quality indoors.

## Appendix B — source provenance

Competitive landscape: `lib-2` (2026 assistant research). Strategy synthesis:
`ora-3`. Content-source + email feasibility: `lib-3`. MCP protocol/design:
`lib-1` / `ora-1` / `ora-2`. All session findings are reproducible from the
cited official sources; uncertainty is flagged inline above.
