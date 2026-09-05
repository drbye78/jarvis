# Jarvis Cognitive Memory & Behavioural Subsystem — Architecture and Implementation Plan

Version 1.0 · branch `fixplan-audit-remediation` · baseline commit `c3a662d` (396 tests green)
Scope: production-quality design and a phased implementation plan for long-term memory, user context, habit detection and proactive behaviour in Jarvis. No backward compatibility is preserved; refactors are permitted where they pay for themselves. The plan also closes every defect identified in the two prior audit rounds.

## Executive Summary

Jarvis is a Russian-first, always-listening voice assistant for a single wall-mounted tablet (Kirin 710A), built around Sber cloud ASR/TTS, the GigaChat cloud LLM, Room persistence, a hand-built manual DI graph (`AppGraph`), and a hard product ethic of honest refusal. Today it is stateless across sessions: a 20-message sliding window (`ConversationManager.getHistoryForLLM()`), a time-only system prompt (`TimeAwareSystemPrompt`), and tools that hold no user model. The user's proposed cognitive report correctly identifies the gap and a sensible tiering, but it contains one material architecture mislabel (a "local LLM extractor" — Jarvis has no local LLM and cannot afford one) and underestimates the interaction between proactive speech and the wake-word/AEC/stop-lane audio stack.

This document delivers: (1) a verdict on each report item — accept, modify or reject; (2) a subsystem architecture (the Cognitive Core) that treats memory as a bounded, observable, kill-switchable subsystem rather than a feature; (3) a data model with three versioned Room migrations (v4/v5/v6); (4) read and write pipelines with explicit latency budgets; (5) a behaviour layer that can only speak through the existing session state machine and is arbitration-gated; and (6) a four-phase implementation plan. Phase 0 is a debt-remediation phase that resolves the two must-fix defects from the re-audit (the voice-stop live toggle, the stop-lane rebuild race), the stale AGENTS.md, the dead 11 MB fp32 encoder asset, and the missing process gates (detekt, CHANGELOG, tags). No cognitive feature ships before Phase 0 lands: several Phase 0 fixes (reactive settings, telemetry choke point, honest-outcome conventions) are exactly the foundations the Cognitive Core builds on.

Design honesty notes: fact extraction and summarization run on GigaChat (cloud), not on device — the plan quantifies this cost and gates it behind an evaluation threshold. Semantic vector recall (RAG) is a Phase 3 *option* with a measured go/no-go gate, not a core dependency; at the target corpus size (≤ 500 facts), a lexical FTS index plus deterministic ranking is expected to be sufficient, and brute-force cosine over ≤ ~1 000 vectors makes sqlite-vec unnecessary.

## 1. Verdict on the Proposed Cognitive Report

The report's instincts are right: memory is the single largest missing capability, on-device truth is non-negotiable, and the "do not build" list is largely correct. The verdicts below are per item. "Modify" means the goal is accepted but the mechanism or sequencing changes in this plan.

| Report item | Verdict | Reason and adjustment |
|---|---|---|
| ① User fact extraction after each turn | Modify | Accepted, but: extraction runs on **GigaChat**, not a local LLM (no local model fits Kirin 710A latency/RAM); calls are gated by a heuristic (most tool turns are skipped), batched 3 turns per call, queued in Room so offline turns are processed later; ships opt-in and defaults on only after the Phase 1 evaluation gate (precision ≥ 0.85 on fixtures). |
| ② Command history + time context | Accept | Implemented as `command_events` telemetry captured at the single choke point (`ToolRegistry.executeResult`), with `hour_of_day`, `day_of_week`, tool and slot fingerprint. It is the substrate for habits, not a separately exposed feature. |
| ③ Memory block in system prompt | Accept | Implemented as a `PromptComposer` refactor of `SystemPromptProvider` with a bounded `MemorySection` (`<memory-context>` framing, hard char budget, deterministic output, snapshot tests). The report's ~500-char cap is adopted for Phase 1. |
| ④ Time-slot habit detection (pure SQL) | Accept | `HAVING COUNT(*) >= 5` over a 21-day window, hour buckets, allowlist of habit-eligible tools. Extended with a reinforcement loop (accept/reject counters) and MUTED/RETIRED states so rejected suggestions stop recurring — the report omits the negative feedback path. |
| ⑤ User profile store | Modify | Merged into ①: a profile *view* is derived from `user_facts` (top facts by score), not a second store. One source of truth; a separate profile table would immediately drift. |
| ⑥ Session summaries / episodic memory | Modify | Accepted with two corrections: summarization is summarization-before-prune (hooked where `MessageDao.deleteAllExceptRecent` runs), so it captures messages *leaving* the window rather than duplicating the window; and it is a daily maintenance job plus a prune-triggered job, not per-session overhead. |
| ⑦ Semantic recall RAG (MiniLM ONNX + sqlite-vec) | Reject as specified; gate for later | Three objections: a multilingual embedding model good enough for Russian is 30–60 MB (APK is already ~169 MB; sherpa AAR is 47 MB), sqlite-vec is a native dependency that a ≤ 1 000-vector corpus does not need (brute-force cosine in Kotlin is < 5 ms), and lexical FTS + recency + confidence ranking is likely sufficient at this scale. Phase 3 defines an `EmbeddingEngine` seam, an on-device benchmark, and a recall@k gate; vectors ship only if they beat the lexical baseline by ≥ 15%. |
| ⑧ Proactive suggestions ("cognitive leap") | Modify | Accepted as the Phase 2 behaviour layer, with hard rules the report misses: proactive speech flows through the **existing session state machine** (`SessionManager`), never interrupts a live session or media playback, is followed by a wake-word-free follow-up window so the user confirms before any action, is rate-limited and cooldown-gated, logs every decision (including refusals to speak), and ships **default OFF**. A wall-mounted device that starts talking unprompted is the fastest way to lose trust in it. |
| ⑨ Entity memory | Modify | Deferred to Phase 3 in reduced form: entities and relations derived at extraction time from RELATION-category facts ("my boss Alex"), stored in two small tables, recallable through the same FTS index. A graph store or traversal engine is explicitly rejected. |
| Do-not-build list (cloud personalization, complex knowledge graphs, collaborative memory, complex NLP pipelines, on-device fine-tuning) | Accept, extended | Additions to the list: on-device LLM extraction (a ≥ 2B parameter model means > 1 GB RAM and seconds per turn on 4×A73), sqlite-vec, WorkManager (the codebase already uses the AlarmManager watchdog pattern; adding a second scheduler is churn), and Gradle multi-module split (single-module + package boundary + tests is the honest choice for a solo project; revisit if `cognitive/` exceeds ~40 files). |

One factual correction to the report's premise: Jarvis is not reminder-less. A full alarm/timer subsystem exists (`tools/AlarmTools.kt`, `ScheduledAlertEntity`, `AlarmScheduler`, full-screen `AlarmRingingActivity`), which is exactly the precedent the proactive layer follows for scheduling. What is missing is a calendar source — integration with a calendar app is a non-goal for this plan.

## 2. Design Principles and Hard Constraints

These principles are binding on every task in Section 11. They generalize the conventions that already make the codebase good (honest outcomes, TurnState isolation, ResourceParityTest) into the cognitive domain.

1. **Honesty extends to memory.** Every fact carries provenance (`sourceMessageId`, `origin`, `confidence`) so the assistant can distinguish "you told me" from "I inferred this" and can say "I am not sure" when confidence is low. Writes are never silent: explicit requests ("запомни, что…") produce deterministic writes with honest outcomes (`MemoryOutcome.WRITTEN`, `.MERGED`, `.NEEDS_CLARIFICATION`, `.FAILED_OFFLINE` — modeled on `OpenAppOutcome`). Forgetting is a two-step confirm-then-delete, mirroring the existing irreversible-action policy in the system prompt. Conflict between an old memory and a new statement marks the old fact SUPERSEDED and flags the pair as contested, which the prompt composer surfaces as "уточнить у пользователя" rather than silently overwriting.
2. **On-device truth, cloud compute.** All cognitive data at rest lives in Room in `jarvis.db`. Cloud (GigaChat) is used only as a stateless function on text that has already been sent to the cloud during normal conversation (current/recent utterances). The one deliberate new egress — summarization re-sends older utterances — is documented in the privacy inventory (Section 9) and disabled by the `memory.cloudEnabled` switch. If cloud is unavailable, work is queued, never dropped and never faked.
3. **The turn's latency budget is sacred.** Cognitive work never runs on the hot path. Reads: `gather()` is launched the moment ASR finalizes and must complete within 40 ms without embeddings (hidden inside GigaChat's ~0.5–1 s time-to-first-token). Writes: fire-and-forget into a Room-backed queue, processed on a dedicated `SupervisorJob` scope with its own `CoroutineExceptionHandler` that logs via Timber — never inside `runTurn`. CancellationExceptions are always rethrown (the A8 class of bug is banned by convention and detekt rule, Section 11 Phase 0).
4. **Bounded everything.** Facts ≤ 500 ACTIVE, summaries ≤ 365, raw `command_events` 90 days, `behavior_log` 30 days, prompt budget ≤ 1 200 chars of cognitive additions per turn. Every bound has a compaction policy that runs in nightly maintenance; nothing grows without bound on a device expected to run for months.
5. **Configuration is reactive, never frozen.** The re-audit's must-fix defect (voice-stop toggle dead in 3 of 4 engine/toggle combinations) exists because `WakeWordRequest` was frozen at graph build time and `onVoiceStopToggled` was a no-op. The Cognitive Core introduces `PrefsFlow` (StateFlow wrappers over `AppPrefs`) in Phase 0, and *all* cognitive switches are consumed reactively from day one: toggling memory, extraction, cloud or behaviour takes effect on the next turn without restart. A regression test asserting live-toggle semantics is part of the definition of done for every new setting.
6. **Kill switches degrade to today's behaviour.** `memory.enabled=false` must yield byte-identical system prompts to the pre-cognitive composer (snapshot-tested), an empty queue, and zero extra cloud calls. The subsystem fails closed, quietly, and verifiably.
7. **Observable, inspectable, erasable.** Every extraction, write, recall, suggestion and arbitration decision is logged (Timber tag `Cognitive`, no fact content in release logs) and counted (daily counters in `memory_meta`). The user can ask "что ты обо мне помнишь?" or open the Memory Inspector in Settings to see, delete, export or wipe everything. An assistant that remembers people owes them an audit trail of what it remembers.
8. **Testability before features.** All ranking, normalization, habit, arbitration and composition logic is pure Kotlin (injected clock, no Android imports) and fixture-tested, following the style already used by `BargeInPolicyTest`, `SessionStateMachineTest` and `SystemPromptProviderTests`. Room changes require exported schema + `MigrationTest` updates (the v2→v3 precedent).
## 3. Current-State Integration Map (Ground Truth)

Verified against HEAD `c3a662d`. These are the exact seams the Cognitive Core plugs into; every file path below is exercised by at least one plan task.

| Seam | Current state (file, behaviour) | Cognitive integration |
|---|---|---|
| System prompt | `session/SystemPrompt.kt` — `interface SystemPromptProvider { fun build(): String }`; `TimeAwareSystemPrompt` builds identity + time context + policies + tool routing; called per LLM pass from `TurnRunner.processLlm` (TurnRunner.kt:274–282) | Replace with `PromptComposer` (same interface, signature gains `PromptContext`); `MemorySection` and `SummarySection` render the recalled block |
| Conversation persistence | `data/ConversationManager.kt`, `MessageDao` (suspend + one Flow), window = `recentDesc(20)` + 24 000-char budget; pruning via `deleteAllExceptRecent`; tool passes persisted `@Transaction` | After user-message commit → `cognitive.ingest(...)`; before prune → summarize-and-cursor hook; `id` ordering relied on by the extraction cursor |
| Tool dispatch | `tools/ToolContract.kt` — `ToolRegistry.executeResult` (per-tool timeout, CancellationException rethrown); `FunctionRouter` registers 19 tools | Add `CommandEventRecorder` inside `ToolRegistry` (single choke point records every execution); register 3 new memory tools via `FunctionRouter` |
| Tool honesty/i18n | `tools/OpenAppOutcome.kt`, `tools/ToolStrings.kt` + `ResourceParityTest` | New tools return `MemoryOutcome`; all user-facing strings go through `ToolStrings` (RU default + `values-en`) and the parity test |
| Session lifecycle | `session/SessionManager.kt` — `sessionSeq` guard, `startSession`, `stopActiveTurn` (voice stop), `reportFailure`, `FollowUpWindowController`, state collector arms stop lane on state change | Add `speakProactively(...)` as a guarded mini-session (Section 8.4); proactive turns marked `origin=PROACTIVE` in `command_events` |
| Wake word / stop lane | `audio/HybridWakeWordDetector.kt` (`reconfigure`, `setStopLaneEnabled`, `buildAndSwap`), `contracts/WakeWordRequest.kt` (stopPhraseEnabled frozen at build), `SessionManager.handleStopPhrase` (no pref re-check) | Phase 0 fixes (0.2/0.3) are prerequisites: proactive speech depends on a stop lane that is always armed and honest |
| TTS outside a turn | `audio/SpeechFeedback.kt` — `TtsSpeechFeedback.speak(text)` fire-and-forget, focus-bracketed, killable by `player.flush()`; `AppGraph.speakVoiceSample` second precedent | Proactive speech reuses the same `ttsClient + player + audioFocus` triad, but wrapped in state-machine state so stop-lane and barge-in semantics apply |
| Settings | `util/AppPrefs.kt` (SharedPreferences, no Flows); `SettingsActivity` + `SettingsCallbacks` (`onVoiceStopToggled` currently a no-op) | `PrefsFlow` reactive wrapper (0.7); new "Память" settings section; voice-stop toggle fix (0.2) |
| DI | `di/AppGraph.kt` eager vals + few `by lazy`; `GraphHolder`; graph built in `JarvisForegroundService.ensureInitialized()` | Add `cognitive` section as lazy `val cognitiveCoordinator`; inject the single `AppPrefs` into `FunctionRouter` (it currently builds its own) |
| Scheduling | `service/JarvisForegroundService.kt` + `BootReceiver` + AlarmManager watchdog (`setExactAndAllowWhileIdle`, 15 min); no WorkManager anywhere | Nightly maintenance via a new `ACTION_RUN_COGNITIVE_MAINTENANCE` inexact alarm + opportunistic throttle on service start |
| Room | `data/AppDatabase.kt` version 3, `exportSchema=true`, `MIGRATION_2_3` explicit no-op, `MigrationTest` in androidTest + JVM | Three migrations: v4 (memory core), v5 (temporal + behaviour), v6 (optional vectors/entities), each with schema export and migration tests |
| LLM | `llm/LlmClients.kt` — `SseLlmClient.chatStream`, native OpenAI-style tool calling already parsed (`SseParser`, `ToolCallAccumulator`); `LlmHttpException.isTransient` (429/5xx) | Add non-streaming `chatOnce(request): String` convenience (collect + validate) for extraction/summarization; reuse transient-retry semantics |
| CI | `.github/workflows/ci.yml` — LFS checkout, `testDebugUnitTest`, `assembleDebug` (exists; the earlier "no CI" finding is obsolete) | Add detekt job, asset-audit step, and the new test suites; androidTest remains a documented local/RUNBOOK step (no device in CI) |

## 4. Architecture Overview

The Cognitive Core is one coordinator with three asynchronous paths (read, write, maintenance) and one synchronous tool surface. It lives in a new package `com.jarvis.assistant.cognitive` with subpackages `data`, `extract`, `recall`, `prompt`, `behavior`, `maint`. It is constructed in `AppGraph` as a lazy singleton and owns its own child scope of the graph scope with a `SupervisorJob` and a `CoroutineExceptionHandler` (Timber error, metric bump) — a crash or hang in cognition must never take a session down.

```text
                         ┌──────────────────────────────────────────────────┐
   ASR final utterance ──►│ TurnRunner (per pass)                            │
                         │   PromptComposer.build(PromptContext)            │
                         │     ├─ TimeSection     (port of TimeAware…)      │
                         │     ├─ MemorySection   ◄─ CognitiveCoordinator   │
                         │     │                      .gather(ctx) ≤40 ms   │
                         │     └─ SummarySection  ◄─ session_summaries      │
                         │   … LLM + tools as today …                       │
                         │   after persist: cognitive.ingest(utterance,msg) │
                         └───────────────┬──────────────────────────────────┘
                                         │ (fire-and-forget)
   ┌─────────────────────────────────────▼───────────────────────────────────┐
   │ CognitiveCoordinator (scope: SupervisorJob + COExceptionHandler)         │
   │                                                                          │
   │  WRITE: ExtractionQueueWorker                                            │
   │    ExtractionGate (heuristic, offline) → batch(3 turns / 90 s)           │
   │    → FactExtractor (GigaChat, JSON schema, temp 0) → parse/validate      │
   │    → FactNormalizer (dedup / supersede / contest) → FactRanker score     │
   │    → FTS content row (SearchTokenizer)              [vectors: Phase 3]   │
   │                                                                          │
   │  READ:  MemoryContextAssembler = rank(all ACTIVE) ∪ FTS(query)           │
   │         → top-k ≤ 5 → MemorySection text (≤ 1 200 chars, deterministic)  │
   │                                                                          │
   │  MAINT (nightly, AlarmManager): decay, habits, summaries, compaction,    │
   │         retention, counters                                              │
   │                                                                          │
   │  BEHAVIOR: HabitDetector → habit_rules → BehaviorArbiter (gate matrix)   │
   │    → SessionManager.speakProactively (state machine + follow-up window)  │
   │    → behavior_log (every decision incl. refusals)                        │
   └──────────────────────────────────────────────────────────────────────────┘
   Tools (LLM-callable, in FunctionRouter): remember_fact / recall_facts /
   forget_fact  →  MemoryOutcome (honest outcomes, ToolStrings i18n)
```

Component responsibilities are deliberately narrow:

- `CognitiveCoordinator` — owns settings observation (`PrefsFlow`), exposes `gather(ctx): MemoryContext`, `ingest(utterance, messageId, origin)`, `onMaintenance()`, `wipeAll()`, `exportJson(): JSONObject`. It is the only class the rest of the app sees.
- `FactExtractor`, `FactNormalizer`, `FactRanker`, `SearchTokenizer`, `HabitDetector`, `BehaviorArbiter` — pure Kotlin, injected `Clock`, fully fixture-testable.
- `PromptComposer` — implements the renamed `SystemPromptProvider.build(context: PromptContext): String`; sections render in fixed order with fixed budgets; output is deterministic for a given DB state (snapshot-tested).
- Memory tools (`RememberFactTool`, `RecallFactsTool`, `ForgetFactTool`) — `ToolContract` implementations registered in `FunctionRouter`; explicit writes bypass the extraction LLM entirely.
- `MemoryInspector` — a "Память" section in `SettingsActivity` (RecyclerView of facts with status/confidence/date, per-item delete, export, wipe). Transparency is a feature, not a debug screen.

Concurrency rules: every cognitive coroutine runs with a `CoroutineName("cognitive…")`; `withTimeout` bounds `gather()` (40 ms) and each GigaChat call (20 s); queue workers catch only `IOException`/`SerializationException` (retry/backoff) — any other throwable escapes to the supervisor handler and the task is quarantined, matching the ToolRegistry error discipline. The queue is drained at ≤ 1 concurrent GigaChat call, backing off 30 s on 429 (reusing `LlmHttpException.isTransient` semantics).

## 5. Data Model and Migrations

All entities live in `cognitive/data/` (entities + DAOs) but are registered in the existing `AppDatabase` (entities list + abstract DAO getters), keeping the single-database guarantee and the `MigrationTestHelper` flow. Room schema exports continue to `app/schemas/`.

**Migration v3 → v4 (Phase 1 — memory core)**

- `user_facts`: `id: String` (UUIDv7, time-ordered PK), `category` (IDENTITY, RELATION, PREFERENCE, ROUTINE, POSSESSION, GOAL, HEALTH, OTHER), `subject` (normalized: "user", "boss", "дочь"), `predicate` ("name", "birthday", "likes", "works_at", …), `value`, `valueNormalized` (lowercased, punctuation-stripped), `confidence: Float`, `origin` (EXPLICIT, INFERRED, DERIVED), `status` (ACTIVE, SUPERSEDED, FORGOTTEN, ARCHIVED, QUARANTINED), `supersedesId: String?`, `contested: Boolean`, `sensitive: Boolean`, `sourceMessageId: Long?`, `createdAt`, `updatedAt`, `lastRecalledAt: Long?`, `recallCount: Int`. Indices: status, category, updatedAt. The supersession chain preserves history — forgetting and correction are visible, never destructive-by-default.
- `fact_fts`: Room `@Fts4(contentEntity = UserFact::class)` over `(subject, value, category)`. Because SQLite's default tokenizer is unreliable for Russian morphology, queries are pre-tokenized by `SearchTokenizer` (lowercase, strip punctuation, split, keep tokens ≥ 3 chars, light singularization for common Russian endings) and the indexed content is written pre-tokenized — deterministic and testable, no native tokenizer gamble.
- `extraction_queue`: `messageId` (PK — makes work exactly-once per message), `attempt`, `state` (PENDING, RUNNING, DONE, QUARANTINED), `batchId: String?`, `createdAt`. Survives process death; the worker polls `PENDING` ordered by `messageId`.
- `memory_meta`: key/value (`schemaRev`, `lastSummarizedMessageId`, `lastMaintenanceAt`, `dailyCountersJson`, `extractionBackfillDone`).

**Migration v4 → v5 (Phase 2 — temporal + behavioural)**

- `command_events`: `id`, `at`, `tool`, `argsFingerprint` (normalized slot payload, e.g. `artist=тарковский` → `q:тарковский`), `ok`, `latencyMs`, `origin` (VOICE, PROACTIVE, SCHEDULED). Index `(tool, at)`. Written by `CommandEventRecorder` inside `ToolRegistry.executeResult` — every existing and future tool gets telemetry for free.
- `habit_rules`: `id`, `kind` (TIME_WINDOW, DAY_SET), `tool`, `argsFingerprint`, `hourBucket: Int?`, `daySet: String?`, `supportCount`, `state` (PROBATION, ACTIVE, MUTED, RETIRED), `acceptCount`, `rejectCount`, `lastSuggestedAt`, `lastFiredAt`, `createdAt`.
- `behavior_log`: `id`, `at`, `ruleId?`, `decision` (FIRED, BLOCKED, DEFERRED), `reason`, `utterance: String?` (what was said, only if fired). 30-day retention.
- `session_summaries`: `id`, `kind` (SESSION, DAILY), `fromMessageId`, `toMessageId`, `fromAt`, `toAt`, `text` (Russian), `modelId`, `tokensIn`, `tokensOut`, `createdAt`.

**Migration v5 → v6 (Phase 3 only if the eval gate passes)**

- `fact_vectors`: `factId` (PK), `dim`, `modelId`, `embedding: ByteArray` (BLOB, FloatArray). Brute-force cosine in Kotlin over an in-memory `FloatArray` cache (≤ ~1 000 × 384 × 4 B ≈ 1.4 MB) — no sqlite-vec, no native dependency.
- `memory_entities` / `entity_relations`: `entities(id, type PERSON|PLACE|ORG|MEDIA, name, nameNormalized, aliasesJson)`, `relations(id, subjectEntityId, predicate, objectEntityId, factId, confidence)`. Derived at extraction time; recalled through the same FTS index.

Caps and compaction (nightly): ACTIVE facts > 500 → lowest-score facts move to ARCHIVED (kept, excluded from prompts); superseded chains older than 90 days → deleted keeping the tip; `command_events` > 90 days → deleted (habit rules persist); `behavior_log` > 30 days → deleted; `session_summaries` > 365 rows → oldest DAILY merged/dropped.

Idempotency and crash safety: extraction is keyed by `messageId` (messages are immutable once written); summarization is keyed by cursor `lastSummarizedMessageId` advanced only after a successful commit; habit recomputation is a pure function of `command_events` plus rule counters; maintenance steps are individually guarded so a failure in one step does not skip the others.
## 6. Write Path: Extraction Pipeline and Memory Tools

### 6.1 Ingest hook and gating

`TurnRunner` persists the user message today (`conversationManager.addMessage(...)`). Immediately after that commit it calls `cognitive.ingest(utterance, messageId, origin)` — a non-suspending call that enqueues into `extraction_queue` and returns. Proactive turns (`origin=PROACTIVE`) are never ingested: the assistant must not learn from its own voice.

`ExtractionGate` is an offline heuristic that decides whether the utterance is worth a cloud call. Signals (ordered, cheap string ops on the RU text): explicit memory verbs ("запомни", "напомни, что я"), first-person possessives and self-statements ("я ", "мне ", "мой ", "моя ", "моё ", "у меня"), likes/dislikes patterns ("люблю", "ненавижу", "нравится", "не люблю"), life facts (dates, names preceded by "зовут", workplaces), or the previous tool call having been an explicit remember. Pure tool traffic ("включи джаз", "погода", "таймер на 10 минут") is skipped. Expected skip rate on this assistant's traffic profile is 50–70%, which is what keeps cost and noise down. A prompt-side instruction (added to `MemorySection` header) tells the model it can also *propose* remembering ("хотите, я запомню?"), but only `remember_fact` actually writes.

### 6.2 Batched GigaChat extraction

`ExtractionQueueWorker` drains `extraction_queue`: batches up to 3 PENDING messages (or flushes after 90 s idle / on session end), makes **one** `chatOnce` call with `temperature = 0`, the extraction system prompt (Appendix A), and a strict JSON contract:

```json
{"facts":[{"subject":"user","predicate":"name","value":"Алексей",
  "confidence":0.95,"evidence":"меня зовут Алексей","messageId":42}]}
```

Validation is strict and local: clamp confidence to [0,1]; drop rows with empty `value` or evidence that does not fuzzily occur in the source utterance (anti-hallucination); unknown predicates → OTHER; category derived from a predicate→category map with HEALTH/politics/religion patterns marking `sensitive=true`. Parse failures → `QUARANTINED` row + counter (never retried blindly, never a crash). Cloud unavailable → rows stay PENDING and drain later; `remember_fact` calls made offline report `FAILED_OFFLINE (queued)` honestly to the user.

Decisions that differ from the source report, stated once here: extraction is **cloud** (a local instruct model that could do this reliably needs ≥ 2B parameters — > 1 GB RAM and seconds per turn on a Kirin 710A — a non-starter); batching is **3 turns per call**; and the feature ships with `memory.autoExtract` default **off**, flipping to on only after the Phase 1 evaluation (Section 10) measures precision ≥ 0.85 / recall ≥ 0.7 on 40 annotated Russian dialogue fixtures. Explicit `remember_fact` writes are always available regardless of that gate.

### 6.3 Normalization: dedup, supersession, contest

`FactNormalizer` (pure Kotlin) takes validated facts and the existing fact set:

- Same normalized `(subject, predicate, valueNormalized)` → duplicate: raise confidence (weighted average, capped), bump `lastConfirmedAt`, done.
- Same `(subject, predicate)` but different value → candidate supersession. If `confidence(new) ≥ confidence(old) − 0.1` and the new fact is fresher: old becomes `SUPERSEDED` with `supersedesId` chain, new becomes ACTIVE. If both are strong, both are marked `contested=true` and the prompt composer renders the pair with the instruction to ask the user ("ты ранее говорил, что твой начальник — Олег; уточни?") — honesty over silent overwrite.
- Paraphrase-level dedup ("люблю фильмы Тарковского" vs "обожаю Тарковского") is lexical-only in Phase 1 (token overlap on `valueNormalized`); semantic merge is exactly the Phase 3 vector use case, which is the strongest argument for keeping that gate alive.

### 6.4 Memory tools (LLM-callable)

| Tool | Behaviour | Honesty contract |
|---|---|---|
| `remember_fact(value, category?, subject?)` | Deterministic write, `origin=EXPLICIT`, `confidence=1.0`, routed through the same normalizer | Returns `MemoryOutcome.WRITTEN` / `.MERGED (with what)` / `.NEEDS_CLARIFICATION (ask user)` / `.FAILED_OFFLINE (queued)` — rendered by `ToolStrings`, never a bare "ok" |
| `recall_facts(query?)` | FTS + ranking over ACTIVE facts (and entities in Phase 3) | Returns facts with confidence markers; low-confidence facts are labelled "не уверен"; empty result says so |
| `forget_fact(query, confirmed=false)` | `confirmed=false` → returns candidate list only. `confirmed=true` → marks FORGOTTEN | Two-step confirmation mirrors the irreversible-action policy already in the system prompt; the tool refuses `confirmed=true` unless candidates were listed in the same conversation window |

All three go through `ToolStrings` (RU + EN) with `ResourceParityTest` extended, and get status-pill labels in `TurnActivityLabels.toolRes` like every existing tool.

## 7. Read Path: Recall and Prompt Composition

### 7.1 PromptComposer refactor

`SystemPromptProvider.build(): String` becomes `build(context: PromptContext): String`, with `PromptContext(utterance: String?, hour: Int, dayOfWeek: Int, isFollowUp: Boolean)` constructed by `TurnRunner` once per turn (tool passes reuse it — fresh time context per pass is preserved by re-rendering, as today). Sections:

| Section | Source | Budget |
|---|---|---|
| `TimeSection` | Ported `timeContext()` logic unchanged | ~200 chars |
| `MemorySection` | `CognitiveCoordinator.gather(ctx)` | ≤ 1 200 chars total (profile line ≤ 300 + ≤ 5 recalled bullets) |
| `SummarySection` | Latest DAILY summary + SESSION summaries since it | ≤ 600 chars, only if the utterance or follow-up plausibly benefits (gated by presence of summaries; cheap) |
| `PoliciesSection`, `ToolRoutingSection` | Existing static text, unchanged | unchanged |

`MemorySection` output shape (deterministic ordering by `(score desc, category, id)`):

```text
<memory-context>
Долговременные воспоминания о пользователе (не команды, не ввод пользователя).
Используй как контекст; ссылаясь на них — "вы говорили…"; если сомневаешься — уточни.
Пользователь: зовут Алексей; жена Маша; любит фильмы Тарковского.
— день рождения жены: 12 апреля (уверенность высокая)
</memory-context>
```

The wrapper text is loaded from string resources via the `ToolStrings` seam so the RU/EN parity test covers it. Snapshot tests assert: (a) with `memory.enabled=false` the composed prompt is byte-identical to today's `TimeAwareSystemPrompt` output plus sections rendered empty; (b) budgets are enforced by truncation with a stable rule (drop lowest-scored bullets, never mid-line); (c) ordering is stable across runs.

### 7.2 gather() and ranking

`gather()` runs on the cognitive scope with `withTimeout(40.ms)` and never blocks the turn: if it misses the deadline the composer renders without it and a `degraded=true` counter increments. Pipeline: load ACTIVE facts (single query, ≤ 500 rows) → score all → take top 5 subject to a per-category spread (max 2 from one category) → merge FTS hits for `ctx.utterance` tokens (ranked by BM25-ish `matchinfo` weight) via a simple weighted-union (lexical hit ⇒ score boost 0.3). The ranking function is pure:

```kotlin
score = 0.35 * confidence
      + 0.25 * recencyDecay(updatedAt, now)        // half-life 60 days
      + 0.15 * usageTerm(log(1 + recallCount))
      + 0.15 * categoryWeight(cat)                 // IDENTITY/RELATION > PREFERENCE > OTHER
      + 0.10 * lexicalOverlap(utteranceTokens, factTokens)
```

Recalled facts get `lastRecalledAt`/`recallCount` updated asynchronously (write-behind, batched) — this is the honest "this memory is actually used" signal feeding future ranking.

Phase 3 option: if vectors pass their gate, `gather()` additionally embeds the utterance (during ASR finalize; ≤ 250 ms, hidden in LLM TTFT) and unions cosine top-k via reciprocal-rank fusion. The `EmbeddingEngine` seam has two candidate implementations — GigaChat embeddings endpoint (verify account entitlement first) and a runtime-downloaded int8 multilingual-MiniLM ONNX (~30–60 MB to `filesDir`, checksummed, never in the APK). Ship whichever wins the benchmark; ship neither if neither beats the lexical baseline by ≥ 15% recall@5 on the fixture set — and write the negative result into CHANGELOG. That is the honest-refusal ethos applied to our own roadmap.

## 8. Behavioural Layer: Telemetry, Habits, Arbitration, Proactive Speech

### 8.1 Command telemetry (Phase 2.1)

`CommandEventRecorder` wraps `ToolRegistry.executeResult`: after each execution it writes one `command_events` row (tool, argsFingerprint, ok, latencyMs, origin) in a fire-and-forget coroutine. `argsFingerprint` normalization lives in pure Kotlin per tool (music query → `q:<normalized>`; weather → `city:<normalized>`; volume → bucketed). No content beyond slot values is stored; free-form utterances are never copied into telemetry.

### 8.2 Habit detection (Phase 2.2)

`HabitDetector` runs in nightly maintenance and after every 10th recorded event. Core SQL (illustrative):

```sql
SELECT tool, argsFingerprint,
       CAST(strftime('%H', at/1000, 'unixepoch', 'localtime') / 2 AS INT) AS hourBucket,
       COUNT(*) AS c
FROM command_events
WHERE origin = 'VOICE' AND ok = 1 AND at > :since AND tool IN (:habitEligible)
GROUP BY tool, argsFingerprint, hourBucket
HAVING c >= 5;
```

`habitEligible` is a config allowlist in `JarvisConfig` (playMusic, getWeather, getNowPlaying, listPlaylists, searchLibrary) — read-mostly and music tools only; `setVolume`/`lockScreen` habits would be noise. New rules enter PROBATION; after the first successful suggestion cycle they become ACTIVE. Negative feedback: an explicit "нет"/"не надо" within the follow-up window → `rejectCount++`; 3 rejects → MUTED for 30 days; 6 lifetime rejects → RETIRED. A matching user-executed command within 10 minutes of a suggestion → `acceptCount++` and strengthens the rule. The report's `HAVING COUNT(*)>=5` idea survives intact; the reinforcement states are the addition that keeps it from becoming annoying.

### 8.3 Arbitration (Phase 2.3)

`BehaviorArbiter.evaluate(rule): Decision` — ordered gates, each independently unit-tested, every evaluation logged to `behavior_log` with its reason:

1. `behavior.enabled` (default **OFF**) and quiet hours (default 23:00–08:00, user-configurable) not in effect;
2. DND off and battery > 15% or charging (wall-mounted device: usually satisfied);
3. `stateMachine.state == IDLE` — never during LISTENING/THINKING/SPEAKING;
4. no external media playing (existing media-session/ducking signals from `JarvisForegroundService`);
5. presence proxy: a session interaction within the last 4 hours (do not talk to an empty room);
6. rule cooldown (72 h default) and global daily quota (≤ 2 proactive utterances/day);
7. rule not MUTED/RETIRED and this exact suggestion not delivered in 24 h.

Passing all gates → FIRED. Failing 3 or 4 (media/busy) → DEFERRED with a re-check scheduled within the same day window. Everything else → BLOCKED with the gate name. This matrix is the formal answer to the report's biggest unaddressed risk: proactive speech colliding with the AEC/wake-word/stop-lane stack.

### 8.4 Proactive delivery (Phase 2.4)

`SessionManager.speakProactively(text: String, ruleId: String?): Boolean` is a guarded mini-session: re-check IDLE, `sessionSeq` bump, transition to SPEAKING, then synthesize via the existing `ttsClient → player.play(flow)` triad with `audioFocus` bracketing (the exact pattern of `TtsSpeechFeedback`), then open the follow-up window (`FollowUpWindowController`) so the user can respond wake-word-free — "да, включи" executes through the normal tool path; anything else decays naturally. Because the state machine is in SPEAKING, the existing state collector arms the stop lane, so «стоп» (fixed in Phase 0 to honour the live pref) interrupts a proactive utterance exactly like a normal answer. The utterance is phrased as a proposal from deterministic templates (no LLM call needed: "Ты обычно слушаешь джаз в это время. Включить?"), persisted as `role=assistant` with a proactive marker, and never auto-executes a tool. Delivery is default OFF; the first run experience of this layer is an explicit Settings opt-in with a plain-language explanation.
## 9. Maintenance, Privacy, Failure and Cost Model

### 9.1 Nightly maintenance

Runs via a new inexact `AlarmManager` alarm (`setAndAllowWhileIdle`, ~03:30) delivered to `JarvisForegroundService` as `ACTION_RUN_COGNITIVE_MAINTENANCE`, plus an opportunistic trigger on service start when `lastMaintenanceAt` is older than 20 h (EMUI can defer inexact alarms; the opportunistic path makes latency tolerable without WorkManager). Steps, each individually guarded: confidence decay (0.99×/day inactive facts, floor 0.2 → ARCHIVE candidate); habit recomputation (8.2); summarization backlog + DAILY digest; compaction and retention (Section 5); vector re-embed if `modelId` changed (Phase 3); daily counters flushed to `memory_meta`. The device is wall-mounted and typically charging, which is also the constraint the report worried about — solved by scheduling, not by a new scheduler dependency.

### 9.2 Data-exit inventory (privacy truth table)

| Data | At rest | Leaves device? | Notes |
|---|---|---|---|
| Utterances | `messages` (Room) | Yes — cloud ASR + LLM (pre-existing behaviour) | Unchanged by this plan |
| Extraction prompt | RAM only | Yes — contains the recent utterances being extracted | Same text already sent in-conversation; no *new* class of egress |
| Summaries input | RAM only | Yes — re-sends older utterances in the summarization prompt | The one **new** egress class; covered by `memory.cloudEnabled`; disclosed in README privacy section |
| Facts, vectors, habits, behavior log | Room | **No** | Local-only, inspector-visible, exportable, wipeable |
| Telemetry/logs | logcat/file | No | No fact content in release logs (detekt + review convention) |

User controls: Memory Inspector (view/delete/export JSON via SAF, wipe-all with confirmation), `memory.enabled`, `memory.autoExtract`, `memory.cloudEnabled`, `behavior.enabled`, quiet hours. "Забыть всё" wipes all cognitive tables and cancels queued work in one transaction; it does not touch `messages` (a separate, pre-existing control).

### 9.3 Failure and corruption model

Cloud failures degrade to queued work with honest verbal status ("запомню, когда появится сеть"). DB corruption in cognitive tables is contained: if a cognitive-table query throws an unrecoverable error, the coordinator quarantines the affected table (disables itself for that domain, logs, increments a metric) rather than poisoning sessions — the narrow-scope analogue of the `KeystoreVault` self-heal (which Phase 0 task 0.4 makes deliberately narrow and once-per-process for the same reason). Migration failures follow the existing non-destructive policy (explicit migrations, `fallbackToDestructiveMigrationOnDowngrade` only).

### 9.4 Cost and performance budget

Cloud: at 150 voice turns/day, ~40% gate pass rate, 3 turns/call → ~20 extraction calls/day ≈ 15–25 K tokens/day in + 3–5 K out, plus one summarization call/day and (Phase 3, if enabled) one embedding per turn. Well within personal-tier GigaChat usage; measured during Phase 1 eval, 429s handled by requeue + 30 s backoff. Device budgets (hard gates for sign-off): added turn latency p95 ≤ 50 ms excluding embedding (hidden in TTFT when enabled); startup added ≤ 30 ms (lazy construction in `AppGraph`); RSS delta ≤ 60 MB with vectors off; APK delta ≤ 0.5 MB in Phases 0–2 (models are runtime-downloaded if Phase 3 goes local); overnight battery delta ≤ 2% with charging-gated maintenance.

## 10. Evaluation and Quality Gates

1. **Extraction quality (gates the `autoExtract` default).** 40 annotated RU dialogue fixtures (Appendix C format) covering explicit remember, self-facts, third parties, corrections, negations, noise turns. Pass: precision ≥ 0.85, recall ≥ 0.7, zero hallucinated facts on the anti-hallucination probe set. Measured in CI-able JVM tests against recorded GigaChat responses; re-run on model change (`modelId` stamped on every summary).
2. **Retrieval quality (gates Phase 3 vectors).** 50 query→expected-fact pairs; lexical baseline vs hybrid; ship vectors only at ≥ 15% recall@5 improvement; negative result documented otherwise.
3. **Regression suite.** Baseline 396 tests must stay green; plan adds ~70 (normalizer, ranker, tokenizer, queue, arbiter matrix, habit detector, composer snapshots, tools/outcomes, migrations v3→v4→v5, parity additions). Detekt clean with a checked-in baseline.
4. **Live-toggle regressions.** For every new setting: a test asserting effect-without-restart (the voice-stop lesson, generalized).
5. **E2E scenarios (RUNBOOK, reproducible — closes the informal 77-word protocol).** Scripted device runbook with exact utterances and expected observable behaviour; first three: memory basics (Appendix D), forget/confirm flow, proactive suggestion with accept and reject paths.
6. **Performance/battery report** at end of Phase 2: measured APK size, RSS, TTFT deltas, overnight drain — numbers into CHANGELOG, honest failure notes if budgets are missed.

## 11. Phased Implementation Plan

Estimates assume the project's demonstrated pace (60 commits / 11 days, AI-accelerated, single reviewer). Each task lists its primary files. Acceptance criteria are cumulative — every phase ends with the full suite green, detekt clean, and a CHANGELOG entry.

### Phase 0 — Debt, Foundations, Guardrails (2–4 days; no user-visible features)

| # | Task | Files | Notes |
|---|---|---|---|
| 0.1 | Docs truth pass: rewrite the three stale AGENTS.md claims (Sherpa "asset-only AAR" paragraph, minSdk 24/30 contradiction, EncryptedSharedPreferences) + refresh test count; add "Cognitive subsystem conventions" section (Appendix B) | `AGENTS.md`, `ARCHITECTURE.md` | Unblocks AI-agent contributors; contradiction is actively dangerous today |
| 0.2 | Voice-stop live toggle fix: `RealCallbacks.onVoiceStopToggled` → `graph.reconfigureWakeWord()` (lifecycleScope); `SessionManager.handleStopPhrase` re-checks `voiceStopEnabled()` before `stopActiveTurn()`; regression tests for the 4 engine×toggle combos + toggle-mid-THINKING | `SettingsActivity.kt`, `SessionManager.kt`, new `SessionManagerVoiceStopToggleTest` | The two-line fix proposed in the re-audit, now with matrix tests |
| 0.3 | Stop-lane rebuild race: `HybridWakeWordDetector.ensureStopLane()` (rebuild under `reconfigureMutex` when lane required) called after `buildAndSwap` and from the state collector; test | `HybridWakeWordDetector.kt`, `SessionManager.kt` | Prerequisite for trustworthy proactive speech |
| 0.4 | `KeystoreVault`: narrow catch to `GeneralSecurityException`/`IOException`; self-heal once per process; Timber + counter | `util/KeystoreVault.kt`, test | Ends catch-all self-heal |
| 0.5 | Dead weight: delete unreferenced `sherpa_kws/encoder-…-64.onnx` (fp32, ~11 MB); drop `security-crypto` catalog entry; add CI asset-audit step (fail on unreferenced asset > 1 MB) | `app/src/main/assets/`, `gradle/libs.versions.toml`, `.github/workflows/ci.yml` | APK −11 MB |
| 0.6 | Process gates: detekt (+ktlint) config with baseline, custom `ForbiddenMethodCall` (println, android.util.Log) and tuned `SwallowedException`; CI job; `CHANGELOG.md` (Keep-a-Changelog, backfilled); tag `v0.2.0` at Phase 0 cut | `config/detekt/…`, `.github/workflows/ci.yml`, `CHANGELOG.md` | CI exists (testDebug + assemble); this hardens it |
| 0.7 | `PrefsFlow`: StateFlow wrappers over `AppPrefs` (callbackFlow → stateIn); migrate wake-word config + voice-stop + follow-up reads; inject the single `AppPrefs` into `FunctionRouter` (drop its private instance) | new `util/PrefsFlow.kt`, `AppGraph.kt`, `FunctionRouter.kt`, `SessionManager.kt` | Kills the "frozen config" bug class; foundation for cognitive switches |
| 0.8 | `LlmClient.chatOnce(request): String` non-streaming convenience with transient-retry helper | `llm/LlmClient.kt`, `llm/LlmClients.kt` | Extraction/summarization transport |

Acceptance: CI green incl. detekt; voice-stop toggle works in all 4 combos live; stop lane survives reconfigure while THINKING; asset audit green; tagged release.

### Phase 1 — Memory Core (5–8 days): explicit + extracted facts, prompt injection, inspector

| # | Task | Files |
|---|---|---|
| 1.1 | Room v4: entities/DAOs/FTS4 content table + migration + schema export + `MigrationTest` updates | `cognitive/data/*.kt`, `data/AppDatabase.kt`, `app/schemas/…/4.json` |
| 1.2 | `CognitiveCoordinator` + graph wiring + scope/exception discipline + settings via `PrefsFlow` | `cognitive/CognitiveCoordinator.kt`, `di/AppGraph.kt` |
| 1.3 | Pure-Kotlin core: `SearchTokenizer`, `FactNormalizer`, `FactRanker` + fixtures | `cognitive/extract/*.kt`, `cognitive/recall/*.kt` |
| 1.4 | `ExtractionQueueWorker` + `ExtractionGate` + parser/validator + quarantine + batching/backoff + fixture tests | `cognitive/extract/…` |
| 1.5 | Memory tools + `MemoryOutcome` + `ToolStrings` (RU/EN) + `TurnActivityLabels` + parity test | `cognitive/tools/*.kt`, `tools/ToolStrings.kt`, `tools/FunctionRouter.kt` |
| 1.6 | `PromptComposer` + `PromptContext` + sections + `TurnRunner` integration + snapshot tests (incl. disabled-memory byte-identity) | `session/SystemPrompt.kt` (refactor), `session/TurnRunner.kt` |
| 1.7 | Ingest hook after user-message persist; PROACTIVE-origin exclusion | `session/TurnRunner.kt` |
| 1.8 | Settings "Память" section + Memory Inspector UI (list/delete/export/wipe; sensitive facts rendered **marked** per §12.4-2, with a prompt-visibility switch; backfill opt-in control per §12.4-4) | `SettingsActivity.kt`, `res/layout/activity_settings.xml`, `strings.xml` + `values-en` |
| 1.9 | Eval fixtures + harness; run the gate; decide `autoExtract` default; opt-in backfill of last 200 messages (runs only when the user enables it in the Память section, behind `extractionBackfillDone`) | `cognitive/eval/…`, `RUNBOOK.md` |

Acceptance demo: «Меня зовут Алексей, жена — Маша, люблю Тарковского» → cold restart → «Как меня зовут?» answers honestly; «Что ты обо мне помнишь?» lists facts; «Забудь, как меня зовут» confirms then complies; toggling `memory.enabled` mid-turn is honoured; prompts stay within budget.

### Phase 2 — Temporal Context and Behaviour (5–8 days): telemetry, habits, summaries, proactive speech

| # | Task | Files |
|---|---|---|
| 2.1 | `CommandEventRecorder` in `ToolRegistry` + `command_events` (v5 migration) + fingerprint normalizers | `tools/ToolContract.kt`, `cognitive/data/…` |
| 2.2 | `HabitDetector` + rule states + reinforcement + maintenance scheduling (`ACTION_RUN_COGNITIVE_MAINTENANCE`) | `cognitive/behavior/HabitDetector.kt`, `service/JarvisForegroundService.kt` |
| 2.3 | `BehaviorArbiter` gate matrix + `behavior_log` + unit tests | `cognitive/behavior/…` |
| 2.4 | `SessionManager.speakProactively` + follow-up integration + deterministic suggestion templates + accept/reject loop | `session/SessionManager.kt`, `cognitive/behavior/ProactivePresenter.kt` |
| 2.5 | Summarize-before-prune hook in `ConversationManager` + DAILY digest + `SummarySection` | `data/ConversationManager.kt`, `cognitive/extract/Summarizer.kt` |
| 2.6 | Settings: behaviour toggle (default **OFF** per §12.4-1), quiet hours, quota; perf/battery measurement report | `SettingsActivity.kt`, `CHANGELOG.md` |

Acceptance: seeded 6-day scenario fires one suggestion at the habitual hour on day 7 (IDLE, charging, within hours), respects quiet hours/cooldown/quota, defers during media, and never interrupts a live session; reject×3 mutes the rule; summaries appear in prompts within budget.

### Phase 3 — Semantic Recall and Depth (3–6 days, strictly gated): embeddings, entities, polish

Tasks: `EmbeddingEngine` seam + entitlement check of the GigaChat embeddings endpoint + (alternatively) runtime-downloaded local int8 model benchmark; v6 migration + backfill + brute-force cosine + RRF hybrid; recall@k eval vs lexical baseline (ship-or-reject with documented numbers); entity/relation derivation from RELATION facts + recall integration ("кто мой начальник?"); a user-visible `memory.embedder` selector (Авто/Облако/На устройстве/Выключено, default = benchmark winner, §12.4-3); final perf/battery report, CHANGELOG, tag. If the gate fails, Phase 3 shrinks to the entity tables (cheap, useful) and the honest negative result is recorded.
## 12. Prior-Issue Mitigation Matrix, Risks, Non-Goals, Open Decisions

### 12.1 Every previously identified issue, and where it dies

| Prior finding | Resolution in this plan |
|---|---|
| Voice-stop Settings toggle dead in 3 of 4 engine×toggle combos (must-fix) | Task 0.2 + matrix regression tests; generalized by 0.7 (`PrefsFlow`) |
| AGENTS.md self-contradiction: "asset-only AAR, do NOT add custom-Sherpa loading" vs shipped `newFromFile` code (must-fix) | Task 0.1 rewrite + Appendix B conventions; docs truth becomes part of release checklist |
| AGENTS.md secondary staleness: minSdk 24 vs 30, EncryptedSharedPreferences, test count | Task 0.1 |
| ~11 MB fp32 encoder asset unreferenced (APK bloat) | Task 0.5 deletion + CI asset-audit gate |
| Stop-lane rebuild race after `reconfigureWakeWord()` while session-active | Task 0.3 `ensureStopLane()` + test |
| `KeystoreVault` catch-all self-heal too wide | Task 0.4 narrow catch + once-per-process heal |
| Dead `security-crypto` version-catalog entry | Task 0.5 |
| No detekt/ktlint/CHANGELOG/tags | Task 0.6 (CI existed; hardened) |
| androidTest not in CI | Honest scoping: no device in CI; documented local/RUNBOOK execution + new cognitive migration tests run there |
| "Config frozen at graph build" bug class (`WakeWordRequest`, provider client restart) | Task 0.7 reactive config; cognitive settings reactive by construction; provider-restart limitation documented in Settings |
| println / swallowed `CancellationException` classes | Task 0.6 detekt rules + Appendix B convention (always rethrow) |
| targetSdk 30 | Explicit documented deferral: device runs Android 11, sideloaded, FGS microphone type already correct; revisit only on new hardware (recorded in CHANGELOG) |
| Informal 77-word probe protocol not reproducible | Section 10.5 scripted e2e scenarios in RUNBOOK + eval fixtures |
| TurnState lesson (per-turn state isolation) | `PromptContext` built per turn; no cognitive singletons hold turn state |
| Tool honesty (`OpenAppOutcome` precedent) | `MemoryOutcome` for all memory tools (1.5) |
| i18n seam (`ToolStrings` + parity test) | All cognitive user-facing strings through the seam (1.5, 1.8) |
| `FunctionRouter`/`AppGraph` duplicate `AppPrefs` instances | Task 0.7 injection cleanup |
| Feature gap: no cross-session memory, no profile, no habits | Phases 1–2 (the subject of this plan) |

### 12.2 Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| GigaChat extraction hallucinates facts | Medium | Evidence-anchored validation, temp 0, quarantine, eval gate before default-on, inspector visibility, contested-fact flow |
| Prompt bloat degrades answer quality | Medium | Hard 1 200-char budget, snapshot tests, per-category spread, budget metrics per day |
| Proactive speech annoys or frightens | Medium | Default OFF, arbitration matrix, proposal-not-action, cooldowns/quotas, behavior_log audit |
| Extraction cost/quota (429) on busy days | Low | Gate skips 50–70%, batching, queue + backoff, honest verbal degradation |
| EMUI defers nightly alarms | High (known) | Opportunistic maintenance on service start; wall-mounted device is nearly always on |
| DB growth over months | Low | Caps + nightly compaction (Section 5), retention tables |
| Migration regressions | Low | Exported schemas, `MigrationTest` per version, non-destructive policy |
| Single-developer bus factor on a 4-phase plan | Medium | Phase 0 documentation gates first; CHANGELOG; AGENTS.md conventions for AI-agent contributors; each phase independently shippable |

### 12.3 Non-goals (with reasons, extended from the report's list)

Cloud personalization/profile sync (single device); complex knowledge graphs (two-table entity model suffices at this scale); collaborative/multi-user memory; complex NLP pipelines (no intent classifier, no NLU stack — the LLM is the NLU); on-device fine-tuning; on-device LLM extraction (> 1 GB RAM, seconds per turn); sqlite-vec (brute force wins below ~10 K vectors); WorkManager (AlarmManager pattern already proven here); Gradle multi-module split (single module + package boundary + Konsist-style tests until `cognitive/` outgrows ~40 files); calendar integration (no calendar source on the device; revisit via NotificationListener later).

### 12.4 Resolved decisions (owner sign-off, 2026-09-05)

All four items are approved as recommended, with one binding amendment: **every one of them must be user-configurable in the app's Settings UX** — a plan default is the initial value of a user-visible switch, never a hard-coded behaviour.

1. `behavior.enabled` ships **default OFF** (trust first), revisited after two weeks of inspector data. → The Phase 2 Settings card (2.6) exposes the toggle, quiet hours and the daily quota.
2. Sensitive-fact categories (HEALTH etc.) are **visible-but-marked** by default for this personal single-user device. → The Memory Inspector (1.8) renders them with an explicit marker, and a `memory.sensitiveVisible` switch controls whether they are injected into prompts at all.
3. The Phase 3 embedder is decided **by benchmark** at Phase 3 start (cloud entitlement check vs runtime-downloaded local int8 model). → The outcome becomes the default of a user-visible `memory.embedder` selector (Авто / Облако / На устройстве / Выключено) rather than an unexplained internal choice.
4. Historical-message backfill (1.9) is **opt-in (default OFF)**. → The «Память» settings section gets an explicit «Проанализировать прошлые диалоги» action with a plain-language privacy note; nothing runs without it.

## 13. Appendices

### Appendix A — Extraction contract (essentials)

System prompt (RU, temperature 0): "Ты — модуль извлечения фактов голосового ассистента. На вход даются реплики пользователя с номерами. Верни СТРОГО JSON без пояснений: только фактически устойчивые сведения о пользователе или названных им людях/вещах; не выдумывай; каждое поле evidence должно дословно встречаться в реплике; если фактов нет — верни {\"facts\":[]}." Response schema as in Section 6.2; validator rules: confidence clamp, evidence fuzzy-match against source, predicate whitelist map, sensitive-category patterns, `messageId` must match a batch member.

### Appendix B — AGENTS.md delta (new "Cognitive subsystem conventions")

Text to add after the Phase 0.1 rewrite: memory tools must return `MemoryOutcome` (never bare success strings); all cognitive strings go through `ToolStrings` and `ResourceParityTest`; cognitive config is consumed via `PrefsFlow` — never captured at graph build; cognitive coroutines use the coordinator scope, catch only IO/serialization errors, and always rethrow `CancellationException`; never log fact content outside DEBUG; prompt sections have fixed char budgets enforced by `PromptComposer`; schema changes require exported schema + migration tests + CHANGELOG entry; new settings require a live-toggle regression test.

### Appendix C — Eval fixture format

`cognitive/eval/fixtures/NNN.json`: `{ "dialogue": [{"messageId": 1, "text": "…"}], "expectedFacts": [{"subject": "user", "predicate": "name", "value": "Алексей"}], "forbiddenFacts": ["…"] }` — 40 files; harness runs recorded GigaChat responses through the real validator+normalizer and reports precision/recall/hallucination counts; CI-runnable.

### Appendix D — E2E scenario: memory basics (RUNBOOK extract)

Precondition: fresh `jarvis.db`, Sherpa engine, voice stop enabled. Utterances (spoken, with expected observable behaviour): (1) «Джарвис, меня зовут Алексей, я люблю фильмы Тарковского» → assistant acknowledges; (2) power-cycle device, reopen app; (3) «Джарвис, как меня зовут?» → answers «Алексей» without re-asking; (4) «Что ты обо мне помнишь?» → lists both facts; (5) «Забудь, что я люблю Тарковского» → assistant confirms → «Да» → fact FORGOTTEN, inspector shows it; (6) repeat (3) → assistant states it does not remember; (7) Settings → Память → wipe all → repeat (4) → honest "ничего не помню". Pass: all seven observations, no crashes, behavior of stop phrase unchanged throughout.

### Appendix E — Test matrix summary

JVM: FactNormalizerTest, FactRankerTest, SearchTokenizerTest, ExtractionGateTest, ExtractionParserTest, ExtractionQueueWorkerTest (idempotency, batching, backoff), PromptComposerSnapshotTest, MemoryToolsTest, HabitDetectorTest, BehaviorArbiterTest, PrefsFlowTest, SessionManagerVoiceStopToggleTest, HybridWakeWordDetectorStopLaneTest, SummarizerCursorTest. Instrumented: MigrationTest (3→4→5, +6 if gated in), DatabaseSmokeTest (FTS content sync), MemoryInspectorSmokeTest. Parity: ResourceParityTest extended to cognitive strings. Expected total: ~465–475 tests.
