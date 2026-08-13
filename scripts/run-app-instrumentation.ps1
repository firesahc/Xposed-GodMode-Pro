param(
    [string]$Adb = "adb"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$appApk = Join-Path $root "app/build/outputs/apk/debug/app-debug.apk"
$testApk = Join-Path $root "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
$targetPackage = "com.viewblocker.jrsen"
$testPackage = "com.viewblocker.jrsen.test"
$runner = "com.viewblocker.jrsen.test/androidx.test.runner.AndroidJUnitRunner"
$activity = "com.kaisar.xposed.godmode.orchestrator.ViewControllerTestActivity"
$testClass = "com.kaisar.xposed.godmode.orchestrator.ViewControllerInstrumentedTest"
$tests = @(
    "deletingVisibleModifyRuleRestoresOwnedProperties",
    "deletingRemoveRuleRestoresViewHiddenByThatRule",
    "recyclingDetachedItemClearsOwnerBeforeRebind",
    "repeatedApplyStillRestoresFirstBaseline",
    "revokePreservesHostValuesChangedAfterApply",
    "recreatedActivityCanApplyAndRevokeWithoutOldOwnerState",
    "deletingOneRuleDoesNotRevokeAnotherTargetWithSameAction"
)

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
foreach ($test in $tests) {
    $passed = $false
    for ($attempt = 1; $attempt -le 3 -and -not $passed; $attempt++) {
        & $Adb shell am force-stop com.kaisar.xposed.godmode.engine.test | Out-Null
        & $Adb shell am force-stop $targetPackage | Out-Null
        & $Adb shell am force-stop $testPackage | Out-Null
        Start-Sleep -Seconds 1

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

        Start-Sleep -Seconds 3
        $start = & $Adb shell su -c "am start -W -n $targetPackage/$activity" 2>&1
        $startText = $start -join [Environment]::NewLine
        if ($LASTEXITCODE -ne 0 -or $startText -notmatch "Status: ok") {
            if (-not $process.HasExited) {
                $process.Kill()
            }
            throw "Unable to foreground test host for $test`n$startText"
        }

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

Write-Host "All $($tests.Count) app instrumentation tests passed."
