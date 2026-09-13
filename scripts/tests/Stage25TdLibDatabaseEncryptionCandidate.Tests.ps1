$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$telegramBuild = Get-Content -LiteralPath (Join-Path $repoRoot 'telegram/build.gradle.kts') -Raw
$provider = Get-Content -LiteralPath (Join-Path $repoRoot 'telegram/src/main/java/com/qixuan/channelvideoflow/telegram/storage/SecureTdLibDatabaseKeyProvider.kt') -Raw
$runner = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts/build-stage25-tdlib-database-encryption-candidate.ps1') -Raw

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

Assert-True ($telegramBuild -match 'cvfTdLibDatabaseEncryptionCandidateEnabled') 'candidate must require an explicit Gradle property'
Assert-True ($telegramBuild -match '\.orElse\(false\)') 'database encryption must default off'
Assert-True ($provider -match 'MigrationRequired') 'existing databases must be gated from implicit migration'
Assert-True ($provider -match 'LinkOption\.NOFOLLOW_LINKS') 'database inspection must not follow links'
Assert-True ($provider -match 'noBackupFilesDir') 'wrapped key must live below noBackupFilesDir'
Assert-True ($runner -match "'DEVICE_ACTIONS=none'") 'candidate builder must not perform device actions'
Assert-True ($runner -match 'EXISTING_DATABASE_MIGRATION=blocked_until_explicit_device_proof') 'runner must retain the migration gate'

Write-Output 'STAGE25_TDLIB_DATABASE_ENCRYPTION_SCRIPT_TEST_RESULT=PASS'
