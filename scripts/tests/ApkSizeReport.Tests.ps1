$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$modulePath = Join-Path (Split-Path $PSScriptRoot -Parent) 'ApkSizeReport.psm1'
Import-Module $modulePath -Force

function Assert-Equal {
    param($Expected, $Actual, [string]$Message)
    if ($Expected -ne $Actual) {
        throw "$Message Expected=[$Expected] Actual=[$Actual]"
    }
}

Assert-Equal 'dex' (Get-CvfApkEntryCategory -Path 'classes2.dex') 'dex category'
Assert-Equal 'native' (Get-CvfApkEntryCategory -Path 'lib/arm64-v8a/libtdjni.so') 'native category'
Assert-Equal 'resources' (Get-CvfApkEntryCategory -Path 'res/drawable/icon.xml') 'resource category'
Assert-Equal 'assets' (Get-CvfApkEntryCategory -Path 'assets/data.bin') 'asset category'
Assert-Equal 'manifest' (Get-CvfApkEntryCategory -Path 'AndroidManifest.xml') 'manifest category'

$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("cvf-apk-size-" + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $tempDirectory
try {
    Add-Type -AssemblyName System.IO.Compression
    $archivePath = Join-Path $tempDirectory 'fixture.apk'
    $stream = [System.IO.File]::Create($archivePath)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $stream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        try {
            foreach ($entrySpec in @(
                @{ Name = 'classes.dex'; Text = 'dex-data' },
                @{ Name = 'lib/arm64-v8a/libtdjni.so'; Text = 'native-data' },
                @{ Name = 'resources.arsc'; Text = 'resources-data' }
            )) {
                $entry = $archive.CreateEntry($entrySpec.Name)
                $writer = [System.IO.StreamWriter]::new($entry.Open())
                try { $writer.Write($entrySpec.Text) } finally { $writer.Dispose() }
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $stream.Dispose()
    }

    $summary = Get-CvfApkSizeSummary -ApkPath $archivePath
    Assert-Equal 3 $summary.EntryCount 'entry count'
    Assert-Equal 1 ($summary.Categories | Where-Object Name -eq 'dex').EntryCount 'dex entry count'
    Assert-Equal 1 ($summary.Categories | Where-Object Name -eq 'native').EntryCount 'native entry count'
    Assert-Equal 1 ($summary.Categories | Where-Object Name -eq 'resources').EntryCount 'resource entry count'
    if ($summary.TotalUncompressedBytes -le 0) { throw 'uncompressed total must be positive' }
} finally {
    Remove-Item -LiteralPath $tempDirectory -Recurse -Force
}

Write-Output 'APK_SIZE_REPORT_SCRIPT_TEST_RESULT=PASS'
