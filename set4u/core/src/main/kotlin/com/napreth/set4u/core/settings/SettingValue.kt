package com.napreth.set4u.core.settings

import java.util.Collections

sealed interface SettingValue {
    data class BooleanValue(val value: Boolean) : SettingValue
    data class IntegerValue(val value: Long) : SettingValue
    data class TextValue(val value: String) : SettingValue
    data class ChoiceValue(val id: String) : SettingValue
    class MultiChoiceValue(ids: Set<String>) : SettingValue {
        val ids: Set<String> = Collections.unmodifiableSet(LinkedHashSet(ids))
        override fun equals(other: Any?) = other is MultiChoiceValue && ids == other.ids
        override fun hashCode() = ids.hashCode()
        override fun toString() = "MultiChoiceValue(ids=$ids)"
    }
    class ActionValue(parameters: Map<String, String>) : SettingValue {
        val parameters: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(parameters))
        init { require(parameters.size <= 64 && parameters.all { it.key.isNotBlank() && it.key.length <= 128 && it.value.length <= 16384 } && parameters.values.sumOf { it.length } <= 16384) }
        override fun equals(other: Any?) = other is ActionValue && parameters == other.parameters
        override fun hashCode() = parameters.hashCode()
        override fun toString() = "ActionValue(keys=${parameters.keys})"
    }
    data object Action : SettingValue
}

enum class ValueType { Boolean, Integer, Text, Choice, MultiChoice, Action }

val SettingValue.isAction: Boolean get() = this == SettingValue.Action || this is SettingValue.ActionValue
