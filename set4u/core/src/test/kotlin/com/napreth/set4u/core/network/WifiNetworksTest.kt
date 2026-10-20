package com.napreth.set4u.core.network

import org.junit.Assert.*
import org.junit.Test

class WifiNetworksTest {
    private fun snapshot(vararg items: WirelessItem) = WirelessSnapshot(
        mapOf("enabled" to "true", "ssid" to "Home", "networkId" to "1", "bssid" to "home-ap"), items.toList())
    private val home = WirelessItem("1", "Home", "saved", "wpa2", connected = true)
    private val homeScan = WirelessItem("home-ap", "Home", "available", "wpa2", "-50 dBm · 5200 MHz", true)

    @Test fun currentNetworkAppearsOnceAndSavedVisibleNetworksAreJoined() {
        val networks = WifiNetworks.from(snapshot(home, homeScan, WirelessItem("2", "Office", "saved", "wpa3"),
            WirelessItem("office-ap", "Office", "available", "wpa3"), WirelessItem("office-ap2", "Office", "available", "wpa3")))
        assertEquals("Home", networks.current!!.name)
        assertEquals(home, networks.current!!.saved)
        assertEquals(homeScan, networks.current!!.available)
        assertEquals(listOf("Office"), networks.others.map { it.name })
        assertEquals("2", networks.others.single().saved!!.id)
        assertEquals(2, networks.saved.size)
    }

    @Test fun sameNameWithDifferentSecurityIsNotMergedWithTheCurrentNetwork() {
        val networks = WifiNetworks.from(snapshot(home, homeScan, WirelessItem("guest-ap", "Home", "available", "open")))
        assertEquals("wpa2", networks.current!!.security)
        assertEquals("open", networks.others.single().security)
        assertNull(networks.others.single().saved)
    }

    @Test fun offlineSavedNetworksStayInManagementAndDisabledWifiHidesStaleScans() {
        val s = snapshot(home, homeScan, WirelessItem("2", "Away", "saved", "wpa2"))
        assertTrue(WifiNetworks.from(s).others.isEmpty())
        val off = WifiNetworks.from(s.copy(fields = s.fields + ("enabled" to "false")))
        assertNull(off.current)
        assertTrue(off.others.isEmpty())
        assertEquals(2, off.saved.size)
    }

    @Test fun detailsUseFreshConnectionAndSavedStateAfterForgetOrDisconnect() {
        val selection = WifiNetworks.from(snapshot(home, homeScan)).current!!
        val latest = WifiNetworks.from(WirelessSnapshot(mapOf("enabled" to "true"), listOf(homeScan.copy(connected = false))))
        val details = latest.updated(selection)
        assertFalse(details.connected)
        assertNull(details.saved)
        assertEquals(homeScan.id, details.available!!.id)
        val gone = WifiNetworks.from(null).updated(selection)
        assertFalse(gone.connected)
        assertNull(gone.saved)
        assertNull(gone.available)
    }
}
