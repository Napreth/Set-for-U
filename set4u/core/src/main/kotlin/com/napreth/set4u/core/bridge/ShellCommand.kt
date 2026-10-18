package com.napreth.set4u.core.bridge

sealed interface ShellCommand {
    /** Arguments for the target Android shell, not the host operating system. */
    data class Arguments(val values: List<String>) : ShellCommand {
        init {
            require(values.isNotEmpty())
            require(values.first().isNotBlank())
            require(values.none { '\u0000' in it })
        }
    }

    /** Explicit shell syntax; callers are responsible for the script's contents. */
    data class Script(val source: String) : ShellCommand {
        init {
            require(source.isNotBlank())
            require('\u0000' !in source)
        }
    }

    fun render(): String = when (this) {
        is Arguments -> values.joinToString(" ") { quote(it) }
        is Script -> source
    }

    companion object {
        fun arguments(vararg values: String): Arguments = Arguments(values.toList())

        private fun quote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
