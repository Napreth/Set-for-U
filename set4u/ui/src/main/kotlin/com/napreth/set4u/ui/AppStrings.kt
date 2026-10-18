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
            "设置" to "Settings", "工作模式" to "Work mode", "关于" to "About", "返回" to "Back",
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
