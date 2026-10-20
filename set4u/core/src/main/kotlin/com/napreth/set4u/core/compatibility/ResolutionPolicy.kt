package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.session.*
import java.util.Collections

enum class AssessmentPurpose { PageLoad, OnDemand, BeforeOperation, Scan, Diagnostic }
enum class EvidenceSource { Declared, Scanned, Runtime }
enum class EvidenceVerdict { Supported, Unsupported, Blocked, Unknown }
enum class ProbeProof { Presence, Prerequisite, Behavior }
enum class ProbeRisk { ReadOnly, Reversible, Disruptive, Destructive }
enum class ProbeCache { None, Session }
enum class ProbeStatus { Completed, Cached, Failed, NotTested }
enum class ResolutionStrategy { DeclaredOnly, ProbeOnly, DeclaredFirst, ProbeFirst, RequireBoth }
enum class ConflictPolicy { PreferDeclared, PreferProbe, ReportConflict }

/** An operation restriction may apply to one package/user/identity, not the entire device. */
class CompatibilityScope(
    val device: DeviceCondition = DeviceCondition.Always,
    identities: Set<ExecutionIdentity> = emptySet(),
    val packageName: String? = null,
    val userId: Int? = null,
    val uid: Int? = null,
) {
    val identities: Set<ExecutionIdentity> = Collections.unmodifiableSet(LinkedHashSet(identities))
    init { require(userId == null || userId >= 0); require(packageName == null || packageName.isNotBlank()) }
    internal fun match(profile: DeviceProfile, target: SettingTarget, environment: ExecutionEnvironment, condition: DeviceCondition = DeviceCondition.Always): RuleMatch {
        if (packageName != null && (target as? AppTarget)?.packageName != packageName) return RuleMatch(MatchState.NoMatch)
        val user = when (target) { is AppTarget -> target.userId; is DeviceTarget -> target.userId }
        if (userId != null && userId != user) return RuleMatch(MatchState.NoMatch)
        if (uid != null && environment.uid != null && environment.uid != uid) return RuleMatch(MatchState.NoMatch)
        if (identities.isNotEmpty() && environment.identity != ExecutionIdentity.Unknown && environment.identity !in identities) return RuleMatch(MatchState.NoMatch)
        val deviceMatch = DeviceCondition.All(listOf(device, condition)).match(profile)
        if (deviceMatch.state != MatchState.Match) return deviceMatch
        if (uid != null && environment.uid == null) return RuleMatch(MatchState.Unknown, "Execution UID is unknown")
        if (identities.isNotEmpty() && environment.identity == ExecutionIdentity.Unknown) return RuleMatch(MatchState.Unknown, "Execution identity is unknown")
        return RuleMatch(MatchState.Match)
    }
}

data class CompatibilityEvidence(
    val capabilityId: String,
    val verdict: EvidenceVerdict,
    val source: EvidenceSource,
    val proof: ProbeProof,
    val reason: String,
    val scope: CompatibilityScope = CompatibilityScope(),
    val observedAtMillis: Long? = null,
    val expiresAtMillis: Long? = null,
    val sourceId: String? = null,
    val probeStatus: ProbeStatus? = null,
) {
    init { require(capabilityId.isNotBlank() && reason.isNotBlank()); require(expiresAtMillis == null || observedAtMillis == null || expiresAtMillis > observedAtMillis) }
}

class ProbePolicy(purposes: Set<AssessmentPurpose> = AssessmentPurpose.entries.toSet()) {
    val purposes: Set<AssessmentPurpose> = Collections.unmodifiableSet(LinkedHashSet(purposes))
}
data class ResolutionPolicy(
    val strategy: ResolutionStrategy = ResolutionStrategy.DeclaredFirst,
    val probePolicy: ProbePolicy = ProbePolicy(),
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ReportConflict,
    val proof: ProbeProof = ProbeProof.Behavior,
)

class ResolutionRule(
    val id: String,
    val capabilityId: String?,
    val condition: DeviceCondition = DeviceCondition.Always,
    val policy: ResolutionPolicy,
    val scope: CompatibilityScope = CompatibilityScope(),
    replaces: Set<String> = emptySet(),
) {
    val replaces: Set<String> = Collections.unmodifiableSet(LinkedHashSet(replaces))
    init { require(id.isNotBlank() && (capabilityId == null || capabilityId.isNotBlank())) }
}

internal class ResolutionRules(rules: List<ResolutionRule>) {
    private val rules = rules.toList()
    private val byId = rules.associateBy { it.id }
    private val ancestors = mutableMapOf<String, Set<String>>()
    init {
        require(byId.size == rules.size) { "Duplicate resolution rule ID" }
        fun visit(id: String, path: Set<String>): Set<String> {
            require(id !in path) { "Resolution policy replacement cycle" }
            val rule = byId.getValue(id)
            return rule.replaces.flatMap { parentId ->
                val parent = requireNotNull(byId[parentId]) { "Unknown replaced resolution policy: $parentId" }
                require(parent.capabilityId == null || parent.capabilityId == rule.capabilityId) { "Resolution policy replacement crosses capabilities" }
                setOf(parentId) + visit(parentId, path + id)
            }.toSet()
        }
        rules.forEach { ancestors[it.id] = visit(it.id, emptySet()) }
    }
    fun select(id: String, profile: DeviceProfile, target: SettingTarget, environment: ExecutionEnvironment, default: ResolutionPolicy): Pair<ResolutionPolicy, String?> {
        val candidates = rules.filter { it.capabilityId == null || it.capabilityId == id }.map { rule ->
            rule to rule.scope.match(profile, target, environment, rule.condition)
        }
        val matches = candidates.filter { it.second.state == MatchState.Match }.map { it.first }
        val suppressed = matches.flatMap { ancestors.getValue(it.id) }.toSet()
        check(candidates.none { it.second.state == MatchState.Unknown && it.first.id !in suppressed }) { "Resolution policy depends on unknown facts" }
        val selected = matches.filter { it.id !in suppressed }
        check(selected.size <= 1) { "Conflicting resolution policies: ${selected.joinToString { it.id }}" }
        return selected.singleOrNull()?.let { it.policy to it.id } ?: (default to null)
    }
}

class ProbeContext internal constructor(
    val profile: DeviceProfile,
    val target: SettingTarget,
    val environment: ExecutionEnvironment,
    val purpose: AssessmentPurpose,
    private val executor: (suspend (ShellRequest) -> ShellResult)?,
) {
    suspend fun execute(request: ShellRequest): ShellResult = checkNotNull(executor) { "No probe transport" }(request)
}
data class ProbeObservation(val verdict: EvidenceVerdict, val reason: String)
interface CapabilityProbe {
    val id: String
    val proof: ProbeProof
    val risk: ProbeRisk get() = ProbeRisk.ReadOnly
    val cache: ProbeCache get() = ProbeCache.None
    suspend fun probe(context: ProbeContext): ProbeObservation
}

data class EvidenceConclusion(val verdict: EvidenceVerdict, val reason: String, val conflict: Boolean = false)

/** Prerequisite evidence is never accepted as proof that a behavior is supported. */
internal fun resolveEvidence(proof: ProbeProof, evidence: List<CompatibilityEvidence>, policy: ResolutionPolicy): EvidenceConclusion {
    fun combine(items: List<CompatibilityEvidence>): EvidenceConclusion? {
        val known = items.filter { it.verdict != EvidenceVerdict.Unknown }
        if (known.isEmpty()) return null
        if (known.map { it.verdict }.distinct().size > 1) return EvidenceConclusion(EvidenceVerdict.Unknown, "Conflicting evidence from the same source", true)
        return EvidenceConclusion(known.first().verdict, known.joinToString { it.reason })
    }
    val relevant = evidence.filter { it.proof == proof }
    val declared = combine(relevant.filter { it.source != EvidenceSource.Runtime })
    val probe = combine(relevant.filter { it.source == EvidenceSource.Runtime })
    val unknown = EvidenceConclusion(EvidenceVerdict.Unknown, relevant.firstOrNull { it.verdict == EvidenceVerdict.Unknown }?.reason ?: "Insufficient compatibility evidence")
    val disagreement = declared != null && probe != null && declared.verdict != probe.verdict
    if (policy.strategy == ResolutionStrategy.DeclaredOnly) return (declared ?: unknown).copy(conflict = disagreement || declared?.conflict == true)
    if (policy.strategy == ResolutionStrategy.ProbeOnly) return (probe ?: unknown).copy(conflict = disagreement || probe?.conflict == true)
    if (declared?.conflict == true || probe?.conflict == true) return EvidenceConclusion(EvidenceVerdict.Unknown, "Conflicting compatibility evidence", true)
    if (declared != null && probe != null && declared.verdict != probe.verdict) {
        val chosen = when (policy.conflictPolicy) {
            ConflictPolicy.PreferDeclared -> declared
            ConflictPolicy.PreferProbe -> probe
            ConflictPolicy.ReportConflict -> EvidenceConclusion(EvidenceVerdict.Unknown, "Declared rules and runtime probe disagree", true)
        }
        return if (policy.strategy == ResolutionStrategy.RequireBoth && chosen.verdict == EvidenceVerdict.Supported)
            EvidenceConclusion(EvidenceVerdict.Unknown, "Both sources must confirm support", true) else chosen.copy(conflict = true)
    }
    return when (policy.strategy) {
        ResolutionStrategy.DeclaredFirst -> declared ?: probe ?: unknown
        ResolutionStrategy.ProbeFirst -> probe ?: declared ?: unknown
        ResolutionStrategy.RequireBoth -> when {
            declared?.verdict == EvidenceVerdict.Supported && probe?.verdict == EvidenceVerdict.Supported -> declared
            declared?.verdict in setOf(EvidenceVerdict.Blocked, EvidenceVerdict.Unsupported) -> checkNotNull(declared)
            probe?.verdict in setOf(EvidenceVerdict.Blocked, EvidenceVerdict.Unsupported) -> checkNotNull(probe)
            else -> unknown
        }
    }
}
