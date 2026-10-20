package com.napreth.set4u.core.network

import org.junit.Assert.*
import org.junit.Test

class WifiConfigurationValuesTest {
    @Test fun staticAddressesAreNumericAndInvalidFormsAreRejectedBeforeExecution() {
        val p = mapOf("option" to "ip", "value" to "static", "ip" to "10.0.2.17", "prefix" to "24", "gateway" to "10.0.2.2", "dns" to "10.0.2.3,2001:4860:4860::8888")
        WifiConfigurationValues.validate(p)
        for ((key, invalid) in listOf("ip" to "example.com", "ip" to "10.0.2.999", "prefix" to "33", "gateway" to "", "dns" to "example.com"))
            assertThrows(IllegalArgumentException::class.java) { WifiConfigurationValues.validate(p + (key to invalid)) }
    }
    @Test fun proxyFieldsAreValidatedForTheSelectedMode() {
        WifiConfigurationValues.validate(mapOf("option" to "proxy", "value" to "none", "host" to ""))
        WifiConfigurationValues.validate(mapOf("option" to "proxy", "value" to "manual", "host" to "proxy.example", "port" to "8080", "exclusions" to "localhost,*.example"))
        WifiConfigurationValues.validate(mapOf("option" to "proxy", "value" to "pac", "pac" to "https://example.com/proxy.pac"))
        assertThrows(IllegalArgumentException::class.java) { WifiConfigurationValues.validate(mapOf("option" to "proxy", "value" to "manual", "host" to "host;sh", "port" to "8080")) }
        assertThrows(IllegalArgumentException::class.java) { WifiConfigurationValues.validate(mapOf("option" to "proxy", "value" to "pac", "pac" to "file:///etc/hosts")) }
    }
    @Test fun wifiQrEscapesReservedCharactersAndWepKeysAreChecked() {
        val qr = WifiSharing.payload("家;里\\网", "wpa2", "p:a,s;\\word", true)
        assertEquals("WIFI:T:WPA;S:家\\;里\\\\网;P:p\\:a\\,s\\;\\\\word;H:true;;", qr)
        assertEquals("WIFI:T:nopass;S:Open;P:;H:false;;", WifiSharing.payload("Open", "open", "", false))
        WifiConfigurationValues.password("wep", "0123456789")
        WifiConfigurationValues.password("wep", "abcde")
        assertThrows(IllegalArgumentException::class.java) { WifiConfigurationValues.password("wep", "invalid") }
    }
}
