package com.napreth.set4u.core.session

sealed interface SettingTarget

data class AppTarget(val userId: Int, val packageName: String) : SettingTarget {
    init {
        require(userId >= 0)
        require(PACKAGE_NAME.matches(packageName)) { "Invalid Android package name" }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")
    }
}

data class DeviceTarget(val userId: Int? = null) : SettingTarget {
    init { require(userId == null || userId >= 0) }
}
