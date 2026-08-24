param(
    [string]$Adb = "adb",
    [string]$DeviceLogRoot = "/data/misc/godmode"
)

$ErrorActionPreference = "Stop"
$logFile = "$DeviceLogRoot/godmodepro.log"
$metadata = & $Adb shell su -c "stat -c %a,%s $logFile" 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Persistent log is not readable: $logFile`n$($metadata -join [Environment]::NewLine)"
}

$metadataText = ($metadata -join " ").Trim()
if ($metadataText -notmatch '^600,[1-9][0-9]*$') {
    throw "Persistent log must be a non-empty owner-only 0600 file: $logFile ($metadataText)"
}

$lines = & $Adb shell su -c "tail -n 200 $logFile" 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Unable to read persistent log tail: $logFile`n$($lines -join [Environment]::NewLine)"
}

$recordPattern = '^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \d+ [VDIWEA?]/[^:]+: \[[^]]+\] '
$invalidLines = @($lines | Where-Object {
    $_ -and $_ -notmatch $recordPattern
})
if ($invalidLines.Count -gt 0) {
    throw "Persistent log contains malformed or multi-line records:`n$($invalidLines -join "`n")"
}

Write-Host "PERSISTENT_LOG_PASS $logFile mode=0600 nonempty format=single-line"
