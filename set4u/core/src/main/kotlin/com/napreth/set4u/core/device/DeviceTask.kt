package com.napreth.set4u.core.device

import com.napreth.set4u.core.session.SettingTarget
import java.lang.reflect.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.reflect.KClass

/** A device entry is a concrete class. The host only refers to its descriptor. */
abstract class DeviceTask<Request : Any, Response : Any> {
    abstract suspend fun execute(context: DeviceTaskContext, request: Request): Response
}

/** Static identity for the future build-time task registry; never evaluate task instances to find it. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DeviceEntry(val id: String, val protocolVersion: Int = 1, val spec: KClass<*> = Unit::class)

/** Portable protocol declaration. It must never reference its device implementation. */
abstract class DeviceTaskSpec<Request : Any, Response : Any> {
    abstract val descriptor: DeviceTaskDescriptor<Request, Response>
}

data class DeviceTaskContext(val requestId: String, val target: SettingTarget) {
    init { require(requestId.isNotBlank()) }
}

interface DeviceCodec<T : Any> {
    fun encode(value: T): ByteArray
    fun decode(bytes: ByteArray): T
}

/** Portable host API: no reference to the Android implementation class or its constructor. */
class DeviceTaskDescriptor<Request : Any, Response : Any>(
    val id: String,
    val requestCodec: DeviceCodec<Request>,
    val responseCodec: DeviceCodec<Response>,
    val protocolVersion: Int = 1,
) {
    init { require(id.isNotBlank() && protocolVersion > 0) }
}

enum class DeviceTaskError { UnknownTask, ProtocolMismatch, InvalidRequest, ExecutionFailed }

sealed interface DeviceTaskResult {
    class Success(payload: ByteArray) : DeviceTaskResult {
        private val bytes = payload.copyOf()
        val payload: ByteArray get() = bytes.copyOf()
    }
    /** Failure does not imply that a device operation had no side effects. */
    data class Failure(val error: DeviceTaskError, val message: String) : DeviceTaskResult
}

/** Device-side registration only. Its class constraint excludes free functions and callback entries. */
class DeviceTaskRegistration<Request : Any, Response : Any>(
    val descriptor: DeviceTaskDescriptor<Request, Response>,
    taskClass: Class<out DeviceTask<Request, Response>>,
) {
    private val constructor = taskClass.getDeclaredConstructor()

    init {
        val entry = requireNotNull(taskClass.getAnnotation(DeviceEntry::class.java)) { "Missing @DeviceEntry: ${taskClass.name}" }
        require(entry.id == descriptor.id && entry.protocolVersion == descriptor.protocolVersion) { "Device entry does not match descriptor: ${taskClass.name}" }
        require(Modifier.isPublic(taskClass.modifiers) && !Modifier.isAbstract(taskClass.modifiers)) { "Device entry must be a public concrete class" }
        require(Modifier.isPublic(constructor.modifiers)) { "Device entry needs a public no-argument constructor" }
    }

    internal suspend fun dispatch(context: DeviceTaskContext, payload: ByteArray, limit: Int): DeviceTaskResult {
        currentCoroutineContext().ensureActive()
        val request = try { descriptor.requestCodec.decode(payload.copyOf()) }
        catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { return DeviceTaskResult.Failure(DeviceTaskError.InvalidRequest, exception.message ?: "Invalid request") }

        return try {
            val response = constructor.newInstance().execute(context, request)
            currentCoroutineContext().ensureActive()
            val bytes = descriptor.responseCodec.encode(response)
            if (bytes.size > limit) DeviceTaskResult.Failure(DeviceTaskError.ExecutionFailed, "Device response exceeds $limit bytes")
            else DeviceTaskResult.Success(bytes)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            DeviceTaskResult.Failure(DeviceTaskError.ExecutionFailed, exception.message ?: exception.javaClass.simpleName)
        }
    }
}

/** Common dispatcher for the future app_process and APK execution entries. */
class DeviceTaskRegistry(registrations: List<DeviceTaskRegistration<*, *>>, private val maxPayloadBytes: Int = 1_048_576) {
    private val entries = registrations.associateBy { it.descriptor.id }
    init {
        require(maxPayloadBytes > 0)
        require(entries.size == registrations.size) { "Duplicate device task ID" }
    }

    suspend fun execute(
        taskId: String,
        protocolVersion: Int,
        context: DeviceTaskContext,
        payload: ByteArray,
    ): DeviceTaskResult {
        val entry = entries[taskId] ?: return DeviceTaskResult.Failure(DeviceTaskError.UnknownTask, "Unknown device task: $taskId")
        if (protocolVersion != entry.descriptor.protocolVersion) {
            return DeviceTaskResult.Failure(DeviceTaskError.ProtocolMismatch, "Unsupported task protocol: $protocolVersion")
        }
        if (payload.size > maxPayloadBytes) return DeviceTaskResult.Failure(DeviceTaskError.InvalidRequest, "Device request exceeds $maxPayloadBytes bytes")
        return entry.dispatch(context, payload, maxPayloadBytes)
    }
}
