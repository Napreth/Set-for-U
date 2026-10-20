@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.execution

import com.napreth.set4u.core.*
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.session.*
import com.napreth.set4u.core.state.SettingSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class HandlerRuntimeTest {
    private interface Lookup : Handler { suspend fun query(context: HandlerContext): List<String> }
    private fun lookup(effect: HandlerEffect = HandlerEffect.ReadOnly, query: suspend (HandlerContext) -> List<String>) =
        HandlerDefinition("sample.lookup", Lookup::class, mapOf("default" to { object : Lookup {
            override suspend fun query(context: HandlerContext) = query(context)
        } }), effect)

    @Test fun customMethodsReturnStructuredDataAndFactoriesAreInvocationLocal() = runTest {
        var factories = 0
        val definition = HandlerDefinition("wifi.scan", Lookup::class, mapOf("default" to {
            factories++
            object : Lookup { var calls = 0; override suspend fun query(context: HandlerContext) = listOf("network-${++calls}") }
        }), HandlerEffect.ReadOnly)
        val catalog = SettingsCatalog(emptyMap(), handlers = listOf(definition))
        repeat(2) { withSession(catalog = catalog) { session ->
            repeat(2) { assertEquals(listOf("network-1"), session.handlers.invoke(definition, Target) { h, c -> h.query(c) }.requireCompleted()) }
        } }
        assertEquals(4, factories)
    }

    @Test fun defaultSettingFactoriesDoNotShareStateAndOneWriteUsesOneHandlerForVerification() = runTest {
        val owners = mutableListOf<SettingTarget>()
        val inspected = mutableListOf<Int>()
        val definition = SettingDefinition("sample.flag", ValueType.Boolean, {
            object : SettingHandler by FlagHandler() {
                var owner: SettingTarget? = null; var calls = 0
                override suspend fun inspect(context: SettingContext): SettingSnapshot {
                    check(owner == null || owner == context.target); owner = context.target
                    owners += context.target; inspected += ++calls
                    return FlagHandler().inspect(context)
                }
            }
        })
        val catalog = catalogOf(definition)
        for (target in listOf(Target, AppTarget(10, "com.example.other"))) withSession(catalog = catalog) { session ->
            assertEquals(SettingOutcome.Verified, session.settings.set(target, definition, Enabled).await().outcome)
        }
        assertEquals(listOf(1, 2, 1, 2), inspected)
        assertEquals(listOf(Target, Target, AppTarget(10, "com.example.other"), AppTarget(10, "com.example.other")), owners)
    }

    @Test fun queriesShareTheSettingQueueAndCallerCancellationReleasesIt() = runTest {
        val entered = CompletableDeferred<Unit>()
        val definition = lookup { entered.complete(Unit); awaitCancellation() }
        val flag = flagDefinition()
        val bridge = FakeBridge()
        val catalog = SettingsCatalog(emptyMap(), definitions = listOf(flag), handlers = listOf(definition))
        withSession(bridge, catalog) { session ->
            val query = backgroundScope.async { session.handlers.invoke(definition, Target) { h, c -> h.query(c) } }
            entered.await()
            val write = session.settings.set(Target, flag, Enabled)
            runCurrent(); assertTrue(bridge.writes.isEmpty())
            query.cancelAndJoin()
            assertEquals(SettingOutcome.Verified, write.await().outcome)
        }
    }

    @Test fun timeoutDoesNotClaimAMutatingHandlerDidNothing() = runTest {
        for (effect in HandlerEffect.entries) {
            val definition = lookup(effect) { awaitCancellation() }
            withSession(catalog = SettingsCatalog(emptyMap(), handlers = listOf(definition)), options = CoreOptions(operationTimeoutMillis = 20)) { session ->
                val result = session.handlers.invoke(definition, Target) { h, c -> h.query(c) }
                assertEquals(if (effect == HandlerEffect.ReadOnly) HandlerOutcome.TimedOut else HandlerOutcome.Uncertain, result.outcome)
            }
        }
    }

    @Test fun registryAndSettingPipelineCannotBeBypassedThroughTheGenericEntry() = runTest {
        val definition = lookup { emptyList() }
        val impostor = lookup { error("Must not execute") }
        val flag = flagDefinition()
        withSession(catalog = SettingsCatalog(emptyMap(), definitions = listOf(flag), handlers = listOf(definition))) { session ->
            expectFailure<IllegalArgumentException> { session.handlers.invoke(impostor, Target) { h, c -> h.query(c) } }
            expectFailure<IllegalArgumentException> { session.handlers.invoke(flag.handlerDefinition, Target) { h, c -> h.apply(SettingContext(Target, ShellExecutor(FakeBridge()), EvidenceLog()), Enabled) } }
            val mutating = lookup(HandlerEffect.Mutating) { emptyList() }
            expectFailure<IllegalArgumentException> { session.handlers.queries.invoke(mutating, Target) { h, c -> h.query(c) } }
        }
    }

    @Test fun aCompletedContextCannotIssueLateCommands() = runTest {
        val definition = lookup { emptyList() }; val bridge = FakeBridge()
        withSession(bridge, SettingsCatalog(emptyMap(), handlers = listOf(definition))) { session ->
            val context = session.handlers.invoke(definition, Target) { _, context -> context }.requireCompleted()
            expectFailure<IllegalStateException> { context.execute(com.napreth.set4u.core.bridge.ShellCommand.arguments("read-flag", Target.toString())) }
            assertTrue(bridge.requests.isEmpty())
        }
    }
}
