$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$appBuild = Get-Content -LiteralPath (Join-Path $repoRoot 'app/build.gradle.kts') -Raw
$rules = Get-Content -LiteralPath (Join-Path $repoRoot 'app/proguard-rules.pro') -Raw
$runner = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts/build-stage25-r8-candidate.ps1') -Raw
$generator = Get-Content -LiteralPath (Join-Path $repoRoot 'benchmark/src/main/java/com/qixuan/channelvideoflow/benchmark/BaselineProfileGenerator.kt') -Raw

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

Assert-True ($appBuild -match 'cvfR8CandidateEnabled') 'benchmark R8 must be controlled by an explicit property'
Assert-True ($appBuild -match 'release\s*\{[\s\S]*?isMinifyEnabled\s*=\s*false[\s\S]*?isShrinkResources\s*=\s*false') 'production release must remain unminified by default'
Assert-True ($rules -match 'org\.drinkless\.tdlib\.TdApi\$\*') 'TDLib JNI object classes require a keep boundary'
Assert-True ($runner -match "'DEVICE_ACTIONS=none'") 'candidate builder must declare that it performs no device action'
Assert-True ($runner -match "'PRODUCTION_RELEASE_R8=false'") 'candidate builder must report the production fallback'
$hostRedirectCount = ([regex]::Matches($runner, 'ForEach-Object\s*\{\s*Write-Host')).Count
Assert-True ($hostRedirectCount -eq 2) 'Gradle and nested reporter output must not pollute the returned APK descriptor'
Assert-True ($generator -match 'includeInStartupProfile\s*=\s*true') 'generator must emit a startup profile'
Assert-True ($generator -match 'credential screen') 'generator must document its no-account boundary'

Write-Output 'STAGE25_R8_CANDIDATE_SCRIPT_TEST_RESULT=PASS'
