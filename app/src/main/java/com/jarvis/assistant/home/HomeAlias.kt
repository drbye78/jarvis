package com.jarvis.assistant.home

/**
 * Alias map helpers: a normalized spoken alias → a stable [HomeDeviceKey].
 *
 * The map is persisted as JSON by [HomeAliasCodec]. [seed] is the pure initial
 * population from a freshly discovered catalog: a normalized device name is
 * seeded ONLY when it is unique across the catalog, so an ambiguous name is
 * never silently bound to one of several devices (the resolver then surfaces
 * the ambiguity instead).
 */
object HomeAlias {

    fun seed(devices: List<HomeDevice>): Map<String, HomeDeviceKey> {
        val byName = linkedMapOf<String, MutableList<HomeDeviceKey>>()
        for (device in devices) {
            val name = HomeNormalizer.normalize(device.name)
            if (name.isEmpty()) continue
            byName.getOrPut(name) { mutableListOf() }.add(device.key)
        }
        val seeded = linkedMapOf<String, HomeDeviceKey>()
        for ((name, keys) in byName) {
            if (keys.size == 1) seeded[name] = keys.single()
        }
        return seeded
    }

    /** The normalized alias currently bound to [key], or null when none is set. */
    fun aliasFor(aliases: Map<String, HomeDeviceKey>, key: HomeDeviceKey): String? =
        aliases.entries.firstOrNull { it.value == key }?.key

    /**
     * Rebind [key] to [alias], returning a new map. Any previous alias of [key]
     * is dropped first (a device has at most one alias), and a blank [alias]
     * simply clears the old binding. The key is normalized exactly as
     * [HomeAliasCodec] does, so a stored alias always resolves.
     */
    fun bind(
        aliases: Map<String, HomeDeviceKey>,
        alias: String,
        key: HomeDeviceKey,
    ): Map<String, HomeDeviceKey> {
        val out = LinkedHashMap(aliases)
        out.entries.removeAll { it.value == key }
        val normalized = HomeNormalizer.normalize(alias)
        if (normalized.isNotEmpty()) out[normalized] = key
        return out
    }
}
