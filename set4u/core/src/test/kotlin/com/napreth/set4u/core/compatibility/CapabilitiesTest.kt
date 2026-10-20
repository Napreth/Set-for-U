@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.*
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.execution.SettingOutcome
import com.napreth.set4u.core.localization.UiText
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.state.SettingAccess
import com.napreth.set4u.core.viewmodel.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class CapabilitiesTest {
    @Test fun entrySourcesCannotRunBeforeTheirCapabilityCheck() = runTest {
        val flag = flagDefinition(); var collections = 0
        val entry = object : SettingEntry(flag,UiText.Literal("Root setting")) {
            override fun observe(target: com.napreth.set4u.core.session.SettingTarget) = flowOf(EntrySnapshot(0)).also { collections++ }
        }
        val policy = CapabilityPolicy(identities = setOf(ExecutionIdentity.Root), manual = SystemSettingsScreen.Wifi)
        withSession(catalog = SettingsCatalog(testPage("sample","Settings",listOf(entry))), compatibility = registry(flag.id,policy)) { session ->
            val vm = session.viewModels.settings(Target,"sample"); vm.enter().join(); runCurrent()
            assertEquals(0,collections)
            assertTrue(vm.state.value.items.single().component is NavigationRow)
            vm.close()
        }
    }
    private fun registry(id: String, policy: CapabilityPolicy) = CompatibilityRegistry(listOf(CompatibilityRule("test", id, DeviceCondition.Always, RuleDecision.Use("default"))), mapOf(id to policy))
    @Test fun anExplicitRomExclusionIsDifferentFromAnUnimplementedBackend() = runTest {
        val flag = flagDefinition()
        val excluded = CompatibilityRegistry(listOf(CompatibilityRule("rom.removed", flag.id, DeviceCondition.Always, RuleDecision.Unsupported("Removed from this ROM"))))
        withSession(catalog = catalogOf(flag), compatibility = excluded) { session ->
            val assessment = session.compatibility.assess(flag)
            assertEquals(DeviceSupport.Supported, assessment.support)
            assertTrue(assessment.operation is OperationAvailability.Unavailable)
        }
        val missing = CompatibilityRegistry(listOf(CompatibilityRule("newer", flag.id, DeviceCondition.IntegerRange(DeviceFields.Sdk, 40), RuleDecision.Use("default"))), mapOf(flag.id to CapabilityPolicy(manual = SystemSettingsScreen.Wifi)))
        withSession(catalog = catalogOf(flag), compatibility = missing, deviceProfileSource = DeviceProfileSource { DeviceProfile(mapOf("ro.build.version.sdk" to "35")) }) { session ->
            val capability = session.compatibility.assess(flag)
            assertEquals(DeviceSupport.Supported, capability.support)
            assertTrue(capability.operation is OperationAvailability.Manual)
        }
    }
    @Test fun rootAuthorizationIsNotADeviceSupportFactAndIsRecheckedBeforeWriting() = runTest {
        val bridge = FakeBridge().apply { testUid = 0 }
        val flag = flagDefinition()
        var probes = 0
        val policy = CapabilityPolicy(SupportProbe { probes++; DeviceSupport.Supported }, setOf(ExecutionIdentity.Root))
        withSession(bridge, catalogOf(flag), compatibility = registry(flag.id, policy), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
            assertTrue(session.compatibility.assess(flag).operation is OperationAvailability.Direct)
            bridge.onExecute = { request -> bridge.testUid = 2000; bridge.respond(request) }
            val result = session.settings.set(Target, flag, Enabled).await()
            assertEquals(SettingOutcome.Failed, result.outcome); assertTrue(bridge.writes.isEmpty())
            val blocked = session.compatibility.assess(flag)
            assertEquals(DeviceSupport.Supported, blocked.support)
            assertTrue(blocked.operation is OperationAvailability.RequiresPrivilege)
            assertEquals(1, probes)
        }
    }
    @Test fun aSystemFallbackDoesNotPretendTheHardwareIsMissing() = runTest {
        val flag = flagDefinition(); val entry = SettingEntry(flag, UiText.Literal(flag.id), id = "wifi.fallback")
        val policy = CapabilityPolicy(identities = setOf(ExecutionIdentity.Root), manual = SystemSettingsScreen.Wifi)
        withSession(catalog = SettingsCatalog(testPage("sample", "WLAN", listOf(entry))), compatibility = registry(flag.id, policy)) { session ->
            val vm = session.viewModels.settings(Target, "sample"); vm.enter().join(); runCurrent()
            assertTrue(vm.state.value.items.single().component is NavigationRow)
            assertEquals(SettingAccess.Manual, vm.state.value.items.single().status.observation?.snapshot?.access)
            val effects = mutableListOf<SettingsEffect>()
            val watch = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
            val row = vm.state.value.items.single(); vm.activate(row.id, row.token); runCurrent()
            assertEquals(listOf(SettingsEffect.OpenSystemSettings(SystemSettingsScreen.Wifi, Target)), effects)
            assertEquals(SettingOutcome.Failed, session.settings.set(Target, flag, Enabled).await().outcome)
            watch.cancel(); vm.close()
        }
    }
    @Test fun hardwareVisibilityIsChosenByThePage() = runTest {
        val flag = flagDefinition(); val entry = SettingEntry(flag, UiText.Literal(flag.id), id = "nfc.hardware")
        val hidden = testPage("hidden", "Settings", listOf(entry)) { p -> listOf(RowContainer("body", p.rows(entry, unsupported = UnsupportedVisibility.Hide))) }
        val shown = testPage("shown", "Diagnostics", listOf(entry)) { p -> listOf(RowContainer("body", p.rows(entry))) }
        val policy = CapabilityPolicy(SupportProbe { DeviceSupport.Unsupported("No NFC hardware") })
        withSession(catalog = SettingsCatalog(hidden,shown), compatibility = registry(flag.id, policy), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
            val a = session.viewModels.settings(Target,"hidden"); val b = session.viewModels.settings(Target,"shown")
            a.enter().join(); b.enter().join(); runCurrent()
            assertTrue(a.state.value.items.isEmpty()); assertEquals(1,b.state.value.items.size)
            assertFalse(b.state.value.items.single().isEnabled)
            assertTrue(session.search.search(flag.id).isEmpty())
            assertEquals(1, session.search.search(flag.id, includeUnsupported = true).size)
            a.close(); b.close()
        }
    }
    @Test fun uncertainProbesAreNotCachedAsUnsupported() = runTest {
        val flag = flagDefinition(); var known = false
        val policy = CapabilityPolicy(SupportProbe { if (known) DeviceSupport.Supported else DeviceSupport.Unknown("Probe unavailable") })
        withSession(catalog = catalogOf(flag), compatibility = registry(flag.id,policy), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
            assertTrue(session.compatibility.assess(flag).support is DeviceSupport.Unknown)
            known = true
            assertEquals(DeviceSupport.Supported,session.compatibility.assess(flag).support)
            assertEquals(SettingAccess.Writable,session.settings.read(Target,flag).snapshot.access)
        }
    }
    @Test fun permissionRequirementsHaveTheirOwnAvailabilityResult() = runTest {
        val flag = flagDefinition()
        val policy = CapabilityPolicy(permissions = setOf("android.permission.BLUETOOTH_SCAN"))
        withSession(catalog = catalogOf(flag), compatibility = registry(flag.id,policy)) { session ->
            assertTrue(session.compatibility.assess(flag).operation is OperationAvailability.RequiresPermission)
            assertEquals(DeviceSupport.Supported,session.compatibility.assess(flag).support)
        }
    }
}
