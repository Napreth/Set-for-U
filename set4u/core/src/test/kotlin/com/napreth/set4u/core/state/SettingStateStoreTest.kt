@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.napreth.set4u.core.state

import com.napreth.set4u.core.Target
import com.napreth.set4u.core.Enabled
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class SettingStateStoreTest {
    @Test fun subscriberCanReadStateFromAnotherThreadWithoutWaitingForTheEmitterLock() = runBlocking {
        val store = SettingStateStore()
        val key = SettingKey("session", Target, "a")
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
        val observer = launch(Dispatchers.Unconfined) {
            store.observeKey(key).collect {
                if (it.observation != null) reader.submit<SettingStatus> { store.status(key) }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        try { store.observe(SettingObservation(key, SettingSnapshot(Enabled), 0)) }
        finally { observer.cancelAndJoin(); reader.shutdownNow() }
    }
    @Test fun keyedSubscriptionsIgnoreOtherTargetsAndPinOnlyTheirCacheEntry() = runTest {
        var now = 0L
        val store = SettingStateStore(2, 100) { now }
        fun key(id: String) = SettingKey("session", Target, id)
        fun update(id: String) = store.observe(SettingObservation(key(id), SettingSnapshot(Enabled), now++))
        var emissions = 0
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.observeKey(key("a")).collect { emissions++ } }
        update("b"); update("c")
        assertEquals(1, emissions)
        assertTrue(store.state.value.containsKey(key("a")))
        assertFalse(store.state.value.containsKey(key("b")))
        update("a")
        assertEquals(2, emissions)
        observer.cancelAndJoin()
        now = 200
        update("d")
        assertEquals(setOf(key("d")), store.state.value.keys)
        store.releaseTarget(Target)
        assertTrue(store.state.value.isEmpty())
    }
}
