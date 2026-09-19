# Jarvis Remediation Plan — 0.2.2 audit closure

Status: **APPROVED FOR EXECUTION.** Decisions D1–D9 accepted as recommended
(2026-09-19, §1).

## Progress log (updated as lanes land)

| Item | State | Evidence |
|---|---|---|
| Phase 1 privacy/logging | **DONE** | 3 leaks demoted; new tests proven to fail on pre-fix code |
| Phase 2 schema v2 | **DONE** | `2.json` exported; `1.json` untouched; FK enforcement confirmed in generated `AppDatabase_Impl`; 0 composite-PK call-site fallout |
| Phase 3 exact-alarm policy | **DONE** | `AlertDeliveryPlan` + `OverdueAlertPolicy`; `setAlarmClock` now degrades on `SecurityException` instead of crashing |
| Phase 3 ring lifecycle | **DONE** | Receiver owns terminal transition; fire-identity guard; `jarvis_alarm_v2` channel (attributes are immutable, so the old id would have stayed soundless); token-guarded activity |
| Phase 3 clock domains | **DONE** | Timers arm on `ELAPSED_REALTIME_WAKEUP`; boot re-anchors from wall clock (the elapsed clock resets at reboot) |
| Phase 3 permission reconciler | **DONE** | 3 hooks: permission broadcast + `MainActivity.onStart` + boot |
| Phase 3 DeviceTools | **DONE** | `DeviceToolOutcome`; brightness/volume report honestly; DND panel deep-link at SDK 35+ |
| Phase 3 detekt regression | **DONE** | 9 findings fixed; detekt clean |
| Phase 4 decay idempotency (F8) | **DONE** | Immutable anchors; old code yielded `0.99^15` where correct is `0.99^5` (proof captured) |
| Phase 4 remaining | **DONE** | F9 release-on-failure (unexpected exception now `markTransportFailure` + honest report, startup sweep untouched), F4 retry storm (`repeat`+`return@repeat` re-summarized the same span up to 6×; now `for`+`break`, test asserts `llm.calls == 1`), F7 `degradedCounter` → `AtomicLong` — then F3/F2, F5/F6, F11, D8, F1 and the two seams, all in the rows below |
| Phase 5 A1 + A3 | **DONE** | A1: `AudioPipeline` now level-triggered (`wantRunning` desired vs `running` actual); the slow native `AudioRecord` open runs on the producer, never the caller — the 3 main-thread paths (watchdog revive, `setMuted`, `onPowerConnected`) can no longer ANR. A3: new `sourceOpen` independent of `running` + single `closeSourceLocked()` used by every close site, so `stop()` after a give-up releases the mic (it previously early-returned on `!running` and held it open). Publish re-reads `wantRunning` under the monitor, closing the mic-open-while-muted window. `isRunning()` is now eventually-consistent (5 assertions converted to await). 887 tests |
| Phase 5 A2 | **DONE** | `applyMachineEvent` now takes `controlLock` itself (`synchronized` body), so the seq read + state read + `stateMachine.onEvent` are ONE unit — previously the comment claimed atomicity it did not have. Deadlock-checked: `controlLock → SessionStateMachine.lock` is one-way into a leaf lock (`onEvent` never calls back); `Any()` monitors are reentrant so the callers that already hold `controlLock` are unaffected. 887 tests |
| Detekt regression sweep | **DONE** | 7 findings from the recent lanes (they ran only the assemble+test gate, not detekt — CI is blocked on detekt, so **every lane must run `:app:detekt` too**). Fixed: `runProducer` complexity 23 → extracted `reconcileDesire()`; `Summarizer` `for`+3×`break` → extracted `summarizeNextBacklogSpan()` + `while`; `ExtractionQueueWorker` swallowed exception now logged with `e`; test `RuntimeException` → `SimulatedSerializationFailure`; dead `UserFactDao.upsertAll` deleted (missed by the Phase 2 sweep) + unused `Transaction` import. Detekt clean |
| Phase 4 D8 + F1 | **DONE** | **D8:** `PromptComposer.COGNITIVE_BUDGET = 1200` now caps memory+summary COMBINED — previously the documented "hard 1200 budget" was unenforceable (memory ≤1200 + summary ≤600 ≈ 1800); the summary yields to memory and loses whole trailing lines (`truncateByLines`), and a test pins the composer cap == `MemorySectionRenderer.SECTION_BUDGET` so they cannot drift. The false KDoc claiming the coordinator enforced the combined budget was corrected. Also folded the duplicated `renderMemoryBlock`/`renderSummaryBlock` into one `renderSection(label, gather)` (preserves both log strings). **F1:** `MessageDao.inRange(fromInclusive → afterId)` + both test fakes; KDoc now states the bounds are exactly `(afterId, toInclusive]` (the "off-by-one" was a naming lie, never a bug). +4 tests. **891 tests** |
| Phase 4 F3 + F2 | **DONE** | **F3:** `HabitDetector` write passes are now `ruleWriteMutex`-guarded — `promoteProbationRules`/`unmuteExpired` were previously UNLOCKED read-modify-write cycles racing the coordinator's reject/accept ladder (a concurrent reject could be clobbered, and `unmuteExpired` could silently un-mute a rule the user had just muted). Public wrappers + private `*Locked` bodies + new `nightly()` that takes the mutex ONCE (kotlinx `Mutex` is not reentrant, so `nightly` calls the `*Locked` bodies and `onMaintenance` now runs the whole habit pass as one atomic `maintenanceStep`). **F2:** promotion now requires `rejectCount == 0` — previously a rejected suggestion got promoted the first time the user merely ignored it (aged out clean), restarting the mute ladder from a blessed state. +2 tests. **893 tests** |
| Phase 4 F5 + F6 | **DONE** | **F5:** the recorded entitlement stamps were written by the benchmark and NEVER read — `resolveActiveEngine` used `cloudUsable = cloudEmbedder != null`, so a DENIED account still routed fact values to GigaChat via both the explicit `CLOUD` selector and an AUTO cloud winner. New pure `CloudEntitlement` (`isUsable` read side, `nextStamps` write side) is now the single source of truth, and the two `memory_meta` keys are MUTUALLY EXCLUSIVE — a verdict replaces the other stamp (`MemoryMetaDao.delete` added; Ok clears unavailability, Denied clears entitlement, Transient writes nothing so a network hiccup cannot erase a real verdict). **F6:** `GigaChatEmbedder.checkEntitlement()` now provably always returns a verdict: a malformed-but-200 reply trips `require(...)`/`NumberFormatException` (NOT `IOException`), which used to escape the probe and abort the Settings benchmark with a crash; now mapped to `Transient(-1)` with `CancellationException` still rethrown first. Also collapsed the duplicated meta read into `readMeta`. +7 tests. **900 tests** |
| Phase 4 F11 (hot path) | **DONE** | Gather's CPU phases (active-fact filter/snapshot, `FactRanker.topFacts` + lexical boost sort, relation boost sort, RRF fusion, byte→float decode + `topK`, section render) now run on an injectable `CognitiveDeps.cpuDispatcher` (default `Dispatchers.Default`) via `onCpu { … }` instead of the caller's session `Dispatchers.IO` lane — no IO worker is burned and network IO is not contended. New phase budget (`GATHER_OPTIONAL_PHASE_BUDGET_MS = 20`): once the mandatory lexical rank has spent it, the two OPTIONAL boost lanes (relation vocabulary, local vector channel) are dropped and the block still renders, instead of the outer 40 ms `withTimeout` discarding all memory (that path now degrades honestly rather than to ""). Added a content-free duration log (`Timber.d` — ms + active/pool/emitted COUNTS only). Corrected the false "hides inside LLM TTFT" claim in 4 places (`TurnRunner` ×2, `PromptContext`, the `GATHER_BUDGET_MS` KDoc, `applyVectorChannel`) — the gather overlaps PRE-LLM prompt assembly; TTFT begins once the request is on the wire. Test note: `MemoryToolsTest`/`SemanticRecallTest` pin `cpuDispatcher = Dispatchers.Unconfined` (same precedent as `engineBuildDispatcher`) so the virtual/real-time 40 ms window cannot race a thread hop. +2 tests (`GatherHotPathTest`: the hop is dispatched; a spent budget skips the vector lane but keeps the block — with a control proving the lane otherwise runs). **902 tests** |
| Phase 4 two seams (queue loop + recall) | **DONE** | The two approved extractions, and the **only** two: `extract/ExtractionQueueLoop` (`start()`/`wake()`, coalescing 1-slot `Channel`, `IDLE_WAIT_MS`/`IDLE_FLUSH_MS`, crash-recovery sweep, CE-rethrow-first discipline) and `recall/RecallPipeline` (gather + budgets + phase degradation + engine resolution + `rankedForQuery`/`writeBehindRecallStats`; constructed from `CognitiveDeps` + `scope` + the coordinator's `onDegraded` hook so the observable counter stays the coordinator's). The settings-watch `combine(memory, autoExtract, cloud, sensitive, embedder)` is built in the coordinator and injected as a plain `Flow<Unit>`, so the loop knows nothing about settings. Pure code motion: no behavior, budget or byte-output change; `GATHER_OPTIONAL_PHASE_BUDGET_MS`/`GATHER_POOL`/`SPREAD_POOL`/`RECALL_LIMIT`/`IDLE_*` moved to their owners' companions, `GATHER_BUDGET_MS` stays on the coordinator (still used by `gatherSummary`); 13 now-unused coordinator imports dropped. No test referenced any moved member. `CognitiveCoordinator` 1487 → ~1050 lines. **902 tests, detekt clean** |
| Phase 4 | **COMPLETE** | Exit criterion met: `MaintenanceDecayAnchorTest` drives the real `onMaintenance` → `decayInactiveFacts` across 5 consecutive nightly passes and asserts `c = c0·0.99^n` (the pre-fix triangular `0.99^15` is named in the assertion message); plus the unanchored-row latch case. All of F1–F11, D8, and both seams green |
| Phase 5 A5 + A6 | **DONE** | **A5:** the Porcupine stop lane was armed ONLY from the `stateMachine.state` collector, so flipping `voiceStopEnabled` while THINKING/SPEAKING left the lane stale until the next state change — enabling voice stop mid-turn silently did nothing. Extracted `applyVoiceStopLane(state)` (the collector's whole body), added public `reapplyVoiceStopLane()` for out-of-band callers, and wired `SettingsActivity.onVoiceStopToggled` to call it on Main BEFORE the async `reconfigureWakeWord()` (whose rebuild tail re-arms from the flag this sets). No detector-side change needed (`setStopLaneEnabled`/`armStopLaneIfNeeded` were already correct; `handleStopPhrase` re-checks the live pref, so the disabled→stale-armed direction was only wasted frames). **A6:** the `Effect` vocabulary is now consumed rather than discarded — `onTurnEnded(): Effect.OpenWindow?`, `onVadActive(): Effect.StartFollowUpTurn?`, `transition(): Effect.ExpireWindow?` are each typed to the single variant they can emit, deleting the dead "not emitted here" branch; the runtime `when` in the window collector now dispatches off `onVadActive()` and `transition()` verdicts instead of ignoring them. Also closed the gap the old code hid: `onVadActive()` now requires `nowMs() < deadlineMs`, so an onset landing in the poll gap after the deadline can no longer open a follow-up turn the countdown had already stopped advertising. +3 tests (2 A5 lane-arming via a recording `FakeWakeWord.stopLaneHistory`, 1 A6 deadline-onset). **905 tests, detekt clean** |
| Phase 6 (media/speech) | **DONE** | **M-3:** an ABSENT capability bit is now low confidence, not a refusal — `TransportControl.control` computes `lowConfidence = required.isNotEmpty() && caps.known && required.none { caps.supports(it) }` and still dispatches (the Boolean dispatch result IS the verification), phrasing the uncertainty («…но подтверждения нет: плеер не сообщал о поддержке этой команды»); `TransportPolicy.likeAllowed` fails OPEN on `!caps.known` so an unpublished PlaybackState no longer rejects LIKE pre-dispatch. **M-7:** `requiredAction(Action): TransportAction?` → `requiredActions(Action): Set<TransportAction>`; TOGGLE requires PLAY **or** PLAY_PAUSE (it dispatches play/pause by state), so compat-only players are no longer refused. **M-4:** `playOrResume()` returns the real dispatch Boolean — `Action.PLAY` no longer hardcodes `true`, so an IPC failure reports `dispatch_failed` instead of «Команда отправлена». **M-5:** `playLibraryItem` with a null `titleHint` (real caller: `MusicTools` `obj.string("title")`) now requires an actual command EFFECT (`awaitCommandEffect`) → `DISPATCHED`, else `APP_OPENED` — never `PLAYING` naming the stale track that was already playing. **M-6:** new pure `media.NotificationListenerComponent.matches(entry, pkg, cls)` tolerates BOTH flattened ComponentName forms (`pkg/full.Class` and `pkg/.Class`) — the old `flattenToString()` equals silently disabled the media lane on short-form ROMs. **M-8:** new `media.InstalledAppsCache` (`@Volatile` snapshot + DCL, 60 s TTL) wraps `AndroidMediaGateway.installedLaunchables` so the per-turn `getLaunchIntentForPackage` walk over every installed app is not repeated. **S-1:** `SberStreamingAsr.start()` now cancels its child `Context` and rethrows if stub construction throws — previously the context leaked (S-1's `closed` guard meant nobody else ever cancelled it) and the error was swallowed. **S-2:** new `speech/SaluteGrpc.kt` `bearerStub(channel, token, deadlineMs, newStub)` — one `AbstractStub`-bounded factory replaces the duplicated Metadata/interceptor wiring in the ASR (`grpc.recognition`) and TTS (`grpc.synthesis`) stubs; per-call deadlines stay at the call sites (60 s stream, 20 s sentence). **S-3:** a late non-EOU frame can no longer overwrite the replayed terminal event (`&& !terminalEmitted.get()`). **S-4:** dropped audio frames are counted (`AtomicLong`) with a bounded content-free DEBUG log (first + every 50th). +16 tests (`InstalledAppsCacheTest` call-count assertion = the plan's "enumeration cached" verification row; `NotificationListenerComponentTest` both flatten forms; TOGGLE/`lowConfidence`/`dispatch_failed`/stale-track/`hasScoreableExpectation`/inflection/S-3-late-partial). **921 tests, 0 failures, detekt clean** |
| Phase 7 slice 1 (pill + palette + contrast) | **DONE** | **N2 (truthful pill):** new pure JVM `ui/StateLabel.labelRes(state, muted, deaf, activity)` is the single source of truth (precedence: deaf → `state_wake_error_full`; muted → the previously-dead `state_muted`; THINKING+activity → `TurnActivityLabels`; `state == null` → `state_stopped` — the conservative non-lying choice, since carrying over the last live state is exactly how the pill lied when the service died mid-turn); `MainActivity.renderStatus()` has no early `return`s and always renders through it (the stale-pill mechanism), the stop button now clears state and re-renders instead of writing pill text directly, and the pill carries `accessibilityLiveRegion="polite"`. **U3/N8 (palette):** one `colors.xml` edit fixes orb + Settings status text together — day `idle #5F6967` / `listening #00796B` (4.10→5.06) / `thinking #8A5600` (4.03→5.85) / `speaking #006A8A`, night `speaking #8FD3FF`; all listed values reached AA on their real backgrounds with no correction needed. **`StatusContrastTest` (new, pure JVM):** parses BOTH `values/colors.xml` and `values-night/colors.xml`, asserts ≥ 4.5:1 for text pairs and ≥ 3:1 for orb/large-graphic pairs — N8 un-regressable. +11 tests (`StateLabelTest` truth table ×7, `StatusContrastTest` ×4). **932 tests, 0 failures, detekt clean** |
| Phase 7 slice 2 (motion) | **DONE** | **U4/U8:** new `ui/Motion.kt` — pure `shouldAnimate(animatorsEnabled, durationScale)` (JVM-testable) + live cache fed by `areAnimatorsEnabled()` **and** a `Settings.Global.ANIMATOR_DURATION_SCALE` `ContentObserver` (start/stop from `MainActivity.onStart/onStop`; listeners unregistered in `VoiceOrbView.onDetachedFromWindow`); four tokens `TRANSCRIPT_INSERT_MS` / `PILL_CROSSFADE_MS` / `RINGING_PULSE_MS` / `WINDOW_ENTER_MS`. Wired: **orb** (`VoiceOrbView.restartAnimators` bails to `applyStaticFrame()` — pinned per-state phases, re-evaluated live via the listener); **transcript** (`scrollToPosition` instead of `smoothScrollToPosition` + `itemAnimator = null` under reduced motion, `addDuration = TRANSCRIPT_INSERT_MS` otherwise); **pill** (crossfade in `MainActivity.renderStatus`, immediate write under reduced motion). FOLLOW_UP countdown arc still renders accurately — it is drawn from `followUpProgress`, not the frozen ripple phase. `RINGING_PULSE_MS`/`WINDOW_ENTER_MS` are contract-only: the ringing screen is already static and no themed window animation exists, so nothing was fabricated to consume them. New `MotionTest` ×6 (incl. a regression guard that the pure policy stays reachable from plain JVM tests — the object initializer must not touch framework statics). **938 tests, 0 failures, detekt clean** |
| Phase 7 slice 3 (shape channel + a11y focus) | **DONE** | **U12:** new `ui/OrbShape.kt` — `OrbState` promoted to a top-level enum and `OrbShape.of(state, muted, deaf)` extracted out of `VoiceOrbView` as pure JVM logic; MUTED now draws a ring + diagonal slash and DEAF a ring + 12-o'clock radial tick (the two "not hearing" states must not be told apart by hue alone), and the FOLLOW_UP countdown arc is floored at `MIN_FOLLOW_UP_ARC_DEGREES = 12f` so FOLLOW_UP never loses its shape signal — the arc is information and still renders at its true sweep above the floor. **U6:** `android:accessibilityHeading="true"` on all 10 `TextAppearance.Jarvis.SectionHeader` headings; `isFocusable = true` on the programmatic onboarding action TextView (a click listener alone left the only unblocking affordance out of D-pad/keyboard focus order); 6 `contentDescription`s on the behaviour stepper buttons naming the controlled value (+6 keys in BOTH locales). New `OrbShapeTest` ×6 (`ResourceParityTest` green). **944 tests, 0 failures, detekt clean** |
| Phase 7 slice 4 (dynamic type + touch targets) | **DONE** | **N9/U7:** new `values/dimens.xml` (`touch_target_min 48dp`, `button_min_height 56dp`, `home_column_width 2000dp`) — all 12 `44dp` icon-button sites across 5 layouts now use `@dimen/touch_target_min` (zero `44dp` left). **U5:** `values-sw600dp/dimens.xml` caps the reading column at 840dp, so `capColumnWidthOnTablets()` + its two `TABLET_*` consts + the `DisplayMetrics` import are DELETED (behaviour preserved: the cap clamps to the parent below 840dp, which is what the old `> 900dp` check produced); the three `?attr/actionBarSize` headers became `wrap_content` + `minHeight` so a large font scale can grow them instead of clipping; the ringing time is now a `MaterialTextView` with `autoSizeTextType="uniform"` (36–60sp) because a fixed 60sp overflows at fontScale 2.0 and the time is the entire message; onboarding/alarms/ringing buttons became `wrap_content` + `minHeight`. Layout-only slice — no new JVM tests. **944 tests, 0 failures, detekt clean** |
| Phase 7 slice 6 (inline field errors U7) | **DONE** | Validation errors were delivered by Toast — the wrong surface for a problem with a specific input: it names no field, vanishes on its own, and is not read in the context of the field that caused it. New pure-JVM `ui/FieldValidation.kt` decides WHAT is wrong and WHICH field owns it (`Field` enum, `FieldError(field, messageRes)`); new `ui/FieldErrorRenderer.kt` clears every wrapper then attaches each message to its own `TextInputLayout` (clearing first is what makes a corrected save stop showing the stale message). **Base URL / API key:** the `saveLlmProviderSettings` Toast became an inline error, and the API key is now actually enforced — `error_api_key` existed but was DEAD (an endpoint with no key failed only at the first turn). **OAuth pairs:** a HALF-filled Salute/GigaChat pair can never authenticate, so the error lands on the missing half; a FULLY-empty pair is "not configured yet", not an error (saving stays local-first). Success stays a Toast — it has no field to attach to. `activity_settings.xml` gained ids on the 8 `TextInputLayout` wrappers (none had one), +2 string keys in BOTH locales, new `FieldValidationTest` ×10. **Flake fixed (found by this slice's gate):** `MemoryToolsTest` "gather respects the disabled switch" failed ~1-in-3 FULL-SUITE runs while passing 6/6 in isolation. Root cause: `CognitiveCoordinator.scope` was hardcoded to `Dispatchers.IO`, ignoring the injected `parentScope` — so `RecallPipeline.writeBehindRecallStats`'s fire-and-forget `recordRecalls` ran on a real pool thread, captured the row via `byFactId`, and re-inserted it via `update()` AFTER the test's `rows.clear()`, making the next `gather` see a non-empty DB. Fixed at the root, following the existing `cpuDispatcher` precedent: the dispatcher is now injectable (`CognitiveDeps.cognitiveDispatcher`, production default unchanged at `Dispatchers.IO`) and the test pins it to `Dispatchers.Unconfined`. Verified 5/5 clean full-suite reruns after the fix. **954 tests, 0 failures, 6 skipped, detekt clean** |
| Phase 7 slice 5 (Settings decomposition U1) | **DONE** | **Increment 1:** new `Widget.Jarvis.SettingsCard` style — the five identical appearance attributes on all 9 cards defined once; 36 copy-pasted lines removed (1154→1127). `parent=""` is load-bearing: AAPT reads dots in a style name as implicit inheritance and otherwise demands a non-existent `Widget.Jarvis` style. `layout_width`/`layout_height` stay explicit at each call site — a zero-sized card is not a risk worth four saved lines — and per-card margins genuinely differ. **Increment 2:** new pure-JVM `ui/SettingsMapping.kt` owns the view↔preference encodings: `Player` enum with `playerForPref`/`playerPrefFor` (accepts BOTH `ru.yandex.music` and the legacy `com.yandex.music`, canonicalises on write), `nextEmbedder` (now case-insensitive and self-healing — an unknown value restarts at AUTO instead of silently sticking), `followUpSeconds`, `quietHour` (the `+23`/`%24` wraparound made explicit), `quota`, `selectedVoiceId`, `isSherpaEngine`; 12 call sites rewired and the dead `embedderOrder` local deleted. `SettingsMappingTest` ×19, incl. exhaustive loops over all 24 hours and the full quota range. **Increment 3:** `activity_settings.xml` 1127→123 lines — header + 9 `<include>`s; the cards live in `settings_card_*.xml` (46–189 lines each), sized on the `<include>` tag rather than relying on the include-override rule. **Guard:** new pure-JVM `SettingsLayoutTest` ×3 — asserts every `R.id.X` in `SettingsActivity.kt` is declared by the host or its include closure, that no id is declared twice, and that every card file is included exactly once with explicit sizing. It closes the real gap: `R.id` constants are generated from ALL layouts, so a dropped id still compiles and only NPEs at runtime. It earned its keep immediately — it caught the split emitting bare `layout="@layout/…"` instead of `android:layout="@layout/…"`, nine includes that built cleanly and would have rendered an EMPTY settings screen on device. **Increment 4:** `onCreate` decomposed from one ~630-line method into a `setupXCard()` per card (LLM provider, music, AEC, follow-up, memory, behaviour, semantic recall, voice, credentials, wake word) — a **pure statement move**, proven by diffing the multiset of code lines before/after (0 lost; only declarations, call sites and braces added). Set-state-before-attach is preserved inside each method, which is what stops a programmatic `check()`/`isChecked =` from being read as a user action (that is what would stop a live capture lane or rebuild the wake engine). Three moves needed a reordering argument and each was checked against every read site by grep: the credential pre-fill, the wake-word/sensitivity initialisation, and the close-button wiring — nothing reads those views between the old and new positions. `setupSemanticRecallCard` needed a further split (`observeSemanticStatus` + the two vector actions) because as one method it hit CyclomaticComplexity 22 > 20. **Deliberate deviation (recorded):** the `SettingsSection` base + one class per card was NOT done. @oracle reviewed and advised against it: the screen has zero behavioural coverage (no JVM test, no instrumentation test — `androidTest` is nightly-only and has no settings test), so a missed `render()` or unbound listener is invisible to every gate and surfaces only on a device the owner is not running; the extraction would also have silently gutted `SettingsLayoutTest`, which parses only `SettingsActivity.kt`; and there is no Robolectric in the catalog to build a real harness. The plan's own escape hatch ("helpers + includes suffice") covers the nine-method form, which banks ~90% of the readability win at provably zero behavioural risk. Revisit only alongside (a) `SettingsLayoutTest` widened to the section sources, (b) a registry↔layout parity assertion, (c) a nightly instrumentation smoke that launches the screen. **976 tests, 0 failures, 6 skipped, detekt clean** |
| Phase 8 (N4/N5/N6 + edge-to-edge) | **DONE** | **Recon result: three of the four items were already satisfied, and saying so is the deliverable.** **N4 (D6)** landed in Phase 3 — `DndPanelPolicy.usePanel(sdkInt)` (false at 34, true at 35+) with `SetDndTool` → `openDndPanel()` → `DeviceToolOutcome.PanelOpened` (`status=panel_opened`) and a direct apply below 35, pinned by `DndPanelPolicyTest`. **N5 is satisfied by construction** — `BootReceiver` only calls `rescheduleAllOnBoot()` (re-arm; dailies rolled forward, expired timers persisted DISABLED) and posts a "tap to activate" prompt, because since API 29 a background mic start would silence the assistant anyway; the ring path (`AlarmReceiver.onReceive` → `RingCoordinator.beginRing`) starts **no FGS at all**, so the D5 `specialUse` question is moot: `grep 'specialUse\|SPECIAL_USE'` over `app/src` is zero hits and the manifest has no `FOREGROUND_SERVICE_SPECIAL_USE` permission. **N6 is satisfied** — the ring requests no audio focus (`AlarmRinger` is a `MediaPlayer` with USAGE_ALARM); every focus call in the repo sits in the TTS lane inside the already-running FGS, and the FSI-denied fallback already exists (`jarvis_alarm_v2` channel with explicit alarm sound + vibration; the v2 id is load-bearing because channel attributes are immutable). **The real gap was edge-to-edge: zero inset handling anywhere** — a grep for `enableEdgeToEdge\|WindowCompat\|setDecorFitsSystemWindows\|WindowInsetsCompat\|fitsSystemWindows\|windowOptOutEdgeToEdgeEnforcement` across `app/src/main` matched only `themes.xml`. Adopted **unconditionally** (not version-gated, so one code path is exercised from API 29 to 36) via `androidx.core.view.WindowCompat`: `enableEdgeToEdge()` was unusable both because no `androidx.activity` version is pinned in the catalog and because `AlarmRingingActivity` is a plain `android.app.Activity`. New pure-JVM `ui/EdgeInsets.kt` (`Sides` + `EdgeInsetsPolicy.rootPadding`) composes **base XML padding + per-side `max(systemBars, cutout)`**, folding the IME into the bottom only when the caller opts in; new `ui/EdgeToEdge.kt` applies it (`enable` before `setContentView`, `pad` after, capturing the base padding once so rotation/keyboard re-dispatch recomposites idempotently) and takes the bar icon appearance from `R.bool.jarvis_light_bars`. Root ids added to all six layout roots and all six activities wired; `SettingsActivity` is the only `includeIme = true` screen (the only one with text fields) and gained `android:windowSoftInputMode="adjustResize"` — inert once the window draws edge-to-edge, but pinning it stops the system choosing `adjustPan` while the `ime()` insets do the real work. No status-bar visual change: `jarvis_surface == jarvis_background` in both themes, so the transparent bar shows exactly the old colour; the **accepted consequence** is the nav bar turning transparent/app-background instead of black on 3-button devices. New `EdgeInsetsPolicyTest` ×7 and `EdgeToEdgeWiringTest` ×2 (static guard over the sources: `enable` before `setContentView`, `pad` after it, and the padded id is that layout's ROOT id — the activities have no other coverage, and `R.id.*` is generated from every layout so a wrong id compiles and only breaks on a device). **985 tests, 0 failures, 6 skipped, detekt clean.** Exit criterion's manual device pass (boot→ring, DND tool, insets) remains **owner-only and unperformed**, as agreed |
| Phase 9 step 1 (targetSdk 34 → 35, D9) | **DONE** | `compileSdk`/`targetSdk` 34 → 35 in `app/build.gradle.kts`. Gate green; `aapt dump badging` → `targetSdkVersion:'35'`. Commit `e732e4a` |
| Phase 9 step 2 (targetSdk 35 → 36 + API-36 nullability) | **DONE** | `compileSdk`/`targetSdk` 35 → 36, and the now-dead `lint { disable += "ExpiredTargetSdkVersion" }` block DELETED (replaced by an explanatory comment — the suppression existed only because the app *was* behind target). One genuine API-36 source break: Android 16 annotates `MediaProjectionManager.getMediaProjection()` `@Nullable`, so a denied/expired consent returns null where the compiler previously saw a platform type; `audio/aec/PlaybackCaptureFarEndSource.kt` fed that straight into `AudioPlaybackCaptureConfiguration.Builder` → fixed with an explicit null check + honest content-free `AecDiag` log + return (the software-AEC far-end lane degrades, it does not crash). `aapt dump badging` → `targetSdkVersion:'36'`. Commit `c28840b` |
| Phase 9 step 3 (Porcupine 3.0.0 → 4.0.2, 16 KB) | **DONE** | `porcupine = "4.0.2"` in `gradle/libs.versions.toml`; the Java API compiles **unchanged** (4.x `Builder` is a superset of the seven members used — `setAccessKey`/`setKeywordPath`/`setKeyword(BuiltInKeyword.JARVIS)`/`setSensitivity`/`build`/`process`/`delete`, and `BuiltInKeyword.JARVIS` is present and NOT deprecated). Measured from the published AARs (`llvm-objdump -p` for LOAD, `llvm-readelf -Wl` for RELRO): **3.0.0 was misaligned on ALL FOUR ABIs** (LOAD `2**12`; RELRO rem `0x3000` arm64 / `0x2000` x86_64), so the bump was **mandatory, not optional**; **3.0.2, 3.0.3 and 4.0.2 all reach LOAD `2**14` + RELRO rem `0x0` on the 64-bit ABIs**. 4.0.2 chosen over the 3.x alternatives that also fix alignment because Porcupine Console now issues **v4-format** keywords only, so 4.x is the only major whose custom `.ppn` files can still be trained (see N11). 4.0.1 is **pom-only on Maven Central (no AAR)** — do not pin it. Not yet committed |
| Phase 9 16 KB verification (full documented check set) | **DONE (official gate) / OPEN (RELRO, N10)** | All three documented checks run, not just the script. **(1) LOAD — PASS:** every `arm64-v8a`/`x86_64` `.so` has `p_align = 2**14`, and every LOAD segment is congruent (`vaddr ≡ offset mod align`). **(2) zip — PASS:** `zipalign -c -P 16 -v 4` → "Verification succesful". **(3) Google's official `check_elf_alignment.sh` — PASS:** "ELF Verification Successful"; the only `UNALIGNED` entries are `armeabi-v7a`/`x86` `libpv_porcupine.so`, which the script itself excludes ("only arm64-v8a/x86_64 libs need to be aligned") — this is the authoritative confirmation that the requirement is **64-bit only**, not just a reading of the prose. **(4) RELRO — FAIL on the vendored prebuilts (N10):** the doc's second, *manual* check `(GNU_RELRO.VirtAddr + MemSiz) % 0x4000 == 0` fails on `libonnxruntime.so` and 3 of the 4 `libsherpa-onnx-*.so` on BOTH 64-bit ABIs, and `check_elf_alignment.sh` **cannot see it** (it inspects only the first LOAD segment). Porcupine 4.0.2 passes both 64-bit ABIs. See N10 for the owner decision |

**Known flake — FIXED.** `MemoryToolsTest.gather respects the disabled switch and the budget shape`
failed ~1-in-3 full-suite runs while passing 6/6 in isolation. Root cause: `CognitiveCoordinator.scope`
was hardcoded to `Dispatchers.IO`, ignoring the injected `parentScope`, so `RecallPipeline`'s
fire-and-forget `recordRecalls` could re-insert a row after the test cleared the DB. Fixed in
Phase 7 slice 6 by making the dispatcher injectable (`CognitiveDeps.cognitiveDispatcher`), with the
test pinning `Dispatchers.Unconfined`; verified by 5 consecutive clean full-suite runs.
Scope: every finding from the seven-lane audit plus the four design lanes.
Policy: **no backward compatibility.** Pre-1.0. Destructive Room migrations are
accepted and preferred over migration chains. Architectural refactors allowed
where they reduce real risk; cosmetic churn is forbidden.

Gate for every phase: `./gradlew :app:assembleDebug :app:testDebugUnitTest` **plus `./gradlew --no-daemon :app:detekt`** (CI is blocked on detekt; the assemble+test gate alone let 7 detekt findings accumulate across the Phase 3/4/5 lanes — see the Detekt regression sweep row below).
(847 tests green at plan time). CI additionally runs detekt + ktlint.

---

## 0. Design-lane corrections to the audit (read this first)

Four audit findings were **wrong**. Do not implement them as written.

| Original finding | Verdict | Reality |
|---|---|---|
| Cognitive cursor off-by-one (F1) | **FALSE POSITIVE — drop** | `MessageDao` is correctly exclusive (`id > :fromInclusive AND id <= :toInclusive`), matching its KDoc `(fromInclusive, toInclusive]`. `Summarizer.captureDoomed`, `runBacklogAndDigest` and `FakeMessageDao.inRange` all agree. Changing it to inclusive **introduces** duplicate summarization. Only defect: the parameter name lies — rename `fromInclusive` to `afterId`. |
| Alarms are exempt from exact-alarm permission; only timers degrade | **INVERTED** | `setAlarmClock` **requires** `SCHEDULE_EXACT_ALARM` at target 31+. Revoking the permission deletes alarms scheduled via `setExact`, `setExactAndAllowWhileIdle` **and** `setAlarmClock`. Both kinds degrade; revocation is a mass-deletion event. |
| First-use ONNX model load can stall the turn past 40 ms | **STALE** | The shipped local engine is `LexicalEmbedder` (pure-CPU signed hashing). No ONNX/JNI in `cognitive/`. The non-suspending-CPU concern stands; the model-load concern does not. |
| `renderEmbedderSelector` is a 284-LOC function | **WRONG** | It is 10 lines (481–491). The real problem is the flat **620-LOC `onCreate`**, which the audit already had right. |

Also corrected: the orb does **not** distinguish states mainly by color — shapes
already differ substantially (hollow ring / ripples / rotating arcs / core+halo).
The honest defects are narrower: FOLLOW_UP reuses LISTENING's silhouette, MUTED
and DEAF are both flat, and every shape cue collapses when motion is reduced.

**New findings surfaced by the design lanes** (not in the original audit):

- **N1 (High, alarms):** `setAlarmClock` requires `SCHEDULE_EXACT_ALARM`;
  revocation silently deletes all pending alarms. Reconcile-and-rearm is mandatory.
- **N2 (Medium, UI):** the status pill can **lie**. `MainActivity.renderStatus()`
  early-returns on `micMuted` without changing the text, so the only accessible
  carrier keeps showing «Слушаю…» while the orb shows MUTED. `state_muted` exists
  in both locales and is never referenced. Same for the graph-null branch.
- **N3 (Medium, data):** same schema version + changed schema is an
  `IllegalStateException` crash, not a wipe. A version bump is mandatory.
- **N4 (High, platform):** `SetDndTool` breaks at target 35 — apps can no longer
  change global DND state via `setInterruptionFilter`/`setNotificationPolicy`.
- **N5 (Medium, platform):** `BOOT_COMPLETED` may not start `mediaPlayback` or
  `microphone` FGS at target 35 — constrains how an overdue alarm rings at boot.
- **N6 (Medium, platform):** apps must be top-app or running an FGS to request
  audio focus at target 35 — the FSI-denied ring path may be silent.
- **N7 (Medium, privacy):** `memory.cloudEnabled=false` cannot purge CLOUD vectors
  because `FactVectorDao.deleteForEngine` has zero callers.
- **N8 (Low, UI):** two real WCAG 1.4.3 **text** failures the audit missed —
  `jarvis_status_listening` (4.10:1) and `jarvis_status_thinking` (4.03:1) are used
  as status text in Settings and need >= 4.5:1.
- **N9 (Low, UI):** icon buttons are 44dp, below the 48dp minimum touch target.
- **N10 (High, platform/packaging — OPEN, owner decision):** the 16 KB page-size
  work is **not** complete for the bundled native libraries. Google's
  `check_elf_alignment.sh` passes, but it inspects only the *first LOAD segment*
  (`objdump -p … | grep LOAD | awk '{print $NF}' | head -1`) and contains no RELRO
  check at all. The doc's separate manual check
  `(GNU_RELRO.VirtAddr + MemSiz) % 0x4000 == 0` **fails** on `libonnxruntime.so` and
  3 of the 4 `libsherpa-onnx-*.so` inside `app/libs/sherpa-onnx.aar` (v1.13.6) on
  **both** 64-bit ABIs (`arm64-v8a` rem `0x1000`/`0x3000`/`0x2000`, `x86_64` rem
  `0x3000`/`0x2000`/`0x1000`). The doc states such a library crashes with SIGSEGV on
  a 16 KB device, and because these libs are LOAD-`0x4000` the OS runs the app in
  **native** 16 KB mode rather than backcompat mode — exactly the condition under
  which the over-protection applies. Root cause is upstream, not local: sherpa-onnx
  PR #2520 and ONNX Runtime's CMake add only `-Wl,-z,max-page-size=16384` and never
  `-Wl,-z,common-page-size=16384`, so LOAD aligns but the RELRO end does not.
  Porcupine 4.0.2 is clean on both 64-bit ABIs. Play's *documented* gate
  (LOAD align + `zipalign -P 16`) still accepts the upload, so the exposure is a
  **runtime crash on 16 KB devices**, not upload rejection (enforcement date
  Feb 1 2027). Options: accept + document as a known limitation, rebuild the vendored
  libs with both linker flags (needs an NDK and a replacement LFS AAR), or upstream
  the missing flag. **Do not** count on version-swapping sherpa/ORT (measured: only
  isolated releases happen to line up; later ones regress).
- **N11 (Low, docs/compat):** Porcupine 4.x binds keyword files to the SDK major
  version, so a `.ppn` trained for 3.x is rejected at runtime ("file belongs to a
  different version of the library"). Documented in `README.md` + `RUNBOOK.md`; the
  built-in `JARVIS` keyword is unaffected. Accepted consequence of the N-D9 bump
  under the pre-1.0 no-backward-compatibility policy.

---

## 1. Open decisions (blocking — resolve before Phase 1)

| # | Decision | Recommendation | Blocks |
|---|---|---|---|
| D1 | FK enforcement: declare + `PRAGMA foreign_keys` on, or declare-only? | Declare **and** enforce, but only on derived children (`fact_vectors`, `fact_entities`). Never on `extraction_queue.messageId` (must survive message pruning) or nullable provenance (`sourceMessageId`). | Phase 2 |
| D2 | Retention horizons for ARCHIVED / FORGOTTEN / QUARANTINED facts | 180d / 90d / 180d | Phase 2 |
| D3 | Exact-alarm permission: `SCHEDULE_EXACT_ALARM` (revocable) vs `USE_EXACT_ALARM` (Play-scrutinized, alarm-clock category) | `SCHEDULE_EXACT_ALARM` + honest degraded UX + reconcile-on-grant. Argue `USE_EXACT_ALARM` separately if Play classification allows. | Phase 3 |
| D4 | Overdue-at-boot grace threshold | 15 min | Phase 3 |
| D5 | Ring FGS type | `specialUse` (needs Play declaration + manifest property); `mediaPlayback` is blocked from BOOT (N5) | Phase 3 |
| D6 | DND at target 36 (N4): panel deep-link vs `AutomaticZenRule` | Deep-link + `status=panel_opened` | Phase 8 |
| D7 | Decay + recall: does recall re-anchor the baseline or only reset the clock? | Reset the **clock only**, not the baseline (matches existing KDoc) | Phase 4 |
| D8 | Prompt budget: enforce combined <= 1200, or reconcile plan to 1800? | Enforce <= 1200 combined in `PromptComposer` | Phase 4 |
| D9 | Play targetSdk path: 34→35→36 incremental, or jump? | 35 first (insets + opt-out still exists), verify, then 36 | Phase 9 |

**LOCKED 2026-09-19: D1–D9 all accepted as recommended.**

---

## 2. THE critical orchestration constraint

**Three independent lanes each require a Room schema change:**

- data lane → indices, composite PKs, FK annotations
- cognitive lane → decay anchor columns on `user_facts`
- alarms lane → `clockDomain` / `anchorElapsedMillis` / `armedElapsedMillis` on
  `scheduled_alerts`, plus a new `ring_sessions` table

If these land as three separate bumps you get three destructive wipes, three
conflicting `N.json` exports, and a broken migration test. **They must be ONE
coordinated v2 bump**, owned by a single lane, with every other schema-touching
change frozen until it merges.

This is the single highest-risk integration point in the whole plan, and it is
why Phase 2 precedes all feature work.

---

## 3. Phase plan

### Phase 0 — Guardrails (no product code)

- Write the decision record for §1 (D1–D9) under `docs/`.
- Fix the working-tree hygiene: `.opencode-trace/`, `mise.toml`, `.ignore` are
  untracked strays — either commit with intent or ignore them.
- Record the baseline: gate green, 847 tests, APK 161 MB.
- **Exit:** D1–D9 answered and recorded; baseline recorded.

### Phase 1 — Privacy + logging (independent, cheap, zero risk)

Fixes live violations of the project's own binding rule, including one the current
test suite has a blind spot for.

- `MusicPlaybackOrchestrator.kt:616` → demote the `Timber.w` of the voice query to
  `Timber.d`; keep a content-free WARN summary.
- `MediaDiagnostics.kt:74-80` → drop title/artist from the INFO dump; keep the
  capability mask.
- `CognitiveCoordinator.kt:862` → `Timber.d` for the habit fingerprint.
- **Extend `SpeechContentLoggingTest`** to cover all three paths — the blind spot is
  why they survived. Add a single-quoted-span case to `LogScrubber`, or explicitly
  document the exclusion and rely on the demotion.
- **Exit:** gate green; the new test cases fail against the old code (proves the
  test actually bites).

### Phase 2 — Schema v2 (SINGLE coordinated bump) — **critical path**

One lane owns every schema artifact. All three sub-changes land together.

**Data layer**

- `AppDatabase`: `version = 2`. Replace the chained
  `.fallbackToDestructiveMigration()` + `.fallbackToDestructiveMigrationOnDowngrade(true)`
  with one `.fallbackToDestructiveMigration(dropAllTables = true)` — verified to also
  cover downgrade; the boolean form drops via `sqlite_master` enumeration, whereas the
  deprecated no-arg form only declared entities. Add an `onOpen` FK-enable callback if
  D1 = enforce.
- `extraction_queue`: add index on `state` + `messageId`, and one on `batchId`
  (`pending()` is `WHERE state=PENDING ORDER BY messageId`; `releaseBatch` scans by
  batchId — the highest-value missing index in the DB).
- `user_facts`: drop the dead `category` index; replace the separate `status` and
  `updatedAt` indices with one composite `status` + `updatedAt` index; reorder
  `allFacts()` / `observeAll()` from `ORDER BY createdAt` to `ORDER BY factId`
  (UUIDv7, already time-ordered and indexed).
- `scheduled_alerts`: replace the `(kind, enabled, triggerAtMillis)` index with
  `(kind, triggerAtMillis)` plus `triggerAtMillis` alone — `enabled` in the middle
  breaks `alarmsLive()` ordering.
- `session_summaries`: replace the separate `kind` and `toAt` indices with a composite
  `kind` + `toAt`.
- `fact_vectors`: composite PK `(factId, engineId)` instead of the current PK that
  contradicts the per-engine KDoc; add `distinctEngineIds()` (needed by N7).
- Delete dead DAO methods: `MessageDao.trimToIds`, `ExtractionQueueDao.delete`,
  `EntityDao.deleteLinksByFactIds`.
- Fix the `ConversationManager.trim()` KDoc — the pair-preservation claim is false.
- Export `app/schemas/.../2.json`; update the `AppDatabase` KDoc so the schema-freeze
  promise moves from 1→2 to **2→3**.

**Cognitive layer (same bump)**

- `UserFactEntity` + `FactSnapshot`: add `decayAnchorConfidence: Float` and
  `decayAnchorAt: Long` (immutable affirmation anchors; consumed in Phase 4).

**Alarms layer (same bump)**

- `ScheduledAlertEntity`: add `clockDomain` (RTC vs ELAPSED), `anchorElapsedMillis`,
  `armedElapsedMillis`.
- New `ring_sessions` table (`alertId` PK, `token`, `startedAtElapsed`, `label`,
  `isTimer`, `firedTriggerMillis`) for durable, process-independent ring state.

**Exit:** gate green; `2.json` exported; migration/smoke tests open a v2 DB with
`emptyList()` migrations; `EXPLAIN QUERY PLAN` assertions (androidTest) prove no `SCAN`
and no `USE TEMP B-TREE FOR ORDER BY` on the hot queries.

### Phase 3 — Alarm / timer / ringing lifecycle (highest user-facing severity)

Three things make this a redesign rather than a patch: the terminal DB transition sits
in the wrong process lifetime, the notification is not a control surface, and the
permission model is inverted.

- **Move the terminal transition to the receiver.** `AlarmReceiver.onReceive` must
  `goAsync()` and run `RingCoordinator.beginRing(...)` on the app scope *before*
  posting/launching. This is what makes re-arm survive "activity never launched or was
  killed" (T4).
- **Fire-identity guard.** Widen `AlertDao.applyFired(id, firedTriggerMillis, now,
  nextDaily)`: for non-daily rows, disable **only if** `triggerAtMillis ==
  firedTriggerMillis`, otherwise NoOp. Kills the snooze race (T7) and the
  early-delivery drop (T10) by construction instead of by ordering luck.
- **`RingCoordinator` + `RingingNotifier` + `NotificationChannels`.** One notification
  builder; `contentIntent` → activity; **Dismiss** and **Snooze** broadcast actions
  (the orphaned `ACTION_DISMISS` / `ACTION_SNOOZE` constants finally get wired);
  `setDeleteIntent` → dismiss; `setAutoCancel(false)`; `setOngoing(false)`; explicit
  alarm sound/vibration/importance on the channel (T1, T14).
- **Guards (T2):** check `canUseFullScreenIntent()` and `areNotificationsEnabled()`,
  degrade honestly and deep-link; never silently pretend it worked.
- **`AlarmRingingActivity`:** drop `singleInstance` (or add `onNewIntent`), remove the
  duplicate notification post, make buttons token-based, and stop silent back-out.
- **Exact-alarm strategy (T5, N1):** grow `ExactAlarmPolicy` into a per-kind
  `AlertDeliveryPlan { AlarmClock | ExactAllowWhileIdle | Degraded(reason) }`; wrap
  `setAlarmClock` in `SecurityException` handling; the degraded path uses
  `setAndAllowWhileIdle` plus an honest, deep-linked user note; add
  **`AlertPermissionReconciler`** that re-arms on
  `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` **and** lazily on app-foreground
  and boot (revoke-broadcast delivery is unverified).
- **Clock domain (T6):** timers arm with `ELAPSED_REALTIME_WAKEUP`; alarms stay
  `RTC_WAKEUP`; reboot reconciliation re-arms timers from the elapsed anchor.
- **`OverdueAlertPolicy` (T13):** within grace → Ring; one-shot alarm beyond grace →
  RollForward; one-shot timer beyond grace → DisableWithNotice. Delete `BootReceiver`'s
  second `SystemAlertArmer` and route boot through the graph.
- **Cancel tools stop a live ring (T17).**
- **Split `AlarmScheduler.kt` (702 LOC)** into `tools/alarm/`: `AlarmTimes`,
  `ExactAlarmPolicy` (+ `OverdueAlertPolicy`, pure / no-Android), `AlertArmer`,
  `AlarmScheduler` (orchestration), `AlarmReceiver`, `RingingNotifier`,
  `RingCoordinator`, `AlertPermissionReconciler`.
- **`DeviceTools` (T8, T9, N4):** `setBrightness` verifies both `putInt` results;
  `setVolume` catches `SecurityException` under DND; `setDnd` becomes a panel deep-link
  at target 35+; introduce `DeviceToolOutcome`; new `tool_*` keys in **both** locales.

**Exit:** gate green + the new JVM tests; CHANGELOG entry.

### Phase 4 — Cognitive correctness

- **Decay idempotency (F8 — the biggest correctness bug).** Replace mutation of the
  persisted value with an immutable anchor:
  `lastTouched = max(decayAnchorAt, lastRecalledAt)`, then return
  `max(FLOOR, decayAnchorConfidence * 0.99^effectiveIdle)`. Output then depends only on
  immutable fields plus `now`, giving exactly `c_k = c0 * 0.99^k` instead of the current
  `0.99^(k(k+1)/2)` compounding-per-mutation bug. Wire the anchors through
  `FactNormalizer.snapshot`, `UserFactDao.confirmFact`, and `MemoryWriter`'s
  ConfirmExisting + working-set merge. Per D7, recall resets the clock only.
- **`ExtractionQueueWorker` release-on-failure (F9):** wrap the whole claimed region in
  `catch (CancellationException) { throw }` / `catch (Exception) { releaseBatch;
  backoff }`. `releaseBatch` already returns rows to PENDING preserving `attempt`, so
  `MAX_ATTEMPTS` bounds a poison batch. Keep the startup RUNNING sweep; do **not** add a
  concurrent periodic sweep — it could release an in-flight batch.
- **Habit concurrency (F3):** private `*Locked` bodies + public locked wrappers, plus a
  `nightly()` that takes `ruleWriteMutex` **once** (kotlinx `Mutex` is not reentrant).
- **Probation (F2):** require `rejectCount == 0` to promote.
- **Retry storm (F4):** replace `repeat` + `return@repeat` with `for` + `break`.
- **Cloud selector / entitlement (F5, F6):** compute `cloudUsable` from the recorded
  meta keys; make `checkEntitlement` return a verdict instead of throwing; clear
  entitlement on Denied.
- **Hot path (F11):** move CPU ranking to `Dispatchers.Default` with elapsed checks
  between gather phases; correct the false "hides inside TTFT" comment (it overlaps
  pre-LLM setup, not TTFT); emit a content-free duration log.
- **Prompt cap (D8):** enforce the combined budget in `PromptComposer`.
- **Atomic counters (F7):** `degradedCounter` → `AtomicLong`.
- Rename `fromInclusive` → `afterId` (F1 cosmetic; the semantics are already correct).
- **Extract exactly two seams:** `extract/ExtractionQueueLoop` and `recall/RecallPipeline`.
  **Do NOT decompose `CognitiveCoordinator` further** — the findings are local, and a
  big-bang split would break the supervised-scope / PrefsFlow / mutex contracts for no
  gain.

**Exit:** gate green + a multi-pass decay regression test that fails on the old code.

### Phase 5 — Session / audio / DI correctness

- **A1:** stop opening `AudioRecord` on the caller/binder/main thread — dispatch the
  start path to `Dispatchers.IO`/`Default` (mirrors the existing "never build the
  wake-word engine on main" rule; this is the same class of ANR).
- **A2:** wrap `applyMachineEvent` in `synchronized(controlLock)` so the seq guard is
  atomic with the transition, as the comment already claims.
- **A3:** track `sourceOpen` independently of `running` so `stop()` after give-up
  actually releases the mic.
- **A5:** re-apply the voice-stop lane on preference toggle, not only on state change.
- **A6:** either consume the follow-up `Effect` vocabulary or delete the unused variants.

**Exit:** gate green.

### Phase 6 — Media / speech

- **M-3:** treat an absent capability bit as low-confidence (dispatch + verify) instead
  of refusing outright — today an under-reporting player loses the whole feature.
- **M-4:** `Action.PLAY` must propagate the dispatch Boolean; it currently hardcodes
  `true`, so it reports success on IPC failure.
- **M-5:** when `titleHint` is absent, report DISPATCHED rather than «Включил: <old
  track>».
- **M-6:** tolerate both long and short listener-component forms.
- **M-7:** gate TOGGLE on `PLAY_PAUSE` as well as `PLAY`.
- **M-8:** cache installed-app enumeration (hundreds of PackageManager calls per turn).
- **S-1:** cancel `cancellableContext` if `start()` throws.
- **S-2:** extract one `bearerStub(channel, token, deadlineMs)` helper (ASR/TTS
  duplication already diverges on deadlines).
- **S-3:** gate the partial-result path on `terminalEmitted`.
- **S-4:** count/log dropped ASR frames content-free.
- **S-5:** ignore query tokens shorter than 3 chars and/or add stemming — fixes both the
  «группу Кино» false negative and the single-token false positive.

**Exit:** gate green.

### Phase 7 — UI / accessibility / Settings (@designer owns and implements)

- **Truthful pill (N2) — do first; cheapest high-impact fix.** Route every state through
  one pure `StateLabel.labelRes(state, muted, deaf, activity)`; remove the early returns;
  wire the never-used `state_muted`; add `accessibilityLiveRegion=polite`.
- **Palette (U3, N8):** day `status_idle #6F7977 → #5F6967`, `listening #00897B →
  #00796B`, `thinking #B26A00 → #8A5600`, `speaking #00A693 → #006A8A`; night
  `speaking #6FF0DE → #8FD3FF`. One palette edit fixes the orb and the Settings
  status-text contrast failures together.
- **`StatusContrastTest` (new, pure JVM):** parse both `colors.xml` files and assert
  >= 4.5:1. Makes the finding un-regressable.
- **Shape channel (U12):** MUTED = ring + diagonal slash; DEAF = ring + radial tick;
  FOLLOW_UP = always-drawn arc segment. Targeted, not a redesign.
- **Reduced motion (U4):** `ui/Motion.kt` using `ValueAnimator.areAnimatorsEnabled()`
  plus a `ContentObserver` on `ANIMATOR_DURATION_SCALE`; static representative frames
  when disabled; `smoothScrollToPosition` → `scrollToPosition`; the countdown arc always
  renders (it is information, not decoration).
- **Dynamic type (U5):** `dimens.xml` (+ `sw600dp`); fixed heights → `wrap_content` +
  `minHeight`; the header's `?attr/actionBarSize` clips a 24sp title at fontScale 2.0;
  ringing-screen time gets autosize; delete `capColumnWidthOnTablets()` runtime math.
- **Touch targets (U7, N9):** 44dp → 48dp.
- **Inline field errors (U7):** `FieldValidation` + `FieldErrorRenderer`; credential
  errors attach to the offending field; toasts become success-only.
- **Settings decomposition (U1):** `SettingsSection` base + one class per card (9 cards →
  9 classes, 60–160 LOC each); `activity_settings.xml` → header + 9 includes;
  `Widget.Jarvis.SettingsCard` / `SettingRow` styles replace the 9 duplicated blocks.
  Do **NOT** introduce Fragments/Navigation or a generic settings catalog, and do not
  build a `SettingRowView` ViewGroup yet — helpers + includes suffice.
- **Motion system (U8):** `ui/Motion.kt` tokens; exactly four transitions (window enter
  via theme, transcript insert, pill crossfade, ringing icon pulse); nothing else.
- **Focus (U6):** onboarding action TextViews `focusable`; `accessibilityHeading` on the
  9 section headers; contentDescriptions on stepper buttons. Default order is intentional.
- **Extract pure logic out of `VoiceOrbView`/Settings** so JVM tests can cover state →
  label, state → shape, color math, and validation without Robolectric.

**Exit:** gate green; new JVM tests for `StateLabel` + contrast; visual check on a real
device at fontScale 2.0 with animations off.

### Phase 8 — Platform behavior at target 35/36 (N4, N5, N6)

Do this immediately after Phase 3 lands the ring FGS type, because these are behavioral
changes the ring path depends on.

- **DND (N4, D6):** `setDnd` becomes a Settings-panel deep-link plus an honest
  `status=panel_opened` outcome; no more attempt to change global interruption filter.
- **Boot FGS (N5):** confirm the ring path never *starts* a `mediaPlayback`/`microphone`
  FGS from `BOOT_COMPLETED`; use the D5 `specialUse` type and an alarm-category
  notification path.
- **Audio focus (N6):** ensure the ring acquires focus only while an FGS is running;
  otherwise the FSI-denied path may be silent — add a detectable fallback (vibrate +
  notification) rather than assuming playback.
- **Edge-to-edge:** at target 36 the opt-out is removed; verify insets handling on the
  settings list, ringing screen, and transcript.
- **Exit:** gate green; manual device pass over boot-rings-alarm, DND tool, and insets.

> **Post-recon correction (Phase 8 as-built, do not re-litigate):** Phase 3 never built a
> ring FGS — the ring is a notification + full-screen intent +
> `AlarmRingingActivity`, so there is no "ring FGS type" to land and the D5 `specialUse`
> row is moot rather than satisfied. N4 was already implemented in Phase 3; N5 and N6 hold
> by construction (see the Phase 8 row in the progress table). The only work Phase 8
> actually owed was edge-to-edge, which is now unconditional across all six activities,
> not merely verified on the three screens named above. The bullet list is left as written
> for provenance; trust the progress-table row.

### Phase 9 — targetSdk 34 → 35 → 36 + Porcupine 16 KB (D9)

- Bump to 35 first (insets + opt-out still available), run the full gate plus a device
  pass, then bump to 36 and remove the `ExpiredTargetSdkVersion` suppression.
- Install Android SDK Platform 36 (AGP 8.11.1 already supports API 36 — no AGP bump).
- **Porcupine:** `libpv_porcupine.so` 3.0.0 is misaligned on **all four ABIs**
  (LOAD `0x1000`, not 16 KB compliant), so the bump was mandatory; 3.0.2, 3.0.3 and
  4.0.2 all reach LOAD `0x4000` **and** RELRO-aligned on the 64-bit ABIs. 4.0.2 chosen
  because Porcupine Console now issues v4-format keywords only (see N11); keep it in
  sync with the shipped AAR, never hand-edit the bundled keyword/params assets. Play
  blocks non-compliant updates from Feb 1 2027.
- Re-verify alignment with `llvm-objdump -p` for LOAD (`2**14`) **and**
  `llvm-readelf -Wl` for the RELRO formula — `readelf -l | grep LOAD` alone verifies
  the first requirement and hides the second.

> **Post-recon correction (Phase 9) — do not re-litigate.** The original Phase 9 text
> above claimed "Sherpa-ONNX and ONNX Runtime are already `0x4000`", and the exit
> criterion ("ELF check passes") inherited that blind spot. That claim was true for
> **LOAD alignment only**. The 16 KB requirement has a *second*, manual check that
> Google's `check_elf_alignment.sh` does not implement — the RELRO check
> `(GNU_RELRO.VirtAddr + MemSiz) % 0x4000 == 0` — and the vendored
> `app/libs/sherpa-onnx.aar` libraries fail it on both 64-bit ABIs (see **N10**).
> Separately, the requirement scope is **64-bit only** (`arm64-v8a`, `x86_64`) — this
> is not merely an inference from the prose: the doc's own script checks only 64-bit
> LOAD segments and prints "only arm64-v8a/x86_64 libs need to be aligned", so
> `armeabi-v7a`/`x86` misalignment is out of scope and no ABI filter needs adding.

- **Exit:** gate green; `aapt dump badging` shows targetSdk 36; **official** ELF check
  (`check_elf_alignment.sh` + `zipalign -P 16`) passes — all met. The RELRO check is a
  **separate open finding (N10)**, deliberately not treated as a Phase 9 exit gate
  because it needs an owner decision and an upstream/prebuilt fix.

### Phase 10 — Docs, dependencies, CI hardening (independent, parallelizable)

- **Dependency bumps:** protobuf-java 3.25.3 → 4.x or at minimum a patched 3.25.x
  (CVE-2024-7254), matching `protoc` version; review okhttp, core-ktx, and the rest of
  the catalog for stale pins.
- **CI:** pin GitHub Actions to commit SHAs instead of tags.
- **Doc drift:** `README.md:5,119`, `AGENTS.md:8,47`, `RUNBOOK.md:3`,
  `ARCHITECTURE.md:3,37,358,390,421,435`, `CHANGELOG.md` — align with the post-plan
  reality (schema v2, targetSdk 36, Porcupine 4.x, no security-crypto).
- **AGENTS.md** must record the new invariants: single coordinated schema bump,
  immutable decay anchors, ring state owned by the receiver, exact-alarm reconcile.

**Exit:** gate green; docs match code.

---

## 4. Verification matrix (what proves each risky phase)

| Phase | Evidence required beyond the gate |
|---|---|
| 1 | New `SpeechContentLoggingTest` cases fail on old code, pass on new |
| 2 | Exported `2.json`; v2 open with `emptyList()` migrations; `EXPLAIN QUERY PLAN` shows no SCAN / TEMP B-TREE on hot queries |
| 3 | JVM tests: fire-identity NoOp on stale `firedTriggerMillis`; reconcile re-arms after revoke; overdue policy table; capture/resume guard |
| 4 | Multi-pass decay test asserts `c_k = c0*0.99^k`; fails on old code. Poison-batch test asserts rows return to PENDING |
| 5 | Give-up → `stop()` releases mic; seq-guard atomicity test under interleaving |
| 6 | PLAY propagates false on dispatch failure; enumeration cached (call-count assertion) |
| 7 | `StateLabel` truth-table test; `StatusContrastTest` >= 4.5:1 |
| 9 | `aapt dump badging` targetSdk; `llvm-objdump -p` LOAD align `2**14` for every 64-bit `.so`; `zipalign -c -P 16`; `check_elf_alignment.sh` → ALIGNED; **plus the RELRO formula** `(GNU_RELRO.VirtAddr + MemSiz) % 0x4000 == 0` (the first three pass; RELRO is open as N10) |

Every "fails on old code" claim is mandatory — a regression test that passes before the
fix does not count.

---

## 5. Execution order and lane ownership

Recommended dispatch (respecting the single-writer rule per file and the frozen-schema
rule between Phase 2 lanes):

1. **Phase 1** — one lane, no schema, no UI. Safe to start immediately.
2. **Phase 2** — one lane only. All other lanes freeze schema-touching edits until it
   merges (this is the hard gate).
3. **Phase 3** — independent of 4/5/6 after Phase 2.
4. **Phase 4 / 5 / 6** — parallelizable once Phase 2 merges; they touch different
   files, except `CognitiveCoordinator` (Phase 4 owns) and `DeviceTools` (Phase 3 owns).
5. **Phase 7** — @designer, can start in parallel with 3–6 for the non-Android parts
   (palette, `StateLabel`, contrast test) because it touches only `ui/` + resources.
6. **Phase 8 / 9** — after Phase 3 and Phase 7 land.
7. **Phase 10** — anytime; purely additive.

Conflict notes to enforce during dispatch:

- `AppDatabase` and every entity file: Phase 2 only.
- `AlarmScheduler.kt` / `AlarmReceiver` / `AlarmRingingActivity`: Phase 3 only.
- `DeviceTools.kt`: Phase 3 owns brightness/volume/DND; Phase 6 must not touch it.
- `SessionManager.kt` / `AudioPipeline.kt`: Phase 5 only.
- `colors.xml` / `strings.xml` / `ui/*`: Phase 7 only; Phase 10's doc edits stay in
  `.md` files.
- Any new string key lands in **both** `values/` and `values-en/` in the same change or
  `ResourceParityTest` fails the suite.
