package com.napreth.set4u.ui.form

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.napreth.set4u.core.form.*
import com.napreth.set4u.ui.*

class FormFieldScope internal constructor(val field: FormField<*>, val state: FormState, val enabled: Boolean, private val change: (Any) -> Unit) {
    val value get() = state.values[field]
    fun changeValue(value: Any) { if (enabled) change(value) }
    override fun toString() = "FormFieldScope(field=${field.key}, enabled=$enabled)"
}
typealias FormFieldRenderer = @Composable (FormFieldScope, @Composable () -> Unit) -> Unit

/** Keys belong to one form; standard field types keep their default renderer as a fallback. */
class FormRenderers(forms: Map<String, Map<String, FormFieldRenderer>>) {
    private val forms = forms.mapValues { it.value.toMap() }
    internal fun find(formId: String, field: FormField<*>) = field.rendererKey?.let { forms[formId]?.get(it) }
    companion object { val Empty = FormRenderers(emptyMap()) }
}

/** No dialog or page dependency: the same session can be hosted in either position. */
@Composable
fun FormRenderer(session: FormSession<*>, modifier: Modifier = Modifier, renderers: FormRenderers = FormRenderers.Empty) {
    val state by session.state.collectAsState()
    val t = LocalStrings.current::text
    if (state.closed) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (session.definition.summary.isNotBlank()) ThemeText(t(session.definition.summary), size = 14)
        session.definition.fields.filter { it.visibleWhen(state.values) }.forEach { field -> key(field.key) {
            val enabled = state.editable && field.enabledWhen(state.values)
            val scope = FormFieldScope(field, state, enabled) { session.editValue(field, it) }
            val default: @Composable () -> Unit = {
                when (field) {
                    is TextField -> ThemeInput(state.values[field], { scope.changeValue(it) }, t(field.title), integer = field.integer, secret = field.secret, enabled = enabled)
                    is ToggleField -> ThemeRow(t(field.title), summary = t(field.summary), action = if (enabled) ({ scope.changeValue(!state.values[field]) }) else null) {
                        ThemeSwitch(t(field.title), state.values[field], enabled) { scope.changeValue(!state.values[field]) }
                    }
                    is ChoiceField -> {
                        val selected = field.options.indexOfFirst { it.id == state.values[field] }.coerceAtLeast(0)
                        if (field.presentation == ChoicePresentation.Dropdown) ThemeDropdownRow(t(field.title), field.options.map { t(it.title) }, selected, enabled) { scope.changeValue(field.options[it].id) }
                        else Column {
                            ThemeText(t(field.title), size = 15)
                            field.options.forEachIndexed { index, option ->
                                ThemeRow(t(option.title), action = if (enabled) ({ scope.changeValue(option.id) }) else null) { ThemeValue(if (index == selected) "✓" else "") }
                            }
                        }
                    }
                    else -> ThemeText(t("此表单组件暂不可用"), size = 14)
                }
            }
            val renderer = renderers.find(session.definition.id, field)
            if (renderer == null) default() else renderer(scope, default)
            state.errors.filter { it.fieldKey == field.key }.forEach { issue -> FormErrorText(t(issue.message)) }
        } }
        state.errors.filter { it.fieldKey == null }.forEach { FormErrorText(t(it.message)) }
    }
}

@Composable
private fun FormErrorText(message: String) = ThemeText(message, Modifier.semantics { error(message) }, size = 13, color = MaterialTheme.colorScheme.error)
