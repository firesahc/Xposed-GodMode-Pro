param(
    [string]$Baseline = "v6.8.0"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $repositoryRoot
try {
    $failures = [System.Collections.Generic.List[string]]::new()

    function Assert-GitPathUnchanged {
        param([string]$Path, [string]$Description)
        git diff --quiet $Baseline -- $Path
        if ($LASTEXITCODE -ne 0) {
            $failures.Add("$Description changed: $Path")
        }
    }

    Assert-GitPathUnchanged "app/src/main/aidl" "AIDL ABI"
    Assert-GitPathUnchanged `
        "app/src/main/java/com/kaisar/xposed/godmode/rule/RuleRecord.java" `
        "RuleRecord Parcelable layout"

    $baselineSubmodule = git rev-parse "$Baseline`:libxservicemanager"
    $currentSubmodule = git rev-parse "HEAD:libxservicemanager"
    if ($baselineSubmodule -ne $currentSubmodule) {
        $failures.Add("libxservicemanager gitlink changed")
    }

    $expectedModules = @("app", "engine", "libxservicemanager")
    $actualModules = Select-String -Path "settings.gradle" `
        -Pattern "^include ':([^']+)'$" | ForEach-Object {
            $_.Matches[0].Groups[1].Value
        }
    if (Compare-Object $expectedModules $actualModules) {
        $failures.Add("Gradle module topology changed")
    }

    $forbiddenPattern = @(
        "TargetPlan", "TargetEvaluator", "RuleBindingIndex", "RuleDocument",
        "CurrentSaveCoordinator", "UNCERTAIN", "RuleOperationStore"
    ) -join "|"
    $forbidden = rg -n --glob "!**/build/**" $forbiddenPattern `
        app/src/main engine/src/main settings.gradle 2>$null
    if ($LASTEXITCODE -eq 0) {
        $failures.Add("7.0-only production symbols found:`n$forbidden")
    } elseif ($LASTEXITCODE -ne 1) {
        $failures.Add("Unable to scan for 7.0-only production symbols")
    }

    if ($failures.Count -gt 0) {
        $failures | ForEach-Object { Write-Error $_ }
        exit 1
    }

    Write-Host "6.8 stabilization contract check passed."
    Write-Host "Baseline: $Baseline"
    Write-Host "AIDL and RuleRecord Parcelable sources: unchanged"
    Write-Host "Modules: app, engine, libxservicemanager"
    Write-Host "libxservicemanager: $currentSubmodule"
    Write-Host "7.0-only production symbols: absent"
} finally {
    Pop-Location
}
