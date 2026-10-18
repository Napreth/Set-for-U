function Run-OnAndroid {
    [CmdletBinding()]
    param (
        [Parameter(Mandatory = $true, Position = 0)]
        [ValidateNotNullOrEmpty()]
        [string]$Path
    )

    # Suppress successful external-command output; show it if a command fails.
    function Invoke-QuietCommand {
        param (
            [Parameter(Mandatory = $true)]
            [string]$Command,
            [string[]]$Arguments = @()
        )

        $output = & $Command @Arguments 2>&1
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            if ($output) { $output | Out-Host }
            throw "$Command failed (exit code $exitCode)."
        }
    }

    $buildDir = $null

    try {
        # Resolve the source file and check prerequisites.
        $scriptPath = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).ProviderPath
        if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf) -or
            [IO.Path]::GetExtension($scriptPath) -ine '.java') {
            throw "The path must point to an existing .java file: $Path"
        }

        foreach ($cmd in @('adb', 'javac', 'd8')) {
            if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
                throw "Command not found: $cmd"
            }
        }

        $deviceOutput = & adb devices 2>&1
        if ($LASTEXITCODE -ne 0) {
            $deviceOutput | Out-Host
            throw 'adb devices failed.'
        }
        $devices = @($deviceOutput | Select-String '^\S+\s+device\s*$')
        if ($devices.Count -eq 0) {
            throw 'No authorized Android device connected.'
        }
        if ($devices.Count -gt 1) {
            throw 'Multiple Android devices found. Disconnect the extras before running.'
        }

        $sdkRoot = if ($env:ANDROID_HOME) {
            $env:ANDROID_HOME
        } else {
            $env:ANDROID_SDK_ROOT
        }
        if (-not $sdkRoot) {
            throw 'ANDROID_HOME or ANDROID_SDK_ROOT is required.'
        }

        $androidJar = Get-ChildItem -Path (Join-Path $sdkRoot 'platforms\android-*\android.jar') `
            -File -ErrorAction SilentlyContinue |
            Sort-Object {
                $match = [regex]::Match($_.Directory.Name, '^android-(\d+)$')
                if ($match.Success) { [int]$match.Groups[1].Value } else { -1 }
            } -Descending |
            Select-Object -First 1 -ExpandProperty FullName
        if (-not $androidJar) {
            throw "Cannot find android.jar under $sdkRoot\platforms."
        }

        $scriptDir = Split-Path -Path $scriptPath -Parent
        $baseName = [IO.Path]::GetFileNameWithoutExtension($scriptPath)

        # Use a unique temporary directory, so an existing 'build' is never deleted.
        $buildDir = Join-Path $scriptDir ('.run-on-android-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $buildDir -ErrorAction Stop | Out-Null

        $outputDir = Join-Path $scriptDir 'output'
        New-Item -ItemType Directory -Path $outputDir -Force -ErrorAction Stop | Out-Null
        $logPath = Join-Path $outputDir 'logcat.log'

        # Compile the Java source.
        Invoke-QuietCommand 'javac' @(
            '--release', '8',
            '-classpath', "$androidJar;$scriptDir",
            '-d', $buildDir,
            $scriptPath
        )

        $classFiles = @(
            Get-ChildItem -LiteralPath $buildDir -Recurse -File -Filter '*.class' |
                Select-Object -ExpandProperty FullName
        )
        if ($classFiles.Count -eq 0) {
            throw 'javac did not generate any .class files.'
        }

        # Convert to one DEX file for app_process.
        Invoke-QuietCommand 'd8' (@('--output', $buildDir, '--lib', $androidJar) + $classFiles)
        $generatedDex = Join-Path $buildDir 'classes.dex'
        if (-not (Test-Path -LiteralPath $generatedDex -PathType Leaf)) {
            throw 'd8 did not generate classes.dex.'
        }
        if (@(Get-ChildItem -LiteralPath $buildDir -File -Filter '*.dex').Count -ne 1) {
            throw 'd8 generated multiple DEX files; a single DEX is required.'
        }

        $remoteDex = "/data/local/tmp/$baseName.dex"
        Invoke-QuietCommand 'adb' @('push', $generatedDex, $remoteDex)

        # Determine the fully qualified main class name.
        $source = Get-Content -LiteralPath $scriptPath -Raw -ErrorAction Stop
        $packageMatch = [regex]::Match($source, '(?m)^\s*package\s+([\w.]+)\s*;')
        $mainClass = $baseName
        if ($packageMatch.Success) {
            $mainClass = "$($packageMatch.Groups[1].Value).$baseName"
        }

        Invoke-QuietCommand 'adb' @('logcat', '-c')

        # Let the Java program print to the terminal as usual.
        & adb shell "CLASSPATH=$remoteDex app_process /system/bin $mainClass"
        $runExitCode = $LASTEXITCODE

        # Always attempt to capture logcat, even if the Java program failed.
        & adb logcat -d 2>&1 | Out-File -FilePath $logPath -Encoding utf8
        $logExitCode = $LASTEXITCODE
        if ($logExitCode -ne 0) {
            Write-Warning "Failed to save logcat (exit code $logExitCode)."
        }
        if ($runExitCode -ne 0) {
            throw "$mainClass exited with code $runExitCode. Log: $logPath"
        }
    }
    finally {
        if ($buildDir -and (Test-Path -LiteralPath $buildDir)) {
            Remove-Item -LiteralPath $buildDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
