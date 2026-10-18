package com.napreth.set4u.core.execution

import com.napreth.set4u.core.CoreOptions
import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.state.SettingAccess
import com.napreth.set4u.core.state.SettingKey
import com.napreth.set4u.core.state.SettingObservation
import com.napreth.set4u.core.state.SettingSnapshot
import com.napreth.set4u.core.state.SettingStateStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

class OperationInProgressException(settingId: String) :
    IllegalStateException("A different request for $settingId is already running")

internal class ExecutionEngine(
    private val sessionId: String,
    private val scope: CoroutineScope,
    private val shell: ShellExecutor,
    private val store: SettingStateStore,
    private val options: CoreOptions,
    private val clock: () -> Long,
) {
    // The initial implementation also serializes reads to avoid stale query publication.
    private val gate = OperationScheduler()
    private val inFlight = mutableMapOf<SettingKey, InFlight>()

    internal fun key(target: SettingTarget, settingId: String) =
        SettingKey(sessionId, target, settingId)

    fun submit(operation: SettingOperation): Deferred<SettingResult> = synchronized(inFlight) {
        check(scope.isActive) { "Session is closed" }
        val key = key(operation.target, operation.settingId)
        inFlight[key]?.let { previous ->
            if (previous.operation.desiredValue == operation.desiredValue && previous.operation.admissionKey == operation.admissionKey) return@synchronized previous.job
            throw OperationInProgressException(operation.settingId)
        }

        val trace = Trace(UUID.randomUUID().toString(), key, operation)
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                gate.run(write = true, options.queueTimeoutMillis) {
                    store.running(key, trace.id)
                    execute(trace)
                }
            } catch (_: QueueTimeoutException) {
                trace.result(SettingOutcome.Failed, FailureReason("Operation queue timed out")).also {
                    store.finish(it)
                    trace.finished = true
                }
            }
        }
        inFlight[key] = InFlight(operation, job)
        store.start(key, trace.id, operation.desiredValue)
        job.invokeOnCompletion {
            if (!trace.finished) {
                store.finish(trace.result(
                    SettingOutcome.Failed,
                    FailureReason("Operation was cancelled before execution"),
                ))
            }
            synchronized(inFlight) {
                if (inFlight[key]?.job === job) inFlight.remove(key)
            }
        }
        job.start()
        job
    }

    internal suspend fun read(
        target: SettingTarget,
        settingId: String,
        handler: SettingHandler,
    ): SettingObservation {
        check(scope.isActive) { "Session is closed" }
        val job = scope.async {
            val key = key(target, settingId)
            try {
                gate.run(write = false, options.queueTimeoutMillis) {
                withTimeoutOrNull(options.operationTimeoutMillis) {
                    observe(key, handler, context(target, EvidenceLog()))
                } ?: SettingObservation(
                    key,
                    SettingSnapshot(null, SettingAccess.Unavailable, "Read timed out"),
                    clock(),
                ).also(store::observe)
                }
            } catch (_: QueueTimeoutException) {
                SettingObservation(key, SettingSnapshot(null, SettingAccess.Busy, "其他操作正在执行，尚未读取；请稍后刷新"), clock()).also(store::observe)
            }
        }
        try {
            return job.await()
        } finally {
            if (!job.isCompleted) job.cancel()
        }
    }

    private suspend fun execute(trace: Trace): SettingResult {
        var result: SettingResult? = null
        try {
            result = withTimeoutOrNull(options.operationTimeoutMillis) {
                runOperation(trace)
            } ?: trace.result(
                if (trace.applyAttempted) SettingOutcome.Uncertain else SettingOutcome.Failed,
                FailureReason(
                    "Operation timed out",
                    if (trace.applyAttempted) ReasonCertainty.Unknown else ReasonCertainty.Certain,
                ),
            )
            if (result.outcome != SettingOutcome.Verified &&
                result.outcome != SettingOutcome.AlreadySatisfied
            ) {
                try {
                    result = result.copy(reason = trace.operation.handler.explain(result) ?: result.reason)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // Diagnostic failure must not erase the execution result.
                }
            }
            return result
        } catch (exception: CancellationException) {
            result = trace.result(
                if (trace.applyAttempted) SettingOutcome.Uncertain else SettingOutcome.Failed,
                FailureReason(
                    "Operation was cancelled; already applied changes are not undone",
                    if (trace.applyAttempted) ReasonCertainty.Unknown else ReasonCertainty.Certain,
                ),
            )
            throw exception
        } catch (exception: Exception) {
            result = trace.result(
                if (trace.applyAttempted) SettingOutcome.Uncertain else SettingOutcome.Failed,
                FailureReason(
                    exception.message ?: exception.javaClass.simpleName,
                    if (trace.applyAttempted) ReasonCertainty.Unknown else ReasonCertainty.Certain,
                ),
            )
            return result
        } finally {
            result?.let(store::finish)
            trace.finished = true
        }
    }

    private suspend fun runOperation(trace: Trace): SettingResult {
        val operation = trace.operation
        val handler = operation.handler
        val context = context(operation.target, trace.evidence)
        operation.guard()
        val before = observe(trace.key, handler, context)
        trace.before = before
        when (val decision = handler.check(before.snapshot, operation.desiredValue)) {
            CheckDecision.AlreadySatisfied -> return trace.result(SettingOutcome.AlreadySatisfied)
            is CheckDecision.Blocked -> return trace.result(SettingOutcome.Failed, decision.reason)
            CheckDecision.Proceed -> Unit
        }

        handler.prepare(context, operation.desiredValue)
        operation.guard()
        trace.applyAttempted = true
        var applyError: FailureReason? = null
        try {
            handler.apply(context, operation.desiredValue)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            applyError = FailureReason(
                exception.message ?: exception.javaClass.simpleName,
                ReasonCertainty.Possible,
            )
        }

        val policy = handler.verificationPolicy
        var verification: Verification = Verification.Unknown(
            FailureReason("Verification did not complete", ReasonCertainty.Unknown)
        )
        repeat(policy.attempts) { attempt ->
            if (attempt > 0) delay(policy.delayMillis)
            val after = observe(trace.key, handler, context)
            trace.after = after
            verification = handler.verify(before.snapshot, after.snapshot, operation.desiredValue)
            when (val current = verification) {
                Verification.Satisfied -> return trace.result(SettingOutcome.Verified)
                is Verification.Partial ->
                    return trace.result(SettingOutcome.PartiallyCompleted, current.reason)
                else -> Unit
            }
        }
        return when (val current = verification) {
            is Verification.NotSatisfied ->
                trace.result(SettingOutcome.Failed, applyError ?: current.reason)
            is Verification.Unknown -> trace.result(SettingOutcome.Uncertain, current.reason)
            is Verification.Partial -> trace.result(SettingOutcome.PartiallyCompleted, current.reason)
            Verification.Satisfied -> trace.result(SettingOutcome.Verified)
        }
    }

    private fun context(target: SettingTarget, evidence: EvidenceLog) =
        SettingContext(target, shell, evidence)

    private suspend fun observe(
        key: SettingKey,
        handler: SettingHandler,
        context: SettingContext,
    ): SettingObservation {
        val snapshot = try {
            shell.requireReady()
            handler.inspect(context)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            SettingSnapshot(
                value = null,
                access = SettingAccess.Unavailable,
                message = exception.message ?: exception.javaClass.simpleName,
            )
        }
        currentCoroutineContext().ensureActive()
        return SettingObservation(key, snapshot, clock()).also(store::observe)
    }

    private data class InFlight(
        val operation: SettingOperation,
        val job: Deferred<SettingResult>,
    )

    private class Trace(
        val id: String,
        val key: SettingKey,
        val operation: SettingOperation,
    ) {
        val evidence = EvidenceLog()
        var before: SettingObservation? = null
        var after: SettingObservation? = null
        var applyAttempted = false
        var finished = false

        fun result(outcome: SettingOutcome, reason: FailureReason? = null) = SettingResult(
            id, key, operation.desiredValue, outcome, before, after, evidence.snapshot(), reason, evidence.truncated,
        )
    }
}
