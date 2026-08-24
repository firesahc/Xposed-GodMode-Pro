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

if (-not (Test-Path -LiteralPath $testApk)) {
    throw "Missing APK: $testApk"
}
$install = & $Adb install -r -t $testApk 2>&1
if ($LASTEXITCODE -ne 0 -or $install -notcontains "Success") {
    throw "APK install failed: $testApk`n$($install -join [Environment]::NewLine)"
}

$failed = @()
foreach ($test in $tests) {
    $passed = $false
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
        if (-not $passed -and $infrastructureFailure -and $attempt -lt 3) {
            Write-Host "RETRY $test after device infrastructure interruption ($attempt/3)"
        } elseif (-not $passed) {
            Write-Host $outputText
            break
        }
    }
    if ($passed) {
        Write-Host "PASS $test"
    } else {
        $failed += $test
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
Write-Host "All $($tests.Count) engine instrumentation tests passed."
