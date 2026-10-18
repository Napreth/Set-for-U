package com.napreth.set4u.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.napreth.set4u.core.bridge.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

/** A non-daemon UserService owned by this client, separate from the shared Shizuku server. */
class ShizukuBridge(context: Context) : IAdbBridge {
    override val transport = BridgeTransport.Shizuku
    private val mutableState = MutableStateFlow(BridgeState.Disconnected)
    override val connectionState = mutableState.asStateFlow()
    private val lifecycle = Mutex()
    private val processes = ConcurrentHashMap.newKeySet<RemoteProcess>()
    private val transfers = ConcurrentHashMap.newKeySet<Job>()
    private val token = UUID.randomUUID().toString()
    private val component = ComponentName(context.applicationContext, BridgeUserService::class.java)
    @Volatile private var service: IBridgeService? = null
    private val bindingLock = Any()
    @Volatile private var binding: Binding? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Each binding owns its callbacks and death recipient. Old callbacks cannot touch a new lease.
    private inner class Binding {
        // The Shizuku SDK caches its own connection by tag, so the tag must also change per lease.
        private val generation = UUID.randomUUID().toString()
        val args = Shizuku.UserServiceArgs(component).daemon(false).tag("set4u-$token-$generation")
            .processNameSuffix("bridge-${token.take(8)}-${generation.take(8)}").version(1)
        val ready = CompletableDeferred<IBridgeService>()
        var binder: IBinder? = null
        var bound = false
        val death = IBinder.DeathRecipient { disconnected(this) }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, endpoint: IBinder) {
                synchronized(bindingLock) {
                    if (binding !== this@Binding || mutableState.value == BridgeState.Closed) return
                    try {
                        endpoint.linkToDeath(death, 0)
                        binder = endpoint
                        ready.complete(IBridgeService.Stub.asInterface(endpoint))
                    } catch (exception: Exception) { ready.completeExceptionally(exception) }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) { disconnected(this@Binding) }
            override fun onBindingDied(name: ComponentName) { disconnected(this@Binding) }
        }
    }
    private val deadListener = Shizuku.OnBinderDeadListener {
        if (!Shizuku.pingBinder()) binding?.let(::disconnected)
    }

    init { Shizuku.addBinderDeadListener(deadListener) }

    private fun disconnected(attempt: Binding) {
        synchronized(bindingLock) {
            if (binding !== attempt || mutableState.value == BridgeState.Closed) return
            service = null
            attempt.ready.completeExceptionally(BridgeUnavailableException(BridgeState.Disconnected))
            mutableState.value = BridgeState.Disconnected
            scope.launch { processes.toList().forEach { runCatching { it.close() } } }
        }
    }

    private suspend fun retire(attempt: Binding) {
        val bound = synchronized(bindingLock) {
            attempt.binder?.let { runCatching { it.unlinkToDeath(attempt.death, 0) } }
            attempt.binder = null
            attempt.bound.also { attempt.bound = false }
        }
        if (bound) withContext(Dispatchers.Main) { runCatching { Shizuku.unbindUserService(attempt.args, attempt.connection, true) } }
    }

    /** Permission UI is explicit: callers choose when to ask with requestPermission=true. */
    suspend fun connect(requestPermission: Boolean = false, timeoutMillis: Long = 15_000) = lifecycle.withLock {
        check(mutableState.value != BridgeState.Closed) { "Bridge is closed" }
        if (mutableState.value == BridgeState.Ready && service?.asBinder()?.pingBinder() == true &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return@withLock
        var attempt: Binding? = null
        try {
            withTimeout(timeoutMillis) {
                awaitBinder()
                check(mutableState.value != BridgeState.Closed) { "Bridge is closed" }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    synchronized(bindingLock) {
                        check(mutableState.value != BridgeState.Closed) { "Bridge is closed" }
                        mutableState.value = BridgeState.Unauthorized
                    }
                    if (!requestPermission || !awaitPermission()) throw BridgeUnavailableException(BridgeState.Unauthorized)
                }
                val next = Binding()
                attempt = next
                val previous = synchronized(bindingLock) {
                    check(mutableState.value != BridgeState.Closed) { "Bridge is closed" }
                    binding.also { binding = next; service = null }
                }
                previous?.let { retire(it) }
                withContext(Dispatchers.Main) {
                    synchronized(bindingLock) {
                        check(binding === next && mutableState.value != BridgeState.Closed)
                        next.bound = true
                        Shizuku.bindUserService(next.args, next.connection)
                    }
                }
                val endpoint = next.ready.await()
                synchronized(bindingLock) {
                    check(binding === next && mutableState.value != BridgeState.Closed)
                    check(endpoint.asBinder().pingBinder()) { "Bridge service disconnected during binding" }
                    service = endpoint
                    mutableState.value = BridgeState.Ready
                }
            }
        } catch (e: Throwable) {
            synchronized(bindingLock) {
                if (mutableState.value != BridgeState.Closed) {
                    if (mutableState.value != BridgeState.Unauthorized) mutableState.value = BridgeState.Disconnected
                    if (binding === attempt) { binding = null; service = null }
                }
            }
            attempt?.let { cleanupWithin { retire(it) } }
            throw e
        }
    }

    private suspend fun awaitBinder() {
        val ready = CompletableDeferred<Unit>()
        val listener = Shizuku.OnBinderReceivedListener { ready.complete(Unit) }
        Shizuku.addBinderReceivedListenerSticky(listener)
        try { ready.await() } finally { Shizuku.removeBinderReceivedListener(listener) }
    }

    private suspend fun awaitPermission(): Boolean {
        val code = permissionCodes.incrementAndGet()
        val result = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { request, grant ->
            if (request == code) result.complete(grant == PackageManager.PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            withContext(Dispatchers.Main) { Shizuku.requestPermission(code) }
            return result.await()
        } finally { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    override suspend fun execute(request: ShellRequest): ShellResult =
        executeShell(request) { openProcess(request) }

    override suspend fun openProcess(request: ShellRequest): RemoteProcess = lifecycle.withLock {
        val endpoint = requireService()
        val input = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.createPipe()
        val error = ParcelFileDescriptor.createPipe()
        try {
            val handle = withContext(NonCancellable + Dispatchers.IO) {
                endpoint.start(request.command.render(), input[0], output[1], error[1])
            }
            val remote = StreamRemoteProcess(
                ParcelFileDescriptor.AutoCloseOutputStream(input[1]),
                ParcelFileDescriptor.AutoCloseInputStream(output[0]),
                ParcelFileDescriptor.AutoCloseInputStream(error[0]), request.timeoutMillis,
                waitFor = {
                    var code = handle.pollExit()
                    while (code < 0) {
                        Thread.sleep(25)
                        code = handle.pollExit()
                    }
                    code
                },
                terminate = { withContext(Dispatchers.IO) { runCatching { handle.close() }; Unit } },
                onClosed = { processes.remove(it) },
            )
            processes.add(remote)
            if (remote.isClosed) processes.remove(remote)
            if (!currentCoroutineContext().isActive || mutableState.value == BridgeState.Closed) remote.close()
            currentCoroutineContext().ensureActive()
            requireService()
            remote
        } catch (e: Throwable) {
            (input + output + error).forEach { runCatching { it.close() } }
            throw e
        } finally {
            input[0].close()
            output[1].close()
            error[1].close()
        }
    }

    override suspend fun transfer(request: TransferRequest) = coroutineScope {
        if (request.atomic) { atomicTransfer(request); return@coroutineScope }
        val job = currentCoroutineContext().job
        val endpoint = lifecycle.withLock { requireService().also { transfers.add(job) } }
        try {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    when (request.direction) {
                        TransferDirection.ToDevice -> File(request.source).inputStream().use { source ->
                            ParcelFileDescriptor.AutoCloseOutputStream(endpoint.openFile(request.destination, true)).use { source.copyTo(it) }
                        }
                        TransferDirection.FromDevice -> ParcelFileDescriptor.AutoCloseInputStream(endpoint.openFile(request.source, false)).use { source ->
                            File(request.destination).outputStream().use { source.copyTo(it) }
                        }
                    }
                }
            }
            Unit
        } finally { transfers.remove(job) }
    }
    private fun requireService(): IBridgeService {
        if (mutableState.value == BridgeState.Ready && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            mutableState.compareAndSet(BridgeState.Ready, BridgeState.Unauthorized)
        }
        if (mutableState.value != BridgeState.Ready) throw BridgeUnavailableException(mutableState.value)
        return service ?: throw BridgeUnavailableException(BridgeState.Disconnected)
    }

    override suspend fun close() {
        val attempt = synchronized(bindingLock) {
            if (mutableState.value == BridgeState.Closed) return
            mutableState.value = BridgeState.Closed
            service = null
            binding.also { binding = null; it?.ready?.completeExceptionally(BridgeUnavailableException(BridgeState.Closed)) }
        }
        scope.cancel()
        transfers.toList().forEach { it.cancel() }
        Shizuku.removeBinderDeadListener(deadListener)
        cleanupWithin {
            attempt?.let { launch { retire(it) } }
            processes.toList().forEach { launch { runCatching { it.close() } } }
        }
    }

    private companion object { val permissionCodes = AtomicInteger(4000) }
}
