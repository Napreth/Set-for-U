package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.state.*

data class SettingUiItem(val component: Row, val token: String, val status: SettingStatus = SettingStatus(), val bound: Boolean = false) {
    val id get() = component.id
    val value: SettingValue? get() = status.observation?.snapshot?.value
    val isBusy get() = status.isChanging
    val isInteractive get() = component is NavigationRow || (component as? SettingRow)?.control != RowControl.Value
    val isEnabled get() = component is NavigationRow || (bound && !isBusy && status.observation?.snapshot?.access == SettingAccess.Writable &&
        ((component as? SettingRow)?.control != RowControl.Toggle || value is SettingValue.BooleanValue))
    val selected: Boolean? get() {
        val control = (component as? SettingRow)?.control as? RowControl.Selection ?: return null
        return when (val current = value) { is SettingValue.ChoiceValue -> current.id == control.choiceId; is SettingValue.MultiChoiceValue -> control.choiceId in current.ids; else -> null }
    }
}
data class SettingUiContainer(val component: RowContainer, val items: List<SettingUiItem>)
data class SettingsPageInfo(val id: String, val title: String)
data class SettingsUiState(val isLoading: Boolean = false, val page: SettingsPageInfo? = null, val containers: List<SettingUiContainer> = emptyList(), val error: String? = null) {
    val items: List<SettingUiItem> get() = containers.flatMap { it.items }
    fun find(id: String) = items.firstOrNull { it.id == id }
}
sealed interface SettingsEvent {
    data class Activate(val componentId: String, val token: String? = null) : SettingsEvent
    data class ChangeValue(val componentId: String, val desiredValue: SettingValue, val token: String? = null) : SettingsEvent
    data class SelectChoice(val componentId: String, val choiceId: String, val token: String? = null) : SettingsEvent
    data object Refresh : SettingsEvent
}
sealed interface SettingsEffect {
    data class Navigate(val pageId: String, val target: SettingTarget) : SettingsEffect
    data class OpenSystemSettings(val screen: SystemSettingsScreen, val target: SettingTarget) : SettingsEffect
}
