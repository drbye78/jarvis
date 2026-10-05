package com.jarvis.assistant.manage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The value types a manageable setting can carry. Deliberately coarser than
 * arbitrary JSON so the REST layer and the export/import validator can
 * type-check a write before it reaches `AppPrefs`.
 */
enum class ManagedSettingType {
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
    STRING,
    ENUM,
    JSON_BLOB,
}

/**
 * When a management write takes effect. A local mirror of
 * `com.jarvis.assistant.settings.ApplyPolicy` so this package stays
 * Android-free and decoupled from the settings lane; an adapter maps the two
 * one-to-one in the bindings lane.
 */
enum class ManagedApplyPolicy {
    LIVE,
    SERVICE_RESTART,
    APP_RESTART,
}

/**
 * Pure metadata for one manageable setting — everything the REST/UI layer
 * needs to advertise, validate and describe a key without touching `AppPrefs`.
 *
 * [category] is a plain String (the `SettingsCategory` id) on purpose: this
 * file must not import the settings package, so the management core can be
 * tested in isolation and a settings reshuffle cannot drag UI types in here.
 *
 * A [secret] setting is **write-only**: its value is never returned by a GET,
 * never rendered, and only leaves the device inside an encrypted export when
 * the caller explicitly opts in (§14.6).
 *
 * @property essential a setting without which the assistant is materially
 *   degraded; used to rank/warn in the UI.
 * @property options the complete list of allowed persisted values, populated
 *   **only** for [ManagedSettingType.ENUM] (and any future constrained-string)
 *   settings and empty for every other type. It is the SINGLE source of truth
 *   for the allowed vocabulary: the REST layer advertises it so a UI renders a
 *   select instead of free text, and `ManagementCore` rejects a write outside
 *   it. For a non-enum setting the default empty list keeps the payload
 *   unchanged.
 */
data class ManagedSetting(
    val key: String,
    val category: String,
    val type: ManagedSettingType,
    val policy: ManagedApplyPolicy,
    val essential: Boolean = false,
    val secret: Boolean = false,
    val options: List<String> = emptyList(),
) {
    init {
        require(key.isNotBlank()) { "managed setting key must not be blank" }
    }
}

/**
 * A typed setting value. This is the projection used by the bindings/REST
 * layer; [ConfigDocument] stores the JSON-native form (`JsonElement`) so the
 * export codec stays a straight kotlinx-serialization round-trip. Use
 * [toJsonElement] / [fromJsonElement] to cross the boundary, which validates
 * the JSON against [ManagedSettingType] (a mismatch yields null, never a
 * coercion).
 */
sealed interface ManagedValue {
    data class Bool(val value: Boolean) : ManagedValue
    data class IntValue(val value: Int) : ManagedValue
    data class LongValue(val value: Long) : ManagedValue
    data class FloatValue(val value: Double) : ManagedValue
    data class StringValue(val value: String) : ManagedValue
    data class EnumValue(val value: String) : ManagedValue
    data class JsonValue(val value: JsonElement) : ManagedValue

    /** The JSON form persisted inside an export / returned by the REST layer. */
    fun toJsonElement(): JsonElement = when (this) {
        is Bool -> JsonPrimitive(value)
        is IntValue -> JsonPrimitive(value)
        is LongValue -> JsonPrimitive(value)
        is FloatValue -> JsonPrimitive(value)
        is StringValue -> JsonPrimitive(value)
        is EnumValue -> JsonPrimitive(value)
        is JsonValue -> value
    }

    companion object {
        /**
         * Parse [element] as [type]; null when it does not fit. The `when` is
         * exhaustive with NO `else`, so a new [ManagedSettingType] is a compile
         * error until its parser is added.
         */
        fun fromJsonElement(type: ManagedSettingType, element: JsonElement): ManagedValue? = when (type) {
            ManagedSettingType.BOOLEAN -> (element as? JsonPrimitive)?.booleanOrNull?.let { Bool(it) }
            ManagedSettingType.INT -> (element as? JsonPrimitive)?.intOrNull?.let { IntValue(it) }
            ManagedSettingType.LONG -> (element as? JsonPrimitive)?.longOrNull?.let { LongValue(it) }
            ManagedSettingType.FLOAT -> (element as? JsonPrimitive)?.doubleOrNull?.let { FloatValue(it) }
            ManagedSettingType.STRING ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.let { StringValue(it.content) }

            ManagedSettingType.ENUM ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.let { EnumValue(it.content) }

            ManagedSettingType.JSON_BLOB -> JsonValue(element)
        }
    }
}
