@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.network

import com.napreth.set4u.core.*
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.session.*
import com.napreth.set4u.core.state.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class WirelessFrameworkTest {
    @Test fun bluetoothStateHandlesTheAndroid37HeaderAndCapitalizedStateField() = runTest {
        val bridge = FakeBridge().apply { onExecute = {
            ShellResult(0, "Bluetooth Status:\n  State:         ON\n  Name:          AVD\n\nBluetooth Activity History:\n  OFF\n")
        } }
        val handler = BluetoothHandler()
        val flag = SettingDefinition("bluetooth.test", ValueType.Boolean, binding = { handler })
        withSession(bridge, catalogOf(flag)) { core ->
            assertEquals(Enabled, core.settings.read(DeviceTarget(), flag).snapshot.value)
        }
    }
    @Test fun independentDeviceFactsAreCollectedConcurrently() = runTest {
        val bridge = FakeBridge().apply { onExecute = { request ->
            val args = (request.command as ShellCommand.Arguments).values
            if (args == listOf("getprop")) ShellResult(0, "[ro.build.version.sdk]: [37]\n")
            else { delay(500); ShellResult(0, if (args[0] == "pm") "feature:android.hardware.wifi\n" else "known") }
        } }
        val result = DeviceInfoCollector(bridge).collect()
        assertEquals(500L, testScheduler.currentTime)
        assertEquals(FactState.Known, result.fact(DeviceFields.PackageFeatures).state)
        assertEquals(6, bridge.requests.size)
    }
    @Test fun pageRefreshReusesDeviceFactsAndIdentityButWritesRecheckAuthority() = runTest {
        val flags = (0..3).map { flagDefinition("flag.$it") }
        val page = testPage("flags", "Flags", flags.map(::entryOf))
        val fake = FakeBridge(); var environments = 0; var profiles = 0
        val bridge = object : IAdbBridge by fake {
            override suspend fun environment() = fake.environment().also { environments++ }
        }
        val registry = CompatibilityRegistry(emptyList(), flags.associate { it.id to CapabilityPolicy(identities = setOf(ExecutionIdentity.Shell, ExecutionIdentity.Root)) })
        val core = Core.open(bridge, SettingsCatalog(page), dispatcher = StandardTestDispatcher(testScheduler), compatibility = registry,
            deviceProfileSource = DeviceProfileSource { profiles++; DeviceProfile(mapOf("ro.build.version.sdk" to "37")) })
        try {
            val vm = core.viewModels.settings(Target, "flags"); vm.enter().join(); runCurrent()
            core.compatibility.deviceProfile()
            vm.refresh().join(); runCurrent()
            assertEquals(1, environments); assertEquals(1, profiles)
            fake.testUid = 12345
            val result = core.settings.set(Target, flags.first(), Enabled).await()
            assertEquals(SettingOutcome.Failed, result.outcome)
            assertTrue(environments > 1); assertTrue(fake.writes.isEmpty())
            vm.close()
        } finally { core.close() }
    }
    @Test fun passwordsReachTheHandlerButAreNotRetainedInSessionResults() = runTest {
        var received = ""
        val action = SettingDefinition("secret.action", ValueType.Action, binding = { object : SettingHandler {
            override suspend fun inspect(context: SettingContext) = SettingSnapshot(Enabled)
            override suspend fun apply(context: SettingContext, desiredValue: SettingValue) { received = (desiredValue as SettingValue.ActionValue).parameters.getValue("password") }
            override fun verify(before: SettingSnapshot, after: SettingSnapshot, desiredValue: SettingValue) = Verification.Satisfied
        } }, actionParameters = mapOf("password" to ActionParameter(sensitive = true)))
        withSession(catalog = catalogOf(action)) { core ->
            val result = core.settings.set(Target, action, SettingValue.ActionValue(mapOf("password" to "private-test-password"))).await()
            assertEquals(SettingOutcome.Verified, result.outcome)
            assertEquals("private-test-password", received)
            assertEquals("[redacted]", (result.desiredValue as SettingValue.ActionValue).parameters["password"])
            assertFalse(core.state.value.toString().contains("private-test-password"))
        }
    }
    @Test fun structuredWirelessProtocolRoundTripsUnicodeAndRejectsTrailingData() {
        val spec = WirelessSpec().descriptor
        val input = WirelessRequest(WirelessKind.Wifi, "connect", mapOf("ssid" to "家里的网络", "password" to "secret"))
        val decoded = spec.requestCodec.decode(spec.requestCodec.encode(input))
        assertEquals(input.parameters, decoded.parameters)
        assertFalse(input.toString().contains("secret"))
        val response = WirelessSnapshot(mapOf("ssid" to "家里的网络"), listOf(WirelessItem("1", "家里的网络", "saved", connected = true)))
        val bytes = spec.responseCodec.encode(response)
        assertEquals(response, spec.responseCodec.decode(bytes))
        assertThrows(IllegalStateException::class.java) { spec.responseCodec.decode(bytes + byteArrayOf(1)) }
    }
}
