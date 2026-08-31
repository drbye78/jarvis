# Music Lane Production Plan — Capability-Driven Voice Music Control

> **Status (Aug 31 2026): IMPLEMENTED on `feat/music-capabilities`** —
> Phases 0–6 complete. Phase 0 = audit M-fixes + carried-over bugs; Phase 1
> = Tier 0 probes (MediaCapabilities, gated dispatch, MusicDiag); Phase 2 =
> Tier 1 structured voice search (VoiceQuery scoring, Assistant extras
> Bundle, S4 legacy intent, playMusic slots); Phase 3 = Tier 3 browser lane
> pulled forward (S0 search→playFromMediaId, S2 token cold start,
> listPlaylists/searchLibrary); Phase 4 = Tier 2 rich transport (12 gated
> actions, API-29 speed guard, compat-wrapped controllers); Phase 5 = audio
> etiquette (duck-during-TTS, spoken progress, pauseMusicOnWake); Phase 6 =
> docs truth-pass + delivery. Verified by the three compile gates (162 JVM
> tests) — on-device ground-truth probing (§10) still requires the tablet.

Target repo state: `main` @ `13612ed` + music lane (`ae1a0f6`).
Device target: Huawei MatePad SE 11 (Android 11 / HarmonyOS 2.0, API 30, no GMS).
Player target: Яндекс Музыка (`ru.yandex.music`, legacy `com.yandex.music`), any
other MediaSession player as graceful fallback.

---

## 1. Goal and principles

**Goal.** Turn «Джарвис, включи Bohemian Rhapsody» from an *architected cascade*
into a precise, honest, fast, production-grade music voice lane, and unlock the
full capability surface that Assistant-compliant players (Yandex Music included)
expose locally: structured voice search, rich transport control, and library
browsing with deterministic `playFromMediaId`.

**Non-goals.** Streaming audio ourselves; user authentication into Yandex;
Cloud-to-device APIs; any GMS dependency.

**Principles (the quality bar for every phase).**

1. **Probe, never assume.** Feature claims from vendor docs are hints, not
   contracts. Ground truth is `PlaybackState.getActions()`, `getRatingType()`,
   and the MediaBrowser connection result — read at runtime, cached per attempt,
   logged for diagnostics.
2. **Honest status contract.** `PLAYING` is only ever spoken after verification
   against the *requested* query. Every unverifiable outcome is labeled for what
   it is (`SEARCH_OPENED`, `APP_OPENED`, `DISPATCHED`). No theater.
3. **Graceful degradation.** Every strategy works alone. The cascade picks the
   best path *the player actually supports* — capability bits gate each hop, so
   an unsupported feature costs one probe, not a timeout.
4. **JVM-testable core.** All decision logic (cascade, scoring, capability
   parsing, selection) stays pure Kotlin behind the existing `MediaGateway`
   seam. Android adapters stay thin. Every behavior change ships with tests.
5. **No regressions.** Existing 25 music tests + 15 suites keep passing; tool
   schema changes are additive; alarms/timers/weather/device tools untouched.
6. **Atomic patch series.** One concern per commit, reviewable, revertable.

---

## 2. Verified API facts this plan relies on

| Fact | Source | Consequence |
|---|---|---|
| `onPlayFromSearch(query, extras)` with `MediaStore.EXTRA_MEDIA_FOCUS` + `EXTRA_MEDIA_ARTIST/ALBUM/PLAYLIST/GENRE/TITLE` is the Assistant voice-search contract | developer.android.com/media/implement/assistant | Tier 1: structured slots |
| **Empty query** `onPlayFromSearch("", null)` = "play recent playlist / random queue" | same | «включи музыку» becomes a real command |
| Legacy `android.media.action.MEDIA_PLAY_FROM_SEARCH` activity intent (`SearchManager.QUERY` extra) is the pre-session fallback | same | Extra cold-start strategy |
| `prepare*` added API 24 = our minSdk; `setPlaybackSpeed` added **API 29**; `seekTo/playFromSearch/skipToQueueItem/setRating` API 21 | API diff pages | One `Build.VERSION` guard needed (speed) |
| `setRepeatMode`/`setShuffleMode`/`prepareFromSearch` **do not exist** in framework `MediaController.TransportControls` — they are androidx.media compat-protocol custom actions | framework reference + API diffs | Tier 2 needs `MediaControllerCompat.wrap()` |
| Compat sessions advertise `ACTION_SET_REPEAT_MODE` / `ACTION_SET_SHUFFLE_MODE` bits in the action mask | PlaybackStateCompat constants | Repeat/shuffle ARE probe-gated |
| `onGetRoot()` may return null (refuse), **empty root (connect + token, no browse)**, or browsable root | legacy MediaBrowserService docs | Even a "refuse browsing" Yandex gives us headless cold start |
| `startActivity` from a foreground service is **silently blocked** on API 29+ (no BAL exemption) unless the app is foreground / exempt | Android 10 BAL restrictions | `launchApp`/deep links are *conditional* strategies; browser bind is the fix |
| `MediaBrowserCompat.connect()` binds an exported service — allowed from FGS context, no UI, no permission | Assistant "Playback from a service" pattern | Headless cold start without BAL |
| `getActiveSessions()` needs our notification listener; the browser token path needs **no permission at all** | MediaSessionManager docs | Browser lane also de-risks permissions |

---

## 3. Target architecture

```
tools/MusicTools.kt                  LLM schemas v2 (structured slots, rich actions)
media/MediaCapabilities.kt   NEW     pure: parsed action mask, rating type, queue presence
media/VoiceQuery.kt          NEW     pure: structured query slots + verification scoring
media/MediaGateway.kt                +capabilities(), +structured playFromSearch, +seek/queue/rating
media/MediaBrowserGateway.kt NEW     pure contract: discovery / connect / search / children
media/AndroidMediaBrowserGateway.kt NEW  MediaBrowserCompat adapter (bind, token, search)
media/AndroidMediaGateway.kt         Uri.encode fix, BAL honesty, compat wrap, API-29 guard
media/MusicPlaybackOrchestrator.kt   cascade v2 (capability-driven, browser-first cold start)
media/MediaDiagnostics.kt    NEW     logcat capability dump (RUNBOOK tool)
audio/AssistantAudioFocus.kt NEW     duck-during-TTS focus manager (pure state machine + adapter)
audio/SpeechFeedback.kt      NEW     best-effort spoken progress («Секунду…»)
config/JarvisConfig.kt               browser budgets, pauseMusicOnWake, spokenProgress knobs
session/SessionStateMachine.kt       AsrFailed transition-key fix
```

### 3.1 Cascade v2 (playMusic)

```
playMusic(query, slots{artist|album|playlist|genre}, app):
  resolve app (MusicAppCatalog)
  probe live session capabilities (if any)

  S0 BROWSER_SEARCH   browser connected AND player searchable:
                      search(query) → score results vs slots
                      → playFromMediaId(best) → VERIFY(title/artist match)
  S1 LIVE_SESSION     session active AND ACTION_PLAY_FROM_SEARCH bit:
                      playFromSearch(query, extrasFromSlots) → VERIFY
                      (empty query if no slots → "play something")
  S2 BROWSER_COLD     browsable service installed (even empty root):
                      bind MediaBrowserService — NO UI, immune to BAL
                      → session token → controller → playFromSearch → VERIFY
  S3 LAUNCH_COLD      launchApp + poll ≤8s for session → playFromSearch → VERIFY
                      [BAL caveat: works when Jarvis UI visible / kiosk]
  S4 LEGACY_INTENT    resolve MEDIA_PLAY_FROM_SEARCH activity → send intent
                      with SearchManager.QUERY [same BAL caveat]
  S5 DEEP_LINK        yandexmusic://search?query=%20-encoded [same BAL caveat]
  S6 LAUNCH_ONLY      honest APP_OPENED outcome
```

Ordering rationale: **S2 before S3** because binding is the Assistant-grade
headless cold start — no BAL exposure, no permission dependency, and it works
even when Yandex refuses *browsing* (empty root still yields the session token).
S0/S2 both verify deterministically via the mediaId path, which retires the
title-change heuristics for browsable players entirely.

### 3.2 Verification v2 (fixes audit M3)

`VoiceQuery.score(nowPlaying, slots)` — pure, unit-tested:

```
normalize(s)  = lowercase, strip punctuation, collapse whitespace
tokens(x)     = normalize(x).split(' ')
titleScore    = |t(np.title) ∩ t(slots.title ∪ slots.query)| / |t(slots.title ∪ slots.query)|
artistScore   = same against slots.artist (1.0 when no artist slot)
combined      = 0.65·titleScore + 0.35·artistScore
positionReset = np.positionMs < before.positionMs || np.positionMs < 2000

VERIFIED  ⟺ state ∈ {PLAYING, BUFFERING} AND (combined ≥ 0.5 OR positionReset AND title/artist partially match)
```

The blind "position < 10 s ⇒ success" rule is deleted. A player that ignores
`playFromSearch` while the old track happens to be early no longer produces a
confident lie.

### 3.3 Transport selection v2 (fixes audit M4)

`control(action, appHint)`:
named app's session → any **playing** session → most recent session →
media-key fallback. If `appHint` resolved to an installed app that has **no**
live session: instructive outcome («Звук не запущен — открыть?») instead of
silently pausing a different player.

---

## 4. LLM tool contract v2

**playMusic** *(+structured slots)*
```json
{"query": "Bohemian Rhapsody", "artist": "Queen", "album": null,
 "playlist": null, "genre": null, "app": null}
```
System prompt: «назван трек/исполнитель/плейлист — заполни слоты, не склеивай всё в query».

**controlPlayback** *(+rich actions)*
```json
{"action": "play|pause|toggle|next|previous|stop|seek|restart|like|repeat|shuffle|speed",
 "positionMs": 60000, "deltaMs": 30000,
 "mode": "off|one|all", "shuffle": true, "speed": 1.5, "app": null}
```
Each action is capability-gated; unsupported ⇒ honest Russian answer naming the
limitation (never a silent no-op).

**getNowPlaying** *(richer output)*
`title, artist, album, playing, positionSec, durationSec, queueIndex, queueSize,
speed, repeat, shuffle, app`

**New: listPlaylists** `()` → top-level browse children, capped 10:
`[{title, playable, mediaId}]` — mediaIds are session-ephemeral, used
immediately by a follow-up play.

**New: searchLibrary** `(query)` → browser `search()` results capped 10:
`[{title, artist, mediaId}]`

---

## 5. Phases

### Phase 0 — Correctness foundation (audit M-fixes + carried-over bugs)

| # | Fix | File(s) |
|---|---|---|
| 0.1 | **M1**: `URLEncoder.encode` → `Uri.encode` in deep links (spaces become `%20`, not `+`); extract pure `SearchUriBuilder` taking an encoder fn for JVM tests | `AndroidMediaGateway.kt` + new small pure helper |
| 0.2 | **M2**: BAL honesty — no-access branch stops claiming «открыл поиск»; deep-link/launch outcomes phrased as attempted; statuses unchanged | `MusicPlaybackOrchestrator.kt` |
| 0.3 | **M3 (interim)**: delete blind near-start heuristic; require position-reset | `MusicPlaybackOrchestrator.kt` |
| 0.4 | **M4**: transport selection prefers playing session; named-app miss ⇒ instructive outcome | `MusicPlaybackOrchestrator.kt` |
| 0.5 | `AsrFailed(cause)` transition-key mismatch — `is SessionEvent.AsrFailed` branch (machine currently wedges in LISTENING on real ASR failures) | `SessionStateMachine.kt` |
| 0.6 | Deaf out-of-box default: `wakeEngine` default → `"sherpa"` (bundled, zero-config); RUNBOOK truth pass | `AppPrefs.kt`, `RUNBOOK.md` |
| 0.7 | Sensitivity slider no-op: `reconfigureWakeWord` re-reads fresh provider snapshot | `JarvisForegroundService.kt` |
| 0.8 | OpenAI-compat provider unreachable: Settings writes provider prefs (baseUrl/model), `LlmClients` consumes; docs match reality | `SettingsActivity.kt`, `AppGraph.kt`, `LlmClients.kt` |

Est: ~350 main LOC, ~200 test LOC, 5–6 atomic commits.

### Phase 1 — Capability probes (Tier 0)

- `MediaCapabilities` pure model: `data class(supported: Set<TransportAction>,
  ratingType: Int, hasQueue: Boolean)`; parse from the action mask via named
  constants (no magic numbers); unknown bits ignored.
- `MediaControllerHandle.capabilities()`; probe once per strategy attempt,
  passed into the orchestrator.
- Cascade gates on bits: no `ACTION_PLAY_FROM_SEARCH` ⇒ skip S1/S3 dispatch
  (saves the wasted 4.5 s verify wait), jump straight to deep link / browser.
- `MediaDiagnostics.dump()`: logcat table of every session (pkg, mask decoded,
  rating type, queue) + browser discovery results. RUNBOOK gains
  `adb logcat -s MusicDiag` as the first-line troubleshooting step.
- **On-tablet acceptance probe** (user-run, 5 min): the dump answers the Yandex
  ground-truth questions (playFromSearch bit? repeat/shuffle bits? heart
  rating? browser root?) that no static audit can.

Est: ~300 main, ~250 test LOC.

### Phase 2 — Structured voice search (Tier 1)

- `VoiceQuery` pure model + normalization + scoring (§3.2); full M3 fix lands
  here (verification consumes slots).
- `MediaGateway.playFromSearchStructured(query, slots)` → Bundle with
  `MediaStore.EXTRA_MEDIA_FOCUS` (artist/album/playlist/genre entry types) +
  slot extras; adapter assembles, orchestrator stays pure.
- Empty-query semantics: «включи музыку» with no slots and a supporting session
  → `playFromSearch("", null)` ("play my mix"); else previous resume logic.
- Legacy strategy **S4**: manifest `<queries>` gains
  `<intent><action android:name="android.media.action.MEDIA_PLAY_FROM_SEARCH"/></intent>`
  visibility; intent built with `SearchManager.QUERY`.
- `playMusic` LLM schema v2 (§4) + system prompt update + tests for slot
  extraction and Bundle spec relay.

Est: ~450 main, ~400 test LOC.

### Phase 3 — MediaBrowser lane (Tier 3, pulled forward — fixes cold start properly)

Rationale: binding `MediaBrowserService` is the only BAL-immune, permission-free,
headless way to cold-start a compliant player; empty-root acceptance still
yields the session token. Even if Yandex refuses browsing for our package, S2
works; if it allows browsing, S0 + deterministic `playFromMediaId` retire the
heuristic verification entirely.

- `MediaBrowserGateway` contract (pure): `discover(): List<BrowserServiceInfo>`
  (queryIntentServices over `android.media.browse.MediaBrowserService`),
  `connect(pkg, timeoutMs): BrowserSession?`,
  `BrowserSession.search(query, timeoutMs): List<MediaItem>?`,
  `BrowserSession.children(parentId, timeoutMs): List<MediaItem>?`,
  `BrowserSession.controller(): MediaControllerHandle?`,
  `BrowserSession.disconnect()`.
- `AndroidMediaBrowserGateway`: `MediaBrowserCompat` with strict timeouts
  (connect 3 s, search/browse 3 s), `onConnectionFailed` → null (refused),
  empty root → session-with-token, per-package connection cache
  (service-lifetime scope), error-safe disconnect on drop.
- Manifest: `<queries>` + `<intent><action android:name="android.media.browse.MediaBrowserService"/></intent>`.
- Cascade v2 wiring: S0/S2 per §3.1; `playFromMediaId` on the token controller;
  mediaId search results scored by `VoiceQuery` (reuse!).
- Library tools: `listPlaylists`, `searchLibrary` (§4); results capped,
  session-ephemeral mediaIds documented in tool descriptions so the LLM uses
  them immediately.
- Config: `browserConnectTimeoutMs`, `browserSearchTimeoutMs`.

Est: ~700 main, ~450 test LOC.

### Phase 4 — Rich transport (Tier 2)

- Dependency: `androidx.media:media` (+ Proguard keep rules).
- `AndroidControllerHandle`: wrap framework controller with
  `MediaControllerCompat.wrap()` once per handle; expose `seekTo`,
  `skipToQueueItem`, `setRating(RATING_HEART)` (gated on `getRatingType`),
  `setPlaybackSpeed` (**Build.VERSION guard: API 29+**, minSdk is 24),
  `setRepeatMode`, `setShuffleMode`, `prepareFromSearch`.
- Contracts extend `MediaControllerHandle` with the same methods returning
  `Boolean` best-effort semantics; capabilities advertise them via compat
  action bits (`ACTION_SET_REPEAT_MODE`, `ACTION_SET_SHUFFLE_MODE`,
  `ACTION_SEEK_TO`, `ACTION_SKIP_TO_QUEUE_ITEM`, `ACTION_SET_RATING`).
- `controlPlayback` v2 schema (§4) with per-action capability gating and honest
  unsupported-answers; `getNowPlaying` v2 (queue index/size via compat
  `getQueueTitle`/queue metadata, speed, repeat/shuffle state).
- System prompt: «промотай/сначала/лайкни/повтори трек/перемешай/быстрее/медленнее».

Est: ~450 main, ~350 test LOC.

### Phase 5 — Audio etiquette + perceived latency (M5/M6/M7)

- `AssistantAudioFocus`: pure focus state machine + thin
  `AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)` adapter; request at
  first sentence of a TTS generation, abandon on drain/flush; re-request after
  barge-in regeneration. Compliant players (Yandex) duck to ~20 % for the
  spoken confirmation — the standard assistant behavior.
- `pauseMusicOnWake` config knob (default **off**): on session start, pause the
  active external session (clean listening window; mitigates M7 physics).
  No auto-resume — the user says «продолжи»; documented tradeoff.
- `SpeechFeedback`: interface + TTS-backed impl; orchestrator emits
  «Секунду…» when the cascade predicts a long path (no live session and cold
  start ahead) and «Открываю плеер…» before S3. Best-effort: never blocks the
  cascade, never throws, dies with the player generation on barge-in.
- M7 documented in RUNBOOK (no AEC; wake-word through loud music is unreliable;
  pause-on-wake is the mitigation).

Est: ~400 main, ~250 test LOC.

### Phase 6 — Hardening, docs, delivery

- Full JVM suite pass + flake sweep (CopyOnWrite patterns where producer
  coroutines and assertions share state).
- Docs: ARCHITECTURE (cascade v2 diagram + capability model), RUNBOOK
  (diagnostic dump procedure, new troubleshooting ladders, BAL explanation,
  AEC limitation), README feature list.
- Proguard rules for androidx.media; manifest queries audit.
- Patch series on `feat/music-capabilities`, zip + patches to `download/`.

Est: ~300 doc LOC + fixes.

---

## 6. Test inventory (new/extended)

| Suite | Covers |
|---|---|
| `MediaCapabilitiesTest` | mask parsing incl. unknown bits, rating types, queue presence |
| `VoiceQueryTest` | normalization, scoring matrix (title/artist/both/none), position-reset rule |
| `MusicOrchestratorTest` (+~15 cases) | capability-gated skips, S0–S6 cascade with fake browser, empty-query, structured extras relayed, honest BAL phrasing, named-app miss, verification v2 |
| `MediaBrowserGatewayTest` | connect timeout, refused root, empty root + token, search unsupported/timeout, children cap, disconnect hygiene |
| `TransportToolsTest` | seek/like/repeat/shuffle/speed mapping + API-29 guard, unsupported honest answers |
| `AssistantAudioFocusTest` | pure state machine: request/abandon/re-request on regeneration, flush path |
| `SpeechFeedbackTest` | emits on predicted-long cascade, silent when live session, never throws |
| `SearchUriBuilderTest` | `%20` encoding, query structure for both link forms |
| Existing suites | all green (regression gate) |

Compile gates (no Android SDK in env, as before): standalone `kotlinc` +
android-all API 30 + `androidx.media` jar; three gates — pure JVM tests,
adapter compile, FunctionRouter wiring compile.

---

## 7. Risk register

| # | Risk | Likelihood | Mitigation |
|---|---|---|---|
| R1 | Yandex `onGetRoot` refuses our package (null) | unknown | S2 degrades to S3/S5; diagnostics reveal on day one; plan works either way |
| R2 | Yandex ignores playFromSearch extras | medium | verification by VoiceQuery scoring; no false success; S0 path unaffected |
| R3 | BAL blocks S3–S5 when Jarvis UI not visible | certain (API 29+) | S2 bind immune; honest phrasing; kiosk usage typically foreground; RUNBOOK |
| R4 | Compat repeat/shuffle custom actions unsupported | low | probe-gated bits + honest unsupported answer |
| R5 | Focus duck ignored by player | low | cosmetic; confirmation still audible |
| R6 | LLM misroutes new tools/slots | medium | schema descriptions + prompt lines; bounded tool loop already exists |
| R7 | `setPlaybackSpeed` crash on API < 29 | only if guard missed | Build guard + JVM test asserting guard logic |
| R8 | Browser connect leaks handles | medium | session-scoped cache + disconnect on drop + test |
| R9 | Spoken progress overlaps final answer | low | generation-based flush kills stale feedback; short phrases only |

---

## 8. Acceptance criteria (on-device, user-verifiable)

1. «Джарвис, включи Bohemian Rhapsody» — music starts ≤ 10 s typical cold; the
   spoken status is *true* (verified / поиск открыт / открыл приложение).
2. «включи музыку» — queue starts (empty-query) or resumes; no dead air.
3. «включи альбом Группа крови» — album plays via structured extras or
   mediaId; wrong-track rate measurably lower than heuristic baseline.
4. «пауза / дальше / промотай минуту / лайкни / повтори трек / перемешай» —
   correct transport; unsupported actions answered honestly in Russian.
5. «что играет?» — answer includes artist, queue position, state.
6. Spoken confirmation ducks music; optional pause-on-wake works.
7. `adb logcat -s MusicDiag` shows the capability table for installed players.
8. All JVM suites green in the compile gates; patch series applies cleanly.

---

## 9. Delivery

- Branch `feat/music-capabilities` off `main`; ~18 atomic commits across 6
  phases; each phase ends with the three compile gates green.
- Patches + branch zip exported to `download/` per phase batch.
- Doc truth-pass bundled with the code that makes it true.

## 10. Open ground-truth questions (resolved by the Phase-1 diagnostic on the tablet)

1. Does Yandex's live session advertise `ACTION_PLAY_FROM_SEARCH`?
2. Repeat/shuffle bits + heart rating present?
3. Does `onGetRoot` accept `com.jarvis.assistant` (browsable root / empty root / null)?
4. Is `onSearch()` implemented (structured search results)?
5. Does the empty query actually start a queue on the current build?

The plan is robust to every combination of answers — each "no" removes a
strategy, never the feature.
