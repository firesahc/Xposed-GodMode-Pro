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

    function Invoke-SourceSearch {
        param(
            [Parameter(Mandatory)]
            [string]$Pattern,
            [Parameter(Mandatory)]
            [string[]]$SearchRoots,
            [string]$IncludeFileName,
            [string[]]$ExcludedFileNames = @(),
            [switch]$ExcludeBuildDirectories
        )

        $matches = [System.Collections.Generic.List[string]]::new()
        $visitedFiles = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::Ordinal
        )
        try {
            foreach ($searchRoot in $SearchRoots) {
                $rootItem = Get-Item -LiteralPath $searchRoot -ErrorAction Stop
                $candidateFiles = if ($rootItem.PSIsContainer) {
                    Get-ChildItem -LiteralPath $rootItem.FullName -Recurse -File -ErrorAction Stop
                } else {
                    @($rootItem)
                }
                foreach ($candidateFile in $candidateFiles) {
                    if (!$visitedFiles.Add($candidateFile.FullName)) {
                        continue
                    }
                    if ($IncludeFileName -and $candidateFile.Name -notlike $IncludeFileName) {
                        continue
                    }
                    if ($ExcludedFileNames -contains $candidateFile.Name) {
                        continue
                    }

                    $relativePath = [System.IO.Path]::GetRelativePath($repositoryRoot, $candidateFile.FullName)
                    $normalizedPath = $relativePath -replace "\\", "/"
                    if ($ExcludeBuildDirectories -and $normalizedPath -match "(^|/)build(/|$)") {
                        continue
                    }

                    Select-String -LiteralPath $candidateFile.FullName -Pattern $Pattern -ErrorAction Stop |
                        ForEach-Object {
                            $matchText = "{0}:{1}:{2}" -f @($normalizedPath, $_.LineNumber, $_.Line)
                            [void]$matches.Add($matchText)
                        }
                }
            }
            return [pscustomobject]@{
                Succeeded = $true
                Matches = $matches.ToArray()
                Error = $null
            }
        } catch {
            return [pscustomobject]@{
                Succeeded = $false
                Matches = @()
                Error = $_.Exception.Message
            }
        }
    }

    $ruleRecordAidl = "app/src/main/aidl/com/kaisar/xposed/godmode/rule/RuleRecord.aidl"
    Assert-GitPathUnchanged $ruleRecordAidl "RuleRecord parcel ABI"
    $legacyAidlPaths = @(
        "app/src/main/aidl/com/kaisar/xposed/godmode/IGodModeManager.aidl",
        "app/src/main/aidl/com/kaisar/xposed/godmode/IObserver.aidl"
    )
    foreach ($legacyAidlPath in $legacyAidlPaths) {
        if (Test-Path $legacyAidlPath) {
            $failures.Add("Retired IPC contract still present: $legacyAidlPath")
        }
    }
    foreach ($unusedParcelableAidl in @(
        "app/src/main/aidl/com/kaisar/xposed/godmode/rule/ActRules.aidl",
        "app/src/main/aidl/com/kaisar/xposed/godmode/rule/AppRules.aidl"
    )) {
        if (Test-Path $unusedParcelableAidl) {
            $failures.Add("Unused Parcelable AIDL declaration still present: $unusedParcelableAidl")
        }
    }
    $serviceAidl = "app/src/main/aidl/com/kaisar/xposed/godmode/ipc/contract/IRuleService.aidl"
    $observerAidl = "app/src/main/aidl/com/kaisar/xposed/godmode/ipc/contract/IRuleObserver.aidl"
    if (!(Test-Path $serviceAidl) -or !(Test-Path $observerAidl)) {
        $failures.Add("6.10 canonical IPC contract is missing")
    } else {
        $serviceAidlText = Get-Content -Raw $serviceAidl
        $observerAidlText = Get-Content -Raw $observerAidl
        foreach ($requiredServiceMethod in @(
            "ServiceIdentityParcel\s+getServiceIdentity\s*\(\s*\)",
            "ObserverRegistrationParcel\s+addObserver\s*\(",
            "OperationLeaseParcel\s+openOperation\s*\(\s*int\s+operationType\s*,\s*String\s+packageName",
            "String\s+getToolbarHiddenItems\s*\(\s*String\s+packageName\s*\)",
            "RuleMutationResult\s+mutate\s*\(",
            "UndoStateParcel\s+getUndoState\s*\(",
            "UndoResultParcel\s+undoLatest\s*\(",
            "ParcelFileDescriptor\s+openImageFileDescriptor\s*\("
        )) {
            if ($serviceAidlText -notmatch $requiredServiceMethod) {
                $failures.Add("Canonical IRuleService method is missing: $requiredServiceMethod")
            }
        }
        if ($serviceAidlText -match "getProtocolDescriptor|getProtocolVersion|isInEditMode|finishAssetWrite|\bint\s+getServiceState") {
            $failures.Add("Retired IRuleService transaction remains")
        }
        if ($serviceAidlText -match "AssetWriteSession|openAssetWrite|discardAssetWrite") {
            $failures.Add("Retired remote asset session remains in IRuleService")
        }
        if ($observerAidlText -notmatch "onEditModeChanged\s*\(\s*boolean\s+enable\s*,\s*long\s+editRevision" -or
            $observerAidlText -notmatch "onRulesInvalidated\s*\(\s*String\s+packageName\s*,\s*long\s+generation") {
            $failures.Add("IRuleObserver is missing revisioned invalidation fields")
        }
    }
    $contractSource = "app/src/main/java/com/kaisar/xposed/godmode/ipc/RuleServiceContract.java"
    if (!(Test-Path $contractSource) -or
        (Get-Content -Raw $contractSource) -notmatch "PROTOCOL_VERSION\s*=\s*61000" -or
        (Get-Content -Raw $contractSource) -notmatch [regex]::Escape("BUILD_VERSION_CODE = BuildConfig.VERSION_CODE") -or
        (Get-Content -Raw $contractSource) -notmatch "OP_MUTATION\s*=\s*3" -or
        (Get-Content -Raw $contractSource) -notmatch 'CONTRACT_FINGERPRINT' -or
        (Get-Content -Raw $contractSource) -notmatch 'iruleservice-61000-fd-mutate-v3') {
        $failures.Add("6.10 IPC protocol identity is missing")
    }
    $mutationRequest = "app/src/main/java/com/kaisar/xposed/godmode/ipc/contract/RuleMutationRequest.java"
    if (!(Test-Path $mutationRequest)) {
        $failures.Add("RuleMutationRequest is missing")
    } else {
        $mutationText = Get-Content -Raw $mutationRequest
        foreach ($fdField in @("ParcelFileDescriptor mainImageFd", "ParcelFileDescriptor modifiedImageFd")) {
            if ($mutationText -notmatch [regex]::Escape($fdField)) {
                $failures.Add("FD mutate request field is missing: $fdField")
            }
        }
        if ($mutationText -notmatch "boolean\s+captureUndo") {
            $failures.Add("Undoable mutate request flag is missing")
        }
        if ($mutationText -match "AssetSessionId|PARCELABLE_WRITE_RETURN_VALUE") {
            $failures.Add("RuleMutationRequest still contains remote asset-session semantics")
        }
    }
    # Build identity contract: versionCode must stay derived from the git commit
    # counter; release naming is gated on an exact v* tag over a clean worktree,
    # and the in-tree versionName stays a baseline-plus-dev-suffix composition.
    $buildGradleText = Get-Content -Raw "app/build.gradle"
    if ($buildGradleText -notmatch "gitCapture\(\['rev-list',\s*'--count'") {
        $failures.Add("App versionCode must be derived from the git commit count (rev-list --count)")
    }
    if ($buildGradleText -notmatch [regex]::Escape("describe', '--exact-match', '--tags', '--match', 'v*")) {
        $failures.Add("Release builds must be gated on an exact v* tag (git describe --exact-match --tags)")
    }
    if ($buildGradleText -notmatch 'versionName\s+"\$\{versionBaseline\}\$\{devSuffix\}"') {
        $failures.Add("App versionName must stay composed as baseline plus dev suffix slot")
    }
    $snapshotParcel = "app/src/main/java/com/kaisar/xposed/godmode/ipc/contract/RuleSnapshotParcel.java"
    if (!(Test-Path $snapshotParcel)) {
        $failures.Add("RuleSnapshotParcel is missing; SharedMemory must not cross Binder裸传")
    } else {
        $snapshotText = Get-Content -Raw $snapshotParcel
        foreach ($field in @("status", "packageName", "generation", "payloadLength", "sha256", "memory")) {
            if ($snapshotText -notmatch "\b$([regex]::Escape($field))\b") {
                $failures.Add("RuleSnapshotParcel is missing validated field: $field")
            }
        }
    }

    # IPC must expose the envelope types. AIDL Bitmap and bare SharedMemory return values
    # bypass the authority boundary and make protocol validation impossible.
    $aidlText = (Get-ChildItem "app/src/main/aidl" -Recurse -Filter *.aidl |
        Get-Content -Raw) -join "`n"
    if ($aidlText -match "\bBitmap\b") {
        $failures.Add("Binder IPC must not expose Bitmap directly; use input FDs on mutate")
    }
    if ($aidlText -match "\bSharedMemory\s+get[A-Za-z]+Snapshot\s*\(") {
        $failures.Add("Binder IPC must return RuleSnapshotParcel instead of bare SharedMemory")
    }
    $ipcSources = @(
        "app/src/main/java/com/kaisar/xposed/godmode/ipc",
        "app/src/main/java/com/kaisar/xposed/godmode/control"
    )
    $bareSnapshot = Invoke-SourceSearch `
        -Pattern "\b(public|private|protected)\s+SharedMemory\s+get[A-Za-z]+Snapshot\s*\(|readSnapshot\s*\(\s*SharedMemory" `
        -SearchRoots $ipcSources `
        -IncludeFileName "*.java"
    if (!$bareSnapshot.Succeeded) {
        $failures.Add("Unable to scan for bare SharedMemory snapshot helpers: $($bareSnapshot.Error)")
    } elseif ($bareSnapshot.Matches.Count -gt 0) {
        $failures.Add("Bare SharedMemory snapshot helper remains:`n$($bareSnapshot.Matches -join "`n")")
    }

    # FileUtils exposes symbolic mode constants for callers; the gate checks usage sites,
    # not that constant declaration itself.
    $worldWritable = Invoke-SourceSearch `
        -Pattern "0777|S_IRWXO|S_IWOTH|S_IROTH|S_IXOTH|S_IRWXU\s*\|\s*S_IRWXG\s*\|\s*S_IRWXO" `
        -SearchRoots @("app/src/main", "engine/src/main") `
        -IncludeFileName "*.java" `
        -ExcludedFileNames @("FileUtils.java")
    if (!$worldWritable.Succeeded) {
        $failures.Add("Unable to scan production permissions: $($worldWritable.Error)")
    } elseif ($worldWritable.Matches.Count -gt 0) {
        $failures.Add("World-writable permissions are forbidden in production storage code:`n$($worldWritable.Matches -join "`n")")
    }

    $requiredDocs = @("docs/README.md", "docs/adr/0002-6-10-ipc-authority.md")
    foreach ($requiredDoc in $requiredDocs) {
        if (!(Test-Path $requiredDoc)) {
            $failures.Add("6.10 authority documentation is missing: $requiredDoc")
        }
    }
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

    # 子模块指针已于 6.10 线解冻（abd9f62 起恢复常规更新），不再与 v6.8.0 基线比较；
    # 仅输出当前指针供发布台账核对。
    $currentSubmodule = git rev-parse "HEAD:libxservicemanager"

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
        "CurrentSaveCoordinator", "RuleOperationStore",
        "RuleMapper", "RuleMatchSpec", "ActionSpec", "RuleFields"
    ) -join "|"
    $forbidden = Invoke-SourceSearch `
        -Pattern $forbiddenPattern `
        -SearchRoots @("app/src/main", "engine/src/main", "settings.gradle") `
        -ExcludeBuildDirectories
    if (!$forbidden.Succeeded) {
        $failures.Add("Unable to scan for excluded production symbols: $($forbidden.Error)")
    } elseif ($forbidden.Matches.Count -gt 0) {
        $failures.Add("Excluded production symbols found:`n$($forbidden.Matches -join "`n")")
    }

    $legacyReferences = Invoke-SourceSearch `
        -Pattern "IGodModeManager|IObserver|OP_ONE_SHOT|finishAssetWrite|saveImageFile|getProtocolDescriptor|getProtocolVersion|AssetWriteSession|openAssetWrite|discardAssetWrite" `
        -SearchRoots @("app/src/main", "engine/src/main", "app/src/main/aidl") `
        -ExcludeBuildDirectories
    if (!$legacyReferences.Succeeded) {
        $failures.Add("Unable to scan for retired IPC references: $($legacyReferences.Error)")
    } elseif ($legacyReferences.Matches.Count -gt 0) {
        $failures.Add("Retired IPC symbols referenced by production code:`n$($legacyReferences.Matches -join "`n")")
    }

    # Logging is part of the 6.10 observability contract. Production code may use
    # android.util.Log only inside the Logger/GodModeLog adapters or the legacy
    # XServiceManager default delegate; all business paths must go through Logger.
    $directLogMatches = Invoke-SourceSearch `
        -Pattern "android\.util\.Log|import\s+android\.util\.Log" `
        -SearchRoots @("app/src/main", "engine/src/main", "libxservicemanager/src/main") `
        -IncludeFileName "*.java" `
        -ExcludeBuildDirectories
    if (!$directLogMatches.Succeeded) {
        $failures.Add("Unable to scan direct production logging calls: $($directLogMatches.Error)")
    } elseif ($directLogMatches.Matches.Count -gt 0) {
        $unexpectedDirectLogs = @($directLogMatches.Matches | Where-Object {
            $_ -notmatch "(engine[\\/]src[\\/]main[\\/]java[\\/]com[\\/]kaisar[\\/]xposed[\\/]godmode[\\/]engine[\\/]util[\\/]Logger\.java|app[\\/]src[\\/]main[\\/]java[\\/]com[\\/]kaisar[\\/]xposed[\\/]godmode[\\/]control[\\/]GodModeLog\.java|libxservicemanager[\\/]src[\\/]main[\\/]java[\\/]com[\\/]kaisar[\\/]xservicemanager[\\/]XServiceManager\.java)"
        })
        if ($unexpectedDirectLogs.Count -gt 0) {
            $failures.Add("Production code bypasses Logger with direct android.util.Log:`n$($unexpectedDirectLogs -join "`n")")
        }
    }

    $writerInstallations = Invoke-SourceSearch `
        -Pattern "Logger\.setWriter\(" `
        -SearchRoots @("app/src/main", "engine/src/main") `
        -IncludeFileName "*.java" `
        -ExcludeBuildDirectories
    if (!$writerInstallations.Succeeded) {
        $failures.Add("Unable to scan Logger writer installations: $($writerInstallations.Error)")
    } elseif ($writerInstallations.Matches.Count -gt 0) {
        $unexpectedWriterInstallations = @($writerInstallations.Matches | Where-Object {
            $_ -notmatch "(RuleServiceClient\.java|RuleServiceServer\.java|ServiceBootstrapper\.java)"
        })
        if ($unexpectedWriterInstallations.Count -gt 0) {
            $failures.Add("Logger writer installed outside approved process boundaries:`n$($unexpectedWriterInstallations -join "`n")")
        }
    }
    foreach ($requiredProcessLoggingCall in @(
        "app/src/main/java/com/kaisar/xposed/godmode/GodModeApplication.java",
        "app/src/main/java/com/kaisar/xposed/godmode/inject/AppInjector.java"
    )) {
        if ((Get-Content -Raw $requiredProcessLoggingCall) -notmatch "installProcessLogging") {
            $failures.Add("Process logging bridge is missing: $requiredProcessLoggingCall")
        }
    }

    $messageOnlyThrowable = Invoke-SourceSearch `
        -Pattern "Logger\.(d|i|w|e)\([^;`r`n]*getMessage\(\)" `
        -SearchRoots @("app/src/main", "engine/src/main", "libxservicemanager/src/main") `
        -IncludeFileName "*.java" `
        -ExcludeBuildDirectories
    if (!$messageOnlyThrowable.Succeeded) {
        $failures.Add("Unable to scan Throwable logging calls: $($messageOnlyThrowable.Error)")
    } elseif ($messageOnlyThrowable.Matches.Count -gt 0) {
        $failures.Add("Throwable message is logged without the Throwable object:`n$($messageOnlyThrowable.Matches -join "`n")")
    }

    $godModeLogSource = "app/src/main/java/com/kaisar/xposed/godmode/control/GodModeLog.java"
    $loggerSource = "engine/src/main/java/com/kaisar/xposed/godmode/engine/util/Logger.java"
    if (!(Test-Path $godModeLogSource) -or !(Test-Path $loggerSource)) {
        $failures.Add("Unified logging implementation is missing")
    } else {
        $godModeLogText = Get-Content -Raw $godModeLogSource
        $loggerText = Get-Content -Raw $loggerSource
        foreach ($requiredLogContract in @(
            "formatLine",
            "sourcePid",
            "oneLine",
            "Log\.VERBOSE",
            "Log\.ASSERT",
            "MAX_PENDING_RECORDS",
            "ThreadPoolExecutor"
        )) {
            if ($godModeLogText -notmatch $requiredLogContract) {
                $failures.Add("GodModeLog contract is missing: $requiredLogContract")
            }
        }
        foreach ($requiredLoggerContract in @(
            "d\(String tag, String msg, Throwable tr\)",
            "sDispatching",
            "log writer failed",
            "eImmediate"
        )) {
            if ($loggerText -notmatch $requiredLoggerContract) {
                $failures.Add("Logger contract is missing: $requiredLoggerContract")
            }
        }
        if ($godModeLogText -notmatch "/data/misc/godmode/godmodepro\.log") {
            $failures.Add("GodModeLog persistent path is missing")
        }
    }
    $clientSource = "app/src/main/java/com/kaisar/xposed/godmode/ipc/RuleServiceClient.java"
    if (!(Test-Path $clientSource) -or
        (Get-Content -Raw $clientSource) -notmatch "MAX_PENDING_LOGS|flushPendingLogs|PendingLog") {
        $failures.Add("Client logging backlog contract is missing")
    }
    $logTest = "app/src/test/java/com/kaisar/xposed/godmode/control/GodModeLogTest.java"
    if (!(Test-Path $logTest)) {
        $failures.Add("GodModeLog format contract test is missing: $logTest")
    }
    $logRulesDoc = "docs/device-test-rules.md"
    if (!(Test-Path $logRulesDoc) -or
        (Get-Content -Raw $logRulesDoc) -notmatch "MM-dd HH:mm:ss\.SSS pid LEVEL/tag") {
        $failures.Add("Device log format contract is missing from $logRulesDoc")
    }

    if ($failures.Count -gt 0) {
        $failures | ForEach-Object { Write-Error $_ }
        exit 1
    }

    Write-Host "Stabilization contract check passed."
    Write-Host "Baseline: $Baseline"
    Write-Host "RuleRecord parcel ABI: unchanged"
    Write-Host "IPC: canonical 6.10 contract present; retired AIDL absent"
    Write-Host "IPC: snapshot envelope, single-call input FDs, and no direct Bitmap transport"
    Write-Host "Storage: no world-writable production permissions"
    Write-Host "Logging: unified adapters, Throwable preservation, single-line format, and contract test present"
    Write-Host "RuleRecord: flat JSON and Parcelable boundaries present (golden tests enforce wire slots)"
    Write-Host "Modules: app, engine, libxservicemanager"
    Write-Host "libxservicemanager: $currentSubmodule"
    Write-Host "Excluded production symbols: absent"
} finally {
    Pop-Location
}
