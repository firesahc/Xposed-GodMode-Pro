param(
    [string]$Adb = "adb",
    [switch]$RequirePersistentLog
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$debugApkDir = Join-Path $root "app/build/outputs/apk/debug"
$appApk = Get-ChildItem -LiteralPath $debugApkDir -Filter *.apk |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $appApk) {
    throw "Debug APK not found under $debugApkDir; assemble the app first."
}
$testApk = Join-Path $root "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
$targetPackage = "com.viewblocker.jrsen"
$testPackage = "com.viewblocker.jrsen.test"
$runner = "com.viewblocker.jrsen.test/androidx.test.runner.AndroidJUnitRunner"
$activity = "com.kaisar.xposed.godmode.orchestrator.ViewControllerTestActivity"
$deviceLogRoot = "/data/misc/godmode"
. (Join-Path $PSScriptRoot "foreground-instrumentation.ps1")

# 环境能力前置行：把“环境行不行”变成每次可见的事实，而不是藏在失败名单里。
$null = & $Adb shell echo device-ok 2>$null
$envShellOk = $LASTEXITCODE -eq 0
# 仅记录可选能力，不参与测试执行、重试或通过/失败判定。
$null = & $Adb shell su -c "true" 2>$null
$envSuOk = $LASTEXITCODE -eq 0
Write-Host "ENV_PROBE shell=$envShellOk su=$envSuOk"
$testSuites = @(
    @{
        TestClass = "com.kaisar.xposed.godmode.orchestrator.ViewControllerInstrumentedTest"
        Tests = @(
            "deletingVisibleModifyRuleRestoresOwnedProperties",
            "deletingRemoveRuleRestoresViewHiddenByThatRule",
            "recyclingDetachedItemClearsOwnerBeforeRebind",
            "repeatedApplyStillRestoresFirstBaseline",
            "revokePreservesHostValuesChangedAfterApply",
            "recreatedActivityCanApplyAndRevokeWithoutOldOwnerState",
            "deletingOneRuleDoesNotRevokeAnotherTargetWithSameAction"
        )
    },
    @{
        TestClass = "com.kaisar.xposed.godmode.editor.PanelResourceSelectionInstrumentedTest"
        Tests = @(
            "portraitInflationUsesVerticalToolbarColumn",
            "landscapeInflationUsesHorizontalToolbarColumn",
            "productionUiContextInflationUsesRequestedConfiguration",
            "immediateRebuildLeavesOnePanelAndOneMask",
            "configurationOverlayPreservesNonProjectedQualifiers"
        )
    },
    @{
        TestClass = "com.kaisar.xposed.godmode.editor.EditorOrchestratorConfigurationInstrumentedTest"
        Tests = @(
            "configChangedRebuildsSelectorAcrossOrientations",
            "layoutObservationAndConfigChangedConvergeToOnePanel",
            "configurationChangedDoesNotOpenHiddenPanel",
            "propertyEditorConfigurationChangedClosesCurrentSession"
        )
    }
)

function Ensure-DeviceLogRoot {
    # 确保测试运行的日志前提存在，而非要求历史存在。
    # 返回值：$true=目录可用（既有或新建）；$false=当前环境建不出。
    # 三出口与两种死因的报错在此彻底分开：
    #   无 su 二进制 vs 目录建失败，不再混成同一条 throw。
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
    $unavailableMessage = "Device log root unavailable: {0}. " -f $deviceLogRoot
    $unavailableMessage += "Log-dependent assertions will be skipped unless -RequirePersistentLog is set."
    Write-Warning $unavailableMessage
    return $false
}

foreach ($apk in @($appApk, $testApk)) {
    if (-not (Test-Path -LiteralPath $apk)) {
        throw "Missing APK: $apk"
    }
    $install = & $Adb install -r -t $apk 2>&1
    if ($LASTEXITCODE -ne 0 -or $install -notcontains "Success") {
        throw "APK install failed: $apk`n$($install -join [Environment]::NewLine)"
    }
}

# 日志前提检查放在安装之后：目录是“测试运行的前提”，不是仓库状态的前提。
$logRootReady = Ensure-DeviceLogRoot

$failed = @()
$infraFailed = @()
$totalTests = 0
foreach ($suite in $testSuites) {
    $testClass = $suite.TestClass
    foreach ($test in $suite.Tests) {
        $totalTests++
        $passed = $false
        $lastInfraFailure = $false
        for ($attempt = 1; $attempt -le 3 -and -not $passed; $attempt++) {
            & $Adb shell am force-stop com.kaisar.xposed.godmode.engine.test | Out-Null
            & $Adb shell am force-stop $targetPackage | Out-Null
            & $Adb shell am force-stop $testPackage | Out-Null
            Start-Sleep -Seconds 1

            $result = Invoke-ForegroundInstrumentationAttempt `
                -Adb $Adb -Runner $runner -TestClass $testClass -TestName $test `
                -ForegroundPackage $targetPackage -ActivityName $activity `
                -CleanupPackages @($targetPackage, $testPackage)
            $passed = $result.Passed
            $outputText = $result.Output
            $infrastructureFailure = $result.InfrastructureFailure
            $lastInfraFailure = $infrastructureFailure

            if (-not $passed -and $infrastructureFailure -and $attempt -lt 3) {
                Write-Host "RETRY $testClass::$test after device infrastructure interruption ($attempt/3)"
            } elseif (-not $passed) {
                Write-Host $outputText
                break
            }
        }
        if ($passed) {
            Write-Host "PASS $testClass::$test"
        } elseif ($lastInfraFailure -and $outputText -notmatch "(?m)^FAILURES!!!") {
            # 无执行痕迹的失败才算基础设施：跑起来并断言了的，以断言结论为准，
            # 即使前台化同时失败（前台缺失不改变已执行的断言事实）。
            $infraFailed += "$testClass::$test"
        } else {
            $failed += "$testClass::$test"
        }
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

Write-Host "All $totalTests app instrumentation tests passed."
