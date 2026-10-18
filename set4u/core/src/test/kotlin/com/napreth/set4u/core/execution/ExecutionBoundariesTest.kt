@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.execution

import com.napreth.set4u.core.*
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.session.AppTarget
import com.napreth.set4u.core.state.*
import java.io.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionBoundariesTest {
    @Test fun busyReadPreservesTheLastObservationAndCanBeRetried() = runTest {
        val release = CompletableDeferred<Unit>()
        val handler = object : SettingHandler by FlagHandler() {
            override suspend fun apply(context: SettingContext, desiredValue: com.napreth.set4u.core.settings.SettingValue) {
                release.await()
                FlagHandler().apply(context, desiredValue)
            }
        }
        val definition = flagDefinition(handler = handler)
        withSession(catalog = catalogOf(definition), options = CoreOptions(queueTimeoutMillis = 50)) { session ->
            val old = session.settings.read(Target, definition)
            val write = session.settings.set(AppTarget(10, Target.packageName), definition, Enabled)
            runCurrent()
            val busy = session.settings.read(Target, definition)
            assertEquals(SettingAccess.Busy, busy.snapshot.access)
            val status = session.state.value.getValue(old.key)
            assertEquals(old, status.observation)
            assertTrue(status.readDeferred)
            release.complete(Unit); write.await()
            session.settings.read(Target, definition)
            assertFalse(session.state.value.getValue(old.key).readDeferred)
        }
    }

    @Test fun shellRetainsOnlyItsBudgetAndDrainsBothStreams() = runBlocking {
        val drained = AtomicLong()
        fun output() = object : InputStream() {
            var remaining = 8 * 1024 * 1024
            override fun read(): Int = error("Use buffered reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0) return -1
                val count = minOf(length, remaining)
                bytes.fill(65, offset, offset + count)
                remaining -= count
                drained.addAndGet(count.toLong())
                return count
            }
        }
        val process = object : RemoteProcess {
            override val stdin = ByteArrayOutputStream()
            override val stdout = output()
            override val stderr = output()
            override suspend fun awaitExit() = 0
            override suspend fun close() {}
        }
        val result = collectShellResult(process, 65536)
        assertEquals(65536, result.stdout.length + result.stderr.length)
        assertTrue(result.stdoutTruncated && result.stderrTruncated)
        assertEquals(16L * 1024 * 1024, drained.get())
        expectFailure<IllegalStateException> { result.requireSuccess() }
        Unit
    }

    @Test fun queuedWriteExpiresWithoutRunningAndShowsWaitingPhase() = runTest {
        val release = CompletableDeferred<Unit>()
        val blocking = object : SettingHandler by FlagHandler() {
            override suspend fun inspect(context: SettingContext): SettingSnapshot {
                release.await()
                return FlagHandler().inspect(context)
            }
        }
        withSession(options = CoreOptions(queueTimeoutMillis = 50), catalog = catalogOf(flagDefinition(handler = blocking))) { session ->
            val first = session.settings.set(Target, flagDefinition(handler = blocking), Enabled)
            runCurrent()
            val queued = session.settings.set(AppTarget(10, Target.packageName), flagDefinition(handler = blocking), Enabled)
            assertEquals(OperationPhase.Queued, session.state.value.values.single { it.requestedValue == Enabled && it.phase == OperationPhase.Queued }.phase)
            val result = queued.await()
            assertEquals("Operation queue timed out", result.reason?.message)
            assertNull(result.before)
            assertTrue(result.evidence.isEmpty())
            assertEquals(50L, testScheduler.currentTime)
            release.complete(Unit)
            assertEquals(SettingOutcome.Verified, first.await().outcome)
        }
    }

    @Test fun waitingWritesGoBeforeReadsAndCancelledTicketsReleaseAdmission() = runTest {
        val scheduler = OperationScheduler()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async { scheduler.run(false, 1000) { release.await() } }
        runCurrent()
        val cancelled = async { scheduler.run(true, 1000) { error("Cancelled ticket ran") } }
        val read = async { scheduler.run(false, 1000) { order += "read" } }
        val write = async { scheduler.run(true, 1000) { order += "write" } }
        runCurrent()
        cancelled.cancelAndJoin()
        release.complete(Unit)
        first.await(); read.await(); write.await()
        assertEquals(listOf("write", "read"), order)
    }

    @Test fun sessionCloseReturnsEvenWhenBridgeIgnoresCancellation() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val bridge = object : IAdbBridge {
            override val connectionState = MutableStateFlow(BridgeState.Ready)
            override suspend fun execute(request: ShellRequest) = ShellResult(0)
            override suspend fun close() = withContext(NonCancellable) { started.complete(Unit); release.await() }
        }
        val session = Core.open(bridge, options = CoreOptions(shutdownTimeoutMillis = 60))
        try {
            val began = System.nanoTime()
            session.close()
            assertTrue("Close must abandon an uncooperative cleanup", (System.nanoTime() - began) / 1_000_000 < 600)
            withTimeout(1000) { started.await() }
        } finally { release.complete(Unit) }
    }

    @Test fun evidenceHasASeparateBudgetAndOmitsSensitiveTextByDefault() = runTest {
        val secret = "password=private-value"
        val bridge = FakeBridge().apply { onExecute = { ShellResult(0, secret.repeat(10000)) } }
        val handler = object : SettingHandler {
            override suspend fun inspect(context: SettingContext): SettingSnapshot {
                repeat(40) { context.execute(ShellCommand.arguments("read", secret)) }
                return SettingSnapshot(Disabled)
            }
            override suspend fun apply(context: SettingContext, desiredValue: com.napreth.set4u.core.settings.SettingValue) {}
        }
        withSession(bridge, catalog = catalogOf(flagDefinition(handler = handler))) { session ->
            val result = session.settings.set(Target, flagDefinition(handler = handler), Enabled).await()
            assertEquals(32, result.evidence.size)
            assertTrue(result.evidenceTruncated)
            assertFalse(result.evidence.toString().contains(secret))
        }
    }
}
