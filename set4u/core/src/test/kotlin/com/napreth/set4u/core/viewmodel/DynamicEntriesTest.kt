@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.localization.UiText
import com.napreth.set4u.core.localization.TextResources
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class DynamicEntriesTest {
    @Test fun customEntrySubclassesCannotAccidentallyDisableResourceExpiry() = runTest {
        val f = WifiFixture()
        val entry = object : SettingEntry(f.definition, UiText.Literal("WLAN")) {
            override fun observe(target: com.napreth.set4u.core.session.SettingTarget) = f.frames
            override fun rows(snapshot: EntrySnapshot, texts: TextResources, locale: String, style: EntryStyle) = snapshot.rows
        }
        withSession(catalog = SettingsCatalog(testPage("wifi", "WLAN", listOf(entry)))) { session ->
            val vm = session.viewModels.settings(Target, "wifi"); vm.enter().join(); runCurrent()
            val row = vm.state.value.items.first()
            advanceTimeBy(1001)
            assertNull(vm.activate(row.id,row.token)); assertTrue(f.writes.isEmpty()); vm.close()
        }
    }
    private class WifiFixture {
        var connected = "none"
        val writes = mutableListOf<String>()
        val definition = SettingDefinition("wifi.connect", ValueType.Action, object : SettingHandler {
            override suspend fun inspect(context: SettingContext) = SettingSnapshot(SettingValue.TextValue(connected))
            override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
                connected = (desiredValue as SettingValue.ActionValue).parameters.getValue("network")
                writes += connected
            }
            override fun verify(before: SettingSnapshot, after: SettingSnapshot, desiredValue: SettingValue) =
                if (after.value == SettingValue.TextValue((desiredValue as SettingValue.ActionValue).parameters.getValue("network"))) Verification.Satisfied
                else Verification.NotSatisfied(FailureReason("Network differs"))
        }, actionParameters = mapOf("network" to ActionParameter()))
        fun frame(revision: Long, names: List<String>, expires: Long? = null) = EntrySnapshot(revision, names.mapIndexed { i, name ->
            EntryRow(SettingRow("access-point-$i", name, RowControl.Action()), RowRequest.Fixed(SettingValue.ActionValue(mapOf("network" to name))))
        }, expires)
        val frames = MutableStateFlow(frame(1, listOf("Home-WiFi", "Office-5G", "Guest"), 1000))
        val entry = DynamicSettingEntry(definition, UiText.Literal("连接 WLAN"), keywords = listOf(UiText.Literal("connect Wi-Fi")), id = "wifi.networks") { frames }
        val catalog = SettingsCatalog(testPage("wifi", "WLAN", listOf(entry)))
    }

    @Test fun threeNetworksAreRowsOfOneEntryAndCallOneParameterizedSetting() = runTest {
        val f = WifiFixture()
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "wifi")
            vm.enter().join(); runCurrent()
            assertEquals(1, session.catalog.entries.all().size)
            assertEquals(listOf("Home-WiFi", "Office-5G", "Guest"), vm.state.value.items.map { it.component.title })
            val row = vm.state.value.items[1]
            assertEquals(SettingOutcome.Verified, vm.activate(row.id, row.token)!!.await().outcome)
            assertEquals(listOf("Office-5G"), f.writes)
            assertTrue(f.catalog.search.search("Home-WiFi").isEmpty())
            assertEquals(1, f.catalog.search.search("connect Wi-Fi").size)
            vm.close()
        }
    }
    @Test fun resourceUpdatesRejectOldTokensEvenWhenTheRowIdIsReused() = runTest {
        val f = WifiFixture()
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "wifi"); vm.enter().join(); runCurrent()
            val old = vm.state.value.items.first()
            f.frames.value = f.frame(2, listOf("Replacement"), 2000); runCurrent()
            assertNull(vm.activate(old.id, old.token))
            assertNull(vm.activate(old.id))
            assertTrue(f.writes.isEmpty())
            val fresh = vm.state.value.items.single()
            vm.activate(fresh.id, fresh.token)!!.await()
            assertEquals(listOf("Replacement"), f.writes); vm.close()
        }
    }
    @Test fun expiredRowsCannotSubmitNewRequests() = runTest {
        val f = WifiFixture()
        withSession(catalog = f.catalog) { session ->
            val vm = session.viewModels.settings(Target, "wifi"); vm.enter().join(); runCurrent()
            val row = vm.state.value.items.first()
            advanceTimeBy(1001)
            assertNull(vm.activate(row.id, row.token)); assertTrue(f.writes.isEmpty()); vm.close()
        }
    }
    @Test fun queueTimeCannotExtendAResourceLease() = runTest {
        val f = WifiFixture(); val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val blocker = MemorySetting(Disabled).apply { onApply = { started.complete(Unit); release.await() } }
        val block = flagDefinition("blocking", blocker)
        val catalog = SettingsCatalog(testPage("wifi", "WLAN", listOf(f.entry, entryOf(block))))
        withSession(catalog = catalog) { session ->
            val vm = session.viewModels.settings(Target, "wifi"); vm.enter().join(); runCurrent()
            val first = session.settings.set(Target, block, Enabled); runCurrent(); started.await()
            val row = vm.state.value.items.first { it.id.startsWith("access-point") }
            val queued = vm.activate(row.id, row.token)!!; runCurrent()
            advanceTimeBy(1001); release.complete(Unit); first.await()
            assertEquals(SettingOutcome.Failed, queued.await().outcome)
            assertTrue(f.writes.isEmpty()); vm.close()
        }
    }
    @Test fun changingTheResourceWhileQueuedAlsoPreventsTheOldWrite() = runTest {
        val f = WifiFixture(); val release = CompletableDeferred<Unit>()
        val blocker = MemorySetting(Disabled).apply { onApply = { release.await() } }
        val block = flagDefinition("blocking", blocker)
        val catalog = SettingsCatalog(testPage("wifi", "WLAN", listOf(f.entry, entryOf(block))))
        withSession(catalog = catalog) { session ->
            val vm = session.viewModels.settings(Target, "wifi"); vm.enter().join(); runCurrent()
            val first = session.settings.set(Target, block, Enabled); runCurrent()
            val row = vm.state.value.items.first { it.id.startsWith("access-point") }
            val queued = vm.activate(row.id, row.token)!!; runCurrent()
            f.frames.value = f.frame(2, listOf("Replacement"), 2000); runCurrent()
            release.complete(Unit); first.await()
            assertEquals(SettingOutcome.Failed, queued.await().outcome)
            assertTrue(f.writes.isEmpty()); vm.close()
        }
    }
    @Test fun sourcesAreOwnedByThePageAndDoNotBecomeBackgroundServices() = runTest {
        val f = WifiFixture(); var active = 0; var starts = 0
        val entry = DynamicSettingEntry(f.definition, UiText.Literal("WLAN")) { flow {
            active++; starts++
            try { emit(f.frames.value); awaitCancellation() } finally { active-- }
        } }
        withSession(catalog = SettingsCatalog(testPage("wifi", "WLAN", listOf(entry)))) { session ->
            val vm = session.viewModels.settings(Target, "wifi"); vm.enter().join(); runCurrent()
            assertEquals(1, active)
            vm.refresh().join(); runCurrent(); assertEquals(1, active); assertEquals(2, starts)
            vm.close(); runCurrent(); assertEquals(0, active)
        }
    }
    @Test fun emptyScanSnapshotsLeaveTheAddEntryAndInvalidateOldActions() = runTest {
        val f = WifiFixture()
        val page = testPage("wifi", "WLAN", listOf(f.entry)) { rows -> listOf(
            RowContainer("results", rows.rows(f.entry)),
            RowContainer("actions", listOf(rows.navigation("add-network", UiText.Literal("添加网络"), SettingsDestination(systemScreen = SystemSettingsScreen.Wifi)))),
        ) }
        withSession(catalog = SettingsCatalog(page)) { session ->
            val vm = session.viewModels.settings(Target, "wifi"); vm.enter().join(); runCurrent()
            val old = vm.state.value.items.first()
            f.frames.value = f.frame(2, emptyList()); runCurrent()
            assertEquals(listOf("actions"), vm.state.value.containers.map { it.component.id })
            assertEquals(listOf("添加网络"), vm.state.value.items.map { it.component.title })
            assertNull(vm.state.value.error)
            assertEquals(1, session.catalog.entries.all().size)
            assertNull(vm.activate(old.id, old.token)); assertTrue(f.writes.isEmpty())
            f.frames.value = f.frame(3, listOf("New-Network")); runCurrent()
            assertNull(vm.activate(old.id, old.token))
            val fresh = vm.state.value.items.first()
            assertEquals(SettingOutcome.Verified, vm.activate(fresh.id, fresh.token)!!.await().outcome)
            assertEquals(listOf("New-Network"), f.writes); vm.close()
        }
    }
}
