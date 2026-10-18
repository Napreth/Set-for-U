@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.state.SettingAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class SettingsComponentViewModelTest {
    @Test fun entriesAreReadOnceWhileTheirRowsCanAppearManyTimes() = runTest {
        val f = ComponentSettingsFixture()
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "application")
            vm.enter().join(); runCurrent()
            assertEquals("Do I Have", vm.state.value.page?.title)
            assertEquals(4, vm.state.value.containers.size)
            assertEquals("com.napreth.doihave", vm.state.value.find("package")!!.displayValue())
            assertEquals(1, f.notifications.reads); assertEquals(1, f.background.reads)
            assertEquals(0, f.notifications.writes)
            vm.close()
        }
    }
    @Test fun navigationDoesNotReadOrWriteAgain() = runTest {
        val f = ComponentSettingsFixture()
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "application")
            vm.enter().join()
            val effects = mutableListOf<SettingsEffect>()
            val watch = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
            vm.activate("background"); runCurrent()
            assertEquals(listOf(SettingsEffect.Navigate("background", Target)), effects)
            assertEquals(1, f.background.reads); assertEquals(0, f.background.writes)
            watch.cancel(); vm.close()
        }
    }
    @Test fun informationAndNavigationCannotSubmitMutations() = runTest {
        val f = ComponentSettingsFixture()
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "application")
            vm.enter().join()
            assertNull(vm.setValue("cache", SettingValue.TextValue("0 B")))
            assertNull(vm.setValue("background", SettingValue.ChoiceValue("restricted")))
            assertNull(vm.setValue("package", SettingValue.TextValue("another")))
            assertEquals(0, f.background.writes)
            vm.close()
        }
    }
    @Test fun toggleKeepsObservedValueAndDeduplicatesTheSameRequest() = runTest {
        val f = ComponentSettingsFixture()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.notifications.onApply = { started.complete(Unit); release.await() }
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "application")
            vm.enter().join(); runCurrent()
            val operation = vm.setValue("notifications", Enabled)!!
            runCurrent(); started.await()
            assertEquals(Disabled, vm.state.value.find("notifications")!!.value)
            assertTrue(vm.state.value.find("notifications")!!.isBusy)
            assertSame(operation, vm.setValue("notifications", Enabled))
            assertNull(vm.setValue("notifications", Disabled))
            release.complete(Unit); assertEquals(SettingOutcome.Verified, operation.await().outcome)
            runCurrent(); assertEquals(Enabled, vm.state.value.find("notifications")!!.value)
            assertEquals(1, f.notifications.writes); vm.close()
        }
    }
    @Test fun readOnlyRowsShareTheVerifiedResultAcrossPages() = runTest {
        val f = ComponentSettingsFixture()
        withSession(catalog = f.catalog) { session ->
            val first = session.viewModels.settings(Target, "application")
            val second = session.viewModels.settings(Target, "notificationDetails")
            first.enter().join(); second.enter().join()
            first.setValue("notifications", Enabled)!!.await(); runCurrent()
            assertEquals(Enabled, second.state.value.items.single().value)
            assertEquals(SettingOutcome.Verified, second.state.value.items.single().status.lastResult?.outcome)
            assertFalse(second.state.value.items.single().isInteractive)
            first.close(); second.close()
        }
    }
    @Test fun enumOptionsAreRowsOfOneEntryAndOneOperation() = runTest {
        val f = ComponentSettingsFixture()
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "background")
            vm.enter().join(); runCurrent()
            assertEquals(listOf(false, true, false), vm.state.value.items.map { it.selected })
            val row = vm.state.value.items.first()
            assertEquals("不限制应用的任何后台行为", row.component.summary)
            vm.activate(row.id, row.token)!!.await(); runCurrent()
            assertEquals(listOf(true, false, false), vm.state.value.items.map { it.selected })
            assertEquals(1, f.background.writes)
            assertNull(vm.setValue(row.id, SettingValue.ChoiceValue("restricted")))
            vm.close()
        }
    }
    @Test fun multiChoiceRowsPreserveOtherSelections() = runTest {
        val f = ComponentSettingsFixture()
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "channels")
            vm.enter().join(); runCurrent()
            val row = vm.state.value.items.first { it.id.endsWith("updates") }
            vm.activate(row.id, row.token)!!.await(); runCurrent()
            assertEquals(SettingValue.MultiChoiceValue(setOf("messages", "updates")), vm.state.value.items.first().value)
            vm.activate(row.id, row.token)!!.await(); runCurrent()
            assertEquals(SettingValue.MultiChoiceValue(setOf("messages")), vm.state.value.items.first().value)
            vm.close()
        }
    }
    @Test fun invalidInputNeverReachesTheHandler() = runTest {
        val f = ComponentSettingsFixture()
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "application")
            vm.enter().join(); runCurrent()
            assertNull(vm.setValue("timeout", SettingValue.IntegerValue(0)))
            assertNull(vm.setValue("timeout", Enabled))
            assertEquals(0, f.input.writes)
            vm.setValue("timeout", SettingValue.IntegerValue(20))!!.await()
            assertEquals(1, f.input.writes); vm.close()
        }
    }
    @Test fun actionVerificationUpdatesItsReadOnlyPresentation() = runTest {
        val f = ComponentSettingsFixture()
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "application")
            vm.enter().join(); runCurrent()
            val row = vm.state.value.find("clearCache")!!
            assertEquals(SettingOutcome.Verified, vm.activate(row.id, row.token)!!.await().outcome)
            runCurrent(); assertEquals(SettingValue.TextValue("0 B"), vm.state.value.find("cache")!!.value)
            assertEquals(1, f.clearCalls); vm.close()
        }
    }
    @Test fun unknownValuesAndReadOnlyStateCannotBeChanged() = runTest {
        val f = ComponentSettingsFixture().apply { notifications.value = null; notifications.access = SettingAccess.ReadOnly }
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "application")
            vm.enter().join(); runCurrent()
            assertNull(vm.state.value.find("notifications")!!.value)
            assertFalse(vm.state.value.find("notifications")!!.isEnabled)
            assertNull(vm.setValue("notifications", Enabled)); assertEquals(0, f.notifications.writes)
            vm.close()
        }
    }
}
