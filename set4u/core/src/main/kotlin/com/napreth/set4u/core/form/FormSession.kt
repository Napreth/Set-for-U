package com.napreth.set4u.core.form

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

class FormState internal constructor(
    val values: FormValues,
    dirtyFields: Set<String> = emptySet(),
    errors: List<FormError> = emptyList(),
    val submitting: Boolean = false,
    val submitted: Boolean = false,
    val closed: Boolean = false,
) {
    val dirtyFields: Set<String> = Collections.unmodifiableSet(dirtyFields.toSet())
    val dirty get() = dirtyFields.isNotEmpty()
    val errors: List<FormError> = Collections.unmodifiableList(errors.toList())
    val editable get() = !submitting && !submitted && !closed
    override fun toString() = "FormState(dirty=$dirty, submitting=$submitting, submitted=$submitted, closed=$closed)"
}

/** Owned by the editor, not by a device observation. Closing releases the draft without cancelling a submitted device operation. */
class FormSession<R>(val definition: FormDefinition<R>) : AutoCloseable {
    private val lock = Any()
    private val initial = definition.fields.associate { it.key to it.initial }
    private var draft = initial
    private var validationRequested = false
    private val mutable = MutableStateFlow(FormState(values()))
    val state = mutable.asStateFlow()

    private fun values() = FormValues(definition.fields, draft)
    fun <T : Any> edit(field: FormField<T>, value: T): Boolean = editValue(field, value)
    fun editValue(field: FormField<*>, value: Any): Boolean = synchronized(lock) {
        require(definition.fields.any { it === field } && field.accepts(value)) { "Invalid form field or value: ${field.key}" }
        if (!state.value.editable || !field.visibleWhen(values()) || !field.enabledWhen(values())) return false
        draft = draft + (field.key to value)
        publish(if (validationRequested) errors() else emptyList())
        true
    }

    fun validate(): Boolean = synchronized(lock) {
        if (!state.value.editable) return false
        validationRequested = true
        val errors = errors()
        publish(errors)
        errors.isEmpty()
    }

    private fun visible() = definition.fields.filter { it.visibleWhen(values()) }
    private fun activeValues(): FormValues {
        val fields = visible()
        return FormValues(fields, draft.filterKeys { key -> fields.any { it.key == key } })
    }
    private fun errors(): List<FormError> {
        val visible = visible()
        val fieldErrors = visible.mapNotNull { it.error(values()) }
        if (fieldErrors.isNotEmpty()) return fieldErrors
        val errors = definition.validator.validate(activeValues())
        require(errors.all { error -> error.fieldKey == null || definition.fields.any { it.key == error.fieldKey } }) { "Unknown validation field" }
        return errors.filter { it.fieldKey == null || visible.any { field -> field.key == it.fieldKey } }
    }
    private fun publish(errors: List<FormError> = emptyList(), submitting: Boolean = false, submitted: Boolean = false) {
        mutable.value = FormState(values(), draft.keys.filter { draft[it] != initial[it] }.toSet(), errors, submitting, submitted)
    }

    /** True means the request was handed to the callback. The execution engine remains responsible for device success. */
    suspend fun submit(send: suspend (R) -> Unit): Boolean {
        val request = synchronized(lock) {
            if (!validate()) return false
            try { definition.request(activeValues()) }
            catch (_: Exception) { publish(listOf(FormError(message = "无法生成请求，请检查输入"))); return false }
                .also { publish(submitting = true) }
        }
        try {
            send(request)
            synchronized(lock) { if (!state.value.closed) publish(submitted = true) }
            return true
        } catch (exception: CancellationException) {
            synchronized(lock) { if (!state.value.closed) publish() }
            throw exception
        } catch (_: Exception) {
            // Callback exceptions may contain credentials. Never display or retain their raw text.
            synchronized(lock) { if (!state.value.closed) publish(listOf(FormError(message = "无法提交，请重新打开页面后重试"))) }
            return false
        }
    }

    fun reset(): Boolean = synchronized(lock) {
        if (!state.value.editable) return false
        draft = initial; validationRequested = false; publish(); true
    }
    override fun close() = synchronized(lock) {
        draft = emptyMap()
        mutable.value = FormState(FormValues(emptyList(), emptyMap()), closed = true)
    }
    override fun toString() = "FormSession(id=${definition.id})"
}
