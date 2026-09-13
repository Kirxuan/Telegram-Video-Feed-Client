[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Serial,
    [ValidateRange(1, 1000)][int]$SwipeCount = 12,
    [ValidateRange(1, 120)][int]$PerSwipeTimeoutSeconds = 12,
    [ValidateRange(0, 15000)][int]$WatchMillis = 0,
    [ValidateSet('Normal', 'Fast')][string]$Mode = 'Normal',
    [ValidateSet('Forward', 'Reverse')][string]$Direction = 'Forward',
    [ValidateRange(5, 120)][int]$PlaybackReadyTimeoutSeconds = 30,
    [ValidateRange(0, 100)][int]$FastCheckpointEvery = 0,
    [ValidateSet('stage13b', 'stage13c', 'stage13d', 'stage13e', 'stage13f', 'stage18', 'stage25a', 'stage25f', 'stage25g', 'stage26', 'stage27')][string]$ReportStage = 'stage13d',
    [ValidateSet('Debug', 'Benchmark')][string]$BuildVariant = 'Debug',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$PackageName = 'com.qixuan.channelvideoflow'
$ActivityName = "$PackageName/.MainActivity"
$repoRoot = Split-Path $PSScriptRoot -Parent
$reportRoot = Join-Path $repoRoot "build/reports/$ReportStage"
$reportTitle = switch ($ReportStage) {
    'stage13b' { 'Stage 13B preload owner promotion benchmark' }
    'stage13c' { 'Stage 13C random round targeting benchmark' }
    'stage13d' { 'Stage 13D startup range A/B benchmark' }
    'stage13e' { 'Stage 13E random reference resolution benchmark' }
    'stage13f' { 'Stage 13F random final acceptance benchmark' }
    'stage18' { 'Stage 18 HLS and weak-network continuous playback benchmark' }
    'stage25a' { 'Stage 25A release-like performance baseline' }
    'stage25f' { 'Stage 25F SampleQueue candidate A/B' }
    'stage25g' { 'Stage 25G fixed two-player pool candidate A/B' }
    'stage26' { 'Stage 26 mobile-data dual-player acceptance' }
    'stage27' { 'Stage 27 mobile-data fast-start acceptance' }
}
$comparisonGuidance = if ($ReportStage -eq 'stage13f') {
    'Compare bind→first-frame against the Stage 13A RANDOM baseline only when media/cache/network conditions are comparable; compare release→settle against the fresh Stage 13E production baseline.'
}
elseif ($ReportStage -eq 'stage18') {
    'Compare identical Stage 18 flag builds only with the same account, queue, media, quality, network window, and cache precondition; SampleQueue requires at least 15% P95 improvement, 100% FIRST_FRAME, and zero safety failures.'
}
elseif ($ReportStage -in @('stage26', 'stage27')) {
    'Report all cellular attempts, timeouts and READY subgroup separately. Different media, watch time, cache or network conditions are observational samples, not a controlled speedup claim. Keep account and index intact.'
}
elseif ($ReportStage -in @('stage25f', 'stage25g')) {
    'Compare only against a fresh Stage 25A single-player production baseline under the same account, queue, media, network window, thermal state, and cache precondition. A candidate stays disabled without device A/B evidence and zero safety regressions.'
}
else {
    'Compare bind→first-frame against the fresh Stage 13C RANDOM baseline; startup-range and owner counters classify the long tail without media inspection.'
}
$modulePath = Join-Path $PSScriptRoot 'SwipeFirstFrameBenchmark.psm1'
Import-Module $modulePath -Force

if ($Mode -ne 'Fast' -and $FastCheckpointEvery -ne 0) {
    throw '-FastCheckpointEvery is only valid with -Mode Fast.'
}

function Find-Adb {
    $candidates = [System.Collections.Generic.List[string]]::new()
    foreach ($rootVariable in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($rootVariable)) {
            $candidates.Add((Join-Path $rootVariable 'platform-tools/adb.exe'))
        }
    }
    $candidates.Add('E:\AndroidStudio2.0\platform-tools\adb.exe')
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($null -ne $command) { $candidates.Add($command.Source) }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'adb.exe not found. Configure ANDROID_HOME/ANDROID_SDK_ROOT or PATH.'
}

$Adb = Find-Adb
$mainLogHistory = [Collections.Generic.List[string]]::new()
$mainLogSeen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string[]]$AdbArguments,
        [switch]$AllowFailure
    )
    $output = @(& $Adb @AdbArguments 2>&1)
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "adb command failed with exit code $exitCode"
    }
    return ,$output
}

function Get-MainLogLines {
    $snapshot = Invoke-Adb -AdbArguments @(
        '-s', $Serial, 'logcat', '-b', 'main', '-d', '-v', 'threadtime', '-s',
        'CVF-Transition:I', 'CVF-Player:I', 'CVF-Preload:I', 'CVF-Adaptive:I',
        'CVF-StartupRange:I', 'CVF-TdFile:I', 'CVF-FeedPerf:I', 'CVF-CachePerf:I'
    )
    # logcat is a ring buffer. Retain every observed line throughout this run so
    # device log rotation cannot erase earlier terminals or make counters decrease.
    foreach ($line in $snapshot) {
        if ($mainLogSeen.Add([string]$line)) { $mainLogHistory.Add([string]$line) }
    }
    return $mainLogHistory.ToArray()
}

function Get-CrashLogLines {
    return @(Invoke-Adb -AdbArguments @(
        '-s', $Serial, 'logcat', '-b', 'crash', '-d', '-v', 'threadtime'
    ) -AllowFailure)
}

function Get-ProcessMemInfo {
    $lines = Invoke-Adb -AdbArguments @(
        '-s', $Serial, 'shell', 'dumpsys', 'meminfo', $PackageName
    ) -AllowFailure
    return ConvertFrom-CvfMemInfo -Lines $lines
}

function Reset-FrameStats {
    Invoke-Adb -AdbArguments @(
        '-s', $Serial, 'shell', 'dumpsys', 'gfxinfo', $PackageName, 'reset'
    ) -AllowFailure | Out-Null
}

function Get-FrameStats {
    $lines = Invoke-Adb -AdbArguments @(
        '-s', $Serial, 'shell', 'dumpsys', 'gfxinfo', $PackageName
    ) -AllowFailure
    return ConvertFrom-CvfGfxInfo -Lines $lines
}

function Clear-BenchmarkLogs {
    $mainLogHistory.Clear()
    $mainLogSeen.Clear()
    Invoke-Adb -AdbArguments @('-s', $Serial, 'logcat', '-b', 'main', '-c') | Out-Null
    Invoke-Adb -AdbArguments @('-s', $Serial, 'logcat', '-b', 'crash', '-c') | Out-Null
}

function Wake-BenchmarkDisplay {
    # The physical benchmark device can dim/sleep while uiautomator is producing the verified
    # playback-page tree. WAKEUP is idempotent while already awake and sends no blind UI action.
    Invoke-Adb -AdbArguments @(
        '-s', $Serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'
    ) | Out-Null
    Start-Sleep -Milliseconds 100
}

function Get-TerminalCount {
    param($Summary)
    return $Summary.OutcomeCounts.FIRST_FRAME +
        $Summary.OutcomeCounts.FAILED +
        $Summary.OutcomeCounts.UNSUPPORTED +
        $Summary.OutcomeCounts.UNCHANGED
}

function Test-PlaybackPageSafely {
    $tree = (
        Invoke-Adb -AdbArguments @(
            '-s', $Serial, 'exec-out', 'uiautomator', 'dump', '/dev/tty'
        ) -AllowFailure
    ) -join "`n"
    # Playback controls auto-hide while the video remains on the same page. Recognize the
    # app-owned reveal-control surface so natural playback is not misreported as navigation.
    # The order selector can be collapsed or animating. RANDOM is independently required
    # from every transition's diagnostic at report time, not guessed from transient UI.
    return Test-CvfPlaybackUiTree -UiTree $tree
}

function Assert-PlaybackPageBeforeGesture {
    Wake-BenchmarkDisplay
    # UIAutomator can momentarily return no idle root during the controls animation.
    # Retry observation only; never send a gesture without a verified playback surface.
    for ($observation = 0; $observation -lt 3; $observation += 1) {
        if (Test-PlaybackPageSafely) { return }
        Start-Sleep -Milliseconds 200
    }
    throw 'Playback page changed before a gesture; sampling stopped without sending another swipe.'
}

function Wait-ForInitialPlayback {
    $deadline = [DateTime]::UtcNow.AddSeconds($PlaybackReadyTimeoutSeconds)
    Write-Host "请在 $PlaybackReadyTimeoutSeconds 秒内安全进入播放页；脚本不会自动点击、退出账号或清理缓存。"
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-PlaybackPageSafely) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

function Wait-ForTerminalAfter {
    param(
        [Parameter(Mandatory = $true)][int]$PreviousCount,
        [int]$PreviousReadyCount = -1,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $summary = ConvertFrom-CvfBenchmarkLog -Lines @(Get-MainLogLines) -PackageName $PackageName
        if ((Get-TerminalCount -Summary $summary) -gt $PreviousCount -and
            ($PreviousReadyCount -lt 0 -or $summary.PlayableReadyMetric.Count -gt $PreviousReadyCount)) { return $true }
        Start-Sleep -Milliseconds 100
    }
    return $false
}

function Get-AppUid {
    $lines = Invoke-Adb -AdbArguments @(
        '-s', $Serial, 'shell', 'cmd', 'package', 'list', 'packages', '-U', $PackageName
    ) -AllowFailure
    foreach ($line in $lines) {
        if ($line -match '\buid:(?<uid>\d+)\b') { return [int]$Matches['uid'] }
    }
    return $null
}

function Get-UidNetworkBytes {
    param([AllowNull()]$Uid)
    if ($null -eq $Uid) { return $null }
    $lines = Invoke-Adb -AdbArguments @(
        '-s', $Serial, 'shell', 'dumpsys', 'netstats', 'detail'
    ) -AllowFailure
    $rx = 0L
    $tx = 0L
    $matched = 0
    foreach ($line in $lines) {
        if ($line -notmatch ("\buid=" + [int]$Uid + "\b")) { continue }
        if ($line -match '\btag=0x(?!0\b)') { continue }
        if ($line -match '\brxBytes=(?<rx>\d+)\b.*\btxBytes=(?<tx>\d+)\b') {
            $rx += [long]$Matches['rx']
            $tx += [long]$Matches['tx']
            $matched += 1
        }
    }
    if ($matched -eq 0) { return $null }
    return [pscustomobject]@{ RxBytes = $rx; TxBytes = $tx }
}

function Write-FailureReport {
    param(
        [Parameter(Mandatory = $true)][string]$Reason,
        [Parameter(Mandatory = $true)][string]$ReportPath
    )
    New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
    @(
        "# $reportTitle",
        '',
        '- Result: FAIL',
        "- Reason: $Reason",
        '- Samples: insufficient; no PASS was inferred.',
        '- Device identifiers, network names, addresses, paths, Telegram keys, and content were not recorded.'
    ) | Set-Content -LiteralPath $ReportPath -Encoding UTF8
}

function Format-MetricValue {
    param($Value)
    if ($null -eq $Value) { return 'n/a' }
    return "${Value}ms"
}

New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$baseName = "random-swipe-first-frame-$($Mode.ToLowerInvariant())-$($Direction.ToLowerInvariant())-$timestamp"
$reportPath = Join-Path $reportRoot "$baseName.md"
$evidencePath = Join-Path $reportRoot "$baseName.log"
$summaryPath = Join-Path $reportRoot "$baseName.summary.json"
$attempts = [Collections.Generic.List[object]]::new()

try {
    $deviceLines = @(& $Adb devices)
    if ($LASTEXITCODE -ne 0 -or -not ($deviceLines -match ('^' + [regex]::Escape($Serial) + '\s+device\b'))) {
        throw 'target serial is not connected and authorized as device'
    }
    $isQemu = (Invoke-Adb -AdbArguments @('-s', $Serial, 'shell', 'getprop', 'ro.kernel.qemu')) -join ''
    $bootQemu = (Invoke-Adb -AdbArguments @('-s', $Serial, 'shell', 'getprop', 'ro.boot.qemu')) -join ''
    if ($isQemu.Trim() -eq '1' -or $bootQemu.Trim() -eq '1' -or $Serial -match '^emulator-') {
        throw 'target must be a physical device, not an emulator'
    }

    if (-not $SkipBuild) {
        Push-Location $repoRoot
        try {
            $assembleTask = if ($BuildVariant -eq 'Benchmark') { 'assembleBenchmark' } else { 'assembleDebug' }
            & .\gradlew.bat $assembleTask --no-daemon --console=plain
            if ($LASTEXITCODE -ne 0) { throw "$assembleTask failed" }
        } finally {
            Pop-Location
        }
        $apkPath = if ($BuildVariant -eq 'Benchmark') {
            Join-Path $repoRoot 'app/build/outputs/apk/benchmark/app-benchmark.apk'
        } else {
            Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk'
        }
        if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
            throw 'debug APK was not produced'
        }
        Invoke-Adb -AdbArguments @('-s', $Serial, 'install', '-r', '-t', $apkPath) | Out-Null
    }

    $packagePath = Invoke-Adb -AdbArguments @(
        '-s', $Serial, 'shell', 'pm', 'path', $PackageName
    ) -AllowFailure
    if (-not ($packagePath -match '^package:')) {
        throw 'target package is not installed; run without -SkipBuild to install while preserving data'
    }

    $uid = Get-AppUid
    $trafficBefore = Get-UidNetworkBytes -Uid $uid
    Clear-BenchmarkLogs
    Reset-FrameStats
    $memoryBefore = Get-ProcessMemInfo
    # A warm launcher intent resets the current in-app navigation route on this app. Preserve an
    # already verified playback page so the benchmark does not invalidate its own precondition.
    if (-not (Test-PlaybackPageSafely)) {
        Invoke-Adb -AdbArguments @(
            '-s', $Serial, 'shell', 'am', 'start', '-W', '-n', $ActivityName
        ) | Out-Null
    }
    if (-not (Wait-ForInitialPlayback)) {
        Write-FailureReport -Reason 'Could not safely confirm the playback page. Enter it manually and rerun; no blind taps were sent.' -ReportPath $reportPath
        Write-Output "SWIPE_BENCHMARK_RESULT=FAIL"
        Write-Output "REPORT=$reportPath"
        exit 3
    }

    Clear-BenchmarkLogs
    $sizeLines = Invoke-Adb -AdbArguments @('-s', $Serial, 'shell', 'wm', 'size')
    $sizeText = $sizeLines -join "`n"
    $sizeMatches = [regex]::Matches($sizeText, '(?<width>\d+)x(?<height>\d+)')
    if ($sizeMatches.Count -eq 0) { throw 'could not determine physical display size' }
    $sizeMatch = $sizeMatches[$sizeMatches.Count - 1]
    $width = [int]$sizeMatch.Groups['width'].Value
    $height = [int]$sizeMatch.Groups['height'].Value
    $x = [Math]::Floor($width * 0.5)
    $startY = if ($Direction -eq 'Forward') {
        [Math]::Floor($height * 0.75)
    } else {
        [Math]::Floor($height * 0.25)
    }
    $endY = if ($Direction -eq 'Forward') {
        [Math]::Floor($height * 0.25)
    } else {
        [Math]::Floor($height * 0.75)
    }
    $gestureDuration = if ($Mode -eq 'Fast') { 80 } else { 150 }
    $timedOut = $false
    $attempts = [Collections.Generic.List[object]]::new()
    [int[]]$fastBatches = if ($Mode -eq 'Fast') {
        @(Get-CvfFastSwipeBatches -SwipeCount $SwipeCount -CheckpointEvery $FastCheckpointEvery)
    } else {
        @()
    }

    if ($Mode -eq 'Normal') {
        for ($index = 1; $index -le $SwipeCount; $index += 1) {
            if ($WatchMillis -gt 0) { Start-Sleep -Milliseconds $WatchMillis }
            Assert-PlaybackPageBeforeGesture
            $beforeSummary = ConvertFrom-CvfBenchmarkLog -Lines @(Get-MainLogLines) -PackageName $PackageName
            $beforeCount = Get-TerminalCount -Summary $beforeSummary
            Wake-BenchmarkDisplay
            Invoke-Adb -AdbArguments @(
                '-s', $Serial, 'shell', 'input', 'swipe',
                "$x", "$startY", "$x", "$endY", "$gestureDuration"
            ) | Out-Null
            $previousReady = if ($ReportStage -in @('stage26', 'stage27')) { $beforeSummary.PlayableReadyMetric.Count } else { -1 }
            $completed = Wait-ForTerminalAfter -PreviousCount $beforeCount -PreviousReadyCount $previousReady -TimeoutSeconds $PerSwipeTimeoutSeconds
            $attempts.Add([pscustomobject]@{ Attempt = $index; Gestures = 1; TerminalObserved = $completed; TimeoutSeconds = $PerSwipeTimeoutSeconds })
            $attempts | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath "$evidencePath.attempts.json" -Encoding UTF8
            if (-not $completed) {
                $timedOut = $true
            }
            Start-Sleep -Milliseconds 250
        }
    } else {
        for ($batchIndex = 0; $batchIndex -lt $fastBatches.Count; $batchIndex += 1) {
            Assert-PlaybackPageBeforeGesture
            $beforeSummary = ConvertFrom-CvfBenchmarkLog -Lines @(Get-MainLogLines) -PackageName $PackageName
            $beforeCount = Get-TerminalCount -Summary $beforeSummary
            for ($index = 1; $index -le $fastBatches[$batchIndex]; $index += 1) {
                Wake-BenchmarkDisplay
                Invoke-Adb -AdbArguments @(
                    '-s', $Serial, 'shell', 'input', 'swipe',
                    "$x", "$startY", "$x", "$endY", "$gestureDuration"
                ) | Out-Null
                Start-Sleep -Milliseconds 100
            }
            $completed = Wait-ForTerminalAfter -PreviousCount $beforeCount -TimeoutSeconds $PerSwipeTimeoutSeconds
            $attempts.Add([pscustomobject]@{ Attempt = $batchIndex + 1; Gestures = $fastBatches[$batchIndex]; TerminalObserved = $completed; TimeoutSeconds = $PerSwipeTimeoutSeconds })
            $attempts | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath "$evidencePath.attempts.json" -Encoding UTF8
            if (-not $completed) {
                $timedOut = $true
            }
            if ($batchIndex -lt $fastBatches.Count - 1) {
                Start-Sleep -Milliseconds 250
            }
        }
        if (-not $timedOut) { Start-Sleep -Milliseconds 750 }
    }

    $mainLines = @(Get-MainLogLines)
    $attempts | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath "$evidencePath.attempts.json" -Encoding UTF8
    $crashLines = @(Get-CrashLogLines)
    $allLines = @($mainLines) + @($crashLines)
    $summary = ConvertFrom-CvfBenchmarkLog -Lines $allLines -PackageName $PackageName
    $memoryAfter = Get-ProcessMemInfo
    $frameStats = Get-FrameStats
    $trafficAfter = Get-UidNetworkBytes -Uid $uid
    $safeEvidence = Protect-CvfBenchmarkLog -Lines $mainLines
    @($safeEvidence) + @("CVF-Benchmark crashCount=$($summary.CrashCount)") |
        Set-Content -LiteralPath $evidencePath -Encoding UTF8

    $gestureMetric = $summary.Metrics.gestureToTerminalMs
    $bindMetric = $summary.Metrics.bindToTerminalMs
    $directionConfirmed = Test-CvfRequestedDirection -Summary $summary -Direction $Direction
    $enoughSamples = if ($Mode -eq 'Normal') {
        $summary.SuccessfulSampleCount -eq $SwipeCount -and
            ($ReportStage -notin @('stage26', 'stage27') -or $summary.PlayableReadyMetric.Count -eq $SwipeCount)
    } else {
        $summary.SuccessfulSampleCount -ge 1
    }
    $cleanRun = $summary.OutcomeCounts.FAILED -eq 0 -and
        $summary.OutcomeCounts.UNSUPPORTED -eq 0 -and
        $summary.RebufferCount -eq 0 -and
        $summary.CrashCount -eq 0 -and
        -not $timedOut
    $result = if (
        -not $enoughSamples -or
        -not $cleanRun -or
        -not $summary.RandomOrderConfirmed -or
        -not $directionConfirmed -or
        -not $summary.RequiredFieldsComplete -or
        -not $summary.StartupRangeObservationComplete
    ) {
        'FAIL'
    } else {
        'PASS'
    }

    $metricLabels = [ordered]@{
        gestureToReleaseMs = 'gesture→release'
        gestureToTargetKnownMs = 'gesture→target-known'
        releaseToSettleMs = 'release→settle'
        targetKnownToSettleMs = 'target-known→settle'
        targetKnownToPlanReadyMs = 'target-known→plan-ready'
        planReadyToSettleMs = 'plan-ready→settle'
        settleToPlanMs = 'settle→plan'
        planAgeMs = 'plan age'
        refreshMs = 'message refresh'
        planToBindMs = 'plan→bind'
        bindToPrepareMs = 'bind→prepare'
        prepareToReadyMs = 'prepare→READY'
        bindToFirstByteMs = 'bind→first-byte'
        firstByteToReadyMs = 'first-byte→READY'
        bindToReadyMs = 'bind→READY'
        readyToFirstFrameMs = 'READY→first-frame'
        bindToTerminalMs = 'bind→first-frame'
        releaseToTerminalMs = 'release→first-frame'
        targetKnownToTerminalMs = 'target-known→first-frame'
        gestureToTerminalMs = 'gesture→first-frame'
    }
    $report = [System.Collections.Generic.List[string]]::new()
    $report.Add("# $reportTitle")
    $report.Add('')
    $report.Add("- Result: $result")
    $report.Add("- Mode: $Mode")
    $report.Add("- Build variant: $BuildVariant")
    $report.Add("- Requested direction: $Direction")
    $report.Add("- Requested swipes: $SwipeCount")
    $report.Add("- Fast batch sizes: $(if ($Mode -eq 'Fast') { $fastBatches -join ',' } else { 'n/a' })")
    $report.Add("- Successful first-frame samples: $($summary.SuccessfulSampleCount)")
    $report.Add("- Attempt records including timeouts: $($attempts.Count); the evidence .attempts.json retains every attempted stable target/batch.")
    $report.Add('- Latency percentiles below describe successful display callbacks only. Any failure/timeout invalidates an all-switch performance claim; never remove it from the report.')
    $report.Add("- Per-swipe timeout: ${PerSwipeTimeoutSeconds}s")
    $report.Add("- Additional viewing time before each normal swipe: ${WatchMillis}ms")
    if ($ReportStage -in @('stage26', 'stage27')) { $report.Add('- Normal protocol waits for visible first frame AND actual playable READY before its next viewing interval; all requested targets must reach READY for a pass.') }
    $report.Add('- Physical device and installed package: verified')
    $report.Add('- App data/cache/network/VPN: unchanged by this script')
    $report.Add('- Display: idempotent WAKEUP sent immediately before each verified gesture')
    $report.Add("- RANDOM order confirmed: $($summary.RandomOrderConfirmed)")
    $report.Add("- Requested direction confirmed: $directionConfirmed")
    $report.Add("- Required metric fields complete: $($summary.RequiredFieldsComplete)")
    $report.Add("- Startup range observation complete: $($summary.StartupRangeObservationComplete)")
    $report.Add('')
    $report.Add('## Outcomes')
    $report.Add('')
    $report.Add('| Outcome | Count |')
    $report.Add('|---|---:|')
    foreach ($outcome in @('FIRST_FRAME', 'FAILED', 'UNSUPPORTED', 'SUPERSEDED', 'UNCHANGED', 'RELEASED')) {
        $report.Add("| $outcome | $($summary.OutcomeCounts.$outcome) |")
    }
    $report.Add('')
    $report.Add('## RANDOM context')
    $report.Add('')
    $report.Add("- order RANDOM/LATEST/UNKNOWN: $($summary.OrderCounts.RANDOM)/$($summary.OrderCounts.LATEST)/$($summary.OrderCounts.UNKNOWN)")
    $report.Add("- direction FORWARD/REVERSE/INITIAL/UNCHANGED/UNKNOWN: $($summary.DirectionCounts.FORWARD)/$($summary.DirectionCounts.REVERSE)/$($summary.DirectionCounts.INITIAL)/$($summary.DirectionCounts.UNCHANGED)/$($summary.DirectionCounts.UNKNOWN)")
    $report.Add("- random round boundaries: $($summary.RandomRoundBoundaryCount)/$($summary.RandomRoundBoundaryEligibleCount)")
    $report.Add(
        "- random boundary plans atomically promoted: " +
            "$($summary.RandomRoundBoundaryPlanPromotedCount)/" +
            "$($summary.RandomRoundBoundaryPlanPromotedEligibleCount) " +
            "($($summary.RandomRoundBoundaryPlanPromotedRatePercent)%)"
    )
    $report.Add("- refresh SUCCESS/FALLBACK/SKIPPED/UNKNOWN: $($summary.RefreshOutcomeCounts.SUCCESS)/$($summary.RefreshOutcomeCounts.FALLBACK)/$($summary.RefreshOutcomeCounts.SKIPPED)/$($summary.RefreshOutcomeCounts.UNKNOWN)")
    $report.Add("- transparent recovery attempts: $($summary.TransparentRecoveryAttemptCount) across $($summary.TransparentRecoveryEligibleCount) terminal samples")
    $report.Add(
        "- transparent recovery REBOUND/SOFT_TIMEOUT/UNAVAILABLE/MESSAGE_UNAVAILABLE/STALE_REFERENCE/REFRESHED_FILE_UNAVAILABLE/UNKNOWN: " +
            "$($summary.TransparentRecoveryOutcomeCounts.REBOUND)/" +
            "$($summary.TransparentRecoveryOutcomeCounts.SOFT_TIMEOUT)/" +
            "$($summary.TransparentRecoveryOutcomeCounts.UNAVAILABLE)/" +
            "$($summary.TransparentRecoveryOutcomeCounts.MESSAGE_UNAVAILABLE)/" +
            "$($summary.TransparentRecoveryOutcomeCounts.STALE_REFERENCE)/" +
            "$($summary.TransparentRecoveryOutcomeCounts.REFRESHED_FILE_UNAVAILABLE)/" +
            "$($summary.TransparentRecoveryOutcomeCounts.UNKNOWN)"
    )
    $report.Add('')
    $report.Add('## Segments (FIRST_FRAME only, nearest-rank)')
    $report.Add('')
    $report.Add('| Segment | N | P50 | P90 | P95 | max |')
    $report.Add('|---|---:|---:|---:|---:|---:|')
    foreach ($metricName in $metricLabels.Keys) {
        $metric = $summary.Metrics.$metricName
        $report.Add(
            "| $($metricLabels[$metricName]) | $($metric.Count) | " +
                "$(Format-MetricValue $metric.P50) | $(Format-MetricValue $metric.P90) | " +
                "$(Format-MetricValue $metric.P95) | " +
                "$(Format-MetricValue $metric.Max) |"
        )
    }
    $report.Add('')
    $report.Add('## Safety and regression counters')
    $report.Add('')
    $report.Add("- promoted: $($summary.PromotedCount)/$($summary.PromotedEligibleCount) ($($summary.PromotedRatePercent)%)")
    $report.Add("- preload yield/resume: $($summary.PreloadYieldCount)/$($summary.PreloadResumeCount)")
    $report.Add("- promotion attempt/matched/terminal: $($summary.PromotionAttemptCount)/$($summary.PromotionMatchedCount)/$($summary.PromotionTerminalCount)")
    $report.Add("- reused active request: $($summary.ReusedActiveRequestCount)")
    $report.Add("- Telegram scheduler REUSED_ACTIVE: $($summary.SchedulerActiveRequestReuseCount)")
    $report.Add(
        "- first uncached DataSpec HEAD/TAIL/MIDDLE/UNKNOWN/NONE: " +
            "$($summary.FirstMissCategoryCounts.HEAD)/$($summary.FirstMissCategoryCounts.TAIL)/" +
            "$($summary.FirstMissCategoryCounts.MIDDLE)/$($summary.FirstMissCategoryCounts.UNKNOWN)/" +
            "$($summary.FirstMissCategoryCounts.NONE)"
    )
    $covered = $summary.CoveredBeforeCurrentMetric
    $report.Add(
        "- bytes covered before current N/P50/P90/max: $($covered.Count)/" +
            "$($covered.P50)/$($covered.P90)/$($covered.Max) bytes"
    )
    $speculative = $summary.SpeculativeCoveredMetric
    $extra = $summary.SpeculativeExtraMetric
    $completedExtra = $summary.SpeculativeCompletedExtraMetric
    $report.Add(
        "- speculative covered N/P50/P90/max/total: $($speculative.Count)/" +
            "$($speculative.P50)/$($speculative.P90)/$($speculative.Max)/" +
            "$($speculative.Total) bytes"
    )
    $report.Add(
        "- speculative requested extra N/P50/P90/max/total: $($extra.Count)/" +
            "$($extra.P50)/$($extra.P90)/$($extra.Max)/$($extra.Total) bytes"
    )
    $report.Add(
        "- speculative completed extra N/P50/P90/max/total: $($completedExtra.Count)/" +
            "$($completedExtra.P50)/$($completedExtra.P90)/$($completedExtra.Max)/" +
            "$($completedExtra.Total) bytes"
    )
    $report.Add(
        "- current request reused next owner: $($summary.CurrentReusedNextOwnerCount)/" +
            "$($summary.CurrentReusedNextOwnerEligibleCount)"
    )
    $report.Add(
        "- extractor switches / Telegram switches / merges / cancels: " +
            "$($summary.ExtractorRangeSwitchCount)/$($summary.TelegramRangeSwitchCount)/" +
            "$($summary.TelegramRangeMergeCount)/$($summary.TelegramRangeCancelCount)"
    )
    $report.Add("- NO_PROGRESS timeouts: $($summary.NoProgressTimeoutCount)")
    $report.Add("- cancelled before current acquire: $($summary.CancelledBeforeCurrentAcquireCount)")
    $handoff = $summary.OwnerHandoffMetric
    $report.Add(
        "- owner handoff N/P50/P90/max: $($handoff.Count)/" +
            "$(Format-MetricValue $handoff.P50)/$(Format-MetricValue $handoff.P90)/" +
            "$(Format-MetricValue $handoff.Max)"
    )
    $report.Add("- rebuffer/crash: $($summary.RebufferCount)/$($summary.CrashCount)")
    $report.Add("- Actual playable READY N/P50/P95/max: $($summary.PlayableReadyMetric.Count)/$($summary.PlayableReadyMetric.P50)/$($summary.PlayableReadyMetric.P95)/$($summary.PlayableReadyMetric.Max) ms. A visible still frame may precede READY; missing readiness is not zero wait.")
    $report.Add("- preload hit rate: $($summary.PreloadHitRatePercent)%")
    $waste = $summary.SkippedNextWastedMetric
    $report.Add("- skipped-next wasted bytes N/P50/P90/max/total: $($waste.Count)/$($waste.P50)/$($waste.P90)/$($waste.Max)/$($waste.Total)")
    $report.Add("- feed first emission (subscription latency, not SQL execution) P50/P90: $($summary.FeedInitialEmissionMetric.P50)/$($summary.FeedInitialEmissionMetric.P90) ms")
    $report.Add("- feed hydration P50/P90: $($summary.FeedHydrationMetric.P50)/$($summary.FeedHydrationMetric.P90) ms")
    $report.Add("- visible first frame P50/P90/P95: $($summary.VisibleFirstFrameMetric.P50)/$($summary.VisibleFirstFrameMetric.P90)/$($summary.VisibleFirstFrameMetric.P95) ms")
    $report.Add("- cache metadata rows committed (not SQL statement count): $($summary.CacheTouchWrites)")
    $report.Add("- TDLib file events received/applied/coalesced: $($summary.TdFileEventsReceived)/$($summary.TdFileEventsApplied)/$($summary.TdFileEventsCoalesced)")
    if ($memoryBefore.Available -and $memoryAfter.Available) {
        $report.Add("- PSS before/after/delta: $($memoryBefore.PssKb)/$($memoryAfter.PssKb)/$($memoryAfter.PssKb - $memoryBefore.PssKb) KiB")
    } else {
        $report.Add('- PSS: 尚未验证（设备未返回可解析的 dumpsys meminfo TOTAL）')
    }
    if ($frameStats.Available) {
        $report.Add("- gfxinfo total/janky/rate: $($frameStats.TotalFrames)/$($frameStats.JankyFrames)/$($frameStats.JankyRatePercent)%")
    } else {
        $report.Add('- gfxinfo jank: 尚未验证（设备未返回可解析的帧统计）')
    }
    if ($null -ne $trafficBefore -and $null -ne $trafficAfter) {
        $rxDelta = [Math]::Max(0L, $trafficAfter.RxBytes - $trafficBefore.RxBytes)
        $txDelta = [Math]::Max(0L, $trafficAfter.TxBytes - $trafficBefore.TxBytes)
        $report.Add("- UID traffic delta (all app activity in window): rx=$rxDelta bytes, tx=$txDelta bytes")
    } else {
        $report.Add('- UID traffic delta: 尚未验证（设备未提供可安全聚合的 UID netstats）')
    }
    $report.Add("- $comparisonGuidance")
    $report.Add('- Evidence is redacted; no Telegram content, names, paths, device/network identifiers, addresses, or credentials are stored.')
    $report | Set-Content -LiteralPath $reportPath -Encoding UTF8
    [pscustomobject]@{
        schemaVersion = 1
        reportStage = $ReportStage
        buildVariant = $BuildVariant
        mode = $Mode
        direction = $Direction
        requestedSwipes = $SwipeCount
        watchMillis = $WatchMillis
        summary = $summary
        memory = [pscustomobject]@{
            beforePssKb = if ($memoryBefore.Available) { $memoryBefore.PssKb } else { $null }
            afterPssKb = if ($memoryAfter.Available) { $memoryAfter.PssKb } else { $null }
        }
        frameStats = $frameStats
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $summaryPath -Encoding UTF8

    Write-Output "SWIPE_BENCHMARK_RESULT=$result"
    Write-Output "REPORT=$reportPath"
    Write-Output "EVIDENCE=$evidencePath"
    Write-Output "SUMMARY=$summaryPath"
    if ($result -eq 'FAIL') { exit 5 }
} catch {
    Protect-CvfBenchmarkLog -Lines @(Get-MainLogLines) |
        Set-Content -LiteralPath $evidencePath -Encoding UTF8
    $attempts | ConvertTo-Json -Depth 3 |
        Set-Content -LiteralPath "$evidencePath.attempts.json" -Encoding UTF8
    Write-FailureReport -Reason $_.Exception.Message -ReportPath $reportPath
    Write-Output 'SWIPE_BENCHMARK_RESULT=FAIL'
    Write-Output "REPORT=$reportPath"
    exit 2
}
