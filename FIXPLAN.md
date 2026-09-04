# FIXPLAN — Post-audit remediation (2026-09-04)

Fixes every issue from the 2026-09-04 code audit, plus two features: **voice
stop mid-speech without the wake word** and **custom Sherpa wake words**.
No backward compatibility is preserved (pre-1.0 appliance).

## A. Defect fixes

| # | Audit finding | Fix |
|---|---|---|
| A1 | `JarvisConfig.llmEndpoint` is misnamed — it is the SaluteSpeech gRPC target, not the LLM endpoint (ARCHITECTURE.md repeated the error) | Renamed to `saluteGrpcEndpoint`; docs corrected. The LLM URL stays `gigaChatEndpoint` |
| A2 | `ProviderSettings.wakeSensitivity` dead duplicate of `AppPrefs.wakeSensitivity` | Field removed; `AppPrefs` is the single source of truth |
| A3 | Deprecated `EncryptedSharedPreferences` (security-crypto) foundation | Replaced by `SecretVault` abstraction + `KeystoreVault` (AndroidKeyStore AES-256/GCM, fresh IV per value, corrupt-entry self-heal). security-crypto dependency deleted. `SecurePrefs` deleted |
| A4 | `DeviceTools` hardcoded RU error strings bypassed i18n; one EN string in the same file | New `ToolStrings` seam (RU `Default` for JVM tests + resource-backed `AndroidToolStrings`); all tool errors route through it; values-en added; ResourceParityTest enforces both locales |
| A5 | `openApp` claimed `"ok"` unconditionally — BAL silently blocks service-context launches on Android 10+ (violates the repo's own honesty standard) | Outcome now depends on `AppForegroundTracker.isVisible`: BAL-permitted → `ok`, UI not visible → honest `attempted` + contingency wording, mirroring the music lane |
| A6 | Weather geocoding hardcoded `language=ru`; first-result-only city pick | Geocoding language follows device locale (injected); top-5 candidates fetched with exact-name preference; response carries `resolved_name` + `country` so the LLM can state which city answered |
| A7 | `TurnRunner.spokeThisTurn` at instance scope shared across superseding sessions | Per-turn `TurnState` (atomic spoke flag) created per `runTurn`; `ttsSynthPermits` deliberately stays instance-level (global TTS stream cap, documented) |
| A8 | `AppGraph.speakVoiceSample` swallowed `CancellationException` | Rethrown after focus cleanup |
| A9 | `SecurePrefs` reported keystore resets via `println` (invisible in release) | Moot with A3 — `KeystoreVault` logs via Timber |
| A10 | `FunctionRouter` built a fresh `AppPrefs` per player resolve | One instance injected at construction |
| A11 | minSdk 24 contradicted the "Android 11 / HarmonyOS 2.0+" support claim | minSdk 30 (HarmonyOS 2.0 is API-30-based); docs aligned |

## B. Feature 1 — voice stop without the wake word

Saying **«стоп» / "stop"** while the assistant THINKS or SPEAKS cancels the
active turn (flushes TTS, cancels ASR/LLM, IDLE) — no wake word needed.

Design (validated against the bundled AAR v1.13.6):
- The gigaspeech KWS model is English-BPE (no Cyrillic tokens), but Russian
  «стоп» and English "stop" are the same spoken word /stɒp/. The keyword is
  added to the Sherpa keywords file as `▁ST O P` (BPE produced by
  `sentencepiece` with the repo's own `bpe.model` — same tooling as the
  existing `▁JA R VI S` line, which this toolchain reproduces exactly).
- The AAR's `KeywordSpotterResult` exposes `getKeyword()` and the JNI lib
  exports `newFromFile` — keyword identity and filesystem models both work.
- `WakeWordEngine` now reports its phrase list; `process()` returns the
  matched phrase INDEX. `SherpaKwsEngine` matches Sherpa's returned keyword
  text against the configured token lines (whitespace-normalized), falling
  back to the wake phrase.
- `Detection` gained `StopPhrase(keyword)`; it passes the barge-in gate
  UNGATED (one utterance must stop — repeat-to-interrupt stays wake-only).
- `SessionManager` routes `StopPhrase` to a new `stopActiveTurn()`: seq bump +
  session cancel + player flush + focus abandon, detection collector KEPT
  alive (unlike `cancelAll`), state machine `Cancelled` → IDLE. Ignored
  outside THINKING/SPEAKING (a "stop" inside a normal command must not nuke
  the turn).
- Porcupine-primary mode: the detector lazily builds a dedicated Sherpa stop
  lane (`keywords_stop.txt` asset, bundled model) and feeds it ONLY while the
  state machine is THINKING/SPEAKING (`setStopLaneEnabled`) — zero idle CPU.
  Sherpa-primary mode needs no second engine: the stop phrase rides in the
  same keywords file.
- Opt-out: `JarvisConfig.voiceStopEnabled` + Settings toggle.

## C. Feature 2 — custom Sherpa wake words

- The AAR's `newFromFile` constructor loads models from the filesystem —
  the old "asset-only AAR" limitation is lifted for the FILE path (the
  asset path stays for the bundled zero-config default).
- `SherpaModelStore` extracts the bundled model (int8 encoder/decoder/joiner
  int8 + tokens + bpe.model) into `filesDir` once, with size validation.
- `BpeTokenizer` (pure Kotlin, JVM-tested): parses the sentencepiece
  `bpe.model` protobuf (pieces+scores), encodes a word via max-score
  lattice Viterbi — verified byte-identical to sentencepiece BPE on 77
  probe words against the repo model. Rejects inputs that would hit
  `<unk>` (digits, punctuation, accents) instead of building a dead keyword.
- Settings → Wake word → Sherpa: free-text keyword (default «Jarvis»);
  validated live with the real tokenizer before saving; applied via the
  existing `reconfigureWakeWord()` path (no restart).
- Custom user-supplied Sherpa model directories (`sherpaOnnxPath` pref,
  previously dead) are now honored: absolute paths, generated keywords file,
  tokenization against THAT model's tokens/bpe.

## D. Verification

- Full JVM suite (`testDebugUnitTest`) + `assembleDebug` must pass
  (same gate as CI).
- New tests: BPE tokenizer (fixtures generated with the repo's own model),
  keyword-file writer, stop-phrase gating + session routing, per-turn state
  isolation, openApp honesty decision, weather locale/disambiguation,
  vault-backed credential store, tool-strings defaults.
- On-device items that stay manual (unchanged in nature): stop-phrase false
  accept rate at speaker volume, custom-keyword detection accuracy —
  RUNBOOK ladders added.
