package com.napreth.set4u.core.settings.presentation

import com.napreth.set4u.core.localization.*
import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.settings.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Collections

data class EntryChoice(val id: String, val title: UiText, val summary: UiText = UiText.Literal(""))
sealed interface RowRequest {
    data object Edit : RowRequest
    data class Fixed(val value: SettingValue) : RowRequest
    data class ToggleChoice(val choiceId: String) : RowRequest
    data object ReadOnly : RowRequest
}

/** A binding belongs to the entry/VM. Its Row is a separate, presentation-only object. */
data class EntryRow(val row: SettingRow, val request: RowRequest = RowRequest.Edit)
class EntrySnapshot(val revision: Long, rows: List<EntryRow> = emptyList(), val validUntilMillis: Long? = null) {
    val rows: List<EntryRow> = Collections.unmodifiableList(ArrayList(rows))
    init {
        require(revision >= 0)
        require(rows.size <= 4096) { "Too many dynamic rows" }
        require(rows.map { it.row.id }.distinct().size == rows.size)
    }
}

open class SettingEntry(
    setting: SettingDefinition,
    val title: UiText,
    val summary: UiText = UiText.Literal(""),
    choices: List<EntryChoice> = emptyList(),
    keywords: List<UiText> = emptyList(),
    resources: List<UiText> = emptyList(),
    val id: String = setting.id,
) {
    val setting: SettingDefinition = setting.copy(choices = Collections.unmodifiableList(ArrayList(setting.choices)),
        operationFunctions = Collections.unmodifiableMap(LinkedHashMap(setting.operationFunctions)),
        actionParameters = Collections.unmodifiableMap(LinkedHashMap(setting.actionParameters)))
    val choices: List<EntryChoice> = Collections.unmodifiableList(ArrayList(choices))
    val keywords: List<UiText> = Collections.unmodifiableList(ArrayList(keywords))
    val resources: List<UiText> = Collections.unmodifiableList(ArrayList(resources))
    open val dynamic: Boolean = false
    init {
        require(id.isNotBlank()) { "Entry ID must not be blank" }
        require(this.choices.map { it.id }.distinct().size == this.choices.size)
        require(this.choices.map { it.id }.toSet() == setting.choices.toSet()) { "Entry choices must match setting choices: $id" }
    }

    /** Collection is owned by the visible page, and is cancelled on refresh or closure. */
    open fun observe(target: SettingTarget): Flow<EntrySnapshot> = flowOf(EntrySnapshot(0))

    open fun rows(snapshot: EntrySnapshot, texts: TextResources, locale: String, style: EntryStyle): List<EntryRow> {
        val title = texts.resolve(title, locale)
        val summary = texts.resolve(summary, locale)
        val options = choices.map { RowChoice(it.id, texts.resolve(it.title, locale), texts.resolve(it.summary, locale)) }
        if (style == EntryStyle.Information) return listOf(EntryRow(SettingRow(id, title, RowControl.Value, summary), RowRequest.ReadOnly))
        if ((setting.valueType == ValueType.Choice || setting.valueType == ValueType.MultiChoice) && style != EntryStyle.Dropdown) {
            return options.map { option -> EntryRow(SettingRow("$id.${option.id}", option.title, RowControl.Selection(option.id, setting.valueType == ValueType.MultiChoice), option.summary),
                if (setting.valueType == ValueType.MultiChoice) RowRequest.ToggleChoice(option.id) else RowRequest.Fixed(SettingValue.ChoiceValue(option.id))) }
        }
        val control = when (setting.valueType) {
            ValueType.Boolean -> RowControl.Toggle
            ValueType.Integer -> RowControl.Input(integer = true)
            ValueType.Text -> RowControl.Input()
            ValueType.Choice -> RowControl.Choice(options)
            ValueType.MultiChoice -> error("Multi-choice entries require selection rows")
            ValueType.Action -> RowControl.Action()
        }
        return listOf(EntryRow(SettingRow(id, title, control, summary), if (setting.valueType == ValueType.Action) RowRequest.Fixed(SettingValue.Action) else RowRequest.Edit))
    }

    fun textReferences(): List<UiText> = listOf(title, summary) + keywords + resources + choices.flatMap { listOf(it.title, it.summary) }
}

class DynamicSettingEntry(
    setting: SettingDefinition,
    title: UiText,
    keywords: List<UiText> = emptyList(),
    resources: List<UiText> = emptyList(),
    id: String = setting.id,
    private val source: (SettingTarget) -> Flow<EntrySnapshot>,
) : SettingEntry(setting, title, keywords = keywords, resources = resources, id = id) {
    override val dynamic = true
    override fun observe(target: SettingTarget) = source(target)
    override fun rows(snapshot: EntrySnapshot, texts: TextResources, locale: String, style: EntryStyle): List<EntryRow> {
        require(style == EntryStyle.Default)
        return snapshot.rows
    }
}
