@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.napreth.set4u.core.execution

import com.napreth.set4u.core.*
import com.napreth.set4u.core.bridge.BridgeState
import com.napreth.set4u.core.bridge.ShellCommand
import com.napreth.set4u.core.bridge.ShellResult
import com.napreth.set4u.core.session.AppTarget
import com.napreth.set4u.core.settings.SettingValue
import com.napreth.set4u.core.state.SettingAccess
import com.napreth.set4u.core.state.SettingSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExecutionEngineTest {
    @Test
    fun modifiesAndVerifiesWithFreshReads() = runTest {
        val bridge = FakeBridge()
        val definition = flagDefinition()
        withSession(bridge) { session ->
            val result = session.settings.set(Target, definition, Enabled).await()
            assertEquals(SettingOutcome.Verified, result.outcome)
            assertEquals(Disabled, result.before?.snapshot?.value)
            assertEquals(Enabled, result.after?.snapshot?.value)
            assertEquals(3, bridge.requests.size)
            assertEquals(1, bridge.writes.size)
            assertEquals(result, session.state.value[result.key]?.lastResult)
            assertFalse(session.state.value.getValue(result.key).isChanging)
        }
    }

    @Test
    fun alreadySatisfiedDoesNotWrite() = runTest {
        val bridge = FakeBridge().apply { flags[Target.toString()] = true }
        withSession(bridge) { session ->
            val result = session.settings.set(Target, flagDefinition(), Enabled).await()
            assertEquals(SettingOutcome.AlreadySatisfied, result.outcome)
            assertTrue(bridge.writes.isEmpty())
        }
    }

    @Test
    fun readOnlySettingRejectsChange() = runTest {
        val handler = object : SettingHandler by FlagHandler() {
            override suspend fun inspect(context: SettingContext) =
                SettingSnapshot(Disabled, SettingAccess.ReadOnly, "Read only on this ROM")
        }
        val bridge = FakeBridge()
        withSession(bridge, catalog = catalogOf(flagDefinition(handler = handler))) { session ->
            val result = session.settings.set(Target, flagDefinition(handler = handler), Enabled).await()
            assertEquals(SettingOutcome.Failed, result.outcome)
            assertEquals("Read only on this ROM", result.reason?.message)
            assertTrue(bridge.writes.isEmpty())
        }
    }

    @Test
    fun failedInitialReadDoesNotTurnIntoDisabledOrWrite() = runTest {
        val bridge = FakeBridge().apply {
            onExecute = { ShellResult(1, stderr = "Permission denied") }
        }
        withSession(bridge) { session ->
            val result = session.settings.set(Target, flagDefinition(), Enabled).await()
            assertEquals(SettingOutcome.Failed, result.outcome)
            assertNull(result.before?.snapshot?.value)
            assertEquals(SettingAccess.Unavailable, result.before?.snapshot?.access)
            assertTrue(bridge.writes.isEmpty())
        }
    }

    @Test
    fun nonzeroExitStillVerifiesAnAppliedChange() = runTest {
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                val response = respond(request)
                if ((request.command as ShellCommand.Arguments).values[0] == "write-flag") {
                    ShellResult(1, stderr = "Reported an error after changing state")
                } else response
            }
        }
        withSession(bridge) { session ->
            val result = session.settings.set(Target, flagDefinition(), Enabled).await()
            assertEquals(SettingOutcome.Verified, result.outcome)
            assertTrue(result.evidence.any { it.result?.exitCode == 1 })
        }
    }

    @Test
    fun zeroExitDoesNotImplySuccess() = runTest {
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                if ((request.command as ShellCommand.Arguments).values[0] == "write-flag") {
                    ShellResult(0)
                } else respond(request)
            }
        }
        withSession(bridge) { session ->
            val result = session.settings.set(Target, flagDefinition(), Enabled).await()
            assertEquals(SettingOutcome.Failed, result.outcome)
            assertEquals(Disabled, result.after?.snapshot?.value)
        }
    }

    @Test
    fun unreadableResultIsUncertain() = runTest {
        var reads = 0
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                if ((request.command as ShellCommand.Arguments).values[0] == "read-flag" && ++reads > 1) {
                    ShellResult(0, "unknown")
                } else respond(request)
            }
        }
        withSession(bridge) { session ->
            val result = session.settings.set(Target, flagDefinition(), Enabled).await()
            assertEquals(SettingOutcome.Uncertain, result.outcome)
            assertNull(result.after?.snapshot?.value)
        }
    }

    @Test
    fun disconnectAfterWritingIsNotReportedAsSuccess() = runTest {
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                val response = respond(request)
                if ((request.command as ShellCommand.Arguments).values[0] == "write-flag") {
                    connectionState.value = BridgeState.Disconnected
                }
                response
            }
        }
        withSession(bridge) { session ->
            val result = session.settings.set(Target, flagDefinition(), Enabled).await()
            assertEquals(SettingOutcome.Uncertain, result.outcome)
            assertEquals(SettingAccess.Unavailable, result.after?.snapshot?.access)
        }
    }

    @Test
    fun delayedVerificationRetriesReadsWithoutRepeatingWrite() = runTest {
        var reads = 0
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                when ((request.command as ShellCommand.Arguments).values[0]) {
                    "write-flag" -> ShellResult(0)
                    else -> {
                        reads++
                        ShellResult(0, (reads >= 3).toString())
                    }
                }
            }
        }
        val handler = object : SettingHandler by FlagHandler() {
            override val verificationPolicy = VerificationPolicy(attempts = 3, delayMillis = 20)
        }
        withSession(bridge, catalog = catalogOf(flagDefinition(handler = handler))) { session ->
            val result = session.settings.set(Target, flagDefinition(handler = handler), Enabled).await()
            assertEquals(SettingOutcome.Verified, result.outcome)
            assertEquals(3, reads)
            assertEquals(1, bridge.writes.size)
        }
    }

    @Test
    fun duplicateRequestsShareOneOperationAndConflictingRequestIsRejected() = runTest {
        val bridge = FakeBridge()
        withSession(bridge) { session ->
            val definition = flagDefinition()
            val first = session.settings.set(Target, definition, Enabled)
            val duplicate = session.settings.set(Target, definition, Enabled)
            assertSame(first, duplicate)
            expectFailure<OperationInProgressException> {
                session.settings.set(Target, definition, Disabled)
            }
            first.await()
            assertEquals(1, bridge.writes.size)
        }
    }

    @Test
    fun mutationsOnDifferentTargetsAreSerialized() = runTest {
        var activeWrites = 0
        var maxWrites = 0
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                if ((request.command as ShellCommand.Arguments).values[0] == "write-flag") {
                    activeWrites++
                    maxWrites = maxOf(activeWrites, maxWrites)
                    try {
                        delay(20)
                        respond(request)
                    } finally {
                        activeWrites--
                    }
                } else respond(request)
            }
        }
        withSession(bridge) { session ->
            val definition = flagDefinition()
            val first = session.settings.set(Target, definition, Enabled)
            val second = session.settings.set(AppTarget(10, Target.packageName), definition, Enabled)
            first.await()
            second.await()
            assertEquals(1, maxWrites)
            assertEquals(2, bridge.writes.size)
        }
    }

    @Test
    fun operationTimeoutAfterWriteAttemptIsUncertainAndDoesNotRetry() = runTest {
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                if ((request.command as ShellCommand.Arguments).values[0] == "write-flag") {
                    delay(1_000)
                }
                respond(request)
            }
        }
        withSession(bridge, options = CoreOptions(operationTimeoutMillis = 50)) { session ->
            val result = session.settings.set(Target, flagDefinition(), Enabled).await()
            assertEquals(SettingOutcome.Uncertain, result.outcome)
            assertEquals(1, bridge.writes.size)
            assertNull(result.after)
            assertFalse(session.state.value.getValue(result.key).isChanging)
        }
    }

    @Test
    fun cancellingStartedMutationKeepsAnUncertainResult() = runTest {
        val started = CompletableDeferred<Unit>()
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                val response = respond(request)
                if ((request.command as ShellCommand.Arguments).values[0] == "write-flag") {
                    started.complete(Unit)
                    awaitCancellation()
                }
                response
            }
        }
        withSession(bridge) { session ->
            val operation = session.settings.set(Target, flagDefinition(), Enabled)
            runCurrent()
            started.await()
            operation.cancel()
            expectFailure<CancellationException> { operation.await() }
            runCurrent()
            val status = session.state.value.values.single()
            assertFalse(status.isChanging)
            assertEquals(SettingOutcome.Uncertain, status.lastResult?.outcome)
            assertEquals(true, bridge.flags[Target.toString()])
            assertEquals(1, bridge.writes.size)
        }
    }

    @Test
    fun actionRequiresItsOwnVerifier() = runTest {
        var applied = false
        val handler = object : SettingHandler {
            override suspend fun inspect(context: SettingContext) =
                SettingSnapshot(SettingValue.Action)
            override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
                applied = true
            }
        }
        val definition = com.napreth.set4u.core.settings.SettingDefinition("sample.action", com.napreth.set4u.core.settings.ValueType.Action, { handler })
        withSession(catalog = catalogOf(definition)) { session ->
            val result = session.settings.set(Target, definition, SettingValue.Action).await()
            assertTrue(applied)
            assertEquals(SettingOutcome.Uncertain, result.outcome)
        }
    }

    @Test
    fun customVerificationCanReportPartialCompletion() = runTest {
        val handler = object : SettingHandler by FlagHandler() {
            override fun verify(
                before: SettingSnapshot,
                after: SettingSnapshot,
                desiredValue: SettingValue,
            ) = Verification.Partial(FailureReason("Only the native policy changed"))
        }
        withSession(catalog = catalogOf(flagDefinition(handler = handler))) { session ->
            val result = session.settings.set(Target, flagDefinition(handler = handler), Enabled).await()
            assertEquals(SettingOutcome.PartiallyCompleted, result.outcome)
            assertEquals("Only the native policy changed", result.reason?.message)
        }
    }

    @Test
    fun queryHasAnOverallTimeLimit() = runTest {
        val handler = object : SettingHandler by FlagHandler() {
            override suspend fun inspect(context: SettingContext): SettingSnapshot {
                delay(1_000)
                return SettingSnapshot(Enabled)
            }
        }
        withSession(options = CoreOptions(operationTimeoutMillis = 50), catalog = catalogOf(flagDefinition(handler = handler))) { session ->
            val observation = session.settings.read(Target, flagDefinition(handler = handler))
            assertNull(observation.snapshot.value)
            assertEquals(SettingAccess.Unavailable, observation.snapshot.access)
            assertEquals("Read timed out", observation.snapshot.message)
            assertEquals(50L, testScheduler.currentTime)
        }
    }

    @Test
    fun closingSessionCancelsMutationAndPreservesUncertainty() = runTest {
        val started = CompletableDeferred<Unit>()
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                val response = respond(request)
                if ((request.command as ShellCommand.Arguments).values[0] == "write-flag") {
                    started.complete(Unit)
                    awaitCancellation()
                }
                response
            }
        }
        withSession(bridge) { session ->
            val operation = session.settings.set(Target, flagDefinition(), Enabled)
            runCurrent()
            started.await()
            session.close()
            assertTrue(operation.isCancelled)
            assertEquals(1, bridge.closeCalls)
            val status = session.state.value.values.single()
            assertFalse(status.isChanging)
            assertEquals(SettingOutcome.Uncertain, status.lastResult?.outcome)
        }
    }

    @Test
    fun cancellingQueuedMutationDoesNotClaimItWasApplied() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                if ((request.command as ShellCommand.Arguments).values[0] == "write-flag") {
                    started.complete(Unit)
                    release.await()
                }
                respond(request)
            }
        }
        withSession(bridge) { session ->
            val definition = flagDefinition()
            val first = session.settings.set(Target, definition, Enabled)
            runCurrent()
            started.await()
            val otherTarget = AppTarget(10, Target.packageName)
            val queued = session.settings.set(otherTarget, definition, Enabled)
            queued.cancel()
            runCurrent()
            val status = session.state.value.entries.single { it.key.target == otherTarget }.value
            assertFalse(status.isChanging)
            assertEquals(SettingOutcome.Failed, status.lastResult?.outcome)
            assertTrue(status.lastResult!!.evidence.isEmpty())
            release.complete(Unit)
            first.await()
            assertEquals(1, bridge.writes.size)
        }
    }

    @Test
    fun absentDiagnosticOverrideDoesNotEraseFailureReason() = runTest {
        val handler = object : SettingHandler by FlagHandler() {
            override suspend fun apply(context: SettingContext, desiredValue: SettingValue) = Unit
            override fun explain(result: SettingResult): FailureReason? = null
        }
        withSession(catalog = catalogOf(flagDefinition(handler = handler))) { session ->
            val result = session.settings.set(Target, flagDefinition(handler = handler), Enabled).await()
            assertEquals(SettingOutcome.Failed, result.outcome)
            assertNotNull(result.reason)
        }
    }
}
