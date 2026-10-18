package com.napreth.set4u.desktop.bridge

import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.testing.BridgeContract
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import org.junit.Assume.assumeTrue

class RootBridgeAvdTest : BridgeContract() {
    override val expectedUid get() = 0
    override val localDirectory: File get() = File(System.getProperty("java.io.tmpdir"))
    override val targetPackage: String get() = System.getenv("SET4U_TEST_TARGET_PACKAGE") ?: super.targetPackage
    override suspend fun newBridge(): IAdbBridge {
        val serial = System.getenv("SET4U_TEST_ADB_SERIAL").orEmpty()
        assumeTrue("Requires an explicitly selected AVD", serial.startsWith("emulator-"))
        assumeTrue("Root tests are opt-in on a prepared userdebug AVD", System.getenv("SET4U_TEST_ROOT") == "1")
        val adb = AdbBridge(serial)
        try {
            adb.connect()
            return RootBridge(adb) { source, target -> Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING); Unit }.also { it.connect() }
        } catch (exception: Throwable) { adb.close(); throw exception }
    }
}
