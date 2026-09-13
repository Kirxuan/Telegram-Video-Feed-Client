[CmdletBinding()]
param(
    [string]$ApkPath = 'app/build/outputs/apk/debug/app-debug.apk',
    [string]$OutputDirectory = 'build/reports/stage25a/apk'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path $PSScriptRoot -Parent
$modulePath = Join-Path $PSScriptRoot 'ApkSizeReport.psm1'
Import-Module $modulePath -Force

$resolvedApkPath = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
    $ApkPath
} else {
    Join-Path $repoRoot $ApkPath
}
$resolvedOutputDirectory = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
} else {
    Join-Path $repoRoot $OutputDirectory
}

$summary = Export-CvfApkSizeReport -ApkPath $resolvedApkPath -OutputDirectory $resolvedOutputDirectory
Write-Output "APK_SIZE_REPORT_RESULT=PASS"
Write-Output "APK_BYTES=$($summary.ApkBytes)"
Write-Output "APK_SIZE_JSON=$(Join-Path $resolvedOutputDirectory 'apk-size.json')"
