$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $repositoryRoot
try {
    $failures = [System.Collections.Generic.List[string]]::new()

    function Get-JavaFiles {
        param([Parameter(Mandatory)][string[]]$SearchRoots)

        $files = [System.Collections.Generic.List[object]]::new()
        foreach ($searchRoot in $SearchRoots) {
            if (!(Test-Path -LiteralPath $searchRoot)) {
                $failures.Add("Architecture scan root is missing: $searchRoot")
                continue
            }
            Get-ChildItem -LiteralPath $searchRoot -Recurse -File -Filter *.java |
                Where-Object { $_.FullName -notmatch "[\\/]build[\\/]" } |
                ForEach-Object { [void]$files.Add($_) }
        }
        return $files.ToArray()
    }

    function Assert-NoImport {
        param(
            [Parameter(Mandatory)][string]$Description,
            [Parameter(Mandatory)][string[]]$SearchRoots,
            [Parameter(Mandatory)][string]$Pattern
        )

        $matches = [System.Collections.Generic.List[string]]::new()
        foreach ($file in Get-JavaFiles $SearchRoots) {
            Select-String -LiteralPath $file.FullName -Pattern $Pattern |
                ForEach-Object {
                    $relative = $file.FullName.Substring($repositoryRoot.Length + 1) -replace "\\", "/"
                    $matches.Add("{0}:{1}:{2}" -f $relative, $_.LineNumber, $_.Line.Trim())
                }
        }
        if ($matches.Count -gt 0) {
            $failures.Add("$Description`n$($matches -join "`n")")
        }
    }

    $editorRoot = "app/src/main/java/com/kaisar/xposed/godmode/editor"
    $orchestratorRoot = "app/src/main/java/com/kaisar/xposed/godmode/orchestrator"
    $controlRoot = "app/src/main/java/com/kaisar/xposed/godmode/control"
    $backupRoot = "app/src/main/java/com/kaisar/xposed/godmode/backup"
    $engineRoot = "engine/src/main/java"

    Assert-NoImport `
        -Description "Editor must depend on RuntimeRulePort, not runtime implementations" `
        -SearchRoots @($editorRoot) `
        -Pattern "^\s*import\s+com\.kaisar\.xposed\.godmode\.orchestrator\.(RuleLifecycleManager|ViewController)"

    Assert-NoImport `
        -Description "Control authority must not depend on editor workflow" `
        -SearchRoots @($controlRoot) `
        -Pattern "^\s*import\s+com\.kaisar\.xposed\.godmode\.editor\."

    Assert-NoImport `
        -Description "Backup workflow must not depend on control implementation" `
        -SearchRoots @($backupRoot) `
        -Pattern "^\s*import\s+com\.kaisar\.xposed\.godmode\.control\."

    Assert-NoImport `
        -Description "Engine must not depend on Xposed" `
        -SearchRoots @($engineRoot) `
        -Pattern "^\s*import\s+de\.robv\.android\.xposed\."

    Assert-NoImport `
        -Description "Editor and runtime must not directly depend on Xposed" `
        -SearchRoots @($editorRoot, $orchestratorRoot) `
        -Pattern "^\s*import\s+de\.robv\.android\.xposed\."

    foreach ($requiredPath in @(
        "app/src/main/java/com/kaisar/xposed/godmode/editor/RuntimeRulePort.java",
        "app/src/main/java/com/kaisar/xposed/godmode/inject/RuntimeRulePortAdapter.java",
        "app/src/main/java/com/kaisar/xposed/godmode/backup/RuleBackupManager.java",
        "app/src/main/java/com/kaisar/xposed/godmode/platform/xposed/XposedPackageManager.java",
        "app/src/main/java/com/kaisar/xposed/godmode/platform/xposed/XposedContextBaseAccessor.java"
    )) {
        if (!(Test-Path -LiteralPath $requiredPath)) {
            $failures.Add("Required architecture boundary file is missing: $requiredPath")
        }
    }

    foreach ($retiredPath in @(
        "app/src/main/java/com/kaisar/xposed/godmode/control/RuleBackupManager.java",
        "app/src/main/java/com/kaisar/xposed/godmode/control/PackageNameValidator.java",
        "app/src/main/java/com/kaisar/xposed/godmode/util/PackageManagerUtils.java"
    )) {
        if (Test-Path -LiteralPath $retiredPath) {
            $failures.Add("Retired architecture location remains: $retiredPath")
        }
    }

    if ($failures.Count -gt 0) {
        Write-Error ("Architecture boundary check failed:`n" + ($failures -join "`n"))
        exit 1
    }

    Write-Host "Architecture boundary check passed."
    Write-Host "Editor -> Runtime uses RuntimeRulePort"
    Write-Host "Control authority and backup workflow are separated"
    Write-Host "Xposed imports are confined to injection/platform adapters"
} finally {
    Pop-Location
}
