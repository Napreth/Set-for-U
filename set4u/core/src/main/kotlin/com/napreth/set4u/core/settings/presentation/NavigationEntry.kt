package com.napreth.set4u.core.settings.presentation

import com.napreth.set4u.core.localization.UiText

/** Searchable navigation metadata, with no invented SettingDefinition or Handler. */
data class NavigationEntry(
    val id: String,
    val title: UiText,
    val destination: SettingsDestination,
    val keywords: List<UiText> = emptyList(),
    val summary: UiText = UiText.Literal(""),
    val pageIds: Set<String> = emptySet(),
    val icon: String? = null,
    val availabilityPolicy: NavigationAvailabilityPolicy = NavigationAvailabilityPolicy.FollowPage,
) {
    init { require(id.isNotBlank()) }
    fun textReferences() = listOf(title, summary) + keywords
    internal fun frozen() = copy(keywords = java.util.Collections.unmodifiableList(ArrayList(keywords)), pageIds = java.util.Collections.unmodifiableSet(LinkedHashSet(pageIds)))
}
