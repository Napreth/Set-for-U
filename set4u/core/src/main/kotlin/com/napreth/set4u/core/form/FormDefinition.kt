package com.napreth.set4u.core.form

import java.util.Collections

/** Labels are resolved by the host; option IDs and request values never depend on the UI language. */
abstract class FormField<T : Any>(
    val key: String,
    val title: String,
    val initial: T,
    val visibleWhen: (FormValues) -> Boolean = { true },
    val enabledWhen: (FormValues) -> Boolean = { true },
    val rendererKey: String? = null,
    private val validate: (T) -> String? = { null },
) {
    init { require(key.isNotBlank()) }
    abstract fun accepts(value: Any): Boolean
    internal fun error(values: FormValues) = validate(values[this])?.let { FormError(key, it) }
    override fun toString() = "FormField(key=$key)"
}

open class TextField(
    key: String, title: String, initial: String = "", val secret: Boolean = false, val integer: Boolean = false,
    visibleWhen: (FormValues) -> Boolean = { true }, enabledWhen: (FormValues) -> Boolean = { true },
    rendererKey: String? = null, validate: (String) -> String? = { null },
) : FormField<String>(key, title, initial, visibleWhen, enabledWhen, rendererKey, validate) {
    override fun accepts(value: Any) = value is String
}

class PasswordField(key: String, title: String, visibleWhen: (FormValues) -> Boolean = { true }, enabledWhen: (FormValues) -> Boolean = { true },
    rendererKey: String? = null, validate: (String) -> String? = { null }) :
    TextField(key, title, secret = true, visibleWhen = visibleWhen, enabledWhen = enabledWhen, rendererKey = rendererKey, validate = validate)

class ToggleField(
    key: String, title: String, initial: Boolean = false,
    visibleWhen: (FormValues) -> Boolean = { true }, enabledWhen: (FormValues) -> Boolean = { true }, rendererKey: String? = null,
    val summary: String = "",
) : FormField<Boolean>(key, title, initial, visibleWhen, enabledWhen, rendererKey) {
    override fun accepts(value: Any) = value is Boolean
}

data class FormOption(val id: String, val title: String)
enum class ChoicePresentation { Dropdown, Radio }
class ChoiceField(
    key: String, title: String, initial: String, options: List<FormOption>,
    val presentation: ChoicePresentation = ChoicePresentation.Dropdown,
    visibleWhen: (FormValues) -> Boolean = { true }, enabledWhen: (FormValues) -> Boolean = { true }, rendererKey: String? = null,
) : FormField<String>(key, title, initial, visibleWhen, enabledWhen, rendererKey) {
    val options: List<FormOption> = Collections.unmodifiableList(options.toList())
    init { require(options.isNotEmpty() && options.map { it.id }.distinct().size == options.size && options.all { it.id.isNotBlank() }); require(accepts(initial)) }
    override fun accepts(value: Any) = value is String && options.any { it.id == value }
}

/** Immutable draft or submission values. A request builder cannot read a hidden field. */
class FormValues internal constructor(fields: List<FormField<*>>, values: Map<String, Any>) {
    private val fields = fields.associateBy { it.key }
    private val values = values.toMap()
    operator fun <T : Any> get(field: FormField<T>): T {
        require(fields[field.key] === field && values.containsKey(field.key)) { "Unknown or inactive form field: ${field.key}" }
        @Suppress("UNCHECKED_CAST")
        return values.getValue(field.key) as T
    }
    fun contains(field: FormField<*>) = fields[field.key] === field && values.containsKey(field.key)
    override fun toString() = "FormValues(fields=${values.keys})"
}

class FormError(val fieldKey: String? = null, val message: String) {
    override fun toString() = "FormError(field=$fieldKey)"
}
fun interface FormValidator { fun validate(values: FormValues): List<FormError> }

/** A form builds a typed request; it does not execute or establish device permissions. */
class FormDefinition<R>(
    val id: String,
    val title: String,
    fields: List<FormField<*>>,
    val summary: String = "",
    internal val validator: FormValidator = FormValidator { emptyList() },
    internal val request: (FormValues) -> R,
) {
    val fields: List<FormField<*>> = Collections.unmodifiableList(fields.toList())
    init {
        require(id.isNotBlank() && fields.isNotEmpty())
        require(fields.map { it.key }.distinct().size == fields.size) { "Duplicate form field" }
        require(fields.all { it.accepts(it.initial) }) { "Invalid initial form value" }
    }
    override fun toString() = "FormDefinition(id=$id, fields=${fields.map { it.key }})"
}
