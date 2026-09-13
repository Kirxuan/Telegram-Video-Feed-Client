Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-CvfApkEntryCategory {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ($Path -match '^classes(?:\d+)?\.dex$') { return 'dex' }
    if ($Path -match '^lib/') { return 'native' }
    if ($Path -match '^(res/|resources\.arsc$)') { return 'resources' }
    if ($Path -match '^assets/') { return 'assets' }
    if ($Path -match '^META-INF/') { return 'metadata' }
    if ($Path -eq 'AndroidManifest.xml') { return 'manifest' }
    return 'other'
}

function Get-CvfApkSizeSummary {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$ApkPath)

    $resolvedPath = (Resolve-Path -LiteralPath $ApkPath).Path
    Add-Type -AssemblyName System.IO.Compression
    $stream = [System.IO.File]::OpenRead($resolvedPath)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $stream,
            [System.IO.Compression.ZipArchiveMode]::Read,
            $false
        )
        try {
            $buckets = [ordered]@{}
            foreach ($category in @('dex', 'native', 'resources', 'assets', 'manifest', 'metadata', 'other')) {
                $buckets[$category] = [ordered]@{
                    EntryCount = 0
                    CompressedBytes = 0L
                    UncompressedBytes = 0L
                }
            }

            foreach ($entry in $archive.Entries) {
                if ([string]::IsNullOrEmpty($entry.Name)) { continue }
                $category = Get-CvfApkEntryCategory -Path $entry.FullName
                $buckets[$category].EntryCount += 1
                $buckets[$category].CompressedBytes += [long]$entry.CompressedLength
                $buckets[$category].UncompressedBytes += [long]$entry.Length
            }

            $categories = @(
                foreach ($category in $buckets.Keys) {
                    [pscustomobject]@{
                        Name = $category
                        EntryCount = $buckets[$category].EntryCount
                        CompressedBytes = $buckets[$category].CompressedBytes
                        UncompressedBytes = $buckets[$category].UncompressedBytes
                    }
                }
            )
            return [pscustomobject]@{
                SchemaVersion = 1
                ApkFileName = [System.IO.Path]::GetFileName($resolvedPath)
                ApkBytes = (Get-Item -LiteralPath $resolvedPath).Length
                EntryCount = ($categories | Measure-Object -Property EntryCount -Sum).Sum
                TotalCompressedBytes = ($categories | Measure-Object -Property CompressedBytes -Sum).Sum
                TotalUncompressedBytes = ($categories | Measure-Object -Property UncompressedBytes -Sum).Sum
                Categories = $categories
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Export-CvfApkSizeReport {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [Parameter(Mandatory = $true)][string]$OutputDirectory
    )

    $summary = Get-CvfApkSizeSummary -ApkPath $ApkPath
    $null = New-Item -ItemType Directory -Force -Path $OutputDirectory
    $jsonPath = Join-Path $OutputDirectory 'apk-size.json'
    $markdownPath = Join-Path $OutputDirectory 'apk-size.md'
    $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $jsonPath -Encoding utf8

    $rows = @(
        '# APK size report'
        ''
        "APK: ``$($summary.ApkFileName)``"
        ''
        '| Category | Entries | Compressed bytes | Uncompressed bytes |'
        '| --- | ---: | ---: | ---: |'
    )
    foreach ($category in $summary.Categories) {
        $rows += "| $($category.Name) | $($category.EntryCount) | $($category.CompressedBytes) | $($category.UncompressedBytes) |"
    }
    $rows += ''
    $rows += "Archive bytes: $($summary.ApkBytes)"
    $rows += "Uncompressed entry bytes: $($summary.TotalUncompressedBytes)"
    $rows | Set-Content -LiteralPath $markdownPath -Encoding utf8
    return $summary
}

Export-ModuleMember -Function @(
    'Get-CvfApkEntryCategory',
    'Get-CvfApkSizeSummary',
    'Export-CvfApkSizeReport'
)
