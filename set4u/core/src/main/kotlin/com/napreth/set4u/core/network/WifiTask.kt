@file:Suppress("DEPRECATION", "MissingPermission")
package com.napreth.set4u.core.network

import android.content.Context
import android.net.*
import android.net.wifi.*
import android.os.*
import com.napreth.set4u.core.device.*
import com.napreth.set4u.core.session.DeviceTarget
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.function.Consumer

/** All changes use the system Wi-Fi service. No configuration XML or supplicant files are edited. */
@DeviceEntry("network.wifi", protocolVersion = 2, spec = WifiSpec::class)
class WifiTask : DeviceTask<WirelessRequest, WirelessSnapshot>() {
    override suspend fun execute(context: DeviceTaskContext, request: WirelessRequest): WirelessSnapshot {
        require(context.target == DeviceTarget() && request.kind == WirelessKind.Wifi)
        check(Process.myUid() == 0) { "无线网络管理需要 root 权限" }
        check(Build.VERSION.SDK_INT >= 30)
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val type = Class.forName("android.app.ActivityThread")
        val thread = type.getMethod("systemMain").invoke(null)
        val api = (type.getMethod("getSystemContext").invoke(thread) as Context).createPackageContext("com.android.shell", 0)
        val wifi = checkNotNull(api.getSystemService(WifiManager::class.java))
        try {
            when (request.operation) {
                "query" -> Unit
                "scan" -> {
                    check(wifi.isWifiEnabled) { "请先开启 WLAN" }
                    val old = wifi.scanResults.maxOfOrNull { it.timestamp } ?: 0
                    check(wifi.startScan()) { "系统暂未接受扫描，请稍后重试" }
                    val fresh = withTimeoutOrNull(7_000) { while ((wifi.scanResults.maxOfOrNull { it.timestamp } ?: 0) <= old) delay(250); true } ?: false
                    return snapshot(api, wifi).let { it.copy(fields = it.fields + ("scanFresh" to fresh.toString())) }
                }
                "connectSaved" -> {
                    val c = saved(wifi, request)
                    check(wifi.enableNetwork(c.networkId, true)) { "系统未接受连接请求" }
                    check(wifi.reconnect()) { "无法启动连接" }
                }
                "disconnect" -> check(wifi.disconnect()) { "系统未接受断开请求" }
                "forget" -> check(wifi.removeNetwork(saved(wifi, request).networkId)) { "系统未删除此网络" }
                "configure" -> configure(wifi, saved(wifi, request), request.parameters)
                "preference" -> preference(api, wifi, request.parameters.getValue("option"), request.parameters.getValue("value").toBooleanStrict())
                "share" -> {
                    val target = saved(wifi, request)
                    @Suppress("UNCHECKED_CAST")
                    val configs = wifi.javaClass.getMethod("getPrivilegedConfiguredNetworks").invoke(wifi) as List<WifiConfiguration>
                    val full = configs.firstOrNull { it.networkId == target.networkId && it.SSID == target.SSID } ?: error("无法读取此网络的凭据")
                    val password = if (security(full) == "wep") full.wepKeys.getOrNull(full.wepTxKeyIndex) else full.preSharedKey
                    check(password != "*" && (security(full) in setOf("open", "owe") || !password.isNullOrEmpty())) { "系统未返回可分享的密码" }
                    return WirelessSnapshot(mapOf("qr" to WifiSharing.payload(ssid(full.SSID), security(full), password?.removeSurrounding("\"").orEmpty(), full.hiddenSSID)))
                }
                else -> error("Unknown Wi-Fi operation")
            }
            val result = snapshot(api, wifi)
            if (request.parameters["option"] == "password") {
                @Suppress("UNCHECKED_CAST")
                val configs = wifi.javaClass.getMethod("getPrivilegedConfiguredNetworks").invoke(wifi) as List<WifiConfiguration>
                val full = configs.firstOrNull { it.networkId.toString() == request.parameters["id"] && ssid(it.SSID) == request.parameters["ssid"] }
                val password = full?.let { if (security(it) == "wep") it.wepKeys.getOrNull(it.wepTxKeyIndex) else it.preSharedKey }?.removeSurrounding("\"")
                return result.copy(fields = result.fields + ("passwordMatches" to (password == request.parameters["password"]).toString()))
            }
            return result
        } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
    }

    private fun ssid(value: String?) = value?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" }.orEmpty()
    private fun saved(wifi: WifiManager, request: WirelessRequest): WifiConfiguration =
        wifi.configuredNetworks.orEmpty().firstOrNull { it.networkId.toString() == request.parameters["id"] && ssid(it.SSID) == request.parameters["ssid"] }
            ?: error("已保存网络已变化，请重新选择")
    private fun has(o: Any, method: String, vararg types: Class<*>) = try { o.javaClass.getMethod(method, *types); true } catch (_: NoSuchMethodException) { false }
    private fun call(o: Any, method: String) = o.javaClass.getMethod(method).invoke(o)
    private fun int(o: Any, field: String) = o.javaClass.getField(field).getInt(o)
    private fun setInt(o: Any, field: String, value: Int) { o.javaClass.getField(field).setInt(o, value) }

    private fun configure(wifi: WifiManager, config: WifiConfiguration, p: Map<String, String>) {
        WifiConfigurationValues.validate(p)
        val value = p["value"].orEmpty()
        if (p["option"] == "autoJoin") {
            wifi.javaClass.getMethod("allowAutojoin", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(wifi, config.networkId, value.toBooleanStrict())
            return
        }
        when (p["option"]) {
            "metered" -> setInt(config, "meteredOverride", value.toInt())
            "macMode" -> if (has(config, "setMacRandomizationSetting", Int::class.javaPrimitiveType!!)) config.setMacRandomizationSetting(value.toInt()) else setInt(config, "macRandomizationSetting", value.toInt())
            "shared" -> config.shared = value.toBooleanStrict()
            "allowEdit" -> config.setAllowedToUpdateByOtherUsers(value.toBooleanStrict())
            "hidden" -> config.hiddenSSID = value.toBooleanStrict()
            "password" -> {
                val password = p.getValue("password")
                if (security(config) == "wep") config.wepKeys[config.wepTxKeyIndex] = if (password.matches(Regex("[0-9a-fA-F]{10}|[0-9a-fA-F]{26}|[0-9a-fA-F]{58}"))) password else "\"$password\""
                else { check(security(config) in setOf("wpa2", "wpa3")); config.preSharedKey = if (password.matches(Regex("[0-9a-fA-F]{64}"))) password else "\"$password\"" }
            }
            "proxy" -> {
                val proxy = when (value) {
                    "manual" -> ProxyInfo.buildDirectProxy(p.getValue("host"), p.getValue("port").toInt(), p["exclusions"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty))
                    "pac" -> ProxyInfo.buildPacProxy(Uri.parse(p.getValue("pac")))
                    else -> null
                }
                check(proxy == null || proxy.isValid) { "代理配置无效" }
                config.httpProxy = proxy
            }
            "ip" -> {
                val ipType = Class.forName("android.net.IpConfiguration")
                val ip = call(config, "getIpConfiguration")!!
                val assignment = Class.forName("android.net.IpConfiguration\$IpAssignment")
                ipType.getMethod("setIpAssignment", assignment).invoke(ip, assignment.enumConstants.first { (it as Enum<*>).name == if (value == "static") "STATIC" else "DHCP" })
                val static = if (value == "static") StaticIpConfiguration.Builder()
                    .setIpAddress(LinkAddress::class.java.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType).newInstance(InetAddress.getByName(p.getValue("ip")), p.getValue("prefix").toInt()))
                    .setGateway(InetAddress.getByName(p.getValue("gateway")))
                    .setDnsServers(p.getValue("dns").split(',').map { InetAddress.getByName(it.trim()) }).build() else null
                ipType.getMethod("setStaticIpConfiguration", StaticIpConfiguration::class.java).invoke(ip, static)
                config.setIpConfiguration(ip as IpConfiguration)
            }
        }
        val reconnect = wifi.connectionInfo?.networkId == config.networkId && p["option"] in setOf("ip", "macMode", "password")
        check(wifi.updateNetwork(config) == config.networkId) { "系统拒绝保存网络配置" }
        if (reconnect) { check(wifi.disconnect()); check(wifi.enableNetwork(config.networkId, true)); check(wifi.reconnect()) }
    }

    private fun preference(context: Context, wifi: WifiManager, option: String, enabled: Boolean) {
        if (option == "wake" && enabled) check(wifi.isScanAlwaysAvailable && context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled) { "需要开启位置信息和 WLAN 扫描" }
        val method = when (option) { "wake" -> "setAutoWakeupEnabled"; "notifier" -> "setOpenNetworkNotifierEnabled"; "wep" -> "setWepAllowed"; "scanAlways" -> "setScanAlwaysAvailable"; else -> error("Unknown preference") }
        if (has(wifi, method, Boolean::class.javaPrimitiveType!!)) wifi.javaClass.getMethod(method, Boolean::class.javaPrimitiveType).invoke(wifi, enabled)
        else {
            error("此 Android 版本不支持这项设置")
        }
    }

    private suspend fun snapshot(context: Context, wifi: WifiManager): WirelessSnapshot {
        val info = wifi.connectionInfo
        val connected = info != null && info.networkId >= 0 && info.supplicantState == SupplicantState.COMPLETED
        val f = mutableMapOf("enabled" to wifi.isWifiEnabled.toString(), "ssid" to if (connected) ssid(info.ssid) else "",
            "networkId" to (if (connected) info.networkId else -1).toString(), "bssid" to if (connected) info.bssid.orEmpty() else "")
        if (connected) {
            f.putAll(mapOf("frequency" to info.frequency.toString(), "speed" to info.linkSpeed.toString(), "txSpeed" to info.txLinkSpeedMbps.toString(),
                "rxSpeed" to info.rxLinkSpeedMbps.toString(), "mac" to info.macAddress.orEmpty(), "rssi" to info.rssi.toString(), "standard" to info.wifiStandard.toString()))
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = (if (has(wifi, "getCurrentNetwork")) call(wifi, "getCurrentNetwork") as? Network else null)
                ?: cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            val links = network?.let { cm.getLinkProperties(it) }
            val ipv4 = links?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            f["ip"] = ipv4?.address?.hostAddress.orEmpty()
            f["prefix"] = ipv4?.prefixLength?.toString().orEmpty()
            f["gateway"] = links?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress.orEmpty()
            f["dns"] = links?.dnsServers?.joinToString(", ") { it.hostAddress.orEmpty() }.orEmpty()
            f["ipv6"] = links?.linkAddresses?.filter { it.address is Inet6Address }?.joinToString("\n") { it.address.hostAddress.orEmpty().substringBefore('%') }.orEmpty()
        }
        // Optional APIs are reported individually. One unavailable preference must not erase the network list.
        try { if (has(wifi, "setAutoWakeupEnabled", Boolean::class.javaPrimitiveType!!)) f["wake"] = wifi.isAutoWakeupEnabled.toString() } catch (_: Exception) { f["wakeError"] = "无法读取此设置" }
        try {
            when {
                has(wifi, "isOpenNetworkNotifierEnabled", Executor::class.java, Consumer::class.java) -> {
                    val value = CompletableDeferred<Boolean>()
                    wifi.javaClass.getMethod("isOpenNetworkNotifierEnabled", Executor::class.java, Consumer::class.java).invoke(wifi, Executor { it.run() }, Consumer<Boolean> { value.complete(it) })
                    f["notifier"] = withTimeout(1_500) { value.await() }.toString()
                }
                has(wifi, "isOpenNetworkNotifierEnabled") -> f["notifier"] = call(wifi, "isOpenNetworkNotifierEnabled").toString()
                else -> f["notifierLegacy"] = "true"
            }
        } catch (e: CancellationException) { currentCoroutineContext().ensureActive(); f["notifierError"] = "无法读取此设置" }
        catch (_: Exception) { f["notifierError"] = "无法读取此设置" }
        try {
            if (has(wifi, "setWepAllowed", Boolean::class.javaPrimitiveType!!)) {
                f["wepSupported"] = wifi.isWepSupported.toString()
                val allowed = CompletableDeferred<Boolean>()
                wifi.javaClass.getMethod("queryWepAllowed", Executor::class.java, Consumer::class.java).invoke(wifi, Executor { it.run() }, Consumer<Boolean> { allowed.complete(it) })
                f["wep"] = withTimeout(1_500) { allowed.await() }.toString()
            }
        } catch (e: CancellationException) { currentCoroutineContext().ensureActive(); f["wepError"] = "无法读取此设置" }
        catch (_: Exception) { f["wepError"] = "此 Android 版本不支持这项设置" }
        f["directSupported"] = context.packageManager.hasSystemFeature("android.hardware.wifi.direct").toString()
        f["scanAlways"] = wifi.isScanAlwaysAvailable.toString()
        f["locationEnabled"] = context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled.toString()
        val randomMac = has(wifi, "isConnectedMacRandomizationSupported") && call(wifi, "isConnectedMacRandomizationSupported") == true
        val saved = wifi.configuredNetworks.orEmpty().distinctBy { it.networkId }.take(256).map { c ->
            WirelessItem(c.networkId.toString(), ssid(c.SSID), "saved", security(c), connected = connected && info.networkId == c.networkId, properties = configuration(c, randomMac))
        }
        val scans = if (!wifi.isWifiEnabled) emptyList() else wifi.scanResults.filter { it.SSID.isNotBlank() }
            .groupBy { it.SSID to security(it.capabilities) }.values.map { group -> group.maxBy { it.level } }.sortedByDescending { it.level }.take(256).map {
                WirelessItem(it.BSSID, it.SSID, "available", security(it.capabilities), "${it.level} dBm · ${it.frequency} MHz", connected && info.bssid == it.BSSID,
                    mapOf("rssi" to it.level.toString(), "frequency" to it.frequency.toString(), "standard" to it.wifiStandard.toString()))
            }
        return WirelessSnapshot(f, saved + scans)
    }

    private fun configuration(c: WifiConfiguration, randomMac: Boolean): Map<String, String> = buildMap {
        put("autoJoin", c.allowAutojoin.toString()); put("shared", c.shared.toString()); put("hidden", c.hiddenSSID.toString())
        try { put("metered", int(c, "meteredOverride").toString()) } catch (_: ReflectiveOperationException) { }
        try {
            put("macMode", if (has(c, "getMacRandomizationSetting")) c.macRandomizationSetting.toString() else int(c, "macRandomizationSetting").toString())
            put("macModes", if (!randomMac) "0" else if (Build.VERSION.SDK_INT >= 33) "0,1,2,3" else "0,1")
        } catch (_: ReflectiveOperationException) { }
        if (has(c, "isAllowedToUpdateByOtherUsers")) put("allowEdit", c.isAllowedToUpdateByOtherUsers.toString())
        try {
            val ip = call(c, "getIpConfiguration")!!
            put("ipMode", if (call(ip, "getIpAssignment").toString() == "STATIC") "static" else "dhcp")
            val proxy = c.httpProxy
            put("proxyMode", when (call(ip, "getProxySettings").toString()) { "STATIC" -> "manual"; "PAC" -> "pac"; else -> "none" })
            if (proxy != null) { put("host", proxy.host.orEmpty()); put("port", proxy.port.toString()); put("exclusions", proxy.exclusionList?.joinToString(",").orEmpty()); put("pac", proxy.pacFileUrl?.toString().orEmpty()) }
            (call(ip, "getStaticIpConfiguration") as? StaticIpConfiguration)?.let { s ->
                put("ip", s.ipAddress?.address?.hostAddress.orEmpty()); put("prefix", s.ipAddress?.prefixLength?.toString().orEmpty())
                put("gateway", s.gateway?.hostAddress.orEmpty()); put("dns", s.dnsServers.joinToString(",") { it.hostAddress.orEmpty() })
            }
        } catch (_: ReflectiveOperationException) { }
    }
    private fun security(c: WifiConfiguration) = when {
        c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP) || c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X) -> "enterprise"
        c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE) -> "wpa3"
        c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> "wpa2"
        c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE) -> "owe"
        c.wepKeys.any { it != null } -> "wep"
        else -> "open"
    }
    private fun security(s: String) = when { "EAP" in s -> "enterprise"; "SAE" in s -> "wpa3"; "PSK" in s -> "wpa2"; "WEP" in s -> "wep"; "OWE" in s -> "owe"; else -> "open" }
}
