package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.bridge.*
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

enum class FactState { Known, Absent, Unavailable }

data class DeviceFact(val value: String?, val state: FactState, val source: String, val detail: String? = null) {
    init { require((state == FactState.Known) == (value != null)) }
}

data class DeviceField(val id: String, val property: String? = null) {
    init { require(id.isNotBlank()); require(property == null || property.isNotBlank()) }
}

object DeviceFields {
    val Manufacturer = property("ro.product.manufacturer")
    val Brand = property("ro.product.brand")
    val Model = property("ro.product.model")
    val Device = property("ro.product.device")
    val Product = property("ro.product.name")
    val Sdk = property("ro.build.version.sdk")
    val FullSdk = property("ro.build.version.sdk_full")
    val AndroidRelease = property("ro.build.version.release")
    val Fingerprint = property("ro.build.fingerprint")
    val SecurityPatch = property("ro.build.version.security_patch")
    val KernelRelease = DeviceField("kernel.release")
    val KernelBuild = DeviceField("kernel.build")
    val Architecture = DeviceField("kernel.architecture")
    val PageSize = DeviceField("runtime.pageSize")
    val PackageFeatures = DeviceField("package.features")
    fun property(name: String) = DeviceField("property:$name", name)
}

/** An immutable snapshot. Vendor-specific version properties remain available without schema changes. */
class DeviceProfile(
    properties: Map<String, String>,
    facts: Map<DeviceField, DeviceFact> = emptyMap(),
    val collectedAtMillis: Long = System.currentTimeMillis(),
) {
    val properties: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(properties))
    val facts: Map<DeviceField, DeviceFact> = Collections.unmodifiableMap(LinkedHashMap(facts))
    fun fact(field: DeviceField): DeviceFact {
        field.property?.let { key ->
            val value = properties[key]
            return when {
                value.isNullOrBlank() -> DeviceFact(null, FactState.Absent, "getprop:$key")
                value.equals("unknown", true) -> DeviceFact(null, FactState.Unavailable, "getprop:$key", "Device reported unknown")
                else -> DeviceFact(value, FactState.Known, "getprop:$key")
            }
        }
        return facts[field] ?: DeviceFact(null, FactState.Unavailable, field.id, "Not collected")
    }
    fun value(field: DeviceField): String? = fact(field).value
    val sdkInt: Int? get() = value(DeviceFields.Sdk)?.toIntOrNull()
}

fun interface DeviceProfileSource { suspend fun collect(): DeviceProfile }

/** All information comes from the target device, including on Desktop. */
class DeviceInfoCollector(
    private val bridge: IAdbBridge,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceProfileSource {
    override suspend fun collect(): DeviceProfile {
        val result = command("getprop").requireSuccess()
        val properties = parseProperties(result.stdout)
        check(properties.isNotEmpty()) { "Device returned no readable system properties" }
        val probes = listOf(
            DeviceFields.KernelRelease to arrayOf("uname", "-r"),
            DeviceFields.KernelBuild to arrayOf("uname", "-v"),
            DeviceFields.Architecture to arrayOf("uname", "-m"),
            DeviceFields.PageSize to arrayOf("getconf", "PAGESIZE"),
            DeviceFields.PackageFeatures to arrayOf("pm", "list", "features"),
        )
        val facts = coroutineScope { probes.map { (field, args) -> async {
            field to try {
                val response = command(*args).requireSuccess()
                val text = if (field == DeviceFields.PackageFeatures) {
                    val lines = response.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                    check(lines.isNotEmpty() && lines.all { it.matches(Regex("feature:[A-Za-z0-9_.]+(?:=.*)?")) }) { "Unrecognised package feature list" }
                    lines.map { it.removePrefix("feature:").substringBefore('=') }.distinct().joinToString("\n")
                } else response.stdout.trim().takeIf { it.isNotEmpty() }
                DeviceFact(text, if (text == null) FactState.Unavailable else FactState.Known, args.joinToString(" "))
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { DeviceFact(null, FactState.Unavailable, args.joinToString(" "), exception.message) }
        } }.awaitAll().toMap() }
        return DeviceProfile(properties, facts, clock())
    }

    private suspend fun command(vararg args: String): ShellResult = withTimeoutOrNull(5_000) {
        check(bridge.connectionState.value == BridgeState.Ready) { "Device bridge is not ready" }
        bridge.execute(ShellRequest(ShellCommand.arguments(*args), 5_000))
    } ?: throw ShellTimeoutException(5_000)

    companion object {
        fun parseProperties(output: String): Map<String, String> = buildMap {
            val pattern = Regex("^\\[([^]]+)]: \\[(.*)]$")
            output.lineSequence().forEach { line ->
                pattern.matchEntire(line.trimEnd())?.let { put(it.groupValues[1], it.groupValues[2]) }
            }
        }
    }
}
