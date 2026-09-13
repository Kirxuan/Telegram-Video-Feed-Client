[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path $PSScriptRoot -Parent

Push-Location $repoRoot
try {
    & .\gradlew.bat :telegram:testDebugUnitTest `
        --tests 'com.qixuan.channelvideoflow.telegram.storage.SecureTdLibDatabaseKeyProviderTest' `
        --tests 'com.qixuan.channelvideoflow.telegram.client.TelegramClientManagerTest' `
        --offline --dependency-verification=strict --max-workers=1 --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw 'TDLib database encryption host tests failed.' }

    & .\gradlew.bat :app:assembleBenchmark `
        -PcvfTdLibDatabaseEncryptionCandidateEnabled=true `
        --offline --dependency-verification=strict --max-workers=1 --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw 'TDLib database encryption candidate build failed.' }

    $outputDirectory = Join-Path $repoRoot 'build/reports/stage25-experiments/apks'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot 'app/build/outputs/apk/benchmark/app-benchmark.apk') `
        -Destination (Join-Path $outputDirectory 'velora-database-encryption.apk') -Force

    Write-Output 'STAGE25_TDLIB_DATABASE_ENCRYPTION_CANDIDATE=PASS'
    Write-Output 'PRODUCTION_DEFAULT=false'
    Write-Output 'EXISTING_DATABASE_MIGRATION=blocked_until_explicit_device_proof'
    Write-Output 'CORRUPT_OR_MISSING_KEY_POLICY=fail_closed'
    Write-Output 'DEVICE_ACTIONS=none'
    Write-Warning 'Do not install or enable this candidate for an existing TDLib session. A disposable-account migration matrix and explicit physical-device approval are still required.'
} finally {
    Pop-Location
}
