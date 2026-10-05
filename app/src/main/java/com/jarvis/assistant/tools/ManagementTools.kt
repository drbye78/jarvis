package com.jarvis.assistant.tools

import com.jarvis.assistant.manage.ManagementMode
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.JsonOut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * R13 §14.7 voice enable/disable of the management surface.
 *
 * Three tools, all [ToolRisk.CONTROLLED] (voice-turn-only, user-originated
 * local control — see [ToolAuthorization]):
 *
 *  - [EnableLocalManagementTool] — loopback only; adb-gated, so it may skip
 *    the two-turn dance, but it IS logged (an INFO, content-free line);
 *  - [EnableLanManagementTool] — opens a NETWORK-reachable surface, so it
 *    additionally implements [ConfirmedTool] and the registry requires an
 *    explicit affirmative on the immediately-next turn before `execute` runs;
 *  - [DisableManagementTool] — the fail-safe direction, always allowed on a
 *    voice turn.
 *
 * Each tool writes `prefs.managementMode` to its [ManagementMode.id] and then
 * applies the persisted intent through [applyMode] (production:
 * `ManagementServerProvider.activate()` for an enable and `deactivate()` for a
 * disable). It is a DELIBERATE user action, NOT the process-start
 * `reconcile()`: [ManagementServerProvider.activate] also re-opens a listener
 * that LAN idle-close shut, whereas `reconcile()` would leave a persisted
 * `lan` intent closed after an idle window. The call is heavy — TLS + Netty —
 * so it runs on [Dispatchers.Default]. The pref write happens INSIDE
 * `execute`, i.e. only after the registry's confirmation gate returned
 * [WriteGate.Confirmed], so an unconfirmed LAN call changes nothing.
 *
 * Deliberately Android-free: `applyMode` is injected, so the mode-writing
 * logic is JVM-testable without an Android Context (the composition root
 * supplies the provider-backed action).
 */
class ManagementTools(
    private val prefs: AppPrefs,
    private val strings: ToolStrings = ToolStrings.Default,
    /**
     * Applies the persisted mode to the process-scoped listener. Production
     * dispatches enable → `ManagementServerProvider.activate()` and disable →
     * `deactivate()`; a test injects a fake so no Android/Netty is touched.
     */
    private val applyMode: (ManagementMode) -> Unit,
) {

    /** All three tools, in advertised order. */
    fun all(): List<ToolContract> = listOf(
        EnableLocalManagementTool(),
        EnableLanManagementTool(),
        DisableManagementTool(),
    )

    /** Loopback-only enable: no confirmation (adb-gated), but logged. */
    inner class EnableLocalManagementTool : ToolContract {
        override val name = "enableLocalManagement"
        override val risk = ToolRisk.CONTROLLED
        override val description =
            "Enable the assistant's optional management web interface on localhost only " +
                "(reachable from this device, for example through adb). No network exposure. Applies immediately."
        override val parametersJson = EMPTY_PARAMETER_SCHEMA

        override suspend fun execute(arguments: String): String {
            Timber.i("Management: localhost listener enabled by voice")
            return applyAndReport(ManagementMode.LOCALHOST, strings.managementLocalEnabled)
        }
    }

    /**
     * LAN enable: confirmation-gated. The description tells the model to ask
     * the user first; the registry enforces it.
     */
    inner class EnableLanManagementTool : ToolContract, ConfirmedTool {
        override val name = "enableLanManagement"
        override val risk = ToolRisk.CONTROLLED
        override val description =
            "Enable the assistant's optional management web interface on the LOCAL NETWORK, so other " +
                "devices on the same network can reach it over HTTPS. Security-sensitive: you MUST ask the " +
                "user to confirm this exact action first. The first call returns needs_confirmation and " +
                "changes nothing; call it again with no arguments only after the user explicitly agrees."
        override val parametersJson = EMPTY_PARAMETER_SCHEMA

        override val confirmationDomain = "management"
        override val confirmationAction = "lan"

        override suspend fun execute(arguments: String): String =
            applyAndReport(ManagementMode.LAN, strings.managementLanEnabled)
    }

    /** Fail-safe disable: always allowed on a voice turn. */
    inner class DisableManagementTool : ToolContract {
        override val name = "disableManagement"
        override val risk = ToolRisk.CONTROLLED
        override val description =
            "Disable the assistant's management web interface and stop its listener. The safe direction. Applies immediately."
        override val parametersJson = EMPTY_PARAMETER_SCHEMA

        override suspend fun execute(arguments: String): String {
            Timber.i("Management: listener disabled by voice")
            return applyAndReport(ManagementMode.DISABLED, strings.managementDisabled)
        }
    }

    /**
     * Persist the intent, then apply it. A failure to bind (e.g. no LAN IPv4
     * available) is an honest, content-free error result; the persisted intent
     * is left as the user asked so the status row shows "mode, stopped".
     */
    private suspend fun applyAndReport(mode: ManagementMode, message: String): String {
        prefs.managementMode = mode.id
        return try {
            withContext(Dispatchers.Default) { applyMode(mode) }
            JsonOut.obj("status" to "ok", "mode" to mode.id, "message" to message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Content-free: the exception message can carry a host/port.
            Timber.w("Management: applying mode %s failed (%s)", mode.id, e.javaClass.simpleName)
            JsonOut.error(strings.managementUnavailable)
        }
    }
}
