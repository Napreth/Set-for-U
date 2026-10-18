package com.napreth.set4u.desktop.bridge

import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.testing.BridgeContract
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RootShellBridgeAvdTest : BridgeContract() {
    private lateinit var connected: IAdbBridge
    override val expectedUid get() = 2000
    override val localDirectory: File get() = File(System.getProperty("java.io.tmpdir"))
    override val targetPackage: String get() = System.getenv("SET4U_TEST_TARGET_PACKAGE") ?: super.targetPackage
    override suspend fun newBridge(): IAdbBridge {
        val serial = System.getenv("SET4U_TEST_ADB_SERIAL").orEmpty()
        assumeTrue("Requires an explicit root AVD", serial.startsWith("emulator-") && System.getenv("SET4U_TEST_ROOT") == "1")
        val adb = AdbBridge(serial)
        try {
            adb.connect()
            return RootBridge(adb, emulateShell = true) { source, target -> Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING); Unit }.also { it.connect(); connected = it }
        } catch (exception: Throwable) { adb.close(); throw exception }
    }

    @Test fun environmentReportsTheActualShellIdentity() = runBlocking {
        assertEquals(ExecutionIdentity.Shell, connected.environment().identity)
        assertEquals(BridgeTransport.Adb, connected.environment().transport)
        assertEquals("2000", connected.execute(ShellRequest(ShellCommand.arguments("id", "-g"))).requireSuccess().stdout.trim())
    }
}
