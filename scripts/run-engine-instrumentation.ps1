param(
    [string]$Adb = "adb",
    [switch]$RequirePersistentLog
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$testApk = Join-Path $root "engine/build/outputs/apk/androidTest/debug/engine-debug-androidTest.apk"
$package = "com.kaisar.xposed.godmode.engine.test"
$runner = "$package/androidx.test.runner.AndroidJUnitRunner"
$activity = "com.kaisar.xposed.godmode.engine.applier.ModifyApplierTestActivity"
$testClass = "com.kaisar.xposed.godmode.engine.applier.ModifyApplierInstrumentedTest"
$deviceLogRoot = "/data/misc/godmode"
. (Join-Path $PSScriptRoot "foreground-instrumentation.ps1")
$tests = @(
    "applyAndRevokeRestoreCapturedBaseline",
    "revokePreservesPropertiesChangedByHost",
    "repeatedApplyRetainsFirstBaseline",
    "recycleRestoresBaselineBeforeNewBinding",
    "lateImageCannotOverwriteNewerRequest",
    "clearingActivityStateDropsLateImageWithoutViewWrite"
)

function Ensure-DeviceLogRoot {
    # 确保测试运行的日志前提存在，而非要求历史存在。
    # 返回值：$true=目录可用（既有或新建）；$false=当前环境建不出。
    $savedErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        # 1) 裸测：userdebug 模拟器 adbd 即 root，无需 su。
        & $Adb shell test -d $deviceLogRoot 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "DEVICE_LOG_ROOT $deviceLogRoot (pre-existing)"
            return $true
        }
        # 2) 升级手段：开发者真机经 su 建目录。
        & $Adb shell su -c "mkdir -p $deviceLogRoot" 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            & $Adb shell test -d $deviceLogRoot 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) {
                Write-Host "DEVICE_LOG_ROOT $deviceLogRoot (created)"
                return $true
            }
        }
    } finally {
        $ErrorActionPreference = $savedErrorAction
    }
    $unavailableMessage = "Device log root unavailable: "
    $unavailableMessage += $deviceLogRoot
    $unavailableMessage += ". Log-dependent assertions will be skipped unless -RequirePersistentLog is set."
    Write-Warning $unavailableMessage
    return $false
}

if (-not (Test-Path -LiteralPath $testApk)) {
    throw "Missing APK: $testApk"
}
$install = & $Adb install -r -t $testApk 2>&1
if ($LASTEXITCODE -ne 0 -or $install -notcontains "Success") {
    throw "APK install failed: $testApk`n$($install -join [Environment]::NewLine)"
}

# 日志前提检查放在安装之后：目录是“测试运行的前提”，不是仓库状态的前提。
$logRootReady = Ensure-DeviceLogRoot

$failed = @()
$infraFailed = @()
foreach ($test in $tests) {
    $passed = $false
    $lastInfraFailure = $false
    for ($attempt = 1; $attempt -le 3 -and -not $passed; $attempt++) {
        & $Adb shell am force-stop $package | Out-Null
        Start-Sleep -Seconds 1
        $result = Invoke-ForegroundInstrumentationAttempt `
            -Adb $Adb -Runner $runner -TestClass $testClass -TestName $test `
            -ForegroundPackage $package -ActivityName $activity `
            -CleanupPackages @($package)
        $passed = $result.Passed
        $outputText = $result.Output
        $infrastructureFailure = $result.InfrastructureFailure
        $lastInfraFailure = $infrastructureFailure
        if (-not $passed -and $infrastructureFailure -and $attempt -lt 3) {
            Write-Host "RETRY $test after device infrastructure interruption ($attempt/3)"
        } elseif (-not $passed) {
            Write-Host $outputText
            break
        }
    }
    if ($passed) {
        Write-Host "PASS $test"
    } elseif ($lastInfraFailure -and $outputText -notmatch "(?m)^FAILURES!!!") {
        $infraFailed += $test
    } else {
        $failed += $test
    }
}

if ($infraFailed.Count -gt 0 -or $failed.Count -gt 0) {
    $failureLines = @()
    if ($infraFailed.Count -gt 0) {
        $failureLines += "Infrastructure failures: "
        $failureLines += $infraFailed -join ", "
    }
    if ($failed.Count -gt 0) {
        $failureLines += "Assertion failures: "
        $failureLines += $failed -join ", "
    }
    throw ($failureLines -join [Environment]::NewLine)
}
if ($RequirePersistentLog) {
    if (!$logRootReady) {
        $gateMessage = "Persistent log gate requires an environment able to provide "
        $gateMessage += "$deviceLogRoot (rooted device or LSPosed host); the current device "
        $gateMessage += "cannot. This is an environment gap, not a product failure."
        throw $gateMessage
    }
    & (Join-Path $PSScriptRoot "assert-persistent-log.ps1") `
        -Adb $Adb -DeviceLogRoot $deviceLogRoot
} else {
    Write-Host "PERSISTENT_LOG_CHECK not requested; pass -RequirePersistentLog for file/format validation."
}
Write-Host "All $($tests.Count) engine instrumentation tests passed."
