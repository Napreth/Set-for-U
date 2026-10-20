package com.napreth.set4u.core.settings.presentation

import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.settings.SystemSettingsScreen

/** These objects contain presentation data and intents, never handlers or setting definitions. */
sealed interface Row {
    val id: String
    val title: String
    val summary: String
    val icon: String?
}

data class RowChoice(val id: String, val title: String, val summary: String = "")
sealed interface RowControl {
    data object Toggle : RowControl
    data object Value : RowControl
    data class Action(val destructive: Boolean = false) : RowControl
    data class Input(val integer: Boolean = false) : RowControl
    data class Choice(val options: List<RowChoice>) : RowControl
    data class Selection(val choiceId: String, val multiple: Boolean = false) : RowControl
}
data class SettingRow(
    override val id: String,
    override val title: String,
    val control: RowControl,
    override val summary: String = "",
    override val icon: String? = null,
    val rendererKey: String? = null,
    val detail: String = "",
) : Row

data class SettingsDestination(val pageId: String = "", val target: SettingTarget? = null, val systemScreen: SystemSettingsScreen? = null) {
    init { require(pageId.isNotBlank() != (systemScreen != null)) { "Choose a page or a system screen" } }
}
data class NavigationRow(
    override val id: String,
    override val title: String,
    val destination: SettingsDestination,
    val detail: String = "",
    override val summary: String = "",
    override val icon: String? = null,
    val availabilityPolicy: NavigationAvailabilityPolicy = NavigationAvailabilityPolicy.FollowPage,
) : Row

enum class NavigationAvailabilityPolicy { FollowPage, ShowDisabled, IgnorePage }

data class RowContainer(val id: String, val rows: List<Row>, val title: String = "", val summary: String = "")
enum class EntryStyle { Default, Choices, Dropdown, Information }
enum class UnsupportedVisibility { Hide, Show }
