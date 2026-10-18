package com.napreth.set4u.core.bridge

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

enum class BridgeState { Ready, Disconnected, Unauthorized, Closed }

/**
 * A client connection bound to one Android device.
 * Implementations must stop owned work when a suspending call is cancelled.
 */
interface IAdbBridge {
    val connectionState: StateFlow<BridgeState>
    val transport: BridgeTransport get() = BridgeTransport.Unknown

    suspend fun environment(): ExecutionEnvironment {
        // Includes transport initialization and bounded process cleanup on cold desktop runtimes.
        val result = execute(ShellRequest(ShellCommand.arguments("id", "-u"), 5_000, 256)).requireSuccess()
        val uid = result.stdout.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
        return ExecutionEnvironment(transport, uid)
    }

    /** Collects UTF-8 stdout/stderr, keeping the device exit code. */
    suspend fun execute(request: ShellRequest): ShellResult

    /** Raw byte streams. Callers drain both outputs and close the handle after use. */
    suspend fun openProcess(request: ShellRequest): RemoteProcess =
        throw UnsupportedOperationException("This bridge does not support remote processes")

    /** One regular file; the device path is absolute, the local path belongs to this platform. */
    suspend fun transfer(request: TransferRequest) {
        throw UnsupportedOperationException("This bridge does not support file transfer")
    }

    /** Releases this client's resources, not a shared ADB server or Shizuku instance. */
    suspend fun close()
}

interface RemoteProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    suspend fun awaitExit(): Int
    suspend fun close()
}

enum class TransferDirection { ToDevice, FromDevice }

data class TransferRequest(
    val source: String,
    val destination: String,
    val direction: TransferDirection = TransferDirection.ToDevice,
    /** False is reserved for a caller that explicitly accepts a partial destination. */
    val atomic: Boolean = true,
) {
    init {
        require(source.isNotBlank() && destination.isNotBlank())
        require('\u0000' !in source && '\u0000' !in destination)
    }
}

data class ShellRequest(
    val command: ShellCommand,
    val timeoutMillis: Long = 5_000,
    val maxOutputBytes: Int = 2 * 1024 * 1024,
    /** Opt-in diagnostic text. Raw commands, arguments and output are omitted from evidence by default. */
    val evidenceLabel: String = "shell",
    val retainOutputInEvidence: Boolean = false,
) {
    init {
        require(timeoutMillis > 0)
        require(maxOutputBytes in 0..16 * 1024 * 1024)
        require(evidenceLabel.length <= 256)
    }
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
) {
    val outputTruncated: Boolean get() = stdoutTruncated || stderrTruncated
    fun requireSuccess(): ShellResult {
        if (exitCode != 0) throw ShellCommandException(this)
        check(!outputTruncated) { "Command output was truncated" }
        return this
    }
}

class ShellCommandException(val result: ShellResult) :
    Exception("Command exited with ${result.exitCode}")

class BridgeUnavailableException(state: BridgeState) :
    Exception("Bridge is not ready: $state")

class ShellTimeoutException(timeoutMillis: Long) :
    Exception("Command timed out after ${timeoutMillis}ms")
