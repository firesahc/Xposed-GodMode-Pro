function Bring-TestHostToForeground(
        [string]$Adb,
        [string]$packageName,
        [string]$activityName,
        [string]$testName) {
    $start = & $Adb shell su -c "am start -W -n $packageName/$activityName" 2>&1
    $startText = $start -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0 -or $startText -notmatch "Status: ok") {
        throw "Unable to foreground test host for $testName`n$startText"
    }
    for ($check = 0; $check -lt 20; $check++) {
        $activities = ((& $Adb shell dumpsys activity activities 2>&1) -join "`n") `
            -replace "\s+", " "
        if ($activities -match "topResumedActivity=.*$packageName/$activityName" -or
            $activities -match "ResumedActivity:.*$packageName/$activityName") {
            return
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Test host did not reach RESUMED for $testName`n$startText"
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
        [int]$timeoutMillis = 30000) {
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
            # am instrument restarts the target process, so the host must be foregrounded afterwards.
            Start-Sleep -Seconds 3
            Bring-TestHostToForeground $Adb $foregroundPackage $activityName $testName
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
            InfrastructureFailure = $null -ne $launchFailure
            Output = $outputText
        }
    } finally {
        $process.Dispose()
    }
}
