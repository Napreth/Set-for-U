package com.napreth.set4u.core.session

import com.napreth.set4u.core.CoreOptions
import com.napreth.set4u.core.bridge.IAdbBridge
import com.napreth.set4u.core.execution.ExecutionEngine
import com.napreth.set4u.core.execution.ShellExecutor
import com.napreth.set4u.core.settings.SettingsCatalog
import com.napreth.set4u.core.settings.SettingsService
import com.napreth.set4u.core.state.SettingStateStore
import com.napreth.set4u.core.state.SettingKey
import com.napreth.set4u.core.viewmodel.ViewModelFactory
import com.napreth.set4u.core.compatibility.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.napreth.set4u.core.bridge.BridgeState

class CoreSession internal constructor(
    private val bridge: IAdbBridge,
    val catalog: SettingsCatalog,
    private val options: CoreOptions,
    dispatcher: CoroutineDispatcher,
    clock: () -> Long,
    compatibilityRegistry: CompatibilityRegistry,
    deviceProfileSource: DeviceProfileSource?,
) {
    val id: String = UUID.randomUUID().toString()
    private val closed = AtomicBoolean(false)
    private val job = SupervisorJob()
    internal val scope = CoroutineScope(job + dispatcher)
    private val store = SettingStateStore(options.maxCachedSettings, options.stateIdleTimeoutMillis, clock)

    val connectionState = bridge.connectionState
    val state = store.state
    private val shell = ShellExecutor(bridge)
    private val engine = ExecutionEngine(id, scope, shell, store, options, clock)
    val compatibility = CompatibilityService(compatibilityRegistry, deviceProfileSource ?: DeviceInfoCollector(bridge, clock), ::requireOpen, com.napreth.set4u.core.bridge.ExecutionEnvironmentSource { bridge.environment() })
    internal val clock = clock
    suspend fun executionEnvironment() = bridge.environment()
    val settings = SettingsService(catalog, engine, compatibility)
    val search = com.napreth.set4u.core.settings.presentation.SettingsSearchService(catalog, compatibility)
    val navigation = com.napreth.set4u.core.settings.SystemSettingsNavigator(shell)
    val viewModels = ViewModelFactory(this)

    init {
        scope.launch { connectionState.collect { if (it != BridgeState.Ready) { compatibility.invalidate(); shell.invalidateTasks() } } }
    }

    internal fun requireOpen() {
        check(!closed.get()) { "Session is closed" }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            com.napreth.set4u.core.bridge.cleanupWithin(options.shutdownTimeoutMillis) {
                try {
                    com.napreth.set4u.core.bridge.cleanupWithin((options.shutdownTimeoutMillis / 2).coerceAtLeast(1)) { job.join(); shell.close() }
                } finally { bridge.close() }
            }
        }
    }

    fun observeSetting(target: SettingTarget, settingId: String) = store.observeKey(SettingKey(id, target, settingId))
    internal fun observeSettings(keys: Set<SettingKey>) = store.observeKeys(keys)
    internal fun status(key: SettingKey) = store.status(key)
    fun releaseTarget(target: SettingTarget) { store.releaseTarget(target) }
}
