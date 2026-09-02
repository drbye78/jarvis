# PLAN — AEC (Phase A + Phase B) & Follow-up Window Mode

Status: **IMPLEMENTED** (2026-09-02) — commits 0962524, eada709, cd436d9 on `feat/aec-followup`; gates: 246 pure-JVM tests + 32-file Android compile + router wiring. Device validation ladders live in RUNBOOK.
Branch: `feat/aec-followup` off `main` (`bbe5ae2`). No backward compatibility
required. All new features are **optional and user-controllable, default off**.

## 0. Scope corrections (honesty first)

Two corrections to the earlier Q1/Q2 sketches, discovered during pre-flight:

1. **WebRTC AEC3 is not deliverable as a Maven dependency.** Verified against
   `io.getstream:stream-webrtc-android:1.3.8` (the maintained libwebrtc
   packaging): its `WebRtcAudioEffects` wraps the *framework*
   `AcousticEchoCanceler`, and the native APM runs only inside a
   PeerConnection's native audio stack — there is **no public Java API for
   standalone APM use** (`ExternalAudioProcessingFactory` injects *your own*
   processing into *their* pipeline, the opposite direction). No other
   Android-consumable APM artifact exists on Maven Central (surveyed). A real
   AEC3 would require a custom JNI wrapper compiled against libwebrtc — not
   compilable or verifiable in this offline environment.
   → **Phase B delivers**: the full far-end-reference architecture + a
   built-in, pure-Kotlin adaptive echo canceller (NLMS family) that is
   unit-tested offline, behind a pluggable `EchoCanceller` interface whose
   native-AEC3 slot is documented. Docs state plainly that the built-in
   canceller is not AEC3-grade (typ. 15–30 dB ERLE under linear echo paths,
   weaker against nonlinear speaker distortion).
2. **Own-TTS echo does not need AudioPlaybackCapture.** The assistant's own
   playback is known *before* it hits the speaker — `AudioPlaybackCapture`
   cannot even capture it (usage `ASSISTANT` is exempt). We tap our own TTS
   PCM electrically (zero-latency, permission-free) and use
   `AudioPlaybackCapture` (API 29+) only for **other apps' music** — exactly
   the M7 scenario (wake word through speaker music). That lane needs a
   one-time-per-service MediaProjection consent, opt-in, experimental.

## 1. Architecture (new package `audio/aec/`, pure Kotlin unless noted)

```
                          far-end lanes (echo reference)
  TtsPlayer tap (24 kHz) ──► LinearResampler ──┐
  (StreamingAudioTrackPlayer)                  ├─► FarEndMixer ─► DelayAligner ─► NlmsEchoCanceller
  PlaybackCaptureSource (API29+, 16 kHz) ──────┘   (time-stamped      (block      (NLMS adaptive
   other apps' music, opt-in                       ring buffer)     xcorr)       filter + NLP gate)
                                                        ▲                              │
  AudioRecordSource(MicProfile) ─► AudioPipeline ──────│──────────────────────────────┴─► frames/ring
   OFF        : VOICE_RECOGNITION, no HW AEC           │                    processed once,
   HARDWARE   : VOICE_COMMUNICATION + AcousticEchoCanceler (platform DSP)   shared by wake-word + ASR
   SOFTWARE   : VOICE_RECOGNITION + NlmsEchoCanceller
```

- `AecMode` (OFF/HARDWARE/SOFTWARE) drives `MicProfile` (audio source int +
  attach-HW-AEC flag) — one pure decision, tested.
- `AecProbe`/`AecDiag`: `AcousticEchoCanceler.isAvailable()` static probe +
  runtime attach outcome logged under the `AecDiag` Timber tag (MusicDiag
  pattern) and persisted for the Settings row. **Phase A** = this + the
  VOICE_COMMUNICATION switch + knob (default OFF, because comm-mode NS/AGC can
  shift wake-word accuracy — must be validated on the MatePad).
- `NlmsEchoCanceller`: per-20 ms-frame block processing, tail default 96 ms
  (1536 taps ≈ 25M MAC/s — fine on-device), normalized step with leak
  regularization, divergence guard, ERLE estimator, conservative residual
  suppression gate active only while far-end energy is present.
- `DelayAligner`: block normalized cross-correlation over the far-end ring
  (search ±250 ms, refresh every 250 ms during far-end activity) so the NLMS
  taps don't waste coverage on bulk delay.
- `EnergyVad` (pure, `audio/`): RMS + adaptive noise floor + onset/offset
  hysteresis + hangover; feeds the follow-up window (not ASR EOU — the server
  still owns that).

## 2. Follow-up window mode

- `AssistantState` gains `FOLLOW_UP_WINDOW`; `SessionEvent` gains
  `FollowUpWindowOpened / FollowUpSpeechDetected / FollowUpWindowExpired`;
  legal edges: SPEAKING→FOLLOW_UP_WINDOW→{LISTENING (speech or wake),
  IDLE (expiry/error/cancel)}. Wake word stays armed during the window.
- `FollowUpWindowController` (pure, injectable clock): one window per spoken
  turn; opens only when the turn actually spoke (drain completed, not barge-in
  cancelled); VAD onset triggers `startSession()` (no wake word needed);
  expiry → IDLE. Chained conversation = each spoken turn re-opens the window.
- `TurnRunner.finish(id, spoke)` — signature change (no-compat): spoke = any
  sentence reached playback this turn.
- UI: orb `FOLLOW_UP` visual (warm listening tint + shrinking countdown arc,
  `setFollowUpProgress`), status pill «Продолжайте говорить…», Settings card:
  MaterialSwitch + window length 2–12 s (default 5 s). Default OFF.
- Interplay: `pauseMusicOnWake` and the capture-based AEC both reduce the
  music-in-window false-trigger risk; EnergyVad onset requires short/long-term
  energy ratio (≥6×), documented as *not* music-proof by itself.

## 3. UX surface (Settings)

- Card «Эхоподавление» / "Echo cancellation": RadioGroup OFF / HARDWARE /
  SOFTWARE; probe row («Аппаратный AEC: доступен/нет» from AecProbe); sub-card
  switch «Захват музыки (API 29+, эксперимент)» + consent button launching
  MediaProjection; hint that HARDWARE/SOFTWARE apply after service restart
  (AudioRecord must be rebuilt).
- Card «Продолжение диалога» / "Follow-up window": switch + seconds slider.
- All strings RU + EN with exact key/format-arg parity (script-checked, same
  discipline as the values-en completion).

## 4. Test plan (offline, pure JVM)

- `NlmsEchoCancellerTest` — synthetic linear echo path (delay + FIR + noise):
  ERLE ≥ 20 dB on far-end-only segments after convergence; near-end signal
  preserved (relative error bound); far-end silent ⇒ passthrough bit-equal;
  divergence guard reset behavior.
- `DelayAlignerTest` — injected lag found within tolerance, robust to near-end
  speech contamination.
- `LinearResamplerTest` — length math, sine fidelity (band-limited), streaming
  continuity across block boundaries.
- `EnergyVadTest` — onset/offset/hangover, adaptive floor, music-steady-state
  does not trigger (ratio gate).
- `FollowUpWindowControllerTest` — virtual clock: opens only after spoken
  turns, expires, triggers, chains, barge-in cancels window, muted never.
- `SessionTransitions` — new edges accepted; illegal ones rejected (extend
  existing suite).
- `MicProfileTest`, `AecModeTest` (parse/coerce), far-end mixer ordering.
- Android gate gains: `PlaybackCaptureFarEndSource`, refactored
  `AudioRecordSource`, TTS tap, `AudioPipeline` (compile-only against
  android-all API-30).

## 5. Deliverable shape

Commits on `feat/aec-followup` → merge to `main`; combined patch
(`cf5f9a1..main`) + refreshed repo bundle/tarball uploaded to the file hosts;
worklog + RUNBOOK device-verification ladders (AEC probe via
`adb logcat -s AecDiag`, ERLE recipe, wake-word-vs-comm-mode validation,
follow-up window tuning) and honest limitations.
