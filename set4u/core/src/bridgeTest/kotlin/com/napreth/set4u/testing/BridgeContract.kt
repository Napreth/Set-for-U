package com.napreth.set4u.testing

import com.napreth.set4u.core.Core
import com.napreth.set4u.core.compatibility.*
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.session.AppTarget
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.state.SettingSnapshot
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.localization.UiText
import com.napreth.set4u.core.viewmodel.*
import com.napreth.set4u.core.device.*
import com.napreth.set4u.core.device.tasks.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue
import com.napreth.set4u.core.settings.network.*
import com.napreth.set4u.core.session.DeviceTarget
import com.napreth.set4u.core.state.SettingAccess

/** The same contract runs on Desktop/ADB and inside Android/Shizuku, against an explicit AVD. */
abstract class BridgeContract {
    protected abstract suspend fun newBridge(): IAdbBridge
    protected abstract val localDirectory: File
    protected open val targetPackage: String get() = "com.napreth.doihave"
    protected open val expectedUid: Int get() = 2000
    private lateinit var bridge: IAdbBridge
    private val token = UUID.randomUUID().toString()
    private val remotePath get() = "/data/local/tmp/set4u test '$token 中文"

    @Before fun setUp() = runBlocking {
        bridge = newBridge()
        assertEquals("Tests must target an emulator", "1", command("getprop", "ro.kernel.qemu").stdout.trim())
        assertEquals(BridgeState.Ready, bridge.connectionState.value)
    }
    @After fun tearDown() = runBlocking {
        if (::bridge.isInitialized) bridge.close()
    }
    private suspend fun command(vararg args: String) = bridge.execute(ShellRequest(ShellCommand.arguments(*args))).requireSuccess()
    private suspend fun script(source: String, timeout: Long = 5_000) = bridge.execute(ShellRequest(ShellCommand.Script(source), timeout))

    @Test fun shellIdentityAndArgumentEscaping() = runBlocking {
        assertEquals(expectedUid.toString(), command("id", "-u").stdout.trim())
        val text = "空 格 ' \" \$HOME ; echo unsafe\nsecond line"
        assertEquals(text, command("printf", "%s", text).stdout)
    }
    @Test fun separatesOutputAndExitCode() = runBlocking {
        val result = script("printf output; printf error >&2; exit 7")
        assertEquals(ShellResult(7, "output", "error"), result)
    }
    @Test fun drainsBothPipesWithoutDeadlock() = runBlocking {
        val result = script("dd if=/dev/zero bs=65536 count=8 2>/dev/null; (dd if=/dev/zero bs=65536 count=8 2>/dev/null) >&2", 15_000)
        assertEquals(0, result.exitCode)
        assertEquals(524288, result.stdout.length)
        assertEquals(524288, result.stderr.length)
    }
    @Test fun oversizedTextOutputIsMarkedAndStillDrained() = runBlocking {
        val result = bridge.execute(ShellRequest(ShellCommand.Script(
            "dd if=/dev/zero bs=65536 count=32 2>/dev/null; (dd if=/dev/zero bs=65536 count=32 2>/dev/null) >&2"
        ), 15_000, maxOutputBytes = 65536))
        assertEquals(0, result.exitCode)
        assertEquals(65536, result.stdout.length + result.stderr.length)
        assertTrue(result.stdoutTruncated && result.stderrTruncated)
        assertEquals(expectedUid.toString(), command("id", "-u").stdout.trim())
    }

    @Test fun generatedDexExecutesWithShellIdentityAndExplicitTarget() = runBlocking {
        val spec = RuntimeProbeSpec().descriptor
        val target = AppTarget(0, targetPackage)
        var observed: RuntimeProbeResult? = null
        val handler = object : SettingHandler {
            override suspend fun inspect(context: SettingContext): SettingSnapshot {
                observed = context.executeTask(spec, RuntimeProbeRequest("中文 binary protocol"))
                return SettingSnapshot(SettingValue.TextValue(checkNotNull(observed).text))
            }
            override suspend fun apply(context: SettingContext, desiredValue: SettingValue) = Unit
        }
        val definition = SettingDefinition("test.device.probe", ValueType.Text, { handler })
        var deployments = 0
        var processes = 0
        var artifact = ""
        val borrowed = object : IAdbBridge by bridge {
            override suspend fun transfer(request: TransferRequest) { deployments++; artifact = request.destination; bridge.transfer(request) }
            override suspend fun openProcess(request: ShellRequest): RemoteProcess { processes++; return bridge.openProcess(request) }
            override suspend fun close() {}
        }
        val session = Core.open(borrowed, SettingsCatalog(emptyMap(), entries = listOf(SettingEntry(definition, UiText.Literal(definition.id)))))
        try {
            val result = session.settings.read(target, definition)
            assertEquals(result.toString(), SettingValue.TextValue("中文 binary protocol"), result.snapshot.value)
            assertEquals(command("getprop", "ro.build.version.sdk").stdout.trim().toInt(), observed!!.sdk)
            assertEquals(expectedUid, observed!!.uid)
            assertEquals(target.toString(), observed!!.target)
            repeat(2) { session.settings.read(target, definition).also { assertEquals(result.snapshot, it.snapshot) } }
            assertEquals(1, deployments)
            assertEquals(3, processes)
            assertTrue(artifact.contains("/set4u-cache-") && artifact.endsWith(".jar"))
            assertTrue(command("find", "/data/local/tmp", "-maxdepth", "1", "-name", "set4u-task-*").stdout.isBlank())
        } finally { session.close() }
        assertEquals(0, script("test ! -e ${ShellCommand.arguments(artifact).render()}").exitCode)
    }

    @Test fun networkSettingsReadModifyVerifyAndRestoreOnTheDevice() = runBlocking {
        assumeTrue(command("getprop", "ro.build.version.sdk").stdout.trim().toInt() >= 30)
        val wifiBefore = command("cmd", "wifi", "status").stdout.lineSequence().any { it.trim() == "Wifi is enabled" }
        val airplaneBefore = command("cmd", "connectivity", "airplane-mode").stdout.trim() == "enabled"
        val dnsBefore = command("settings", "get", "global", "private_dns_mode").stdout.trim()
        val hostnameBefore = command("settings", "get", "global", "private_dns_specifier").stdout.trim()
        val borrowed = object : IAdbBridge by bridge { override suspend fun close() {} }
        val session = Core.open(borrowed, ApplicationSettings.catalog(), compatibility = NetworkCompatibility.registry)
        val target = DeviceTarget()
        suspend fun set(definition: SettingDefinition, value: SettingValue) {
            val result = session.settings.set(target, definition, value).await()
            assertTrue(result.toString(), result.outcome == SettingOutcome.Verified || result.outcome == SettingOutcome.AlreadySatisfied)
            assertEquals(value, (result.after ?: result.before)?.snapshot?.value)
        }
        try {
            val model = session.viewModels.settings(target, "network")
            model.enter().join()
            val loaded = model.state.first { !it.isLoading && it.items.any { row -> row.id == "open.wifi" } }
            assertNotNull(loaded.find("open.dns")); assertNotNull(loaded.find(NetworkSettings.airplane.id))
            model.close()
            set(NetworkSettings.wifi, SettingValue.BooleanValue(!wifiBefore))
            set(NetworkSettings.wifi, SettingValue.BooleanValue(wifiBefore))
            set(NetworkSettings.airplane, SettingValue.BooleanValue(!airplaneBefore))
            set(NetworkSettings.airplane, SettingValue.BooleanValue(airplaneBefore))
            set(NetworkSettings.dnsMode, SettingValue.ChoiceValue("off"))
            set(NetworkSettings.dnsHostname, SettingValue.TextValue("dns.google"))
            set(NetworkSettings.dnsMode, SettingValue.ChoiceValue("hostname"))
            assertFalse(session.navigation.open(SystemSettingsScreen.Wifi))
        } finally {
            withContext(NonCancellable) {
                try {
                    command("cmd", "connectivity", "airplane-mode", if (airplaneBefore) "enable" else "disable")
                    command("svc", "wifi", if (wifiBefore) "enable" else "disable")
                    for ((key, value) in listOf("private_dns_mode" to dnsBefore, "private_dns_specifier" to hostnameBefore)) {
                        if (value == "null") command("settings", "delete", "global", key)
                        else command("settings", "put", "global", key, value)
                    }
                } finally { session.close() }
            }
        }
        assertEquals(dnsBefore, command("settings", "get", "global", "private_dns_mode").stdout.trim())
        assertEquals(hostnameBefore, command("settings", "get", "global", "private_dns_specifier").stdout.trim())
        assertEquals(airplaneBefore, command("cmd", "connectivity", "airplane-mode").stdout.trim() == "enabled")
    }

    @Test fun radioSettingsRespectHardwareAndVerifyAvailableChanges() = runBlocking {
        assumeTrue(command("getprop", "ro.build.version.sdk").stdout.trim().toInt() >= 35)
        var nfcCalls = 0
        val borrowed = object : IAdbBridge by bridge {
            override suspend fun execute(request: ShellRequest): ShellResult {
                if ((request.command as? ShellCommand.Arguments)?.values?.take(2) == listOf("cmd", "nfc")) nfcCalls++
                return bridge.execute(request)
            }
            override suspend fun close() {}
        }
        val session = Core.open(borrowed, ApplicationSettings.catalog(), compatibility = NetworkCompatibility.registry)
        val target = DeviceTarget()
        var bluetoothBefore: SettingValue? = null
        var mobileBefore: SettingValue? = null
        suspend fun change(model: SettingsViewModel, definition: SettingDefinition, desired: SettingValue) {
            val result = checkNotNull(model.setValue(definition.id, desired)) { model.state.value.toString() }.await()
            assertEquals(result.toString(), SettingOutcome.Verified, result.outcome)
            assertEquals(desired, result.after?.snapshot?.value)
        }
        try {
            val network = session.viewModels.settings(target, "network")
            network.enter().join()
            val loaded = withTimeout(5_000) { network.state.first { !it.isLoading && it.items.any { row -> row.id == NetworkSettings.bluetooth.id && row.status.observation != null } } }
            val bluetooth = loaded.items.single { it.id == NetworkSettings.bluetooth.id }
            assertEquals(bluetooth.toString(), SettingAccess.Writable, bluetooth.status.observation?.snapshot?.access)
            bluetoothBefore = checkNotNull(bluetooth.value as? SettingValue.BooleanValue)
            assertTrue(session.compatibility.resolve(NetworkSettings.bluetooth) is CompatibilityResolution.Supported)
            val profile = session.compatibility.deviceProfile()
            val hasNfc = DeviceCondition.HasFeature("android.hardware.nfc").match(profile)
            assertEquals(MatchState.NoMatch, hasNfc.state) // This AVD has no NFC controller.
            assertTrue(session.compatibility.resolve(NetworkSettings.nfc) is CompatibilityResolution.Unsupported)
            withTimeout(5_000) { network.state.first { !it.isLoading && it.find(NetworkSettings.nfc.id) == null } }
            assertEquals(SettingOutcome.Failed, session.settings.set(target, NetworkSettings.nfc, SettingValue.BooleanValue(true)).await().outcome)
            assertEquals(0, nfcCalls)
            change(network, NetworkSettings.bluetooth, SettingValue.BooleanValue(!(bluetoothBefore as SettingValue.BooleanValue).value))
            change(network, NetworkSettings.bluetooth, bluetoothBefore!!)
            network.close()

            val mobile = session.viewModels.settings(target, "network.mobile")
            mobile.enter().join()
            val mobileUi = withTimeout(5_000) { mobile.state.first { !it.isLoading && it.items.any { row -> row.id == NetworkSettings.mobileData.id && row.status.observation != null } } }
                .items.single { it.id == NetworkSettings.mobileData.id }
            if (mobileUi.status.observation?.snapshot?.access == SettingAccess.ReadOnly && mobileUi.value == null) {
                val blocked = session.settings.set(target, NetworkSettings.mobileData, SettingValue.BooleanValue(true)).await()
                assertEquals(SettingOutcome.Failed, blocked.outcome)
                assertNull(blocked.before?.snapshot?.value)
                assertNull(blocked.after)
                mobile.close()
                println("Mobile data: no active default SIM; verified read-only guard, no write round trip")
                return@runBlocking
            }
            assertEquals(mobileUi.toString(), SettingAccess.Writable, mobileUi.status.observation?.snapshot?.access)
            mobileBefore = checkNotNull(mobileUi.value as? SettingValue.BooleanValue)
            change(mobile, NetworkSettings.mobileData, SettingValue.BooleanValue(!(mobileBefore as SettingValue.BooleanValue).value))
            change(mobile, NetworkSettings.mobileData, mobileBefore!!)
            mobile.close()
        } finally {
            withContext(NonCancellable) {
                try {
                    for ((definition, value) in listOf(NetworkSettings.mobileData to mobileBefore, NetworkSettings.bluetooth to bluetoothBefore)) {
                        if (value != null) {
                            val restored = session.settings.set(target, definition, value).await()
                            assertTrue(restored.toString(), restored.outcome == SettingOutcome.Verified || restored.outcome == SettingOutcome.AlreadySatisfied)
                            assertEquals(value, session.settings.read(target, definition).snapshot.value)
                        }
                    }
                } finally { session.close() }
            }
        }
    }

    @Test fun deviceProtocolMismatchAndDeadlineLeaveTheBridgeUsable() = runBlocking {
        val spec = RuntimeProbeSpec().descriptor
        var deadline = false
        val handler = object : SettingHandler {
            override suspend fun inspect(context: SettingContext): SettingSnapshot {
                val descriptor = if (deadline) spec else DeviceTaskDescriptor(spec.id, spec.requestCodec, spec.responseCodec, 999)
                context.executeTask(descriptor, RuntimeProbeRequest("deadline", if (deadline) 30_000 else 0),
                    timeoutMillis = if (deadline) 3_000 else 15_000)
                return SettingSnapshot(SettingValue.TextValue("unexpected"))
            }
            override suspend fun apply(context: SettingContext, desiredValue: SettingValue) = Unit
        }
        val definition = SettingDefinition("test.device.protocol", ValueType.Text, { handler })
        val session = Core.open(bridge, SettingsCatalog(emptyMap(), entries = listOf(SettingEntry(definition, UiText.Literal(definition.id)))))
        try {
            val mismatch = session.settings.read(AppTarget(0, targetPackage), definition)
            assertTrue(mismatch.toString(), mismatch.snapshot.message.orEmpty().contains("Unsupported task protocol"))
            deadline = true
            val began = System.nanoTime()
            val timedOut = session.settings.read(AppTarget(0, targetPackage), definition)
            assertTrue(timedOut.toString(), timedOut.snapshot.message.orEmpty().contains("timed out"))
            assertTrue((System.nanoTime() - began) / 1_000_000 < 10_000)
            assertEquals(expectedUid.toString(), command("id", "-u").stdout.trim())
            assertTrue(command("find", "/data/local/tmp", "-maxdepth", "1", "-name", "set4u-task-*").stdout.isBlank())
        } finally { session.close() }
    }
    @Test fun streamsInputAndWaitsForExit() = runBlocking {
        val process = bridge.openProcess(ShellRequest(ShellCommand.Script("cat; printf error >&2; exit 9")))
        try {
            val payload = ByteArray(262144) { it.toByte() }
            val out = async(Dispatchers.IO) { process.stdout.readBytes() }
            val err = async(Dispatchers.IO) { process.stderr.readBytes().toString(Charsets.UTF_8) }
            withContext(Dispatchers.IO) { process.stdin.use { it.write(payload) } }
            assertEquals(9, process.awaitExit())
            assertArrayEquals(payload, out.await())
            assertEquals("error", err.await())
        } finally { process.close() }
    }
    @Test fun transfersBinaryFilesWithSpacesAndUnicode() = runBlocking {
        val source = File(localDirectory, "set4u $token ' 中文.bin")
        val destination = File(localDirectory, "set4u $token result.bin")
        val bytes = ByteArray(262144) { (it % 251).toByte() }
        try {
            source.writeBytes(bytes)
            bridge.transfer(TransferRequest(source.path, remotePath))
            bridge.transfer(TransferRequest(remotePath, destination.path, TransferDirection.FromDevice))
            assertArrayEquals(bytes, destination.readBytes())
        } finally {
            command("rm", "-f", remotePath)
            source.delete()
            destination.delete()
        }
    }
    @Test fun earlyCloseDoesNotLeaveProcessMarkers() = runBlocking {
        suspend fun markerCount() = command("find", "/data/local/tmp", "-maxdepth", "1", "-name", "set4u-*.pid").stdout.lines().count { it.isNotBlank() }
        val before = markerCount()
        repeat(5) {
            bridge.openProcess(ShellRequest(ShellCommand.arguments("sleep", "90"), 120_000)).close()
        }
        assertEquals(before, markerCount())
    }
    @Test fun openProcessHasItsOwnDeadline() = runBlocking {
        val process = bridge.openProcess(ShellRequest(ShellCommand.arguments("sleep", "90"), 500))
        try {
            delay(1_000)
            try { process.awaitExit(); fail("Expected process timeout") }
            catch (_: ShellTimeoutException) { }
            assertEquals(BridgeState.Ready, bridge.connectionState.value)
        } finally { process.close() }
    }
    @Test fun timeoutStopsRemoteChildren() = runBlocking { checkStop(cancel = false) }
    @Test fun cancellationStopsRemoteChildren() = runBlocking { checkStop(cancel = true) }

    private suspend fun checkStop(cancel: Boolean) = supervisorScope {
        val pidPath = "$remotePath.child"
        val quoted = ShellCommand.arguments(pidPath).render()
        val work = async {
            script("sleep 90 & echo \$! > $quoted; wait", if (cancel) 15_000 else 2_000)
        }
        try {
            val pid = withTimeout(8_000) {
                var value = ""
                while (value.isBlank()) {
                    delay(100)
                    value = script("cat $quoted 2>/dev/null").stdout.trim()
                }
                value.also { require(it.all(Char::isDigit)) }
            }
            if (cancel) {
                work.cancelAndJoin()
            } else {
                try { work.await(); fail("Expected a command timeout") }
                catch (_: ShellTimeoutException) { }
            }
            val state = script("if [ -r /proc/$pid/stat ]; then cut -d ' ' -f 3 /proc/$pid/stat; fi").stdout.trim()
            assertTrue("Child $pid is still running ($state)", state.isEmpty() || state == "Z")
            assertEquals(expectedUid.toString(), command("id", "-u").stdout.trim())
        } finally {
            work.cancelAndJoin()
            command("rm", "-f", pidPath)
        }
    }
    @Test fun closePreservesSharedServiceAndOtherClients() = runBlocking {
        val other = newBridge()
        try {
            val process = bridge.openProcess(ShellRequest(ShellCommand.arguments("sleep", "90"), 120_000))
            bridge.close()
            bridge.close()
            assertEquals(BridgeState.Closed, bridge.connectionState.value)
            assertEquals(expectedUid.toString(), other.execute(ShellRequest(ShellCommand.arguments("id", "-u"))).requireSuccess().stdout.trim())
            process.close()
            try { command("id"); fail("Closed bridge accepted a command") }
            catch (_: BridgeUnavailableException) { }
        } finally { other.close() }
    }
    @Test fun componentsChangeAndVerifyDoIHaveNotificationPermission() = runBlocking {
        val packageName = targetPackage
        val sdk = command("getprop", "ro.build.version.sdk").stdout.trim().toInt()
        val permission = if (sdk >= 33) "android.permission.POST_NOTIFICATIONS" else "android.permission.CAMERA"
        val pattern = Regex("${Regex.escape(permission)}: granted=(true|false)")
        fun parse(value: String) = pattern.find(value)?.groupValues?.get(1)?.toBooleanStrict()
            ?: error("Target runtime permission was not found")
        val before = parse(command("dumpsys", "package", packageName).stdout)
        var legacyFunctions = 0
        var modernFunctions = 0
        fun notificationOperation(): SettingHandler = object : SettingHandler {
            override suspend fun inspect(context: SettingContext): SettingSnapshot {
                val result = context.execute(ShellCommand.arguments("dumpsys", "package", packageName)).requireSuccess()
                return SettingSnapshot(SettingValue.BooleanValue(parse(result.stdout)))
            }
            override suspend fun apply(context: SettingContext, desiredValue: SettingValue) {
                val enabled = (desiredValue as SettingValue.BooleanValue).value
                context.execute(ShellRequest(ShellCommand.arguments("pm", if (enabled) "grant" else "revoke", "--user", "0", packageName, permission), evidenceLabel = "pm")).requireSuccess()
            }
        }
        fun android33(): SettingHandler { legacyFunctions++; return notificationOperation() }
        fun android35(): SettingHandler { modernFunctions++; return notificationOperation() }
        val definition = SettingDefinition("test.notifications", ValueType.Boolean,
            operationFunctions = mapOf("android33" to ::android33, "android35" to ::android35))
        val compatibility = CompatibilityRegistry(listOf(
            CompatibilityRule("notifications.api33", definition.id, DeviceCondition.IntegerRange(DeviceFields.Sdk, minimum = 23), RuleDecision.Use("android33")),
            CompatibilityRule("notifications.api35", definition.id, DeviceCondition.IntegerRange(DeviceFields.Sdk, minimum = 35), RuleDecision.Use("android35"), setOf("notifications.api33")),
        ))
        val permissionEntry = SettingEntry(definition, UiText.Literal("通知"))
        class PermissionsPage : SettingsPage("test.permissions", "Do I Have") {
            override val entries = listOf(permissionEntry)
            override fun layout(rows: PageRows) = listOf(RowContainer("permissions",
                rows.rows(permissionEntry, instance = "permission.toggle") + rows.rows(permissionEntry, EntryStyle.Information, "permission.info")))
        }
        val page = PermissionsPage()
        val session = Core.open(bridge, SettingsCatalog(page), compatibility = compatibility)
        val vm = session.viewModels.settings(AppTarget(0, packageName), page.id)
        try {
            vm.enter().join()
            val profile = session.compatibility.deviceProfile()
            assertTrue(checkNotNull(profile.sdkInt) >= 23)
            assertEquals("1", profile.properties["ro.kernel.qemu"])
            assertFalse(profile.value(DeviceFields.KernelRelease).isNullOrBlank())
            val selected = session.compatibility.resolve(definition) as CompatibilityResolution.Supported
            assertEquals(if (profile.sdkInt!! >= 35) "android35" else "android33", selected.operationId)
            val desired = SettingValue.BooleanValue(!before)
            val changed = vm.setValue("permission.toggle", desired)!!.await()
            assertEquals(changed.toString(), SettingOutcome.Verified, changed.outcome)
            assertEquals(!before, parse(command("dumpsys", "package", packageName).stdout))
            val observed = withTimeout(5_000) {
                vm.state.first { (it.find("permission.info") as? SettingUiItem)?.value == desired }
            }
            assertEquals(desired, (observed.find("permission.toggle") as SettingUiItem).value)
            assertFalse((observed.find("permission.info") as SettingUiItem).isInteractive)
            assertNull(vm.setValue("permission.info", SettingValue.BooleanValue(before)))
            val unchanged = vm.setValue("permission.toggle", desired)!!.await()
            assertEquals(SettingOutcome.AlreadySatisfied, unchanged.outcome)
            assertEquals(0, unchanged.evidence.count { (it.request.command as? ShellCommand.Arguments)?.values?.firstOrNull() == "pm" })
            assertEquals(0, if (profile.sdkInt!! >= 35) legacyFunctions else modernFunctions)
            assertTrue((if (profile.sdkInt!! >= 35) modernFunctions else legacyFunctions) >= 3)
        } finally {
            try {
                command("pm", if (before) "grant" else "revoke", "--user", "0", packageName, permission)
                assertEquals("Permission baseline was not restored", before, parse(command("dumpsys", "package", packageName).stdout))
            } finally { vm.close(); session.close() }
        }
    }
}
