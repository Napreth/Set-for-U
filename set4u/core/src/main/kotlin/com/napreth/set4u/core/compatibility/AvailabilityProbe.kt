package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.execution.HandlerService
import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AvailabilityStatus { Available, Unsupported, Unavailable, Unknown }
data class AvailabilityAssessment(val status: AvailabilityStatus = AvailabilityStatus.Available, val reason: String? = null)
fun interface AvailabilityProbe { suspend fun assess(context: AvailabilityContext): AvailabilityAssessment }

class AvailabilityContext internal constructor(
    val target: SettingTarget,
    val compatibility: CompatibilityService,
    val handlers: com.napreth.set4u.core.execution.HandlerQueries,
) {
    suspend fun assess(setting: SettingDefinition): CapabilityAssessment = compatibility.assess(setting, target, AssessmentPurpose.PageLoad)
}

/** Results are shared by navigation rows within this load; a new page load gets a new cycle. */
class AvailabilityCycle internal constructor(
    private val scope: CoroutineScope,
    private val catalog: SettingsCatalog,
    private val compatibility: CompatibilityService,
    private val handlers: HandlerService,
) {
    private val gate = Mutex()
    private val results = mutableMapOf<Pair<String, SettingTarget>, Deferred<AvailabilityAssessment>>()
    suspend fun assess(pageId: String, target: SettingTarget): AvailabilityAssessment = assessLoaded(pageId, target, null)
    internal suspend fun assessLoaded(pageId: String, target: SettingTarget, probe: AvailabilityProbe?): AvailabilityAssessment {
        val job = gate.withLock {
            results.getOrPut(pageId to target) { scope.async(start = CoroutineStart.LAZY) {
                try {
                    val context = AvailabilityContext(target, compatibility, handlers.queries)
                    if (probe != null) probe.assess(context) else catalog.availability(pageId, target, context)
                }
                catch (exception: CancellationException) { throw exception }
                catch (exception: Exception) { AvailabilityAssessment(AvailabilityStatus.Unknown, exception.message ?: "Page detection failed") }
            } }.also { it.start() }
        }
        return job.await()
    }
}

class PageAvailabilityService internal constructor(private val catalog: SettingsCatalog, private val compatibility: CompatibilityService, private val handlers: HandlerService) {
    fun cycle(scope: CoroutineScope) = AvailabilityCycle(scope, catalog, compatibility, handlers)
}
