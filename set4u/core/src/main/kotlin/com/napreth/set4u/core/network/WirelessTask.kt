@file:Suppress("DEPRECATION", "MissingPermission")
package com.napreth.set4u.core.network

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.net.wifi.*
import android.os.*
import com.napreth.set4u.core.device.*
import com.napreth.set4u.core.session.DeviceTarget
import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.util.concurrent.*

/** Finite device process. Receivers, discovery and callbacks belong to this invocation. */
@DeviceEntry("network.wireless", protocolVersion = 2, spec = WirelessSpec::class)
class WirelessTask : DeviceTask<WirelessRequest, WirelessSnapshot>() {
    override suspend fun execute(context: DeviceTaskContext, request: WirelessRequest): WirelessSnapshot {
        if (request.kind == WirelessKind.Wifi) return WifiTask().execute(context, request)
        require(context.target == DeviceTarget())
        check(Process.myUid() == 0) { "无线网络管理需要 root 权限" }
        check(Build.VERSION.SDK_INT >= 30) { "无线网络管理需要 Android 11 或更高版本" }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val threadType = Class.forName("android.app.ActivityThread")
        val thread = threadType.getMethod("systemMain").invoke(null)
        val system = threadType.getMethod("getSystemContext").invoke(thread) as Context
        val api = system.createPackageContext("com.android.shell", 0)
        try {
            return when (request.kind) {
                WirelessKind.Wifi -> error("Wi-Fi is handled by WifiTask")
                WirelessKind.Bluetooth -> bluetooth(api, request)
                WirelessKind.Hotspot -> hotspot(api, request)
            }
        } catch (exception: java.lang.reflect.InvocationTargetException) { throw exception.targetException }
    }
    private fun arg(request: WirelessRequest, name: String) = requireNotNull(request.parameters[name]) { "Missing $name" }
    private fun ssid(value: String?) = value?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" }.orEmpty()

    private suspend fun bluetooth(context: Context, request: WirelessRequest): WirelessSnapshot {
        // app_process does not pass through the zygote's modular service initialization.
        try {
            val initializer = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
            if (initializer.getMethod("getBluetoothServiceManager").invoke(null) == null) {
                val manager = Class.forName("android.os.BluetoothServiceManager")
                initializer.getMethod("setBluetoothServiceManager", manager).invoke(null, manager.getConstructor().newInstance())
            }
        } catch (_: ClassNotFoundException) { /* Older framework versions initialize this service directly. */ }
        val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter) { "设备没有蓝牙适配器" }
        val found = ConcurrentHashMap<String, BluetoothDevice>()
        when (request.operation) {
            "query" -> Unit
            "scan" -> {
                check(adapter.isEnabled) { "请先开启蓝牙" }
                check(!adapter.isDiscovering) { "系统正在扫描蓝牙，请稍后重试" }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null) found[device.address] = device
                    }
                }
                val worker = HandlerThread("set4u-bluetooth").apply { start() }
                val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, null, Handler(worker.looper), Context.RECEIVER_EXPORTED)
                else context.registerReceiver(receiver, filter, null, Handler(worker.looper))
                val scanner = adapter.bluetoothLeScanner
                val leCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) { found[result.device.address] = result.device }
                }
                var owned = false
                var leOwned = false
                try {
                    owned = adapter.startDiscovery()
                    if (scanner != null) { scanner.startScan(leCallback); leOwned = true }
                    check(owned || leOwned) { "系统未接受蓝牙扫描请求" }
                    delay(12_000)
                } finally {
                    if (leOwned) scanner?.stopScan(leCallback)
                    if (owned && adapter.isDiscovering) adapter.cancelDiscovery()
                    context.unregisterReceiver(receiver); worker.quitSafely()
                }
            }
            "rename" -> check(adapter.setName(arg(request, "name"))) { "无法修改蓝牙名称" }
            "pair", "unpair", "connect", "disconnect" -> {
                check(adapter.isEnabled) { "请先开启蓝牙" }
                val address = arg(request, "address"); require(BluetoothAdapter.checkBluetoothAddress(address))
                val device = adapter.getRemoteDevice(address)
                when (request.operation) {
                    "pair" -> check(device.createBond()) { "系统未接受配对请求" }
                    "unpair" -> check(device.javaClass.getMethod("removeBond").invoke(device) != false) { "无法取消配对" }
                    else -> {
                        check(device.bondState == BluetoothDevice.BOND_BONDED) { "请先完成配对" }
                        val result = device.javaClass.getMethod(request.operation).invoke(device)
                        check(result != false && (result !is Int || result == 0)) { "系统未接受蓝牙连接请求（$result）" }
                    }
                }
            }
            else -> error("Unknown Bluetooth operation")
        }
        fun item(device: BluetoothDevice, category: String) = WirelessItem(device.address, device.name ?: device.address, category,
            detail = device.address, connected = device.javaClass.getMethod("isConnected").invoke(device) as Boolean)
        val paired = adapter.bondedDevices.orEmpty().associateBy { it.address }
        val requested = request.parameters["address"]?.let { adapter.getRemoteDevice(it) }
        val fields = mutableMapOf("enabled" to adapter.isEnabled.toString(), "name" to adapter.name.orEmpty())
        if (requested != null) {
            fields["bonded"] = (requested.bondState == BluetoothDevice.BOND_BONDED).toString()
            fields["connected"] = (requested.javaClass.getMethod("isConnected").invoke(requested) as Boolean).toString()
        }
        return WirelessSnapshot(fields, paired.values.map { item(it, "paired") } + found.values.filter { it.address !in paired }.map { item(it, "available") })
    }

    private suspend fun hotspot(context: Context, request: WirelessRequest): WirelessSnapshot {
        val wifi = checkNotNull(context.getSystemService(WifiManager::class.java))
        val manager = checkNotNull(context.getSystemService("tethering"))
        val type = request.parameters["type"]?.toInt() ?: 0
        require(type in 0..2)
        when (request.operation) {
            "query" -> Unit
            "configure" -> {
                check(wifi.javaClass.getMethod("getWifiApState").invoke(wifi) != 13) { "请先关闭热点再修改配置" }
                val current = wifi.javaClass.getMethod("getSoftApConfiguration").invoke(wifi)
                val configClass = Class.forName("android.net.wifi.SoftApConfiguration")
                val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
                val builder = builderClass.getConstructor(configClass).newInstance(current)
                val name = arg(request, "ssid"); require(name.toByteArray(Charsets.UTF_8).size in 1..32)
                val password = arg(request, "password"); require(password.length in 8..63)
                builderClass.getMethod("setSsid", String::class.java).invoke(builder, name)
                builderClass.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType).invoke(builder, password, 1)
                builderClass.getMethod("setBand", Int::class.javaPrimitiveType).invoke(builder, arg(request, "band").toInt().also { require(it in 1..3) })
                check(wifi.javaClass.getMethod("setSoftApConfiguration", configClass).invoke(wifi, builderClass.getMethod("build").invoke(builder)) as Boolean)
            }
            "start" -> {
                val builderType = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                val builder = builderType.getConstructor(Int::class.javaPrimitiveType).newInstance(type)
                val tetherRequest = builderType.getMethod("build").invoke(builder)
                val callbackType = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                val completed = CompletableDeferred<Int>()
                val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
                    when (method.name) {
                        "onTetheringStarted" -> { completed.complete(0); null }
                        "onTetheringFailed" -> { completed.complete(args!![0] as Int); null }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.get(0)
                        "toString" -> "Set4uTetheringCallback"
                        else -> null
                    }
                }
                manager.javaClass.getMethod("startTethering", tetherRequest.javaClass, Executor::class.java, callbackType)
                    .invoke(manager, tetherRequest, Executor { it.run() }, callback)
                val result = withTimeout(15_000) { completed.await() }
                check(result == 0) { "网络共享启动失败（系统错误 $result）" }
            }
            "stop" -> manager.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(manager, type)
            else -> error("Unknown tethering operation")
        }
        val interfaces = manager.javaClass.getMethod("getTetheredIfaces").invoke(manager) as Array<*>
        val config = wifi.javaClass.getMethod("getSoftApConfiguration").invoke(wifi)
        fun active(method: String): Boolean {
            val patterns = manager.javaClass.getMethod(method).invoke(manager) as Array<*>
            return interfaces.any { name -> patterns.any { Regex(it.toString()).matches(name.toString()) } }
        }
        val fields = mutableMapOf(
            "wifi" to active("getTetherableWifiRegexs").toString(),
            "usb" to active("getTetherableUsbRegexs").toString(),
            "bluetooth" to active("getTetherableBluetoothRegexs").toString(),
            "ssid" to config.javaClass.getMethod("getSsid").invoke(config).toString(),
            "band" to config.javaClass.getMethod("getBand").invoke(config).toString(),
            "interfaces" to interfaces.joinToString(", "),
        )
        request.parameters["password"]?.let { expected ->
            fields["passwordMatches"] = (config.javaClass.getMethod("getPassphrase").invoke(config) == expected).toString()
        }
        return WirelessSnapshot(fields)
    }
}
