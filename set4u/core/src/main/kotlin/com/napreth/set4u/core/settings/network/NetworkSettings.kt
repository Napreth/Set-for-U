package com.napreth.set4u.core.settings.network

import com.napreth.set4u.core.bridge.ShellCommand
import com.napreth.set4u.core.network.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.session.DeviceTarget
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.state.SettingSnapshot

object NetworkSettings {
    val wifi = SettingDefinition("network.wifi.enabled", ValueType.Boolean, WifiHandler())
    val mobileData = SettingDefinition("network.mobile_data.enabled", ValueType.Boolean,
        operationFunctions = mapOf("telephony31" to { MobileDataHandler() }))
    val bluetooth = SettingDefinition("network.bluetooth.enabled", ValueType.Boolean,
        operationFunctions = mapOf("manager31" to { BluetoothHandler() }))
    val nfc = SettingDefinition("network.nfc.enabled", ValueType.Boolean,
        operationFunctions = mapOf("shell35" to { NfcHandler() }))
    val airplane = SettingDefinition("network.airplane.enabled", ValueType.Boolean, AirplaneHandler())
    val dnsMode = SettingDefinition("network.private_dns.mode", ValueType.Choice, DnsModeHandler(),
        choices = listOf("off", "opportunistic", "hostname"))
    val dnsHostname = SettingDefinition("network.private_dns.hostname", ValueType.Text, DnsHostnameHandler(),
        maxLength = 253)
    val definitions = listOf(wifi, airplane, dnsMode, dnsHostname, mobileData, bluetooth, nfc)
}

private fun SettingContext.requireDevice() {
    require(target == DeviceTarget()) { "这些设置影响整台设备，请使用当前设备目标" }
}

private suspend fun SettingContext.command(vararg args: String): String {
    requireDevice()
    return execute(ShellCommand.arguments(*args)).requireSuccess().stdout.trim()
}

private class WifiHandler : SettingHandler {
    override val verificationPolicy = VerificationPolicy(20, 300)
    override suspend fun inspect(context: SettingContext): SettingSnapshot {
        val status = context.command("cmd", "wifi", "status")
        val enabled = when {
            status.lineSequence().any { it.trim() == "Wifi is enabled" } -> true
            status.lineSequence().any { it.trim() == "Wifi is disabled" } -> false
            else -> error("系统没有返回可识别的 WLAN 状态")
        }
        return SettingSnapshot(SettingValue.BooleanValue(enabled))
    }
    override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
        context.command("svc", "wifi", if ((desiredValue as SettingValue.BooleanValue).value) "enable" else "disable")
    }
}

private class AirplaneHandler : SettingHandler {
    override val verificationPolicy = VerificationPolicy(10, 250)
    override suspend fun inspect(context: SettingContext): SettingSnapshot {
        val value = context.command("cmd", "connectivity", "airplane-mode")
        val enabled = when (value) { "enabled" -> true; "disabled" -> false; else -> error("无法读取飞行模式状态") }
        return SettingSnapshot(SettingValue.BooleanValue(enabled))
    }
    override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
        context.command("cmd", "connectivity", "airplane-mode", if ((desiredValue as SettingValue.BooleanValue).value) "enable" else "disable")
    }
}

private suspend fun SettingContext.global(key: String) = command("settings", "get", "global", key).let { if (it == "null") "" else it }
private fun validHostname(value: String): Boolean = value.length in 1..253 && value.split('.').all {
    it.length in 1..63 && it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
} && value.any { it.isLetter() }

private class DnsModeHandler : SettingHandler {
    override suspend fun inspect(context: SettingContext): SettingSnapshot {
        val mode = context.global("private_dns_mode").ifEmpty {
            context.global("private_dns_default_mode").ifEmpty { "opportunistic" }
        }
        check(mode in setOf("off", "opportunistic", "hostname")) { "无法识别私人 DNS 模式" }
        return SettingSnapshot(SettingValue.ChoiceValue(mode))
    }
    override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
        val mode = (desiredValue as SettingValue.ChoiceValue).id
        if (mode == "hostname") require(validHostname(context.global("private_dns_specifier"))) { "请先填写有效的 DNS 提供商主机名" }
        context.command("settings", "put", "global", "private_dns_mode", mode)
    }
}

private class DnsHostnameHandler : SettingHandler {
    override suspend fun inspect(context: SettingContext) = SettingSnapshot(SettingValue.TextValue(context.global("private_dns_specifier")))
    override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
        val hostname = (desiredValue as SettingValue.TextValue).value
        require(hostname.isEmpty() || validHostname(hostname)) { "请输入 DNS 主机名，不要填写网址、端口或 IP 地址" }
        require(hostname.isNotEmpty() || context.global("private_dns_mode") != "hostname") { "先将私人 DNS 切换为自动或关闭，再清空主机名" }
        context.command("settings", "put", "global", "private_dns_specifier", hostname)
    }
}
