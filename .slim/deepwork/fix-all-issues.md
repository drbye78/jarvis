# Deepwork: Fix All Identified Issues

## Goal
Fix all 21 identified bugs/issues from the technical debt report. No backward compatibility constraints.

## Issue Summary
| Tier | ID | Issue | Files |
|------|-----|-------|-------|
| C | C1 | Destructive DB migration | `data/AppDatabase.kt` |
| C | C2 | AppGraph on main thread | `service/JarvisForegroundService.kt`, `di/AppGraph.kt` |
| H | H1 | Bluetooth deprecated API | `tools/DeviceTools.kt` |
| H | H2 | runBlocking in release() | `audio/HybridWakeWordDetector.kt` |
| H | H3 | Broad catch blocks | `session/TurnRunner.kt`, `audio/AudioPipeline.kt`, `speech/SaluteSpeechTts.kt` |
| H | H4 | No instrumentation tests | `app/src/androidTest/` (create) |
| H | H5 | Sherpa crash trap | `audio/SherpaKwsEngine.kt` |
| M | M1 | WiFi silent failure | `tools/DeviceTools.kt` |
| M | M2 | OpenAppTool TOCTOU | `tools/DeviceTools.kt` |
| M | M3 | State machine thread safety | `session/SessionStateMachine.kt` |
| M | M4 | Orchestrator 902 lines | `media/MusicPlaybackOrchestrator.kt` |
| M | M5 | Coroutines 1.7.3 | `gradle/libs.versions.toml` |
| M | M6 | No vuln scanning | `app/build.gradle.kts` |
| M | M7 | Deprecated facade | `tools/FunctionRouter.kt`, `session/TurnRunner.kt` |
| M | M8 | AlarmRinger scope | `tools/AlarmRinger.kt` |
| M | M9 | BootReceiver scope | `service/BootReceiver.kt` |
| L | L1 | Sherpa release swallows exceptions | `audio/SherpaKwsEngine.kt` |
| L | L2 | AudioPipeline infinite retry | `audio/AudioPipeline.kt` |
| L | L3 | Stale MediaController | `service/JarvisForegroundService.kt` |
| L | L4 | WiFi panel failure report | `tools/DeviceTools.kt` |
| L | L5 | ConversationManager trim O(N) | `data/ConversationManager.kt` |

## Execution Phases

### Phase 1: Critical + Build Infrastructure (C1, C2, M5, M6)
**Owner**: @fixer (parallel)
**Gate**: Build verification
**Rationale**: Foundation work — DB migration, threading, and dependency updates must land first.

- **1A**: C1 — Replace `fallbackToDestructiveMigration()` with proper Room migration (v2→v3 with data preservation) + export schema + add MigrationTest
- **1B**: C2 — Move AppGraph construction off main thread in `JarvisForegroundService.kt`, show `Bootstrapping` notification state
- **1C**: M5 — Update coroutines to 1.9.x
- **1D**: M6 — Add OWASP dependency-check plugin

### Phase 2: DeviceTools + Audio Fixes (H1, H3, H5, M1, M2, L1, L2, L4)
**Owner**: @fixer (parallel per file group)
**Gate**: @oracle review
**Rationale**: Crash vectors and error handling — all changes are independent per file.

- **2A**: DeviceTools.kt — H1 (BT API 33+ fallback) + M1 (WiFi silent failure) + M2 (OpenAppTool TOCTOU) + L4 (WiFi panel report)
- **2B**: TurnRunner.kt + AudioPipeline.kt — H3 (specific catches) + L2 (infinite retry fix)
- **2C**: SherpaKwsEngine.kt — H5 (runtime assertion) + L1 (release exception logging)
- **2D**: HybridWakeWordDetector.kt — H2 (monitor/document runBlocking)
- **2E**: SaluteSpeechTts.kt — H3 (specific catches)

### Phase 3: Threading, Facades, Scopes (M3, M7, M8, M9)
**Owner**: @fixer
**Gate**: @oracle review
**Rationale**: Thread safety and coroutine correctness.

- **3A**: SessionStateMachine.kt — M3 (add Mutex)
- **3B**: FunctionRouter.kt + TurnRunner.kt — M7 (remove deprecated facade)
- **3C**: BootReceiver.kt — M9 (proper scope)
- **3D**: AlarmRinger.kt — M8 (document intent)
- **3E**: JarvisForegroundService.kt — L3 (document stale controller)

### Phase 4: Architecture Decomposition (M4)
**Owner**: @fixer
**Gate**: @oracle review
**Rationale**: Decompose the 902-line orchestrator.

- **4A**: Extract `TransportControl` and `LibraryBrowser` from MusicPlaybackOrchestrator.kt

### Phase 5: Tests + Final (H4, L5)
**Owner**: @fixer
**Gate**: Build + test pass
**Rationale**: Final validation.

- **5A**: ConversationManager.kt — L5 (SQL subquery trim)
- **5B**: Add instrumentation smoke tests
- **5C**: Final build + test gate
