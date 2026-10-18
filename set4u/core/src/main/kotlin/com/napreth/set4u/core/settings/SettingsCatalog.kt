package com.napreth.set4u.core.settings

import com.napreth.set4u.core.localization.*
import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.settings.presentation.*

class SettingsCatalog(
    providers: Map<String, suspend (SettingTarget) -> SettingsPage>,
    definitions: List<SettingDefinition> = emptyList(),
    entries: List<SettingEntry> = emptyList(),
    val texts: TextResources = TextResources.Empty,
    entryPages: Map<String, Set<String>> = emptyMap(),
) {
    private val providers = providers.toMap()
    val registry = SettingsRegistry(definitions + entries.map { it.setting })
    val entries = EntryRegistry(entries, registry)
    val search = EntrySearchIndex(this.entries, texts, entryPages)
    init {
        require(providers.keys.all { it.isNotBlank() })
        entries.flatMap { it.textReferences() }.forEach { texts.allTranslations(it) }
    }
    constructor(vararg pages: SettingsPage, texts: TextResources = TextResources.Empty) : this(
        staticProviders(pages.toList()), entries = pages.flatMap { it.snapshot().entries }, texts = texts,
        entryPages = pages.flatMap { p -> p.entries.map { it.id to p.id } }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() },
    )
    suspend fun describe(pageId: String, target: SettingTarget) = load(pageId, target).page
    internal suspend fun load(pageId: String, target: SettingTarget): LoadedSettingsPage {
        val definition = (providers[pageId] ?: error("Unknown settings page: $pageId"))(target)
        val page = definition.snapshot()
        require(page.id == pageId)
        page.entries.forEach { entries.resolve(it); registry.resolve(it.setting) }
        texts.allTranslations(page.title)
        return LoadedSettingsPage(page, definition::onEnter)
    }
    internal fun validateDestination(destination: SettingsDestination) {
        require(destination.systemScreen != null || destination.pageId in providers) { "Unknown destination: ${destination.pageId}" }
    }
    companion object { val Empty = SettingsCatalog(emptyMap()) }
}
private fun staticProviders(pages: List<SettingsPage>): Map<String, suspend (SettingTarget) -> SettingsPage> {
    require(pages.map { it.id }.distinct().size == pages.size)
    return pages.associate { original ->
        val frozen = original.snapshot()
        val page = object : SettingsPage(frozen.id, frozen.title) {
            override val entries = frozen.entries
            override fun layout(rows: PageRows) = frozen.layout(rows)
            override suspend fun onEnter(context: SettingsPageContext) = original.onEnter(context)
        }
        val provider: suspend (SettingTarget) -> SettingsPage = { page }
        original.id to provider
    }
}
internal data class LoadedSettingsPage(val page: SettingsPageSnapshot, val onEnter: suspend (SettingsPageContext) -> Unit)
