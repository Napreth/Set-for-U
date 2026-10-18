package com.napreth.set4u.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.napreth.set4u.core.bridge.IAdbBridge
import com.napreth.set4u.testing.BridgeContract
import java.io.File
import org.junit.runner.RunWith
import org.junit.Test
import org.junit.Assert.*
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.IBinder
import com.napreth.set4u.core.bridge.*
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ShizukuBridgeAvdTest : BridgeContract() {
    override val targetPackage: String get() = InstrumentationRegistry.getArguments().getString("targetPackage") ?: super.targetPackage
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    override val localDirectory: File get() = context.cacheDir
    override suspend fun newBridge(): IAdbBridge = ShizukuBridge(context).also { it.connect(requestPermission = InstrumentationRegistry.getArguments().getString("requestShizukuPermission") == "true") }

    @Test fun lateCallbacksFromAnOldBindingCannotDisconnectTheReplacement() = runBlocking {
        val bridge = newBridge() as ShizukuBridge
        try {
            val field = ShizukuBridge::class.java.getDeclaredField("binding").apply { isAccessible = true }
            val old = checkNotNull(field.get(bridge))
            val connection = old.javaClass.getDeclaredField("connection").apply { isAccessible = true }.get(old) as ServiceConnection
            val death = old.javaClass.getDeclaredField("death").apply { isAccessible = true }.get(old) as IBinder.DeathRecipient
            val name = ComponentName(context, BridgeUserService::class.java)
            connection.onServiceDisconnected(name)
            bridge.connect()
            assertNotSame(old, field.get(bridge))
            connection.onServiceDisconnected(name)
            connection.onBindingDied(name)
            death.binderDied()
            assertEquals(BridgeState.Ready, bridge.connectionState.value)
            assertEquals("2000", bridge.execute(ShellRequest(ShellCommand.arguments("id", "-u"))).requireSuccess().stdout.trim())
        } finally { bridge.close() }
    }
}
