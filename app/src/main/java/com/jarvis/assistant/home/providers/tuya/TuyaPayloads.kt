package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.HomeError
import com.jarvis.assistant.home.HomeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure Tuya Cloud OpenAPI payload parsing. Total and exception-free: malformed
 * input degrades to a typed [HomeResult.Err], never a throw.
 *
 * Tuya's envelope is `{"success":bool,"code":int,"msg":str,"result":…,"t":…,"tid":…}`.
 * A `success:true` reply carries `result`; a failure carries `code`/`msg`.
 */
object TuyaPayloads {

    /** Tuya error codes we act on; anything else is a generic [HomeError.FAILED]. */
    private const val CODE_TOKEN_INVALID = 1010
    private const val CODE_TOKEN_EXPIRED = 1011
    private const val CODE_PERMISSION_DENIED = 1106
    private const val CODE_SIGN_INVALID = 1004
    private const val CODE_DEVICE_OFFLINE = 2008

    /** Parse the envelope and return `result`, or a typed error. */
    fun result(body: String, json: Json): HomeResult<JsonElement> {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return HomeResult.Err(HomeError.FAILED, "tuya response was not JSON")
        val success = (obj["success"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        if (success != true) {
            val code = (obj["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            return HomeResult.Err(errorFor(code), "tuya code $code")
        }
        return HomeResult.Ok(obj["result"] ?: JsonObject(emptyMap()))
    }

    private fun errorFor(code: Int?): HomeError = when (code) {
        CODE_TOKEN_INVALID, CODE_TOKEN_EXPIRED -> HomeError.AUTH
        CODE_PERMISSION_DENIED, CODE_SIGN_INVALID -> HomeError.UNSUPPORTED
        CODE_DEVICE_OFFLINE -> HomeError.UNREACHABLE
        else -> HomeError.FAILED
    }

    /** The `access_token` of a token response, or null. */
    fun accessToken(element: JsonElement): String? =
        runCatching { element.jsonObject["access_token"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** The `expire_time` (SECONDS) of a token response, or null. */
    fun expireSeconds(element: JsonElement): Long? =
        runCatching { element.jsonObject["expire_time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.getOrNull()

    /** The `result` array of a device-list response, or an empty list. */
    fun array(element: JsonElement): JsonArray = runCatching { element.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    // ------------------------------------------------------------------
    // Device fields.
    // ------------------------------------------------------------------

    fun string(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** A device's `category` (kind discriminator) or null. */
    fun category(device: JsonObject): String? = string(device, "category")

    /** A device's display name: `name`, else `product_name`, else null. */
    fun deviceName(device: JsonObject): String? = string(device, "name") ?: string(device, "product_name")

    /** The stable native id (`id`) of a device, or null. */
    fun deviceId(device: JsonObject): String? = string(device, "id")

    /**
     * The DP status entries (`[{code,value}]`) of a device object's `status[]`
     * field, as a code→value map (the device-list shape).
     */
    fun statusMap(obj: JsonObject): Map<String, JsonElement> =
        entries(runCatching { obj["status"]?.jsonArray }.getOrNull().orEmpty())

    /**
     * DP status entries when `result` is the array ITSELF
     * (`GET /v1.0/devices/{id}/status` returns `{"result":[{code,value}]}`).
     */
    fun statusOf(element: JsonElement): Map<String, JsonElement> =
        entries(runCatching { element.jsonArray }.getOrNull().orEmpty())

    private fun entries(list: List<JsonElement>): Map<String, JsonElement> =
        list.mapNotNull { entry ->
            val e = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
            val code = string(e, "code") ?: return@mapNotNull null
            code to (e["value"] ?: JsonPrimitive(""))
        }.toMap()

    /**
     * The device list from `GET /v1.0/users/{uid}/devices`. The `result` is
     * usually a bare array, but some accounts/endpoints wrap it as
     * `{"devices":[...]}` — both are accepted.
     */
    fun deviceList(element: JsonElement): List<JsonObject> {
        val array = runCatching { element.jsonArray }.getOrNull()
            ?: runCatching { element.jsonObject["devices"]?.jsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
    }

    /** The writable `functions[]` codes of a device specification. */
    fun functionCodes(element: JsonElement): Set<String> =
        runCatching { element.jsonObject["functions"]?.jsonArray }.getOrNull().orEmpty()
            .mapNotNull { fn -> runCatching { string(fn.jsonObject, "code") }.getOrNull() }
            .toSet()
}
