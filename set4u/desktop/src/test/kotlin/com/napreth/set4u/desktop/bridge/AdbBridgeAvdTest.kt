package com.napreth.set4u.desktop.bridge

import com.napreth.set4u.core.bridge.IAdbBridge
import com.napreth.set4u.testing.BridgeContract
import java.io.File
import org.junit.Assume.assumeTrue

class AdbBridgeAvdTest : BridgeContract() {
    override val targetPackage: String get() = System.getenv("SET4U_TEST_TARGET_PACKAGE") ?: super.targetPackage
    override val localDirectory: File get() = File(System.getProperty("java.io.tmpdir"))
    override suspend fun newBridge(): IAdbBridge {
        val serial = System.getenv("SET4U_TEST_ADB_SERIAL").orEmpty()
        assumeTrue("Set SET4U_TEST_ADB_SERIAL to an emulator serial to run device tests", serial.startsWith("emulator-"))
        return AdbBridge(serial).also { it.connect() }
    }
}
