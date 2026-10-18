package com.napreth.set4u.bridge

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import com.napreth.set4u.core.bridge.ManagedShell
import com.napreth.set4u.core.bridge.ShellCommand
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Instantiated by Shizuku as shell/root; this is not an Android Service component. */
class BridgeUserService(context: Context) : IBridgeService.Stub() {
    private val ownerUid = context.applicationInfo.uid
    private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable, "bridge-pipe").apply { isDaemon = true } }
    private val processes = ConcurrentHashMap.newKeySet<OwnedProcess>()

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid != ownerUid && uid != 0 && uid != 2000) throw SecurityException("Unexpected Bridge caller: $uid")
    }

    override fun start(command: String, stdin: ParcelFileDescriptor, stdout: ParcelFileDescriptor, stderr: ParcelFileDescriptor): IBridgeProcess {
        checkCaller()
        val shell = ManagedShell(ShellCommand.Script(command))
        val process = ProcessBuilder("/system/bin/sh", "-c", shell.start).start()
        return OwnedProcess(process, shell, stdin, stdout, stderr).also { processes.add(it) }
    }

    override fun openFile(path: String, write: Boolean): ParcelFileDescriptor {
        checkCaller()
        require(path.startsWith("/") && '\u0000' !in path)
        val file = File(path)
        require(file.isFile || (write && !file.exists())) { "Bridge transfers require a regular file" }
        return ParcelFileDescriptor.open(file, if (write) {
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        } else ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun releaseAll() { checkCaller(); processes.toList().forEach { it.close() } }
    override fun destroy() { releaseAll(); workers.shutdownNow(); exitProcess(0) }

    private inner class OwnedProcess(
        private val process: Process,
        private val shell: ManagedShell,
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
        error: ParcelFileDescriptor,
    ) : IBridgeProcess.Stub() {
        private val closed = AtomicBoolean(false)
        private val inputStream = ParcelFileDescriptor.AutoCloseInputStream(input)
        private val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(output)
        private val errorStream = ParcelFileDescriptor.AutoCloseOutputStream(error)
        private val inputJob = workers.submit { runCatching { inputStream.use { it.copyTo(process.outputStream) } }; runCatching { process.outputStream.close() } }
        private val outputJob = workers.submit { runCatching { process.inputStream.use { source -> outputStream.use { source.copyTo(it) } } } }
        private val errorJob = workers.submit { runCatching { process.errorStream.use { source -> errorStream.use { source.copyTo(it) } } } }

        override fun pollExit(): Int {
            checkCaller()
            return try { process.exitValue() } catch (_: IllegalThreadStateException) { -1 }
        }
        override fun close() {
            checkCaller()
            if (!closed.compareAndSet(false, true)) return
            try {
                val cleanup = ProcessBuilder("/system/bin/sh", "-c", shell.cleanup).start()
                val deadline = System.nanoTime() + 2_000_000_000L
                var finished = false
                while (!finished && System.nanoTime() < deadline) {
                    finished = try { cleanup.exitValue(); true } catch (_: IllegalThreadStateException) { false }
                    if (!finished) Thread.sleep(25)
                }
                if (!finished) cleanup.destroy()
            } finally {
                process.destroy()
                runCatching { inputStream.close() }
                runCatching { process.outputStream.close() }
                runCatching { outputStream.close() }
                runCatching { errorStream.close() }
                inputJob.cancel(true)
                outputJob.cancel(true)
                errorJob.cancel(true)
                processes.remove(this)
            }
        }
    }
}