package com.napreth.set4u.desktop.bridge

import com.napreth.set4u.core.Core
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.session.DeviceTarget
import com.napreth.set4u.core.settings.SettingsCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class GenericHandlerAvdTest {
    private interface DeviceQuery : Handler { suspend fun facts(context: HandlerContext): Map<String, String> }
    @Test fun customMethodUsesAdbWithStructuredResultsAndInvocationLocalHandlers() = runBlocking {
        val serial = System.getenv("SET4U_TEST_ADB_SERIAL").orEmpty()
        assumeTrue("Explicit AVD required", serial.startsWith("emulator-"))
        var instances = 0
        val query = HandlerDefinition("device.query", DeviceQuery::class, mapOf("default" to {
            instances++
            object : DeviceQuery {
                override suspend fun facts(context: HandlerContext) = mapOf(
                    "sdk" to context.execute(ShellCommand.arguments("getprop", "ro.build.version.sdk")).requireSuccess().stdout.trim(),
                    "uid" to context.execute(ShellCommand.arguments("id", "-u")).requireSuccess().stdout.trim(),
                )
            }
        }), HandlerEffect.ReadOnly)
        val bridge = AdbBridge(serial).also { it.connect() }
        val compatibility = CompatibilityRegistry(listOf(CompatibilityRule("adb.query", query.id, DeviceCondition.Always, RuleDecision.Use("default"))),
            mapOf(query.id to CapabilityPolicy(identities = setOf(ExecutionIdentity.Shell, ExecutionIdentity.Root))))
        val session = Core.open(bridge, SettingsCatalog(emptyMap(), handlers = listOf(query)), compatibility = compatibility)
        try {
            repeat(2) {
                val result = session.handlers.invoke(query, DeviceTarget()) { h, c -> h.facts(c) }
                val facts = result.requireCompleted()
                assertTrue(facts.getValue("sdk").toInt() >= 23)
                assertTrue(facts.getValue("uid") in setOf("0", "2000"))
                assertEquals(2, result.evidence.size)
                assertTrue(result.evidence.all { it.result?.stdout.isNullOrEmpty() })
            }
            assertEquals(2, instances)
        } finally { session.close() }
    }
}
