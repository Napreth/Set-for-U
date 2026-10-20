package com.napreth.set4u.desktop.bridge

import com.napreth.set4u.core.Core
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.NetworkCompatibility
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.network.*
import com.napreth.set4u.core.session.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.network.ApplicationSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test

class WifiConfigurationAvdTest {
    private suspend fun session(block: suspend (CoreSession, RootBridge) -> Unit) {
        val serial = System.getenv("SET4U_TEST_ADB_SERIAL").orEmpty()
        assumeTrue(serial.matches(Regex("emulator-\\d+")) && System.getenv("SET4U_TEST_ROOT") == "1")
        val adb = AdbBridge(serial); adb.connect()
        check(adb.execute(ShellRequest(ShellCommand.arguments("getprop", "ro.kernel.qemu"))).requireSuccess().stdout.trim() == "1")
        val root = RootBridge(adb)
        val core = Core.open(root, ApplicationSettings.catalog(), compatibility = NetworkCompatibility.registry)
        try { root.connect(); block(core, root) } finally { core.close() }
    }
    private suspend fun query(core: CoreSession) = core.handlers.queries.invoke(WirelessCapabilities.queries, DeviceTarget()) { h, c -> h.read(c, WirelessKind.Wifi) }.requireCompleted()
    private suspend fun action(core: CoreSession, operation: String, parameters: Map<String, String>) {
        val result = core.settings.set(DeviceTarget(), WirelessCapabilities.actions.getValue(WirelessKind.Wifi), SettingValue.ActionValue(parameters + ("operation" to operation))).await()
        assertTrue("$operation ${parameters["option"]}: ${result.outcome}, ${result.reason}", result.outcome in setOf(SettingOutcome.Verified, SettingOutcome.AlreadySatisfied))
    }
    @Test fun savedNetworkConfigurationAndGlobalPreferencesRoundTripThroughTheEngine() = runBlocking { session { core, root ->
        val before = query(core)
        val name = "Set4u-Config-QA-${System.currentTimeMillis()}"
        var saved: WirelessItem? = null
        try {
            root.execute(ShellRequest(ShellCommand.arguments("cmd", "wifi", "add-network", name, "wpa2", "Set4u-Test-Original"))).requireSuccess()
            saved = query(core).items.single { it.category == "saved" && it.name == name }
            suspend fun change(option: String, value: String, extra: Map<String, String> = emptyMap()) = action(core, "configure", extra + mapOf("id" to saved!!.id, "ssid" to name, "option" to option, "value" to value))
            change("autoJoin", "false")
            change("metered", "1"); change("metered", "2")
            change("macMode", "0")
            if ("1" in saved!!.properties["macModes"].orEmpty().split(',')) change("macMode", "1")
            else println("AVD reports no connected MAC randomization support; only device MAC is available")
            change("shared", "false"); change("shared", "true")
            if ("allowEdit" in saved!!.properties) change("allowEdit", "false")
            change("hidden", "true")
            change("proxy", "manual", mapOf("host" to "127.0.0.1", "port" to "8080", "exclusions" to "localhost,*.example"))
            change("proxy", "pac", mapOf("pac" to "https://example.invalid/proxy.pac"))
            change("proxy", "none")
            change("ip", "static", mapOf("ip" to "10.77.0.2", "prefix" to "24", "gateway" to "10.77.0.1", "dns" to "1.1.1.1"))
            change("ip", "dhcp")
            change("password", "", mapOf("password" to "Set4u-Test-Replaced"))
            val shared = core.handlers.queries.invoke(WirelessCapabilities.queries, DeviceTarget()) { h, c -> h.share(c, saved!!.id, name) }.requireCompleted()
            assertEquals(WifiSharing.payload(name, "wpa2", "Set4u-Test-Replaced", true), shared)
            println("Saved configuration: auto-connect, metering, MAC, multi-user, hidden, proxy, static/DHCP and credential sharing verified")
            for (key in listOf("notifier", "scanAlways")) if (before.fields[key] in setOf("true", "false")) {
                action(core, "preference", mapOf("option" to key, "value" to (before.fields[key] != "true").toString()))
                action(core, "preference", mapOf("option" to key, "value" to before.fields.getValue(key)))
            }
            if (before.fields["wake"] != null && before.fields["locationEnabled"] == "true" && before.fields["scanAlways"] == "true") {
                action(core, "preference", mapOf("option" to "wake", "value" to (before.fields["wake"] != "true").toString()))
            }
            if (before.fields["wepSupported"] == "true" && before.fields["wep"] != null)
                action(core, "preference", mapOf("option" to "wep", "value" to (before.fields["wep"] != "true").toString()))
        } finally {
            for (key in listOf("scanAlways", "notifier", "wake", "wep")) {
                val fresh = query(core)
                val original = before.fields[key] ?: continue
                if (key == "wep" && before.fields["wepSupported"] != "true") continue
                if (fresh.fields[key] != original) action(core, "preference", mapOf("option" to key, "value" to original))
            }
            val remaining = query(core).items.firstOrNull { it.category == "saved" && it.name == name }
            if (remaining != null) action(core, "forget", mapOf("id" to remaining.id, "ssid" to remaining.name))
            val restored = query(core)
            for (key in listOf("scanAlways", "notifier", "wake", "wep")) assertEquals("Preference $key", before.fields[key], restored.fields[key])
            assertEquals(before.fields["ssid"], restored.fields["ssid"])
        }
    } }

    @Test fun connectedAvdNetworkSwitchesStaticIpAndDhcpAndRestoresItsConfiguration() = runBlocking { session { core, _ ->
        val before = query(core)
        assumeTrue(before.fields["ssid"] == "AndroidWifi")
        val saved = before.items.single { it.category == "saved" && it.id == before.fields["networkId"] }
        assumeTrue(saved.properties["ipMode"] == "dhcp")
        val identity = mapOf("id" to saved.id, "ssid" to saved.name, "option" to "ip")
        try {
            action(core, "configure", identity + mapOf("value" to "static", "ip" to before.fields.getValue("ip"), "prefix" to before.fields.getValue("prefix"),
                "gateway" to before.fields.getValue("gateway"), "dns" to before.fields.getValue("dns").substringBefore(',')))
            assertEquals(before.fields["ip"], query(core).fields["ip"])
        } finally {
            action(core, "configure", identity + ("value" to "dhcp"))
            assertEquals("AndroidWifi", query(core).fields["ssid"])
        }
        println("Connected AndroidWifi: static IP applied, DHCP restored and connection verified")
    } }
}
