[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Baseline', 'SampleQueue', 'PoolC1', 'PoolC2')]
    [string]$Candidate
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path $PSScriptRoot -Parent
$ownerPromotionSource = Join-Path $repoRoot `
    'player/src/main/java/com/qixuan/channelvideoflow/player/VideoPreloadManager.kt'
$sourceText = Get-Content -LiteralPath $ownerPromotionSource -Raw
if ($sourceText -notmatch 'PRODUCTION_OWNER_PROMOTION_ENABLED\s*=\s*false') {
    throw 'Owner Promotion must remain disabled for every Stage 25 playback experiment.'
}

$sampleQueue = $Candidate -eq 'SampleQueue'
$poolCandidate = switch ($Candidate) {
    'PoolC1' { 'C1' }
    'PoolC2' { 'C2' }
    default { 'DISABLED' }
}
$runtimePoolIntegration = $poolCandidate -ne 'DISABLED'

Push-Location $repoRoot
try {
    & .\gradlew.bat :app:assembleBenchmark `
        "-PcvfSampleQueuePreloadEnabled=$($sampleQueue.ToString().ToLowerInvariant())" `
        "-PcvfPlaybackPoolCandidate=$poolCandidate" `
        --offline --dependency-verification=strict --max-workers=1 --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw "Stage 25 $Candidate build failed." }

    $sourceApk = Join-Path $repoRoot 'app/build/outputs/apk/benchmark/app-benchmark.apk'
    if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) {
        throw 'Benchmark APK was not produced.'
    }
    $outputDirectory = Join-Path $repoRoot 'build/reports/stage25-experiments/apks'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $destination = Join-Path $outputDirectory "velora-$($Candidate.ToLowerInvariant()).apk"
    Copy-Item -LiteralPath $sourceApk -Destination $destination -Force

    Write-Output "STAGE25_EXPERIMENT_BUILD=PASS"
    Write-Output "CANDIDATE=$Candidate"
    Write-Output "SAMPLE_QUEUE=$($sampleQueue.ToString().ToLowerInvariant())"
    Write-Output "POOL=$poolCandidate"
    Write-Output "RUNTIME_POOL_INTEGRATION=$($runtimePoolIntegration.ToString().ToLowerInvariant())"
    Write-Output 'OWNER_PROMOTION=false'
    Write-Output "APK=$destination"
    Write-Output 'DEVICE_ACTIONS=none'
    if ($poolCandidate -eq 'C2') {
        Write-Warning 'C2 uses one consumed EGL Surface with paused decode. Offscreen frames are excluded from visible metrics; protected content uses C1. Hardware performance gates remain unverified.'
    }
} finally {
    Pop-Location
}
