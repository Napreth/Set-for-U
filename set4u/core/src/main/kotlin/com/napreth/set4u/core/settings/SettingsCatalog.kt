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
    handlers: List<com.napreth.set4u.core.execution.HandlerDefinition<*>> = emptyList(),
    navigation: List<NavigationEntry> = emptyList(),
) {
    private val providers = providers.toMap()
    val registry = SettingsRegistry(definitions + entries.map { it.setting })
    val entries = EntryRegistry(entries, registry)
    val handlers = com.napreth.set4u.core.execution.HandlerRegistry(registry.all().map { it.handlerDefinition } + handlers)
    val navigation: List<NavigationEntry> = java.util.Collections.unmodifiableList(navigation.groupBy { it.id }.map { (_, references) ->
        val first = references.first()
        require(references.all { it.copy(pageIds = emptySet()) == first.copy(pageIds = emptySet()) }) { "Conflicting navigation metadata: ${first.id}" }
        first.copy(pageIds = references.flatMap { it.pageIds }.toSet()).frozen()
    })
    val search = EntrySearchIndex(this.entries, texts, entryPages, this.navigation)
    init {
        require(providers.keys.all { it.isNotBlank() })
        entries.flatMap { it.textReferences() }.forEach { texts.allTranslations(it) }
        this.navigation.flatMap { it.textReferences() }.forEach { texts.allTranslations(it) }
        this.navigation.forEach { validateDestination(it.destination) }
    }
    constructor(vararg pages: SettingsPage, texts: TextResources = TextResources.Empty, handlers: List<com.napreth.set4u.core.execution.HandlerDefinition<*>> = emptyList(), definitions: List<SettingDefinition> = emptyList()) : this(
        staticProviders(pages.toList()), definitions = definitions, entries = pages.flatMap { it.snapshot().entries }, texts = texts,
        entryPages = pages.flatMap { p -> p.entries.map { it.id to p.id } }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() },
        navigation = pages.flatMap { p -> p.navigationEntries.map { it.copy(pageIds = it.pageIds + p.id) } },
        handlers = handlers,
    )
    suspend fun describe(pageId: String, target: SettingTarget) = load(pageId, target).page
    internal suspend fun availability(pageId: String, target: SettingTarget, context: com.napreth.set4u.core.compatibility.AvailabilityContext) =
        (providers[pageId] ?: error("Unknown settings page: $pageId"))(target).assess(context)
    internal suspend fun load(pageId: String, target: SettingTarget): LoadedSettingsPage {
        val definition = (providers[pageId] ?: error("Unknown settings page: $pageId"))(target)
        val page = definition.snapshot()
        require(page.id == pageId)
        page.entries.forEach {
            entries.resolve(it); registry.resolve(it.setting)
            if (it is HandlerSettingEntry) handlers.resolve(it.sourceCapability)
        }
        texts.allTranslations(page.title)
        return LoadedSettingsPage(page, definition::onEnter, definition)
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
            override val navigationEntries = original.navigationEntries.toList()
            override fun layout(rows: PageRows) = frozen.layout(rows)
            override suspend fun onEnter(context: SettingsPageContext) = original.onEnter(context)
            override suspend fun assess(context: com.napreth.set4u.core.compatibility.AvailabilityContext) = original.assess(context)
        }
        val provider: suspend (SettingTarget) -> SettingsPage = { page }
        original.id to provider
    }
}
internal data class LoadedSettingsPage(val page: SettingsPageSnapshot, val onEnter: suspend (SettingsPageContext) -> Unit, val probe: com.napreth.set4u.core.compatibility.AvailabilityProbe)
