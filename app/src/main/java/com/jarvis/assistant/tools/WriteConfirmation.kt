package com.jarvis.assistant.tools

/**
 * Outcome of a write-confirmation gate check.
 *
 * A sealed result rather than a Boolean so the caller can distinguish
 * "already confirmed, execute" from "ask the user" from "structurally
 * impossible / fail closed".
 */
sealed interface WriteGate {
    /** The challenge was confirmed for THIS turn and key; consume and execute. */
    data object Confirmed : WriteGate

    /** No live confirmation for this turn; ask the user for an explicit yes. */
    data object NeedsConfirmation : WriteGate

    /** No turn bound (or no key): structurally impossible to confirm. */
    data object Denied : WriteGate
}

/**
 * Single-use, PROCESS-LIFETIME confirmation store for external WRITE tools.
 *
 * ## Security invariant (read before editing)
 * A confirmation is derived ONLY from the user's own ASR text plus local
 * state. It is NEVER derived from a value the model can author: there is
 * deliberately no token, nonce, or other model-supplied secret the LLM could
 * echo back to authorize itself. The model can *request* a write; only a
 * genuine, ASR-produced affirmative on the immediately-next user turn can
 * confirm it. The store holds only a key DIGEST (see [WriteBinding]) — never
 * raw arguments — so nothing here can leak a payload into a log or a prompt.
 *
 * ## State machine
 * Fields: a monotonically increasing finalized-utterance [utteranceOrdinal], the
 * [currentTurnId] set by [noteTurnStart], and at most ONE [PendingWrite]
 * (key digest + arm turn + arm ordinal + arm time + optional confirmation turn).
 *
 * - [noteUserUtterance] advances the ordinal. If a live pending exists whose
 *   arm ordinal is EXACTLY one less (the immediately-previous utterance), whose
 *   arm turn is the immediately-previous turn, and the utterance is an
 *   [AffirmativeUtterance], the pending is marked confirmed for that turn.
 *   This is the ONLY method that can confirm a challenge.
 * - [gate] consumes a matching confirmed pending (single use), otherwise
 *   returns [WriteGate.NeedsConfirmation] and ARMS a fresh pending when it is
 *   safe to (non-null key, no live pending for the same turn). A pending older
 *   than [ttlMs] is dead and treated as absent for both confirm and arm.
 *
 * Thread-safe via one lock; NO suspension ever runs under the lock (`synchronized`,
 * not a `Mutex`).
 */
class WriteConfirmation(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    private val lock = Any()

    /** Count of finalized utterances seen; the arm/confirm ordering token. */
    private var utteranceOrdinal: Int = 0

    /** The turn most recently started; an utterance must belong to it. */
    private var currentTurnId: Int? = null

    /** The single live challenge, or null when none is armed. */
    private var pending: PendingWrite? = null

    /** Record the turn whose utterances follow. */
    fun noteTurnStart(turnId: Int) {
        synchronized(lock) {
            currentTurnId = turnId
        }
    }

    /**
     * Record a finalized user utterance. Advances the ordinal, then confirms a
     * live pending ONLY when this utterance is the immediately-following one
     * (ordinal adjacency), belongs to the immediately-next turn, is not
     * expired, and reads as an explicit affirmative. The utterance is ASR text
     * — never a model argument.
     */
    fun noteUserUtterance(turnId: Int, utterance: String) {
        synchronized(lock) {
            utteranceOrdinal += 1
            val candidate = livePending(clock()) ?: return
            val immediateUtterance = candidate.ordinal == utteranceOrdinal - 1
            val immediatelyNextTurn = candidate.turnId < turnId && turnId == candidate.turnId + 1
            val sameTurn = currentTurnId == turnId
            val nextTurnConfirmable = sameTurn && immediateUtterance && immediatelyNextTurn
            if (candidate.confirmedForTurnId == null && nextTurnConfirmable &&
                AffirmativeUtterance.isAffirmative(utterance)
            ) {
                candidate.confirmedForTurnId = turnId
            }
        }
    }

    /**
     * Check (and, on a match, atomically consume) the confirmation for [key]
     * on [turnId].
     *
     * `turnId == null` → [WriteGate.Denied]. A confirmed pending whose key and
     * turn match is consumed and returns [WriteGate.Confirmed] — a second
     * identical call therefore cannot execute again. Otherwise
     * [WriteGate.NeedsConfirmation] is returned and a fresh pending is armed
     * when allowed: a null [key] never arms (fail closed), and a live pending
     * for the SAME turn is never overwritten (bait-and-switch guard — the
     * model cannot swap the target call within the arming turn).
     */
    fun gate(key: String?, turnId: Int?): WriteGate {
        synchronized(lock) {
            if (turnId == null) return WriteGate.Denied
            val now = clock()
            val live = livePending(now)
            if (key != null && live != null && matchesConfirmed(live, key, turnId)) {
                pending = null // take-and-clear: single use
                return WriteGate.Confirmed
            }
            if (key != null && (live == null || live.turnId < turnId)) {
                pending = PendingWrite(key, turnId, utteranceOrdinal, now)
            }
            return WriteGate.NeedsConfirmation
        }
    }

    /** True when [candidate] was confirmed for this exact turn and key digest. */
    private fun matchesConfirmed(candidate: PendingWrite, key: String, turnId: Int): Boolean =
        candidate.confirmedForTurnId == turnId && candidate.key == key

    /** The live pending, clearing (and treating as absent) one past its TTL. */
    private fun livePending(now: Long): PendingWrite? {
        val candidate = pending ?: return null
        if (now - candidate.createdAtMs > ttlMs) {
            pending = null
            return null
        }
        return candidate
    }

    /**
     * One armed challenge. Holds only the key DIGEST and ordering metadata —
     * never raw arguments. [confirmedForTurnId] is set exactly once, by
     * [noteUserUtterance], from an ASR-produced affirmative.
     */
    private data class PendingWrite(
        val key: String,
        val turnId: Int,
        val ordinal: Int,
        val createdAtMs: Long,
        var confirmedForTurnId: Int? = null,
    )

    companion object {
        /** A confirmation challenge lives for five minutes. */
        const val DEFAULT_TTL_MS: Long = 5 * 60_000
    }
}
