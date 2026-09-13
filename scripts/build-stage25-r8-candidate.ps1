[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path $PSScriptRoot -Parent
$outputDirectory = Join-Path $repoRoot 'build/reports/stage25i'
$apkOutput = Join-Path $repoRoot 'app/build/outputs/apk/benchmark/app-benchmark.apk'
$sizeReporter = Join-Path $PSScriptRoot 'write-apk-size-report.ps1'

function Invoke-GradleBuild([bool]$EnableR8, [string]$Label) {
    & (Join-Path $repoRoot 'gradlew.bat') :app:assembleBenchmark `
        "-PcvfR8CandidateEnabled=$($EnableR8.ToString().ToLowerInvariant())" `
        --offline --dependency-verification=strict --max-workers=1 --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process' |
        ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw "$Label benchmark build failed." }
    if (-not (Test-Path -LiteralPath $apkOutput -PathType Leaf)) {
        throw "$Label benchmark APK was not produced."
    }

    $destination = Join-Path $outputDirectory "velora-$Label.apk"
    Copy-Item -LiteralPath $apkOutput -Destination $destination -Force
    & $sizeReporter -ApkPath $destination -OutputDirectory (Join-Path $outputDirectory $Label) |
        ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw "$Label APK size report failed." }
    return [pscustomobject]@{
        Path = $destination
        Bytes = (Get-Item -LiteralPath $destination).Length
    }
}

Push-Location $repoRoot
try {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $baselineApk = Invoke-GradleBuild -EnableR8 $false -Label 'unminified'
    $candidateApk = Invoke-GradleBuild -EnableR8 $true -Label 'r8-candidate'

    $baselineBytes = $baselineApk.Bytes
    $candidateBytes = $candidateApk.Bytes
    $savedBytes = $baselineBytes - $candidateBytes
    $savedPercent = if ($baselineBytes -eq 0) { 0 } else {
        [Math]::Round(($savedBytes * 100.0) / $baselineBytes, 2)
    }

    Write-Output 'STAGE25_R8_CANDIDATE_BUILD=PASS'
    Write-Output 'PRODUCTION_RELEASE_R8=false'
    Write-Output "BASELINE_APK_BYTES=$baselineBytes"
    Write-Output "CANDIDATE_APK_BYTES=$candidateBytes"
    Write-Output "SAVED_BYTES=$savedBytes"
    Write-Output "SAVED_PERCENT=$savedPercent"
    Write-Output 'DEVICE_ACTIONS=none'
    Write-Warning 'R8/resource shrinking remains a benchmark-only candidate until install, TDLib JNI, Room/Hilt, login restoration, and playback smoke pass on an explicitly approved physical target.'
} finally {
    Pop-Location
}
