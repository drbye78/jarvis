# Recorded fixtures (P2.3 — REMEDIATION_PLAN Phase 2)

Sanitized SaluteSpeech fixtures replayed in CI by
`SaluteFixtureReplayTest` through the Phase 1 in-process gRPC fakes.

- **Regenerate**: `./gradlew :app:recordSaluteFixtures` (local only, requires
  credentials in the gitignored `local.secrets.properties`). The recorder
  overwrites the same two files — commit the fresh set after review.
- **Format contract**: `app/src/test/java/com/jarvis/assistant/integration/SaluteFixtures.kt`
  (`SaluteFixture`, formatVersion 1). File names are stable
  (`asr_silence_ru.json`, `tts_mila_probe.json`) so re-recording is a clean diff.
- **Privacy / sanitization contract**: server responses ONLY — no credentials,
  no request headers, no timestamps. The recorder sends synthetic silence (ASR)
  and a fixed non-personal probe phrase (TTS), so no user audio or text can
  enter a fixture; ASR error entries carry the gRPC status code and exception
  class name only. Never put credentials or user data into this directory.
- **Current files are SEED fixtures** (`"recorded": false`): hand-written,
  synthetic-but-realistic placeholders committed so the replay tier runs in CI
  before any local recording exists. Replace them with real recordings.
