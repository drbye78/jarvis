package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.R

/**
 * Strings the TOOL layer returns as JSON error/details — these are read by
 * the LLM and SPOKEN to the user, so they must follow the device locale.
 *
 * Audit A4: [DeviceTools] hardcoded Russian error literals (with one English
 * string in the same file), so English-locale users heard Russian errors
 * from the tool lane while the rest of the app was fully localized. Tool
 * errors now flow through this seam:
 *
 * - [AndroidToolStrings] resolves the `tool_*` resources (values/ +
 *   values-en/ — parity is test-enforced by [com.jarvis.assistant.ResourceParityTest]);
 * - [Default] carries the Russian literals for JVM tests and non-Android
 *   callers, mirroring the [com.jarvis.assistant.session.SpeechPhrases] pattern.
 */
interface ToolStrings {
    val audioServiceUnavailable: String
    val writeSettingsMissing: String
    val wifiPanelUnavailable: String
    fun wifiPanelOpenFailed(detail: String?): String
    val wifiPanelDetail: String
    val btNearbyPermissionMissing: String
    val btSettingsUnavailable: String
    fun btSettingsOpenFailed(detail: String?): String
    val btToggleFailed: String
    val btAdapterUnavailable: String
    val dndServiceUnavailable: String
    val dndAccessMissing: String
    val dndToggleFailed: String
    val adminServiceUnavailable: String
    val adminLockMissing: String
    fun appNotFound(app: String): String
    fun appNotLaunchable(app: String): String
    val openAppAttemptedDetail: String
    val weatherNotAvailable: String

    companion object {
        /** Russian fallback (the product language) — also the JVM-test default. */
        val Default: ToolStrings = object : ToolStrings {
            override val audioServiceUnavailable =
                "Аудиосервис недоступен на этом устройстве"
            override val writeSettingsMissing =
                "Нет права менять настройки системы. Открой приложение Джарвис → Экран приветствия → «Настройки записи» и выдай доступ."
            override val wifiPanelUnavailable =
                "Системная панель Wi-Fi недоступна на этом устройстве."
            override fun wifiPanelOpenFailed(detail: String?) =
                "Не удалось открыть панель Wi-Fi: ${detail ?: "неизвестная ошибка"}"
            override val wifiPanelDetail =
                "Система не позволяет менять Wi-Fi напрямую — открыл панель настроек."
            override val btNearbyPermissionMissing =
                "Нет права Bluetooth Nearby devices — выдай его в настройках приложения."
            override val btSettingsUnavailable =
                "Настройки Bluetooth недоступны на этом устройстве."
            override fun btSettingsOpenFailed(detail: String?) =
                "Не удалось открыть настройки Bluetooth: ${detail ?: "неизвестная ошибка"}"
            override val btToggleFailed = "Не удалось переключить Bluetooth"
            override val btAdapterUnavailable = "Bluetooth-адаптер недоступен на этом устройстве"
            override val dndServiceUnavailable =
                "Не удалось получить доступ к сервису уведомлений"
            override val dndAccessMissing =
                "Нет доступа к режиму «Не беспокоить». Открой настройки → Звук → Не беспокоить → доступ для приложений → Джарвис."
            override val dndToggleFailed =
                "Не удалось переключить режим «Не беспокоить»"
            override val adminServiceUnavailable =
                "Сервис администрирования устройства недоступен"
            override val adminLockMissing =
                "Экран нельзя выключить без прав администратора устройства. Открой экран приветствия Джарвиса и включи «Блокировка экрана»."
            override fun appNotFound(app: String) = "Приложение '$app' не найдено"
            override fun appNotLaunchable(app: String) = "Приложение '$app' недоступно для запуска"
            override val openAppAttemptedDetail =
                "Я не вижу открытый экран, поэтому система могла заблокировать запуск — открой приложение вручную, если оно не появилось."
            override val weatherNotAvailable = "нет данных"
        }
    }
}

/** Locale-aware, resource-backed implementation for the live app. */
class AndroidToolStrings(private val context: Context) : ToolStrings {
    override val audioServiceUnavailable: String
        get() = context.getString(R.string.tool_audio_service_unavailable)
    override val writeSettingsMissing: String
        get() = context.getString(R.string.tool_write_settings_missing)
    override val wifiPanelUnavailable: String
        get() = context.getString(R.string.tool_wifi_panel_unavailable)
    override fun wifiPanelOpenFailed(detail: String?): String =
        context.getString(R.string.tool_open_failed_with_detail, context.getString(R.string.tool_wifi_panel), detail ?: "")
    override val wifiPanelDetail: String
        get() = context.getString(R.string.tool_wifi_panel_detail)
    override val btNearbyPermissionMissing: String
        get() = context.getString(R.string.tool_bt_nearby_missing)
    override val btSettingsUnavailable: String
        get() = context.getString(R.string.tool_bt_settings_unavailable)
    override fun btSettingsOpenFailed(detail: String?): String =
        context.getString(R.string.tool_open_failed_with_detail, context.getString(R.string.tool_bt_settings), detail ?: "")
    override val btToggleFailed: String
        get() = context.getString(R.string.tool_bt_toggle_failed)
    override val btAdapterUnavailable: String
        get() = context.getString(R.string.tool_bt_adapter_unavailable)
    override val dndServiceUnavailable: String
        get() = context.getString(R.string.tool_dnd_service_unavailable)
    override val dndAccessMissing: String
        get() = context.getString(R.string.tool_dnd_access_missing)
    override val dndToggleFailed: String
        get() = context.getString(R.string.tool_dnd_toggle_failed)
    override val adminServiceUnavailable: String
        get() = context.getString(R.string.tool_admin_service_unavailable)
    override val adminLockMissing: String
        get() = context.getString(R.string.tool_admin_lock_missing)
    override fun appNotFound(app: String): String =
        context.getString(R.string.tool_app_not_found, app)
    override fun appNotLaunchable(app: String): String =
        context.getString(R.string.tool_app_not_launchable, app)
    override val openAppAttemptedDetail: String
        get() = context.getString(R.string.tool_open_app_attempted_detail)
    override val weatherNotAvailable: String
        get() = context.getString(R.string.tool_weather_not_available)
}
