package com.napreth.set4u.core.settings.presentation

import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.settings.SettingsCatalog
import com.napreth.set4u.core.session.*
import kotlinx.coroutines.coroutineScope

data class AvailableEntryResult(val entry: EntrySearchResult, val capability: CapabilityAssessment?, val availability: AvailabilityAssessment? = null)
class SettingsSearchService internal constructor(private val catalog: SettingsCatalog, private val compatibility: CompatibilityService, private val pages: PageAvailabilityService) {
    suspend fun search(query: String, locale: String = catalog.texts.defaultLocale, includeUnsupported: Boolean = false, target: SettingTarget = DeviceTarget()): List<AvailableEntryResult> = coroutineScope {
        val cycle = pages.cycle(this)
        catalog.search.search(query, locale).mapNotNull { result ->
            val capability = result.settingId?.let { compatibility.assess(catalog.registry[it], target) }
            val destination = result.destination
            val policy = catalog.navigation.firstOrNull { it.id == result.entryId }?.availabilityPolicy
            val availability = if (destination != null && destination.systemScreen == null && policy != NavigationAvailabilityPolicy.IgnorePage) cycle.assess(destination.pageId, destination.target ?: target) else null
            if (!includeUnsupported && (capability?.support is DeviceSupport.Unsupported || availability?.status == AvailabilityStatus.Unsupported)) null else AvailableEntryResult(result, capability, availability)
        }
    }
}
