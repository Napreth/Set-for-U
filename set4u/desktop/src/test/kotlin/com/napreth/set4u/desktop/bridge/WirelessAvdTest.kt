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

class WirelessAvdTest {
    private suspend fun session(block: suspend (CoreSession, RootBridge) -> Unit) {
        val serial = System.getenv("SET4U_TEST_ADB_SERIAL").orEmpty()
        assumeTrue(serial.matches(Regex("emulator-\\d+")) && System.getenv("SET4U_TEST_ROOT") == "1")
        val adb = AdbBridge(serial); adb.connect()
        check(adb.execute(ShellRequest(ShellCommand.arguments("getprop", "ro.kernel.qemu"))).requireSuccess().stdout.trim() == "1")
        val root = RootBridge(adb)
        val core = Core.open(root, ApplicationSettings.catalog(), compatibility = NetworkCompatibility.registry)
        try { root.connect(); block(core, root) } finally { core.close() }
    }
    private suspend fun query(core: CoreSession, kind: WirelessKind, scan: Boolean = false): WirelessSnapshot =
        core.handlers.queries.invoke(WirelessCapabilities.queries, DeviceTarget()) { h, c -> h.read(c, kind, scan) }.also {
            assertEquals(it.reason?.message, HandlerOutcome.Completed, it.outcome)
        }.requireCompleted()
    private suspend fun action(core: CoreSession, kind: WirelessKind, operation: String, parameters: Map<String, String>) =
        core.settings.set(DeviceTarget(), WirelessCapabilities.actions.getValue(kind), SettingValue.ActionValue(parameters + ("operation" to operation))).await().also {
            assertTrue("${it.outcome}: ${it.reason}", it.outcome in setOf(SettingOutcome.Verified, SettingOutcome.AlreadySatisfied))
        }
    @Test fun rootQueriesAndWifiScanUseRealSystemApis() = runBlocking { session { core, _ ->
        for (kind in WirelessKind.entries) {
            val begin = System.nanoTime()
            val result = query(core, kind)
            println("$kind: fields=${result.fields.keys}, items=${result.items.size}, millis=${(System.nanoTime() - begin) / 1_000_000}")
        }
        val wifi = query(core, WirelessKind.Wifi)
        assumeTrue(wifi.fields["enabled"] == "true")
        val scanned = query(core, WirelessKind.Wifi, true)
        assertTrue(scanned.fields["scanFresh"] in listOf("true", "false"))
        println("Wi-Fi scan: ${scanned.items.count { it.category == "available" }} real networks; fresh=${scanned.fields["scanFresh"]}")
        val bluetooth = query(core, WirelessKind.Bluetooth)
        if (bluetooth.fields["enabled"] == "true") {
            val found = query(core, WirelessKind.Bluetooth, true)
            println("Bluetooth discovery completed: ${found.items.size} devices")
        }
    } }
    @Test fun savedWifiForgetAndBluetoothRenameAreReadBackAndRestored() = runBlocking { session { core, root ->
        val name = "Set4u-AVD-QA-${System.currentTimeMillis()}"
        val oldBluetooth = query(core, WirelessKind.Bluetooth).fields.getValue("name")
        var qaId: String? = null
        try {
            root.execute(ShellRequest(ShellCommand.arguments("cmd", "wifi", "add-network", name, "open"))).requireSuccess()
            qaId = query(core, WirelessKind.Wifi).items.single { it.category == "saved" && it.name == name }.id
            action(core, WirelessKind.Wifi, "forget", mapOf("id" to qaId, "ssid" to name))
            assertFalse(query(core, WirelessKind.Wifi).items.any { it.category == "saved" && it.name == name })
            action(core, WirelessKind.Bluetooth, "rename", mapOf("name" to "Set4U AVD QA"))
            assertEquals("Set4U AVD QA", query(core, WirelessKind.Bluetooth).fields["name"])
        } finally {
            query(core, WirelessKind.Wifi).items.filter { it.category == "saved" && it.name == name }.map { it.id }.distinct().forEach { id ->
                root.execute(ShellRequest(ShellCommand.arguments("cmd", "wifi", "forget-network", id)))
            }
            action(core, WirelessKind.Bluetooth, "rename", mapOf("name" to oldBluetooth))
        }
    } }
    @Test fun wifiConnectionAndRealInternetTetheringAreVerifiedAndRestored() = runBlocking { session { core, _ ->
        val before = query(core, WirelessKind.Wifi)
        // Only reconnect the emulator's known open AP; never rewrite a user's protected network.
        assumeTrue(before.fields["ssid"] == "AndroidWifi")
        val saved = before.items.single { it.category == "saved" && it.name == "AndroidWifi" }
        val hotspotBefore = query(core, WirelessKind.Hotspot)
        assumeTrue(hotspotBefore.fields["wifi"] == "false")
        try {
            action(core, WirelessKind.Wifi, "disconnect", emptyMap())
            action(core, WirelessKind.Wifi, "connect", mapOf("ssid" to "AndroidWifi", "security" to "open"))
            assertEquals("AndroidWifi", query(core, WirelessKind.Wifi).fields["ssid"])
            action(core, WirelessKind.Hotspot, "start", mapOf("type" to "0"))
            assertEquals("true", query(core, WirelessKind.Hotspot).fields["wifi"])
            action(core, WirelessKind.Hotspot, "stop", mapOf("type" to "0"))
        } finally {
            val hotspot = query(core, WirelessKind.Hotspot)
            if (hotspot.fields["wifi"] != hotspotBefore.fields["wifi"]) action(core, WirelessKind.Hotspot, "stop", mapOf("type" to "0"))
            action(core, WirelessKind.Wifi, "connectSaved", mapOf("id" to saved.id, "ssid" to saved.name))
            assertEquals(before.fields["ssid"], query(core, WirelessKind.Wifi).fields["ssid"])
        }
    } }
}
