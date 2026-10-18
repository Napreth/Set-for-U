package com.napreth.set4u.core.settings.presentation

import com.napreth.set4u.core.localization.*
import com.napreth.set4u.core.settings.SettingsRegistry

/** Entries have their own IDs; multiple entries may reference the same canonical setting. */
class EntryRegistry(entries: List<SettingEntry>, private val settings: SettingsRegistry) {
    private val entries = linkedMapOf<String, SettingEntry>()
    init { entries.forEach { entry ->
        settings.resolve(entry.setting)
        val previous = this.entries.putIfAbsent(entry.id, entry)
        require(previous == null || previous === entry) { "Conflicting setting entry: ${entry.id}" }
    } }
    operator fun get(id: String) = entries[id] ?: throw IllegalArgumentException("Unregistered entry: $id")
    internal fun resolve(entry: SettingEntry) = get(entry.id).also { require(it === entry) { "Pages must reference the canonical entry: ${entry.id}" } }
    fun all(): List<SettingEntry> = entries.values.toList()
    fun forSetting(settingId: String): List<SettingEntry> = entries.values.filter { it.setting.id == settingId }
}

data class EntrySearchResult(val entryId: String, val settingId: String, val title: String, val pageIds: Set<String>)
class EntrySearchIndex internal constructor(private val entries: EntryRegistry, private val texts: TextResources, destinations: Map<String, Set<String>>) {
    private val destinations = java.util.Collections.unmodifiableMap(destinations.mapValues { java.util.Collections.unmodifiableSet(LinkedHashSet(it.value)) })
    fun destinationsFor(id: String): Set<String> = destinations[id].orEmpty()
    private val documents by lazy {
        val compiled = java.util.Properties()
        javaClass.getResourceAsStream("/metadata/settings-search.properties")?.reader(Charsets.UTF_8)?.use { compiled.load(it) }
        entries.all().associate { entry ->
            val current = searchDocument(entry, texts)
            val packed = if (compiled.getProperty("${entry.id}.hash") == com.napreth.set4u.core.metadata.fingerprint(current)) compiled.getProperty("${entry.id}.terms") else null
            entry.id to (packed ?: current)
        }
    }
    fun search(query: String, locale: String = texts.defaultLocale): List<EntrySearchResult> {
        val words = searchText(query).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return entries.all().filter { entry ->
            val haystack = documents.getValue(entry.id)
            words.all { it in haystack }
        }.map { EntrySearchResult(it.id, it.setting.id, texts.resolve(it.title, locale), destinations[it.id].orEmpty()) }
    }
}
internal fun searchDocument(entry: SettingEntry, texts: TextResources) = entry.textReferences().flatMap(texts::allTranslations).map(::searchText).distinct().sorted().joinToString(" ")
