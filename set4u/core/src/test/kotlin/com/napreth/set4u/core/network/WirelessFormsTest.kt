package com.napreth.set4u.core.network

import com.napreth.set4u.core.form.*
import com.napreth.set4u.core.network.form.WirelessForms
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WirelessFormsTest {
    private fun text(session: FormSession<*>, key: String, value: String) = session.editValue(session.definition.fields.single { it.key == key }, value)

    @Test fun connectionUsesBackendPasswordRulesAndOmitsInactiveCredentials() = runTest {
        val session = FormSession(WirelessForms.connect())
        val psk = "a".repeat(64)
        assertFalse(session.validate()); assertEquals("ssid", session.state.value.errors.single().fieldKey)
        text(session, "ssid", " 含 空格;网络 "); text(session, "password", psk)
        assertTrue(session.validate())
        text(session, "security", "wpa3"); assertFalse(session.validate())
        text(session, "security", "wpa2")
        var request: WirelessRequest? = null
        assertTrue(session.submit { request = it })
        WirelessInputValues.connect(request!!.parameters)
        assertEquals(psk, request!!.parameters["password"]); assertEquals(" 含 空格;网络 ", request!!.parameters["ssid"])
        assertFalse(request.toString().contains(psk))
        val passwordForm = FormSession(WirelessForms.password(WifiNetwork("Saved", "wpa2", saved = WirelessItem("4", "Saved", "saved", "wpa2"))))
        assertFalse(passwordForm.validate()); assertEquals("password", passwordForm.state.value.errors.single().fieldKey)
        val open = FormSession(WirelessForms.connect(WirelessItem("id", "Open", "available", "open")))
        assertTrue(open.submit { assertFalse(it.parameters.containsKey("password")); assertEquals("open", it.parameters["security"]) })
    }

    @Test fun proxyModesKeepTheirDraftsAndProduceOnlyActiveParameters() = runTest {
        val network = WifiNetwork("Home", "wpa2", saved = WirelessItem("5", "Home", "saved", "wpa2"))
        val session = FormSession(WirelessForms.proxy(network))
        text(session, "value", "manual"); assertFalse(session.validate())
        text(session, "host", " proxy.local "); text(session, "port", "8080")
        text(session, "value", "pac"); text(session, "pac", "https://example.test/wifi.pac")
        assertTrue(session.submit {
            assertEquals("5", it.parameters["id"]); assertEquals("pac", it.parameters["value"])
            assertFalse(it.parameters.containsKey("host")); assertFalse(it.parameters.containsKey("port"))
            WifiConfigurationValues.validate(it.parameters)
        })
    }

    @Test fun dhcpIgnoresInvalidStaticDraftAndReopeningUsesCurrentBaseline() = runTest {
        val network = WifiNetwork("Home", "open", saved = WirelessItem("5", "Home", "saved", properties = mapOf("ipMode" to "dhcp")))
        val session = FormSession(WirelessForms.ip(network))
        text(session, "value", "static"); text(session, "ip", "bad-address"); assertFalse(session.validate())
        text(session, "value", "dhcp")
        assertTrue(session.submit { assertEquals("dhcp", it.parameters["value"]); assertFalse(it.parameters.containsKey("ip")) })
        val updated = FormSession(WirelessForms.ip(network, mapOf("ip" to "10.0.0.7")))
        val ip = updated.definition.fields.filterIsInstance<TextField>().single { it.key == "ip" }
        assertEquals("10.0.0.7", updated.state.value.values[ip])
        text(updated, "value", "static"); text(updated, "ip", "10.0.0.8")
        assertEquals("10.0.0.8", updated.state.value.values[ip]); assertTrue(updated.state.value.dirty)
    }
}
