package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.settings.SettingDefinition
import com.napreth.set4u.core.settings.SettingValue
import com.napreth.set4u.core.state.SettingAccess
import com.napreth.set4u.core.state.SettingSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/** Session-local device snapshot; refresh is explicit and never starts a background device monitor. */
class CompatibilityService internal constructor(
    private val registry: CompatibilityRegistry,
    private val source: DeviceProfileSource,
    private val requireOpen: () -> Unit,
    private val environmentSource: com.napreth.set4u.core.bridge.ExecutionEnvironmentSource,
) {
    private val gate = Mutex()
    private var cached: DeviceProfile? = null

    suspend fun deviceProfile(refresh: Boolean = false): DeviceProfile = gate.withLock {
        requireOpen()
        if (refresh) cached = null
        cached ?: source.collect().also { cached = it }
    }
    suspend fun invalidate() { gate.withLock { cached = null }; supportGate.withLock { supportCache.clear() } }

    suspend fun resolve(definition: SettingDefinition): CompatibilityResolution {
        requireOpen()
        if (!registry.hasRules(definition.id)) {
            return if (definition.binding != null && definition.operationFunctions.isEmpty()) CompatibilityResolution.Supported("default", "legacy.default")
            else CompatibilityResolution.Unsupported("No compatibility rules for ${definition.id}")
        }
        val resolution = try {
            registry.resolve(definition.id, if (registry.needsProfile(definition.id)) deviceProfile() else DeviceProfile(emptyMap()))
        } catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { return CompatibilityResolution.Unknown(exception.message ?: "Unable to read device information") }
        if (resolution is CompatibilityResolution.Supported && !definition.hasOperation(resolution.operationId)) {
            return CompatibilityResolution.Unknown("Operation ${resolution.operationId} is not registered by ${definition.id}")
        }
        return resolution
    }

    private val supportGate = Mutex()
    private val supportCache = mutableMapOf<String, DeviceSupport>()

    suspend fun assess(definition: SettingDefinition): CapabilityAssessment {
        requireOpen()
        val policy = registry.policies[definition.id] ?: CapabilityPolicy()
        val support = supportGate.withLock {
            supportCache[definition.id] ?: try {
                (policy.supportProbe?.probe(deviceProfile()) ?: DeviceSupport.Supported).also {
                    if (it !is DeviceSupport.Unknown) supportCache[definition.id] = it
                }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { DeviceSupport.Unknown(exception.message ?: "Hardware probe failed") }
        }
        if (support is DeviceSupport.Unsupported) return CapabilityAssessment(support, OperationAvailability.Unavailable(support.reason))
        if (support is DeviceSupport.Unknown) return CapabilityAssessment(support, OperationAvailability.Unknown(support.reason))
        fun fallback(reason: OperationAvailability): OperationAvailability = policy.manual?.let { OperationAvailability.Manual(it) } ?: reason
        val selected = when (val resolution = resolve(definition)) {
            is CompatibilityResolution.Supported -> resolution.operationId
            is CompatibilityResolution.Unsupported -> return if (resolution.ruleId != null)
                CapabilityAssessment(DeviceSupport.Unsupported(resolution.reason), OperationAvailability.Unavailable(resolution.reason))
                else CapabilityAssessment(support, fallback(OperationAvailability.Unavailable(resolution.reason)))
            is CompatibilityResolution.Unknown -> return CapabilityAssessment(support, fallback(OperationAvailability.Unknown(resolution.reason)))
        }
        if (policy.identities.isNotEmpty() || policy.permissions.isNotEmpty()) {
            val environment = try { environmentSource.read() }
            catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { return CapabilityAssessment(support, fallback(OperationAvailability.Unknown("Cannot establish execution identity"))) }
            if (policy.identities.isNotEmpty() && environment.identity !in policy.identities) return CapabilityAssessment(support, fallback(OperationAvailability.RequiresPrivilege(policy.identities)))
            val missing = policy.permissions - environment.permissions
            if (missing.isNotEmpty()) return CapabilityAssessment(support, fallback(OperationAvailability.RequiresPermission(missing)))
        }
        return CapabilityAssessment(support, OperationAvailability.Direct(selected))
    }

    internal fun handler(definition: SettingDefinition): SettingHandler = object : SettingHandler {
        private var selected: SettingHandler? = null
        private var operationId: String? = null
        private var assessment: CapabilityAssessment? = null
        override val verificationPolicy: VerificationPolicy get() = selected?.verificationPolicy ?: VerificationPolicy()

        override suspend fun inspect(context: SettingContext): SettingSnapshot {
            if (selected == null) {
                val current = assess(definition).also { assessment = it }
                val direct = current.operation as? OperationAvailability.Direct
                if (direct == null) return blocked(current)
                operationId = direct.operationId
                selected = definition.createOperation(direct.operationId)
            }
            return checkNotNull(selected).inspect(context).copy(capability = assessment)
        }
        override fun check(before: SettingSnapshot, desiredValue: SettingValue): CheckDecision =
            selected?.check(before, desiredValue) ?: CheckDecision.Blocked(FailureReason(before.message ?: "No available operation"))
        override suspend fun prepare(context: SettingContext, desiredValue: SettingValue) {
            // The UI's earlier assessment never authorizes a write. Recheck at execution time.
            val current = assess(definition)
            check((current.operation as? OperationAvailability.Direct)?.operationId == operationId) { blocked(current).message ?: "Execution capability changed" }
            checkNotNull(selected).prepare(context, desiredValue)
        }
        override suspend fun apply(context: SettingContext, desiredValue: SettingValue) = checkNotNull(selected).apply(context, desiredValue)
        override fun verify(before: SettingSnapshot, after: SettingSnapshot, desiredValue: SettingValue) = checkNotNull(selected).verify(before, after, desiredValue)
        override fun explain(result: SettingResult) = selected?.explain(result) ?: result.reason
    }

    private fun blocked(current: CapabilityAssessment): SettingSnapshot {
        val support = current.support
        val operation = current.operation
        val access = when {
            support is DeviceSupport.Unsupported -> SettingAccess.Unsupported
            operation is OperationAvailability.Manual -> SettingAccess.Manual
            else -> SettingAccess.Unavailable
        }
        val reason = when (operation) {
            is OperationAvailability.Direct -> "Operation is available"
            is OperationAvailability.Manual -> "Open system settings"
            is OperationAvailability.RequiresPrivilege -> "Required execution identity: ${operation.identities}"
            is OperationAvailability.RequiresPermission -> "Required permissions: ${operation.permissions}"
            is OperationAvailability.Unavailable -> operation.reason
            is OperationAvailability.Unknown -> operation.reason
        }
        return SettingSnapshot(null, access, reason, current)
    }
}
