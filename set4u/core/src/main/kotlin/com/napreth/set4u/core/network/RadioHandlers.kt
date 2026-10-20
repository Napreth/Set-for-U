package com.napreth.set4u.core.network

import com.napreth.set4u.core.bridge.ShellCommand
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.session.DeviceTarget
import com.napreth.set4u.core.settings.SettingValue
import com.napreth.set4u.core.state.*

internal class MobileDataHandler(
    private val call: suspend (SettingContext, MobileDataRequest) -> MobileDataState = { context, request ->
        context.executeTask(MobileDataSpec().descriptor, request)
    },
) : SettingHandler {
    private var subscriptionId: Int? = null
    override val verificationPolicy = VerificationPolicy(5, 250)
    override suspend fun inspect(context: SettingContext): SettingSnapshot {
        require(context.target == DeviceTarget())
        val state = call(context, MobileDataRequest(expectedSubscriptionId = subscriptionId))
        return when (state.status) {
            MobileDataStatus.Ready -> {
                subscriptionId = state.subscriptionId
                SettingSnapshot(SettingValue.BooleanValue(checkNotNull(state.enabled)))
            }
            MobileDataStatus.NoActiveSubscription -> SettingSnapshot(null, SettingAccess.ReadOnly, "未检测到可用的默认数据 SIM 卡")
            MobileDataStatus.DefaultSubscriptionChanged -> SettingSnapshot(null, SettingAccess.Unavailable, "默认数据 SIM 卡已切换，请刷新后重试")
        }
    }
    override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
        require(context.target == DeviceTarget())
        val expected = checkNotNull(subscriptionId) { "请先读取默认数据 SIM 卡" }
        val state = call(context, MobileDataRequest((desiredValue as SettingValue.BooleanValue).value, expected))
        check(state.status == MobileDataStatus.Ready) { "默认数据 SIM 卡已发生变化，请刷新后重试" }
    }
}

internal class BluetoothHandler : SettingHandler {
    override val verificationPolicy = VerificationPolicy(24, 250)
    override suspend fun inspect(context: SettingContext): SettingSnapshot {
        val output = context.radioCommand("dumpsys", "bluetooth_manager")
        val header = output.lineSequence().dropWhile { !it.trim().removeSuffix(":").equals("Bluetooth Status", ignoreCase = true) }.drop(1).takeWhile { it.isNotBlank() }.toList()
        val state = header.singleOrNull { it.trim().startsWith("state:", ignoreCase = true) }?.substringAfter(':')?.trim()
        return when (state) {
            "ON" -> SettingSnapshot(SettingValue.BooleanValue(true))
            "OFF", "BLE_ON" -> SettingSnapshot(SettingValue.BooleanValue(false))
            "TURNING_ON", "TURNING_OFF", "BLE_TURNING_ON", "BLE_TURNING_OFF" -> SettingSnapshot(null, SettingAccess.ReadOnly, "蓝牙正在切换状态，请稍后刷新")
            else -> error("系统没有返回可识别的蓝牙状态")
        }
    }
    override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
        context.radioCommand("cmd", "bluetooth_manager", if ((desiredValue as SettingValue.BooleanValue).value) "enable" else "disable")
    }
}

internal class NfcHandler : SettingHandler {
    override val verificationPolicy = VerificationPolicy(20, 250)
    override suspend fun inspect(context: SettingContext): SettingSnapshot = when (context.radioCommand("cmd", "nfc", "status")) {
        "Nfc is enabled" -> SettingSnapshot(SettingValue.BooleanValue(true))
        "Nfc is disabled" -> SettingSnapshot(SettingValue.BooleanValue(false))
        else -> error("系统没有返回可识别的 NFC 状态")
    }
    override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
        if ((desiredValue as SettingValue.BooleanValue).value) context.radioCommand("cmd", "nfc", "enable-nfc")
        else context.radioCommand("cmd", "nfc", "disable-nfc", "[persist]")
    }
}

private suspend fun SettingContext.radioCommand(vararg args: String): String {
    require(target == DeviceTarget()) { "这些设置影响整台设备，请使用当前设备目标" }
    return execute(ShellCommand.arguments(*args)).requireSuccess().stdout.trim()
}
