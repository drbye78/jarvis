# Changelog

All notable changes to Jarvis are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
semver (pre-1.0: breaking changes bump the minor).

## [Unreleased]

## [0.2.1] — 2026-09

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
