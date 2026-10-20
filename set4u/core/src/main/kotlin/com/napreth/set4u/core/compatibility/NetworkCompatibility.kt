package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.settings.network.NetworkSettings
import com.napreth.set4u.core.bridge.ExecutionIdentity
import com.napreth.set4u.core.settings.SystemSettingsScreen

/** Extend this package with ROM overrides; pages continue to reference the same definitions. */
object NetworkCompatibility {
    val registry = CompatibilityRegistry(listOf(NetworkSettings.wifi, NetworkSettings.airplane, NetworkSettings.dnsMode, NetworkSettings.dnsHostname).map {
        CompatibilityRule("${it.id}.android", it.id,
            DeviceCondition.IntegerRange(DeviceFields.Sdk, if (it.id.startsWith("network.private_dns")) 28 else 30),
            RuleDecision.Use("default"))
    } + listOf(
        CompatibilityRule("network.mobile_data.android31", NetworkSettings.mobileData.id, DeviceCondition.All(listOf(
            DeviceCondition.IntegerRange(DeviceFields.Sdk, 31),
            DeviceCondition.AnyOf(listOf(
                DeviceCondition.HasFeature("android.hardware.telephony.data"),
                DeviceCondition.All(listOf(DeviceCondition.IntegerRange(DeviceFields.Sdk, maximum = 32), DeviceCondition.HasFeature("android.hardware.telephony"))),
            )),
        )), RuleDecision.Use("telephony31")),
        CompatibilityRule("network.bluetooth.android31", NetworkSettings.bluetooth.id, DeviceCondition.All(listOf(
            DeviceCondition.IntegerRange(DeviceFields.Sdk, 31), DeviceCondition.HasFeature("android.hardware.bluetooth"),
        )), RuleDecision.Use("manager31")),
        CompatibilityRule("network.nfc.android35", NetworkSettings.nfc.id, DeviceCondition.All(listOf(
            DeviceCondition.IntegerRange(DeviceFields.Sdk, 35), DeviceCondition.HasFeature("android.hardware.nfc"),
        )), RuleDecision.Use("shell35")),
    ) + (listOf(com.napreth.set4u.core.network.WirelessCapabilities.queries.id) + com.napreth.set4u.core.network.WirelessCapabilities.actions.values.map { it.id }).map {
        CompatibilityRule("$it.android30", it, DeviceCondition.IntegerRange(DeviceFields.Sdk, 30), RuleDecision.Use("default"))
    }, policies = NetworkSettings.definitions.associate { definition ->
        val probe = when (definition.id) {
            NetworkSettings.nfc.id -> FeatureSupportProbe("android.hardware.nfc")
            NetworkSettings.bluetooth.id -> FeatureSupportProbe("android.hardware.bluetooth")
            else -> null
        }
        val manual = when (definition.id) {
            NetworkSettings.wifi.id -> SystemSettingsScreen.Wifi
            NetworkSettings.mobileData.id -> SystemSettingsScreen.Mobile
            NetworkSettings.bluetooth.id -> SystemSettingsScreen.Bluetooth
            NetworkSettings.nfc.id -> SystemSettingsScreen.Nfc
            else -> null
        }
        definition.id to CapabilityPolicy(probe, setOf(ExecutionIdentity.Shell, ExecutionIdentity.Root), manual = manual)
    } + com.napreth.set4u.core.network.WirelessCapabilities.policies)
}
