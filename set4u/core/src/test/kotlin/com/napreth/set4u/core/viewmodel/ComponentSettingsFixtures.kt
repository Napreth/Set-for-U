package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.Disabled
import com.napreth.set4u.core.testPage
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.session.AppTarget
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.localization.UiText
import com.napreth.set4u.core.entryOf
import com.napreth.set4u.core.state.*

internal class MemorySetting(var value: SettingValue?, var access: SettingAccess = SettingAccess.Writable) : SettingHandler {
    var reads = 0
    var writes = 0
    var failRead = false
    var onApply: suspend () -> Unit = {}
    override suspend fun inspect(context: SettingContext): SettingSnapshot {
        reads++
        if (failRead) error("Unable to read device value")
        return SettingSnapshot(value, access)
    }
    override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
        writes++
        onApply()
        value = desiredValue
    }
}

internal class ComponentSettingsFixture {
    val notifications = MemorySetting(Disabled)
    val background = MemorySetting(SettingValue.ChoiceValue("optimized"))
    val channels = MemorySetting(SettingValue.MultiChoiceValue(setOf("messages")))
    val cache = MemorySetting(SettingValue.TextValue("1.00 GB"), SettingAccess.ReadOnly)
    val input = MemorySetting(SettingValue.IntegerValue(5))
    var clearCalls = 0
    val notificationDefinition = SettingDefinition("application.notifications", ValueType.Boolean, notifications)
    val backgroundDefinition = SettingDefinition("application.background", ValueType.Choice, background, choices = listOf("allowed", "optimized", "restricted"))
    val channelDefinition = SettingDefinition("application.channels", ValueType.MultiChoice, channels, choices = listOf("messages", "updates", "promotions"))
    val inputDefinition = SettingDefinition("application.timeout", ValueType.Integer, input, minimum = 1, maximum = 60)
    val clearDefinition = SettingDefinition("application.clearCache", ValueType.Action, object : SettingHandler {
        override suspend fun inspect(context: SettingContext) = cache.inspect(context).copy(access = SettingAccess.Writable)
        override suspend fun apply(context: SettingContext, desiredValue: SettingValue) { clearCalls++; cache.value = SettingValue.TextValue("0 B") }
        override fun verify(before: SettingSnapshot, after: SettingSnapshot, desiredValue: SettingValue) = if (after.value == SettingValue.TextValue("0 B")) Verification.Satisfied else Verification.NotSatisfied(FailureReason("Cache was not cleared"))
    })
    val notificationEntry = SettingEntry(notificationDefinition, UiText.Literal("通知"))
    val backgroundEntry = SettingEntry(backgroundDefinition, UiText.Literal("耗电行为控制"), choices = listOf(
        EntryChoice("allowed", UiText.Literal("完全允许后台行为"), UiText.Literal("不限制应用的任何后台行为")),
        EntryChoice("optimized", UiText.Literal("智能优化后台运行（推荐）")), EntryChoice("restricted", UiText.Literal("限制后台运行")),
    ))
    val channelEntry = SettingEntry(channelDefinition, UiText.Literal("通知类别"), choices = channelDefinition.choices.map { EntryChoice(it, UiText.Literal(it)) })
    val inputEntry = entryOf(inputDefinition)
    val clearEntry = entryOf(clearDefinition)
    val catalog = SettingsCatalog(
        testPage("application", "Do I Have", listOf(notificationEntry, backgroundEntry, channelEntry, inputEntry, clearEntry)) { p -> listOf(
            RowContainer("package.card", listOf(SettingRow("package", "应用", RowControl.Value, detail = "com.napreth.doihave"))),
            RowContainer("permissions", p.rows(notificationEntry, instance = "notifications") + listOf(
                p.navigation("background", UiText.Literal("后台运行"), SettingsDestination("background"), backgroundEntry),
                p.navigation("channels", UiText.Literal("通知类别"), SettingsDestination("channels")),
                p.navigation("otherUser", UiText.Literal("工作用户"), SettingsDestination("background", AppTarget(10, "com.napreth.doihave"))),
            )),
            RowContainer("storage", p.rows(clearEntry, EntryStyle.Information, "cache") + p.rows(clearEntry, instance = "clearCache")),
            RowContainer("timeout", p.rows(inputEntry, instance = "timeout")),
        ) },
        testPage("background", "后台运行", listOf(backgroundEntry)),
        testPage("channels", "通知类别", listOf(channelEntry)),
        testPage("notificationDetails", "通知详情", listOf(notificationEntry)) { p -> listOf(RowContainer("details", p.rows(notificationEntry, EntryStyle.Information, "notificationInfo"))) },
    )
}
