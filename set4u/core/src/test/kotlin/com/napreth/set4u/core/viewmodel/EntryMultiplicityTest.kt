@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.*
import com.napreth.set4u.core.execution.SettingOutcome
import com.napreth.set4u.core.localization.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class EntryMultiplicityTest {
    @Test fun differentEntriesShareReadsStateAndInFlightWritesAcrossPages() = runTest {
        val release = CompletableDeferred<Unit>()
        val memory = MemorySetting(Disabled).apply { onApply = { release.await() } }
        val definition = flagDefinition(handler = memory)
        val primary = SettingEntry(definition, UiText.Literal("Primary"), id = "flag.primary")
        val secondary = SettingEntry(definition, UiText.Literal("Secondary"), id = "flag.secondary")
        val catalog = SettingsCatalog(testPage("main", "Main", listOf(primary, secondary)), testPage("details", "Details", listOf(secondary)))
        withSession(catalog = catalog) { session ->
            val main = session.viewModels.settings(Target, "main")
            val details = session.viewModels.settings(Target, "details")
            main.enter().join(); runCurrent()
            assertEquals(1, memory.reads)
            assertEquals(listOf(Disabled, Disabled), main.state.value.items.map { it.value })
            details.enter().join(); runCurrent()
            val first = main.setValue(primary.id, Enabled)!!
            assertSame(first, details.setValue(secondary.id, Enabled))
            runCurrent()
            assertTrue(main.state.value.items.all { it.isBusy })
            assertTrue(details.state.value.items.single().isBusy)
            release.complete(Unit)
            assertEquals(SettingOutcome.Verified, first.await().outcome)
            runCurrent()
            assertEquals(listOf(Enabled, Enabled), main.state.value.items.map { it.value })
            assertEquals(Enabled, details.state.value.items.single().value)
            assertEquals(1, memory.writes)
            main.close(); details.close()
        }
    }

    @Test fun separateDropdownAndSelectionEntriesUseOneChoiceSetting() = runTest {
        val fixture = ComponentSettingsFixture()
        val original = fixture.backgroundEntry
        val dropdown = SettingEntry(original.setting, UiText.Literal("Summary"), choices = original.choices, id = "background.summary")
        val options = SettingEntry(original.setting, UiText.Literal("Options"), choices = original.choices, id = "background.options")
        val page = testPage("sample", "Settings", listOf(dropdown, options)) { rows -> listOf(
            RowContainer("summary", rows.rows(dropdown, EntryStyle.Dropdown)),
            RowContainer("options", rows.rows(options)),
        ) }
        withSession(catalog = SettingsCatalog(page)) { session ->
            val vm = session.viewModels.settings(Target, page.id)
            vm.enter().join(); runCurrent()
            assertEquals(1, fixture.background.reads)
            vm.setValue(dropdown.id, SettingValue.ChoiceValue("restricted"))!!.await(); runCurrent()
            assertTrue(vm.state.value.find("${options.id}.restricted")!!.selected == true)
            val allowed = vm.state.value.find("${options.id}.allowed")!!
            vm.activate(allowed.id, allowed.token)!!.await(); runCurrent()
            assertEquals(SettingValue.ChoiceValue("allowed"), vm.state.value.find(dropdown.id)!!.value)
            assertEquals(2, fixture.background.writes)
            vm.close()
        }
    }

    @Test fun anEmptyEntryLeavesOtherPageEntriesUsable() = runTest {
        val memory = MemorySetting(Disabled)
        val entry = object : SettingEntry(flagDefinition(handler = memory), UiText.Literal("Empty")) {
            override fun rows(snapshot: EntrySnapshot, texts: TextResources, locale: String, style: EntryStyle) = emptyList<EntryRow>()
        }
        val visible = entryOf(flagDefinition("visible"))
        withSession(catalog = SettingsCatalog(testPage("sample", "Settings", listOf(entry, visible)))) { session ->
            val vm = session.viewModels.settings(Target, "sample")
            vm.enter().join(); runCurrent()
            assertNull(vm.state.value.error)
            assertEquals(listOf(visible.id), vm.state.value.items.map { it.id })
            assertTrue(vm.state.value.items.single().isEnabled)
            assertNull(vm.setValue(entry.id, Enabled))
            assertEquals(0, memory.writes)
            assertEquals(SettingOutcome.Verified, vm.setValue(visible.id, Enabled)!!.await().outcome)
            vm.close()
        }
    }
}
