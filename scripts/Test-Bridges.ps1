[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-\d+$')][string]$Serial,
    [string]$TargetApk,
    [string]$ShizukuApk,
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_.]+$')][string]$TargetPackage = 'com.napreth.doihave',
    [string[]]$GradleArguments = @()
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$reportDirectory = Join-Path $projectRoot 'build/reports/bridge-avd'
[IO.Directory]::CreateDirectory($reportDirectory) | Out-Null
$adbExecutable = (Get-Command adb -ErrorAction Stop).Source

function Invoke-Device {
    param([string[]]$Arguments)
    & $adbExecutable -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
}

if ((Invoke-Device @('shell', 'getprop', 'ro.kernel.qemu')).Trim() -ne '1') {
    throw 'Bridge integration tests require an Android emulator.'
}
if ((Invoke-Device @('shell', 'id', '-u')).Trim() -ne '2000') {
    throw 'Run this AVD with adbd as shell (adb unroot), then restart Shizuku.'
}
foreach ($apk in @($TargetApk, $ShizukuApk)) {
    if ($apk) { Invoke-Device @('install', '-r', (Resolve-Path -LiteralPath $apk).Path) }
}
Invoke-Device @('shell', 'pm', 'path', $TargetPackage) | Out-Null
$shizukuPath = (Invoke-Device @('shell', 'pm', 'path', 'moe.shizuku.privileged.api') | Select-Object -First 1).Trim()
if (!$shizukuPath.StartsWith('package:')) { throw 'Install Shizuku before running the tests.' }
$serverPid = & $adbExecutable -s $Serial shell pidof shizuku_server
if (!$serverPid) {
    $abi = (Invoke-Device @('shell', 'getprop', 'ro.product.cpu.abi')).Trim()
    $starter = $shizukuPath.Substring(8) -replace '/base\.apk$', "/lib/$abi/libshizuku.so"
    Invoke-Device @('shell', $starter)
}

$sdk = [int](Invoke-Device @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
$permission = if ($sdk -ge 33) { 'android.permission.POST_NOTIFICATIONS' } else { 'android.permission.CAMERA' }
function Get-NotificationPermission {
    $details = (Invoke-Device @('shell', 'dumpsys', 'package', $TargetPackage)) -join "`n"
    if ($details -notmatch "$([regex]::Escape($permission)): granted=(true|false)") {
        throw 'Target runtime permission was not found.'
    }
    return $Matches[1] -eq 'true'
}
$notificationBefore = Get-NotificationPermission
$previousSerial = $env:SET4U_TEST_ADB_SERIAL
$previousTarget = $env:SET4U_TEST_TARGET_PACKAGE
$runner = $null
Push-Location -LiteralPath $projectRoot
try {
    $env:SET4U_TEST_ADB_SERIAL = $Serial
    $env:SET4U_TEST_TARGET_PACKAGE = $TargetPackage
    $onWindows = [IO.Path]::DirectorySeparatorChar -eq '\'
    $gradleExecutable = if ($onWindows) { './gradlew.bat' } else { 'sh' }
    $launcherArguments = if ($onWindows) { @() } else { @('./gradlew') }
    & $gradleExecutable @launcherArguments :core:test :desktop:test :android:assembleDebug :android:assembleDebugAndroidTest --console=plain @GradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'Build or JVM tests failed.' }
    Invoke-Device @('install', '-r', 'set4u/android/build/outputs/apk/debug/android-debug.apk')
    Invoke-Device @('install', '-r', 'set4u/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk')

    $runnerLog = Join-Path $reportDirectory 'shizuku-instrumentation.txt'
    $runnerError = Join-Path $reportDirectory 'shizuku-instrumentation.stderr.txt'
    $runnerOptions = @{}
    if ($onWindows) { $runnerOptions.WindowStyle = 'Hidden' }
    $runner = Start-Process -FilePath $adbExecutable -ArgumentList @(
        '-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'requestShizukuPermission', 'true',
        '-e', 'targetPackage', $TargetPackage,
        '-e', 'class', 'com.napreth.set4u.bridge.ShizukuBridgeAvdTest',
        'com.napreth.set4u.test/androidx.test.runner.AndroidJUnitRunner'
    ) @runnerOptions -RedirectStandardOutput $runnerLog -RedirectStandardError $runnerError -PassThru
    $deadline = [DateTime]::UtcNow.AddMinutes(5)
    while (!$runner.HasExited) {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Android instrumentation timed out.' }
        # Only accept the observed Shizuku permission dialog for this app, on this AVD.
        & $adbExecutable -s $Serial shell uiautomator dump /sdcard/set4u-bridge-test-window.xml 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            try {
                [xml]$window = (& $adbExecutable -s $Serial shell cat /sdcard/set4u-bridge-test-window.xml) -join "`n"
                $title = $window.SelectSingleNode('//node[@package="moe.shizuku.privileged.api" and @resource-id="android:id/title" and @text="Allow Set-for-U to access Shizuku?"]')
                $allow = $window.SelectSingleNode('//node[@resource-id="android:id/button1" and @text="Allow all the time"]')
                if ($title -and $allow -and $allow.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
                    $x = [int]( ([int]$Matches[1] + [int]$Matches[3]) / 2 )
                    $y = [int]( ([int]$Matches[2] + [int]$Matches[4]) / 2 )
                    Invoke-Device @('shell', 'input', 'tap', "$x", "$y")
                }
            } catch [System.Xml.XmlException] { }
        }
        Start-Sleep -Milliseconds 200
    }
    $runner.WaitForExit()
    $result = Get-Content -LiteralPath $runnerLog -Raw
    if ($result -notmatch '(?m)^OK \((\d+) tests?\)\s*$') {
        Get-Content -LiteralPath $runnerLog -Tail 60
        throw "Android tests failed. See $runnerLog"
    }
    $androidTests = [int]$Matches[1]
    $desktopResults = Get-ChildItem 'set4u/desktop/build/test-results/test/TEST-*.xml' | ForEach-Object { ([xml](Get-Content -LiteralPath $_.FullName -Raw)).testsuite }
    $coreResults = Get-ChildItem 'set4u/core/build/test-results/test/TEST-*.xml' | ForEach-Object { ([xml](Get-Content -LiteralPath $_.FullName -Raw)).testsuite }
    $notificationAfter = Get-NotificationPermission
    if ($notificationAfter -ne $notificationBefore) { throw 'Do I Have permission baseline was not restored.' }
    $report = [ordered]@{
        timeUtc = [DateTime]::UtcNow.ToString('o')
        serial = $Serial
        fingerprint = (Invoke-Device @('shell', 'getprop', 'ro.build.fingerprint')).Trim()
        shellUid = (Invoke-Device @('shell', 'id', '-u')).Trim()
        coreTests = ($coreResults | Measure-Object -Property tests -Sum).Sum
        desktopTests = ($desktopResults | Measure-Object -Property tests -Sum).Sum
        androidTests = $androidTests
        targetPackage = $TargetPackage
        runtimePermission = $permission
        notificationPermissionBefore = $notificationBefore
        notificationPermissionAfter = $notificationAfter
        targetVersion = (Invoke-Device @('shell', 'dumpsys', 'package', $TargetPackage) | Select-String 'versionName=' | Select-Object -First 1).ToString().Trim()
        shizukuVersion = (Invoke-Device @('shell', 'dumpsys', 'package', 'moe.shizuku.privileged.api') | Select-String 'versionName=' | Select-Object -First 1).ToString().Trim()
    }
    $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reportDirectory 'summary.json') -Encoding utf8
    Write-Host "Passed: Core $($report.coreTests), Desktop $($report.desktopTests), Android $androidTests. Reports: $reportDirectory"
} finally {
    if ($runner -and !$runner.HasExited) {
        & $adbExecutable -s $Serial shell am force-stop com.napreth.set4u | Out-Null
        Stop-Process -Id $runner.Id -ErrorAction SilentlyContinue
    }
    & $adbExecutable -s $Serial shell rm -f /sdcard/set4u-bridge-test-window.xml | Out-Null
    $env:SET4U_TEST_ADB_SERIAL = $previousSerial
    $env:SET4U_TEST_TARGET_PACKAGE = $previousTarget
    Pop-Location
}
