package com.napreth.set4u.core.bridge

import com.napreth.set4u.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class RootBridgeTest {
    @Test fun closingDuringAuthorizationCannotResurrectTheBridge() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delegate = FakeBridge().apply { testUid = 0; onExecute = { entered.complete(Unit); release.await(); ShellResult(0,"0") } }
        val root = RootBridge(delegate)
        supervisorScope {
            val pending = async { root.connect() }
            entered.await(); root.close(); release.complete(Unit)
            expectFailure<IllegalStateException> { pending.await() }
        }
        assertEquals(BridgeState.Closed,root.connectionState.value)
        assertEquals(1,delegate.closeCalls)
    }
    @Test fun aDeniedSuGrantNeverReportsAReadyShellFallback() = runTest {
        val delegate = FakeBridge().apply { onExecute = { ShellResult(126,stderr="Permission denied") } }
        val root = RootBridge(delegate)
        try {
            expectFailure<BridgeUnavailableException> { root.connect() }
            assertEquals(BridgeState.Unauthorized,root.connectionState.value)
            expectFailure<BridgeUnavailableException> { root.execute(ShellRequest(ShellCommand.arguments("id","-u"))) }
        } finally { root.close() }
    }

    @Test fun shellEmulationRejectsRootOutputInsteadOfPretendingItIsShell() = runTest {
        val delegate = FakeBridge().apply { testUid = 0; onExecute = { ShellResult(0, "0") } }
        val root = RootBridge(delegate, emulateShell = true)
        try {
            expectFailure<ShellIdentityUnavailableException> { root.connect() }
            assertEquals(BridgeState.Disconnected, root.connectionState.value)
            expectFailure<BridgeUnavailableException> { root.execute(ShellRequest(ShellCommand.arguments("id", "-u"))) }
        } finally { root.close() }
    }

    @Test fun shellEmulationStillRequiresRootAuthorization() = runTest {
        val delegate = FakeBridge().apply { onExecute = { ShellResult(0, "2000") } }
        val root = RootBridge(delegate, emulateShell = true)
        try {
            expectFailure<BridgeUnavailableException> { root.connect() }
            assertEquals(BridgeState.Unauthorized, root.connectionState.value)
        } finally { root.close() }
    }

    @Test fun closingDuringShellVerificationCannotReportReadyLater() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val delegate = FakeBridge().apply { testUid = 0; onExecute = {
            if (++calls == 1) ShellResult(0, "0")
            else { entered.complete(Unit); release.await(); ShellResult(0, "2000") }
        } }
        val root = RootBridge(delegate, emulateShell = true)
        supervisorScope {
            val pending = async { root.connect() }
            entered.await(); root.close(); release.complete(Unit)
            expectFailure<IllegalStateException> { pending.await() }
        }
        assertEquals(BridgeState.Closed, root.connectionState.value)
    }
}
