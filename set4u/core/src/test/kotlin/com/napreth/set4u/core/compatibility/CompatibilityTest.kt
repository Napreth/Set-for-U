@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.napreth.set4u.core.compatibility

import com.napreth.set4u.core.*
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.state.*
import com.napreth.set4u.core.viewmodel.*
import java.time.LocalDate
import kotlinx.coroutines.test.*
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class CompatibilityTest {
    @Test fun featureCollectionOnlyInfersAbsenceFromACompleteSuccessfulList() = runTest {
        var response = ShellResult(0, "feature:reqGlEsVersion=0x30000\nfeature:android.hardware.bluetooth\nfeature:android.hardware.hardware_keystore=300\n")
        val bridge = FakeBridge().apply { onExecute = { request ->
            val args = (request.command as ShellCommand.Arguments).values
            when (args) {
                listOf("getprop") -> ShellResult(0, "[ro.build.version.sdk]: [35]\n")
                listOf("pm", "list", "features") -> response
                else -> ShellResult(0, "kernel")
            }
        } }
        val actual = DeviceInfoCollector(bridge).collect()
        assertEquals(MatchState.Match, DeviceCondition.HasFeature("android.hardware.bluetooth").match(actual).state)
        assertEquals(MatchState.NoMatch, DeviceCondition.HasFeature("android.hardware.nfc").match(actual).state)
        assertEquals(MatchState.Match, DeviceCondition.HasFeature("android.hardware.hardware_keystore").match(actual).state)
        for (failure in listOf(ShellResult(1), ShellResult(0), ShellResult(0, "error: device is booting"),
            ShellResult(0, "feature:android.hardware.bluetooth\n", stdoutTruncated = true))) {
            response = failure
            val unavailable = DeviceInfoCollector(bridge).collect()
            assertEquals(FactState.Unavailable, unavailable.fact(DeviceFields.PackageFeatures).state)
            assertEquals(MatchState.Unknown, DeviceCondition.HasFeature("android.hardware.nfc").match(unavailable).state)
        }
    }

    private val profile = DeviceProfile(mapOf("ro.build.version.sdk" to "35", "ro.product.manufacturer" to "Example", "rom.version" to "5.10.2", "ro.build.version.security_patch" to "2025-06-05"))
    private fun rule(id: String, condition: DeviceCondition = DeviceCondition.Always, operation: String = "default", replaces: Set<String> = emptySet()) =
        CompatibilityRule(id, "sample.flag", condition, RuleDecision.Use(operation), replaces)

    @Test fun collectorReadsTheTargetAndPreservesUnknownAndUnavailableFacts() = runTest {
        val bridge = FakeBridge().apply { onExecute = { request ->
            when ((request.command as ShellCommand.Arguments).values.joinToString(" ")) {
                "getprop" -> ShellResult(0, "[ro.build.version.sdk]: [35]\n[ro.product.model]: [AVD]\n[vendor.sota]: [B12]\n[ro.soc.model]: [unknown]\n")
                "uname -r" -> ShellResult(0, "6.6.1-android\n")
                "uname -v" -> ShellResult(0, "#1 SMP")
                "uname -m" -> ShellResult(0, "x86_64")
                "getconf PAGESIZE" -> ShellResult(1, stderr = "not supported")
                else -> error("Unexpected request")
            }
        } }
        val actual = DeviceInfoCollector(bridge) { 123 }.collect()
        assertEquals(35, actual.sdkInt)
        assertEquals("B12", actual.value(DeviceFields.property("vendor.sota")))
        assertEquals("6.6.1-android", actual.value(DeviceFields.KernelRelease))
        assertEquals(FactState.Absent, actual.fact(DeviceFields.Brand).state)
        assertEquals(FactState.Unavailable, actual.fact(DeviceFields.PageSize).state)
        assertEquals(FactState.Unavailable, actual.fact(DeviceFields.property("ro.soc.model")).state)
        assertEquals(123L, actual.collectedAtMillis)
        assertTrue(bridge.writes.isEmpty())
    }

    @Test fun versionRangesUseNumericComponentsAndDatesAndKeepUnparseableValuesUnknown() {
        assertEquals(MatchState.Match, DeviceCondition.VersionRange(DeviceFields.property("rom.version"), "5.9", "5.11").match(profile).state)
        assertEquals(MatchState.NoMatch, DeviceCondition.VersionRange(DeviceFields.property("rom.version"), "6").match(profile).state)
        assertEquals(MatchState.Unknown, DeviceCondition.VersionRange(DeviceFields.Manufacturer, "1").match(profile).state)
        assertEquals(MatchState.Match, DeviceCondition.DateRange(DeviceFields.SecurityPatch, LocalDate.parse("2025-06-01")).match(profile).state)
        assertEquals(MatchState.NoMatch, DeviceCondition.IntegerRange(DeviceFields.Sdk, maximum = 34).match(profile).state)
        val vendorProfile = DeviceProfile(mapOf("rom.version" to "V5.10.2(BETA)"))
        val vendorParser = VersionParser { DottedVersionParser.parse(it.removePrefix("V").substringBefore('(')) }
        assertEquals(MatchState.Match, DeviceCondition.VersionRange(DeviceFields.property("rom.version"), "V5.9", "V5.11", vendorParser).match(vendorProfile).state)
        assertEquals(listOf(2147483648L), DottedVersionParser.parse("2147483648"))
    }

    @Test fun collectionDeadlinesBecomeReadErrorsRatherThanCancellingTheSettingOperation() = runTest {
        val bridge = FakeBridge().apply { onExecute = { request ->
            val command = (request.command as ShellCommand.Arguments).values.joinToString(" ")
            if (command == "getprop") ShellResult(0, "[ro.build.version.sdk]: [35]\n")
            else { delay(5_001); ShellResult(0, "late") }
        } }
        val actual = DeviceInfoCollector(bridge).collect()
        assertEquals(35, actual.sdkInt)
        assertEquals(FactState.Unavailable, actual.fact(DeviceFields.KernelRelease).state)
        bridge.onExecute = { delay(5_001); ShellResult(0) }
        expectFailure<ShellTimeoutException> { DeviceInfoCollector(bridge).collect() }
    }

    @Test fun booleanRulesPreserveThreeValuedLogic() {
        val unknown = DeviceCondition.Equals(DeviceFields.Brand, "missing")
        val yes = DeviceCondition.Equals(DeviceFields.Manufacturer, "example")
        val no = DeviceCondition.Equals(DeviceFields.Manufacturer, "other")
        assertEquals(MatchState.NoMatch, DeviceCondition.All(listOf(unknown, no)).match(profile).state)
        assertEquals(MatchState.Match, DeviceCondition.AnyOf(listOf(unknown, yes)).match(profile).state)
        assertEquals(MatchState.Unknown, DeviceCondition.Not(unknown).match(profile).state)
    }

    @Test fun explicitOverridesAreIndependentOfRegistrationOrder() {
        val generic = rule("generic")
        val vendor = rule("vendor", DeviceCondition.Equals(DeviceFields.Manufacturer, "Example"), "vendor", setOf("generic"))
        val latest = rule("latest", DeviceCondition.IntegerRange(DeviceFields.Sdk, 35), "latest", setOf("vendor"))
        for (rules in listOf(listOf(generic, vendor, latest), listOf(latest, vendor, generic))) {
            assertEquals(CompatibilityResolution.Supported("latest", "latest"), CompatibilityRegistry(rules).resolve("sample.flag", profile))
        }
    }

    @Test fun missingVendorInformationAndConflictingRulesNeverFallBackSilently() {
        val generic = rule("generic")
        val vendor = rule("vendor", DeviceCondition.Equals(DeviceFields.Brand, "Example"), "vendor", setOf("generic"))
        assertTrue(CompatibilityRegistry(listOf(generic, vendor)).resolve("sample.flag", profile) is CompatibilityResolution.Unknown)
        assertTrue(CompatibilityRegistry(listOf(generic, rule("other"))).resolve("sample.flag", profile) is CompatibilityResolution.Unknown)
        val denied = CompatibilityRule("removed", "sample.flag", DeviceCondition.Always, RuleDecision.Unsupported("Removed by ROM"), setOf("generic"))
        assertEquals(CompatibilityResolution.Unsupported("Removed by ROM", "removed"), CompatibilityRegistry(listOf(generic, denied)).resolve("sample.flag", profile))
    }

    @Test fun registryRejectsBrokenReplacementGraphs() = runTest {
        expectFailure<IllegalArgumentException> { CompatibilityRegistry(listOf(rule("a"), rule("a"))) }
        expectFailure<IllegalArgumentException> { CompatibilityRegistry(listOf(rule("a", replaces = setOf("missing")))) }
        expectFailure<IllegalArgumentException> { CompatibilityRegistry(listOf(rule("a", replaces = setOf("b")), rule("b", replaces = setOf("a")))) }
        expectFailure<IllegalArgumentException> { CompatibilityRegistry(listOf(rule("a", replaces = setOf("b")), CompatibilityRule("b", "other.setting", DeviceCondition.Always, RuleDecision.Use("default")))) }
    }

    @Test fun settingsSelectTheNamedFunctionAndPinItAcrossApplyAndVerification() = runTest {
        val calls = mutableListOf<String>()
        var value = Disabled
        var factories = 0
        fun selectedFunction(): SettingHandler {
            factories++
            return object : SettingHandler {
                override suspend fun inspect(context: SettingContext): SettingSnapshot { calls += "read"; return SettingSnapshot(value) }
                override suspend fun apply(context: SettingContext, desiredValue: SettingValue) { calls += "set"; value = desiredValue as SettingValue.BooleanValue }
                override fun verify(before: SettingSnapshot, after: SettingSnapshot, desiredValue: SettingValue): Verification { calls += "verify"; return super.verify(before, after, desiredValue) }
            }
        }
        val definition = SettingDefinition("sample.flag", ValueType.Boolean, operationFunctions = mapOf("api35" to ::selectedFunction, "old" to { error("Wrong function selected") }))
        val registry = CompatibilityRegistry(listOf(rule("api35", DeviceCondition.IntegerRange(DeviceFields.Sdk, 35), "api35")))
        withSession(catalog = catalogOf(definition), compatibility = registry, deviceProfileSource = DeviceProfileSource { profile }) { session ->
            val result = session.settings.set(Target, definition, Enabled).await()
            assertEquals(SettingOutcome.Verified, result.outcome)
            assertEquals(listOf("read", "set", "read", "verify"), calls)
            assertEquals(1, factories)
            assertEquals(CompatibilityResolution.Supported("api35", "api35"), session.compatibility.resolve(definition))
        }
    }

    @Test fun directWritesCannotBypassCompatibilityAndUnsupportedRowsAreHidden() = runTest {
        var invoked = 0
        val definition = SettingDefinition("sample.flag", ValueType.Boolean, operationFunctions = mapOf("native" to { invoked++; FlagHandler() }))
        val registry = CompatibilityRegistry(listOf(CompatibilityRule("absent", definition.id, DeviceCondition.Always, RuleDecision.Unsupported("Not in this ROM"))))
        val page = testPage("sample", "设置", listOf(entryOf(definition)))
        withSession(catalog = SettingsCatalog(page), compatibility = registry) { session ->
            val result = session.settings.set(Target, definition, Enabled).await()
            assertEquals(SettingOutcome.Failed, result.outcome)
            assertEquals("Not in this ROM", result.reason?.message)
            assertEquals(0, invoked)
            val vm = session.viewModels.settings(Target, page.id)
            vm.enter().join(); runCurrent()
            assertTrue(vm.state.value.items.isEmpty())
            assertNull(vm.setValue(definition.id, Enabled))
            vm.close()
        }
    }

    @Test fun unknownConditionsRemainVisibleAndDeviceProfilesRefreshExplicitly() = runTest {
        var collections = 0
        val registry = CompatibilityRegistry(listOf(rule("vendor", DeviceCondition.Equals(DeviceFields.Brand, "Example"))))
        val definition = flagDefinition()
        withSession(catalog = catalogOf(definition), compatibility = registry, deviceProfileSource = DeviceProfileSource { collections++; profile }) { session ->
            val vm = session.viewModels.settings(Target, "sample")
            vm.enter().join(); runCurrent()
            assertEquals(1, vm.state.value.items.size)
            assertFalse(vm.state.value.items.single().isEnabled)
            assertTrue(session.compatibility.resolve(definition) is CompatibilityResolution.Unknown)
            assertEquals(1, collections)
            vm.refresh().join()
            assertEquals(2, collections)
            vm.close()
        }
    }

    @Test fun missingOperationAndUnmarkedNamedSettingsDoNotExecuteAnything() = runTest {
        val definition = SettingDefinition("sample.flag", ValueType.Boolean, operationFunctions = mapOf("known" to { error("Should not run") }))
        withSession(catalog = catalogOf(definition), compatibility = CompatibilityRegistry(listOf(rule("missing", operation = "unknown")))) { session ->
            assertTrue(session.compatibility.resolve(definition) is CompatibilityResolution.Unknown)
            assertEquals(SettingOutcome.Failed, session.settings.set(Target, definition, Enabled).await().outcome)
        }
        withSession { session ->
            assertTrue(session.compatibility.resolve(definition) is CompatibilityResolution.Unsupported)
            assertTrue(session.compatibility.resolve(definition.copy(binding = FlagHandler())) is CompatibilityResolution.Unsupported)
        }
    }
}
