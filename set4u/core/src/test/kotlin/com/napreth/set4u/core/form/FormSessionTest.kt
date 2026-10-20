package com.napreth.set4u.core.form

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FormSessionTest {
    @Test fun hiddenDraftIsPreservedButNotValidatedOrSubmitted() = runTest {
        val mode = ChoiceField("mode", "Mode", "manual", listOf(FormOption("manual", "Manual"), FormOption("none", "None")))
        val host = TextField("host", "Host", visibleWhen = { it[mode] == "manual" }, validate = { if (it.isBlank()) "Host required" else null })
        val form = FormDefinition("proxy", "Proxy", listOf(mode, host)) { v -> if (v.contains(host)) v[host] else "disabled" }
        val session = FormSession(form)
        assertFalse(session.validate()); assertEquals("host", session.state.value.errors.single().fieldKey)
        session.edit(host, "draft.local"); assertTrue(session.state.value.errors.isEmpty())
        session.edit(mode, "none"); assertEquals("draft.local", session.state.value.values[host])
        var result = ""
        assertTrue(session.submit { result = it }); assertEquals("disabled", result)
        val another = FormSession(form)
        another.edit(host, "retained.local"); another.edit(mode, "none"); another.edit(mode, "manual")
        assertEquals("retained.local", another.state.value.values[host])
        another.reset(); assertFalse(another.state.value.dirty); assertEquals("", another.state.value.values[host])
    }

    @Test fun crossFieldRulesAndDisabledFieldsUseTheSameDraft() {
        val edit = ToggleField("edit", "Edit", true)
        val start = TextField("start", "Start", "2", integer = true, enabledWhen = { it[edit] })
        val end = TextField("end", "End", "1", integer = true)
        val session = FormSession(FormDefinition("range", "Range", listOf(edit, start, end), validator = FormValidator {
            if (it[start].toInt() > it[end].toInt()) listOf(FormError("end", "End precedes start")) else emptyList()
        }) { it[end].toInt() - it[start].toInt() })
        assertFalse(session.validate()); assertEquals("End precedes start", session.state.value.errors.single().message)
        session.edit(end, "3"); assertTrue(session.state.value.errors.isEmpty())
        session.edit(edit, false); assertFalse(session.edit(start, "8")); assertTrue(session.validate())
    }

    @Test fun oneSubmissionLocksEditingAndRejectsDoubleClicks() = runTest {
        val field = TextField("name", "Name", "initial")
        val session = FormSession(FormDefinition("name", "Name", listOf(field)) { it[field] })
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) { session.submit { calls++; gate.await() } }
        assertTrue(session.state.value.submitting)
        assertFalse(session.edit(field, "changed")); assertFalse(session.reset())
        assertFalse(session.submit { calls++ }); assertEquals(1, calls)
        gate.complete(Unit); assertTrue(first.await()); assertTrue(session.state.value.submitted)
        assertFalse(session.submit { calls++ }); assertEquals(1, calls)
    }

    @Test fun cancellationAndCallbackFailureReleaseSubmissionWithoutLeakingSecrets() = runTest {
        val password = PasswordField("password", "Password")
        val form = FormDefinition("secret", "Secret", listOf(password)) { it[password] }
        val session = FormSession(form)
        val secret = "Only-in-the-editor"
        session.edit(password, secret)
        assertFalse(session.submit { throw IllegalStateException(secret) })
        val printed = listOf(password, form, session, session.state.value, session.state.value.values, session.state.value.errors.single()).joinToString()
        assertFalse(printed.contains(secret)); assertFalse(session.state.value.submitting)
        val running = launch(start = CoroutineStart.UNDISPATCHED) { session.submit { awaitCancellation() } }
        assertTrue(session.state.value.submitting); running.cancelAndJoin()
        assertTrue(session.state.value.editable)
        session.close(); assertFalse(session.state.value.values.contains(password)); assertTrue(session.state.value.closed)
        assertFalse(session.submit { fail("Closed editor submitted") })
        assertFalse(session.edit(password, "another")); assertFalse(session.reset())
    }

    @Test fun closingWhileSubmittingDoesNotReviveTheDraftOrCancelTheCallback() = runTest {
        val text = TextField("text", "Text")
        val session = FormSession(FormDefinition("test", "Test", listOf(text)) { it[text] })
        val gate = CompletableDeferred<Unit>()
        val running = async(start = CoroutineStart.UNDISPATCHED) { session.submit { gate.await() } }
        session.close(); gate.complete(Unit); assertTrue(running.await())
        assertTrue(session.state.value.closed); assertFalse(session.state.value.values.contains(text))
    }

    @Test fun definitionsAndEditorsRejectAmbiguousOrForeignFields() {
        val text = TextField("same", "Text")
        assertThrows(IllegalArgumentException::class.java) { FormDefinition("test", "Test", listOf(text, TextField("same", "Other"))) { Unit } }
        assertThrows(IllegalArgumentException::class.java) { ChoiceField("choice", "Choice", "missing", listOf(FormOption("one", "One"))) }
        val fields = mutableListOf<FormField<*>>(text)
        val definition = FormDefinition("test", "Test", fields) { it[text] }
        fields.clear(); assertEquals(1, definition.fields.size)
        val session = FormSession(definition)
        assertThrows(IllegalArgumentException::class.java) { session.edit(TextField("same", "Fake"), "value") }
        assertThrows(IllegalArgumentException::class.java) { session.editValue(text, true) }
        assertThrows(UnsupportedOperationException::class.java) { (definition.fields as MutableList).clear() }
    }
}
