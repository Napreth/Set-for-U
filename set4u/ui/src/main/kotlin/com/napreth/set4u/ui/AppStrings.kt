package com.napreth.set4u.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Local app strings. Device pages use Core's TextResources with the same resolved locale. */
internal class AppStrings(val locale: String) {
    fun text(chinese: String): String = if (locale.startsWith("zh")) chinese else english[chinese] ?: chinese
    fun deviceField(key: String): String = if (locale.startsWith("zh")) deviceFields[key] ?: key else key
    val tabs get() = listOf("设置", "工作模式", "关于").map(::text)
    fun tabDescription(title: String) = if (locale.startsWith("zh")) "${title}选项卡" else "$title tab"

    companion object {
        private val deviceFields = mapOf(
            "Manufacturer" to "制造商", "Brand" to "品牌", "Model" to "型号", "Device" to "设备代号", "Product" to "产品代号",
            "Android" to "Android 版本", "API" to "API 级别", "Security patch" to "安全更新", "Vendor security patch" to "厂商安全更新",
            "Build ID" to "构建 ID", "Incremental build" to "增量版本", "Build display" to "构建版本", "Fingerprint" to "构建指纹",
            "Hardware / board" to "硬件平台", "SKU" to "SKU", "ODM SKU" to "ODM SKU", "SoC manufacturer" to "处理器厂商", "SoC model" to "处理器型号",
            "ABIs" to "支持的架构", "Bootloader" to "Bootloader", "Baseband" to "基带版本", "Kernel" to "内核版本", "Kernel build" to "内核构建",
            "Architecture" to "处理器架构", "Page size" to "内存页大小",
        )
        private val english = mapOf(
            "此设备不支持随机 MAC 地址" to "This device does not support MAC randomization",
            "网络" to "Networks", "WLAN 详情" to "WLAN details", "高级 WLAN 设置" to "Advanced WLAN settings",
            "自动开启 WLAN" to "Turn on WLAN automatically", "附近有已保存的网络时自动开启 WLAN" to "Turn on WLAN near saved networks",
            "附近有公共网络时发出通知" to "Notify about public networks", "安装证书" to "Install certificates", "WLAN 直连" to "Wi-Fi Direct",
            "WLAN 扫描" to "WLAN scanning", "关闭 WLAN 后仍允许系统扫描网络" to "Allow system scans while WLAN is off",
            "允许连接 WEP 网络" to "Allow WEP networks", "此设备不支持 WEP" to "This device does not support WEP",
            "WEP 是较旧的安全协议，其安全性较低" to "WEP is an older protocol with weak security",
            "此 Android 版本不支持这项设置" to "This Android version does not support this setting", "无法读取此设置" to "Unable to read this setting",
            "需要开启位置信息和 WLAN 扫描" to "Location and WLAN scanning must be enabled",
            "自动连接" to "Auto-connect", "共享网络" to "Share with other device users", "允许其他用户修改网络" to "Allow other users to edit",
            "按流量计费" to "Metered", "不按流量计费" to "Unmetered", "自动检测" to "Detect automatically",
            "隐私" to "Privacy", "使用设备 MAC 地址" to "Use device MAC", "使用随机 MAC 地址" to "Use randomized MAC",
            "每次连接使用随机 MAC 地址" to "Randomize MAC on every connection", "设备 MAC 地址" to "Device MAC address", "接入点 MAC 地址" to "Access point MAC address",
            "IPv4 地址" to "IPv4 address", "IPv6 地址" to "IPv6 addresses", "网关" to "Gateway", "子网掩码" to "Subnet mask",
            "传输链接速度" to "Transmit link speed", "接收链接速度" to "Receive link speed", "WLAN 标准" to "WLAN standard",
            "信号强度" to "Signal strength", "极佳" to "Excellent", "良好" to "Good", "一般" to "Fair", "较弱" to "Weak",
            "高级选项" to "Advanced options", "代理" to "Proxy", "无" to "None", "手动" to "Manual", "代理主机" to "Proxy host",
            "代理端口" to "Proxy port", "不使用代理的地址" to "Bypass proxy for", "PAC 地址" to "PAC URL",
            "IP 设置" to "IP settings", "静态" to "Static", "网络前缀长度" to "Network prefix length", "修改密码" to "Change password",
            "分享" to "Share", "扫描二维码连接网络" to "Scan to connect to this network", "无法分享网络" to "Unable to share network",
            "无法读取此网络的分享凭据" to "Unable to read sharing credentials for this network", "请选择有效选项" to "Choose a valid option",
            "请输入密码" to "Enter a password", "WEP 密码长度或格式无效" to "Invalid WEP password length or format",
            "请输入有效的代理主机" to "Enter a valid proxy host", "端口应为 1–65535" to "Port must be between 1 and 65535",
            "排除列表格式无效" to "Invalid bypass list", "请输入有效的 PAC 地址" to "Enter a valid PAC URL",
            "请输入有效的 IPv4 地址" to "Enter a valid IPv4 address", "网络前缀长度应为 1–32" to "Prefix length must be between 1 and 32",
            "请输入有效的网关" to "Enter a valid gateway", "请输入有效的 DNS 地址" to "Enter valid DNS addresses",
            "其他网络" to "Other networks", "网络详情" to "Network details", "没有已保存的网络" to "No saved networks",
            "已保存" to "Saved", "未连接" to "Not connected", "不在范围内" to "Out of range", "IP 地址" to "IP address",
            "信号" to "Signal", "开放网络" to "Open network", "企业网络" to "Enterprise network",
            "设置" to "Settings", "工作模式" to "Work mode", "关于" to "About", "返回" to "Back",
            "搜索设置" to "Search settings", "没有找到相关设置" to "No matching settings", "搜索失败，请重试" to "Search failed. Try again.",
            "列表已更新，请重试" to "The list changed. Try again.", "此设置正在修改，请稍后重试" to "This setting is being changed. Try again later.",
            "当前无法修改这项设置" to "This setting cannot be changed right now.", "操作未提交，请检查输入或重新进入页面" to "The request was not submitted. Check the value or reopen this page.",
            "页面暂不可用" to "This page is unavailable", "部分内容加载失败" to "Some content could not be loaded", "查看详情" to "Details", "页面加载失败" to "Page loading failed", "待更新" to "Not refreshed",
            "外观" to "Appearance", "调试" to "Debug", "始终使用内置设置页" to "Always use built-in settings pages",
            "非 root 模式下，部分功能可能不可用" to "Some features may be unavailable without root access.",
            "此页面的无线管理功能需要 root 权限" to "Wireless management on this page requires root access", "系统设置" to "System settings",
            "无线操作未完成" to "Wireless operation did not complete", "网络名称" to "Network name", "密码" to "Password", "新密码" to "New password",
            "配置热点" to "Configure hotspot", "关闭热点后可修改配置" to "Turn off the hotspot to edit its configuration",
            "WLAN 热点" to "Wi-Fi hotspot", "USB 网络共享" to "USB tethering", "蓝牙网络共享" to "Bluetooth tethering",
            "已连接" to "Connected", "断开连接" to "Disconnect", "连接" to "Connect", "取消配对" to "Unpair", "忘记网络" to "Forget network",
            "频率" to "Frequency", "连接速率" to "Link speed", "频段" to "Band", "自动" to "Automatic", "设备名称" to "Device name",
            "已保存网络" to "Saved networks", "已配对设备" to "Paired devices", "可用网络" to "Available networks", "附近设备" to "Nearby devices",
            "扫描" to "Scan", "正在扫描…" to "Scanning…", "未扫描到 Wi-Fi" to "No Wi-Fi networks found", "未扫描到蓝牙设备" to "No Bluetooth devices found",
            "添加网络" to "Add network", "连接网络" to "Connect to network", "安全性" to "Security", "隐藏网络" to "Hidden network", "是" to "Yes", "否" to "No",
            "网络名称应为 1–32 字节" to "Network names must contain 1–32 UTF-8 bytes", "密码应为 8–63 个字符" to "Passwords must contain 8–63 characters",
            "请输入有效的设备名称" to "Enter a valid device name",
            "应用设置" to "App settings", "界面风格" to "Interface style", "主题" to "Theme", "语言" to "Language",
            "跟随系统" to "System default", "浅色" to "Light", "深色" to "Dark",
            "版本" to "Version", "软件更新" to "Software updates", "源代码" to "Source code", "许可证" to "License",
            "状态" to "Status", "正在启用…" to "Enabling…", "已启用" to "Enabled", "待授权" to "Authorization needed", "未启用" to "Not enabled",
            "授权" to "Authorization", "获取权限" to "Get access", "重试" to "Retry", "重新获取" to "Get access again", "以 root 模拟 shell 身份" to "Run as shell using root",
            "设备序列号" to "Device serial",
            "关闭菜单" to "Close menu", "关闭对话框" to "Close dialog", "请先连接设备" to "Connect a device first", "未知" to "Unknown",
            "设备信息" to "Device information", "保存" to "Save", "关闭" to "Close", "取消" to "Cancel", "好" to "OK", "已选择" to "Selected",
            "请输入有效整数" to "Enter a valid integer", "不可用" to "Unavailable", "已开启" to "On", "已关闭" to "Off", "未设置" to "Not set",
            "页面暂时无法载入" to "Unable to load this page", "root（通过 ADB）" to "root (via ADB)", "ADB（Shizuku）" to "ADB (Shizuku)",
            "无法保存设置" to "Unable to save preferences", "请检查本机配置目录的写入权限。" to "Check write access to the local configuration directory.",
            "未获得 root 权限" to "Root access was not granted", "启用失败，请检查设备和授权" to "Unable to enable. Check the device and authorization.",
            "当前 root 实现无法切换到 shell 身份" to "This root provider cannot switch to the shell identity",
            "此设备不支持这项设置。" to "This device does not support this setting.", "没有可用的数据 SIM 卡。" to "No data SIM is available.",
            "此设置暂不可用，请检查工作模式和连接。" to "This setting is unavailable. Check the work mode and connection.",
            "未能完成修改" to "Unable to apply setting", "系统未确认这次修改，请检查当前设置。" to "The change could not be verified. Check the current setting.",
            "无法读取设备信息，请检查连接" to "Unable to read device information. Check the connection.",
            "无法打开链接" to "Unable to open link", "没有可用的浏览器" to "No browser is available",
            "无法打开设置" to "Unable to open settings", "设备不支持此入口，或当前连接不可用。" to "This screen is unsupported or the connection is unavailable.",
        )
    }
}

internal val LocalStrings = staticCompositionLocalOf { AppStrings("zh-CN") }
