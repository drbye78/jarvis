# REMEDIATION PLAN — post-audit (0.2.0)

Result of the 2026-09 full project audit (4 lanes: architecture, hygiene, feature completeness, independent
architectural verdict). Verdict: **well-documented prototype with a production-grade skeleton — not yet a
daily driver.** This plan closes the identified gaps.

## Owner decisions (binding)

1. **Real-service testing**: creds live in a gitignored local file; live tests run **locally only**;
   sanitized **recorded fixtures** are committed so CI exercises ASR/TTS without secrets. Keys never enter
   the repo, the chat, or the APK (KeystoreVault remains the only on-device storage path).
2. **Cognitive subsystem**: **keep & finish everything** — retain embedder-selection machinery, finish the
   40-fixture extraction eval, define upgrade criteria for the vector-recall negative result.
3. **This document** is the execution checklist; it is reconciled alongside the other docs in every phase
   (P6 final pass).

## Success criteria

- [x] No utterance/TTS content reachable by the release file log at INFO+ (test-enforced — `SpeechContentLoggingTest`).
- [x] Every doc claim matches code (`grep -rn "currently at v6"` and friends return nothing — only this plan's own gate text mentions it).
- [ ] `SberStreamingAsr`, `SaluteSpeechTts`, service policy, `AlarmRinger` policy, `TurnRunner` have direct
      test suites; CI stays green without credentials.
- [ ] `./gradlew integrationTest` passes locally with creds; recorded fixtures committed.
- [ ] Exact-alarm degradation path exists and is tested (Android 12+ safe).
- [ ] Supersede event-ordering race closed with a stress test.
- [ ] Extraction eval at 40/40 fixtures; device-side perf numbers measured and recorded.
- [ ] detekt baseline shrunk; 7 formatting rules re-enabled; maxIssues stays 0.

---

## Phase 0 — Stop the bleeding (privacy + doc truth)

| ID | Action | Files | Size |
|----|--------|-------|------|
| P0.1 | Demote ASR transcript logging below release-file threshold (`Timber.i` → `d`, keep a content-free length/latency line at `i` if useful) | `session/TurnRunner.kt:138` | S |
| P0.2 | Stop logging spoken sentence content on TTS failure — log exception + sentence length only | `session/TurnRunner.kt:588` | S |
| P0.3 | Redact failure-message content at WARN/ERROR (may embed utterance text) | `session/SessionManager.kt:222,225` | S |
| P0.4 | Regression test: capturing Timber tree at INFO+ over a full turn flow asserts zero utterance content | new test file | M |
| P0.5 | Doc truth: v6→v7 everywhere (AGENTS.md:45, README.md:74, ARCHITECTURE.md:37+356-357, `AppDatabase.kt:21` KDoc) + CHANGELOG entry for `MIGRATION_6_7` (snooze anchor) | 4 docs | S |
| P0.6 | Doc drift: ARCHITECTURE.md:190 minSdk 30→29; FIXPLAN.md:21 annotate superseded minSdk; RUNBOOK MIN_GATE −8 дБ→−16.5 дБ (0.15); RUNBOOK 319→322 keys; ARCHITECTURE.md layers table (add `memory_meta`, `MemoryInspectorActivity`, note `newFromFile` mode); `BasicSmokeTest.kt:48` stale EncryptedSharedPreferences comment | 5 files | S |
| P0.7 | Reword Porcupine error that points at a deliberately-unshipped asset (`jarvis_ru.ppn`) to actionable guidance (enter key / choose custom .ppn in Settings) | `config/JarvisConfig.kt:9`, `audio/HybridWakeWordDetector.kt:225` | S |

**Gate:** `./gradlew :app:assembleDebug :app:testDebugUnitTest`; `grep -rn "currently at v6" *.md` empty.

**Status: ✅ COMPLETE (2026-09).** P0.1–P0.2, P0.4, P0.7 done as specified. P0.3 resolved differently, verified honestly: every `reportFailure` message was traced to a fixed classification phrase (SpeechPhrases literals / fixed wake-engine reasons) that cannot embed user content — no redaction needed; the invariant is now documented in the `reportFailure` KDoc. P0.5–P0.6 done (12 doc edits). Gate green: assembleDebug + 625-test suite.

**Bonus fixed during P0 (found via the gate flake): P0.8 — CognitiveCoordinator rejection-count lost-update race (real production bug, now DONE).** Concurrent `onFollowUpUtterance` calls performed unsynchronized read-modify-write on the same habit-rule row (repro: 70/300 lost updates); fixed with a per-rule `ruleWriteMutex` covering the reject path, `reinforceAccept`, and a merge-safe `fireRule` write (`cognitive/CognitiveCoordinator.kt`); test hardened to await intermediate steps with a diagnosable timeout. 300/300 clean post-fix; full gate green twice.

Follow-ups recorded from P0 execution:
- **P5.5 (open)**: `media/AndroidMediaBrowserGateway.kt:141` logs the music search query at `Timber.i` — same privacy class as P0.1–P0.3, off the turn hot path.
- **P5.3 addendum (open)**: wake-word failure reasons are user-facing via `phrases.wakeWordEngineError(reason)` with the reason interpolated raw (English); full localization needs the `DetectorState`/`SpeechPhrases` seam (both locales).
- **P4.4 addendum (open)**: `HabitDetector`'s rule writes (`recompute`/`promoteProbationRules`/`unmuteExpired`) are not covered by `ruleWriteMutex` (nightly-only paths, rare overlap) — extend serialization during the P4.4 refactor.
- Working tree also holds the owner's own uncommitted `SherpaKwsEngine.kt` zipformer-v1 `modelType` fix (not from this plan).

## Phase 1 — Test the failure lanes (largest phase)

| ID | Action | Files | Size |
|----|--------|-------|------|
| P1.1 | Test infra: in-process gRPC test dependency (grpc-inprocess or equivalent fake transport); shared fake-stream builders for ASR/TTS | `app/build.gradle.kts`, `app/src/test` | M |
| P1.2 | `SberStreamingAsr` suite (zero tests today): partial results, EOU, no-speech timeout, deadline-exceeded, mid-stream token expiry → refresh, stream dies mid-utterance, malformed server events | new test files | L |
| P1.3 | `SaluteSpeechTts` suite: stream assembly, mid-stream failure, 60 s drain timeout, voice catalog mapping | new test files | M |
| P1.4 | Extract service **policy** (watchdog timing, revive conditions, mute gating, action routing) from `JarvisForegroundService` into a testable class + unit tests; service stays a thin shell. Behavior-preserving | `service/JarvisForegroundService.kt` (942 LOC) + new class | L |
| P1.5 | `AlarmRinger` policy tests (loop/auto-stop/vibration decisions); BootReceiver re-schedule path test | `tools/AlarmScheduler.kt` area | M |
| P1.6 | `TurnRunner` direct tests: tool-pass budget (5), zero-output retry, mid-turn stop, error paths | new test file | M |
| P1.7 | androidTest (device-only, manual): `SherpaKwsEngine`/`SherpaModelStore` extraction smoke, `PlaybackCaptureFarEndSource` smoke; documented as not CI-runnable | `app/src/androidTest` | M |

**Gate:** suite grows ~623 → ~750+; existing suite still green after the P1.4 refactor (behavior-preservation evidence).

## Phase 2 — Real-service integration tier (local creds + recorded fixtures)

| ID | Action | Files | Size |
|----|--------|-------|------|
| P2.1 | Credential plumbing: gitignored `local.secrets.properties` (or env vars `JARVIS_SALUTE_CLIENT_ID/SECRET`, `JARVIS_GIGACHAT_*`); Gradle registers the `integrationTest` task only when creds present; `.gitignore` entry; CI unaffected by construction | build files, `.gitignore` | M |
| P2.2 | Live smoke tests (tagged, local-only, quota-aware tiny payloads): GigaChat OAuth fetch + `chatOnce` streaming + embedding call; Salute ASR short-utterance round-trip; Salute TTS synthesis round-trip | new `integrationTest` source set | M |
| P2.3 | Recording pipeline: local task that captures real service responses → **sanitized** fixtures (creds stripped, timestamps normalized) → committed under `app/src/test/resources/recorded/` → Phase 1 fake-stream tests replay them in CI | new task + fixtures | M |
| P2.4 | Docs: README/RUNBOOK "Integration testing" section — local cred setup, what gets recorded, privacy note (no user audio/text in fixtures) | 2 docs | S |

**Gate:** `./gradlew integrationTest` green locally; CI green with zero secrets; fixtures in tree.

## Phase 3 — Correctness landmines

| ID | Action | Files | Size |
|----|--------|-------|------|
| P3.1 | `SCHEDULE_EXACT_ALARM`: add permission + `canScheduleExactAlarms()` check with honest degradation (inexact `set()` + user-visible note); strings in BOTH locales (ResourceParityTest); fallback-branch tests; RUNBOOK note | `tools/AlarmScheduler.kt`, manifest, res ×2 | M |
| P3.2 | Close supersede event-ordering race: route terminal state-machine events through a single sequential dispatcher/channel (no suspension inside `controlLock` — preserve the monitor discipline); concurrency stress test for cancelAll/stopActiveTurn/startSession interleavings | `session/SessionManager.kt` | M |
| P3.3 | Wedge-revive guard: revive counter + daily cap + backoff on the 15-min watchdog path; expose counter in diagnostics; policy tests (by-design engine leak on wedge stays — it is correct) | `service/JarvisForegroundService.kt` | M |
| P3.4 | Defense-in-depth: `FileLoggingTree` scrub hook for content-bearing fields + convention note in AGENTS.md (content-bearing logs must be DEBUG-only) | `util/FileLoggingTree.kt`, AGENTS.md | S |

**Gate:** full suite + assembleDebug; stress test reproducible-green over 100 iterations.

## Phase 4 — Cognitive: keep & finish

| ID | Action | Files | Size |
|----|--------|-------|------|
| P4.1 | Author remaining 26 extraction eval fixtures (14→40) per COGNITIVE_PLAN §10.1; explicit checklist in the plan doc; `autoExtract` stays OFF until the gate passes (by design) | `app/src/test/resources/cognitive/eval/fixtures/`, COGNITIVE_PLAN.md | L |
| P4.2 | Device-side measurements (RSS / TTFT / overnight drain) per COGNITIVE_PLAN §10.6 → RUNBOOK table + CHANGELOG (needs the target device) | RUNBOOK.md, CHANGELOG.md | M |
| P4.3 | Vector-recall upgrade criteria: define in COGNITIVE_PLAN what evidence would flip `RetrievalGate.LOCAL_BRANCH_SHIPS` (fixture count, hybrid weighting, device eval); machinery stays, decision path documented | COGNITIVE_PLAN.md | S |
| P4.4 | CognitiveCoordinator structural slim-down (behavior-preserving; features stay): extract JSON export (`:1278`) and benchmark orchestration (`:779`) into own classes; group ~35 constructor params into a deps object; extend the `ruleWriteMutex` serialization to `HabitDetector`'s rule writes (`recompute`/`promoteProbationRules`/`unmuteExpired` — currently uncovered, rare overlap); existing tests must stay green | `cognitive/CognitiveCoordinator.kt` (1,425 LOC) | L |

**Gate:** 40/40 fixtures committed, eval report updated, coordinator refactor green.

## Phase 5 — Debt & hygiene (parallelizable, fixer-friendly)

| ID | Action | Size |
|----|--------|------|
| P5.1 | Dedicated formatting pass: re-enable the 7 disabled detekt formatting rules; shrink 179-entry baseline in batches; `maxIssues: 0` holds | M |
| P5.2 | Dead code: remove `BYPASS_SILENCE_MS` (+ fix its KDoc), 4 unused test helpers | S |
| P5.3 | Minor: `SettingsActivity.kt:866` hardcoded `"✓ $keyword"` → resource (both locales); `StubCallbacks` logs on invoke so pre-init taps are visible; localize wake-word failure reasons through the `SpeechPhrases` seam (`DetectorState.Failed` reasons are interpolated raw/English — P0.7 follow-up) | S |
| P5.4 | Version bump `0.2.1` + CHANGELOG release entry once phases land | S |
| P5.5 | Privacy: `media/AndroidMediaBrowserGateway.kt:141` logs the music search query at `Timber.i` (persists in release file log) — same class as P0.1–P0.3, off the turn hot path | S |

## Phase 6 — Final doc reconcile + re-audit

- Full docs-vs-code reconcile across all 7 docs (AGENTS, README, ARCHITECTURE, RUNBOOK, COGNITIVE_PLAN,
  FIXPLAN, CHANGELOG) + this file; update AGENTS.md test count.
- Re-run the audit lanes (explorers) to verify closure; report residual risk.

## Out of scope (product roadmap, not remediation)

RU wake words (needs a Russian Sherpa model — investigation item), content reminders, weather forecast,
calendar/calls/IoT/web-search, offline LLM. Tracked separately; do not mix into remediation gates.

## Execution rules

- **Docs ship with code**: every phase commits its doc updates in the same change (P0.5–P0.7 style).
- **Gates are sacred**: no phase closes without its gate command green (`:app:assembleDebug :app:testDebugUnitTest` minimum).
- **Secrets hygiene**: creds only in the gitignored local file; never in chat, commits, fixtures, or the APK.
- Real-time-budgeted tests (wedged-engine release ~2.5 s) stay as they are — do not "optimize".
