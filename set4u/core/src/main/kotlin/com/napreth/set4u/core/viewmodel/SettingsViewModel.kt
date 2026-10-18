package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.session.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.state.*
import com.napreth.set4u.core.execution.SettingResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SettingsViewModel internal constructor(private val session: CoreSession, val target: SettingTarget, val pageId: String, val locale: String) {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val scope = CoroutineScope(session.scope.coroutineContext + SupervisorJob(session.scope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state = mutableState.asStateFlow()
    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private val renderLock = Any()
    private var page: SettingsPageSnapshot? = null
    private val frames = mutableMapOf<String, EntrySnapshot>()
    private var bindings = emptyMap<String, EntryBinding>()
    private var loadJob: Job? = null
    private var pageJobs: Job? = null
    private var entered = false
    private val token = UUID.randomUUID().toString()

    @Synchronized fun enter(): Job {
        requireOpen()
        if (entered) return checkNotNull(loadJob)
        entered = true
        return load(PageLoadReason.Enter)
    }
    @Synchronized fun refresh() = load(PageLoadReason.Refresh)
    private fun load(reason: PageLoadReason): Job {
        requireOpen()
        val version = generation.incrementAndGet()
        loadJob?.cancel(); pageJobs?.cancel()
        synchronized(renderLock) { bindings = emptyMap(); frames.clear() }
        mutableState.update { it.copy(isLoading = true, error = null) }
        return scope.launch {
            try {
                if (reason == PageLoadReason.Refresh) session.compatibility.invalidate()
                val loaded = session.settings.loadPage(target, pageId)
                currentCoroutineContext().ensureActive()
                if (!isCurrent(version)) return@launch
                synchronized(renderLock) { page = loaded.page; publish(version) }
                val observers = SupervisorJob(scope.coroutineContext[Job]).also { pageJobs = it }
                scope.launch(observers) {
                    session.observeSettings(loaded.page.settings.map { key(it.id) }.toSet()).collect {
                        if (isCurrent(version)) synchronized(renderLock) { publish(version) }
                    }
                }
                loaded.page.entries.forEach { entry -> scope.launch(observers) {
                    var last: Long? = null
                    try {
                        // Dynamic sources must not run merely because a page contains their entry.
                        if (session.compatibility.assess(entry.setting).operation !is com.napreth.set4u.core.compatibility.OperationAvailability.Direct) return@launch
                        entry.observe(target).collect { frame ->
                            currentCoroutineContext().ensureActive()
                            if (!isCurrent(version)) return@collect
                            require(last == null || frame.revision > checkNotNull(last)) { "Entry revisions must increase: ${entry.id}" }
                            last = frame.revision
                            synchronized(renderLock) { frames[entry.id] = frame; publish(version) }
                        }
                    } catch (exception: CancellationException) { throw exception }
                    catch (exception: Exception) {
                        if (isCurrent(version)) synchronized(renderLock) {
                            frames.remove(entry.id); publish(version)
                            mutableState.update { it.copy(error = exception.message ?: "Unable to load entry") }
                        }
                    }
                } }
                loaded.onEnter(SettingsPageContext(target, reason, loaded.page.settings) { definition ->
                    currentCoroutineContext().ensureActive()
                    check(isCurrent(version)) { "Stale page load" }
                    session.settings.read(target, definition)
                })
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { if (isCurrent(version)) mutableState.update { it.copy(error = exception.message ?: "Unable to load page") } }
            finally { if (isCurrent(version)) mutableState.update { it.copy(isLoading = false) } }
        }.also { loadJob = it }
    }

    /** Must be called with renderLock held. Every published container is a fresh immutable snapshot. */
    private fun publish(version: Long) {
        try { buildPresentation(version) }
        catch (exception: Exception) {
            bindings = emptyMap()
            mutableState.update { current -> current.copy(error = exception.message ?: "Invalid page presentation",
                containers = current.containers.map { group -> group.copy(items = group.items.map { it.copy(bound = false) }) }) }
        }
    }

    private fun buildPresentation(version: Long) {
        if (!isCurrent(version)) return
        val current = page ?: return
        val rows = PageRows(current, frames.toMap(), session.catalog.texts, locale, "$token:$version", ::status)
        val raw = current.layout(rows)
        require(raw.size <= 512 && raw.map { it.id }.distinct().size == raw.size) { "Duplicate or excessive containers" }
        val ids = mutableSetOf<String>()
        val containers = raw.mapNotNull { container ->
            require(container.id.isNotBlank())
            val items = container.rows.map { row ->
                require(row.id.isNotBlank() && row.title.isNotBlank() && ids.add(row.id)) { "Duplicate or invalid row: ${row.id}" }
                if (row is NavigationRow) session.catalog.validateDestination(row.destination)
                val frozen = when (row) {
                    is NavigationRow -> row
                    is SettingRow -> row.copy(control = (row.control as? RowControl.Choice)?.let { it.copy(options = Collections.unmodifiableList(ArrayList(it.options))) } ?: row.control)
                }
                val binding = rows.bindings[row.id]
                require(row !is SettingRow || row.control == RowControl.Value || binding != null) { "Editable row has no entry: ${row.id}" }
                SettingUiItem(frozen, binding?.token ?: "$token:$version:${row.id}", binding?.let { status(it.entry.setting.id) } ?: SettingStatus(), binding != null)
            }
            if (items.isEmpty()) null else SettingUiContainer(container.copy(rows = Collections.unmodifiableList(items.map { it.component })), Collections.unmodifiableList(items))
        }
        require(ids.size <= 4096) { "Too many page rows" }
        bindings = rows.bindings.filterKeys { it in ids }
        mutableState.update { it.copy(page = SettingsPageInfo(current.id, session.catalog.texts.resolve(current.title, locale)), containers = Collections.unmodifiableList(containers)) }
    }

    fun dispatch(event: SettingsEvent): Job? = when (event) {
        SettingsEvent.Refresh -> refresh()
        is SettingsEvent.Activate -> activate(event.componentId, event.token)
        is SettingsEvent.ChangeValue -> setValue(event.componentId, event.desiredValue, event.token)
        is SettingsEvent.SelectChoice -> setValue(event.componentId, SettingValue.ChoiceValue(event.choiceId), event.token)
    }
    fun activate(id: String, suppliedToken: String? = null): Deferred<SettingResult>? {
        requireOpen()
        val ui = state.value.find(id) ?: return reject("Row is not visible: $id")
        if (suppliedToken != null && suppliedToken != ui.token) return reject("Stale row: $id")
        if (ui.component is NavigationRow) {
            val destination = ui.component.destination
            val effect = destination.systemScreen?.let { SettingsEffect.OpenSystemSettings(it, destination.target ?: target) } ?: SettingsEffect.Navigate(destination.pageId, destination.target ?: target)
            effectChannel.trySend(effect); return null
        }
        val binding = synchronized(renderLock) { bindings[id] } ?: return reject("Row is not an operation")
        val desired = when (val request = binding.request) {
            is RowRequest.Fixed -> request.value
            is RowRequest.ToggleChoice -> {
                val selected = (status(binding.entry.setting.id).observation?.snapshot?.value as? SettingValue.MultiChoiceValue)?.ids ?: return reject("Read the selection first")
                SettingValue.MultiChoiceValue(if (request.choiceId in selected) selected - request.choiceId else selected + request.choiceId)
            }
            else -> return reject("Row has no activation action")
        }
        return submit(ui, binding, desired, suppliedToken)
    }
    fun setValue(id: String, value: SettingValue, suppliedToken: String? = null): Deferred<SettingResult>? {
        requireOpen()
        val ui = state.value.find(id) ?: return reject("Row is not visible: $id")
        val binding = synchronized(renderLock) { bindings[id] } ?: return reject("Row is not editable")
        val control = (ui.component as? SettingRow)?.control
        if (binding.request != RowRequest.Edit || !(control == RowControl.Toggle || control is RowControl.Input || control is RowControl.Choice)) return reject("Row is not editable")
        return submit(ui, binding, value, suppliedToken)
    }
    private fun submit(ui: SettingUiItem, binding: EntryBinding, value: SettingValue, suppliedToken: String?): Deferred<SettingResult>? {
        val current = status(binding.entry.setting.id)
        if (current.observation?.snapshot?.access != SettingAccess.Writable) return reject("Setting is not writable")
        if (current.isChanging && current.requestedValue != value) return reject("Setting is busy")
        if ((ui.component as? SettingRow)?.control == RowControl.Toggle && current.observation.snapshot.value !is SettingValue.BooleanValue) return reject("Read the switch first")
        if (suppliedToken != null && suppliedToken != ui.token) return reject("Stale row")
        if (binding.resourceBound && suppliedToken != ui.token) return reject("Dynamic rows require a version token")
        val version = generation.get()
        val guard: () -> Unit = {
            if (binding.resourceBound) synchronized(renderLock) {
                check(isCurrent(version) && bindings[ui.id]?.token == binding.token && frames[binding.entry.id]?.revision == binding.revision) { "Dynamic resource is no longer current" }
                check(binding.expiresAt?.let { session.clock() < it } != false) { "Dynamic resource expired" }
            }
        }
        return try {
            guard()
            session.settings.setBound(target, binding.entry.setting, value, if (binding.resourceBound) binding.token else null, guard).also { mutableState.update { it.copy(error = null) } }
        } catch (exception: IllegalArgumentException) { reject(exception.message ?: "Invalid value") }
        catch (exception: IllegalStateException) { reject(exception.message ?: "Unavailable operation") }
    }
    private fun reject(message: String): Nothing? { mutableState.update { it.copy(error = message) }; return null }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet(); scope.cancel(); effectChannel.close()
        synchronized(renderLock) { bindings = emptyMap(); frames.clear() }
    }
    private fun isCurrent(version: Long) = !closed.get() && generation.get() == version
    private fun key(id: String) = SettingKey(session.id, target, id)
    private fun status(id: String) = session.status(key(id))
    private fun requireOpen() { session.requireOpen(); check(!closed.get()) { "ViewModel is closed" } }
}
