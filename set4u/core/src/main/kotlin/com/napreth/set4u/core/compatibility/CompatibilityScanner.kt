package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.execution.HandlerRegistry
import com.napreth.set4u.core.session.SettingTarget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class CompatibilityScanEntry(val capabilityId: String, val assessment: CapabilityAssessment)
data class CompatibilityScanReport(val target: SettingTarget, val entries: List<CompatibilityScanEntry>, val sessionId: String)

/** Passive scan only. Non-read-only probes are reported as NotTested, never silently invoked. */
class CompatibilityScanner internal constructor(private val registry: HandlerRegistry, private val compatibility: CompatibilityService, private val sessionId: String) {
    suspend fun scan(target: SettingTarget): CompatibilityScanReport = CompatibilityScanReport(target, registry.all().map {
        currentCoroutineContext().ensureActive()
        CompatibilityScanEntry(it.id, compatibility.assess(it, target, AssessmentPurpose.Scan))
    }, sessionId)
}
