package com.napreth.set4u.core.settings

import com.napreth.set4u.core.*
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.execution.SettingOutcome
import com.napreth.set4u.core.session.DeviceTarget
import com.napreth.set4u.core.settings.network.*
import com.napreth.set4u.core.viewmodel.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NetworkSettingsTest {
    private fun profile(sdk: Int = 35) = DeviceProfileSource { DeviceProfile(mapOf("ro.build.version.sdk" to sdk.toString())) }

    @Test fun wifiRetriesObservationWithoutRepeatingTheMutation() = runTest {
        var enabled = true
        var pending: Boolean? = null
        var reads = 0
        val bridge = FakeBridge().apply { onExecute = { request ->
            val args = (request.command as ShellCommand.Arguments).values
            if (args == listOf("cmd", "wifi", "status")) {
                if (pending != null && ++reads >= 2) { enabled = pending!!; pending = null }
                ShellResult(0, "Wifi is ${if (enabled) "enabled" else "disabled"}\n")
            } else { assertEquals(listOf("svc", "wifi", "disable"), args); pending = false; ShellResult(0) }
        } }
        withSession(bridge, ApplicationSettings.catalog(), compatibility = NetworkCompatibility.registry, deviceProfileSource = profile()) { session ->
            val result = session.settings.set(DeviceTarget(), NetworkSettings.wifi, Disabled).await()
            assertEquals(SettingOutcome.Verified, result.outcome)
            assertEquals(1, bridge.requests.count { (it.command as ShellCommand.Arguments).values.first() == "svc" })
            assertEquals(Disabled, result.after?.snapshot?.value)
        }
    }

    @Test fun dnsValidatesBeforeWritingAndNeverTreatsTheHostnameAsShellCode() = runTest {
        val globals = mutableMapOf<String, String>()
        val writes = mutableListOf<List<String>>()
        val bridge = FakeBridge().apply { onExecute = { request ->
            val args = (request.command as ShellCommand.Arguments).values
            require(args.take(1) == listOf("settings"))
            if (args[1] == "get") ShellResult(0, globals[args[3]] ?: "null")
            else { writes += args; globals[args[3]] = args[4]; ShellResult(0) }
        } }
        withSession(bridge, ApplicationSettings.catalog(), compatibility = NetworkCompatibility.registry, deviceProfileSource = profile()) { session ->
            val target = DeviceTarget()
            assertEquals(SettingValue.ChoiceValue("opportunistic"), session.settings.read(target, NetworkSettings.dnsMode).snapshot.value)
            assertEquals(SettingOutcome.Failed, session.settings.set(target, NetworkSettings.dnsMode, SettingValue.ChoiceValue("hostname")).await().outcome)
            assertEquals(SettingOutcome.Failed, session.settings.set(target, NetworkSettings.dnsHostname, SettingValue.TextValue("dns.google; id")).await().outcome)
            assertTrue(writes.isEmpty())
            assertEquals(SettingOutcome.Verified, session.settings.set(target, NetworkSettings.dnsHostname, SettingValue.TextValue("dns.google")).await().outcome)
            assertEquals(SettingOutcome.Verified, session.settings.set(target, NetworkSettings.dnsMode, SettingValue.ChoiceValue("hostname")).await().outcome)
            val count = writes.size
            assertEquals(SettingOutcome.Failed, session.settings.set(target, NetworkSettings.dnsHostname, SettingValue.TextValue("")).await().outcome)
            assertEquals(count, writes.size)
            assertEquals(SettingOutcome.Failed, session.settings.set(Target, NetworkSettings.wifi, Enabled).await().outcome)
            assertEquals(count, writes.size)
        }
    }

    @Test fun olderAndroidKeepsSystemDestinationsWithoutOfferingUnsupportedWrites() = runTest {
        withSession(catalog = ApplicationSettings.catalog(), compatibility = NetworkCompatibility.registry, deviceProfileSource = profile(23)) { session ->
            val model = session.viewModels.settings(DeviceTarget(), "network")
            model.enter().join(); runCurrent()
            assertFalse(model.state.value.find(NetworkSettings.airplane.id)!!.isEnabled)
            assertNotNull(model.state.value.find("open.wifi"))
            assertNotNull(model.state.value.find("open.vpn"))
            model.close()
        }
    }
}
