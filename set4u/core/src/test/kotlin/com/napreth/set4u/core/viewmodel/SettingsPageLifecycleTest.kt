@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.*
import com.napreth.set4u.core.localization.UiText
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.state.SettingSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class SettingsPageLifecycleTest {
    @Test fun rendersBeforeInitializationAndAllowsNavigation() = runTest {
        val release = CompletableDeferred<Unit>(); val reasons = mutableListOf<PageLoadReason>()
        val entry = entryOf(flagDefinition())
        val page = object : SettingsPage("sample", "设置") {
            override val entries = listOf(entry)
            override fun layout(rows: PageRows) = listOf(RowContainer("body", rows.rows(entry) + rows.navigation("next", UiText.Literal("下一页"), SettingsDestination(id))))
            override suspend fun onEnter(context: SettingsPageContext) { reasons += context.reason; release.await(); context.readAll() }
        }
        val bridge = FakeBridge()
        withSession(bridge, SettingsCatalog(page)) { session ->
            val vm = session.viewModels.settings(Target, page.id)
            val effects = mutableListOf<SettingsEffect>()
            val watch = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
            val loading = vm.enter(); runCurrent()
            assertSame(loading, vm.enter()); assertEquals(2, vm.state.value.items.size)
            assertTrue(vm.state.value.isLoading); assertTrue(bridge.requests.isEmpty())
            vm.activate("next"); runCurrent(); assertEquals(listOf(SettingsEffect.Navigate(page.id, Target)), effects)
            release.complete(Unit); loading.join(); runCurrent()
            assertTrue(vm.state.value.items.first().isEnabled)
            vm.refresh().join(); assertEquals(listOf(PageLoadReason.Enter, PageLoadReason.Refresh), reasons)
            watch.cancel(); vm.close()
        }
    }
    @Test fun declaredEntriesCanReadWithoutProducingAnyRows() = runTest {
        val visible = MemorySetting(Disabled); val hidden = MemorySetting(Disabled)
        val a = entryOf(flagDefinition("visible", visible)); val b = entryOf(flagDefinition("hidden", hidden))
        val page = testPage("sample", "设置", listOf(a,b)) { rows -> listOf(RowContainer("body", rows.rows(a))) }
        withSession(catalog = SettingsCatalog(page)) { session ->
            val vm = session.viewModels.settings(Target, page.id)
            vm.enter().join(); runCurrent()
            assertEquals(1, hidden.reads); assertEquals(1, visible.reads)
            assertNull(vm.state.value.find("hidden")); assertEquals(2, session.state.value.size)
            vm.close()
        }
    }
    @Test fun dropdownAndOptionRowsShareAnEntryAndItsState() = runTest {
        val f = ComponentSettingsFixture(); val entry = f.backgroundEntry
        val page = testPage("sample", "设置", listOf(entry)) { rows -> listOf(
            RowContainer("summary", rows.rows(entry, EntryStyle.Dropdown, "dropdown")), RowContainer("options", rows.rows(entry, instance = "radios")),
        ) }
        withSession(catalog = SettingsCatalog(page)) { session ->
            val vm = session.viewModels.settings(Target, page.id)
            vm.enter().join(); runCurrent()
            assertEquals(1, f.background.reads)
            vm.dispatch(SettingsEvent.SelectChoice("dropdown", "restricted"))!!.join(); runCurrent()
            assertTrue(vm.state.value.find("radios.restricted")!!.selected == true)
            val option = vm.state.value.find("radios.allowed")!!
            vm.activate(option.id, option.token)!!.await(); runCurrent()
            assertEquals("完全允许后台行为", vm.state.value.find("dropdown")!!.displayValue())
            assertEquals(2, f.background.writes); vm.close()
        }
    }
    @Test fun initializationFailureKeepsThePublishedLayoutAndCanBeRetried() = runTest {
        var fail = true; val entry = entryOf(flagDefinition())
        val page = object : SettingsPage("sample", "设置") {
            override val entries = listOf(entry)
            override fun layout(rows: PageRows) = listOf(RowContainer("body", rows.rows(entry)))
            override suspend fun onEnter(context: SettingsPageContext) { if (fail) error("设备探测失败"); context.readAll() }
        }
        withSession(catalog = SettingsCatalog(page)) { session ->
            val vm = session.viewModels.settings(Target, page.id)
            vm.enter().join(); assertEquals("设备探测失败", vm.state.value.error); assertEquals(1, vm.state.value.items.size)
            fail = false; vm.refresh().join(); runCurrent()
            assertNull(vm.state.value.error); assertEquals(Disabled, vm.state.value.items.single().value); vm.close()
        }
    }
    @Test fun refreshAndCloseCancelHooksAndDiscardLateReads() = runTest {
        var loads = 0; var cancelled = 0; val releaseOld = CompletableDeferred<Unit>(); val entry = entryOf(flagDefinition())
        val page = object : SettingsPage("sample", "设置") {
            override val entries = listOf(entry)
            override fun layout(rows: PageRows) = listOf(RowContainer("body", rows.rows(entry)))
            override suspend fun onEnter(context: SettingsPageContext) {
                val n = ++loads
                try { if (n == 1) withContext(NonCancellable) { releaseOld.await() }; if (n == 3) awaitCancellation(); context.readAll() }
                finally { if (!currentCoroutineContext().isActive) cancelled++ }
            }
        }
        val bridge = FakeBridge()
        withSession(bridge, SettingsCatalog(page)) { session ->
            val vm = session.viewModels.settings(Target, page.id)
            val first = vm.enter(); runCurrent(); vm.refresh().join(); releaseOld.complete(Unit); first.join()
            assertEquals(1, bridge.requests.size); assertNull(vm.state.value.error)
            val third = vm.refresh(); runCurrent(); vm.close(); third.join()
            assertTrue(third.isCancelled); assertEquals(2, cancelled)
        }
    }
    @Test fun undeclaredEntryReferencesAndDuplicateRowsAreRejected() = runTest {
        val entry = entryOf(flagDefinition())
        val bad = testPage("sample", "设置", emptyList()) { rows -> listOf(RowContainer("body", rows.rows(entry))) }
        withSession(catalog = SettingsCatalog(bad)) { session ->
            val vm = session.viewModels.settings(Target, bad.id); vm.enter().join()
            assertNotNull(vm.state.value.error); assertTrue(vm.state.value.items.isEmpty()); vm.close()
        }
        val duplicate = testPage("sample", "设置", listOf(entry)) { rows -> listOf(RowContainer("a", rows.rows(entry)), RowContainer("b", rows.rows(entry))) }
        withSession(catalog = SettingsCatalog(duplicate)) { session ->
            val vm = session.viewModels.settings(Target, duplicate.id); vm.enter().join()
            assertNotNull(vm.state.value.error); vm.close()
        }
    }
    @Test fun theSettingStillOwnsTheCompleteAfterSequence() = runTest {
        val calls = mutableListOf<String>()
        val handler = object : SettingHandler {
            override suspend fun inspect(context: SettingContext): SettingSnapshot { calls += "inspect"; return SettingSnapshot(Disabled) }
            override fun check(before: SettingSnapshot, desiredValue: SettingValue): CheckDecision { calls += "check"; return super.check(before,desiredValue) }
            override suspend fun apply(context: SettingContext, desiredValue: SettingValue) { calls += "apply" }
            override fun verify(before: SettingSnapshot, after: SettingSnapshot, desiredValue: SettingValue): Verification { calls += "verify"; return super.verify(before,after,desiredValue) }
            override fun explain(result: SettingResult): FailureReason { calls += "explain"; return FailureReason("系统策略可能拒绝了修改", ReasonCertainty.Possible) }
        }
        val definition = flagDefinition(handler = handler)
        withSession(catalog = catalogOf(definition)) { session ->
            val vm = session.viewModels.settings(Target, "sample"); vm.enter().join(); runCurrent(); calls.clear()
            val result = vm.setValue(definition.id, Enabled)!!.await()
            assertEquals(listOf("inspect","check","apply","inspect","verify","explain"), calls)
            assertEquals(SettingOutcome.Failed,result.outcome); assertEquals(ReasonCertainty.Possible,result.reason!!.certainty); vm.close()
        }
    }
}
