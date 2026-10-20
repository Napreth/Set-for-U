package com.napreth.set4u.core.execution

import com.napreth.set4u.core.bridge.ShellCommand
import com.napreth.set4u.core.bridge.ShellRequest
import com.napreth.set4u.core.bridge.ShellResult
import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.settings.SettingValue
import com.napreth.set4u.core.settings.isAction
import com.napreth.set4u.core.state.SettingAccess
import com.napreth.set4u.core.state.SettingKey
import com.napreth.set4u.core.state.SettingObservation
import com.napreth.set4u.core.state.SettingSnapshot
import kotlinx.coroutines.CancellationException

enum class ReasonCertainty { Certain, Possible, Unknown }

data class FailureReason(
    val message: String,
    val certainty: ReasonCertainty = ReasonCertainty.Certain,
)

sealed interface CheckDecision {
    data object Proceed : CheckDecision
    data object AlreadySatisfied : CheckDecision
    data class Blocked(val reason: FailureReason) : CheckDecision
}

sealed interface Verification {
    data object Satisfied : Verification
    data class NotSatisfied(val reason: FailureReason) : Verification
    data class Partial(val reason: FailureReason) : Verification
    data class Unknown(val reason: FailureReason) : Verification
}

data class VerificationPolicy(val attempts: Int = 1, val delayMillis: Long = 100) {
    init {
        require(attempts in 1..100)
        require(delayMillis >= 0)
    }
}

/** Business behavior shared by settings components and direct application operations. */
interface SettingHandler : Handler {
    val verificationPolicy: VerificationPolicy get() = VerificationPolicy()

    suspend fun inspect(context: SettingContext): SettingSnapshot
    suspend fun apply(context: SettingContext, desiredValue: SettingValue)
    suspend fun prepare(context: SettingContext, desiredValue: SettingValue) {}

    fun check(before: SettingSnapshot, desiredValue: SettingValue): CheckDecision {
        if (!desiredValue.isAction && before.value == desiredValue) {
            return CheckDecision.AlreadySatisfied
        }
        return if (before.access == SettingAccess.Writable) CheckDecision.Proceed
        else CheckDecision.Blocked(FailureReason(before.message ?: "Setting is ${before.access}"))
    }

    fun verify(
        before: SettingSnapshot,
        after: SettingSnapshot,
        desiredValue: SettingValue,
    ): Verification = when {
        desiredValue.isAction -> Verification.Unknown(
            FailureReason("Action requires a specific verification rule", ReasonCertainty.Unknown)
        )
        after.value == null -> Verification.Unknown(
            FailureReason(after.message ?: "Unable to read the resulting state", ReasonCertainty.Unknown)
        )
        after.value == desiredValue -> Verification.Satisfied
        else -> Verification.NotSatisfied(FailureReason("The resulting state does not match the request"))
    }

    fun explain(result: SettingResult): FailureReason? = result.reason
}

internal data class SettingOperation(
    val settingId: String,
    val target: SettingTarget,
    val desiredValue: SettingValue,
    val handler: SettingHandler,
    val admissionKey: String? = null,
    val guard: () -> Unit = {},
    val retainedValue: SettingValue = desiredValue,
) {
    init { require(settingId.isNotBlank()) }
}

enum class SettingOutcome {
    AlreadySatisfied, Verified, Failed, PartiallyCompleted, Uncertain
}

data class CommandEvidence(
    val request: ShellRequest,
    val result: ShellResult? = null,
    val error: String? = null,
)

data class SettingResult(
    val operationId: String,
    val key: SettingKey,
    val desiredValue: SettingValue,
    val outcome: SettingOutcome,
    val before: SettingObservation?,
    val after: SettingObservation?,
    val evidence: List<CommandEvidence>,
    val reason: FailureReason? = null,
    val evidenceTruncated: Boolean = false,
)

open class HandlerContext internal constructor(
    val target: SettingTarget,
    private val executor: ShellExecutor,
    private val evidence: EvidenceLog,
) {
    @Volatile private var active = true
    internal fun finish() { active = false }
    suspend fun execute(request: ShellRequest): ShellResult {
        check(active) { "Handler context is no longer active" }
        try {
            val result = executor.execute(request)
            evidence.add(CommandEvidence(request, result))
            return result
        } catch (exception: CancellationException) {
            evidence.add(CommandEvidence(request, error = "Cancelled; execution may have started"))
            throw exception
        } catch (exception: Exception) {
            evidence.add(CommandEvidence(request, error = exception.message ?: exception.javaClass.simpleName))
            throw exception
        }
    }

    suspend fun execute(command: ShellCommand): ShellResult = execute(ShellRequest(command))

    suspend fun <Request : Any, Response : Any> executeTask(
        descriptor: com.napreth.set4u.core.device.DeviceTaskDescriptor<Request, Response>,
        request: Request,
        timeoutMillis: Long = 15_000,
    ): Response {
        check(active) { "Handler context is no longer active" }
        val diagnostic = ShellRequest(ShellCommand.arguments("device-task"), evidenceLabel = "device-task:${descriptor.id}")
        try {
            return executor.executeTask(target, descriptor, request, timeoutMillis).also {
                evidence.add(CommandEvidence(diagnostic, ShellResult(0)))
            }
        } catch (exception: Exception) {
            evidence.add(CommandEvidence(diagnostic, error = "Device task did not complete"))
            throw exception
        }
    }
}

class SettingContext internal constructor(target: SettingTarget, executor: ShellExecutor, evidence: EvidenceLog) :
    HandlerContext(target, executor, evidence)

internal class EvidenceLog {
    private val entries = mutableListOf<CommandEvidence>()
    var truncated: Boolean = false
        private set
    @Synchronized fun add(evidence: CommandEvidence) {
        if (entries.size == 32) { truncated = true; return }
        val request = evidence.request
        val result = evidence.result?.let {
            val out = if (request.retainOutputInEvidence) it.stdout.take(512) else ""
            val err = if (request.retainOutputInEvidence) it.stderr.take(512) else ""
            val cutOut = out.length < it.stdout.length
            val cutErr = err.length < it.stderr.length
            if (cutOut || cutErr) truncated = true
            it.copy(stdout = out, stderr = err, stdoutTruncated = it.stdoutTruncated || cutOut,
                stderrTruncated = it.stderrTruncated || cutErr)
        }
        entries += CommandEvidence(
            request.copy(command = ShellCommand.arguments(request.evidenceLabel.ifBlank { "shell" })),
            result, evidence.error?.let { "Command did not complete" },
        )
    }
    @Synchronized fun snapshot(): List<CommandEvidence> = entries.toList()
}
