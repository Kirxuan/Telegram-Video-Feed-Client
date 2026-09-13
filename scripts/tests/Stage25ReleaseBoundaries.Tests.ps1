$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$runner = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts/verify-stage25-release-boundaries.ps1') -Raw
$workflow = Get-Content -LiteralPath (Join-Path $repoRoot '.github/workflows/android.yml') -Raw
$wrapper = Get-Content -LiteralPath (Join-Path $repoRoot 'gradle/wrapper/gradle-wrapper.properties') -Raw

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

Assert-True ($runner -match 'Compare-Object \$expectedPermissions \$permissions') 'permissions must be exact, not a subset'
Assert-True ($runner -match 'Compare-Object \$expectedNativeEntries \$nativeEntries') 'native entries must be exact, not a subset'
Assert-True ($runner -match 'LocalCredentialMatchCount') 'APK local credential reverse scan is required'
Assert-True ($runner -match "DeviceActions = 'none'") 'release proof must be host-only'
Assert-True ($runner -match 'cvfPlaybackPoolCandidate') 'release proof must enforce the production C1 playback pool default'
$patternDefinition = [regex]::Match($runner, '(?m)^\$productionPoolDefaultPattern = ''([^'']+)''')
Assert-True $patternDefinition.Success 'production pool default matcher must be available for regression checks'
$poolPattern = $patternDefinition.Groups[1].Value
$actualPlayerBuild = Get-Content -LiteralPath (Join-Path $repoRoot 'player/build.gradle.kts') -Raw
Assert-True ($actualPlayerBuild -match $poolPattern) 'the real C1 configuration with explanatory comments must pass'
$commentedC1 = @'
providers.gradleProperty("cvfPlaybackPoolCandidate")
    // Production ships C1; experiments must remain explicit.
    // Keep this comment between the provider and its default.
    .orElse("C1")
'@
Assert-True ($commentedC1 -match $poolPattern) 'line comments must not cause a false failure'
Assert-True ($commentedC1.Replace('.orElse("C1")', '.orElse("C2")') -notmatch $poolPattern) 'an actual C2 default must still fail even when comments mention C1'
Assert-True ('providers.gradleProperty("cvfPlaybackPoolCandidate").orElse("DISABLED"); val other = providers.gradleProperty("other").orElse("C1")' -notmatch $poolPattern) 'an unrelated later C1 default must not satisfy the pool check'
Assert-True ($workflow -match 'org\.gradle\.dependency\.verification=strict') 'CI must enforce Gradle dependency verification'
Assert-True ($workflow -match '--dependency-verification=strict') 'CI Gradle command must explicitly request strict verification'
Assert-True ($workflow -match 'gradle/actions/wrapper-validation@v4') 'CI must validate the Gradle wrapper before execution'
Assert-True ($workflow -match 'test lint assembleDebug :app:assembleRelease :app:compileInstrumentationKotlin') 'CI must run the full host gate'
Assert-True ($wrapper -match 'validateDistributionUrl=true') 'Gradle wrapper URL validation must be enabled'

Write-Output 'STAGE25_RELEASE_BOUNDARY_SCRIPT_TEST_RESULT=PASS'
