package com.napreth.set4u.core.device

import com.napreth.set4u.core.FakeBridge
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.expectFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeviceBundleCacheTest {
    private class Bridge : IAdbBridge by FakeBridge() {
        val commands = mutableListOf<ShellRequest>()
        var transfers = 0
        var fail = false
        override suspend fun execute(request: ShellRequest): ShellResult { commands += request; return ShellResult(0) }
        override suspend fun transfer(request: TransferRequest) {
            transfers++
            check(!fail) { "Upload interrupted" }
            assertTrue(request.atomic)
            assertTrue(java.io.File(request.source).length() > 0)
        }
    }

    @Test fun concurrentCallsDeployOnceAndReconnectRevalidates() = runTest {
        val bridge = Bridge()
        val cache = DeviceBundleCache(bridge)
        val paths = (1..20).map { async { cache.artifact() } }.awaitAll()
        assertEquals(1, paths.distinct().size)
        assertEquals(1, bridge.transfers)
        assertTrue(paths.first().matches(Regex("/data/local/tmp/set4u-cache-[A-Za-z0-9-]+/[0-9]+\\.[0-9]+\\.[0-9]+(?:-debug)?-[a-f0-9]{64}\\.jar")))
        cache.invalidate()
        assertEquals(paths.first(), cache.artifact())
        assertEquals(2, bridge.transfers)
        cache.close()
        assertTrue(bridge.commands.last().command.render().contains("rm"))
        expectFailure<IllegalStateException> { cache.artifact() }
    }

    @Test fun failedDeploymentDoesNotPoisonTheCacheAndSessionsDoNotSharePaths() = runTest {
        val bridge = Bridge().apply { fail = true }
        val first = DeviceBundleCache(bridge)
        expectFailure<IllegalStateException> { first.artifact() }
        bridge.fail = false
        val path = first.artifact()
        val second = DeviceBundleCache(bridge)
        assertNotEquals(path, second.artifact())
        assertEquals(3, bridge.transfers)
        first.close(); second.close()
    }
}
