@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.napreth.set4u.core

import com.napreth.set4u.core.bridge.BridgeState
import com.napreth.set4u.core.bridge.IAdbBridge
import com.napreth.set4u.core.bridge.ShellCommand
import com.napreth.set4u.core.bridge.ShellRequest
import com.napreth.set4u.core.bridge.ShellResult
import com.napreth.set4u.core.execution.SettingContext
import com.napreth.set4u.core.execution.SettingHandler
import com.napreth.set4u.core.session.AppTarget
import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.core.session.SettingTarget
import com.napreth.set4u.core.settings.SettingDefinition
import com.napreth.set4u.core.settings.SettingValue
import com.napreth.set4u.core.settings.SettingsCatalog
import com.napreth.set4u.core.settings.ValueType
import com.napreth.set4u.core.settings.PageRows
import com.napreth.set4u.core.settings.SettingsPage
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.localization.UiText
import com.napreth.set4u.core.state.SettingSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import com.napreth.set4u.core.compatibility.CompatibilityRegistry
import com.napreth.set4u.core.compatibility.DeviceProfileSource

val Target = AppTarget(0, "com.example.app")
val Enabled = SettingValue.BooleanValue(true)
val Disabled = SettingValue.BooleanValue(false)

class FakeBridge : IAdbBridge {
    override val connectionState = MutableStateFlow(BridgeState.Ready)
    var testUid = 2000
    override suspend fun environment() = com.napreth.set4u.core.bridge.ExecutionEnvironment(com.napreth.set4u.core.bridge.BridgeTransport.Unknown, testUid)
    val requests = mutableListOf<ShellRequest>()
    val flags = mutableMapOf<String, Boolean>()
    var closeCalls = 0
    var onExecute: (suspend (ShellRequest) -> ShellResult)? = null

    override suspend fun execute(request: ShellRequest): ShellResult {
        requests += request
        return onExecute?.invoke(request) ?: respond(request)
    }

    fun respond(request: ShellRequest): ShellResult {
        val args = (request.command as ShellCommand.Arguments).values
        return when (args[0]) {
            "read-flag" -> ShellResult(0, (flags[args[1]] ?: false).toString())
            "write-flag" -> {
                flags[args[1]] = args[2].toBooleanStrict()
                ShellResult(0)
            }
            else -> error("Unexpected fake command: $args")
        }
    }

    override suspend fun close() {
        closeCalls++
        connectionState.value = BridgeState.Closed
    }

    val writes: List<ShellRequest>
        get() = requests.filter { (it.command as ShellCommand.Arguments).values[0] == "write-flag" }
}

class FlagHandler : SettingHandler {
    override suspend fun inspect(context: SettingContext): SettingSnapshot {
        val result = context.execute(
            ShellCommand.arguments("read-flag", context.target.toString())
        ).requireSuccess()
        return SettingSnapshot(result.stdout.toBooleanStrictOrNull()?.let(SettingValue::BooleanValue))
    }

    override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
        val value = desiredValue as SettingValue.BooleanValue
        context.execute(ShellCommand.arguments(
            "write-flag", context.target.toString(), value.value.toString(),
        )).requireSuccess()
    }
}

private val standardFlagHandler = FlagHandler()

fun flagDefinition(
    id: String = "sample.flag",
    handler: SettingHandler = standardFlagHandler,
) = SettingDefinition(id, ValueType.Boolean, handler)

private val entryCache = java.util.IdentityHashMap<SettingDefinition, SettingEntry>()
fun entryOf(definition: SettingDefinition): SettingEntry = entryCache.getOrPut(definition) {
    SettingEntry(definition, UiText.Literal(definition.id), choices = definition.choices.map { EntryChoice(it, UiText.Literal(it)) })
}
fun flagPage(id: String = "sample", definition: SettingDefinition = flagDefinition()) = testPage(id, "Sample", listOf(entryOf(definition)))
fun testPage(id: String, title: String, entries: List<SettingEntry>, layout: ((PageRows) -> List<RowContainer>)? = null): SettingsPage = object : SettingsPage(id, title) {
    override val entries = entries.toList()
    override fun layout(rows: PageRows) = layout?.invoke(rows) ?: listOf(RowContainer("body", entries.flatMap { rows.rows(it, unsupported = UnsupportedVisibility.Hide) }))
}
fun catalogOf(definition: SettingDefinition) = SettingsCatalog(flagPage(definition = definition))

suspend fun TestScope.withSession(
    bridge: FakeBridge = FakeBridge(),
    catalog: SettingsCatalog = catalogOf(flagDefinition()),
    options: CoreOptions = CoreOptions(),
    compatibility: CompatibilityRegistry = CompatibilityRegistry.Empty,
    deviceProfileSource: DeviceProfileSource? = null,
    block: suspend (CoreSession) -> Unit,
) {
    val session = Core.open(
        bridge, catalog, options, StandardTestDispatcher(testScheduler),
        clock = { testScheduler.currentTime },
        compatibility = compatibility, deviceProfileSource = deviceProfileSource,
    )
    try {
        block(session)
    } finally {
        session.close()
    }
}

suspend inline fun <reified T : Throwable> expectFailure(
    noinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (exception: Throwable) {
        if (exception is T) return exception
        throw AssertionError("Expected ${T::class.java.name}, got $exception", exception)
    }
    throw AssertionError("Expected ${T::class.java.name}")
}
