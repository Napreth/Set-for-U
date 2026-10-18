package com.napreth.set4u.core.settings

import com.napreth.set4u.core.localization.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.state.*
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

abstract class SettingsPage(val id: String, val title: UiText) {
    constructor(id: String, title: String) : this(id, UiText.Literal(title))
    init { require(id.isNotBlank()) }
    abstract val entries: List<SettingEntry>
    abstract fun layout(rows: PageRows): List<RowContainer>
    open val resources: List<UiText> = emptyList()
    open suspend fun onEnter(context: SettingsPageContext) { context.readAll() }
    internal fun snapshot() = SettingsPageSnapshot(id, title, Collections.unmodifiableList(ArrayList(entries)), ::layout)
}

enum class PageLoadReason { Enter, Refresh }
class SettingsPageContext internal constructor(
    val target: SettingTarget,
    val reason: PageLoadReason,
    val settings: List<SettingDefinition>,
    private val reader: suspend (SettingDefinition) -> SettingObservation,
) {
    suspend fun read(settingId: String): SettingObservation {
        currentCoroutineContext().ensureActive()
        val definition = settings.firstOrNull { it.id == settingId } ?: error("Setting is not declared: $settingId")
        return reader(definition).also { currentCoroutineContext().ensureActive() }
    }
    suspend fun readAll() { settings.forEach { read(it.id) } }
}

class SettingsPageSnapshot internal constructor(
    val id: String,
    val title: UiText,
    val entries: List<SettingEntry>,
    internal val layout: (PageRows) -> List<RowContainer>,
) {
    val settings = Collections.unmodifiableList(entries.map { it.setting }.distinctBy { it.id })
    init { require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate page entry in $id" } }
}

internal data class EntryBinding(val entry: SettingEntry, val request: RowRequest, val revision: Long, val expiresAt: Long?, val token: String, val resourceBound: Boolean)

/** Pages arrange rows; the VM retains their execution bindings separately. */
class PageRows internal constructor(
    private val page: SettingsPageSnapshot,
    private val frames: Map<String, EntrySnapshot>,
    private val texts: TextResources,
    val locale: String,
    private val generation: String,
    private val status: (String) -> SettingStatus,
) {
    internal val bindings = linkedMapOf<String, EntryBinding>()
    fun text(value: UiText) = texts.resolve(value, locale)
    fun rows(entry: SettingEntry, style: EntryStyle = EntryStyle.Default, instance: String? = null,
             unsupported: UnsupportedVisibility = UnsupportedVisibility.Show): List<Row> {
        require(page.entries.any { it === entry }) { "Undeclared page entry: ${entry.id}" }
        if (unsupported == UnsupportedVisibility.Hide && status(entry.setting.id).observation?.snapshot?.capability?.support is com.napreth.set4u.core.compatibility.DeviceSupport.Unsupported) return emptyList()
        val frame = frames[entry.id] ?: EntrySnapshot(0)
        val manual = status(entry.setting.id).observation?.snapshot?.capability?.operation as? com.napreth.set4u.core.compatibility.OperationAvailability.Manual
        if (manual != null) {
            val id = instance ?: entry.id
            bind(id, entry, RowRequest.ReadOnly, frame)
            return listOf(NavigationRow(id, text(entry.title), SettingsDestination(systemScreen = manual.destination)))
        }
        val specs = entry.rows(frame, texts, locale, style)
        return specs.map { spec ->
            val row = spec.row.copy(id = instance?.let { if (spec.row.id == entry.id) it else "$it.${spec.row.id.removePrefix(entry.id + ".")}" } ?: spec.row.id)
            val valid = when (val control = row.control) {
                RowControl.Toggle -> entry.setting.valueType == ValueType.Boolean && spec.request == RowRequest.Edit
                RowControl.Value -> spec.request == RowRequest.ReadOnly
                is RowControl.Input -> entry.setting.valueType == (if (control.integer) ValueType.Integer else ValueType.Text) && spec.request == RowRequest.Edit
                is RowControl.Choice -> entry.setting.valueType == ValueType.Choice && spec.request == RowRequest.Edit && control.options.map { it.id }.toSet() == entry.setting.choices.toSet()
                is RowControl.Action -> (spec.request as? RowRequest.Fixed)?.let { entry.setting.validationError(it.value) == null } == true
                is RowControl.Selection -> if (control.multiple) entry.setting.valueType == ValueType.MultiChoice && spec.request == RowRequest.ToggleChoice(control.choiceId)
                    else entry.setting.valueType == ValueType.Choice && spec.request == RowRequest.Fixed(SettingValue.ChoiceValue(control.choiceId))
            }
            require(valid) { "Row control and request do not match entry ${entry.id}" }
            bind(row.id, entry, spec.request, frame)
            row
        }
    }
    fun navigation(id: String, title: UiText, destination: SettingsDestination, value: SettingEntry? = null, icon: String? = null): NavigationRow {
        if (value != null) {
            require(page.entries.any { it === value })
            bind(id, value, RowRequest.ReadOnly, EntrySnapshot(0))
        }
        return NavigationRow(id, text(title), destination, icon = icon)
    }
    private fun bind(id: String, entry: SettingEntry, request: RowRequest, frame: EntrySnapshot) {
        val resourceBound = entry.dynamic || frame.rows.isNotEmpty() || frame.revision != 0L || frame.validUntilMillis != null
        require(bindings.putIfAbsent(id, EntryBinding(entry, request, frame.revision, frame.validUntilMillis, "$generation:${entry.id}:${frame.revision}:$id", resourceBound)) == null) { "Duplicate row binding: $id" }
    }
}
