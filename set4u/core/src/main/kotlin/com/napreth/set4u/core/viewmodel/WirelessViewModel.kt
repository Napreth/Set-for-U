package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.network.*
import com.napreth.set4u.core.session.*
import com.napreth.set4u.core.settings.SettingValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class WirelessUiState(val snapshot: WirelessSnapshot? = null, val loading: Boolean = false, val scanning: Boolean = false, val applying: Boolean = false, val error: String? = null)

/** Owns a visible wireless page, not a persistent radio service. */
class WirelessViewModel(private val session: CoreSession, val kind: WirelessKind, dispatcher: CoroutineDispatcher) {
    private val scope = CoroutineScope(session.scope.coroutineContext + SupervisorJob(session.scope.coroutineContext[Job]) + dispatcher)
    private val mutable = MutableStateFlow(WirelessUiState())
    val state = mutable.asStateFlow()
    private var query: Job? = null
    private var closed = false
    fun reload(scan: Boolean = false) {
        if (closed || state.value.applying) return
        query?.cancel()
        query = scope.launch {
            mutable.update { it.copy(loading = true, scanning = scan, error = null) }
            try {
                val result = session.handlers.queries.invoke(WirelessCapabilities.queries, DeviceTarget()) { h, context -> h.read(context, kind, scan) }
                if (result.outcome == HandlerOutcome.Completed) mutable.update { it.copy(snapshot = result.requireCompleted(), error = null) }
                else mutable.update { it.copy(error = result.reason?.message ?: "无线功能暂不可用") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "无法读取无线设备") } }
            finally { mutable.update { it.copy(loading = false, scanning = false) } }
        }
    }
    fun cancelScan() { if (!state.value.applying) query?.cancel() }
    fun apply(operation: String, parameters: Map<String, String> = emptyMap()) {
        if (closed || state.value.applying) return
        val oldQuery = query.also { it?.cancel() }
        mutable.update { it.copy(applying = true, error = null) }
        scope.launch {
            try {
                oldQuery?.join()
                // Writes belong to the Session after submission and retain engine verification.
                val result = session.settings.set(DeviceTarget(), WirelessCapabilities.actions.getValue(kind), SettingValue.ActionValue(parameters + ("operation" to operation))).await()
                if (result.outcome !in setOf(SettingOutcome.Verified, SettingOutcome.AlreadySatisfied)) mutable.update { it.copy(error = result.reason?.message ?: "操作未能完成") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "操作未能完成") } }
            finally { mutable.update { it.copy(applying = false) } }
            val error = state.value.error
            reload()
            query?.join()
            if (error != null) mutable.update { it.copy(error = error) }
        }
    }
    fun close() { closed = true; scope.cancel() }
}
