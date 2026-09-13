[CmdletBinding()]
param(
    [string]$ApkPath = 'app/build/outputs/apk/release/app-release-unsigned.apk',
    [string]$OutputDirectory = 'build/reports/stage25k/release-boundaries'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path $PSScriptRoot -Parent
$resolvedApk = if ([IO.Path]::IsPathRooted($ApkPath)) { $ApkPath } else { Join-Path $repoRoot $ApkPath }
$resolvedOutput = if ([IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $repoRoot $OutputDirectory }
if (-not (Test-Path -LiteralPath $resolvedApk -PathType Leaf)) { throw "Missing APK: $resolvedApk" }

function Resolve-AndroidSdk {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) { return $candidate }
    }
    $localProperties = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $line = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($line) {
            $candidate = (($line -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\'))
            if (Test-Path -LiteralPath $candidate -PathType Container) { return $candidate }
        }
    }
    throw 'Android SDK location is unavailable.'
}

$sdk = Resolve-AndroidSdk
$aapt2 = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Recurse -Filter aapt2.exe |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $aapt2) { throw 'aapt2 is unavailable.' }

$permissionOutput = @(& $aapt2 dump permissions $resolvedApk)
if ($LASTEXITCODE -ne 0) { throw 'aapt2 permission dump failed.' }
$permissions = @($permissionOutput |
    Where-Object { $_ -match "^uses-permission: name='([^']+)'" } |
    ForEach-Object { $matches[1] } |
    Sort-Object -Unique)
$expectedPermissions = @('android.permission.ACCESS_NETWORK_STATE', 'android.permission.INTERNET')
$permissionsPass = (@(Compare-Object $expectedPermissions $permissions).Count -eq 0)

$manifestOutput = (& $aapt2 dump xmltree $resolvedApk --file AndroidManifest.xml) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 manifest dump failed.' }
$backupPass = $manifestOutput -match 'allowBackup[^\n]*=false' -and
    $manifestOutput -match 'fullBackupContent' -and
    $manifestOutput -match 'dataExtractionRules'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $resolvedApk))
try {
    $nativeEntries = @($archive.Entries |
        Where-Object FullName -like 'lib/*' |
        Select-Object -ExpandProperty FullName |
        Sort-Object)
} finally {
    $archive.Dispose()
}
$expectedNativeEntries = @(
    'lib/arm64-v8a/libandroidx.graphics.path.so',
    'lib/arm64-v8a/libdatastore_shared_counter.so',
    'lib/arm64-v8a/libtdjni.so'
)
$nativePass = (@(Compare-Object $expectedNativeEntries $nativeEntries).Count -eq 0)

$apkText = [Text.Encoding]::Latin1.GetString([IO.File]::ReadAllBytes($resolvedApk))
$credentialMatches = 0
$localProperties = Join-Path $repoRoot 'local.properties'
if (Test-Path -LiteralPath $localProperties) {
    Get-Content -LiteralPath $localProperties | ForEach-Object {
        if ($_ -match '^(TELEGRAM_API_ID|TELEGRAM_API_HASH)=(.*)$') {
            $value = $matches[2].Trim()
            if ($value -and $apkText.Contains($value, [StringComparison]::Ordinal)) {
                $credentialMatches += 1
            }
        }
    }
}

$ownerPromotionSource = Get-Content -LiteralPath (
    Join-Path $repoRoot 'player/src/main/java/com/qixuan/channelvideoflow/player/VideoPreloadManager.kt'
) -Raw
$appBuild = Get-Content -LiteralPath (Join-Path $repoRoot 'app/build.gradle.kts') -Raw
$telegramBuild = Get-Content -LiteralPath (Join-Path $repoRoot 'telegram/build.gradle.kts') -Raw
$playerBuild = Get-Content -LiteralPath (Join-Path $repoRoot 'player/build.gradle.kts') -Raw
$productionPoolDefaultPattern = 'cvfPlaybackPoolCandidate"\)(?:\s|//[^\r\n]*(?:\r?\n|$))*\.orElse\("C1"\)'
$fallbacksPass = $ownerPromotionSource -match 'PRODUCTION_OWNER_PROMOTION_ENABLED\s*=\s*false' -and
    $appBuild -match 'release\s*\{[\s\S]*?isMinifyEnabled\s*=\s*false' -and
    $telegramBuild -match 'cvfTdLibDatabaseEncryptionCandidateEnabled[\s\S]*?\.orElse\(false\)' -and
    $playerBuild -match 'cvfSampleQueuePreloadEnabled", false' -and
    $playerBuild -match $productionPoolDefaultPattern -and
    $playerBuild -match '!sampleQueuePreloadEnabled \|\| playbackPoolCandidate == "DISABLED"'

$failures = @()
if (-not $permissionsPass) { $failures += 'permissions' }
if (-not $backupPass) { $failures += 'backup' }
if (-not $nativePass) { $failures += 'native' }
if ($credentialMatches -ne 0) { $failures += 'credentials' }
if (-not $fallbacksPass) { $failures += 'safe_fallbacks' }

$summary = [ordered]@{
    SchemaVersion = 1
    ApkFileName = [IO.Path]::GetFileName($resolvedApk)
    ApkSha256 = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    ApkBytes = (Get-Item -LiteralPath $resolvedApk).Length
    Permissions = $permissions
    BackupDisabledAndExcluded = $backupPass
    NativeEntries = $nativeEntries
    LocalCredentialMatchCount = $credentialMatches
    ProductionSafeFallbacks = $fallbacksPass
    DeviceActions = 'none'
    Result = if ($failures.Count -eq 0) { 'PASS' } else { 'FAIL' }
    Failures = $failures
}

New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
$jsonPath = Join-Path $resolvedOutput 'release-boundaries.json'
$markdownPath = Join-Path $resolvedOutput 'release-boundaries.md'
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $jsonPath -Encoding utf8
@(
    '# Stage 25K release boundary proof'
    ''
    "- Result: $($summary.Result)"
    "- APK: $($summary.ApkFileName) ($($summary.ApkBytes) bytes)"
    "- SHA-256: $($summary.ApkSha256)"
    "- Permissions: $($permissions -join ', ')"
    "- Backup disabled and excluded: $backupPass"
    "- Native entries: $($nativeEntries -join ', ')"
    "- Local credential matches: $credentialMatches"
    "- Production safe fallbacks: $fallbacksPass"
    '- Device actions: none'
) | Set-Content -LiteralPath $markdownPath -Encoding utf8

Write-Output "STAGE25_RELEASE_BOUNDARY_RESULT=$($summary.Result)"
Write-Output "REPORT_JSON=$jsonPath"
Write-Output "REPORT_MD=$markdownPath"
Write-Output 'DEVICE_ACTIONS=none'
if ($failures.Count -ne 0) { throw "Release boundary failures: $($failures -join ', ')" }
