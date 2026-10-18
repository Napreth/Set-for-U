package com.napreth.set4u.core.settings

import com.napreth.set4u.core.execution.SettingHandler

/** Business value, validation and execution binding; presentation belongs to components. */
data class SettingDefinition(
    val id: String,
    val valueType: ValueType,
    val binding: SettingHandler? = null,
    val choices: List<String> = emptyList(),
    val minimum: Long? = null,
    val maximum: Long? = null,
    val maxLength: Int? = null,
    val operationFunctions: Map<String, () -> SettingHandler> = emptyMap(),
    val actionParameters: Map<String, ActionParameter> = emptyMap(),
) {
    init {
        require(id.isNotBlank())
        require(binding != null || operationFunctions.isNotEmpty()) { "Setting needs an operation" }
        require(operationFunctions.keys.all { it.isNotBlank() && it != "default" }) { "Operation IDs must be nonblank; default is reserved" }
        require(minimum == null || maximum == null || minimum <= maximum)
        require(maxLength == null || maxLength >= 0)
        require(valueType == ValueType.Integer || (minimum == null && maximum == null))
        require(valueType == ValueType.Text || maxLength == null)
        require(choices.distinct().size == choices.size)
        require(choices.all { it.isNotBlank() })
        val hasChoices = valueType == ValueType.Choice || valueType == ValueType.MultiChoice
        require(if (hasChoices) choices.isNotEmpty() else choices.isEmpty())
        require(valueType == ValueType.Action || actionParameters.isEmpty())
        require(actionParameters.keys.all { it.isNotBlank() })
    }

    internal fun hasOperation(id: String): Boolean = if (id == "default") binding != null else id in operationFunctions
    internal fun createOperation(id: String): SettingHandler =
        if (id == "default") checkNotNull(binding) else checkNotNull(operationFunctions[id]) { "Unknown operation $id" }.invoke()

    fun validationError(value: SettingValue): String? {
        if (value.isAction) {
            val supplied = (value as? SettingValue.ActionValue)?.parameters.orEmpty()
            if (supplied.keys.any { it !in actionParameters }) return "Unknown action parameter"
            if (actionParameters.any { (id, spec) -> spec.required && id !in supplied }) return "Missing action parameter"
            if (supplied.any { (id, data) -> data.length > actionParameters.getValue(id).maxLength }) return "Action parameter is too long"
        }
        val matches = when (valueType) {
            ValueType.Boolean -> value is SettingValue.BooleanValue
            ValueType.Integer -> value is SettingValue.IntegerValue
            ValueType.Text -> value is SettingValue.TextValue
            ValueType.Choice -> value is SettingValue.ChoiceValue
            ValueType.MultiChoice -> value is SettingValue.MultiChoiceValue
            ValueType.Action -> value.isAction
        }
        if (!matches) return "Expected $valueType for $id"
        return when (value) {
            is SettingValue.IntegerValue -> when {
                minimum != null && value.value < minimum -> "Value is below $minimum"
                maximum != null && value.value > maximum -> "Value is above $maximum"
                else -> null
            }
            is SettingValue.TextValue ->
                if (maxLength != null && value.value.length > maxLength) "Text exceeds $maxLength characters" else null
            is SettingValue.ChoiceValue ->
                if (value.id !in choices) "Unknown choice: ${value.id}" else null
            is SettingValue.MultiChoiceValue ->
                if (value.ids.any { id -> id !in choices }) "Unknown choice in ${value.ids}" else null
            else -> null
        }
    }
}

data class ActionParameter(val required: Boolean = true, val maxLength: Int = 4096) {
    init { require(maxLength in 0..16384) }
}
