package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.session.*
import com.napreth.set4u.core.settings.SettingDefinition
import com.napreth.set4u.core.settings.SettingValue
import com.napreth.set4u.core.state.SettingAccess
import com.napreth.set4u.core.state.SettingSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/** Device facts are session local. Execution permission is always checked against the current bridge. */
class CompatibilityService internal constructor(
    private val registry: CompatibilityRegistry,
    private val source: DeviceProfileSource,
    private val requireOpen: () -> Unit,
    private val environmentSource: ExecutionEnvironmentSource,
    private val executor: (suspend (ShellRequest) -> ShellResult)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val gate = Mutex()
    private var cached: DeviceProfile? = null
    private val supportGate = Mutex()
    private data class SupportFact(val support: DeviceSupport, val observedAt: Long)
    private val supportCache = mutableMapOf<String, SupportFact>()
    private val probeGate = Mutex()
    private data class CachedProbe(val observation: ProbeObservation, val observedAt: Long)
    private val probeCache = mutableMapOf<String, CachedProbe>()

    suspend fun deviceProfile(refresh: Boolean = false): DeviceProfile {
        requireOpen()
        if (refresh) invalidate()
        return gate.withLock { cached ?: source.collect().also { cached = it } }
    }
    suspend fun invalidate() {
        gate.withLock { cached = null }; supportGate.withLock { supportCache.clear() }; probeGate.withLock { probeCache.clear() }; displayEnvironmentGate.withLock { displayEnvironment = null }
    }
    private val displayEnvironmentGate = Mutex()
    private var displayEnvironment: ExecutionEnvironment? = null
    private suspend fun environment(purpose: AssessmentPurpose): ExecutionEnvironment =
        if (purpose != AssessmentPurpose.PageLoad) environmentSource.read()
        else displayEnvironmentGate.withLock { displayEnvironment ?: environmentSource.read().also { displayEnvironment = it } }
    private val unknownEnvironment = ExecutionEnvironment(BridgeTransport.Unknown, null)

    suspend fun resolve(definition: SettingDefinition): CompatibilityResolution {
        requireOpen()
        return try {
            resolve(definition.handlerDefinition, if (registry.needsProfile(definition.id)) deviceProfile() else DeviceProfile(emptyMap()), if (registry.needsEnvironment(definition.id)) environmentSource.read() else unknownEnvironment, DeviceTarget())
        } catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { CompatibilityResolution.Unknown(exception.message ?: "Unable to resolve compatibility") }
    }
    private fun resolve(definition: HandlerDefinition<*>, profile: DeviceProfile, environment: ExecutionEnvironment, target: SettingTarget): CompatibilityResolution {
        if (!registry.hasRules(definition.id)) return if (definition.implementations.keys == setOf("default"))
            CompatibilityResolution.Supported("default", "default") else CompatibilityResolution.Unsupported("No compatibility rules for ${definition.id}")
        val selected = registry.resolve(definition.id, profile, target, environment)
        return if (selected is CompatibilityResolution.Supported && selected.operationId !in definition.implementations)
            CompatibilityResolution.Unknown("Operation ${selected.operationId} is not registered by ${definition.id}") else selected
    }

    suspend fun assess(definition: SettingDefinition, target: SettingTarget = DeviceTarget(), purpose: AssessmentPurpose = AssessmentPurpose.OnDemand) =
        assess(definition.handlerDefinition, target, purpose)

    suspend fun assess(definition: HandlerDefinition<*>, target: SettingTarget = DeviceTarget(), purpose: AssessmentPurpose = AssessmentPurpose.OnDemand): CapabilityAssessment {
        requireOpen()
        val policy = registry.policies[definition.id] ?: CapabilityPolicy()
        val evidence = mutableListOf<CompatibilityEvidence>()
        val environment = try { if (registry.needsEnvironment(definition.id) || policy.identities.isNotEmpty() || policy.permissions.isNotEmpty() || policy.probes.isNotEmpty()) environment(purpose) else unknownEnvironment } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { unknownEnvironment }
        val profile = try {
            if (registry.needsProfile(definition.id) || policy.supportProbe != null || policy.probes.isNotEmpty() || registry.needsResolutionProfile || registry.evidence.any { it.capabilityId == definition.id && it.scope.device.needsProfile }) deviceProfile()
            else DeviceProfile(emptyMap())
        } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { DeviceProfile(emptyMap()) }
        val (resolutionPolicy, policyId) = try { registry.resolutionRules.select(definition.id, profile, target, environment, policy.resolution) }
            catch (exception: IllegalStateException) { return CapabilityAssessment(DeviceSupport.Unknown(exception.message ?: "Resolution policy is ambiguous"), OperationAvailability.Unknown(exception.message ?: "Resolution policy is ambiguous")) }
        val selected = resolve(definition, profile, environment, target)
        val runtimeScope = CompatibilityScope(profile.value(DeviceFields.Fingerprint)?.let { DeviceCondition.Equals(DeviceFields.Fingerprint, it, false) } ?: DeviceCondition.Always,
            identities = if (environment.identity == ExecutionIdentity.Unknown) emptySet() else setOf(environment.identity),
            packageName = (target as? AppTarget)?.packageName, userId = when (target) { is AppTarget -> target.userId; is DeviceTarget -> target.userId }, uid = environment.uid)
        fun add(verdict: EvidenceVerdict, proof: ProbeProof, reason: String, id: String?, source: EvidenceSource = EvidenceSource.Declared, status: ProbeStatus? = null, observedAt: Long? = null) {
            evidence += CompatibilityEvidence(definition.id, verdict, source, proof, reason,
                scope = if (source == EvidenceSource.Runtime) runtimeScope else registry.scopeFor(id),
                observedAtMillis = if (source == EvidenceSource.Runtime) observedAt ?: clock() else null, sourceId = id, probeStatus = status)
        }
        when (selected) {
            is CompatibilityResolution.Supported -> add(EvidenceVerdict.Supported, ProbeProof.Behavior, "Declared implementation: ${selected.operationId}", selected.ruleId)
            is CompatibilityResolution.Unsupported -> add(if (selected.ruleId == null) EvidenceVerdict.Unknown else EvidenceVerdict.Blocked, ProbeProof.Behavior, selected.reason, selected.ruleId)
            is CompatibilityResolution.Absent -> add(EvidenceVerdict.Unsupported, ProbeProof.Presence, selected.reason, selected.ruleId)
            is CompatibilityResolution.Unknown -> add(EvidenceVerdict.Unknown, ProbeProof.Behavior, selected.reason, null)
        }
        val now = clock()
        val records = registry.evidence.filter { it.capabilityId == definition.id && (it.expiresAtMillis == null || now < it.expiresAtMillis) }
        if (records.any { it.scope.match(profile, target, environment).state == MatchState.Unknown }) return CapabilityAssessment(DeviceSupport.Unknown("Compatibility evidence depends on unknown scope facts"), OperationAvailability.Unknown("Compatibility evidence depends on unknown scope facts"), records, resolutionPolicy, policyId)
        evidence += records.filter { it.scope.match(profile, target, environment).state == MatchState.Match }
        if (policy.supportProbe != null) {
            val fact = supportGate.withLock {
                supportCache[definition.id] ?: try {
                    SupportFact(policy.supportProbe.probe(profile), clock()).also { if (it.support !is DeviceSupport.Unknown) supportCache[definition.id] = it }
                } catch (exception: CancellationException) { throw exception }
                catch (exception: Exception) { SupportFact(DeviceSupport.Unknown(exception.message ?: "Feature probe failed"), clock()) }
            }
            val support = fact.support
            add(when (support) { DeviceSupport.Supported -> EvidenceVerdict.Supported; is DeviceSupport.Unsupported -> EvidenceVerdict.Unsupported; is DeviceSupport.Unknown -> EvidenceVerdict.Unknown },
                ProbeProof.Presence, when (support) { DeviceSupport.Supported -> "Device feature is present"; is DeviceSupport.Unsupported -> support.reason; is DeviceSupport.Unknown -> support.reason }, "device-feature", EvidenceSource.Runtime, observedAt = fact.observedAt)
        }
        val context = ProbeContext(profile, target, environment, purpose, executor)
        for (probe in policy.probes) {
            val required = probe.proof == ProbeProof.Prerequisite && purpose == AssessmentPurpose.BeforeOperation
            if (probe.risk != ProbeRisk.ReadOnly || (!required && (purpose !in resolutionPolicy.probePolicy.purposes ||
                    (resolutionPolicy.strategy == ResolutionStrategy.DeclaredOnly && probe.proof == resolutionPolicy.proof)))) {
                add(EvidenceVerdict.Unknown, probe.proof, "Probe not tested in this context (${probe.risk})", probe.id, EvidenceSource.Runtime, ProbeStatus.NotTested)
                continue
            }
            val cacheKey = "${definition.id}:${probe.id}:$target:$environment"
            val cacheable = probe.cache == ProbeCache.Session && probe.proof == ProbeProof.Presence
            var cachedResult = false
            val observed = try {
                probeGate.withLock {
                    (if (cacheable) probeCache[cacheKey]?.also { cachedResult = true } else null) ?: (withTimeoutOrNull(3_000) { probe.probe(context) }
                        ?: ProbeObservation(EvidenceVerdict.Unknown, "Probe timed out")).let { CachedProbe(it, clock()) }.also { if (cacheable && it.observation.verdict != EvidenceVerdict.Unknown) probeCache[cacheKey] = it }
                }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { CachedProbe(ProbeObservation(EvidenceVerdict.Unknown, exception.message ?: "Probe failed"), clock()) }
            val observation = observed.observation
            add(observation.verdict, probe.proof, observation.reason, probe.id, EvidenceSource.Runtime,
                if (cachedResult) ProbeStatus.Cached else if (observation.verdict == EvidenceVerdict.Unknown) ProbeStatus.Failed else ProbeStatus.Completed, observed.observedAt)
        }
        val presenceEvidence = evidence.any { it.proof == ProbeProof.Presence }
        val presence = if (!presenceEvidence) EvidenceConclusion(EvidenceVerdict.Supported, "No device feature restriction") else resolveEvidence(ProbeProof.Presence, evidence,
            if (resolutionPolicy.proof == ProbeProof.Presence) resolutionPolicy else ResolutionPolicy(ResolutionStrategy.ProbeFirst))
        val support = when (presence.verdict) {
            EvidenceVerdict.Supported -> DeviceSupport.Supported
            EvidenceVerdict.Unsupported -> DeviceSupport.Unsupported(presence.reason)
            else -> DeviceSupport.Unknown(presence.reason)
        }
        val behavior = resolveEvidence(ProbeProof.Behavior, evidence, if (resolutionPolicy.proof == ProbeProof.Behavior) resolutionPolicy else ResolutionPolicy())
        fun assessed(operation: OperationAvailability) = CapabilityAssessment(support, operation, Collections.unmodifiableList(ArrayList(evidence)), resolutionPolicy, policyId, presence.conflict || behavior.conflict)
        fun fallback(operation: OperationAvailability) = policy.manual?.let { OperationAvailability.Manual(it) } ?: operation
        if (support is DeviceSupport.Unsupported) return assessed(OperationAvailability.Unavailable(support.reason))
        if (support is DeviceSupport.Unknown) return assessed(OperationAvailability.Unknown(support.reason))
        if (behavior.verdict == EvidenceVerdict.Blocked || behavior.verdict == EvidenceVerdict.Unsupported) return assessed(OperationAvailability.Unavailable(behavior.reason))
        if (behavior.verdict == EvidenceVerdict.Unknown) return assessed(fallback(OperationAvailability.Unknown(behavior.reason)))
        val prerequisites = evidence.filter { it.proof == ProbeProof.Prerequisite }
        prerequisites.firstOrNull { it.verdict != EvidenceVerdict.Supported }?.let {
            return assessed(if (it.verdict == EvidenceVerdict.Unknown) OperationAvailability.Unknown(it.reason) else OperationAvailability.Unavailable(it.reason))
        }
        // These checks are independent of resolution strategy. DeclaredOnly does not grant permission.
        if (policy.identities.isNotEmpty() && environment.identity !in policy.identities) return assessed(fallback(OperationAvailability.RequiresPrivilege(policy.identities)))
        val missing = policy.permissions - environment.permissions
        if (missing.isNotEmpty()) return assessed(fallback(OperationAvailability.RequiresPermission(missing)))
        val operationId = (selected as? CompatibilityResolution.Supported)?.operationId ?: if ("default" in definition.implementations) "default" else null
        return assessed(operationId?.let { OperationAvailability.Direct(it) } ?: fallback(OperationAvailability.Unavailable("No registered implementation")))
    }

    internal fun handler(definition: SettingDefinition, reading: Boolean = false): SettingHandler = object : SettingHandler {
        private var selected: SettingHandler? = null
        private var operationId: String? = null
        private var assessment: CapabilityAssessment? = null
        override val verificationPolicy: VerificationPolicy get() = selected?.verificationPolicy ?: VerificationPolicy()

        override suspend fun inspect(context: SettingContext): SettingSnapshot {
            if (selected == null) {
                val current = assess(definition, context.target, if (reading) AssessmentPurpose.PageLoad else AssessmentPurpose.BeforeOperation).also { assessment = it }
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
            val current = assess(definition, context.target, AssessmentPurpose.BeforeOperation)
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
