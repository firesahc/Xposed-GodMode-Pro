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

function Bring-TestHostToForeground([string]$packageName, [string]$activityName, [string]$test) {
    $start = & $Adb shell su -c "am start -W -n $packageName/$activityName" 2>&1
    $startText = $start -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0 -or $startText -notmatch "Status: ok") {
        throw "Unable to foreground test host for $test`n$startText"
    }
    for ($check = 0; $check -lt 20; $check++) {
        $activities = ((& $Adb shell dumpsys activity activities 2>&1) -join "`n") -replace "\s+", " "
        if ($activities -match "topResumedActivity=.*$packageName/$activityName" -or
            $activities -match "ResumedActivity:.*$packageName/$activityName") {
            return
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Test host did not reach RESUMED for $test`n$startText"
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
        Bring-TestHostToForeground $package $activity $test
        $arguments = @(
            "shell", "am", "instrument", "-r", "-w", "-e", "class",
            "$testClass#$test", $runner
        )
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo.FileName = $Adb
        $process.StartInfo.Arguments = $arguments -join " "
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()

        if (-not $process.WaitForExit(30000)) {
            $process.Kill()
            $outputText = "Instrumentation timed out"
        } else {
            $outputText = ($stdout.GetAwaiter().GetResult(), $stderr.GetAwaiter().GetResult() `
                    | Where-Object { $_ }) -join [Environment]::NewLine
        }
        $passed = $process.ExitCode -eq 0 `
            -and $outputText -match "OK \(1 test\)" `
            -and $outputText -match "INSTRUMENTATION_CODE: -1"
        $infrastructureFailure = $outputText -match "Test host Activity was not started" `
            -or $outputText -match "shortMsg=Process crashed" `
            -or $outputText -match "Instrumentation timed out"
        $process.Dispose()
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
