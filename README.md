# Set-for-U

Set-for-U 是一个 Android 系统与应用设置工具，Android 应用包名为 `com.napreth.set4u`。产品范围见 [DESIGN.md](DESIGN.md)，模块和执行协议见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## Core

Core 维护设置操作、设置条目和共享 ViewModel。SettingDefinition 是操作层定义，只保存稳定 ID、值类型、输入约束和实现。每个 SettingEntry 绑定一个 Definition，一个 Definition 可以没有 Entry，也可以有多个。Entry 使用独立 ID，保存显示资源、搜索词，并定义 Row 的呈现；运行时可以没有可显示的 Row。RowContainer 只组织 Row，不参与业务绑定。不同 Entry 共用 Setting ID 对应的状态、兼容规则和操作。没有界面的设置可以只注册 Definition，不需要创建空壳 Entry。

Row 分为 SettingRow 和 NavigationRow。开关、输入、下拉选择、单选选项、复选选项、动作和信息是 SettingRow 的控件形式。Row 本身不引用 Definition 或 Handler，执行绑定由 Entry 和 ViewModel 保存。单选列表不再是一种特殊容器，它是一组普通的选择 Row。

```kotlin
val entry = SettingEntry(notification, UiText.Key("notifications.title"))
class PermissionsPage : SettingsPage("permissions", UiText.Key("permissions.title")) {
    override val entries = listOf(entry)
    override fun layout(rows: PageRows) = listOf(
        RowContainer("permissions", rows.rows(entry)),
    )
}
val texts = TextResources(mapOf("zh-CN" to mapOf(
    "notifications.title" to "通知", "permissions.title" to "权限",
)))
val catalog = SettingsCatalog(PermissionsPage(), texts = texts)
val session = Core.open(bridge, catalog)
val vm = session.viewModels.settings(AppTarget(0, "com.example.app"), "permissions")
vm.enter().join()
val row = vm.state.value.items.single()
vm.dispatch(SettingsEvent.ChangeValue(row.id, SettingValue.BooleanValue(true), row.token))
```

SettingsPage.layout 安排容器与 Row，onEnter 查询已声明的 Entry。页面先显示，再逐项读取。EntryStyle 可以让同一条目显示为下拉行、选择行或只读信息。页面私有样式通过 SettingRow.rendererKey 表达，平台没有专用渲染器时仍能使用标准 control。

DynamicSettingEntry 接收 Flow<EntrySnapshot>。每个快照包含递增 revision、Row 和可选有效期。没有结果时，由具体 Entry 或页面选择显示提示或隐藏结果条目，框架不自动填充占位 Row。动态行参数放在 EntryRow 的 RowRequest 中，界面只提交 Row ID、意图和 token。列表更新、页面重建、离开页面或资源过期后，旧动态行不能再提交；已经排队的请求也会在执行前重新检查。动态数据源随可见页面取消，不会变成常驻扫描服务。Wi-Fi 的三网络示例见 DynamicEntriesTest，WLAN、蓝牙和热点在实际执行身份为 root 时使用内置页面，展示真实设备数据；非 root 默认打开系统对应页面。

操作经 SettingsService 校验后进入执行引擎，完成 inspect、check、prepare、apply、inspect、verify 和必要的 explain。prepare 重新检查执行资格，页面不负责设置后的验证。实际观测与请求目标分开保存，读取失败不会被当作关闭。

普通查询也可实现 Handler，通过 HandlerDefinition 注册并由 session.handlers.invoke 调用，自定义方法可以直接返回结构化数据。它与设置操作共用调度、超时、取消和执行证据；SettingHandler 仍走 SettingsService 的校验与回读流程。所有实现均使用工厂，实例不会跨操作或会话共享。页面和动态 Entry 的查询入口只接受只读 Handler。

文案位于独立资源表，业务定义不会随语言改变。构建任务 exportUiMetadata 从 Entry 和 NavigationEntry 导出全部语言、稳定设置索引及搜索词，随 APK 和桌面 JAR 打包，不收集动态 SSID。首页搜索框使用 session.search.search(query, locale)，跨语言匹配后按设备和页面能力过滤；热点、VPN 和证书等纯跳转入口也可搜索，不需要虚构设置定义。

兼容包区分 DeviceSupport 与 OperationAvailability。设备确实没有硬件、缺少当前权限、需要 root、需要系统跳转和探测失败分别表达。页面通过 UnsupportedVisibility 决定是否隐藏确认不支持的 Row；权限不足和未知状态不会自动隐藏。静态 ROM/API 规则继续选择具名后端，SupportProbe 补充运行时事实。

CompatibilityEvidence 保存声明、扫描和实时探测的来源、作用域、时间与证明类型。ResolutionPolicy 决定按哪种策略解析证据，ResolutionRule 可按设备、身份、UID、目标包和用户覆盖策略。前置条件满足不能证明行为可成功，也不能覆盖已知操作限制。session.scanner.scan(target) 只运行只读探测，其他测试记为 NotTested，返回结构化报告；主动测试和兼容规则发布尚未实现。

页面可实现 AvailabilityProbe。不同跳转行共享本轮检测结果，按自己的策略隐藏、置灰或保留入口，进入页面后重新检测。共享界面支持页面私有 Row 渲染器、惰性列表、加载错误和提交拒绝反馈。

## 构建

便携包和 APK 的构建命令、运行要求及产物说明见 [BUILDING.md](BUILDING.md)。版本框架位于 `core/compatibility`，已接入设置的读取、修改和页面状态。完整的函数选择及设备采集示例见 [CompatibilityTest.kt](set4u/core/src/test/kotlin/com/napreth/set4u/core/compatibility/CompatibilityTest.kt)。

项目使用 Gradle 9.3.1 和 Android Gradle Plugin 9.1.1。Android 构建需要 API 37 平台及相应 Build Tools，通过 ANDROID_HOME 或本机 SDK 配置提供 SDK 位置。

```powershell
.\gradlew.bat :core:test :desktop:test :android:assembleDebug
```

Core 测试也可以单独运行 `./gradlew :core:test`，需要 SDK 构建设备 DEX，但不需要连接设备。Android 和 Desktop 使用共享 Compose 页面，首页提供“网络与通信”，应用外观放在“关于”。WLAN、移动数据、蓝牙、NFC、飞行模式和私人 DNS 通过执行引擎读取、修改与回读验证；VPN、APN、SIM 管理与证书暂用系统界面，eSIM 放在移动数据子页。任务声明、构建产物和测试方式见 [BUILDING.md](BUILDING.md)。

设备任务使用 [DeviceTask.kt](set4u/core/src/main/kotlin/com/napreth/set4u/core/device/DeviceTask.kt) 中的抽象基类，以带 @DeviceEntry 的具体类作为入口。主机只引用 DeviceTaskDescriptor，设备端通过注册表校验协议、编解码并创建任务实例。[DeviceTaskTest.kt](set4u/core/src/test/kotlin/com/napreth/set4u/core/device/DeviceTaskTest.kt) 验证类注册、请求隔离、取消和错误处理；DEX 已随 APK 和桌面包构建；Session 内按构建版本与 SHA-256 缓存已校验的产物，每次调用仍启动独立设备进程，会话关闭时清理缓存。多语言索引已经接入构建；厂商后端按实际功能继续扩充。

“关于 → 外观 → 界面风格”可以切换 Google Material、Apple Cupertino、HyperOS Miuix、ColorOS COUI。两端分别使用 Material 3、Compose Cupertino、Miuix、COUI 的组件，共用页面和交互。Cupertino 的悬浮玻璃底栏使用 Compose Backdrop，低版本 Android 自动降级。外观页还可下拉选择跟随系统／浅色／深色主题，应用语言仍在关于首页选择。配置保存在本机，切换保留页面与设备会话。版本和平台限制见 [BUILDING.md](BUILDING.md)。

## Bridge

ADB、Shizuku 和 Local 是传输通道，Shell、Root、App 和 System 是执行身份。RootBridge 可以包装不同通道，连接时验证 UID 0，每次命令也带身份检查；未获得 root 时直接失败，不退回 shell。Android 的工作模式页提供 ADB（Shizuku）和 root，后者使用本机 su。Desktop 提供 ADB 和通过 ADB 调用 su 的 root。切换模式关闭旧 Session，创建新 Bridge 和 Session，观测与权限状态不会跨身份复用。

工作模式通过下拉菜单选择，“设备信息”进入独立页面。安卓端查看本机基本信息不需要额外授权，桌面端显示已连接设备的信息。

工作模式、状态、授权操作各占一行，授权一行右侧使用文字操作。失败直接在状态中显示原因，下面可重试。“关于 → 调试”中可开启“以 root 模拟 shell 身份”，非 root 工作模式下置灰；同页可用“始终使用内置设置页”覆盖默认系统跳转。开启模拟 shell 后使用 UID 2000 执行命令；切换会重建会话并重新判断能力，配置保存在本机。此模式需要 root 授权，不能替代授权。


Desktop 的 `AdbBridge(serial)` 固定绑定一台设备，`connect()` 检查连接及授权。命令通过本地 ADB server 的 shell-v2 协议执行，保留原始字节、独立 stderr 和退出码；设备发现、连接检查与文件 push/pull 使用本机 adb。adb 需要位于 PATH，也可以通过构造参数提供可执行文件、server 地址和端口。

Android 的 `ShizukuBridge(context)` 使用 Shizuku API 13.1.5 和非驻留 UserService。`connect()` 等待已授权连接，`connect(requestPermission = true)` 可发起授权请求。Manifest 已注册受权限保护的 Provider；UserService、AIDL 和文件描述符传输均位于 Android 应用模块内。

两端实现 execute、openProcess、单文件双向传输和连接状态。execute 默认保留两路合计最多 2 MiB 的 UTF-8 文本，超出后标记截断并继续排空；openProcess 提供二进制流，调用方需同时读取 stdout、stderr，并在使用后关闭。ShellRequest 的超时同样作用于 openProcess。文件默认临时写入后原子替换，上传会先回读校验 SHA-256。取消和关闭会尽力清理进程组及临时标记，关闭调用只等待有限期限，保留共享 ADB server、Shizuku 和其他客户端。断线时无法保证设备端清理已经完成，操作结果仍由 Core 回读判断。

```kotlin
// Desktop
val bridge = AdbBridge("emulator-5580")
bridge.connect()
val session = Core.open(bridge)
// Android 使用 ShizukuBridge(context)，在合适的页面发起 connect(requestPermission = true)。
```

## AVD 测试

`scripts/Test-Bridges.ps1` 只接受 emulator serial，并再次检查设备属性和 shell UID。AVD 需要运行于 shell 身份，安装 Do I Have 和 Shizuku 13.6。首次运行可以传入两个 APK；以后可省略 APK 参数，复用已经安装的应用。脚本会构建、运行 JVM 测试，再安装并运行 Android instrumentation；英文 Shizuku 授权弹窗由脚本在该 AVD 内确认。

```powershell
.\scripts\Test-Bridges.ps1 -Serial emulator-5580 `
    -TargetApk 'C:\apks\do-i-have.apk' `
    -ShizukuApk 'C:\apks\shizuku.apk'
```

脚本的 `GradleArguments` 可透传临时构建参数。如果 Gradle 选中了缺少 jlink 的精简 JRE，可在 JAVA_HOME 指向完整 JDK 时这样限定本次构建使用的安装路径。

```powershell
.\scripts\Test-Bridges.ps1 -Serial emulator-5580 -GradleArguments @(
    '--no-daemon',
    '-Dorg.gradle.java.installations.auto-detect=false',
    "-Dorg.gradle.java.installations.paths=$env:JAVA_HOME"
)
```
两端运行同一份 [BridgeContract.kt](set4u/core/src/bridgeTest/kotlin/com/napreth/set4u/testing/BridgeContract.kt)，覆盖命令转义、独立输出、退出码、大输出、二进制输入、Unicode 文件路径、超时、取消、提前关闭和客户端隔离。权限用例先实际采集设备信息，由兼容规则按 API level 选中设置项函数，再通过 SettingEntry 生成的开关 Row、共享 ViewModel 和执行引擎改变 `com.napreth.doihave` 的通知权限，回读验证，并确认 只读 Row 自动更新及未选中的函数未被调用。重复设置不会再次写入，信息条目拒绝修改；finally 中恢复原值并再次读取确认。Desktop 另有 shell-v2 协议测试，覆盖分片、二进制数据、拒绝授权和意外断线。

本次在 API 35 Google APIs x86_64 AVD 验证，Do I Have 为 0.0.1，Shizuku 为 13.6.0.r1086.2650830c。测试报告保存在 `build/reports/bridge-avd/`，Desktop 的 JUnit 报告位于 `set4u/desktop/build/reports/tests/test/`。普通构建不会自动连接设备；只有设置 `SET4U_TEST_ADB_SERIAL` 后才运行 Desktop 的 AVD 用例。API 23 和其他 ROM 尚未实测。
本地创建的测试 AVD 名为 `Set4U_API35`，保存在忽略提交的 `build/avd` 中。需要再次打开时，在项目目录执行以下命令，然后运行测试脚本。

```powershell
$env:ANDROID_AVD_HOME = (Resolve-Path .\build\avd).Path
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd Set4U_API35 -port 5580 -no-snapshot
```

在具备 su 的自有 userdebug AVD 上，可显式设置 SET4U_TEST_ROOT=1，再执行 Test-Bridges.ps1，追加运行 RootBridge 的同一份 Contract。默认构建和 CI 不会请求 root。普通 Google APIs AVD 的 su 允许 shell 调用，并不等于允许普通应用调用；Android 本机 su 授权还需要在有相应 root 管理器的设备上验证。


root 无线管理使用 WirelessQuery 读取结构化数据，WirelessTask 在独立设备进程中调用 Android API。Wi-Fi 支持扫描、开放及个人密码网络连接、已保存网络重连和忘记、断开及连接信息；企业网络配置保留系统入口。蓝牙支持经典蓝牙与 BLE 扫描、配对和取消配对、已配对设备连接与断开、设备重命名。热点使用 TetheringManager 启停实际共享，提供 WLAN、USB、蓝牙共享及 WLAN 热点名称、密码和频段配置。硬件、系统版本和厂商服务仍可能限制具体操作，实际结果由回读决定。

WLAN 首页把当前连接与扫描结果放在同一组，以副标题显示已连接或已保存；同名但安全类型不同的网络分别保留。自动开启、公共网络通知、证书和直连入口直接放在首页，WEP 与后台扫描开关放在高级 WLAN 设置里。

网络详情提供自动连接、多用户共享、修改权限、计费方式、MAC 隐私、隐藏网络、密码、代理与 DHCP/静态 IP 配置，以及二维码分享。修改走执行引擎并回读系统配置；静态 IP 切换还检查实际连接地址。网关、DNS、IPv6、收发速率和 MAC 地址从当前 WLAN 的 LinkProperties/WifiInfo 读取。缺少接口或硬件能力的选项会隐藏或禁用，例如 AVD 不支持连接时的 MAC 随机化。证书安装与 WLAN 直连使用系统处理页面。

普通页面不显示顶部加载条。静态条目不再重复启动数据源检查，基础设备事实在 Session 内复用，独立采集命令并发执行；打开网络首页也不会预读移动数据子页。页面刷新仍读取当前设置值，修改前的身份与权限检查不使用展示缓存。
