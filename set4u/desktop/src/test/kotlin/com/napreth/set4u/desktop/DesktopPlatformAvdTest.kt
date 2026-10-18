package com.napreth.set4u.desktop

import com.napreth.set4u.core.bridge.BridgeState
import com.napreth.set4u.ui.WorkMode
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopPlatformAvdTest {
    @Test fun desktopRootModeConnectsAdbBeforeCheckingRootIdentity() = runBlocking {
        val serial = System.getenv("SET4U_TEST_ADB_SERIAL").orEmpty()
        assumeTrue("Explicit root AVD required", serial.startsWith("emulator-") && System.getenv("SET4U_TEST_ROOT") == "1")
        assumeTrue("Desktop window host requires a display", !java.awt.GraphicsEnvironment.isHeadless())
        lateinit var window: MainWindow
        SwingUtilities.invokeAndWait { window = MainWindow() }
        val platform = window.controller.platform
        val root = platform.createBridge(WorkMode.Root, serial)
        try {
            platform.connect(root, true)
            assertEquals(BridgeState.Ready, root.connectionState.value)
            assertEquals(0, root.environment().uid)
        } finally {
            root.close()
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
