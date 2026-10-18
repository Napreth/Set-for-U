package com.napreth.set4u.core.settings

import java.util.Collections

/** The catalog owns definitions. Pages and callers may reference them, but cannot replace them. */
class SettingsRegistry(definitions: List<SettingDefinition>) {
    private val definitions = linkedMapOf<String, SettingDefinition>()

    init {
        definitions.forEach { definition ->
            val frozen = definition.copy(
                choices = Collections.unmodifiableList(ArrayList(definition.choices)),
                operationFunctions = Collections.unmodifiableMap(LinkedHashMap(definition.operationFunctions)),
                actionParameters = Collections.unmodifiableMap(LinkedHashMap(definition.actionParameters)),
            )
            val existing = this.definitions.putIfAbsent(frozen.id, frozen)
            require(existing == null || existing.sameDefinition(frozen)) { "Conflicting global setting definition: ${frozen.id}" }
        }
    }

    operator fun get(id: String): SettingDefinition = definitions[id]
        ?: throw IllegalArgumentException("Unregistered setting: $id")

    internal fun resolve(reference: SettingDefinition): SettingDefinition = get(reference.id).also {
        require(it.sameDefinition(reference)) { "Conflicting global setting definition: ${reference.id}" }
    }
}

internal fun SettingDefinition.sameDefinition(other: SettingDefinition): Boolean =
    this == other && binding === other.binding && operationFunctions.keys == other.operationFunctions.keys &&
        operationFunctions.all { (id, factory) -> factory === other.operationFunctions[id] }
