package com.napreth.set4u.core.settings.presentation

import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.settings.SettingsCatalog

data class AvailableEntryResult(val entry: EntrySearchResult, val capability: CapabilityAssessment)
class SettingsSearchService internal constructor(private val catalog: SettingsCatalog, private val compatibility: CompatibilityService) {
    suspend fun search(query: String, locale: String = catalog.texts.defaultLocale, includeUnsupported: Boolean = false): List<AvailableEntryResult> =
        catalog.search.search(query, locale).mapNotNull { result ->
            val capability = compatibility.assess(catalog.entries[result.entryId].setting)
            if (!includeUnsupported && capability.support is DeviceSupport.Unsupported) null else AvailableEntryResult(result, capability)
        }
}
