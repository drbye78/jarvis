package com.jarvis.assistant.cognitive.embed

/**
 * COGNITIVE_PLAN §10.2: the CI retrieval-gate verdict for the LOCAL branch,
 * recorded from the RetrievalEvalTest run (50 RU fixtures, recall@5,
 * ship-or-reject at ≥ 15 % relative improvement).
 *
 * This constant is the AUTO-default fallback when no on-device benchmark
 * verdict exists (EmbedderSelection). It is asserted against a freshly
 * computed verdict on every CI run — changing the fixtures or the engines
 * REQUIRES re-recording the verdict here, deliberately: the ship decision
 * cannot silently drift.
 *
 * Recorded verdict (v1 fixtures, LexicalEmbedder hybrid): see
 * app/src/test/resources/cognitive/eval/retrieval/results-baseline.json
 * and RetrievalEvalTest.
 */
object RetrievalGate {

    /**
     * true  — the LOCAL hybrid cleared the §10.2 gate on the recorded CI
     *         run; AUTO resolves to LOCAL vectors when no on-device
     *         benchmark overrode it.
     * false — honest negative result: AUTO stays OFF (lexical + relation
     *         boosts only) until an on-device benchmark proves a winner.
     */
    const val LOCAL_BRANCH_SHIPS = false
}
