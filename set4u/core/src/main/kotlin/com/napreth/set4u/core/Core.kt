package com.napreth.set4u.core

import com.napreth.set4u.core.bridge.IAdbBridge
import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.core.settings.SettingsCatalog
import com.napreth.set4u.core.compatibility.CompatibilityRegistry
import com.napreth.set4u.core.compatibility.DeviceProfileSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

data class CoreOptions(
    val operationTimeoutMillis: Long = 30_000,
    val shutdownTimeoutMillis: Long = 5_000,
    val queueTimeoutMillis: Long = 5_000,
    val maxCachedSettings: Int = 512,
    val stateIdleTimeoutMillis: Long = 5 * 60_000,
) {
    init {
        require(operationTimeoutMillis > 0)
        require(shutdownTimeoutMillis > 0)
        require(queueTimeoutMillis > 0)
        require(maxCachedSettings > 0 && stateIdleTimeoutMillis > 0)
    }
}

object Core {
    fun open(
        bridge: IAdbBridge,
        catalog: SettingsCatalog = SettingsCatalog.Empty,
        options: CoreOptions = CoreOptions(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        clock: () -> Long = System::currentTimeMillis,
        compatibility: CompatibilityRegistry = CompatibilityRegistry.Empty,
        deviceProfileSource: DeviceProfileSource? = null,
    ): CoreSession = CoreSession(bridge, catalog, options, dispatcher, clock, compatibility, deviceProfileSource)
}
