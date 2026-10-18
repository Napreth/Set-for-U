package com.napreth.set4u.core.settings

import com.napreth.set4u.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SettingsRegistryTest {
    @Test fun handlerEqualityCannotSubstituteForTheRegisteredImplementation() = runTest {
        fun handler() = object : com.napreth.set4u.core.execution.SettingHandler by FlagHandler() {
            override fun equals(other: Any?) = true
            override fun hashCode() = 1
        }
        val canonical = flagDefinition(handler = handler())
        val impostor = canonical.copy(binding = handler())
        assertEquals(canonical, impostor)
        expectFailure<IllegalArgumentException> { SettingsRegistry(listOf(canonical, impostor)) }
    }

    @Test fun conflictingStaticPagesFailBeforeEitherPageCanRun() = runTest {
        val canonical = flagDefinition()
        val other = canonical.copy(binding = FlagHandler())
        expectFailure<IllegalArgumentException> { SettingsCatalog(flagPage("a", canonical), flagPage("b", other)) }
        val catalog = SettingsCatalog(flagPage("a", canonical), flagPage("b", canonical))
        assertEquals(canonical, catalog.registry[canonical.id])
    }

    @Test fun dynamicProvidersCanOnlyReferenceDeclaredDefinitions() = runTest {
        val canonical = flagDefinition()
        val catalog = SettingsCatalog(mapOf("sample" to { flagPage(definition = canonical.copy(binding = FlagHandler())) }), listOf(canonical), listOf(entryOf(canonical)))
        expectFailure<IllegalArgumentException> { catalog.describe("sample", Target) }
        val missing = SettingsCatalog(mapOf("sample" to { flagPage(definition = canonical) }))
        expectFailure<IllegalArgumentException> { missing.describe("sample", Target) }
    }

    @Test fun directCallsRejectConflictingAndUnregisteredDefinitionsBeforeDeduplication() = runTest {
        val bridge = FakeBridge()
        val canonical = flagDefinition()
        withSession(bridge, catalogOf(canonical)) { session ->
            val running = session.settings.set(Target, canonical, Enabled)
            expectFailure<IllegalArgumentException> { session.settings.set(Target, canonical.copy(binding = FlagHandler()), Enabled) }
            expectFailure<IllegalArgumentException> { session.settings.read(Target, canonical.copy(id = "unregistered")) }
            assertSame(running, session.settings.set(Target, canonical, Enabled))
            running.await()
            assertEquals(1, bridge.writes.size)
        }
    }
}
