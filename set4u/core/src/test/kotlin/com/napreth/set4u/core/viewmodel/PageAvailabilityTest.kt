@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.*
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.settings.network.ApplicationSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.localization.UiText
import kotlinx.coroutines.flow.flow

class PageAvailabilityTest {
    private interface Scan : Handler { fun results(): List<String> }
    @Test fun navigationRowsShareOneProbeButChooseTheirOwnPresentation() = runTest {
        var probes = 0; var enters = 0
        val destination = object : SettingsPage("detail", "Detail") {
            override val entries = emptyList<SettingEntry>()
            override fun layout(rows: PageRows) = emptyList<RowContainer>()
            override suspend fun assess(context: AvailabilityContext) = AvailabilityAssessment(AvailabilityStatus.Unsupported, "No hardware").also { probes++ }
            override suspend fun onEnter(context: SettingsPageContext) { enters++ }
        }
        val page = testPage("main", "Main", emptyList()) { listOf(RowContainer("links", listOf(
            NavigationRow("follow", "Follow", SettingsDestination("detail")),
            NavigationRow("disabled", "Disabled", SettingsDestination("detail"), availabilityPolicy = NavigationAvailabilityPolicy.ShowDisabled),
            NavigationRow("ignore", "Diagnostics", SettingsDestination("detail"), availabilityPolicy = NavigationAvailabilityPolicy.IgnorePage),
        ))) }
        withSession(catalog = SettingsCatalog(page, destination)) { session ->
            val vm = session.viewModels.settings(Target, "main"); vm.enter().join(); runCurrent()
            assertEquals(1, probes); assertNull(vm.state.value.find("follow"))
            assertFalse(vm.state.value.find("disabled")!!.isEnabled)
            assertTrue(vm.state.value.find("ignore")!!.isEnabled)
            val effects = mutableListOf<SettingsEffect>()
            val watch = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
            vm.activate("disabled"); vm.activate("ignore"); runCurrent()
            assertTrue(effects.first() is SettingsEffect.Rejected)
            assertEquals(SettingsEffect.Navigate("detail", Target), effects.last())
            val detail = session.viewModels.settings(Target, "detail"); detail.enter().join()
            assertEquals(2, probes); assertEquals(0, enters)
            assertEquals("No hardware", detail.state.value.availability?.reason)
            vm.refresh().join(); assertEquals(3, probes)
            detail.close(); vm.close(); watch.cancel()
        }
    }

    @Test fun rejectedInputHasASeparateFeedbackEventWhileLoadedPageSurvives() = runTest {
        val definition = flagDefinition()
        withSession(catalog = catalogOf(definition)) { session ->
            val vm = session.viewModels.settings(Target, "sample"); vm.enter().join(); runCurrent()
            val effects = mutableListOf<SettingsEffect>()
            val watch = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
            assertNull(vm.setValue(definition.id, SettingValue.TextValue("bad")))
            runCurrent()
            assertNotNull(vm.state.value.page); assertNull(vm.state.value.error)
            assertNotNull(vm.state.value.rejection); assertTrue(effects.single() is SettingsEffect.Rejected)
            watch.cancel(); vm.close()
        }
    }

    @Test fun pureNavigationIsSearchableAcrossLanguagesWithoutASettingDefinition() = runTest {
        val catalog = ApplicationSettings.catalog()
        withSession(catalog = catalog) { session ->
            val vpn = session.search.search("VPN", "zh-CN").single()
            assertNull(vpn.entry.settingId); assertNull(vpn.capability)
            assertEquals(SystemSettingsScreen.Vpn, vpn.entry.destination?.systemScreen)
            assertTrue(session.search.search("hotspot", "zh-CN").any { it.entry.destination?.pageId == "network.hotspot" })
            assertTrue(session.search.search("certificate", "zh-CN").any { it.entry.destination?.systemScreen == SystemSettingsScreen.InstallCertificate })
            assertEquals(10, catalog.registry.all().size)
        }
    }

    @Test fun scanDataCanBeShownWithoutThePrivilegeRequiredToConnect() = runTest {
        val scan = HandlerDefinition("wifi.scan", Scan::class, mapOf("default" to { object : Scan { override fun results() = listOf("Visible network") } }), HandlerEffect.ReadOnly, requiresBridge = false)
        val connect = SettingDefinition("wifi.connect", ValueType.Action, { error("Connection must not execute") })
        val entry = HandlerSettingEntry(connect, UiText.Literal("Networks"), sourceCapability = scan) { context -> flow {
            val names = context.handlers.invoke(scan, context.target) { handler, _ -> handler.results() }.requireCompleted()
            emit(EntrySnapshot(1, names.mapIndexed { index, name -> EntryRow(SettingRow("network.$index", name, RowControl.Value), RowRequest.ReadOnly) }))
        } }
        val page = testPage("wifi", "WLAN", listOf(entry))
        val registry = CompatibilityRegistry(emptyList(), mapOf(connect.id to CapabilityPolicy(identities = setOf(ExecutionIdentity.Root), manual = SystemSettingsScreen.Wifi)))
        val bridge = FakeBridge().apply { connectionState.value = BridgeState.Disconnected }
        withSession(bridge, SettingsCatalog(page, handlers = listOf(scan)), compatibility = registry) { session ->
            val vm = session.viewModels.settings(Target, "wifi"); vm.enter().join(); runCurrent()
            assertEquals("Visible network", vm.state.value.items.single().component.title)
            assertFalse(vm.state.value.items.single().isEnabled)
            assertTrue(bridge.writes.isEmpty()); vm.close()
        }
    }
}
