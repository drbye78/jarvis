# Jarvis Technical Debt & Bug Analysis Report

Generated: 2026-09-02

## TIER 1 — CRITICAL

### C1. `fallbackToDestructiveMigration()` — Silent Data Loss on Version Bump
- **File**: `app/src/main/java/com/jarvis/assistant/data/AppDatabase.kt:41`
- **Severity**: Critical
- **Impact**: ANY future `version = N` bump silently drops ALL tables (chat history + scheduled alarms/timers). Currently at version=2 — the first developer who bumps to 3 without realizing this ships a data-wiping update.
- **Root cause**: Intentional v1→v2 decision (documented in the class doc), but the mechanism is permanent and will fire on every subsequent bump. The `exportSchema = false` on line 22 means there's no schema history to build auto-migrations against.
- **Risk**: HIGH for the next version bump.
- **Recommendation**: **Fix before any version bump.** Implement `autoMigrations` or at minimum a `Migration(from, to)` that preserves data. If destructive migration is truly desired, add a compile-time reminder or rename the method to `dropAllOnUpgrade()` so it can't be mistaken for a default.

### C2. AppGraph Built on Main Thread — ANR Risk on Boot
- **File**: `app/src/main/java/com/jarvis/assistant/service/JarvisForegroundService.kt:133` + `app/src/main/java/com/jarvis/assistant/di/AppGraph.kt:54`
- **Severity**: Critical
- **Impact**: `ensureInitialized()` constructs the entire `AppGraph` on the main thread: database singleton init, OkHttpClient build, gRPC channel build, `HybridWakeWordDetector` init (which kicks off a native engine build), `AudioPipeline` start. On Kirin 710A-class devices (the stated target), this can exceed the ANR threshold (5 s for foreground service).
- **Root cause**: Manual DI with no Hilt/Dagger; `GraphHolder` pattern requires synchronous construction. The wake-word detector's async build (H1 fix) mitigates the worst case (Sherpa model loading) but AppGraph construction itself still does synchronous I/O (Room, OkHttp).
- **Risk**: HIGH on low-end devices. The watchdog retries, so it's recoverable, but the user experiences a silent 15-minute gap after boot before the assistant is operational.
- **Recommendation**: **Fix before release.** Move AppGraph construction to a background thread with a `Bootstrapping` state shown in the notification. Or make the construction lazy (build subsystems on first use).

---

## TIER 2 — HIGH

### H1. BluetoothAdapter.enable()/disable() Deprecated on API 33+
- **File**: `app/src/main/java/com/jarvis/assistant/tools/DeviceTools.kt:188`
- **Severity**: High
- **Impact**: `adapter.enable()` / `adapter.disable()` is a no-op on Android 13+ (API 33). On the current target (API 30 / Android 11), it works. But if `targetSdk` is ever raised or the app runs on Android 13+ devices (which it does — `compileSdk 34`), the call silently does nothing.
- **Root cause**: Android deprecated the programmatic Bluetooth toggle in favor of the Settings panel intent (`Settings.Panel.ACTION_BLUETOOTH`), same pattern as Wi-Fi on API 29+.
- **Risk**: MEDIUM. Currently masked by `targetSdk 30`, but any user on Android 13+ will silently fail.
- **Recommendation**: **Fix before release.** Add an API-level guard: on 33+, fall back to the Bluetooth settings panel (same pattern as `SetWifiTool`). The code already handles this for Wi-Fi at line 142–151.

### H2. `runBlocking` in HybridWakeWordDetector.release()
- **File**: `app/src/main/java/com/jarvis/assistant/audio/HybridWakeWordDetector.kt:299`
- **Severity**: High
- **Impact**: `release()` is called from `AppGraph.shutdown()` on `Dispatchers.IO`, but if ever called from the main thread (e.g., during a lifecycle teardown), the `runBlocking` blocks the main thread for up to 1 second (the bounded join timeout). This is an ANR trigger.
- **Root cause**: The native engine requires synchronous teardown (use-after-free prevention). The code correctly bounds the wait to 1 s, but `runBlocking` is inherently hostile to the calling thread.
- **Risk**: MEDIUM. Currently called from `AppGraph.shutdown()` which runs on `Dispatchers.IO`, but the service's `onDestroy()` chain could theoretically reach it from the main thread if the shutdown path is refactored.
- **Recommendation**: **Monitor.** The 1-second bound and IO-dispatched call site make this acceptable for now. If lifecycle integration changes, convert to `runBlocking(Dispatchers.IO + NonCancellable)` or make `release()` a suspend function.

### H3. `catch (e: Exception)` Blocks Hiding Real Bugs — High-Risk Subset
- **Severity**: High (aggregate)
- **Impact**: 25 `catch (e: Exception)` blocks + 7 `catch (_: Exception)` blocks + ~70 `runCatching` calls. Most are legitimate defensive boundaries. But several mask failures on **critical paths**:

| File | Line | What's Masked | Risk |
|------|------|---------------|------|
| `TurnRunner.kt` | 120 | Any exception in `runTurn` → generic "Попробуйте ещё раз" | HIGH: LLM timeout, ASR crash, or TTS failure all get the same message |
| `TurnRunner.kt` | 306 | LLM stream failure after tool pass | MEDIUM: loses tool context |
| `ToolContract.kt` | 95 | Tool execution failure | LOW: by design (tools must not crash the turn) |
| `AudioPipeline.kt` | 112 | Generic read errors → 100ms retry | MEDIUM: infinite retry loop on persistent hardware failure |
| `SherpaKwsEngine.kt` | 107,111 | Native release failures | LOW: by design (best-effort cleanup) |
| `SaluteSpeechTts.kt` | 99 | TTS synthesis failure | MEDIUM: sentence silently dropped |

- **Root cause**: Defensive coding pattern appropriate for an always-on appliance, but the blanket catch-all at `TurnRunner.kt:120` and `AudioPipeline.kt:112` could hide correlated failures.
- **Risk**: MEDIUM-HIGH. A network timeout masquerading as "Попробуйте ещё раз" is acceptable; an OOM masquerading as the same is not.
- **Recommendation**: **Triage the high-risk subset.** Add specific catches for `CancellationException` (already done correctly in most places), `TimeoutCancellationException`, `IOException`, and `OutOfMemoryError` before the generic `catch (e: Exception)` in `TurnRunner.kt:120`. The `AudioPipeline.kt:112` should track consecutive failures and surface `DetectorState.Failed` after N retries.

### H4. Zero Instrumentation Tests
- **File**: `app/src/androidTest/` — directory does not exist
- **Severity**: High
- **Impact**: No integration tests for: AudioRecord initialization on real hardware, gRPC/Sber connectivity, Room DAO round-trips on actual SQLite, MediaSession interaction, permission-gated paths (Bluetooth, brightness, DND). The unit tests are thorough (30+ test files), but they exercise JVM fakes.
- **Root cause**: Classic pre-1.0 tradeoff — unit tests were prioritized for speed. The `testInstrumentationRunner` is configured in `build.gradle.kts:30` but no test classes exist.
- **Risk**: MEDIUM. The HarmonyOS 2.0 target has specific quirks (e.g., aggressive power management killing the foreground service) that unit tests cannot catch.
- **Recommendation**: **Fix before release.** At minimum, add smoke tests for: service startup, wake-word engine init, ASR round-trip, and Room schema migration. The existing `testInstrumentationRunner` config makes this ready to go.

### H5. Sherpa-ONNX Native Crash Trap
- **File**: `app/src/main/java/com/jarvis/assistant/audio/SherpaKwsEngine.kt:44`
- **Severity**: High
- **Impact**: The bundled AAR (`sherpa-onnx.aar` v1.13.6, ~47 MB) only exposes the non-null `AssetManager` constructor. Passing an absolute `filesDir` path crashes natively (`AAssetManager_open` → `SHERPA_ONNX_EXIT`). There is NO recovery from this crash — it kills the process.
- **Root cause**: The AAR was built without the nullable-context constructor. The code documents this at `SherpaKwsEngine.kt:16-23` and `AppGraph.kt:182-185`, but there's no runtime guard — a future developer could add a "custom model directory" feature and unknowingly trigger a native crash.
- **Risk**: MEDIUM. The current code correctly passes `context` (assets), but there's no compile-time or runtime assertion preventing a future misuse.
- **Recommendation**: **Add a runtime assertion.** In `SherpaKwsEngine.init`, add `require(context != null) { "Sherpa-ONNX requires a non-null context for AssetManager" }` and a comment block that's impossible to miss. The AGENTS.md warning exists but is documentation-only.

---

## TIER 3 — MEDIUM

### M1. `DeviceTools.SetWifiTool` — Silent Failure Masked as Success
- **File**: `app/src/main/java/com/jarvis/assistant/tools/DeviceTools.kt:134-151`
- **Severity**: Medium
- **Impact**: When `setWifiEnabled` fails (API 29+ always returns false, or SecurityException), the code falls through to open the settings panel. But `startActivity(panel)` at line 146 is wrapped in `runCatching { ... }` with the result discarded — if the panel fails to open (e.g., no matching activity on a HarmonyOS device), the tool still returns `"status": "panel_opened"`. The LLM reports success to the user when nothing actually happened.
- **Root cause**: Defensive `runCatching` pattern applied too broadly. The `startActivity` return value (Unit) provides no success indication, and the exception is swallowed.
- **Risk**: MEDIUM. On HarmonyOS 2.0, the `ACTION_INTERNET_CONNECTIVITY` panel may not exist.
- **Recommendation**: **Fix.** Check if `startActivity` throws and report `"status": "error"` instead of `"panel_opened"`. Or use `packageManager.resolveActivity()` to verify the panel intent exists before claiming success.

### M2. `DeviceTools.OpenAppTool` — TOCTOU Race on Package Resolution
- **File**: `app/src/main/java/com/jarvis/assistant/tools/DeviceTools.kt:282-288`
- **Severity**: Medium
- **Impact**: Line 283 filters launchable apps; line 288 force-unwraps `getLaunchIntentForPackage(match.packageName)!!`. If the app is uninstalled between the filter and the intent call (unlikely but possible on a shared tablet), this crashes with `NullPointerException`.
- **Root cause**: Time-of-check/time-of-use race on `PackageManager` results.
- **Risk**: LOW in practice (the window is microseconds), but the `!!` is a crash vector.
- **Recommendation**: **Fix.** Replace `!!` with `?: return JsonOut.error(...)` for defensive safety.

### M3. SessionStateMachine Not Thread-Safe
- **File**: `app/src/main/java/com/jarvis/assistant/session/SessionStateMachine.kt:69-78`
- **Severity**: Medium
- **Impact**: `onEvent` reads `_state.value`, computes `next`, then writes `_state.value = next`. Two concurrent calls (e.g., barge-in from `SessionManager.cancelAll` racing with `TurnRunner.processLlm`) could both read the same state and both write — the second writer silently wins, dropping the first transition. This could leave the state machine in an inconsistent state (e.g., transitioning from SPEAKING to IDLE while the LLM is still thinking).
- **Root cause**: `MutableStateFlow` is atomic for individual reads/writes, but the read-compute-write sequence is not atomic.
- **Risk**: LOW-MEDIUM. In practice, most calls originate from the same session scope, and the `sessionSeq` guard in `SessionManager` prevents stale sessions from corrupting state. But a barge-in during the exact moment of `reportFailure` could lose the `ErrorOccurred` transition.
- **Recommendation**: **Monitor.** Add a `synchronized` block or use a `Mutex` in `onEvent`. Alternatively, accept the theoretical race since the `sessionSeq` guard already prevents user-visible corruption.

### M4. `MusicPlaybackOrchestrator` at 902 Lines — Decomposition Candidate
- **File**: `app/src/main/java/com/jarvis/assistant/media/MusicPlaybackOrchestrator.kt`
- **Severity**: Medium (maintainability)
- **Impact**: The file contains play-search, browser-lane, transport commands, now-playing, and verification logic all in one class. While the code is well-documented and the strategies are clearly separated by comments, the class has 15+ methods and handles 3 distinct responsibilities (search-play cascade, transport control, library browsing).
- **Root cause**: Organic growth across Phases 3-5 of the MUSIC lane. Each phase added methods without refactoring boundaries.
- **Risk**: LOW for correctness (the unit tests are thorough), but HIGH for future maintainability. A developer modifying browser-lane logic must understand the entire 902-line file.
- **Recommendation**: **Decompose before adding new music features.** Extract `TransportControl` (lines 512-742) and `LibraryBrowser` (lines 386-456) as separate classes. The `playSearchQuery` cascade (lines 110-259) is the core and should stay.

### M5. Coroutines 1.7.3 — Two Major Versions Behind
- **File**: `gradle/libs.versions.toml:11`
- **Severity**: Medium
- **Impact**: The project uses coroutines 1.7.3 (released May 2023). The latest stable is 1.9.x. Key improvements missed: structured concurrency fixes, `Channel` performance improvements, `Flow` operator bugfixes, and Kotlin 2.0 compatibility improvements.
- **Root cause**: Dependency pinning without a regular update cadence.
- **Risk**: LOW for current functionality (the code works), but MEDIUM for Kotlin 2.2 compatibility — coroutines 1.7.x was not designed for Kotlin 2.2 and may have edge-case compilation issues.
- **Recommendation**: **Update before release.** The update is low-risk (coroutines maintain strong backward compatibility). Pin to 1.9.1 or latest.

### M6. No Dependency Vulnerability Scanning
- **File**: `app/build.gradle.kts` (no dependency-check plugin)
- **Severity**: Medium
- **Impact**: OkHttp 4.12.0, gRPC 1.83.1, protobuf 3.25.3, and the bundled `sherpa-onnx.aar` are not scanned for CVEs. A transitive vulnerability in OkHttp (which handles all HTTP including token refresh) could be exploitable.
- **Root cause**: No OWASP Dependency-Check or Snyk plugin configured.
- **Risk**: LOW in the current single-user appliance context, but MEDIUM if the app is ever distributed more broadly.
- **Recommendation**: **Add before release.** Add `com.google.osgradleplugin` or `org.owasp.dependencycheck` plugin. Run once to baseline, then gate on CI.

### M7. `@Deprecated("P7 removes")` Facade Still Has Call Sites
- **File**: `app/src/main/java/com/jarvis/assistant/tools/FunctionRouter.kt:74-76`
- **Severity**: Medium (technical debt)
- **Impact**: The `execute(call): ToolExecution` method is marked for removal but is still called from `TurnRunner.kt:327` via `functionRouter.execute(call.function)`. The `ToolExecutor` interface still exposes it. This means the structured `executeResult` API and the legacy `execute` API coexist, creating confusion about which to use.
- **Root cause**: P7 extraction moved the turn logic to `TurnRunner` but didn't migrate the last call site.
- **Risk**: LOW for correctness (the wrapper is correct), but MEDIUM for maintainability — two APIs for the same operation.
- **Recommendation**: **Fix before release.** Migrate `TurnRunner.kt:327` to use `executeResult` directly, then remove the `execute` method from `ToolExecutor` and `ToolRegistry`.

### M8. AlarmRinger Singleton Scope Never Cancelled
- **File**: `app/src/main/java/com/jarvis/assistant/tools/AlarmRinger.kt:34`
- **Severity**: Medium
- **Impact**: `CoroutineScope(SupervisorJob() + Dispatchers.Default)` lives for the entire process lifetime. The scope's `SupervisorJob` is never completed. This isn't a true leak (the singleton lives forever), but it means the scope's `CoroutineExceptionHandler` is never invoked for final cleanup.
- **Root cause**: Singleton pattern with no lifecycle.
- **Risk**: LOW. The scope is correctly used (auto-stop watchdog), and the process lifetime is the intended scope.
- **Recommendation**: **Accept.** This is correct for an always-on appliance. Document the intent.

### M9. BootReceiver Creates Fire-and-Forget CoroutineScope
- **File**: `app/src/main/java/com/jarvis/assistant/service/BootReceiver.kt:35`
- **Severity**: Medium
- **Impact**: `CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ... }` creates a scope that's never cancelled. The `goAsync()` / `pending.finish()` pattern handles the BroadcastReceiver lifecycle, but the `SupervisorJob` lives until GC. If `rescheduleAllOnBoot()` is slow (many alarms), the scope outlives the receiver.
- **Root cause**: BroadcastReceiver's `onReceive` is not a suspend function; `goAsync()` + fire-and-forget is the standard pattern.
- **Risk**: LOW. The scope completes quickly (database read + alarm re-arm).
- **Recommendation**: **Accept or improve.** Store the `Job` and cancel it in a `finally` block after `pending.finish()`. Or use `goAsync()` with a properly scoped launch.

---

## TIER 4 — LOW

### L1. `SherpaKwsEngine.release()` Swallows Native Exceptions
- **File**: `app/src/main/java/com/jarvis/assistant/audio/SherpaKwsEngine.kt:104-113`
- **Severity**: Low
- **Impact**: Both `stream.release()` and `spotter.release()` catch and discard all exceptions. A native crash during release (double-free, use-after-free) would be silently swallowed.
- **Root cause**: Best-effort cleanup pattern (the `WakeWordEngine.release()` contract says "must be safe to call multiple times").
- **Risk**: LOW. The release ordering (stream first, then spotter) is correct, and double-release is handled by the interface contract.
- **Recommendation**: **Accept.** Log the exception at DEBUG level for diagnostic purposes, but don't propagate.

### L2. `AudioPipeline` Infinite Retry on Persistent Hardware Failure
- **File**: `app/src/main/java/com/jarvis/assistant/audio/AudioPipeline.kt:112-115`
- **Severity**: Low
- **Impact**: Generic exceptions in `runProducer` trigger a 100ms retry forever. If the microphone hardware is persistently broken (not just a transient `IllegalStateException`), the producer spins in an infinite retry loop, consuming CPU.
- **Root cause**: The `IllegalStateException` catch at line 104 correctly exits on "source not started," but the generic catch at line 112 retries indefinitely.
- **Risk**: LOW. A persistent hardware failure would also trigger the `IllegalStateException` path on the next `read()` call. The retry loop would only spin if `read()` throws a non-`IllegalStateException` exception repeatedly.
- **Recommendation**: **Monitor.** Add a consecutive-failure counter and surface `DetectorState.Failed` after N failures (similar to the wake-word detector pattern).

### L3. `JarvisForegroundService.lastController` Holds Stale Framework Reference
- **File**: `app/src/main/java/com/jarvis/assistant/service/JarvisForegroundService.kt:74,291`
- **Severity**: Low
- **Impact**: `lastController = c` stores a framework `MediaController` reference for duck/unduck. If the remote app dies between duck and unduck, `unduck()` calls `lastController?.transportControls?.play()` on a dead controller. The `runCatching` at line 305 masks the `DeadObjectException`.
- **Root cause**: Framework `MediaController` doesn't have a lifecycle callback for remote session death.
- **Risk**: LOW. The `runCatching` handles it gracefully, and the media-key fallback provides a backup path.
- **Recommendation**: **Accept.** The existing defensive pattern is correct for the framework's limitations.

### L4. `DeviceTools.SetWifiTool` Reports "panel_opened" Even When Panel Fails
- **File**: `app/src/main/java/com/jarvis/assistant/tools/DeviceTools.kt:146-151`
- **Severity**: Low
- **Impact**: When both `setWifiEnabled` and `startActivity(panel)` fail, the tool returns `"status": "panel_opened"` with a Russian detail string claiming the panel was opened. The LLM relays this as fact.
- **Root cause**: The panel fallback is best-effort and the error is swallowed.
- **Risk**: LOW. On the target device (HarmonyOS 2.0 with Android 11), the panel intent is likely to exist.
- **Recommendation**: **Fix if easy.** Wrap `startActivity` in a try-catch and return an error status on failure.

### L5. `ConversationManager.trim()` Fetches All Messages Then Discards
- **File**: `app/src/main/java/com/jarvis/assistant/data/ConversationManager.kt:51-72`
- **Severity**: Low
- **Impact**: `trim()` calls `dao.all()` which fetches ALL messages (could be thousands over time), then keeps only the last `maxMessages`. This is O(N) in total message count.
- **Root cause**: Room doesn't have a "delete all except last N" query without a subquery. The current implementation is simple but scales linearly.
- **Risk**: LOW for the current use case (max 20 messages retained, trimmed on every insert). Would become an issue if `maxMessages` were increased or the trim frequency reduced.
- **Recommendation**: **Monitor.** If message volume grows, replace with a single SQL `DELETE WHERE id NOT IN (SELECT id FROM messages ORDER BY id DESC LIMIT ?)`.

---

## Summary

| Tier | Count | Issues |
|------|-------|--------|
| **Critical** | 2 | C1 (destructive migration), C2 (main-thread AppGraph build) |
| **High** | 5 | H1 (BT deprecated), H2 (runBlocking), H3 (catch-all mask), H4 (no androidTest), H5 (Sherpa crash trap) |
| **Medium** | 9 | M1-M9 (wifi silent fail, TOCTOU, state machine race, orchestrator size, coroutines version, vuln scanning, deprecated facade, scope leaks) |
| **Low** | 5 | L1-L5 (exception swallowing, retry loop, stale reference, trim scaling, panel fallback) |

## Prioritized Action Items

| Priority | Issue | Effort |
|----------|-------|--------|
| 1 | C1 — Replace destructive migration | Medium |
| 2 | C2 — Move AppGraph off main thread | Medium-High |
| 3 | H1 — Bluetooth API 33+ fallback | Low |
| 4 | H3 — Specific exception catches in TurnRunner/AudioPipeline | Low |
| 5 | M7 — Remove deprecated `execute()` facade | Low |
| 6 | M5 — Update coroutines to 1.9.x | Low |
| 7 | H4 — Add instrumentation tests | High |
| 8 | M4 — Decompose MusicPlaybackOrchestrator | Medium |
