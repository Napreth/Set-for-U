package com.napreth.set4u.core.state

import com.napreth.set4u.core.execution.SettingResult
import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.settings.SettingValue
import kotlinx.coroutines.flow.*

enum class SettingAccess { Writable, ReadOnly, Unsupported, Manual, Unavailable, Busy }

/** A null value means unknown. A read failure must not be represented as false. */
data class SettingSnapshot(
    val value: SettingValue?,
    val access: SettingAccess = SettingAccess.Writable,
    val message: String? = null,
    val capability: com.napreth.set4u.core.compatibility.CapabilityAssessment? = null,
)

data class SettingKey(
    val sessionId: String,
    val target: SettingTarget,
    val settingId: String,
)

data class SettingObservation(
    val key: SettingKey,
    val snapshot: SettingSnapshot,
    val readAtMillis: Long,
)

data class SettingStatus(
    val observation: SettingObservation? = null,
    val activeOperationId: String? = null,
    val requestedValue: SettingValue? = null,
    val lastResult: SettingResult? = null,
    val phase: OperationPhase? = null,
    val readDeferred: Boolean = false,
) {
    val isChanging: Boolean get() = activeOperationId != null
}

enum class OperationPhase { Queued, Running }

internal class SettingStateStore(
    private val capacity: Int = 512,
    private val idleMillis: Long = 300_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Versioned(val revision: Long, val status: SettingStatus)
    private class Entry(var touched: Long, var watchers: Int = 0) {
        var value = Versioned(0, SettingStatus())
        val flow = MutableStateFlow(value)
    }
    private val entries = LinkedHashMap<SettingKey, Entry>()
    private val revision = MutableStateFlow(0L)

    // Legacy aggregate view takes a snapshot only when read/collected, not on every write.
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class, kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    val state: StateFlow<Map<SettingKey, SettingStatus>> = object : StateFlow<Map<SettingKey, SettingStatus>> {
        override val value: Map<SettingKey, SettingStatus> get() = synchronized(entries) { entries.mapValues { it.value.value.status } }
        override val replayCache get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<Map<SettingKey, SettingStatus>>): Nothing {
            revision.map { value }.distinctUntilChanged().collect { collector.emit(it) }
            error("StateFlow collection completed")
        }
    }

    fun observeKey(key: SettingKey): Flow<SettingStatus> = flow {
        val entry = synchronized(entries) { entry(key).also { it.watchers++; prune() } }
        revision.update { it + 1 }
        try { emitAll(entry.flow.map { it.status }.distinctUntilChanged()) }
        finally {
            synchronized(entries) { entry.watchers--; entry.touched = clock(); prune() }
            revision.update { it + 1 }
        }
    }

    fun observeKeys(keys: Set<SettingKey>): Flow<Map<SettingKey, SettingStatus>> =
        if (keys.isEmpty()) flowOf(emptyMap())
        else combine(keys.map { key -> observeKey(key).map { key to it } }) { values -> values.toMap() }

    fun status(key: SettingKey): SettingStatus = synchronized(entries) { entries[key]?.value?.status ?: SettingStatus() }

    fun observe(observation: SettingObservation) = update(observation.key) {
        if (observation.snapshot.access == SettingAccess.Busy)
            it.copy(readDeferred = true)
        else it.copy(observation = observation, readDeferred = false)
    }
    fun start(key: SettingKey, operationId: String, desiredValue: SettingValue) = update(key) {
        it.copy(activeOperationId = operationId, requestedValue = desiredValue, lastResult = null, phase = OperationPhase.Queued)
    }
    fun running(key: SettingKey, operationId: String) = update(key) {
        if (it.activeOperationId == operationId) it.copy(phase = OperationPhase.Running) else it
    }
    fun finish(result: SettingResult) = update(result.key) {
        if (it.activeOperationId != result.operationId) it
        else it.copy(activeOperationId = null, requestedValue = null, lastResult = result, phase = null)
    }

    fun releaseTarget(target: SettingTarget) {
        synchronized(entries) {
            entries.entries.removeAll { it.key.target == target && it.value.watchers == 0 && !it.value.value.status.isChanging }
        }
        revision.update { it + 1 }
    }

    private fun entry(key: SettingKey) = entries.getOrPut(key) { Entry(clock()) }
    private fun update(key: SettingKey, transform: (SettingStatus) -> SettingStatus) {
        val (entry, value) = synchronized(entries) {
            val entry = entry(key)
            entry.touched = clock()
            val value = Versioned(entry.value.revision + 1, transform(entry.value.status))
            entry.value = value
            prune()
            entry to value
        }
        // Subscribers may resume inline and re-enter the service. Never emit while holding entries.
        entry.flow.update { if (value.revision > it.revision) value else it }
        revision.update { it + 1 }
    }
    private fun prune() {
        val now = clock()
        entries.entries.filter { it.value.watchers == 0 && !it.value.value.status.isChanging }
            .sortedBy { it.value.touched }.forEach {
                if (entries.size > capacity || now - it.value.touched >= idleMillis) entries.remove(it.key)
            }
    }
}
