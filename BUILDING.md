# 构建与运行

构建程序是 scripts/build.py，使用 Python 3.11 或以上版本的标准库。源码构建需要完整 JDK 25、Android API 37 平台和 Build Tools 36.0.0，通过 ANDROID_HOME 或 local.properties 的 sdk.dir 指定 SDK。桌面包同样会构建设备 DEX，因此也需要 SDK。基础版本号在 gradle.properties 中配置，当前为 0.1.0，Android versionCode 为 1。

```powershell
# Windows：默认构建测试版，包含两个桌面便携包及 APK
.\scripts\Build.ps1
# 正式版
.\scripts\Build.ps1 --build-type release
# 已缓存 Gradle 和打包依赖时
.\scripts\Build.ps1 --offline
```

```sh
# Linux
sh scripts/build.sh --targets linux-x64 android
sh scripts/build.sh --targets linux-x64 android --build-type release
```

--build-type 可选 debug、release，默认 debug。测试版版本名为 0.1.0-debug，Android 使用标准 debug 测试签名；正式版为 0.1.0，Android 不启用调试、不配置签名，输出 unsigned APK。正式 APK 需要签名后才能安装。桌面包的版本名同样遵循此规则，Windows EXE 的数值产品版本为 0.1.0。

--targets 可选 windows-x64、linux-x64、android；--skip-tests 省略 JVM 测试，--skip-build 重新包装对应类型的已有 Gradle 输出。普通构建不连接设备，AVD 测试单独使用 scripts/Test-Bridges.ps1。

产物分别位于 build/dist/debug 和 build/dist/release，每种类型有独立的打包暂存目录和校验清单。

| 文件 | 用法 |
| --- | --- |
| Set-for-U-版本-windows-x64.zip | 完整解压后运行 Set-for-U.exe |
| Set-for-U-版本-linux-x64.tar.gz | 完整解压后运行 ./Set-for-U |
| Set-for-U-0.1.0-debug-android.apk | 测试版，已使用测试签名，可直接安装 |
| Set-for-U-0.1.0-android-unsigned.apk | 正式版，未签名 |
| manifest.json、SHA256SUMS | 本次产物的大小、版本与校验值 |

桌面包包含 Temurin Java 21 和 ADB 37.0.0，运行时无需安装 Java 或 Android SDK。下载来源、版本及 SHA-256 固定在 scripts/runtime-lock.json，依赖缓存放在 build/downloads；运行库的许可证与 ADB NOTICE 随包保留。Linux GUI 需要 glibc 桌面及 X11 或 XWayland 库。桌面安装包后续接入。

直接用 Gradle 构建 Android 时，assembleDebug 和 assembleRelease 各自决定版本后缀与签名。桌面 Gradle 构建默认使用测试版，正式版传 -Pset4u.buildType=release，例如 ./gradlew :desktop:stagePortable -Pset4u.buildType=release。

Windows EXE 启动器通过 jpackage 在 Windows 上创建。Linux 便携包直接组装 JRE、应用 JAR、ADB 和启动脚本，保留文件权限与符号链接，因此也可以在 Windows 上生成。构建不会清空整个 build 目录，只替换对应的打包暂存目录。

两端当前提供设置、工作模式和关于选项卡。Android 工作模式页支持 Shizuku 和本机 su，桌面端支持 ADB 和经 ADB 的 root，设备信息从工作模式页进入独立页面；软件更新入口打开 GitHub Releases，源代码入口打开项目仓库。两端使用 Compose，共用 set4u/ui 中的页面与主题适配代码，复用 Core 的页面、定义与 ViewModel。桌面端还可以在命令行读取目标设备信息。

界面使用 Kotlin 2.4.10、Compose Multiplatform 1.11.1、Material 3 1.9.0、Compose Cupertino 2.3.1、Miuix 0.8.5、COUI 1.1.0 和 Backdrop 2.0.0-alpha02。固定版本是为了保持 Compose 与 Kotlin 二进制兼容；Miuix 和 Backdrop 仍可能变更 API，升级时需要验证四套风格。依赖来自 Google Maven 和 Maven Central，不再使用 JitPack。DeviceTask 单独使用 Maven 中的 D8/R8 9.1.56，以支持 Kotlin 2.4 元数据；Android SDK Build Tools 仍用于 APK 构建。

主题入口为“关于 → 外观 → 界面风格”，默认 Cupertino。Android 最低 API 23；API 31 以下使用不透明 Cupertino 底栏，API 31 起支持背景模糊，折射还取决于运行时 Shader 支持。桌面包包含 Windows 和 Linux 的 Skiko 运行库，通过运行平台选择加载，不要求安装 Java。Cupertino、Miuix 和 COUI 是社区组件库，并非 Apple、小米或 OPPO 的官方系统控件。APK 的 assets/licenses、桌面 JAR 的 licenses 目录和便携包的 licenses 目录包含组件说明与许可证。

```text
Set-for-U-cli.cmd --device emulator-5580 --profile
./Set-for-U --device emulator-5580 --profile
Set-for-U-cli.cmd --self-test --gui
./Set-for-U --self-test
```

设备信息始终通过目标设备的 Bridge 采集。便携包优先使用自带 ADB，开发时也可以在 CLI 传 --adb 指定可执行文件。

.github/workflows/build.yml 分别构建 Windows、Linux 的测试版和正式版，并执行 Linux 便携包自检。本机验证包括 Windows 运行时、GUI 创建、连接 AVD，以及 Android APK 启动、Shizuku 授权和设备信息页。Linux 命令行自检及 ADB 在 WSL 的临时 Ubuntu 根目录中验证；Linux GUI 尚未实测。Core、Desktop、Android 的 AVD 测试结果见 build/reports/bridge-avd/summary.json。

## AVD 测试

Bridge Contract 使用专用 AVD，adb、PowerShell 7 和构建环境需要可用。脚本检查模拟器属性，要求 adbd 以 shell 身份运行，并安装 Shizuku 和测试目标应用。默认目标为 Do I Have（com.napreth.doihave）；首次运行可传入两个 APK，后续省略 APK 参数即可复用已安装的应用。

```powershell
.\scripts\Test-Bridges.ps1 -Serial emulator-5580 `
    -TargetApk 'C:\apks\do-i-have.apk' `
    -ShizukuApk 'C:\apks\shizuku.apk'
```

测试会构建并执行桌面与 Android 的共享 Contract，设置修改在 finally 中恢复，报告位于 build/reports/bridge-avd。-TargetPackage 可指定其他测试包。具备 su 的测试 AVD 可额外设置 SET4U_TEST_ROOT=1，追加 root Contract；应用自身仍需获得 root 授权。

scripts/smoke-*-ui.py 检查实际界面，具体脚本与覆盖范围见下文。root 无线检查需要应用已获授权，不能用 adb shell 的 root 身份代替。多个 UIAutomator 脚本应顺序运行。

## DeviceTask 构建与调用

任务可以继续放在所属业务目录。设备入口是带 @DeviceEntry 的 DeviceTask 子类，注解的 spec 指向独立 DeviceTaskSpec 类；Spec 提供共享描述和 codec，不引用任务实现。入口与 Spec 都需要公开无参构造函数，设备操作放在 execute 中。

```kotlin
// SettingHandler 的 inspect 或 apply 内
val result = context.executeTask(
    RuntimeProbeSpec().descriptor,
    RuntimeProbeRequest("hello"),
)
```

单独执行 ./gradlew :core:buildDeviceTasks 会扫描入口、校验依赖、生成注册表并运行 D8。输出在 set4u/core/build/device-tasks/debug，正式构建在 release；device-input.jar 是 D8 输入，resources/device/set4u-device.jar 是可执行 DEX，device-tasks.json 保存入口及 SHA-256，host-excludes.txt 记录从宿主 JAR 剥离的类。普通 APK 和桌面构建自动依赖这一步。

构建扫描只读取字节码，不执行设置、任务或 Spec 来发现条目。它拒绝重复 ID、缺失注解、错误继承、无有效构造函数、宿主引用设备实现，以及设备任务引用平台 UI、共享 ViewModel 或本机会话。字符串反射与 JNI 不在静态检查能力内；新引入的第三方设备依赖也需要单独验证。任务 DEX 包包含 Kotlin 与协程运行库，当前不做 R8 缩减。D8 参数参见 [官方文档](https://developer.android.com/tools/d8)。

Android 从 APK 资源取出 DEX，经 Shizuku 上传并以 shell 身份调用；Desktop 使用相同资源，通过 ADB 执行。同一 Session、设备、构建版本和 SHA-256 的产物只部署一次，上传后流式回读校验，再原子替换并设为只读。每次任务仍启动独立进程并有独立期限，结束后清理进程组，会话关闭清理缓存目录。build-version.txt 随设备资源记录构建版本。请求、响应各限 1 MiB，stderr 保留上限为 8 KiB。设备任务返回成功后，设置引擎仍照常回读并验证设置结果。

构建逻辑测试使用 ./gradlew -p buildSrc test。API 35 AVD 已验证两端 DEX 调用、shell UID、目标上下文、协议拒绝、超时清理、输出截断和重连旧回调，测试仍默认使用 Do I Have，结束后恢复权限原值。

CI 增加 API 23、35 模拟器任务，使用仓库中的 test-fixtures/target 测试应用及固定版本、固定 SHA-256 的官方 Shizuku APK，不依赖本机 Do I Have 文件。Test-Bridges.ps1 现可在 PowerShell 7 的 Windows、Linux 环境运行；-TargetPackage 可指定测试包，API 33 以下验证相机权限，API 33 以上验证通知权限。CI 配置尚未在远端运行，API 23、多用户和厂商 ROM 也尚未完成本机验证，不能据此宣称已经兼容。

# 兼容规则接入

DeviceInfoCollector 采集原始 getprop 与内核、架构和页大小，DeviceProfile 保存不可变观测。基础画像在 Session 内缓存，页面刷新或连接失效会清除缓存，也可调用 session.compatibility.deviceProfile(refresh = true) 重采集。原始属性可用于 ROM、SOTA 等厂商信息，专用解析器及系统组件采集按实际设置扩展。

设置通过 operationFunctions 注册返回 SettingHandler 的具名函数，例如 mapOf("api33" to ::android33, "api35" to ::android35)。兼容包声明设置 ID、匹配条件、使用哪个函数或明确不支持，传入 Core.open 的 compatibility 参数。

```kotlin
val rules = CompatibilityRegistry(listOf(
    CompatibilityRule("notifications.api33", notification.id,
        DeviceCondition.IntegerRange(DeviceFields.Sdk, minimum = 33),
        RuleDecision.Use("api33")),
    CompatibilityRule("notifications.api35", notification.id,
        DeviceCondition.IntegerRange(DeviceFields.Sdk, minimum = 35),
        RuleDecision.Use("api35"), replaces = setOf("notifications.api33")),
))
val session = Core.open(bridge, catalog, compatibility = rules)
```

示例仅说明函数选择，不代表某项实际系统功能的兼容表。条件支持相等、整数范围、版本范围、日期范围、与、或、非；VersionParser 可处理厂商版本格式。replaces 声明明确覆盖关系，不按注册顺序或隐含优先级选函数。重复 ID、无效引用和循环在注册时拒绝；规则冲突、版本未知或选中未注册的函数时返回 Unknown，不自动尝试另一条操作路径。

读取和修改都经过解析，一次操作固定使用同一套 inspect/check/apply/verify/explain。确认不支持的条目及空容器从渲染结果中隐藏，未知状态保留并显示原因；直接调用 SettingsService.set 同样受规则保护。binding 和 operationFunctions 均接收工厂，不保存 Handler 实例；具名实现由规则选择。一次修改的前读、执行和验证使用同一个新实例。

# 图标

assets/icon 保留用户原图、去纹理后的图片、多尺寸 ICO 和生成提示词。图像编辑使用内置 image_gen，后续尺寸及格式转换由 scripts/generate-icons.py 完成，需要 Pillow。普通构建使用已提交的资源，不依赖 Pillow。Android 使用密度图标及带安全边距的自适应图标，Desktop 使用窗口 PNG 和 Windows EXE 内嵌 ICO。

本地 SDK 路径写入 local.properties，该文件已忽略且不进入 Git 索引。便携包及 APK 只从明确的构建输出组装，不包含本地配置文件。分享源码时使用 Git 跟踪文件生成归档，避免直接压缩包含 build 和本地配置的整个工作目录。

网络集成用例在 API 30 以上 AVD 读写 WLAN、飞行模式和私人 DNS，finally 中恢复原值；较低 API 跳过该用例。DEX 集成用例连续调用三次并验证只传输一次、产生三个独立进程和会话结束后的清理。两端共用这些用例。

准备好 Shizuku 和 APK 后，可执行 python scripts/smoke-network-ui.py --serial emulator-5580 检查 Android 真实界面。脚本限定 AVD，进入网络页、点击 WLAN 开关并检查回读反馈，再打开私人 DNS；finally 中恢复 WLAN。截图、页面树和结果位于 build/reports/network-ui。该检查已加入 API 35 CI 配置，远端执行结果仍需由 CI 确认。

无线设置新增共用 Bridge Contract，验证蓝牙实际状态、NFC 缺失后的隐藏与直接调用拦截，以及有可用默认 SIM 时移动数据的修改和回读。没有 SIM 的 AVD 单独验证只读保护，并在报告中明确未执行数据开关往返。移动数据 DeviceTask 与协议位于 core/network，产物由现有 DEX 任务扫描和打包。

网络后端首轮 API 35 AVD 验证通过 Core 109、Desktop 19、Android 17 项测试。蓝牙完成实际开关往返和回读；AVD 没有 NFC，已验证条目隐藏和直接调用拦截；本机 AVD 基带未提供有效默认数据 SIM，移动数据实际验证了只读保护，有卡的设备写入往返尚未完成。模拟测试覆盖用户开关修改、回读和默认订阅切换。增加 --radios 可让 smoke-network-ui.py 验证蓝牙界面和 NFC 隐藏，并明确记录是否执行了移动数据写入往返。

## 文案和索引

:core:exportUiMetadata 读取静态 Entry 元数据，生成 core/build/ui-metadata/debug/metadata；release 构建生成到 release。texts.json 保存各语言资源，settings-index.json 保存规范 Entry 的设置 ID、资源引用和搜索词，settings-search.properties 是运行时词表。普通 JAR 和 APK 构建自动依赖此任务，动态 Flow 不参与导出。使用 -Pset4u.buildType=release 可以单独生成正式版元数据。

RootBridge 的 AVD Contract 仅在 SET4U_TEST_ROOT=1 且显式选择 emulator serial 时启用。它使用 AVD 现有 su，不调用 adb root，也不修改 ADB server 的身份。Android 本机 su 模式需要设备实际授予应用 root，不能用 shell 获得 root 的结果替代这一项验证。

四套风格的 AVD 冒烟使用 `python scripts/smoke-themes-ui.py --serial emulator-5580`，需要先安装测试 APK 并启动 Shizuku。脚本检查主题即时切换、重启保存、页面返回与选项卡、许可证弹窗，以及每套主题下的 WLAN 修改和回读，结束时恢复 WLAN。截图和结果写入 build/reports/themes-ui。编译阶段可先关闭 AVD，完成后再启动测试，以减少内存占用。

本轮 Core 130 项、桌面本地 8 项测试通过。API 35 AVD 验证了四套风格的下拉选择、浅深色、跟随系统、中英文应用及 Core 页面、重启保存、许可证弹窗、WLAN 往返与 NFC 隐藏，WLAN 和系统夜间模式已恢复。两版 Windows 便携包使用自带 Java 21 通过四套风格的浅深色及中英文渲染和 GUI 宿主自检；两版 Linux 包的 CLI 自检通过。新版设备 DEX 分别通过 ADB / root 的实际执行、目标传递、缓存与清理测试。Linux GUI 和 API 23 UI 尚未在本机实测。本次使用的 Set4U_API35 AVD 已关闭。

COUI 使用社区库 io.github.suqi8.coui.kmp:coui-ui:1.1.0（Apache-2.0），不是 OPPO 官方 SDK。它依赖 Compose 1.11.1 / Kotlin 2.4.10，两个平台使用相同版本。应用配置入口位于“关于”，界面风格、主题模式和语言使用下拉菜单。smoke-themes-ui.py 覆盖四种风格、浅深色、应用及 Core 页面中英文切换、重启持久化与 WLAN 修改后回读。

DeviceTask 与 AGP 均使用 R8/D8 9.1.56，以支持 Kotlin 2.4 元数据。

应用菜单与设备导航检查使用 `python scripts/smoke-app-ui.py --serial emulator-5554`，可用 `--theme cupertino` 限定风格。脚本通过 ADB 点击真实屏幕，覆盖外观下拉菜单、空白和返回关闭、许可证、工作模式下拉框，以及设备信息子页和两种返回操作；同时保存实际截图并检查主窗口焦点，不能只凭 UIAutomator 中存在菜单节点判断成功。设备信息会与 getprop 返回的型号比较。脚本不修改工作模式或无线设置，结束时恢复原外观配置，结果在 build/reports/app-ui/<serial>。

菜单修复已在 API 37 AVD 完成四套风格的上述检查，在 API 35 AVD 完成 Cupertino 检查。桌面本地 10 项测试通过，另有 33 项设备集成用例按未指定目标跳过；本次没有重跑未改动的 Core 和 Bridge Contract。两版 Windows 便携包使用自带 Java 21 通过四套风格的浅深色、菜单、许可证渲染及 GUI 宿主自检，Linux 包通过 CLI 自检，Linux GUI 本次未实测。

HyperOS Miuix、ColorOS COUI 的字体与名称调整在 API 37 AVD 检查了中英文、浅深色和下拉菜单，并在启动器确认了缩小后的图标；原外观与工作模式已恢复。Android 自适应图标通过 drawable/ic_launcher_foreground.xml 的 20% 留白控制图案大小，原始图片不需要重新生成。桌面便携包重新通过自带 Java 21 的界面检查和 Linux CLI 自检。

RootShellBridgeAvdTest 复用 BridgeContract，显式设置 SET4U_TEST_ADB_SERIAL 和 SET4U_TEST_ROOT=1 才运行。API 37 AVD 已通过普通 root、模拟 shell 的真实 UID/GID、参数转义、DEX、文件传输、超时及取消清理共 7 项定向检查。Core 133 项通过；这些后端检查沿用上轮结果，本轮没有修改 Bridge。

工作模式页面改为三行，第三行左侧“授权”、右侧文字操作，模拟 shell 开关现已移到“关于 → 调试”。桌面本地 12 项测试通过，覆盖失败状态、超时、重试和四套主题的布局。API 37 AVD 验证了四套主题下启动、重试及切换模拟 shell 时的失败原因直接显示在状态中，不弹错误对话框；Shizuku 成功启用后清除旧原因，重启后仍正常。测试结束恢复原工作模式和外观配置。两版 Windows 便携包通过自带 Java 21 的四套主题浅深色渲染及 GUI 自检，Linux 包通过 CLI 自检，Linux GUI 未实测。


## Handler 与兼容性框架回归

HandlerRuntimeTest 验证自定义查询、结构化返回值、工厂实例隔离、共享队列、取消、超时和调用结束后的上下文失效。ResolutionPolicyTest 覆盖证据来源、作用域、冲突、过期、缓存及只读扫描；PageAvailabilityTest 覆盖跳转检测共享、重新进入、拒绝反馈，以及查询和修改的独立权限。FrameworkUiTest 在 Compose 场景中实际点击私有 Row，并检查回退渲染、加载错误、长列表惰性组合和提交拒绝。

本轮 Core 152 项、桌面本地 13 项测试通过，51 项设备用例因普通测试未指定设备而跳过。单独指定 API 37 AVD 后，GenericHandlerAvdTest 通过真实 ADB 选择实现、执行查询并返回结构化结果。该测试没有修改无线设置。测试结果保存在 build/reports/architecture。

运行 `python scripts/smoke-search-ui.py --serial emulator-5580` 可验证四套界面风格下的跨语言入口搜索、清空搜索、证书索引和 VPN 系统跳转，结果位于 build/reports/search-ui。API 37 AVD 本轮已通过，外观配置在结束时恢复；只读检查也确认 WLAN 显示与设备状态一致，NFC 的显示与设备报告的 feature 一致。这里使用的 API 37 AVD 声明了 NFC feature，不能把它当作实体 NFC 硬件验证。CI 已加入搜索冒烟，远端结果尚待实际运行。

root Wi-Fi 扫描和连接已在后续无线页面实现；会修改系统的自动兼容性实测仍未接入。普通扫描只运行 ReadOnly Probe，其他测试保留 NotTested；报告不会自动变成发布规则。

两版 Windows 便携包使用自带 Java 21 通过四套风格浅深色渲染、私有 Row 点击与 GUI 宿主自检；Linux 两版通过版本、Core 自检和随包 ADB 自检，Linux GUI 本轮未实测。测试用只读 AVD 实例已关闭，没有保存对原 AVD 的更改。


## 无线页面验证

`WirelessFrameworkTest` 检查独立设备信息并发采集、页面刷新缓存、写入前重新检查身份、密码脱敏与设备协议。`WirelessNavigationTest` 检查实际身份分流、强制内置页、从关于返回设备页面后的分流及子页返回；四套主题的本地渲染测试已迁移到外观和调试子页。

准备好已授权 root 的 AVD，设置 SET4U_TEST_ADB_SERIAL 和 SET4U_TEST_ROOT=1 后，执行 `:desktop:test --tests '*WirelessAvdTest'`。用例验证真实 API 查询、Wi-Fi 扫描、蓝牙经典与 BLE 发现、测试网络删除、蓝牙名称修改、Wi-Fi 断开重连和实际热点共享启停。修改有回读，结束恢复名称、连接与共享状态；连接往返仅使用模拟器已连接的 AndroidWifi，测试创建的网络按完整名称清理，不处理其他网络。AVD 没有蓝牙外设，配对与外设连接、USB 共享及真实厂商 ROM 仍需要对应设备验证。

`python scripts/smoke-wireless-ui.py --serial emulator-5554 --root` 要求应用 UID 的 root 已授权，检查内置无线页、非 root 系统跳转、调试覆盖和四套风格的外观、调试布局。省略 --root 时只检查非 root 路由与应用设置，适用于普通 CI 模拟器。测试恢复原工作模式、模拟 shell、内置页偏好和外观，报告在 build/reports/wireless-ui。不要并行运行多个 UIAutomator 脚本。

无线页面本轮 Core 157 项、桌面本地 14 项通过，设备用例在普通本地测试中按未选择目标跳过。API 37 AVD 的三项 root 集成用例通过；Android 界面验证了 root 无线页、蓝牙开关回读往返、系统跳转、调试覆盖及偏好恢复，四套风格的外观与调试页面均已截图检查。

最终 APK 另行验证了从系统设置返回后的无线数据重读：在外部改变蓝牙状态后，内置开关和扫描控件同步更新，结束恢复原状态。两版 Windows 便携包使用自带 Java 21 通过四套主题、无线密码表单保护、私有 Row 和 GUI 自检；Linux 两版通过 CLI 自检，Linux GUI 本轮未实测。

WLAN 列表调整后，Core 161 项、桌面本地 15 项测试通过。WifiNetworksTest 覆盖当前网络去重、保存记录与扫描结果合并、同名不同安全类型、关闭 WLAN 后的缓存和详情状态更新；WifiPresentationTest 检查四套主题首页与详情的信息分配。API 37 AVD 在本机 root 模式下验证了四套主题的当前网络唯一显示、详情与保存列表往返、扫描后去重，截图和记录在 build/reports/wifi-layout。没有修改网络连接或保存记录，结束恢复外观偏好。两种构建类型的 Android、Windows 和 Linux 产物均已重新打包。

WLAN 配置通过 WifiTask 调用系统 Wi-Fi 服务，协议版本为 2。WifiConfigurationValuesTest 检查静态地址、代理、密码与二维码转义，WifiPresentationTest 检查四套主题的布局、表单内下拉菜单和实际渲染二维码的解码。Core 164 项、桌面本地 17 项测试通过。

显式设置 SET4U_TEST_ADB_SERIAL 和 SET4U_TEST_ROOT=1 后，运行 `:desktop:test --tests '*WifiConfigurationAvdTest'` 可验证网络配置与偏好写入。API 37 AVD 两项用例通过，包括当前 AndroidWifi 的静态 IP/DHCP 往返、临时网络上的代理/PAC、计费、多用户共享、隐藏网络、密码修改和分享，以及通知、扫描、WEP 策略的回读与恢复。报告保存在 build/reports/wifi-config-avd。该 AVD 报告不支持连接 MAC 随机化，因此只验证了设备 MAC 选项，界面禁用随机化选项；没有用模拟结果代替硬件能力。

`python scripts/smoke-wifi-settings-ui.py --serial emulator-5554` 要求本机 root 已授权，检查首页、详情、代理和 IP 表单、二维码、高级 WLAN 设置，并通过界面修改和恢复开关。报告与截图位于 build/reports/wifi-settings-ui。它不安装证书，也不连接额外的 WLAN 或直连设备。

API 37 AVD 已完成四套风格的实际点击检查，覆盖网络唯一显示、详情、代理和静态 IP 表单、偏好与高级页面。自动连接、公共网络通知、自动开启和扫描开关通过界面修改后回读，结束恢复原值；最终 APK 也验证了首次进入时的 WLAN 开关状态，以及证书安装和 WLAN 直连的系统页面跳转。`--read-only` 可跳过开关修改。Windows 便携包使用自带 Java 21 通过了表单与二维码的三项定向测试；两种构建类型的 APK、Windows 和 Linux 便携包均已更新。

FormSessionTest 覆盖隐藏草稿、字段与跨字段校验、提交互斥、取消、关闭及敏感值的字符串表示。WirelessFormsTest 检查 64 位 WPA2 密钥、条件字段的提交参数和重新编辑时的初始值，FormRenderingTest 检查四套风格的内嵌、自定义与默认字段渲染，以及对话框编辑会话的生命周期。普通本地测试不会提交设备设置。

统一表单本轮 Core 173 项、桌面本地 19 项测试通过。便携包自带 Java 21 通过五项表单与二维码测试，API 37 AVD 通过四套风格的空网络名校验、开放网络密码显隐、代理和静态 IP 表单检查。设备检查使用 `--read-only`，没有提交有效的网络修改，结束恢复原外观与工作模式。记录保存在 build/reports/forms。
