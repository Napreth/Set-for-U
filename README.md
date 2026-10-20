# Set-for-U

**pre-alpha · 0.1.0**

Set-for-U 是一个 Android 系统与应用设置工具，提供 Android 应用和 Windows、Linux 桌面端。它把分散的设置组织在一起，读取当前状态、执行修改，再回读验证结果。Android 包名为 `com.napreth.set4u`。

项目仍处于早期开发阶段，目前主要实现网络与通信设置。验证以 AVD 为主，真实硬件、多用户和厂商 ROM 的适配还不完整。

## 当前功能

WLAN 内置页面支持扫描、连接、断开、已保存网络管理和二维码分享。网络详情提供自动连接、计费方式、MAC 隐私、隐藏网络、密码、代理和 DHCP／静态 IP 配置；WLAN 首页提供网络偏好，高级设置包含 WEP 和后台扫描开关。企业网络配置、证书安装和 WLAN 直连使用系统页面。

蓝牙内置页面提供经典蓝牙与 BLE 扫描、配对、取消配对、已配对设备连接与断开，以及设备重命名。热点页面提供 WLAN、USB 和蓝牙共享，以及 WLAN 热点名称、密码和频段配置。移动数据、NFC、飞行模式与私人 DNS 已接入设置执行与回读；SIM／eSIM 管理、APN 和 VPN 暂用系统页面。

具体操作受 Android 版本、硬件、ROM 和执行权限限制。WLAN、蓝牙和热点的内置管理需要实际 root 身份，非 root 默认打开系统对应页面；确认不支持的设置可按兼容性规则隐藏。普通页面在进入和返回应用时读取状态，不提供持续监控或自动恢复设置的后台服务。

Android 和桌面端共用 Compose 页面，提供 Google Material、Apple Cupertino、HyperOS Miuix、ColorOS COUI 四套界面风格，以及跟随系统／浅色／深色主题。外观设置位于“关于 → 外观”，应用语言在关于首页选择，支持简体中文、English 和跟随系统。设置搜索可跨语言匹配入口。

Cupertino、Miuix 和 COUI 使用社区 Compose 组件库，并非 Apple、小米或 OPPO 的官方系统控件。Cupertino 玻璃底栏会按平台能力降级。

## 运行与工作模式

Android 最低支持 API 23，各项设置有自己的版本与能力要求。工作模式页可以选择 Shizuku 或本机 root；使用 Shizuku 需要先启动服务并授权，使用 root 需要设备上的 su 授权。

桌面端通过 ADB 管理已授权的 Android 设备，也可以经 ADB 调用设备上的 su 使用 root。Windows、Linux 目前提供 x64 便携包，完整解压后运行 `Set-for-U.exe` 或 `./Set-for-U`。便携包自带 Java 21 和 ADB，无需另装 Java 或 Android SDK；Linux 图形界面需要相应桌面环境。

“关于 → 调试”提供“以 root 模拟 shell 身份”和“始终使用内置设置页”。模拟 shell 使用 UID 2000 的权限；强制打开内置页也不会补足当前身份缺少的权限。切换工作模式会重建会话，重新判断设备能力。

基础版本为 `0.1.0`。测试版使用 `0.1.0-debug`，APK 带测试签名；release 构建使用 `0.1.0`，APK 不签名，需要自行签名后安装。release 是构建类型，当前项目阶段仍为 pre-alpha。

## 从源码构建

需要 Python 3.11+、完整 JDK 25、Android API 37 平台和 Build Tools 36.0.0。通过 `ANDROID_HOME` 或本地 `local.properties` 配置 SDK；桌面构建同样需要 SDK 来生成设备端 DEX。

```powershell
# Windows：生成 Android APK、Windows 和 Linux 便携包
.\scripts\Build.ps1
# release 构建
.\scripts\Build.ps1 --build-type release
```

```sh
# Linux：生成 Linux 便携包和 Android APK
sh scripts/build.sh --targets linux-x64 android
```

产物位于 `build/dist/debug` 和 `build/dist/release`，包含 `manifest.json` 和 `SHA256SUMS`。离线构建、单独构建与运行命令见 [BUILDING.md](BUILDING.md)。

## 代码结构

| 路径 | 职责 |
| --- | --- |
| `set4u/core` | 设置定义、Handler、执行引擎、兼容性、设备任务、表单和共享 ViewModel |
| `set4u/android` | Android 应用宿主、Shizuku、本机 su、Manifest 与资源 |
| `set4u/desktop` | 桌面宿主、ADB、经 ADB 的 su 与桌面集成 |
| `set4u/ui` | 两端共同编译的 Compose 页面和主题组件源码 |

设置定义使用稳定 ID，页面通过 Entry 和 Row 组织呈现。查询与修改由框架调度，设置修改经过条件检查、执行和回读验证。兼容性包负责设备事实、规则匹配与操作实现的选择。

通用表单位于 `core/form`，业务表单放在所属功能目录，例如 `network/form`，Compose 渲染位于 `ui/form`。表单管理草稿、条件显隐、校验和请求生成，提交后的设备操作继续走原执行引擎。

设备端代码以继承 `DeviceTask`、带 `@DeviceEntry` 的类作为入口，可以和对应业务放在一起。构建自动生成 DEX 并随 APK、桌面包分发；会话内复用已校验的部署产物，每次调用启动独立进程。

产品范围见 [DESIGN.md](DESIGN.md)，模块、接口与执行协议见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 测试与兼容范围

```powershell
.\gradlew.bat :core:test :desktop:test :android:assembleDebug
```

普通测试不连接设备；显式指定 `SET4U_TEST_ADB_SERIAL` 后才运行桌面设备集成用例，root 用例还需要 `SET4U_TEST_ROOT=1`。`scripts/Test-Bridges.ps1` 用于 AVD 上的共享 Bridge Contract，`scripts/smoke-*-ui.py` 用于实际界面检查，准备方式和参数见 [BUILDING.md](BUILDING.md)。

目前已在 API 35、37 AVD 上验证不同部分的 Bridge、设置执行和界面行为。API 37 root AVD 还验证了 WLAN 配置往返、四套界面风格和表单交互。Windows 便携包已做图形界面检查，Linux 已做命令行自检，Linux GUI 尚未实测。AVD 结果不能代表蓝牙外设、实体 NFC、蜂窝数据、USB 共享及各厂商 ROM 已完成验证；API 23 虽有 CI 配置，本机尚未完成验证。

## 许可证

[MIT License](LICENSE) · 2026 · Napreth。第三方组件及运行库的许可证随应用和便携包分发。
