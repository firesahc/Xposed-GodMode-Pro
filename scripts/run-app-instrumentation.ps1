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

function Assert-DeviceLogRoot {
    $savedErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = & $Adb shell su -c "ls -d $deviceLogRoot" 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorAction
    }
    if ($exitCode -ne 0 -or (($lines -join "`n") -notmatch [regex]::Escape($deviceLogRoot))) {
        throw "Device log root is missing: $deviceLogRoot"
    }
}

Assert-DeviceLogRoot
Write-Host "DEVICE_LOG_ROOT $deviceLogRoot"

foreach ($apk in @($appApk, $testApk)) {
    if (-not (Test-Path -LiteralPath $apk)) {
        throw "Missing APK: $apk"
    }
    $install = & $Adb install -r -t $apk 2>&1
    if ($LASTEXITCODE -ne 0 -or $install -notcontains "Success") {
        throw "APK install failed: $apk`n$($install -join [Environment]::NewLine)"
    }
}

$failed = @()
$totalTests = 0
foreach ($suite in $testSuites) {
    $testClass = $suite.TestClass
    foreach ($test in $suite.Tests) {
        $totalTests++
        $passed = $false
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

            if (-not $passed -and $infrastructureFailure -and $attempt -lt 3) {
                Write-Host "RETRY $testClass::$test after device infrastructure interruption ($attempt/3)"
            } elseif (-not $passed) {
                Write-Host $outputText
                break
            }
        }
        if ($passed) {
            Write-Host "PASS $testClass::$test"
        } else {
            $failed += "$testClass::$test"
        }
    }
}

if ($failed.Count -gt 0) {
    throw "Instrumentation failures: $($failed -join ', ')"
}

if ($RequirePersistentLog) {
    & (Join-Path $PSScriptRoot "assert-persistent-log.ps1") `
        -Adb $Adb -DeviceLogRoot $deviceLogRoot
} else {
    Write-Host "PERSISTENT_LOG_CHECK not requested; pass -RequirePersistentLog for file/format validation."
}

Write-Host "All $totalTests app instrumentation tests passed."
