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
    $ruleRecordSource = "app/src/main/java/com/kaisar/xposed/godmode/rule/RuleRecord.java"
    $ruleRecordAdapter = "app/src/main/java/com/kaisar/xposed/godmode/rule/RuleRecordTypeAdapter.java"
    if (!(Test-Path $ruleRecordSource) -or !(Test-Path $ruleRecordAdapter)) {
        $failures.Add("RuleRecord flat-wire compatibility implementation is missing")
    } else {
        $recordText = Get-Content -Raw $ruleRecordSource
        $adapterText = Get-Content -Raw $ruleRecordAdapter
        if ($recordText -notmatch "JsonAdapter\(RuleRecordTypeAdapter\.class\)" -or
            $recordText -notmatch "writeToParcel") {
            $failures.Add("RuleRecord flat JSON or Parcelable compatibility boundary is missing")
        }
        $stableFieldWireNames = @(
            "act_class", "view_class", "res_name", "depth", "item_path", "item_root_class",
            "parent_class", "repeatable", "text", "description", "match_mode", "view_type",
            "target_level", "rule_tag", "visibility", "mod_width", "mod_height", "mod_alpha",
            "mod_x_offset", "mod_y_offset", "mod_text", "mod_img_path", "orig_left_margin",
            "orig_top_margin"
        ) -join "|"
        $stableFieldPattern = 'SerializedName\("(' + $stableFieldWireNames + ')"\)'
        if ($recordText -match $stableFieldPattern) {
            $failures.Add("RuleRecord still declares a stable flat-field shadow")
        }
        if ($adapterText -match 'object\.add\("(matchSpec|effect)"') {
            $failures.Add("RuleRecord adapter emits a nested component key")
        }
    }

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
        "CurrentSaveCoordinator", "UNCERTAIN", "RuleOperationStore",
        "RuleMapper", "RuleMatchSpec", "ActionSpec", "RuleFields"
    ) -join "|"
    $forbidden = rg -n --glob "!**/build/**" $forbiddenPattern `
        app/src/main engine/src/main settings.gradle 2>$null
    if ($LASTEXITCODE -eq 0) {
        $failures.Add("Excluded production symbols found:`n$forbidden")
    } elseif ($LASTEXITCODE -ne 1) {
        $failures.Add("Unable to scan for excluded production symbols")
    }

    if ($failures.Count -gt 0) {
        $failures | ForEach-Object { Write-Error $_ }
        exit 1
    }

    Write-Host "Stabilization contract check passed."
    Write-Host "Baseline: $Baseline"
    Write-Host "AIDL: unchanged"
    Write-Host "RuleRecord: flat JSON and Parcelable boundaries present (golden tests enforce wire slots)"
    Write-Host "Modules: app, engine, libxservicemanager"
    Write-Host "libxservicemanager: $currentSubmodule"
    Write-Host "Excluded production symbols: absent"
} finally {
    Pop-Location
}
