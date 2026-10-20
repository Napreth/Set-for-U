@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.*
import com.napreth.set4u.core.bridge.ExecutionIdentity
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.session.*
import com.napreth.set4u.core.settings.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

class ResolutionPolicyTest {
    private fun probe(id: String, proof: ProbeProof, verdict: EvidenceVerdict, risk: ProbeRisk = ProbeRisk.ReadOnly, onRun: () -> Unit = {}) = object : CapabilityProbe {
        override val id = id; override val proof = proof; override val risk = risk
        override suspend fun probe(context: ProbeContext) = ProbeObservation(verdict, id).also { onRun() }
    }
    private fun registry(flag: SettingDefinition, policy: CapabilityPolicy, decision: RuleDecision = RuleDecision.Use("default"), meta: List<ResolutionRule> = emptyList(), evidence: List<CompatibilityEvidence> = emptyList()) =
        CompatibilityRegistry(listOf(CompatibilityRule("declared", flag.id, DeviceCondition.Always, decision)), mapOf(flag.id to policy), meta, evidence)

    @Test fun prerequisitesCannotProveThatAProtectedPackageCanBeDisabled() = runTest {
        val flag = flagDefinition()
        val policy = CapabilityPolicy(probes = listOf(probe("package.exists", ProbeProof.Prerequisite, EvidenceVerdict.Supported)))
        withSession(catalog = catalogOf(flag), compatibility = registry(flag, policy, RuleDecision.Unsupported("Protected package")), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
            val assessment = session.compatibility.assess(flag, Target)
            assertEquals(DeviceSupport.Supported, assessment.support)
            assertEquals(OperationAvailability.Unavailable("Protected package"), assessment.operation)
            assertEquals(2, assessment.evidence.size)
            assertTrue(assessment.evidence.any { it.proof == ProbeProof.Prerequisite })
        }
    }

    @Test fun genuineConflictsAreRetainedAndAnExplicitPolicyCanChooseTheProbe() = runTest {
        val flag = flagDefinition()
        for (conflict in ConflictPolicy.entries) {
            val policy = CapabilityPolicy(probes = listOf(probe("verified", ProbeProof.Behavior, EvidenceVerdict.Supported)),
                resolution = ResolutionPolicy(ResolutionStrategy.ProbeFirst, conflictPolicy = conflict))
            withSession(catalog = catalogOf(flag), compatibility = registry(flag, policy, RuleDecision.Unsupported("Known restriction")), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
                val result = session.compatibility.assess(flag, Target)
                assertTrue(result.conflict); assertEquals(2, result.evidence.size)
                when (conflict) {
                    ConflictPolicy.PreferDeclared -> assertTrue(result.operation is OperationAvailability.Unavailable)
                    ConflictPolicy.PreferProbe -> assertTrue(result.operation is OperationAvailability.Direct)
                    ConflictPolicy.ReportConflict -> assertTrue(result.operation is OperationAvailability.Unknown)
                }
            }
        }
    }

    @Test fun declaredOnlyStillRechecksIdentityAndRuntimePrerequisites() = runTest {
        val flag = flagDefinition()
        var checks = 0
        val policy = CapabilityPolicy(identities = setOf(ExecutionIdentity.Root),
            probes = listOf(probe("available", ProbeProof.Prerequisite, EvidenceVerdict.Supported, onRun = { checks++ })),
            resolution = ResolutionPolicy(ResolutionStrategy.DeclaredOnly, ProbePolicy(emptySet())))
        val bridge = FakeBridge().apply { testUid = 0 }
        withSession(bridge, catalogOf(flag), compatibility = registry(flag, policy), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
            assertTrue(session.compatibility.assess(flag, Target, AssessmentPurpose.BeforeOperation).operation is OperationAvailability.Direct)
            bridge.testUid = 2000
            assertTrue(session.compatibility.assess(flag, Target, AssessmentPurpose.BeforeOperation).operation is OperationAvailability.RequiresPrivilege)
            assertEquals(2, checks)
            assertTrue(bridge.writes.isEmpty())
        }
    }

    @Test fun scopedRestrictionsCannotLeakToAnotherUserPackageOrIdentity() = runTest {
        val flag = flagDefinition()
        val rules = listOf(CompatibilityRule("base", flag.id, DeviceCondition.Always, RuleDecision.Use("default")),
            CompatibilityRule("restricted", flag.id, DeviceCondition.Always, RuleDecision.Unsupported("Protected"), setOf("base"),
                CompatibilityScope(identities = setOf(ExecutionIdentity.Shell), packageName = Target.packageName, userId = 0)))
        val bridge = FakeBridge()
        withSession(bridge, catalogOf(flag), compatibility = CompatibilityRegistry(rules)) { session ->
            assertTrue(session.compatibility.assess(flag, Target).operation is OperationAvailability.Unavailable)
            assertTrue(session.compatibility.assess(flag, AppTarget(10, Target.packageName)).operation is OperationAvailability.Direct)
            assertTrue(session.compatibility.assess(flag, AppTarget(0, "com.example.other")).operation is OperationAvailability.Direct)
            bridge.testUid = 0
            assertTrue(session.compatibility.assess(flag, Target).operation is OperationAvailability.Direct)
        }
    }

    @Test fun passiveScanSkipsRiskyTestsAndDoesNotExecuteSettings() = runTest {
        val flag = flagDefinition(); var runs = 0
        val policy = CapabilityPolicy(probes = listOf(probe("uninstall-test", ProbeProof.Behavior, EvidenceVerdict.Supported, ProbeRisk.Destructive) { runs++ }))
        val bridge = FakeBridge()
        withSession(bridge, catalogOf(flag), compatibility = registry(flag, policy), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
            val report = session.scanner.scan(Target)
            assertEquals(0, runs); assertTrue(bridge.writes.isEmpty())
            assertEquals(ProbeStatus.NotTested, report.entries.single().assessment.evidence.last().probeStatus)
        }
    }

    @Test fun unknownDeviceFactsFromForeignRulesAndPoliciesDoNotBlockTheCurrentTarget() = runTest {
        val flag = flagDefinition()
        val unknownBuild = DeviceCondition.Equals(DeviceFields.Fingerprint, "another-build")
        val foreignUser = CompatibilityScope(userId = 10)
        val rules = listOf(
            CompatibilityRule("base", flag.id, DeviceCondition.Always, RuleDecision.Use("default")),
            CompatibilityRule("foreign", flag.id, unknownBuild, RuleDecision.Unsupported("Other user"), setOf("base"), foreignUser),
        )
        val meta = listOf(ResolutionRule("foreign.policy", flag.id, unknownBuild, ResolutionPolicy(ResolutionStrategy.ProbeOnly), scope = foreignUser))
        withSession(catalog = catalogOf(flag), compatibility = CompatibilityRegistry(rules, resolutionRules = meta), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
            assertTrue(session.compatibility.assess(flag, Target).operation is OperationAvailability.Direct)
            assertTrue(session.compatibility.assess(flag, AppTarget(10, Target.packageName)).operation is OperationAvailability.Unknown)
        }
    }

    @Test fun requireBothNeverPromotesOneSidedSupportAndResolutionTiesAreReported() = runTest {
        val flag = flagDefinition()
        val policy = CapabilityPolicy(resolution = ResolutionPolicy(ResolutionStrategy.RequireBoth))
        withSession(catalog = catalogOf(flag), compatibility = registry(flag, policy)) { session -> assertTrue(session.compatibility.assess(flag).operation is OperationAvailability.Unknown) }
        val meta = listOf("one", "two").map { ResolutionRule(it, flag.id, policy = ResolutionPolicy()) }
        withSession(catalog = catalogOf(flag), compatibility = registry(flag, CapabilityPolicy(), meta = meta)) { session -> assertTrue(session.compatibility.assess(flag).operation is OperationAvailability.Unknown) }
    }

    @Test fun cachedFeatureEvidenceRetainsItsObservationTimeAndRefreshInvalidatesIt() = runTest {
        val flag = flagDefinition(); var calls = 0
        val feature = object : CapabilityProbe {
            override val id = "feature"; override val proof = ProbeProof.Presence; override val cache = ProbeCache.Session
            override suspend fun probe(context: ProbeContext) = ProbeObservation(EvidenceVerdict.Supported, "Present").also { calls++ }
        }
        withSession(catalog = catalogOf(flag), compatibility = registry(flag, CapabilityPolicy(probes = listOf(feature))), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
            val first = session.compatibility.assess(flag).evidence.last()
            advanceTimeBy(100)
            val cached = session.compatibility.assess(flag).evidence.last()
            assertEquals(1, calls); assertEquals(first.observedAtMillis, cached.observedAtMillis)
            assertEquals(ProbeStatus.Cached, cached.probeStatus)
            session.compatibility.deviceProfile(refresh = true)
            session.compatibility.assess(flag); assertEquals(2, calls)
        }
    }

    @Test fun expiredOrForeignEvidenceDoesNotBlockAndUnresolvedScopeStaysUnknown() = runTest {
        val flag = flagDefinition()
        val expired = CompatibilityEvidence(flag.id, EvidenceVerdict.Blocked, EvidenceSource.Scanned, ProbeProof.Behavior, "Old restriction", observedAtMillis = -10, expiresAtMillis = -1)
        val otherUser = expired.copy(reason = "Other user", expiresAtMillis = null, scope = CompatibilityScope(userId = 10))
        withSession(catalog = catalogOf(flag), compatibility = registry(flag, CapabilityPolicy(), evidence = listOf(expired, otherUser))) { session ->
            assertTrue(session.compatibility.assess(flag, Target).operation is OperationAvailability.Direct)
        }
        val foreignBuild = otherUser.copy(scope = CompatibilityScope(
            device = DeviceCondition.Equals(DeviceFields.Fingerprint, "unavailable-build"), userId = 10))
        withSession(catalog = catalogOf(flag), compatibility = registry(flag, CapabilityPolicy(), evidence = listOf(foreignBuild)), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
            assertTrue(session.compatibility.assess(flag, Target).operation is OperationAvailability.Direct)
        }
        val unresolved = otherUser.copy(scope = CompatibilityScope(device = DeviceCondition.Equals(DeviceFields.Fingerprint, "exact-build")))
        withSession(catalog = catalogOf(flag), compatibility = registry(flag, CapabilityPolicy(), evidence = listOf(unresolved)), deviceProfileSource = DeviceProfileSource { DeviceProfile(emptyMap()) }) { session ->
            assertTrue(session.compatibility.assess(flag, Target).operation is OperationAvailability.Unknown)
        }
    }
}
