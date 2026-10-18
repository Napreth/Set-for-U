package com.napreth.set4u.core.settings

import com.napreth.set4u.core.*
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.network.*
import com.napreth.set4u.core.session.DeviceTarget
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.settings.network.*
import com.napreth.set4u.core.state.SettingAccess
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RadioSettingsTest {
    private val target = DeviceTarget()
    private fun profile(vararg features: String, sdk: Int = 35) = DeviceProfile(
        mapOf("ro.build.version.sdk" to sdk.toString()),
        mapOf(DeviceFields.PackageFeatures to DeviceFact(features.joinToString("\n"), FactState.Known, "test")),
    )

    @Test fun hardwareAndVersionRulesSelectTheRegisteredOperations() {
        val registry = NetworkCompatibility.registry
        assertTrue(registry.resolve(NetworkSettings.nfc.id, profile("android.hardware.nfc")) is CompatibilityResolution.Supported)
        assertTrue(registry.resolve(NetworkSettings.nfc.id, profile("android.hardware.bluetooth")) is CompatibilityResolution.Unsupported)
        assertTrue(registry.resolve(NetworkSettings.nfc.id, profile("android.hardware.nfc", sdk = 34)) is CompatibilityResolution.Unsupported)
        assertTrue(registry.resolve(NetworkSettings.mobileData.id, profile("android.hardware.telephony", sdk = 31)) is CompatibilityResolution.Supported)
        assertTrue(registry.resolve(NetworkSettings.mobileData.id, profile("android.hardware.telephony", sdk = 35)) is CompatibilityResolution.Unsupported)
        assertTrue(registry.resolve(NetworkSettings.mobileData.id, profile("android.hardware.telephony.data")) is CompatibilityResolution.Supported)
    }

    @Test fun absentNfcIsHiddenAndDirectWritesNeverReachTheBridge() = runTest {
        val bridge = FakeBridge()
        val page = testPage("nfc", "NFC", listOf(NetworkEntries.nfc))
        withSession(bridge, SettingsCatalog(page, texts = NetworkTexts.resources), compatibility = NetworkCompatibility.registry,
            deviceProfileSource = DeviceProfileSource { profile("android.hardware.bluetooth") }) { session ->
            val model = session.viewModels.settings(target, "nfc")
            model.enter().join(); runCurrent()
            assertTrue(model.state.value.containers.isEmpty())
            assertEquals(SettingOutcome.Failed, session.settings.set(target, NetworkSettings.nfc, Enabled).await().outcome)
            assertTrue(bridge.requests.isEmpty())
            model.close()
        }
    }

    @Test fun unreadableFeatureListDoesNotHideNfcOrAllowWrites() = runTest {
        val bridge = FakeBridge()
        val page = testPage("nfc", "NFC", listOf(NetworkEntries.nfc))
        withSession(bridge, SettingsCatalog(page, texts = NetworkTexts.resources), compatibility = NetworkCompatibility.registry,
            deviceProfileSource = DeviceProfileSource { DeviceProfile(mapOf("ro.build.version.sdk" to "35")) }) { session ->
            val model = session.viewModels.settings(target, "nfc")
            model.enter().join(); runCurrent()
            assertEquals(1, model.state.value.items.size)
            assertFalse(model.state.value.items.single().isEnabled)
            assertEquals(SettingAccess.Unavailable, model.state.value.items.single().status.observation?.snapshot?.access)
            assertTrue(session.compatibility.resolve(NetworkSettings.nfc) is CompatibilityResolution.Unknown)
            assertTrue(bridge.requests.isEmpty())
            model.close()
        }
    }

    @Test fun supportedNfcUsesPersistentDisableAndFreshReadback() = runTest {
        var enabled = false
        val bridge = FakeBridge().apply { onExecute = { request ->
            val args = (request.command as ShellCommand.Arguments).values
            when (args) {
                listOf("cmd", "nfc", "status") -> ShellResult(0, "Nfc is ${if (enabled) "enabled" else "disabled"}\n")
                listOf("cmd", "nfc", "enable-nfc") -> { enabled = true; ShellResult(0) }
                listOf("cmd", "nfc", "disable-nfc", "[persist]") -> { enabled = false; ShellResult(0) }
                else -> error("Unexpected radio command")
            }
        } }
        withSession(bridge, SettingsCatalog(emptyMap(), entries = listOf(entryOf(NetworkSettings.nfc))), compatibility = NetworkCompatibility.registry,
            deviceProfileSource = DeviceProfileSource { profile("android.hardware.nfc") }) { session ->
            assertEquals(SettingOutcome.Verified, session.settings.set(target, NetworkSettings.nfc, Enabled).await().outcome)
            assertEquals(SettingOutcome.Verified, session.settings.set(target, NetworkSettings.nfc, Disabled).await().outcome)
            assertFalse(enabled)
            assertTrue(bridge.requests.last().command.render().contains("status"))
        }
    }

    @Test fun presentHardwareWithADeniedServiceStaysVisibleAndUnknown() = runTest {
        val bridge = FakeBridge().apply { onExecute = { ShellResult(1, stderr = "Permission denied") } }
        val page = testPage("nfc", "NFC", listOf(NetworkEntries.nfc))
        withSession(bridge, SettingsCatalog(page, texts = NetworkTexts.resources), compatibility = NetworkCompatibility.registry,
            deviceProfileSource = DeviceProfileSource { profile("android.hardware.nfc") }) { session ->
            val model = session.viewModels.settings(target, "nfc")
            model.enter().join(); runCurrent()
            assertEquals(1, model.state.value.items.size)
            assertNull(model.state.value.items.single().value)
            assertEquals(SettingAccess.Unavailable, model.state.value.items.single().status.observation?.snapshot?.access)
            model.close()
        }
    }

    @Test fun bluetoothWaitsForActualAdapterStateAndRejectsFalseSuccess() = runTest {
        var state = "BLE_ON"
        var changes = 0
        var reads = 0
        val bridge = FakeBridge().apply { onExecute = { request ->
            val args = (request.command as ShellCommand.Arguments).values
            if (args.first() == "dumpsys") {
                reads++
                ShellResult(0, "Bluetooth Status\n  enabled: true\n  state: $state\n\nOther status:\n  state: ON\n")
            } else { changes++; ShellResult(0) }
        } }
        withSession(bridge, SettingsCatalog(emptyMap(), entries = listOf(entryOf(NetworkSettings.bluetooth))), compatibility = NetworkCompatibility.registry,
            deviceProfileSource = DeviceProfileSource { profile("android.hardware.bluetooth") }) { session ->
            assertEquals(Disabled, session.settings.read(target, NetworkSettings.bluetooth).snapshot.value)
            val result = session.settings.set(target, NetworkSettings.bluetooth, Enabled).await()
            assertEquals(SettingOutcome.Failed, result.outcome)
            assertEquals(1, changes)
            assertTrue(reads > 2)
            state = "TURNING_ON"
            assertNull(session.settings.read(target, NetworkSettings.bluetooth).snapshot.value)
            state = "unrecognised"
            assertEquals(SettingAccess.Unavailable, session.settings.read(target, NetworkSettings.bluetooth).snapshot.access)
        }
    }

    @Test fun mobileDataPinsTheSubscriptionAndOnlyVerifiesItsUserSetting() = runTest {
        var enabled = false
        val requests = mutableListOf<MobileDataRequest>()
        val definition = NetworkSettings.mobileData.copy(operationFunctions = mapOf("telephony31" to { MobileDataHandler { _, request ->
            requests += request
            request.desired?.let { enabled = it }
            MobileDataState(MobileDataStatus.Ready, 7, enabled)
        } }))
        withSession(catalog = SettingsCatalog(emptyMap(), entries = listOf(entryOf(definition))), compatibility = NetworkCompatibility.registry,
            deviceProfileSource = DeviceProfileSource { profile("android.hardware.telephony.data") }) { session ->
            val result = session.settings.set(target, definition, Enabled).await()
            assertEquals(SettingOutcome.Verified, result.outcome)
            assertNull(requests.first().expectedSubscriptionId)
            assertTrue(requests.drop(1).all { it.expectedSubscriptionId == 7 })
            assertEquals(1, requests.count { it.desired != null })
        }
    }

    @Test fun aDefaultSimChangePreventsWritingToTheNewSim() = runTest {
        var currentSubscription = 7
        var writes = 0
        val definition = NetworkSettings.mobileData.copy(operationFunctions = mapOf("telephony31" to { MobileDataHandler { _, request ->
            if (request.desired != null) currentSubscription = 8
            if (request.expectedSubscriptionId != null && request.expectedSubscriptionId != currentSubscription)
                MobileDataState(MobileDataStatus.DefaultSubscriptionChanged)
            else { if (request.desired != null) writes++; MobileDataState(MobileDataStatus.Ready, currentSubscription, false) }
        } }))
        withSession(catalog = SettingsCatalog(emptyMap(), entries = listOf(entryOf(definition))), compatibility = NetworkCompatibility.registry,
            deviceProfileSource = DeviceProfileSource { profile("android.hardware.telephony.data") }) { session ->
            val result = session.settings.set(target, definition, Enabled).await()
            assertEquals(SettingOutcome.Uncertain, result.outcome)
            assertEquals(0, writes)
            assertNull(result.after?.snapshot?.value)
        }
    }

    @Test fun noActiveSimIsReadOnlyAndNeverConvertedToFalse() = runTest {
        val definition = NetworkSettings.mobileData.copy(operationFunctions = mapOf("telephony31" to { MobileDataHandler { _, request ->
            assertNull(request.desired)
            MobileDataState(MobileDataStatus.NoActiveSubscription)
        } }))
        withSession(catalog = SettingsCatalog(emptyMap(), entries = listOf(entryOf(definition))), compatibility = NetworkCompatibility.registry,
            deviceProfileSource = DeviceProfileSource { profile("android.hardware.telephony.data") }) { session ->
            val result = session.settings.set(target, definition, Enabled).await()
            assertEquals(SettingOutcome.Failed, result.outcome)
            assertNull(result.before?.snapshot?.value)
            assertEquals(SettingAccess.ReadOnly, result.before?.snapshot?.access)
        }
    }
}
