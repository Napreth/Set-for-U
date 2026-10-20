package com.napreth.set4u.core.settings

import com.napreth.set4u.core.execution.ExecutionEngine
import com.napreth.set4u.core.execution.SettingOperation
import com.napreth.set4u.core.execution.SettingResult
import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.state.SettingObservation
import com.napreth.set4u.core.compatibility.CompatibilityService
import kotlinx.coroutines.Deferred

class SettingsService internal constructor(
    private val catalog: SettingsCatalog,
    private val engine: ExecutionEngine,
    private val compatibility: CompatibilityService,
) {
    suspend fun describe(target: SettingTarget, pageId: String): SettingsPageSnapshot =
        catalog.describe(pageId, target)

    internal suspend fun loadPage(target: SettingTarget, pageId: String): LoadedSettingsPage =
        catalog.load(pageId, target)

    suspend fun read(target: SettingTarget, definition: SettingDefinition): SettingObservation {
        val registered = catalog.registry.resolve(definition)
        return engine.read(target, registered.id, compatibility.handler(registered, reading = true))
    }

    fun set(
        target: SettingTarget,
        definition: SettingDefinition,
        desiredValue: SettingValue,
    ): Deferred<SettingResult> = setBound(target, definition, desiredValue, null) {}

    internal fun setBound(target: SettingTarget, definition: SettingDefinition, desiredValue: SettingValue,
                          admissionKey: String?, guard: () -> Unit): Deferred<SettingResult> {
        val registered = catalog.registry.resolve(definition)
        val error = registered.validationError(desiredValue)
        require(error == null) { error.orEmpty() }
        val retained = if (desiredValue is SettingValue.ActionValue) SettingValue.ActionValue(desiredValue.parameters.mapValues { (key, value) ->
            if (registered.actionParameters[key]?.sensitive == true) "[redacted]" else value
        }) else desiredValue
        return engine.submit(SettingOperation(
            registered.id, target, desiredValue, compatibility.handler(registered), admissionKey, guard, retained,
        ))
    }
}
