package com.napreth.set4u.core.execution

import com.napreth.set4u.core.compatibility.CapabilityAssessment
import com.napreth.set4u.core.session.SettingTarget
import java.util.Collections
import kotlin.reflect.KClass

/** Marker only. A handler defines its own business methods; the runtime owns execution policy. */
interface Handler

enum class HandlerEffect { ReadOnly, Mutating }

/** One capability, potentially with several platform implementations. Factories run per invocation. */
class HandlerDefinition<H : Handler>(
    val id: String,
    val type: KClass<H>,
    implementations: Map<String, () -> H>,
    val effect: HandlerEffect = HandlerEffect.Mutating,
    val requiresBridge: Boolean = true,
) {
    val implementations: Map<String, () -> H> = Collections.unmodifiableMap(LinkedHashMap(implementations))
    init { require(id.isNotBlank() && implementations.isNotEmpty() && implementations.keys.all { it.isNotBlank() }) }
    internal fun create(id: String): H = checkNotNull(implementations[id]) { "Unknown implementation $id" }.invoke().also {
        check(type.java.isInstance(it)) { "Handler factory returned the wrong type" }
    }
    internal fun same(other: HandlerDefinition<*>): Boolean = id == other.id && type == other.type && effect == other.effect && requiresBridge == other.requiresBridge &&
        implementations.keys == other.implementations.keys && implementations.all { (key, factory) -> factory === other.implementations[key] }
}

class HandlerRegistry(definitions: List<HandlerDefinition<*>>) {
    private val registered = linkedMapOf<String, HandlerDefinition<*>>()
    init { definitions.forEach { definition ->
        val previous = registered.putIfAbsent(definition.id, definition)
        require(previous == null || previous.same(definition)) { "Conflicting handler definition: ${definition.id}" }
    } }
    fun all(): List<HandlerDefinition<*>> = registered.values.toList()
    internal fun <H : Handler> resolve(reference: HandlerDefinition<H>): HandlerDefinition<H> {
        val canonical = requireNotNull(registered[reference.id]) { "Unregistered handler: ${reference.id}" }
        require(canonical.same(reference)) { "Conflicting handler definition: ${reference.id}" }
        @Suppress("UNCHECKED_CAST") return canonical as HandlerDefinition<H>
    }
}

enum class HandlerOutcome { Completed, Rejected, Failed, Uncertain, QueueTimedOut, TimedOut }
data class HandlerResult<R>(
    val operationId: String,
    val handlerId: String,
    val target: SettingTarget,
    val outcome: HandlerOutcome,
    val value: R? = null,
    val reason: FailureReason? = null,
    val capability: CapabilityAssessment? = null,
    val evidence: List<CommandEvidence> = emptyList(),
    val evidenceTruncated: Boolean = false,
) {
    fun requireCompleted(): R {
        check(outcome == HandlerOutcome.Completed) { reason?.message ?: outcome.name }
        @Suppress("UNCHECKED_CAST") return value as R
    }
}
