package com.jarvis.assistant.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Pure state machine behind the Settings credential fields: turns raw typing
 * into debounced, deduplicated validation probes and per-service UI states.
 *
 * Contract (verified by [CredentialCheckControllerTest]):
 *  - input is debounced ([debounceMs] of typing silence) — one probe per pause,
 *    not one per keystroke;
 *  - a pair with a blank side is never probed and resets to [UiState.Idle];
 *  - a pair already confirmed [CredentialCheck.Valid] for the exact same
 *    values is not re-probed by the debounce path ([checkNow] forces a fresh
 *    probe — the button means "ask again");
 *  - a verdict for superseded values is discarded (the user kept typing while
 *    the probe was in flight — showing it would lie about current input);
 *  - a superseded/cancelled probe writes nothing (checked via
 *    [coroutineContext] activity — there are no suspension points between the
 *    guard and the write, so the guard is atomic with the write);
 *  - the two services are fully independent.
 *
 * No Android dependency: the Settings activity feeds input and renders
 * [states]; tests run it on a virtual-time TestScope.
 */
@OptIn(FlowPreview::class) // debounce is preview in coroutines 1.7.3
class CredentialCheckController(
    private val validator: CredentialValidator,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {

    /** The two mandatory credential pairs. */
    enum class Service { SALUTE, GIGACHAT }

    /** Per-service UI state, as rendered by the Settings status rows. */
    sealed interface UiState {
        /** Blank pair / nothing entered yet — status row hidden. */
        data object Idle : UiState

        /** Probe in flight. */
        data object Checking : UiState

        /** Probe finished — carry the [CredentialCheck] verdict. */
        data class Verdict(val check: CredentialCheck) : UiState
    }

    private data class Inputs(
        val salute: Pair<String, String> = "" to "",
        val gigachat: Pair<String, String> = "" to "",
    )

    private val inputs = MutableStateFlow(Inputs())

    private val _states = MutableStateFlow<Map<Service, UiState>>(
        mapOf(Service.SALUTE to UiState.Idle, Service.GIGACHAT to UiState.Idle)
    )

    /** Live per-service states; keyed by every [Service], always complete. */
    val states: StateFlow<Map<Service, UiState>> = _states

    /** Latest in-flight or queued probe per service (cancelled on supersede). */
    private val jobs = mutableMapOf<Service, Job>()

    /** Pair values already confirmed Valid — debounce path skips re-probing. */
    private val confirmedOk = mutableMapOf<Service, Pair<String, String>>()

    init {
        scope.launch {
            inputs.map { it.salute }
                .distinctUntilChanged()
                .debounce(debounceMs)
                .collect { launchCheck(Service.SALUTE, it, force = false) }
        }
        scope.launch {
            inputs.map { it.gigachat }
                .distinctUntilChanged()
                .debounce(debounceMs)
                .collect { launchCheck(Service.GIGACHAT, it, force = false) }
        }
    }

    /** Feed the current SaluteSpeech field values (call on every text change). */
    fun onSaluteInput(clientId: String, clientSecret: String) {
        inputs.update { it.copy(salute = clientId.trim() to clientSecret.trim()) }
    }

    /** Feed the current GigaChat field values (call on every text change). */
    fun onGigaChatInput(clientId: String, clientSecret: String) {
        inputs.update { it.copy(gigachat = clientId.trim() to clientSecret.trim()) }
    }

    /**
     * Immediate re-validation of both pairs, bypassing the debounce and the
     * already-Ok dedup — wired to the «Проверить ключи» button and to Save.
     */
    fun checkNow() {
        launchCheck(Service.SALUTE, inputs.value.salute, force = true)
        launchCheck(Service.GIGACHAT, inputs.value.gigachat, force = true)
    }

    private fun launchCheck(service: Service, pair: Pair<String, String>, force: Boolean) {
        jobs[service]?.cancel()
        if (pair.first.isBlank() || pair.second.isBlank()) {
            _states.update { it + (service to UiState.Idle) }
            confirmedOk.remove(service)
            return
        }
        if (!force && confirmedOk[service] == pair) return
        jobs[service] = scope.launch { runCheck(service, pair) }
    }

    private suspend fun runCheck(service: Service, pair: Pair<String, String>) {
        _states.update { it + (service to UiState.Checking) }
        val check = when (service) {
            Service.SALUTE -> validator.checkSalute(pair.first, pair.second)
            Service.GIGACHAT -> validator.checkGigaChat(pair.first, pair.second)
        }
        // Two guards, in order, with no suspension point after them:
        //  1) superseded by newer input — a newer debounce is already queued;
        //  2) cancelled (blanked out / forced re-check took over).
        val current = if (service == Service.SALUTE) inputs.value.salute else inputs.value.gigachat
        if (current != pair || !coroutineContext.isActive) return
        _states.update { it + (service to UiState.Verdict(check)) }
        if (check is CredentialCheck.Valid) confirmedOk[service] = pair
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 900L
    }
}
