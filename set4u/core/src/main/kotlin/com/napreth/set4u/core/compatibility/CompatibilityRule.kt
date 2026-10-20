package com.napreth.set4u.core.compatibility

import java.time.LocalDate
import java.util.Collections

enum class MatchState { Match, NoMatch, Unknown }
data class RuleMatch(val state: MatchState, val reason: String? = null)

sealed interface DeviceCondition {
    val needsProfile: Boolean get() = true
    fun match(profile: DeviceProfile): RuleMatch

    data object Always : DeviceCondition {
        override val needsProfile = false
        override fun match(profile: DeviceProfile) = RuleMatch(MatchState.Match)
    }

    data class HasFeature(val name: String) : DeviceCondition {
        init { require(name.matches(Regex("[A-Za-z0-9_.]+"))) }
        override fun match(profile: DeviceProfile): RuleMatch {
            val features = profile.value(DeviceFields.PackageFeatures)
                ?: return RuleMatch(MatchState.Unknown, "设备硬件特性尚未成功读取")
            return matched(features.lineSequence().any { it == name })
        }
    }

    data class Equals(val field: DeviceField, val expected: String, val ignoreCase: Boolean = true) : DeviceCondition {
        override fun match(profile: DeviceProfile): RuleMatch {
            val actual = profile.value(field) ?: return unknown(field)
            return matched(actual.equals(expected, ignoreCase))
        }
    }

    data class IntegerRange(val field: DeviceField, val minimum: Long? = null, val maximum: Long? = null) : DeviceCondition {
        init { require(minimum == null || maximum == null || minimum <= maximum) }
        override fun match(profile: DeviceProfile): RuleMatch {
            val actual = profile.value(field)?.toLongOrNull() ?: return unknown(field)
            return matched((minimum == null || actual >= minimum) && (maximum == null || actual <= maximum))
        }
    }

    /** Only dotted numeric versions are comparable here. Vendor formats supply an explicit parser. */
    class VersionRange(
        val field: DeviceField,
        minimum: String? = null,
        maximum: String? = null,
        private val parser: VersionParser = DottedVersionParser,
    ) : DeviceCondition {
        private val lower = minimum?.let { requireNotNull(parser.parse(it)) { "Invalid lower version: $it" } }
        private val upper = maximum?.let { requireNotNull(parser.parse(it)) { "Invalid upper version: $it" } }
        init { require(lower == null || upper == null || compareVersions(lower, upper) <= 0) }
        override fun match(profile: DeviceProfile): RuleMatch {
            val actual = profile.value(field)?.let(parser::parse) ?: return unknown(field)
            return matched((lower == null || compareVersions(actual, lower) >= 0) && (upper == null || compareVersions(actual, upper) <= 0))
        }
    }

    data class DateRange(val field: DeviceField, val minimum: LocalDate? = null, val maximum: LocalDate? = null) : DeviceCondition {
        init { require(minimum == null || maximum == null || !minimum.isAfter(maximum)) }
        override fun match(profile: DeviceProfile): RuleMatch {
            val date = profile.value(field)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return unknown(field)
            return matched((minimum == null || !date.isBefore(minimum)) && (maximum == null || !date.isAfter(maximum)))
        }
    }

    class All(conditions: List<DeviceCondition>) : DeviceCondition {
        val conditions: List<DeviceCondition> = Collections.unmodifiableList(ArrayList(conditions))
        override val needsProfile get() = conditions.any { it.needsProfile }
        override fun match(profile: DeviceProfile): RuleMatch {
            val results = conditions.map { it.match(profile) }
            return results.firstOrNull { it.state == MatchState.NoMatch }
                ?: results.firstOrNull { it.state == MatchState.Unknown } ?: RuleMatch(MatchState.Match)
        }
    }

    class AnyOf(conditions: List<DeviceCondition>) : DeviceCondition {
        val conditions: List<DeviceCondition> = Collections.unmodifiableList(ArrayList(conditions))
        override val needsProfile get() = conditions.any { it.needsProfile }
        override fun match(profile: DeviceProfile): RuleMatch {
            val results = conditions.map { it.match(profile) }
            return results.firstOrNull { it.state == MatchState.Match }
                ?: results.firstOrNull { it.state == MatchState.Unknown } ?: RuleMatch(MatchState.NoMatch)
        }
    }

    data class Not(val condition: DeviceCondition) : DeviceCondition {
        override val needsProfile get() = condition.needsProfile
        override fun match(profile: DeviceProfile): RuleMatch = condition.match(profile).let {
            if (it.state == MatchState.Unknown) it else matched(it.state == MatchState.NoMatch)
        }
    }
}

fun interface VersionParser { fun parse(raw: String): List<Long>? }
object DottedVersionParser : VersionParser {
    override fun parse(raw: String): List<Long>? {
        if (!raw.matches(Regex("[0-9]+(\\.[0-9]+)*"))) return null
        val parts = raw.split('.').map { it.toLongOrNull() ?: return null }
        return parts
    }
}

private fun compareVersions(a: List<Long>, b: List<Long>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrElse(i) { 0L }).compareTo(b.getOrElse(i) { 0L })
        if (comparison != 0) return comparison
    }
    return 0
}
private fun matched(value: Boolean) = RuleMatch(if (value) MatchState.Match else MatchState.NoMatch)
private fun unknown(field: DeviceField) = RuleMatch(MatchState.Unknown, "Unknown or unrecognised ${field.id}")

sealed interface RuleDecision {
    data class Use(val operationId: String) : RuleDecision { init { require(operationId.isNotBlank()) } }
    data class Unsupported(val reason: String) : RuleDecision { init { require(reason.isNotBlank()) } }
    data class Absent(val reason: String) : RuleDecision { init { require(reason.isNotBlank()) } }
}

class CompatibilityRule(
    val id: String,
    val settingId: String,
    val condition: DeviceCondition,
    val decision: RuleDecision,
    replaces: Set<String> = emptySet(),
    val scope: CompatibilityScope = CompatibilityScope(),
) {
    val capabilityId get() = settingId
    val replaces: Set<String> = Collections.unmodifiableSet(LinkedHashSet(replaces))
    init { require(id.isNotBlank() && settingId.isNotBlank()) }
}

sealed interface CompatibilityResolution {
    data class Supported(val operationId: String, val ruleId: String) : CompatibilityResolution
    data class Unsupported(val reason: String, val ruleId: String? = null) : CompatibilityResolution
    data class Unknown(val reason: String) : CompatibilityResolution
    data class Absent(val reason: String, val ruleId: String) : CompatibilityResolution
}

/** Explicit replacement relationships resolve overrides; registration order never selects an operation. */
class CompatibilityRegistry(rules: List<CompatibilityRule>, policies: Map<String, CapabilityPolicy> = emptyMap(),
    resolutionRules: List<ResolutionRule> = emptyList(), evidence: List<CompatibilityEvidence> = emptyList()) {
    internal val resolutionRules = ResolutionRules(resolutionRules)
    internal val needsResolutionProfile = resolutionRules.any { it.condition.needsProfile || it.scope.device.needsProfile }
    private val resolutionNeedsEnvironment = resolutionRules.any { it.scope.identities.isNotEmpty() || it.scope.uid != null }
    internal val evidence: List<CompatibilityEvidence> = Collections.unmodifiableList(ArrayList(evidence))
    val policies: Map<String, CapabilityPolicy> = Collections.unmodifiableMap(policies.mapValues { (_, policy) -> policy.copy(
        identities = Collections.unmodifiableSet(LinkedHashSet(policy.identities)), permissions = Collections.unmodifiableSet(LinkedHashSet(policy.permissions)),
        probes = Collections.unmodifiableList(ArrayList(policy.probes)),
    ) })
    private val rules = rules.toList()
    private val byId = rules.associateBy { it.id }
    private val replaced = mutableMapOf<String, Set<String>>()
    init {
        require(byId.size == rules.size) { "Duplicate compatibility rule ID" }
        policies.values.forEach { policy -> require(policy.probes.all { it.id.isNotBlank() } && policy.probes.map { it.id }.distinct().size == policy.probes.size) { "Probe IDs must be nonblank and unique within a capability" } }
        fun ancestors(id: String, visiting: Set<String>): Set<String> {
            require(id !in visiting) { "Compatibility replacement cycle at $id" }
            val rule = byId.getValue(id)
            return rule.replaces.flatMap { other ->
                val parent = requireNotNull(byId[other]) { "Unknown replaced rule $other" }
                require(parent.settingId == rule.settingId) { "Replacement crosses setting IDs" }
                setOf(other) + ancestors(other, visiting + id)
            }.toSet()
        }
        rules.forEach { replaced[it.id] = ancestors(it.id, emptySet()) }
    }
    fun hasRules(settingId: String) = rules.any { it.settingId == settingId }
    internal fun needsEnvironment(id: String) = resolutionNeedsEnvironment || rules.any { it.settingId == id && (it.scope.identities.isNotEmpty() || it.scope.uid != null) } || evidence.any { it.capabilityId == id && (it.scope.identities.isNotEmpty() || it.scope.uid != null) }
    internal fun scopeFor(ruleId: String?) = byId[ruleId]?.scope ?: CompatibilityScope()
    fun needsProfile(settingId: String) = rules.any { it.settingId == settingId && (it.condition.needsProfile || it.scope.device.needsProfile) }

    fun resolve(settingId: String, profile: DeviceProfile,
        target: com.napreth.set4u.core.session.SettingTarget = com.napreth.set4u.core.session.DeviceTarget(),
        environment: com.napreth.set4u.core.bridge.ExecutionEnvironment = com.napreth.set4u.core.bridge.ExecutionEnvironment(com.napreth.set4u.core.bridge.BridgeTransport.Unknown, null),
    ): CompatibilityResolution {
        val results = rules.filter { it.settingId == settingId }.map { rule ->
            rule to rule.scope.match(profile, target, environment, rule.condition)
        }
        val matches = results.filter { it.second.state == MatchState.Match }.map { it.first }
        val suppressed = matches.flatMap { replaced.getValue(it.id) }.toSet()
        val uncertain = results.filter { it.second.state == MatchState.Unknown && it.first.id !in suppressed }
        if (uncertain.isNotEmpty()) return CompatibilityResolution.Unknown(uncertain.joinToString { "${it.first.id}: ${it.second.reason}" })
        val selected = matches.filter { it.id !in suppressed }
        if (selected.size > 1) return CompatibilityResolution.Unknown("Conflicting compatibility rules: ${selected.joinToString { it.id }}")
        val rule = selected.singleOrNull() ?: return CompatibilityResolution.Unsupported("No supported operation for $settingId")
        return when (val decision = rule.decision) {
            is RuleDecision.Use -> CompatibilityResolution.Supported(decision.operationId, rule.id)
            is RuleDecision.Unsupported -> CompatibilityResolution.Unsupported(decision.reason, rule.id)
            is RuleDecision.Absent -> CompatibilityResolution.Absent(decision.reason, rule.id)
        }
    }
    companion object { val Empty = CompatibilityRegistry(emptyList()) }
}
