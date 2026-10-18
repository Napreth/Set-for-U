package com.napreth.set4u.core.bridge

import com.napreth.set4u.core.FakeBridge
import com.napreth.set4u.core.expectFailure
import com.napreth.set4u.core.session.AppTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BridgeContractTest {
    @Test
    fun argumentsAreQuotedAsDataIncludingApostrophesAndShellSyntax() {
        val command = ShellCommand.arguments("tool", "a'b", "x; echo unsafe", "")
        assertEquals("'tool' 'a'\"'\"'b' 'x; echo unsafe' ''", command.render())
    }

    @Test
    fun explicitScriptPreservesShellSyntax() {
        assertEquals("printf hello | cat", ShellCommand.Script("printf hello | cat").render())
    }

    @Test
    fun optionalCapabilitiesFailExplicitly() = runTest {
        val bridge = FakeBridge()
        expectFailure<UnsupportedOperationException> {
            bridge.openProcess(ShellRequest(ShellCommand.arguments("tool")))
        }
        expectFailure<UnsupportedOperationException> {
            bridge.transfer(TransferRequest("source", "destination"))
        }
        bridge.close()
    }

    @Test
    fun targetValidationAllowsPlatformPackageButRejectsShellInput() = runTest {
        assertEquals("android", AppTarget(0, "android").packageName)
        expectFailure<IllegalArgumentException> { AppTarget(-1, "com.example.app") }
        expectFailure<IllegalArgumentException> { AppTarget(0, "com.example; echo unsafe") }
    }
}
