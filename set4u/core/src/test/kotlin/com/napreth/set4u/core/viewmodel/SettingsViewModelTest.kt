@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.*
import com.napreth.set4u.core.bridge.BridgeState
import com.napreth.set4u.core.bridge.ShellCommand
import com.napreth.set4u.core.execution.SettingOutcome
import com.napreth.set4u.core.session.AppTarget
import com.napreth.set4u.core.settings.SettingValue
import com.napreth.set4u.core.settings.SettingsCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun enteringLoadsWithoutWritingAndDoesNotInitializeTwice() = runTest {
        val bridge = FakeBridge()
        withSession(bridge) { session ->
            val vm = session.viewModels.settings(Target, "sample")
            val firstLoad = vm.enter()
            assertSame(firstLoad, vm.enter())
            firstLoad.join()
            runCurrent()
            assertFalse(vm.state.value.isLoading)
            assertEquals(Disabled, vm.state.value.items.single().status.observation?.snapshot?.value)
            assertTrue(bridge.writes.isEmpty())
            assertEquals(1, bridge.requests.size)
            vm.close()
        }
    }

    @Test
    fun settingsInDifferentListsShareObservedStateAndOperationResults() = runTest {
        val definition = flagDefinition()
        val catalog = SettingsCatalog(flagPage("permissions", definition), flagPage("background", definition))
        withSession(catalog = catalog) { session ->
            val first = session.viewModels.settings(Target, "permissions")
            val second = session.viewModels.settings(Target, "background")
            first.enter().join()
            second.enter().join()
            first.dispatch(SettingsEvent.ChangeValue(definition.id, Enabled))!!.join()
            runCurrent()
            assertEquals(Enabled, first.state.value.items.single().status.observation?.snapshot?.value)
            assertEquals(Enabled, second.state.value.items.single().status.observation?.snapshot?.value)
            assertEquals(
                SettingOutcome.Verified,
                second.state.value.items.single().status.lastResult?.outcome,
            )
            first.close()
            second.close()
        }
    }

    @Test
    fun pageClosureDoesNotCancelSessionOwnedMutation() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bridge = FakeBridge().apply {
            onExecute = { request ->
                if ((request.command as ShellCommand.Arguments).values[0] == "write-flag") {
                    started.complete(Unit)
                    release.await()
                }
                respond(request)
            }
        }
        withSession(bridge) { session ->
            val vm = session.viewModels.settings(Target, "sample")
            vm.enter().join()
            val operation = vm.setValue("sample.flag", Enabled)!!
            runCurrent()
            started.await()
            assertTrue(vm.state.value.items.single().status.isChanging)
            vm.close()
            release.complete(Unit)
            assertEquals(SettingOutcome.Verified, operation.await().outcome)
            assertEquals(Enabled, session.state.value.values.single().observation?.snapshot?.value)
        }
    }

    @Test
    fun refreshDiscardsAnOldProviderEvenWhenItIgnoresCancellation() = runTest {
        var loads = 0
        val oldDefinition = flagDefinition("old.flag")
        val newDefinition = flagDefinition("new.flag")
        val catalog = SettingsCatalog(mapOf("sample" to {
            if (++loads == 1) {
                withContext(NonCancellable) { delay(100) }
                flagPage(definition = oldDefinition)
            } else flagPage(definition = newDefinition)
        }), entries = listOf(entryOf(oldDefinition), entryOf(newDefinition)))
        withSession(catalog = catalog) { session ->
            val vm = session.viewModels.settings(Target, "sample")
            vm.enter()
            runCurrent()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf("new.flag"), vm.state.value.items.map { it.component.id })
            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
            vm.close()
        }
    }

    @Test
    fun invalidInputIsShownWithoutCallingBridge() = runTest {
        val bridge = FakeBridge()
        withSession(bridge) { session ->
            val vm = session.viewModels.settings(Target, "sample")
            vm.enter().join()
            val requestsBefore = bridge.requests.size
            assertNull(vm.setValue("sample.flag", SettingValue.TextValue("not a boolean")))
            assertNotNull(vm.state.value.error)
            assertEquals(requestsBefore, bridge.requests.size)
            assertTrue(bridge.writes.isEmpty())
            vm.close()
        }
    }

    @Test
    fun missingSettingSetProducesPageError() = runTest {
        val bridge = FakeBridge()
        withSession(bridge) { session ->
            val vm = session.viewModels.settings(Target, "missing")
            vm.enter().join()
            assertNotNull(vm.state.value.error)
            assertTrue(vm.state.value.items.isEmpty())
            assertFalse(vm.state.value.isLoading)
            assertTrue(bridge.requests.isEmpty())
            vm.close()
        }
    }

    @Test
    fun externalStateIsReadOnlyWhenPageRefreshes() = runTest {
        val bridge = FakeBridge()
        withSession(bridge) { session ->
            val vm = session.viewModels.settings(Target, "sample")
            vm.enter().join()
            runCurrent()
            bridge.flags[Target.toString()] = true
            runCurrent()
            assertEquals(Disabled, vm.state.value.items.single().status.observation?.snapshot?.value)
            vm.refresh().join()
            runCurrent()
            assertEquals(Enabled, vm.state.value.items.single().status.observation?.snapshot?.value)
            assertTrue(bridge.writes.isEmpty())
            vm.close()
        }
    }

    @Test
    fun androidUsersHaveSeparateState() = runTest {
        val bridge = FakeBridge().apply { flags[Target.toString()] = true }
        withSession(bridge) { session ->
            val owner = session.viewModels.settings(Target, "sample")
            val other = session.viewModels.settings(AppTarget(10, Target.packageName), "sample")
            owner.enter().join()
            other.enter().join()
            runCurrent()
            assertEquals(Enabled, owner.state.value.items.single().status.observation?.snapshot?.value)
            assertEquals(Disabled, other.state.value.items.single().status.observation?.snapshot?.value)
            owner.close()
            other.close()
        }
    }

    @Test
    fun closingSessionReleasesBridgeOnceAndRejectsNewWork() = runTest {
        val bridge = FakeBridge()
        withSession(bridge) { session ->
            val vm = session.viewModels.settings(Target, "sample")
            vm.enter().join()
            session.close()
            session.close()
            assertEquals(1, bridge.closeCalls)
            assertEquals(BridgeState.Closed, session.connectionState.value)
            expectFailure<IllegalStateException> { session.viewModels.settings(Target, "sample") }
            expectFailure<IllegalStateException> { vm.refresh() }
            expectFailure<IllegalStateException> {
                session.settings.set(Target, flagDefinition(), Enabled)
            }
        }
    }
}
