function Ensure-DeviceAwake(
        [string]$Adb) {
    # 设备规范步骤 1：亮屏并解除键guard。失败不阻断（CI 模拟器常已就绪）。
    & $Adb shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    & $Adb shell wm dismiss-keyguard 2>&1 | Out-Null
}

function Bring-TestHostToForeground(
        [string]$Adb,
        [string]$packageName,
        [string]$activityName,
        [string]$testName) {
    # 注意：自有可调试包的 am start 在 userdebug 模拟器上不需要任何特权，
    # 此处刻意不用 su -c（CI 镜像不保证 su 存在；此前因此全矩阵失败）。
    $start = & $Adb shell am start -W -n $packageName/$activityName 2>&1
    $startText = $start -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0 -or $startText -notmatch "Status: ok") {
        throw "Unable to foreground test host for $testName`n$startText"
    }
    # dumpsys 长组件名会被折行，先删全部空白再比对（组件名本身不含空白，
    # 模拟器行为不变，只容忍折行）。
    Confirm-TestHostResumed $Adb $packageName $activityName $testName 5000 `
        $startText
}

function Confirm-TestHostResumed(
        [string]$Adb,
        [string]$packageName,
        [string]$activityName,
        [string]$testName,
        [int]$budgetMillis,
        [string]$diagnostics = "") {
    # 只轮询不拉起：用于 instrument 启动后复核（自管前台的用例自己会拉起，
    # 此时再 am start 只会与用例的生命周期竞态）。
    $wanted = ("$packageName/$activityName") -replace "\s+", ""
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($timer.ElapsedMilliseconds -lt $budgetMillis) {
        $dump = ((& $Adb shell dumpsys activity activities 2>&1) -join "`n") `
            -replace "\s+", ""
        if ($dump -match "topResumedActivity=.*$wanted" -or
            $dump -match "ResumedActivity:.*$wanted") {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Test host did not reach RESUMED for $testName`n$diagnostics"
}

function Stop-InstrumentationAttempt(
        [string]$Adb,
        [System.Diagnostics.Process]$process,
        [string[]]$packages,
        [string]$runnerComponent) {
    $cleanupFailures = @()
    foreach ($packageName in $packages) {
        $cleanupOutput = & $Adb shell am force-stop $packageName 2>&1
        if ($LASTEXITCODE -ne 0) {
            $cleanupFailures += "force-stop $packageName failed: $($cleanupOutput -join ' ')"
        }
    }
    if (-not $process.HasExited -and -not $process.WaitForExit(5000)) {
        $process.Kill()
        if (-not $process.WaitForExit(5000)) {
            $cleanupFailures += "local adb process did not exit after Kill"
        }
    }

    $activityState = & $Adb shell dumpsys activity 2>&1
    if ($LASTEXITCODE -ne 0) {
        $cleanupFailures += "unable to verify device instrumentation cleanup"
    } else {
        $activityText = $activityState -join "`n"
        $marker = $activityText.IndexOf("Active instrumentation:")
        if ($marker -ge 0) {
            $activeSection = $activityText.Substring($marker)
            $sectionEnd = $activeSection.IndexOf("Active broadcasts:")
            if ($sectionEnd -ge 0) {
                $activeSection = $activeSection.Substring(0, $sectionEnd)
            }
            $runnerPackage = $runnerComponent.Split('/')[0]
            if ($activeSection -match [regex]::Escape($runnerPackage)) {
                $cleanupFailures += "device instrumentation is still active: $runnerComponent"
            }
        }
    }
    if ($cleanupFailures.Count -gt 0) {
        throw "Instrumentation cleanup is uncertain:`n$($cleanupFailures -join [Environment]::NewLine)"
    }
}

function Invoke-ForegroundInstrumentationAttempt(
        [string]$Adb,
        [string]$runner,
        [string]$testClass,
        [string]$testName,
        [string]$foregroundPackage,
        [string]$activityName,
        [string[]]$cleanupPackages,
        [int]$timeoutMillis = 30000,
        [switch]$foregroundFirst) {
    $arguments = @(
        "shell", "am", "instrument", "-r", "-w", "-e", "class",
        "$testClass#$testName", $runner
    )
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $Adb
    $process.StartInfo.Arguments = $arguments -join " "
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $attemptTimer = [System.Diagnostics.Stopwatch]::StartNew()
    [void]$process.Start()
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()

        $launchFailure = $null
        $timedOut = $false
        $cleanupPerformed = $false
        try {
            if ($foregroundFirst) {
                # 自管前台的套件（@Before 内 requireResumed）：先前台，instrument
                # 重启进程后只轮询复核、不再 am start，避免与用例生命周期竞态。
                Bring-TestHostToForeground $Adb $foregroundPackage $activityName $testName
            }
            $remainingMillis = $timeoutMillis - [int]$attemptTimer.ElapsedMilliseconds
            $timedOut = $remainingMillis -le 0 `
                -or -not $process.WaitForExit($remainingMillis)
        } catch {
            $launchFailure = $_.Exception.Message
        } finally {
            if ($timedOut -or $launchFailure -or -not $process.HasExited) {
                Stop-InstrumentationAttempt $Adb $process $cleanupPackages $runner
                $cleanupPerformed = $true
            }
        }

        $outputText = ($stdout.GetAwaiter().GetResult(), $stderr.GetAwaiter().GetResult() `
                | Where-Object { $_ }) -join [Environment]::NewLine
        if ($timedOut) {
            $outputText = "Instrumentation timed out`n$outputText"
        }
        if ($launchFailure) {
            $outputText = "Test host foreground failed: $launchFailure`n$outputText"
        }
        $successCount = [regex]::Matches($outputText, "(?m)^OK \(1 test\)\r?$").Count
        $terminalCount = [regex]::Matches(
                $outputText, "(?m)^INSTRUMENTATION_CODE: -1\r?$").Count
        $passed = -not $timedOut -and -not $launchFailure `
            -and $process.ExitCode -eq 0 `
            -and $successCount -eq 1 -and $terminalCount -eq 1 `
            -and $outputText -notmatch "(?m)^FAILURES!!!\r?$|shortMsg=Process crashed"

        if (-not $passed -and -not $cleanupPerformed) {
            Stop-InstrumentationAttempt $Adb $process $cleanupPackages $runner
        }

        return [pscustomobject]@{
            Passed = $passed
            # 超时没有可靠的测试终态，同样属于设备/测试基础设施失败，
            # 需要进入重试和 Infrastructure failures 分类，而不是伪装成断言失败。
            InfrastructureFailure = $timedOut -or ($null -ne $launchFailure)
            Output = $outputText
        }
    } finally {
        $process.Dispose()
    }
}
