package com.napreth.set4u.core.compatibility

/** Shared presentation data for the initial device information screen and CLI. */
object DeviceProfileReport {
    fun fields(profile: DeviceProfile): Map<String, String?> = linkedMapOf(
        "Manufacturer" to profile.value(DeviceFields.Manufacturer),
        "Brand" to profile.value(DeviceFields.Brand),
        "Model" to profile.value(DeviceFields.Model),
        "Device" to profile.value(DeviceFields.Device),
        "Product" to profile.value(DeviceFields.Product),
        "Android" to profile.value(DeviceFields.AndroidRelease),
        "API" to profile.value(DeviceFields.Sdk),
        "Security patch" to profile.value(DeviceFields.SecurityPatch),
        "Vendor security patch" to profile.properties["ro.vendor.build.security_patch"],
        "Build ID" to profile.properties["ro.build.id"],
        "Incremental build" to profile.properties["ro.build.version.incremental"],
        "Build display" to profile.properties["ro.build.display.id"],
        "Fingerprint" to profile.value(DeviceFields.Fingerprint),
        "Hardware / board" to profile.properties["ro.hardware"],
        "SKU" to profile.properties["ro.boot.hardware.sku"],
        "ODM SKU" to profile.properties["ro.boot.product.hardware.sku"],
        "SoC manufacturer" to profile.properties["ro.soc.manufacturer"],
        "SoC model" to profile.properties["ro.soc.model"],
        "ABIs" to profile.properties["ro.product.cpu.abilist"],
        "Bootloader" to profile.properties["ro.bootloader"],
        "Baseband" to profile.properties["gsm.version.baseband"],
        "Kernel" to profile.value(DeviceFields.KernelRelease),
        "Kernel build" to profile.value(DeviceFields.KernelBuild),
        "Architecture" to profile.value(DeviceFields.Architecture),
        "Page size" to profile.value(DeviceFields.PageSize),
    )
    fun text(profile: DeviceProfile): String = fields(profile).entries.joinToString("\n\n") { "${it.key}\n${it.value ?: "Unknown"}" }
    fun json(profile: DeviceProfile): String = fields(profile).entries.joinToString(",\n", "{\n", "\n}") {
        "  ${quote(it.key)}: ${it.value?.let(::quote) ?: "null"}"
    }
    private fun quote(value: String) = buildString {
        append('"')
        value.forEach { char -> when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
        } }
        append('"')
    }
}
