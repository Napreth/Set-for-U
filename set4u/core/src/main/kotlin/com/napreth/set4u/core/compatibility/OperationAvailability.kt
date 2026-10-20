package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.settings.SystemSettingsScreen

sealed interface DeviceSupport {
    data object Supported : DeviceSupport
    data class Unsupported(val reason: String) : DeviceSupport
    data class Unknown(val reason: String) : DeviceSupport
}
sealed interface OperationAvailability {
    data class Direct(val operationId: String) : OperationAvailability
    data class RequiresPermission(val permissions: Set<String>) : OperationAvailability
    data class RequiresPrivilege(val identities: Set<ExecutionIdentity>) : OperationAvailability
    data class Manual(val destination: SystemSettingsScreen) : OperationAvailability
    data class Unavailable(val reason: String) : OperationAvailability
    data class Unknown(val reason: String) : OperationAvailability
}
data class CapabilityAssessment(
    val support: DeviceSupport, val operation: OperationAvailability,
    val evidence: List<CompatibilityEvidence> = emptyList(),
    val resolutionPolicy: ResolutionPolicy = ResolutionPolicy(),
    val resolutionRuleId: String? = null,
    val conflict: Boolean = false,
) {
    fun reason(): String = when (val current = operation) {
        is OperationAvailability.Direct -> "Operation is available"
        is OperationAvailability.Manual -> "Open system settings"
        is OperationAvailability.RequiresPermission -> "Required permissions: ${current.permissions}"
        is OperationAvailability.RequiresPrivilege -> "Required execution identity: ${current.identities}"
        is OperationAvailability.Unavailable -> current.reason
        is OperationAvailability.Unknown -> current.reason
    }
}

/** Hardware probes are session cached; authorization is evaluated again for every operation. */
fun interface SupportProbe { suspend fun probe(profile: DeviceProfile): DeviceSupport }
class FeatureSupportProbe(private val name: String) : SupportProbe {
    override suspend fun probe(profile: DeviceProfile): DeviceSupport = when (DeviceCondition.HasFeature(name).match(profile).state) {
        MatchState.Match -> DeviceSupport.Supported
        MatchState.NoMatch -> DeviceSupport.Unsupported("Device feature is absent: $name")
        MatchState.Unknown -> DeviceSupport.Unknown("Device feature has not been established: $name")
    }
}
data class CapabilityPolicy(
    val supportProbe: SupportProbe? = null,
    val identities: Set<ExecutionIdentity> = emptySet(),
    val permissions: Set<String> = emptySet(),
    val manual: SystemSettingsScreen? = null,
    val probes: List<CapabilityProbe> = emptyList(),
    val resolution: ResolutionPolicy = ResolutionPolicy(),
)
