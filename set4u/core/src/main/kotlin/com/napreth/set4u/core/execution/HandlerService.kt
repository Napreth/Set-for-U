package com.napreth.set4u.core.execution

import com.napreth.set4u.core.compatibility.CompatibilityService
import com.napreth.set4u.core.compatibility.AssessmentPurpose
import com.napreth.set4u.core.session.SettingTarget

class HandlerService internal constructor(
    private val registry: HandlerRegistry,
    private val engine: ExecutionEngine,
    private val compatibility: CompatibilityService,
) {
    val queries = HandlerQueries(this)
    suspend fun <H : Handler, R> invoke(
        definition: HandlerDefinition<H>,
        target: SettingTarget,
        block: suspend (H, HandlerContext) -> R,
    ): HandlerResult<R> {
        val registered = registry.resolve(definition)
        require(!SettingHandler::class.java.isAssignableFrom(registered.type.java)) {
            "Setting handlers must use SettingsService so that validation and verification cannot be bypassed"
        }
        return engine.invokeHandler(registered, target, { compatibility.assess(registered, target, AssessmentPurpose.BeforeOperation) }, block)
    }
}

class HandlerQueries internal constructor(private val service: HandlerService) {
    suspend fun <H : Handler, R> invoke(definition: HandlerDefinition<H>, target: SettingTarget, block: suspend (H, HandlerContext) -> R): HandlerResult<R> {
        require(definition.effect == HandlerEffect.ReadOnly) { "Page queries must be read-only" }
        return service.invoke(definition, target, block)
    }
}
