package com.jarvis.assistant

import android.content.SharedPreferences

/**
 * COGNITIVE_PLAN 0.7 test infrastructure: an in-memory [SharedPreferences]
 * implementation so prefs-backed classes ([com.jarvis.assistant.util.AppPrefs],
 * [com.jarvis.assistant.util.PrefsFlow]) are JVM-testable without Robolectric.
 *
 * Listener semantics mirror the real framework: every successful commit/
 * apply notifies all registered listeners with the affected key (removal
 * included; clear notifies per removed key).
 */
class FakeSharedPreferences : SharedPreferences {

    val map = LinkedHashMap<String, Any?>()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)

    override fun getString(key: String?, defValue: String?): String? =
        map[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String> {
        @Suppress("UNCHECKED_CAST")
        return (map[key] as? Set<String>)?.toMutableSet() ?: defValues ?: mutableSetOf()
    }

    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listeners.remove(listener)
    }

    private fun notifyChanged(key: String) {
        listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
    }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val ops = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            apply { if (key != null) ops[key] = value }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { if (key != null) ops[key] = values }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            apply { if (key != null) ops[key] = value }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            apply { if (key != null) ops[key] = value }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            apply { if (key != null) ops[key] = value }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            apply { if (key != null) ops[key] = value }

        override fun remove(key: String?): SharedPreferences.Editor =
            apply { if (key != null) removals.add(key) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            if (clearAll) {
                val keys = map.keys.toList()
                map.clear()
                keys.forEach { notifyChanged(it) }
                clearAll = false
            }
            removals.forEach { key ->
                map.remove(key)
                notifyChanged(key)
            }
            removals.clear()
            ops.forEach { (key, value) ->
                map[key] = value
                notifyChanged(key)
            }
            ops.clear()
        }
    }
}
