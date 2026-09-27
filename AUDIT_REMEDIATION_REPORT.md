# Jarvis — External Audit Remediation: Session Report & Handoff

**Status:** complete for everything accepted; one work-stream (Wave 3 refactors) deliberately
not started, awaiting the owner's go-ahead.
**Written:** 2026-09-27
**Repo:** `/home/roger/src/jarvis` · branch `main` · **HEAD `91c3bfc`** · in sync with `origin/main`
(git status: clean, 0 unpushed — apart from the known untracked strays `.ignore`,
`.opencode-trace/`, `.opencode/`, which are deliberately left alone).

**Gate at HEAD:** `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:detekt`
→ **1295 tests / 0 failures / 0 errors / 0 skipped, detekt 0**, debug APK built.

> This is a session report, not a spec. It exists so work can resume. It deliberately lives
> outside `README/ARCHITECTURE/RUNBOOK/AGENTS/THREAT_MODEL`, which are the specifications.

---

## 1. What this session was

Three external audit reports were verified claim-by-claim against the source, then the
confirmed defects were remediated:

- `/mnt/c/Work/jarvis_05.md` — architecture/quality, 30 sections
- `/mnt/c/Work/jarvis_06.md` — 8 numbered security/reliability findings
- `/mnt/c/Work/jarvis_07.md` — re-audit table (13 prior findings) + new issues

**The reports are not uniformly reliable.** Headline results of the verification:

| Disposition | Count | Notes |
|---|---|---|
| Verified TRUE (factual premise holds) | ~45 | every verdict carries a `file:line` anchor |
| **FALSE** | 1 | `07 §1.2` (LFS stubs) — the AAR is a real 49 MB ZIP, not a 133-byte pointer |
| PARTIALLY TRUE (premise holds, severity overstated) | 3 | `06 §6`, `06 §7`, `05 §12` |
| OPINION (not falsifiable) | 9 | `05 §8, §17, §20–§26` |
| **Real defects confirmed** | 2 + more found later | see §3 |

Two confirmed defects were exactly as the audit described (`06 §2`, `06 §3`). Two further
real problems were found that the audit did **not** report (the decorative authorization
boundary, and the memory-freeze deletion bug) — see §3.3 and §3.4.

The single most valuable audit recommendation — §14/§27 — was that the LLM→tool path needed a
**non-LLM authorization policy** and that the project lacked a **formal threat model**. Both
now exist (§3.3, §5).

---

## 2. Commits (all pushed)

Range: `6e9e4c8..91c3bfc` — 10 commits by this work, on top of `3e0ec25` (see §7.1).

| Commit | Subject | Size |
|---|---|---|
| `1a5b955` | fix: make the watchdog alarm respect the exact-alarm permission | 3 files, +57/−11 |
| `2899388` | fix: bind `forget_fact` confirmation to turn provenance, not a model-held token | 8 files, +166/−68 |
| `b11c9c4` | fix: reveal the transcript resting prompt deterministically | 1 file, +22/−3 |
| `415acb6` | docs: record the audit-remediation fixes and pin JDK 17 per-repo | 2 files, +39 |
| `5f1b372` | feat: gate tool execution behind a risk policy derived from the user's words | 19 files, +981/−18 |
| `734b4d6` | fix: harden the memory subject contract, forget gate and extraction queue | 24 files, +1307/−129 |
| `a1d950a` | docs: record the audit-remediation round and the new invariants | 2 files, +49/−5 |
| `c9653bf` | docs: add `THREAT_MODEL.md` — the audit's "biggest missing artifact" | 4 files, +289/−1 |
| `2b94e8d` | docs: replace dangling plan/audit citations with stable contracts | 150 files, +1065/−1054 |
| `91c3bfc` | fix: freeze stored memory while the master memory switch is off | 6 files, +313/−12 |

---

## 3. Remediation detail

### 3.1 D1 — watchdog alarm assumed the exact-alarm permission (`1a5b955`)

`JarvisForegroundService.scheduleRestartAlarm()` picked `setExactAndAllowWhileIdle` from
`ServicePolicy.useExactAllowWhileIdle(sdkInt)` — an SDK check permanently true at `minSdk 29`
— with no `canScheduleExactAlarms` query and no `try/catch`. On API 31+ with the permission
revoked, `AlarmManager` throws `SecurityException` from inside the service.

Fixed by replacing the predicate with `ServicePolicy.watchdogDelivery(canScheduleExactAlarms)`
and degrading to the inexact `setAndAllowWhileIdle` (with a log) instead of crashing. The
cognitive-maintenance alarm already used an inexact API and needed no change. `ServicePolicyTest`
pins both branches; the revoked case fails under the old unconditional-exact behaviour.

### 3.2 D2 — `forget_fact` self-confirmation (`2899388`, then hardened in `734b4d6`)

The two-step forget returned a `confirmToken` **inside the JSON handed to the LLM**, so the
model could list candidates and confirm them itself within the same turn (the tool loop allows
5 passes). A prompt-injection payload could therefore destroy a stored fact with no user consent.

- `2899388`: token removed from the wire; confirmation bound to turn provenance (a listing arms
  a pending record for the issuing turn; `confirmed=true` honoured only by a strictly later turn
  with an identical candidate set).
- `734b4d6`: an **adversarial review found the first cut was still one turn wide** — it required
  only "a later turn", so any later utterance (including «нет») would confirm it, and the pending
  record never expired. Now it additionally requires the user's own **affirmative** in the
  **immediately-next** finalized utterance (pure RU/EN matcher, whole-token, negation- and
  question-vetoed), bounded by turn age **and** a 5-minute TTL, check-and-act atomic under a
  coroutine mutex, and the delete loop now precedes releasing the grant (a mid-loop failure
  reports `Failed` instead of a false success).

**Residual (documented, not closed):** a partial delete leaves already-forgotten rows forgotten
(no cross-row rollback); it reports `Failed`.

### 3.3 A1 — tool authorization boundary (`5f1b372`, corrected before commit)

The audit's headline architectural finding: **no authorization boundary** existed between the
LLM and tool execution (`TurnRunner` → `ToolRegistry.executeResult` → `execute`), so the only
controls were per-tool runtime checks and two prompt-level instructions (model-judged).

Implemented: every one of the 24 runtime tools declares a `ToolRisk`
(`READ_ONLY` / `STATEFUL` / `IRREVERSIBLE`) pinned in a single table (`tools/ToolRisks.kt`),
enforced at the one choke point every call traverses (`ToolRegistry.executeResult`); a denial is
an honest error result and `execute` is never invoked.

**Critical lesson — the first implementation was decorative.** Adversarial review (`ora-2`) found
it bound `explicitUserCommand = true` **unconditionally**, so `decide()` returned `Allow` for all
24 tools on the only live path — ~300 lines of false assurance with all tests green. The gap was
in the *spec* (provenance was named but never defined). Corrected in the same commit:
`explicitUserCommand` is now derived from the turn's **own final ASR text** by a pure matcher
(`tools/IrreversibleCommand.kt`), bound after ASR finalizes and before any dispatch, with a
fail-closed `false` baseline at turn start; `STATEFUL` denies on an absent context; the one
off-turn caller (pause-on-wake) gets an explicit system-authored context; the dead
proactive/scheduled branches were deleted; and the 24 risk values are pinned so a silent
reclassification fails a test. `forget_fact` keeps its own independent gate.

**Residual (documented):** the gate is **turn-granular, not per-argument** — if the user genuinely
says «отмени будильник», an injection riding that same turn can steer the model to a different
irreversible tool. And `remember_fact` (`STATEFUL`) remains a durable injection-persistence vector.

### 3.4 A3 — memory-off freeze (`91c3bfc`) — a defect the audit MISSED

The audit claimed "memory-disable does NOT purge stored facts" (CONFIRMED) and "only «Забыть всё»
wipes" (**REFUTED**). Investigating found a real bug: with the master switch OFF, the app correctly
stops *learning* (prompt reads empty, tools `Disabled`, ingest gated, drain loop idles) — but
`onMaintenance()` ran every step unconditionally, so facts were still decayed and archived, and
`SUPERSEDED` rows past the 90-day retention were **hard-deleted**. That contradicted the UI, which
scopes deletion explicitly to the manual wipe.

Fixed per owner decision ("freeze stored data while off"): every memory-mutating step routes
through `CognitiveCoordinator.memoryStep` and is skipped while the switch is off — facts, queued
rows, vectors and entities stay exactly as they were. Behaviour-layer retention (command events,
behaviour log) and the `KEY_LAST_MAINTENANCE_AT` stamp still run. Both backfill entry points
enqueue nothing while off **without consuming the one-shot flag** (so backfill still works after
re-enabling). `MemoryFreezeMaintenanceTest` pins **both directions**; the two freeze tests were
confirmed to fail under the pre-fix behaviour (3 failures before any change; exactly 2 when the
guard was temporarily reverted).

### 3.5 A2 — memory subject contract (`734b4d6`)

`subject` was validated nowhere (unlike `predicate`, which has a 22-item whitelist), yet it is
stored and rendered verbatim into `<memory-context>` — a second-order injection carrier.
Per owner decision, `subject` is now a closed vocabulary (`user`) on **both** ingresses
(extraction parser drops-and-counts; `rememberFact` rejects rather than coerces). The extraction
prompt and the `RememberFactTool` schema were aligned (no free-text `subject`; named people become
a RELATION predicate with the name in `value`), and four drifted eval fixtures were re-encoded.
Eval: **precision 1.00 / recall 1.00**.

**Note:** the audit's `subject` finding was partly a *contract drift* discovery — the prompt
invited facts about "named people/things" while advertising exactly one subject.

### 3.6 A6 — `jarvis_07` medium/low fixes (`734b4d6`)

- One malformed extraction response no longer quarantines its **whole batch**: a bad row is
  attributed to its own `messageId` and only that row burns an attempt; a genuinely incoherent
  response still fails the batch together.
- An undecodable 2xx envelope from Yandex is surfaced as a **typed transport failure** (retry)
  instead of an empty string that looked like a parse failure.
- Yandex folder discovery gained its own bounded timeout and single-flight resolution.
- The cognitive queue no longer registers a duplicate settings watcher on restart.
- The audio frame buffer was raised to ~500 ms (capacity only; `DROP_OLDEST` unchanged).

### 3.7 A4 — tool timeouts: no change needed

The audit rated unbounded tool execution as a caveat. Verified it is **not** a real risk in the
current tree: the registry wraps every call in `withTimeout` (`ToolContract.kt:179`) and every
outbound call is cancellable and bounded — cancellable OkHttp `await()` (weather), MapKit
`invokeOnCancellation { session.cancel() }`, `withTimeoutOrNull` + `invokeOnCancellation {
disconnect() }` on the MediaBrowser connect. The theoretical "`withTimeout` is cooperative" caveat
has no instance in the tool lane, so adding a layer would be churn with no risk reduction.

### 3.8 S5 — documentation archaeology → stable contracts (`2b94e8d`)

Comments and KDoc across `app/src/main` cited planning documents that **no longer exist**
(`COGNITIVE_PLAN.md` / `REMEDIATION_PLAN.md` deleted; `PLAN-AEC-FOLLOWUP.md` /
`MAPKIT_CONTRACT.md` never committed; plus bare `§9.1` markers and audit codes `P1-C`, `P4.4`,
`M4/M5`, `F6`…). All were removed and the invariant each accompanied kept as a standalone
statement. **Verified mechanically:** 0 of 149 changed files differ in code once comments are
stripped (raw-string-aware Kotlin stripper), and the residual scan for `§` / `plan §N` / dangling
plan-file refs is 0 across `app/src/main`.

---

## 4. Decisions taken (with rationale)

| # | Decision | Rationale |
|---|---|---|
| DEC-1 | D1: reuse the existing `ExactAlarmPolicy` machinery, don't rebuild | it already existed and was correctly wired for the user-facing alert path; the service just never consulted it |
| DEC-2 | D2: bind confirmation to turn provenance, then harden to affirmative+TTL | hiding the token alone would leave the hole; the reviewer proved one-turn binding was insufficient |
| DEC-3 | **Do not** rewrite `SessionManager` into an actor model | `controlLock` + `sessionSeq` discipline is load-bearing (11 guarded blocks, supersede-first bumps, seq travelling on `TurnEvent`); a rewrite is high regression risk for a readability-only gain |
| DEC-4 | A1: derive `explicitUserCommand` from the utterance; delete dead proactive/scheduled branches | a constant made the boundary decorative; dead policy branches were false assurance |
| DEC-5 | A2: `subject` is `user`-only; named people via RELATION predicate | matches what the contract already advertised; keeps the prompt free of free-text subjects |
| DEC-6 | A3: **freeze** stored memory while off (do **not** purge on toggle-off) | purging on toggle would be a destructive surprise contradicting the UI; deletion belongs to the explicit wipe |
| DEC-7 | Do not "fix" the LFS claim | it is false at this HEAD; acting on it would have caused a regression |
| DEC-8 | Room destructive-migration policy: **no action** | owner policy is "no backward compat, pre-1.0"; the policy is deliberate and documented |

---

## 5. New artifacts produced

- **`THREAT_MODEL.md`** (new, linked from README/ARCHITECTURE/AGENTS) — the audit's "biggest
  missing artifact". Covers local attacker (filesystem/`adb`/backup/physical, including the honest
  note that a rooted attacker can *invoke* the Keystore though the key can't be exfiltrated),
  remote attacker (compromised LLM endpoint, prompt injection, malicious app, malicious tool
  content), privacy (a complete 13-row egress table), and authorization
  (implicit / spoken-confirmation / physical-interaction / never-allowed). Records accepted gaps
  and 5 tracked open items.
- **`tools/ToolAuthorization.kt`**, **`tools/ToolRisks.kt`**, **`tools/IrreversibleCommand.kt`** —
  the non-LLM authorization policy.
- **`cognitive/tools/ForgetConfirmation.kt`** — the RU/EN affirmative matcher.
- **Tests added:** `ToolAuthorizationTest`, `IrreversibleCommandTest`, `ForgetConfirmationTest`,
  `ForgetFactConfirmationTest`, `ExtractionParserSubjectTest`, `ExtractionQueueLoopTest`,
  `MemoryFreezeMaintenanceTest` (8), plus updates to `ServicePolicyTest`, `MemoryToolsTest`,
  `CognitiveFailureSanitizationTest`, `ExtractionQueueWorkerTest`, `AudioPipelineTest`,
  `YandexWireTest`, `ExtractionEvalTest`. Test count **1227 → 1295**.
- **`AGENTS.md`** gained three invariants: the authorization boundary (and the warning that its
  `explicitUserCommand` must stay *derived*), the closed `subject` vocabulary, and the
  memory-freeze contract.

---

## 6. Remaining work — Wave 3 (structural refactors), NOT started

Deliberately not begun: highest regression risk in the plan, touches the load-bearing session
kernel, and there is no independent way to prove behaviour preservation beyond test-parity.

### S1 — `SessionManager` (`session/SessionManager.kt`, ~1013 lines)
Extract its 9 responsibility clusters as collaborators, **preserving `controlLock`, `sessionSeq`
and the `TurnEvent` provenance verbatim**:
1. machine-event seam (`applyMachineEvent`, `applyTurnEvent`)
2. provenance-guarded UI writes (`publishPartial`, `publishActivity`)
3. session lifecycle (`startSession`, `runSession`, `cancelAll`, `stopActiveTurn`)
4. errors/terminals (`reportFailure`, `finish`)
5. wake/stop lane (`startListening`, `handleStopPhrase`, `applyVoiceStopLane`)
6. follow-up window (`setFollowUpWindow`, `maybeOpenFollowUpWindow`, `startFollowUpCollector`,
   `closeFollowUpWindow`, `LEAD_IN_SLOTS`)
7. proactive mini-session (`speakProactively`, `runProactive`)
8. device/lifecycle intent (`setMuted`, `onPowerConnected`)
9. UI flows (`partialTranscript`, `turnActivity`, `followUpProgress`, `muted`)

Invariants that MUST NOT change: no suspension inside a `controlLock` block; all 4 seq bumps
happen **before** cancelling; the validating seq travels on `TurnEvent` (comparing it at apply
time would be tautological); error turns end via `reportFailure` only (never `finish` after it).

### S2 — `TurnRunner` (`session/TurnRunner.kt`, ~869 lines, 20 constructor params)
Group the params into cohesive objects (`SpeechDeps`, `LlmDeps`, `TurnCallbacks`). Same package as
S1 → sequence them in **one lane**, not parallel.

### S3 — `AppGraph` (`di/AppGraph.kt`, ~806 lines)
The file is "dependency wiring **plus application policy**". Extract sub-factories:
`LlmFactory`, `SpeechFactory`, `AudioFactory`, `ToolFactory`, `MemoryFactory` (+ wake-word and
voice-probe). Preserve the exhaustive `when` guards (provider type, speech backend) — they are
compile-time safety for adding a new provider.

### S4 — `JarvisForegroundService` (`service/JarvisForegroundService.kt`, ~1117 lines)
Extract `AssistantRuntime` + `WatchdogScheduler` + `NotificationFactory` + `MediaDucker` +
`PowerLocks` + `MaintenanceScheduler` + `BinderApi`, leaving the Android component as an adapter.
Sequence **after** D1 (already landed) since the watchdog extraction hosts that fix.

### Suggested execution
One lane at a time, each landing behind the full gate with test-parity as the safety net
(count must not drop; detekt 0). Order: S2+S1 → S4 → S3. Do **not** parallelise across a shared
file, and do not parallelise S1 and S2 (same package, interdependent).

**Still open from the audit, if wanted:** `05 §17` (MapKit strategic coupling — opinion),
`05 §25` (defensive programming — opinion), and the threat model's own 5 open items
(turn-granular authorization; `remember_fact` persistence vector; log retention;
the inert notification listener; DB encryption).

---

## 7. Environment, tooling and gotchas for whoever continues

### 7.1 A foreign commit is in this history
`3e0ec25` "feat: overhaul UI ergonomics and visual language per design audit" is authored by
**"Jarvis Review <review@jarvis.local>"** — which is also *this machine's* default git identity,
so it came from another agent session here, not a third party. It was reviewed statically and
gated green (**1227 tests / detekt 0**) and accepted as sound:
- `StatusContrastTest` was **NOT weakened** — the only hunk is an appended `ColorPair`; thresholds
  unchanged; contrast recomputed and passing.
- One real functional defect was found and fixed in `b11c9c4` (the transcript empty-state never
  revealed, because `ListAdapter`/`AsyncListDiffer` only dispatches per-range callbacks).

### 7.2 JDK — pin or the build breaks at configuration
The shell's default `JAVA_HOME` is **Java 27**, on which Gradle's embedded Kotlin dies with
`IllegalArgumentException: 27` at configuration. `mise.toml` now pins `java = "temurin-17"`, but
the reliable invocation is explicit:
```
JAVA_HOME=/home/roger/.local/share/mise/installs/java/temurin-17 ./gradlew ...
```

### 7.3 Gates and their cost
- Full gate: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:detekt --rerun-tasks`
  (~2.5–4 min; the first build after a shell reset is cold).
- `--rerun-tasks` when UP-TO-DATE looks wrong. detekt is mandatory — CI's `static-analysis` job
  fails on it, and a targeted-test-only workflow let 8 findings accumulate across four lanes
  (they were then cleared mechanically without version changes).
- Real-time-budgeted tests exist (bounded waits, e.g. the wedged-engine release ~2.5 s) — do not
  "optimise" them into yield assertions.

### 7.4 Verification methodology that paid off (repeat it)
1. **Verify audit claims against source; never adopt a report wholesale.** One claim was false,
   one refuted, several inflated, and one real bug existed that no report mentioned.
2. **Independently re-verify lanes' work with a different mechanism than the lane's own check.**
   Lane self-reports passed in every case where an independent check later found a gap:
   `ora-2` (decorative boundary), the residual `§` citations, and a false positive in a
   hand-written comment stripper.
3. **Make a fix's regression test fail under the pre-fix behaviour** — a test that cannot fail is
   worthless. (The A3 lane confirmed 3 failures pre-fix, then exactly 2 when the guard was reverted.)
4. **Beware tool-output mangling** in this environment: literal tokens like `Authorization`,
   `Room`, `[OI]`, `override val name` can render wrong. Use `read`/`python3` for symbol checks.

### 7.5 Specialist agents
- `oracle` is **repaired and working** (root cause: the oh-my-opencode-slim preset omitted
  `model`, so it fell back to a bundled default on a provider that isn't configured; pinned to
  `PlusVibeAPI/deepseek-v4.1-flash:cxbc` in
  `~/.config/opencode/oh-my-opencode-slim.json`). It was used successfully for the two adversarial
  reviews that mattered. **Reach for it before committing any security-sensitive design.**
- Adversarial review of the highest-risk designs is what caught the two most important problems in
  this session. Budget for it.

---

## 8. Quick resume checklist

```bash
cd /home/roger/src/jarvis
git log --oneline -1                 # expect 91c3bfc, in sync with origin/main
git status --porcelain               # expect only .ignore/.opencode-trace/.opencode
JAVA_HOME=/home/roger/.local/share/mise/installs/java/temurin-17 \
  ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:detekt
# expect: BUILD SUCCESSFUL, 1295 tests / 0 failures, detekt 0
```

To continue: pick a Wave 3 item from §6, run it as a single lane behind the full gate, and have
`oracle` adversarially review the result before committing (especially S1/S4, which touch the
session kernel).
