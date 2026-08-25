# Jarvis v4 — Remediation Plan

Maps every defect found in the v3 audit (code + docs) to its fix, records the
architectural decisions, and defines acceptance criteria per phase. No backward
compatibility is kept (Room schema bumps destructively; APIs may change freely).
The v3 plan is preserved in git history.

Constraints agreed with the owner:
- **targetSdk stays 30** (appliance profile, HarmonyOS 2.0 target). Portability
  bugs get guards/docs, not platform migration.
- **Verification = `./gradlew assembleDebug` + `testDebugUnitTest` green**, with
  an expanded JVM suite including a named regression test per fixed bug.
- Stubs getting real implementations: **live ASR partials in UI**, **real
  wake-word sensitivity rebuild**, **timer persistence + reboot re-arm**.
  `JarvisNotificationListener` stays as-is (owner decision).

---

## 1. Audit findings → resolution map

Severity: C = critical, M = major, m = minor.

| # | Finding (v3 audit) | Sev | Resolution in v4 | Verified by |
|---|--------------------|-----|------------------|-------------|
| C1a | `FileLoggingTree` missing `import android.util.Log` → unresolved ref | C | Add import; parameterize priority via `Timber.Tree` log level constant instead of raw `Log.*` where possible | Build gate (P0) |
| C1b | `appGraph.wireErrorHandler {}` does not exist; error-TTS wired to nothing | C | Replace with `sessionManager.setOnError { speakError(it) }`; add compile-time seal: make handler injection part of `AppGraph.sessionManager()` factory so it cannot be forgotten | Build gate + `SessionManagerTest` error-path |
| C2 | Assistant `tool_calls` persisted before results; interruption leaves dangling pair mid-history → HTTP 400 until window ages out | C | **Atomic pair persistence**: execute all tools first, then insert assistant+results in one Room `@Transaction` (`MessageDao.insertAssistantWithResults`). **Defense in depth**: `getHistoryForLLM()` drops any assistant-with-tool_calls whose ids lack matching tool rows anywhere in the window (not just leading) | New: interrupted-tool-pass regression test; mid-window orphan test |
| C3 | `PorcupineDetector.release()` deletes native engine without mutex/join → use-after-free SIGSEGV | C | `release()` joins actor (bounded wait) then swaps+deletes under `processMutex`; delete-after-rebuild ordering everywhere | Release-race test via injected fake engine |
| M1 | Detector init failure emitted into subscriber-less SharedFlow → dropped; app runs deaf silently (regression of v3 #15) | M | Detector exposes `state: StateFlow<DetectorState>` (Bootstrapping/Ready/Failed(reason)); `SessionManager.startListening` reads state synchronously before subscribing; Failed → spoken error + notification, no silent deafness | Init-failure surfacing test |
| M2 | Watchdog cancel builds PendingIntent without action → never cancels; stopped device wakes every 15 min forever | M | Single `watchdogIntent()`/`watchdogPendingIntent()` provider used by schedule AND cancel; identical action/class/flags | Intent-parity unit test |
| M3 | Daily alarm re-arm only in Dismiss/Snooze click handlers; back/HOME/system-kill loses tomorrow's occurrence | M | Re-arm moves into scheduler-owned `onFired(id)` called from ringing activity `onCreate` (idempotent), not click handlers; auto-timeout path also re-arms | Re-arm-idempotency test (fake scheduler) |
| M4 | SSE response body leaked on every successful turn | M | Body/source closed in `finally`; stream consumed via `use`-equivalent | MockWebServer leak test |
| M5 | Barge-in during token-fetch/pre-`newCall` window cancels nothing; full LLM completion still downloaded; blocked socket read ignores cancellation | M | New `llm/Http.kt`: `Call.await()` via `suspendCancellableCoroutine` + `invokeOnCancellation { call.cancel() }`, used by TokenManager OAuth fetch and SSE open; read loop calls `ensureActive()` per line; OkHttp call timeouts set | Cancel-during-token-fetch test (MockWebServer with delayed auth) |
| M6 | Stale sessions emit unguarded `ErrorOccurred` → shared state machine yanked to IDLE mid-session-B; ducking/notification desync | M | `sessionSeq` threaded through every error/finish path; only current-seq emissions reach the state machine; `cancelAll()` performs explicit guarded transition to IDLE; service `onDestroy` unducks unconditionally | Stale-error-ignored test; cancelAll→IDLE test |
| M7 | TTS containing «Джарвис» self-barges-in (>600 ms into playback); docs claim cooldown prevents this | M | Barge-in policy object: outside SPEAKING single detection accepts (unchanged); during SPEAKING requires second detection within 1200 ms (repeat-to-interrupt, standard assistant UX) — configurable `bargeInPolicy = SINGLE\|REPEAT_DURING_PLAYBACK`, default REPEAT. Docs updated to describe actual guarantee | Policy test with fake detector: single-detection-during-playback does not flush; repeat does |
| M8 | Pre-roll ring buffer 160 ms vs wake→ASR-open latency up to ~1.5 s+ → first words clipped | M | Ring capacity from `JarvisConfig.preRollMs` (default 3000 ms ≈ 96 KB); eviction counter logged when pre-roll overflows | Ring-buffer eviction test |
| M9 | Timers vanish on reboot; request codes = truncated epoch-millis (mod 2³² collisions) | M | **Unified `ScheduledAlertEntity`** (kind ALARM\|TIMER, label, triggerAt, repeatDaily, enabled; id PK = sole request-code authority); timers persisted; `BootReceiver` re-arms alarms + future timers; scheduler API owns all codes | Round-trip + reboot re-arm tests; code-uniqueness property test |
| M10 | Missing `USE_FULL_SCREEN_INTENT` while calling `setFullScreenIntent`; no exact-alarm guard for future targetSdk bumps | m | Manifest gains `USE_FULL_SCREEN_INTENT` (harmless on API 30, correct later); exact-alarm caveat documented in RUNBOOK (targetSdk 30 needs no runtime guard) | Manifest review |
| m1 | `isError` detected by `"error"` substring | m | `ToolResult(content, isError)` domain type through FunctionRouter/wire mapper; registry timeout marks `isError=true` | WireDtoTest extension |
| m2 | OAuth raw bodies baked into exception messages → secrets-adjacent material in rotating logs; `now` captured before mutex wait | m | Sanitized exception messages (status + category only); timestamp captured inside mutex | TokenManager test: malformed-body message contains no body text |
| m3 | EncryptedSharedPreferences constructed on main thread; corruption bricks init in watchdog loop; deprecated lib | m | Heavy init (SecurePrefs, TokenManager, Porcupine) moved off main thread into async `ensureInitialized`; corruption → wipe-and-recreate-once fallback; persistent failure → actionable error state | Corruption-recovery test (fake prefs factory) |
| m4 | Player: cross-thread `track.flush()` race; `release()` strands queued deferreds 20 s; `written<=0` truncates silently; degenerate min-buffer fallback | m | Flush/release routed as actor commands; release fails queued deferreds promptly; short-write aborts sentence with log; sane min-buffer floor; thin `AudioTrackAdapter` seam introduced for JVM testability | Player actor tests via fake adapter |
| m5 | `getMinBufferSize` unvalidated; producer spam-loops when source not started | m | Validate ≤0 → error state; producer exits cleanly on unstarted/closed source | Source-validation test |
| m6 | Splitter treats any letter+dot as abbreviation; O(n²) resplit (cosmetic) | m | Abbreviation whitelist + consonant rule; keep resplit (bounded at 280 chars) | SplitterTest cases |
| m7 | Settings Apply stop/start race can leave watchdog cancelled + service dead | m | Ordered restart: start-new-before-stop-old is replaced by explicit suspend handoff; `onDestroy` cancels watchdog only when `userStopped` flag set by the explicit-stop path | Service logic review + flag-state test |
| m8 | Ducking desync via state-edge coupling (with M6/m-cancelAll) | m | Covered by M6 fix + unconditional unduck in `onDestroy` | Desync scenario test |
| m9 | Drain joins children sequentially, each with own 60 s → worst-case N×60 s parked in SPEAKING | m | Parallel join under one overall deadline; finish transitions even on timeout | Drain-deadline test |
| m10 | Every sentence opens concurrent gRPC TTS stream up front (rate limits, memory spike) | m | Bounded synthesis prefetch: `Semaphore(2)` around TTS fetch; playback serialization unchanged | Prefetch-bound test |
| m11 | ASR instant server error droppable (replay-less SharedFlow, async subscribe); gRPC deadline == local cap (90 s) misclassifies as Failed | m | ASR event flow gets `extraBufferCapacity` + subscription established before stream open; gRPC deadline 95 s vs local cap 90 s | ASR-event-delivery test |
| m12 | MainActivity polls GraphHolder at 500 ms; mute toggles pipeline without cancelling active session; mute undone by power receiver | m | UI observes `StateFlow`s (transcript, partials, assistant state, muted) pushed by service; mute routes through pipeline owner which cancels active session and survives receiver restarts | Flow-wiring review; mute-state test |
| m13 | `AlarmRinger` spawns unsupervised sleeping Thread per ring; MediaPlayer.prepare on main | m | Ringer timer becomes coroutine in activity scope; prepare on Dispatchers.IO | Review |
| m14 | NetworkMonitor ignores VALIDATED → captive portal passes offline gate | m | Require VALIDATED capability | Monitor test (fake network) |
| m15 | Dead code: `JarvisConfig.porcupineSensitivity`, `AlarmDao.update`, `AudioUtil.toShortArray/reset`, empty `setSensitivity`, unused import, wrong-package ProGuard keep, gRPC endpoint hardcoded in AppGraph, duplicated ToolCallAccum | m | Removed/consolidated: ProGuard keeps `wire.**$$serializer`; endpoint into `JarvisConfig`; single `ToolCallAccumulator` in `llm/` used by clients + session; `setSensitivity` becomes real (see S2) | Grep-clean gate |
| m16 | `ListAlarmsTool` hides disabled alarms from voice listing | m | Voice listing shows all alerts with enabled/disabled status | Tool output test |

## 2. Stub implementations (owner-selected)

### S1. Live ASR partials in UI
`SessionManager` exposes `partialTranscript: StateFlow<String>` (updated on
`AsrEvent.Partial`, cleared on Final/Error/IDLE). Service forwards transcript,
partials, assistant state and mute as `StateFlow`s on the graph; MainActivity
subscribes (500 ms polling deleted). `TranscriptAdapter` renders an ephemeral
partial row (dimmed italic) replaced by the final message. README's
"streaming recognition" claim becomes true.

### S2. Real wake-word sensitivity
`PorcupineDetector.setSensitivity(v)` rebuilds the engine under `processMutex`
in crash-safe order (build new → swap → delete old); failure keeps the old
engine and reports via detector state. Restart-based application remains
supported but is no longer required. Requires extracting a `PorcupineEngine`
interface (build/process/delete) so the JVM suite can exercise rebuild/race
logic with a fake engine.

### S3. Timer persistence + reboot re-arm
Delivered by the unified alert store (M9): timers are rows in
`scheduled_alerts`, armed by the same scheduler, re-armed on boot/package-
replace when `triggerAt > now`. Voice setTimer/listAlarms operate on the same
store; collision-free request codes by construction.

## 3. Architectural decisions

### 3.1 Atomic tool-pair persistence (C2)
The unit of consistency is the (assistant-with-tool_calls + all tool results)
group. Tools execute first, results buffer in memory, one `@Transaction`
persists the group. History retrieval additionally sanitizes dangling pairs
anywhere in the window — cheap, deterministic, and covers process-death
between transaction and next pass.

### 3.2 One cancellable-HTTP primitive (M4/M5/m2)
All OkHttp usage (OAuth token fetch, SSE open, weather GET) goes through
`llm/Http.kt`'s coroutine-bound `Call.await()` with `invokeOnCancellation`.
Cancellation semantics become uniform and testable; bodies close in `finally`.

### 3.3 Unified scheduled-alert store (M9/S3/M3)
One table, one scheduler API, one request-code authority (row id), one re-arm
path owned by the scheduler (`onFired`) and invoked idempotently from the
ringing activity lifecycle — not from buttons. Alarms and timers differ only
by `kind` and repeat fields. Schema v2, destructive (allowed).

### 3.4 Detector lifecycle as state (C3/M1/S2)
`WakeWordDetector` gains `state: StateFlow<DetectorState>`; native teardown is
mutex-guarded and join-first; sensitivity changes are engine rebuilds under
the same mutex. A `PorcupineEngine` seam isolates the native SDK for tests.

### 3.5 Barge-in policy object (M7)
Self-echo cannot be solved reliably without hardware AEC guarantees, so the
behavior becomes an explicit, documented policy: interrupting playback
requires a repeated wake word (default), single-shot elsewhere. Config knob
allows SINGLE for quieter rooms.

### 3.6 Session decomposition (refactor)
`SessionManager` splits into `TurnRunner` (one turn: listen → collect → LLM
tool loop → speak → drain; fully fake-testable) and `SessionManager` (session
lifecycle, seq-guarded state events, barge-in, partials). Both stay ≤ ~250
LOC. `ToolCallAccumulator` lives once in `llm/`.

### 3.7 UI observes, never polls (m12)
Service owns and publishes `StateFlow`s; activities collect them. Direct
GraphHolder mutation from UI is limited to explicit user intents (mute, stop)
routed through methods that own their side effects.

## 4. Phase plan

| Phase | Scope | Acceptance criteria |
|-------|-------|---------------------|
| P0 Build truth | C1a, C1b; establish green baseline | `assembleDebug` + `testDebugUnitTest` exit 0; 45 existing tests green |
| P1 Critical correctness | C2 (atomic persistence + sanitizer), C3 (detector teardown), M1 (detector state) | New regression tests: interrupted-tool-pass, mid-window orphan, release-race, init-failure-surfaced; full suite green |
| P2 Transport reliability | `llm/Http.kt` (M5), body-close (M4), token sanitization (m2), `ToolResult` (m1) | MockWebServer tests: cancel-during-auth aborts, no body leak, sanitized errors; wire tests for isError; suite green |
| P3 Session/service | Watchdog parity (M2), stale-session guards + ducking (M6/m8), daily re-arm path (M3 session-side), Apply ordering (m7), drain redesign (m9), TTS prefetch (m10), ASR delivery/deadline (m11), partials plumbing (S1 session side) | Extended SessionManagerTest: stale-error ignored, cancelAll→IDLE, drain deadline, partials emitted; suite green |
| P4 Data/scheduler | Alert store v2 (M9/S3), scheduler API + `onFired` re-arm (M3), ringing activity delegation, boot re-arm timers, FSI permission (M10), voice listing (m16) | DAO round-trip tests, reboot re-arm test, request-code uniqueness property, suite green |
| P5 Audio | Barge-in policy (M7), pre-roll config (M8), player fixes + adapter seam (m4), source validation (m5), ringer coroutine (m13) | Policy tests, ring-buffer eviction test, player actor tests via fake adapter; suite green |
| P6 Stubs/UI | Sensitivity rebuild (S2), partials UI + flow wiring (S1/m12), monitor VALIDATED (m14), splitter (m6) | Rebuild/race tests with fake engine; UI flows wired; suite green |
| P7 Decomposition/cleanup | TurnRunner extraction (3.6), dead-code sweep (m15), ProGuard/config consolidation | Suite green; `SessionManager.kt` ≤ ~250 LOC; grep-clean for removed symbols |
| P8 Docs truth pass | ARCHITECTURE/README/RUNBOOK rewritten to shipped behavior; every claim maps to a test or file | Doc-review checklist: no claim without evidence pointer |

Review gates: independent @oracle review after **P1** (critical fixes) and
after **P7** (pre-docs). Orchestrator runs gradle gates after every phase.

## 5. Execution lanes (post-approval)

Strict file ownership per lane; orchestrator merges and runs gates.

- **Lane T (transport)**: `llm/*`, `tools/ToolContract.kt`, `tools/FunctionRouter.kt`, `util/NetworkMonitor.kt` — P2 items.
- **Lane S (session/service)**: `session/*`, `service/JarvisForegroundService.kt`, `service/BootReceiver.kt` — P3 items.
- **Lane D (data/scheduler)**: `data/*`, `tools/AlarmScheduler.kt`, `tools/AlarmTools.kt`, `service/AlarmRingingActivity.kt`, manifest — P4 items.
- **Lane A (audio)**: `audio/*`, `contracts/AudioContracts.kt`, `config/JarvisConfig.kt` — P5 items.
- P0 runs alone first (everything depends on a compiling tree). P2–P5 lanes
  are file-disjoint and may run in parallel after P1 lands (P1 touches
  session+audio and must complete first). P6–P8 sequential.

Test-infrastructure additions: `mockwebserver` (test), `AudioTrackAdapter`
seam, `PorcupineEngine` seam. No other new dependencies.

## 6. Out of scope (recorded decisions)

- targetSdk bump / platform modernization — declined by owner.
- `JarvisNotificationListener` removal — declined by owner (stays as-is).
- Instrumentation/on-device verification — out of scope; JVM suite only.
- Offline ASR/TTS fallback providers — future work, interfaces already allow.
