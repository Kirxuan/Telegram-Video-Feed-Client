$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$modulePath = Join-Path (Split-Path $PSScriptRoot -Parent) 'SwipeFirstFrameBenchmark.psm1'
Import-Module $modulePath -Force

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if ($Expected -ne $Actual) {
        throw "$Message Expected=[$Expected] Actual=[$Actual]"
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) { throw $Message }
}

$percentileInput = @(100L, 200L, 300L, 400L, 500L)
Assert-Equal 300L (Get-NearestRankPercentile -Values $percentileInput -Percentile 50) 'P50'
Assert-Equal 500L (Get-NearestRankPercentile -Values $percentileInput -Percentile 90) 'P90'
Assert-Equal 500L (Get-NearestRankPercentile -Values $percentileInput -Percentile 95) 'P95'

$defaultFastBatches = @(Get-CvfFastSwipeBatches -SwipeCount 10 -CheckpointEvery 0)
$checkpointFastBatches = @(Get-CvfFastSwipeBatches -SwipeCount 10 -CheckpointEvery 3)
Assert-Equal '10' ($defaultFastBatches -join ',') 'default Fast semantics remain one uninterrupted batch'
Assert-Equal '3,3,3,1' ($checkpointFastBatches -join ',') 'checkpoint Fast keeps all ten requested gestures'

$lines = @(
    '07-30 12:00:00.000 I/CVF-Transition: summary outcome=FIRST_FRAME order=RANDOM direction=FORWARD randomRoundBoundary=false chatId=11 messageId=101 refreshOutcome=SUCCESS transparentRecoveryAttempts=1 transparentRecoveryOutcome=REBOUND promoted=true planAgeMs=40 gestureToReleaseMs=150 releaseToSettleMs=470 targetKnownToPlanReadyMs=0 planReadyToSettleMs=470 settleToPlanMs=0 refreshMs=25 planToBindMs=5 bindToFirstByteMs=30 bindToReadyMs=150 bindToTerminalMs=200 releaseToTerminalMs=700 targetKnownToTerminalMs=650 gestureToTerminalMs=850',
    '07-30 12:00:01.000 I/CVF-Transition: summary outcome=FIRST_FRAME order=RANDOM direction=REVERSE randomRoundBoundary=true chatId=12 messageId=102 refreshOutcome=FALLBACK transparentRecoveryAttempts=0 transparentRecoveryOutcome=null promoted=true planAgeMs=45 gestureToReleaseMs=152 releaseToSettleMs=472 targetKnownToPlanReadyMs=1 planReadyToSettleMs=471 settleToPlanMs=0 refreshMs=35 planToBindMs=6 bindToFirstByteMs=40 bindToReadyMs=250 bindToTerminalMs=300 releaseToTerminalMs=800 targetKnownToTerminalMs=750 gestureToTerminalMs=950',
    '07-30 12:00:02.000 I/CVF-Transition: summary outcome=FAILED chatId=13 messageId=103 promoted=false gestureToReleaseMs=154 bindToTerminalMs=900 gestureToTerminalMs=1400',
    '07-30 12:00:02.100 I/CVF-Preload: action=YIELD state=OFF reason=CURRENT_NOT_STABLE',
    '07-30 12:00:02.200 I/CVF-Preload: action=RESUME state=CONSERVATIVE candidate=BASELINE bytes=262144 requestedExtraBytes=0',
    '07-30 12:00:02.210 I/CVF-Preload: promotionAttempt=true promotionMatched=true',
    '07-30 12:00:02.220 I/CVF-Preload: promotionTerminal=true promotionMatched=true reusedActiveRequest=true cancelledBeforeCurrentAcquire=false ownerHandoffMs=18',
    '07-30 12:00:02.230 I/CVF-TdFile: request reprioritize fileId=9 owner=CURRENT_PLAYBACK priority=NEXT_PRELOAD->CURRENT_STARTUP offset=0 limit=262144 result=REUSED_ACTIVE',
    '07-30 12:00:02.231 I/CVF-StartupRange: summary candidate=BASELINE firstMissCategory=TAIL coveredBeforeCurrentBytes=262144 dataSpecOpenCount=2 extractorRangeSwitchCount=1 currentReusedNextOwner=false firstByteToReadyMs=120',
    '07-30 12:00:02.232 I/CVF-StartupRange: summary candidate=BASELINE firstMissCategory=HEAD coveredBeforeCurrentBytes=0 dataSpecOpenCount=1 extractorRangeSwitchCount=0 currentReusedNextOwner=true firstByteToReadyMs=210',
    '07-30 12:00:02.233 I/CVF-Preload: action=CANDIDATE_READY candidate=BASELINE headBytes=262144 tailBytes=0 totalBytes=262144 extraBytes=0',
    '07-30 12:00:02.234 I/CVF-TdFile: request begin fileId=9 owner=CURRENT_PLAYBACK priority=CURRENT_STARTUP offset=0 limit=262144 result=SWITCH',
    '07-30 12:00:02.235 I/CVF-TdFile: request begin fileId=9 owner=CURRENT_PLAYBACK priority=CURRENT_STARTUP offset=0 limit=262144 result=MERGE',
    '07-30 12:00:02.236 I/CVF-TdFile: cancel fileId=9 result=DISJOINT_SWITCH',
    '07-30 12:00:02.237 I/CVF-TdFile: range timeout fileId=9 offset=0 length=262144 waitMs=15000 firstByteMs=null progressBytes=0 reason=NO_PROGRESS',
    '07-30 12:00:02.238 I/CVF-Preload: action=DECISION skippedNextWastedBytes=131072',
    '07-30 12:00:02.239 I/CVF-FeedPerf: summary firstEmissionMs=12 hydrateMs=7 visibleFirstFrameMs=44 snapshotKeys=80 hydratedItems=9',
    '07-30 12:00:02.240 I/CVF-CachePerf: summary touchWrites=3 eventReceived=20 eventApplied=8 eventCoalesced=12',
    '07-30 12:00:02.300 I/CVF-Player: state state=BUFFERING rebufferCount=1 activeRebufferMs=0',
    '07-30 12:00:02.400 E/AndroidRuntime: FATAL EXCEPTION: main',
    '07-30 12:00:02.401 E/AndroidRuntime: Process: com.qixuan.channelvideoflow, PID: 1234'
)

$summary = ConvertFrom-CvfBenchmarkLog -Lines $lines -PackageName 'com.qixuan.channelvideoflow'
Assert-Equal 2 $summary.OutcomeCounts.FIRST_FRAME 'first frame count'
Assert-Equal 1 $summary.OutcomeCounts.FAILED 'failed count'
Assert-Equal 200L $summary.Metrics.bindToTerminalMs.P50 'bind P50'
Assert-Equal 300L $summary.Metrics.bindToTerminalMs.P90 'bind P90'
Assert-Equal 300L $summary.Metrics.bindToTerminalMs.P95 'bind P95'
Assert-Equal 2 $summary.PromotedCount 'promoted count'
Assert-Equal 2 $summary.PromotedEligibleCount 'promoted denominator only uses successful samples'
Assert-Equal 2 $summary.OrderCounts.RANDOM 'random order count'
Assert-Equal 1 $summary.DirectionCounts.FORWARD 'forward direction count'
Assert-Equal 1 $summary.DirectionCounts.REVERSE 'reverse direction count'
Assert-Equal 1 $summary.RandomRoundBoundaryCount 'random round boundary count'
Assert-Equal 1 $summary.RandomRoundBoundaryPlanPromotedCount 'random boundary promoted-plan count'
Assert-Equal 1 $summary.RandomRoundBoundaryPlanPromotedEligibleCount 'random boundary promoted-plan denominator'
Assert-Equal 100 $summary.RandomRoundBoundaryPlanPromotedRatePercent 'random boundary promoted-plan rate'
Assert-Equal 1 $summary.RefreshOutcomeCounts.SUCCESS 'refresh success count'
Assert-Equal 1 $summary.RefreshOutcomeCounts.FALLBACK 'refresh fallback count'
Assert-Equal 1 $summary.TransparentRecoveryAttemptCount 'transparent recovery attempt count'
Assert-Equal 2 $summary.TransparentRecoveryEligibleCount 'transparent recovery eligible count'
Assert-Equal 1 $summary.TransparentRecoveryOutcomeCounts.REBOUND 'transparent recovery rebound count'
Assert-Equal 30L $summary.Metrics.bindToFirstByteMs.P50 'bind to first byte P50'
Assert-Equal 120L $summary.Metrics.firstByteToReadyMs.P50 'first byte to READY P50'
Assert-Equal 210L $summary.Metrics.firstByteToReadyMs.P90 'first byte to READY P90'
Assert-Equal 25L $summary.Metrics.refreshMs.P50 'refresh P50'
Assert-True $summary.RandomOrderConfirmed 'successful samples must prove RANDOM'
Assert-True $summary.RequiredFieldsComplete 'complete specialized fields'
Assert-Equal 1 $summary.PreloadYieldCount 'preload yield count'
Assert-Equal 1 $summary.PreloadResumeCount 'preload resume count'
Assert-Equal 1 $summary.PromotionAttemptCount 'promotion attempt count'
Assert-Equal 1 $summary.PromotionMatchedCount 'promotion matched count'
Assert-Equal 1 $summary.PromotionTerminalCount 'promotion terminal count'
Assert-Equal 1 $summary.ReusedActiveRequestCount 'active request reuse count'
Assert-Equal 0 $summary.CancelledBeforeCurrentAcquireCount 'matched owner must survive current acquire'
Assert-Equal 1 $summary.SchedulerActiveRequestReuseCount 'Telegram scheduler active request reuse'
Assert-Equal 1 $summary.FirstMissCategoryCounts.HEAD 'HEAD first miss count'
Assert-Equal 1 $summary.FirstMissCategoryCounts.TAIL 'TAIL first miss count'
Assert-Equal 2 $summary.StartupRangeSummaryCount 'startup summary count'
Assert-True $summary.StartupRangeObservationComplete 'startup observation must cover successes'
Assert-Equal 262144L $summary.CoveredBeforeCurrentMetric.P90 'covered-before-current P90'
Assert-Equal 262144L $summary.SpeculativeCoveredMetric.P90 'speculative covered P90'
Assert-Equal 0L $summary.SpeculativeExtraMetric.Total 'baseline requested extra bytes'
Assert-Equal 0L $summary.SpeculativeCompletedExtraMetric.Total 'baseline completed extra bytes'
Assert-Equal 1 $summary.CurrentReusedNextOwnerCount 'per-bind reused next owner count'
Assert-Equal 1L $summary.ExtractorRangeSwitchCount 'extractor range switch count'
Assert-Equal 1 $summary.TelegramRangeSwitchCount 'Telegram range switch count'
Assert-Equal 1 $summary.TelegramRangeMergeCount 'Telegram range merge count'
Assert-Equal 1 $summary.TelegramRangeCancelCount 'Telegram range cancel count'
Assert-Equal 1 $summary.NoProgressTimeoutCount 'no-progress timeout count'
Assert-Equal 18L $summary.OwnerHandoffMetric.P90 'owner handoff P90'
Assert-Equal 1 $summary.RebufferCount 'rebuffer count'
Assert-Equal 131072L $summary.SkippedNextWastedMetric.Total 'skipped next waste bytes'
Assert-Equal 50 $summary.PreloadHitRatePercent 'preload hit rate'
Assert-Equal 12L $summary.FeedInitialEmissionMetric.P50 'feed first emission (subscription latency, not SQL execution) P50'
Assert-Equal 7L $summary.FeedHydrationMetric.P50 'feed hydration P50'
Assert-Equal 44L $summary.VisibleFirstFrameMetric.P50 'visible first frame P50'
Assert-Equal 3L $summary.CacheTouchWrites 'cache touch writes'
Assert-Equal 20L $summary.TdFileEventsReceived 'TDLib file events received'
Assert-Equal 8L $summary.TdFileEventsApplied 'TDLib file events applied'
Assert-Equal 12L $summary.TdFileEventsCoalesced 'TDLib file events coalesced'
Assert-Equal 1 $summary.CrashCount 'target package crash count'
Assert-True (-not (Test-CvfRequestedDirection -Summary $summary -Direction Forward)) 'mixed directions cannot confirm Forward'
Assert-True (-not (Test-CvfRequestedDirection -Summary $summary -Direction Reverse)) 'mixed directions cannot confirm Reverse'

$candidateAttempt = ConvertFrom-CvfBenchmarkLog -Lines @(
    '07-30 12:00:03.000 I/CVF-Preload: action=START state=NORMAL candidate=HEAD_512_WIFI bytes=524288 requestedExtraBytes=262144'
) -PackageName 'com.qixuan.channelvideoflow'
Assert-Equal 262144L $candidateAttempt.SpeculativeExtraMetric.Total 'timed-out candidate still reports requested extra bytes'
Assert-Equal 0L $candidateAttempt.SpeculativeCompletedExtraMetric.Total 'timed-out candidate reports no completed extra bytes'

$forwardOnly = ConvertFrom-CvfBenchmarkLog -Lines @($lines[0]) -PackageName 'com.qixuan.channelvideoflow'
$reverseOnly = ConvertFrom-CvfBenchmarkLog -Lines @($lines[1]) -PackageName 'com.qixuan.channelvideoflow'
Assert-True (Test-CvfRequestedDirection -Summary $forwardOnly -Direction Forward) 'all-forward samples confirm Forward'
Assert-True (Test-CvfRequestedDirection -Summary $reverseOnly -Direction Reverse) 'all-reverse samples confirm Reverse'

$randomSelectedTree = @'
<?xml version="1.0" encoding="UTF-8"?>
<hierarchy><node selected="true"><node text="随机" selected="false" /></node></hierarchy>
UI hierarchy dumped
'@
$latestSelectedTree = @'
<?xml version="1.0" encoding="UTF-8"?>
<hierarchy><node selected="true"><node text="最新" selected="false" /></node><node selected="false"><node text="随机" selected="false" /></node></hierarchy>
UI hierarchy dumped
'@
$unrelatedSelectedAncestorTree = @'
<?xml version="1.0" encoding="UTF-8"?>
<hierarchy><node selected="true"><node><node text="随机" selected="false" /></node></node></hierarchy>
UI hierarchy dumped
'@
Assert-True (Test-CvfRandomSelectedUiTree -UiTree $randomSelectedTree) 'selected RANDOM parent semantics must be recognized'
Assert-True (-not (Test-CvfRandomSelectedUiTree -UiTree $latestSelectedTree)) 'LATEST selection must not satisfy RANDOM UI gate'
Assert-True (
    -not (Test-CvfRandomSelectedUiTree -UiTree $unrelatedSelectedAncestorTree)
) 'an unrelated selected ancestor must not satisfy RANDOM UI gate'

$sanitized = Protect-CvfBenchmarkLog -Lines $lines
$sanitizedText = $sanitized -join "`n"
Assert-True ($sanitizedText -notmatch 'chatId=11') 'chatId must be redacted'
Assert-True ($sanitizedText -notmatch 'messageId=101') 'messageId must be redacted'
Assert-True ($sanitizedText -notmatch 'PID: 1234') 'PID must be redacted'
Assert-True ($sanitizedText -notmatch 'Process: com\.qixuan') 'process details must not enter evidence'

$empty = ConvertFrom-CvfBenchmarkLog -Lines @() -PackageName 'com.qixuan.channelvideoflow'
Assert-Equal 0 $empty.SuccessfulSampleCount 'empty logs cannot manufacture samples'
Assert-True (-not $empty.RandomOrderConfirmed) 'empty logs cannot prove RANDOM'
Assert-True (-not $empty.RequiredFieldsComplete) 'empty logs cannot pass required fields'
Assert-True (-not (Test-CvfRequestedDirection -Summary $empty -Direction Forward)) 'empty logs cannot confirm direction'

$latest = ConvertFrom-CvfBenchmarkLog -Lines @(
    'I/CVF-Transition: summary outcome=FIRST_FRAME order=LATEST direction=FORWARD randomRoundBoundary=false refreshOutcome=SKIPPED promoted=false planAgeMs=null targetKnownToPlanReadyMs=null planReadyToSettleMs=null settleToPlanMs=0 refreshMs=0 planToBindMs=1 bindToFirstByteMs=2 bindToReadyMs=3 bindToTerminalMs=4'
) -PackageName 'com.qixuan.channelvideoflow'
Assert-True (-not $latest.RandomOrderConfirmed) 'LATEST must never satisfy RANDOM baseline'

$memInfo = ConvertFrom-CvfMemInfo -Lines @(' TOTAL PSS:      123456            TOTAL RSS: 222222')
Assert-True $memInfo.Available 'modern meminfo must parse'
Assert-Equal 123456L $memInfo.PssKb 'PSS kilobytes'
$gfxInfo = ConvertFrom-CvfGfxInfo -Lines @('Total frames rendered: 200', 'Janky frames: 10 (5.00%)')
Assert-True $gfxInfo.Available 'gfxinfo must parse'
Assert-Equal 5 $gfxInfo.JankyRatePercent 'jank rate'

$blankMemInfo = ConvertFrom-CvfMemInfo -Lines @('', ' TOTAL PSS: 123456', '')
Assert-Equal 123456L $blankMemInfo.PssKb 'dumpsys blank lines must be accepted'
Assert-True (-not (ConvertFrom-CvfMemInfo -Lines @('')).Available) 'blank memory output stays unavailable'
Assert-True (-not (ConvertFrom-CvfGfxInfo -Lines @('')).Available) 'blank frame output stays unavailable'
$blankLog = ConvertFrom-CvfBenchmarkLog -Lines @('', ' ') -PackageName 'com.qixuan.channelvideoflow'
Assert-Equal 0 $blankLog.SuccessfulSampleCount 'blank log lines cannot manufacture samples'
Assert-Equal 0 @(Protect-CvfBenchmarkLog -Lines @('')).Count 'blank log lines are not evidence'

$missingFields = ConvertFrom-CvfBenchmarkLog -Lines @(
    'I/CVF-Transition: summary outcome=FIRST_FRAME promoted=true bindToTerminalMs=4'
) -PackageName 'com.qixuan.channelvideoflow'
Assert-True (-not $missingFields.RandomOrderConfirmed) 'missing order cannot prove RANDOM'
Assert-True (-not $missingFields.RequiredFieldsComplete) 'missing fields must fail validation'

$runnerPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'run-swipe-first-frame-benchmark.ps1'
$runner = Get-Content -Raw -LiteralPath $runnerPath
Assert-True ($runner -match 'RandomOrderConfirmed') 'runner must fail when RANDOM is not proven'
Assert-True ($runner -match 'RequiredFieldsComplete') 'runner must fail on missing metric fields'
Assert-True ($runner -match "ValidateSet\('Forward', 'Reverse'\)") 'runner must select swipe direction explicitly'
Assert-True ($runner -match 'directionConfirmed') 'runner must compare observed and requested direction'
Assert-True ($runner -match '-not \$directionConfirmed') 'runner must fail on direction mismatch'
Assert-True ($runner -match 'Test-CvfRequestedDirection') 'runner must use the tested direction check'
Assert-True ($runner -match "'stage25a', 'stage25f', 'stage25g'") 'runner must constrain Stage 25 evidence routes'
Assert-True ($runner -match "ReportStage = 'stage13d'") 'runner must keep stage 13D as its default evidence stage'
Assert-True ($runner -match 'build/reports/\$ReportStage') 'runner must isolate evidence by the requested stage'
Assert-True ($runner -match "'stage13e' \{ 'Stage 13E random reference resolution benchmark' \}") 'runner must label stage 13E evidence explicitly'
Assert-True ($runner -match "'stage13f' \{ 'Stage 13F random final acceptance benchmark' \}") 'runner must label stage 13F evidence explicitly'
Assert-True ($runner -match "'stage18' \{ 'Stage 18 HLS and weak-network continuous playback benchmark' \}") 'runner must label stage 18 evidence explicitly'
Assert-True ($runner -match "ReportStage -eq 'stage13f'") 'stage 13F reports must use stage-specific comparison guidance'
Assert-True ($runner -match "ReportStage -eq 'stage18'") 'stage 18 reports must use controlled A/B guidance'
Assert-True ($runner -match 'SampleQueue requires at least 15% P95 improvement') 'stage 18 guidance must preserve the production A/B gate'
Assert-True ($runner -match 'Stage 13A RANDOM baseline') 'stage 13F reports must preserve the Stage 13A comparison boundary'
Assert-True ($runner -match 'fresh Stage 13E production baseline') 'stage 13F reports must identify the settle comparison baseline'
Assert-True ($runner -match 'function Wake-BenchmarkDisplay') 'runner must prevent verified gestures from becoming wake-only inputs'
Assert-True ($runner -match "keyevent', 'KEYCODE_WAKEUP'") 'display wake must be an idempotent key event'
Assert-True (
    $runner -match 'if \(-not \(Test-PlaybackPageSafely\)\)'
) 'runner must preserve an already verified playback page instead of resetting navigation'
Assert-True ($runner -match 'RandomRoundBoundaryPlanPromotedCount') 'runner must report atomic random-boundary plan promotion'
Assert-True ($runner -match 'PromotionAttemptCount') 'runner must report owner promotion attempts'
Assert-True ($runner -match 'OwnerHandoffMetric') 'runner must report owner handoff latency'
Assert-True ($runner -match 'Test-CvfPlaybackUiTree') 'runner must use the tested playback route gate'
Assert-True (Test-CvfPlaybackUiTree '<?xml version="1.0"?><hierarchy><node package="com.qixuan.channelvideoflow" content-desc="返回频道" /></hierarchy>') 'loading remains a playback route without a play button'
Assert-True (Test-CvfPlaybackUiTree '<?xml version="1.0"?><hierarchy><node package="com.qixuan.channelvideoflow" content-desc="显示播放控制" /></hierarchy>') 'hidden controls remain a playback route'
Assert-True (-not (Test-CvfPlaybackUiTree '<?xml version="1.0"?><hierarchy><node package="other.app" content-desc="返回频道" /></hierarchy>')) 'other apps must never receive a benchmark swipe'
Assert-True (-not (Test-CvfPlaybackUiTree 'ERROR: no idle root')) 'missing UI tree must not authorize a gesture'
Assert-True (
    $runner -notmatch 'if \(\$summary\.SuccessfulSampleCount -gt 0\) \{ return \$true \}'
) 'initial playback gate must not be bypassed by an arbitrary prior first-frame log'
Assert-True ($runner -match '\$FastCheckpointEvery') 'runner must expose the optional Fast checkpoint interval'
Assert-True ($runner -match 'Get-CvfFastSwipeBatches') 'runner must use the tested Fast batch planner'
Assert-True (
    $runner -match '\[int\[\]\]\$fastBatches\s*='
) 'runner must keep a single Fast batch as an array under StrictMode'
Assert-True ($runner -match 'Fast batch sizes') 'report must disclose the measured Fast batch protocol'
Assert-True ($runner -match 'StartupRangeObservationComplete') 'runner must reject missing startup range observation'
Assert-True ($runner -match 'first uncached DataSpec') 'runner must report first uncached DataSpec categories'
Assert-True ($runner -match "ValidateSet\('Debug', 'Benchmark'\)") 'runner must expose release-like benchmark build'
Assert-True ($runner -match 'Get-ProcessMemInfo') 'runner must capture PSS'
Assert-True ($runner -match 'Get-FrameStats') 'runner must capture gfxinfo jank'


$poolSummary = ConvertFrom-CvfBenchmarkLog -Lines @(
    'I/CVF-Transition: summary outcome=FIRST_FRAME poolPromoted=true poolPreparedReady=true bindToTerminalMs=90',
    'I/CVF-Transition: summary outcome=FIRST_FRAME poolPromoted=false poolPreparedReady=false bindToTerminalMs=600',
    'I/CVF-Transition: summary outcome=FAILED poolPromoted=true poolPreparedReady=true bindToTerminalMs=12000'
) -PackageName 'com.qixuan.channelvideoflow'
Assert-Equal 1 $poolSummary.PoolReadyHitSampleCount 'only READY pool hits form the prepared subgroup'
Assert-Equal 90 $poolSummary.PoolReadyHitBindMetric.P95 'pool hit latency does not replace all switches'
Assert-Equal 1 $poolSummary.OutcomeCounts.FAILED 'failed prepared hits stay in outcome counts'

$feedSummary = ConvertFrom-CvfBenchmarkLog -Lines @(
    'I/CVF-FeedPerf: summary firstEmissionMs=12 hydrateMs=null observationGapMs=null snapshotKeys=80 hydratedItems=null',
    'I/CVF-FeedPerf: summary firstEmissionMs=null hydrateMs=null observationGapMs=60000 snapshotKeys=80 hydratedItems=null',
    'I/CVF-FeedPerf: summary firstEmissionMs=null hydrateMs=7 observationGapMs=null snapshotKeys=null hydratedItems=9'
) -PackageName 'com.qixuan.channelvideoflow'
Assert-Equal 1 $feedSummary.FeedInitialEmissionMetric.Count 'idle gaps and hydration are not zero-time queries'
Assert-Equal 12 $feedSummary.FeedInitialEmissionMetric.P95 'subscription latency excludes idle time'
Assert-Equal 1 $feedSummary.FeedHydrationMetric.Count 'unmeasured hydration events do not bias percentiles'
Assert-Equal 7 $feedSummary.FeedHydrationMetric.P95 'hydration includes only actual measurements'

# Exercise the real collector with overlapping/rotated logcat snapshots, without adb.
$collectorAst = [System.Management.Automation.Language.Parser]::ParseInput($runner, [ref]$null, [ref]$null)
$collector = $collectorAst.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-MainLogLines' }, $true)
. ([scriptblock]::Create($collector.Extent.Text))
$mainLogHistory = [Collections.Generic.List[string]]::new()
$mainLogSeen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$mockSnapshot = @('09-10 01:00:00.001 I/CVF-Transition: summary outcome=FIRST_FRAME', '09-10 01:00:01.001 I/CVF-Transition: summary outcome=FAILED')
function Invoke-Adb { param($AdbArguments) return ,$mockSnapshot }
$Serial = 'fixture'
$firstSnapshot = @(Get-MainLogLines)
$mockSnapshot = @($mockSnapshot[1], '09-10 01:00:02.001 I/CVF-Transition: summary outcome=FIRST_FRAME')
$rotatedSnapshot = @(Get-MainLogLines)
Assert-Equal 2 $firstSnapshot.Count 'first snapshot captured'
Assert-Equal 3 $rotatedSnapshot.Count 'rotation preserves earlier outcomes without duplicate overlap'
Assert-Equal $firstSnapshot[0] $rotatedSnapshot[0] 'earliest first frame survives log rotation'

$readiness = ConvertFrom-CvfBenchmarkLog -Lines @(
    'I/CVF-Transition: summary outcome=FIRST_FRAME chatId=1 messageId=2 bindToReadyMs=null bindToTerminalMs=80',
    'I/CVF-Player: playable chatId=1 messageId=2 bindToReadyMs=2500',
    'I/CVF-Player: playable chatId=1 messageId=3 bindToReadyMs=10',
    'I/CVF-Player: state state=BUFFERING rebufferCount=1',
    'I/CVF-Player: sample state=BUFFERING rebufferCount=1',
    'I/CVF-Player: summary state=BUFFERING rebufferCount=1',
    'I/CVF-Player: state state=BUFFERING rebufferCount=0',
    'I/CVF-Player: state state=BUFFERING rebufferCount=1'
) -PackageName 'com.qixuan.channelvideoflow'
Assert-Equal 2 $readiness.RebufferCount 'cumulative log repetitions are not new rebuffer events'
Assert-Equal 1 $readiness.PlayableReadyMetric.Count 'only observed transition targets enter readiness statistics'
Assert-Equal 2500 $readiness.PlayableReadyMetric.P50 'a still frame at 80ms is not playable READY'

# A just-cleared device log has no lines. Exercise the actual wait path, including
# PowerShell's empty-pipeline semantics, rather than feeding a synthetic empty array.
foreach ($name in @('Get-TerminalCount', 'Wait-ForTerminalAfter')) {
    $definition = $collectorAst.Find({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    . ([scriptblock]::Create($definition.Extent.Text))
}
$PackageName = 'com.qixuan.channelvideoflow'
$mainLogHistory.Clear()
$mainLogSeen.Clear()
$mockSnapshot = @()
Assert-Equal $false (Wait-ForTerminalAfter -PreviousCount 0 -TimeoutSeconds 1) 'empty log times out without a parameter-binding crash'
$mockSnapshot = @(
    'I/CVF-Transition: summary outcome=FIRST_FRAME chatId=1 messageId=2 bindToReadyMs=null bindToTerminalMs=80',
    'I/CVF-Player: playable chatId=1 messageId=2 bindToReadyMs=2500'
)
Assert-Equal $true (Wait-ForTerminalAfter -PreviousCount 0 -PreviousReadyCount 0 -TimeoutSeconds 1) 'collection recovers once a real frame and playable event arrive'
$initialWait = $collectorAst.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Wait-ForInitialPlayback'
}, $true)
. ([scriptblock]::Create($initialWait.Extent.Text))
$PlaybackReadyTimeoutSeconds = 0
Assert-Equal $false (Wait-ForInitialPlayback) 'a status message cannot make failed page verification truthy'
Write-Output 'SWIPE_BENCHMARK_SCRIPT_TEST_RESULT=PASS'
