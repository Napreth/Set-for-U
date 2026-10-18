package com.napreth.set4u.core.device

import com.napreth.set4u.core.bridge.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One bridge is bound to one device. The private namespace belongs to this session only. */
internal class DeviceBundleCache(private val bridge: IAdbBridge) {
    private val directory = "/data/local/tmp/set4u-cache-${UUID.randomUUID()}"
    private val gate = Mutex()
    private val generation = AtomicLong()
    private var verified: Pair<Long, String>? = null
    private var attempted = false
    private var closed = false

    fun invalidate() { generation.incrementAndGet() }

    suspend fun artifact(): String = gate.withLock {
        check(!closed) { "Device bundle cache is closed" }
        verified?.takeIf { it.first == generation.get() }?.let { return@withLock it.second }
        val local = File.createTempFile("set4u-device-", ".jar")
        try {
            val deploymentGeneration = generation.get()
            val identity = withContext(Dispatchers.IO) {
                val loader = javaClass.classLoader!!
                val digest = MessageDigest.getInstance("SHA-256")
                requireNotNull(loader.getResourceAsStream("device/set4u-device.jar")) { "Missing device task bundle" }.use { input ->
                    local.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= 16 * 1024 * 1024) { "Device bundle exceeds size limit" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                val index = requireNotNull(loader.getResourceAsStream("device/device-tasks.json")).bufferedReader().use { it.readText() }
                check(Regex("\"sha256\":\"$sha\"").containsMatchIn(index)) { "Device bundle checksum mismatch" }
                val version = requireNotNull(loader.getResourceAsStream("device/build-version.txt")).bufferedReader().use { it.readText().trim() }
                require(version.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid device bundle build version" }
                "$version-$sha"
            }
            val remote = "$directory/$identity.jar"
            attempted = true
            bridge.execute(ShellRequest(ShellCommand.Script("umask 077; mkdir -p ${ShellCommand.arguments(directory).render()}"))).requireSuccess()
            bridge.transfer(TransferRequest(local.path, remote)) // Atomic transfer verifies SHA-256 before rename.
            bridge.execute(ShellRequest(ShellCommand.arguments("chmod", "444", remote))).requireSuccess()
            check(generation.get() == deploymentGeneration) { "Device disconnected during deployment" }
            verified = deploymentGeneration to remote
            remote
        } catch (exception: Exception) {
            verified = null
            if (attempted) cleanupWithin { bridge.execute(ShellRequest(ShellCommand.arguments("rm", "-rf", directory), 1500)) }
            throw exception
        } finally { local.delete() }
    }

    suspend fun close() = gate.withLock {
        closed = true
        verified = null
        if (attempted) cleanupWithin { bridge.execute(ShellRequest(ShellCommand.arguments("rm", "-rf", directory), 1500)) }
    }
}
