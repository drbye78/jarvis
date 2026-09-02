package com.jarvis.assistant.tools

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.jarvis.assistant.R
import com.jarvis.assistant.util.JsonOut
import kotlinx.serialization.json.jsonObject

/**
 * On-tablet device control tools — the REAL implementation replacing the old
 * permanently-stubbed DeviceControlTool. Each capability is its own tool so
 * the LLM can pick precisely; every tool degrades gracefully with a
 * Russian-language instruction when its (optional) access is not granted.
 *
 * Permission model on the target device (Android 10/11, targetSdk 30):
 * - volume: no permission needed
 * - brightness / Wi-Fi panel / DND / screen-off: special access, granted via
 *   the Onboarding screen; tools report HOW to grant if missing
 */
class DeviceTools(private val context: Context) {

    /** Null-safe audio service lookup (audit #12: `as` threw on odd OEM ROMs). */
    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // ------------------------------------------------------------------
    // Volume
    // ------------------------------------------------------------------

    inner class SetVolumeTool : ToolContract {
        override val name = "setVolume"
        override val description =
            "Set a system volume level (0–100) for a stream: music, ring, alarm or system."
        override val parametersJson = schema(
            mapOf(
                "stream" to """{"type":"string","enum":["music","ring","alarm","system"],"description":"Audio stream (default music)"}""",
                "level" to """{"type":"integer","description":"Volume level from 0 to 100"}""",
            ),
            required = listOf("level"),
        )

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val level = obj.int("level")
                ?: return JsonOut.error("Missing required parameter: level")
            if (level !in 0..100) return JsonOut.error("level must be 0–100")
            val stream = when (obj.string("stream")?.lowercase()) {
                "ring", "ringer" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "system" -> AudioManager.STREAM_SYSTEM
                else -> AudioManager.STREAM_MUSIC
            }
            val am = audioManager
                ?: return JsonOut.error("Аудиосервис недоступен на этом устройстве")
            val max = am.getStreamMaxVolume(stream)
            val target = (level * max + 50) / 100
            am.setStreamVolume(stream, target, 0)
            return JsonOut.obj("status" to "ok", "level" to level)
        }
    }

    // ------------------------------------------------------------------
    // Brightness (needs WRITE_SETTINGS)
    // ------------------------------------------------------------------

    inner class SetBrightnessTool : ToolContract {
        override val name = "setBrightness"
        override val description = "Set screen brightness (0–100). Requires one-time 'modify system settings' access."
        override val parametersJson = schema(
            mapOf(
                "level" to """{"type":"integer","description":"Brightness from 0 to 100"}""",
            ),
            required = listOf("level"),
        )

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val level = obj.int("level")
                ?: return JsonOut.error("Missing required parameter: level")
            if (level !in 0..100) return JsonOut.error("level must be 0–100")
            if (!Settings.System.canWrite(context)) {
                return JsonOut.error(
                    "Нет права менять настройки системы. Открой приложение Джарвис → Экран приветствия → «Настройки записи» и выдай доступ."
                )
            }
            val target = (level * 255 + 50) / 100
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                target.coerceIn(1, 255),
            )
            return JsonOut.obj("status" to "ok", "level" to level)
        }
    }

    // ------------------------------------------------------------------
    // Wi-Fi (panel fallback on Android 10+)
    // ------------------------------------------------------------------

    inner class SetWifiTool : ToolContract {
        override val name = "setWifi"
        override val description =
            "Turn Wi-Fi on or off. On Android 10+ the system may only open the Wi-Fi settings panel instead of toggling directly."
        override val parametersJson = schema(
            mapOf(
                "state" to """{"type":"string","enum":["on","off"],"description":"Desired Wi-Fi state"}""",
            ),
            required = listOf("state"),
        )

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val state = obj.string("state")
                ?: return JsonOut.error("Missing required parameter: state")
            val enable = when (state.lowercase()) {
                "on", "true" -> true
                "off", "false" -> false
                else -> return JsonOut.error("state must be 'on' or 'off'")
            }

            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val toggled = wifi?.let {
                runCatching { it.setWifiEnabled(enable) }.getOrDefault(false)
            } ?: false

            return if (toggled) {
                JsonOut.obj("status" to "ok", "wifi" to state)
            } else {
                // Android 10+ policy: open the system panel for the user.
                val panel = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val resolved = context.packageManager.resolveActivity(panel, 0) != null
                if (!resolved) {
                    return JsonOut.error(
                        "Системная панель Wi-Fi недоступна на этом устройстве."
                    )
                }
                val result = runCatching { context.startActivity(panel) }
                if (result.isFailure) {
                    JsonOut.error(
                        "Не удалось открыть панель Wi-Fi: ${result.exceptionOrNull()?.message}"
                    )
                } else {
                    JsonOut.obj(
                        "status" to "panel_opened",
                        "detail" to "Система не позволяет менять Wi-Fi напрямую — открыл панель настроек.",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Bluetooth
    // ------------------------------------------------------------------

    inner class SetBluetoothTool : ToolContract {
        override val name = "setBluetooth"
        override val description = "Turn Bluetooth on or off."
        override val parametersJson = schema(
            mapOf(
                "state" to """{"type":"string","enum":["on","off"],"description":"Desired Bluetooth state"}""",
            ),
            required = listOf("state"),
        )

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val state = obj.string("state")
                ?: return JsonOut.error("Missing required parameter: state")
            val enable = when (state.lowercase()) {
                "on", "true" -> true
                "off", "false" -> false
                else -> return JsonOut.error("state must be 'on' or 'off'")
            }
            if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return JsonOut.error("Нет права Bluetooth Nearby devices — выдай его в настройках приложения.")
            }
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter = bm?.adapter
                ?: return JsonOut.error("Bluetooth adapter unavailable")
            if (Build.VERSION.SDK_INT >= 33) {
                // API 33+: adapter.enable()/disable() are deprecated;
                // open the Bluetooth settings screen instead.
                val panel = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val resolved = context.packageManager.resolveActivity(panel, 0) != null
                if (!resolved) {
                    return JsonOut.error(
                        "Настройки Bluetooth недоступны на этом устройстве."
                    )
                }
                val result = runCatching { context.startActivity(panel) }
                if (result.isFailure) {
                    return JsonOut.error(
                        "Не удалось открыть настройки Bluetooth: ${result.exceptionOrNull()?.message}"
                    )
                }
                return JsonOut.obj(
                    "status" to "panel_opened",
                    "detail" to "Bluetooth переключается через настройки системы.",
                )
            }
            val ok = if (enable) adapter.enable() else adapter.disable()
            return if (ok || adapter.isEnabled == enable) {
                JsonOut.obj("status" to "ok", "bluetooth" to state)
            } else {
                JsonOut.error("Не удалось переключить Bluetooth")
            }
        }
    }

    // ------------------------------------------------------------------
    // Do-not-disturb (needs notification policy access)
    // ------------------------------------------------------------------

    inner class SetDndTool : ToolContract {
        override val name = "setDnd"
        override val description =
            "Turn Do-Not-Disturb on or off. Requires one-time 'Do Not Disturb access' in settings."
        override val parametersJson = schema(
            mapOf(
                "state" to """{"type":"string","enum":["on","off"],"description":"Desired DND state"}""",
            ),
            required = listOf("state"),
        )

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val state = obj.string("state")
                ?: return JsonOut.error("Missing required parameter: state")
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                ?: return JsonOut.error("Не удалось получить доступ к сервису уведомлений")
            if (!nm.isNotificationPolicyAccessGranted) {
                return JsonOut.error(
                    "Нет доступа к режиму «Не беспокоить». Открой настройки → Звук → Не беспокоить → доступ для приложений → Джарвис."
                )
            }
            val filter = if (state.equals("on", true)) {
                android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            }
            nm.setInterruptionFilter(filter)
            return if (nm.currentInterruptionFilter == filter) {
                JsonOut.obj("status" to "ok", "dnd" to state)
            } else {
                JsonOut.error("Не удалось переключить режим «Не беспокоить»")
            }
        }
    }

    // ------------------------------------------------------------------
    // Screen off (needs device admin)
    // ------------------------------------------------------------------

    inner class LockScreenTool : ToolContract {
        override val name = "lockScreen"
        override val description =
            "Turn the tablet screen off immediately. Requires device-admin access (offered during onboarding)."
        override val parametersJson = schema(emptyMap())

        override suspend fun execute(arguments: String): String {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return JsonOut.error("Сервис администрирования устройства недоступен")
            val admin = ComponentName(context, JarvisDeviceAdmin::class.java)
            return if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
                JsonOut.obj("status" to "ok", "screen" to "off")
            } else {
                JsonOut.error(
                    "Экран нельзя выключить без прав администратора устройства. Открой экран приветствия Джарвиса и включи «Блокировка экрана»."
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Open app
    // ------------------------------------------------------------------

    inner class OpenAppTool : ToolContract {
        override val name = "openApp"
        override val description = "Open an installed app by name, e.g. YouTube, браузер, камера."
        override val parametersJson = schema(
            mapOf(
                "app" to """{"type":"string","description":"App name (Russian or English)"}""",
            ),
            required = listOf("app"),
        )

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val app = obj.string("app")
                ?: return JsonOut.error("Missing required parameter: app")
            val pm = context.packageManager
            val query = app.lowercase()
            val launchables = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            val match = launchables.firstOrNull {
                pm.getApplicationLabel(it).toString().lowercase().contains(query)
            } ?: return JsonOut.error("Приложение '$app' не найдено")

            val intent = pm.getLaunchIntentForPackage(match.packageName)
                ?: return JsonOut.error("Приложение '$app' недоступно для запуска")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return JsonOut.obj("status" to "ok", "app" to pm.getApplicationLabel(match).toString())
        }
    }

    // ------------------------------------------------------------------
    // Device info
    // ------------------------------------------------------------------

    inner class GetDeviceInfoTool : ToolContract {
        override val name = "getDeviceInfo"
        override val description =
            "Get device status: battery level, charging state, current time. Useful before answering questions about the tablet."
        override val parametersJson = schema(emptyMap())

        override suspend fun execute(arguments: String): String {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val charging = bm?.isCharging == true
            val time = java.text.SimpleDateFormat(context.getString(R.string.device_time_format), java.util.Locale.getDefault())
                .format(java.util.Date())
            return JsonOut.obj(
                "battery" to (if (level in 1..100) level else context.getString(R.string.device_battery_na)),
                "charging" to charging,
                "time" to time,
            )
        }
    }

    fun all(): List<ToolContract> = listOf(
        SetVolumeTool(),
        SetBrightnessTool(),
        SetWifiTool(),
        SetBluetoothTool(),
        SetDndTool(),
        LockScreenTool(),
        OpenAppTool(),
        GetDeviceInfoTool(),
    )
}

/**
 * Minimal device admin receiver — only the force-lock policy, used by the
 * lockScreen tool. No password or wipe policies.
 */
class JarvisDeviceAdmin : android.app.admin.DeviceAdminReceiver()
