Set-StrictMode -Version Latest

function Get-NearestRankPercentile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][long[]]$Values,
        [Parameter(Mandatory = $true)][ValidateRange(1, 100)][int]$Percentile
    )

    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $rank = [Math]::Ceiling($sorted.Count * ($Percentile / 100.0))
    return [long]$sorted[[Math]::Max(0, $rank - 1)]
}

function Get-CvfFastSwipeBatches {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][ValidateRange(1, 100)][int]$SwipeCount,
        [Parameter(Mandatory = $true)][ValidateRange(0, 100)][int]$CheckpointEvery
    )

    $batchSize = if ($CheckpointEvery -eq 0) {
        $SwipeCount
    } else {
        [Math]::Min($CheckpointEvery, $SwipeCount)
    }
    $remaining = $SwipeCount
    $batches = [System.Collections.Generic.List[int]]::new()
    while ($remaining -gt 0) {
        $nextBatch = [Math]::Min($batchSize, $remaining)
        $batches.Add($nextBatch)
        $remaining -= $nextBatch
    }
    return $batches.ToArray()
}

function Get-CvfLogFields {
    param([Parameter(Mandatory = $true)][string]$Line)

    $fields = @{}
    foreach ($match in [regex]::Matches($Line, '(?<key>[A-Za-z][A-Za-z0-9]*?)=(?<value>[^\s]+)')) {
        $fields[$match.Groups['key'].Value] = $match.Groups['value'].Value
    }
    return $fields
}

function ConvertTo-NullableLong {
    param($Value)
    if ($null -eq $Value -or $Value -eq 'null') { return $null }
    $parsed = 0L
    if ([long]::TryParse([string]$Value, [ref]$parsed)) { return $parsed }
    return $null
}

function ConvertFrom-CvfMemInfo {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines)

    foreach ($line in $Lines) {
        if ($line -match '^\s*TOTAL PSS:\s*(?<pss>\d+)\b') {
            return [pscustomobject]@{ PssKb = [long]$Matches['pss']; Available = $true }
        }
    }
    foreach ($line in $Lines) {
        if ($line -match '^\s*TOTAL\s+(?<pss>\d+)\s+') {
            return [pscustomobject]@{ PssKb = [long]$Matches['pss']; Available = $true }
        }
    }
    return [pscustomobject]@{ PssKb = $null; Available = $false }
}

function ConvertFrom-CvfGfxInfo {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines)

    $total = $null
    $janky = $null
    foreach ($line in $Lines) {
        if ($line -match '^\s*Total frames rendered:\s*(?<value>\d+)\b') {
            $total = [long]$Matches['value']
        }
        if ($line -match '^\s*Janky frames:\s*(?<value>\d+)\b') {
            $janky = [long]$Matches['value']
        }
    }
    return [pscustomobject]@{
        TotalFrames = $total
        JankyFrames = $janky
        JankyRatePercent = if ($null -ne $total -and $total -gt 0 -and $null -ne $janky) {
            [Math]::Round(100.0 * $janky / $total, 2)
        } else {
            $null
        }
        Available = $null -ne $total -and $null -ne $janky
    }
}

function ConvertFrom-CvfBenchmarkLog {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    $outcomeCounts = [ordered]@{
        FIRST_FRAME = 0
        FAILED = 0
        UNSUPPORTED = 0
        SUPERSEDED = 0
        UNCHANGED = 0
        RELEASED = 0
    }
    $metricNames = @(
        'gestureToReleaseMs',
        'gestureToTargetKnownMs',
        'releaseToSettleMs',
        'targetKnownToSettleMs',
        'targetKnownToPlanReadyMs',
        'planReadyToSettleMs',
        'settleToPlanMs',
        'planAgeMs',
        'refreshMs',
        'planToBindMs',
        'bindToPrepareMs',
        'prepareToReadyMs',
        'bindToFirstByteMs',
        'firstByteToReadyMs',
        'bindToReadyMs',
        'readyToFirstFrameMs',
        'bindToTerminalMs',
        'releaseToTerminalMs',
        'targetKnownToTerminalMs',
        'gestureToTerminalMs'
    )
    $successfulFields = [System.Collections.Generic.List[hashtable]]::new()
    $orderCounts = [ordered]@{ RANDOM = 0; LATEST = 0; UNKNOWN = 0 }
    $directionCounts = [ordered]@{
        FORWARD = 0
        REVERSE = 0
        INITIAL = 0
        UNCHANGED = 0
        UNKNOWN = 0
    }
    $refreshOutcomeCounts = [ordered]@{ SUCCESS = 0; FALLBACK = 0; SKIPPED = 0; UNKNOWN = 0 }
    $transparentRecoveryOutcomeCounts = [ordered]@{
        REBOUND = 0
        SOFT_TIMEOUT = 0
        UNAVAILABLE = 0
        MESSAGE_UNAVAILABLE = 0
        STALE_REFERENCE = 0
        REFRESHED_FILE_UNAVAILABLE = 0
        UNKNOWN = 0
    }
    $transparentRecoveryAttemptCount = 0
    $transparentRecoveryEligibleCount = 0
    $randomRoundBoundaryCount = 0
    $randomRoundBoundaryEligibleCount = 0
    $randomRoundBoundaryPlanPromotedCount = 0
    $randomRoundBoundaryPlanPromotedEligibleCount = 0
    $promotedCount = 0
    $promotedEligibleCount = 0
    $preloadYieldCount = 0
    $preloadResumeCount = 0
    $promotionAttemptCount = 0
    $promotionMatchedCount = 0
    $promotionTerminalCount = 0
    $reusedActiveRequestCount = 0
    $cancelledBeforeCurrentAcquireCount = 0
    $schedulerActiveRequestReuseCount = 0
    $firstMissCategoryCounts = [ordered]@{
        HEAD = 0
        TAIL = 0
        MIDDLE = 0
        UNKNOWN = 0
        NONE = 0
    }
    $startupRangeSummaryCount = 0
    $coveredBeforeCurrentBytes = [System.Collections.Generic.List[long]]::new()
    $speculativeCoveredBytes = [System.Collections.Generic.List[long]]::new()
    $speculativeRequestedExtraBytes = [System.Collections.Generic.List[long]]::new()
    $speculativeCompletedExtraBytes = [System.Collections.Generic.List[long]]::new()
    $currentReusedNextOwnerCount = 0
    $currentReusedNextOwnerEligibleCount = 0
    $extractorRangeSwitchCount = 0
    $telegramRangeSwitchCount = 0
    $telegramRangeMergeCount = 0
    $telegramRangeCancelCount = 0
    $noProgressTimeoutCount = 0
    $startupCandidateCounts = @{}
    $ownerHandoffMillis = [System.Collections.Generic.List[long]]::new()
    $rebufferCount = 0
    $explicitRebufferCount = 0
    $previousRebufferCount = 0
    $playableSamples = [Collections.Generic.List[object]]::new()
    $skippedNextWastedBytes = [System.Collections.Generic.List[long]]::new()
    $feedInitialEmissionMillis = [System.Collections.Generic.List[long]]::new()
    $feedHydrationMillis = [System.Collections.Generic.List[long]]::new()
    $visibleFirstFrameMillis = [System.Collections.Generic.List[long]]::new()
    $feedSnapshotKeyCounts = [System.Collections.Generic.List[long]]::new()
    $feedHydratedItemCounts = [System.Collections.Generic.List[long]]::new()
    $cacheTouchWrites = 0L
    $tdFileEventsReceived = 0L
    $tdFileEventsApplied = 0L
    $tdFileEventsCoalesced = 0L
    $processCrashCount = 0
    $cmdlineCrashCount = 0

    foreach ($line in $Lines) {
        if ($line -match 'CVF-Transition.*\bsummary\b') {
            $fields = Get-CvfLogFields -Line $line
            if (-not $fields.ContainsKey('outcome')) { continue }
            $outcome = [string]$fields['outcome']
            if ($outcomeCounts.Contains($outcome)) {
                $outcomeCounts[$outcome] += 1
            }
            if ($fields.ContainsKey('transparentRecoveryAttempts')) {
                $attempts = ConvertTo-NullableLong $fields['transparentRecoveryAttempts']
                if ($null -ne $attempts) {
                    $transparentRecoveryEligibleCount += 1
                    $transparentRecoveryAttemptCount += $attempts
                }
            }
            if ($fields.ContainsKey('transparentRecoveryOutcome')) {
                $recoveryOutcome = [string]$fields['transparentRecoveryOutcome']
                if ($transparentRecoveryOutcomeCounts.Contains($recoveryOutcome)) {
                    $transparentRecoveryOutcomeCounts[$recoveryOutcome] += 1
                } elseif ($recoveryOutcome -ne 'null') {
                    $transparentRecoveryOutcomeCounts.UNKNOWN += 1
                }
            }
            if ($outcome -eq 'FIRST_FRAME') {
                $firstByte = if ($fields.ContainsKey('bindToFirstByteMs')) {
                    ConvertTo-NullableLong $fields['bindToFirstByteMs']
                } else {
                    $null
                }
                $ready = if ($fields.ContainsKey('bindToReadyMs')) {
                    ConvertTo-NullableLong $fields['bindToReadyMs']
                } else {
                    $null
                }
                if ($null -ne $firstByte -and $null -ne $ready) {
                    $fields['firstByteToReadyMs'] = [string][Math]::Max(0L, $ready - $firstByte)
                }
                $successfulFields.Add($fields)
                $order = if ($fields.ContainsKey('order')) { [string]$fields['order'] } else { 'UNKNOWN' }
                if ($orderCounts.Contains($order)) { $orderCounts[$order] += 1 } else { $orderCounts.UNKNOWN += 1 }
                $direction = if ($fields.ContainsKey('direction')) { [string]$fields['direction'] } else { 'UNKNOWN' }
                if ($directionCounts.Contains($direction)) {
                    $directionCounts[$direction] += 1
                } else {
                    $directionCounts.UNKNOWN += 1
                }
                $refreshOutcome = if ($fields.ContainsKey('refreshOutcome')) {
                    [string]$fields['refreshOutcome']
                } else {
                    'UNKNOWN'
                }
                if ($refreshOutcomeCounts.Contains($refreshOutcome)) {
                    $refreshOutcomeCounts[$refreshOutcome] += 1
                } else {
                    $refreshOutcomeCounts.UNKNOWN += 1
                }
                if (
                    $fields.ContainsKey('randomRoundBoundary') -and
                    $fields['randomRoundBoundary'] -in @('true', 'false')
                ) {
                    $randomRoundBoundaryEligibleCount += 1
                    if ($fields['randomRoundBoundary'] -eq 'true') {
                        $randomRoundBoundaryCount += 1
                    }
                }
                if ($fields.ContainsKey('promoted') -and $fields['promoted'] -in @('true', 'false')) {
                    $promotedEligibleCount += 1
                    if ($fields['promoted'] -eq 'true') { $promotedCount += 1 }
                }
                if (
                    $fields.ContainsKey('randomRoundBoundary') -and
                    $fields['randomRoundBoundary'] -eq 'true' -and
                    $fields.ContainsKey('promoted') -and
                    $fields['promoted'] -in @('true', 'false')
                ) {
                    $randomRoundBoundaryPlanPromotedEligibleCount += 1
                    if ($fields['promoted'] -eq 'true') {
                        $randomRoundBoundaryPlanPromotedCount += 1
                    }
                }
            }
            continue
        }
        if ($line -match 'CVF-Preload.*\baction=YIELD\b') {
            $preloadYieldCount += 1
            continue
        }
        if ($line -match 'CVF-Preload.*\bskippedNextWastedBytes=') {
            $fields = Get-CvfLogFields -Line $line
            $wasted = if ($fields.ContainsKey('skippedNextWastedBytes')) {
                ConvertTo-NullableLong $fields['skippedNextWastedBytes']
            } else {
                $null
            }
            if ($null -ne $wasted) { $skippedNextWastedBytes.Add($wasted) }
        }
        if ($line -match 'CVF-FeedPerf.*\bsummary\b') {
            $fields = Get-CvfLogFields -Line $line
            foreach ($metric in @(
                @{ Name = 'firstEmissionMs'; Values = $feedInitialEmissionMillis },
                @{ Name = 'hydrateMs'; Values = $feedHydrationMillis },
                @{ Name = 'visibleFirstFrameMs'; Values = $visibleFirstFrameMillis },
                @{ Name = 'snapshotKeys'; Values = $feedSnapshotKeyCounts },
                @{ Name = 'hydratedItems'; Values = $feedHydratedItemCounts }
            )) {
                if (-not $fields.ContainsKey($metric.Name)) { continue }
                $value = ConvertTo-NullableLong $fields[$metric.Name]
                if ($null -ne $value) { $metric.Values.Add($value) }
            }
            continue
        }
        if ($line -match 'CVF-CachePerf.*\bsummary\b') {
            $fields = Get-CvfLogFields -Line $line
            foreach ($counter in @(
                @{ Name = 'touchWrites'; Target = 'cacheTouchWrites' },
                @{ Name = 'eventReceived'; Target = 'tdFileEventsReceived' },
                @{ Name = 'eventApplied'; Target = 'tdFileEventsApplied' },
                @{ Name = 'eventCoalesced'; Target = 'tdFileEventsCoalesced' }
            )) {
                if (-not $fields.ContainsKey($counter.Name)) { continue }
                $value = ConvertTo-NullableLong $fields[$counter.Name]
                if ($null -eq $value) { continue }
                Set-Variable -Name $counter.Target -Value $value
            }
            continue
        }
        if ($line -match 'CVF-Preload.*\baction=RESUME\b') {
            $preloadResumeCount += 1
        }
        if ($line -match 'CVF-Preload.*\baction=(START|RESUME|TAIL_START)\b') {
            $fields = Get-CvfLogFields -Line $line
            if ($fields.ContainsKey('requestedExtraBytes')) {
                $requestedExtra = ConvertTo-NullableLong $fields['requestedExtraBytes']
                if ($null -ne $requestedExtra) {
                    $speculativeRequestedExtraBytes.Add($requestedExtra)
                }
            } elseif ($fields.ContainsKey('candidate') -and $fields.ContainsKey('bytes')) {
                $requestedBytes = ConvertTo-NullableLong $fields['bytes']
                if ($null -ne $requestedBytes) {
                    $speculativeRequestedExtraBytes.Add(
                        [Math]::Max(0L, $requestedBytes - 262144L)
                    )
                }
            }
            continue
        }
        if ($line -match 'CVF-Preload.*\baction=CANDIDATE_READY\b') {
            $fields = Get-CvfLogFields -Line $line
            foreach ($fieldName in @('totalBytes', 'extraBytes')) {
                if (-not $fields.ContainsKey($fieldName)) { continue }
                $value = ConvertTo-NullableLong $fields[$fieldName]
                if ($null -eq $value) { continue }
                if ($fieldName -eq 'totalBytes') {
                    $speculativeCoveredBytes.Add($value)
                } else {
                    $speculativeCompletedExtraBytes.Add($value)
                }
            }
            continue
        }
        if ($line -match 'CVF-Preload.*\bpromotionAttempt=true\b') {
            $promotionAttemptCount += 1
            $fields = Get-CvfLogFields -Line $line
            if ($fields.ContainsKey('promotionMatched') -and $fields['promotionMatched'] -eq 'true') {
                $promotionMatchedCount += 1
            }
            continue
        }
        if ($line -match 'CVF-Preload.*\bpromotionTerminal=true\b') {
            $promotionTerminalCount += 1
            $fields = Get-CvfLogFields -Line $line
            if ($fields.ContainsKey('reusedActiveRequest') -and $fields['reusedActiveRequest'] -eq 'true') {
                $reusedActiveRequestCount += 1
            }
            if (
                $fields.ContainsKey('cancelledBeforeCurrentAcquire') -and
                $fields['cancelledBeforeCurrentAcquire'] -eq 'true'
            ) {
                $cancelledBeforeCurrentAcquireCount += 1
            }
            if ($fields.ContainsKey('ownerHandoffMs')) {
                $handoff = ConvertTo-NullableLong $fields['ownerHandoffMs']
                if ($null -ne $handoff) { $ownerHandoffMillis.Add($handoff) }
            }
            continue
        }
        if (
            $line -match 'CVF-TdFile.*\bpriority=NEXT_PRELOAD->CURRENT_STARTUP\b.*\bresult=REUSED_ACTIVE\b'
        ) {
            $schedulerActiveRequestReuseCount += 1
            continue
        }
        if ($line -match 'CVF-StartupRange.*\bsummary\b') {
            $fields = Get-CvfLogFields -Line $line
            $startupRangeSummaryCount += 1
            $category = if ($fields.ContainsKey('firstMissCategory')) {
                [string]$fields['firstMissCategory']
            } else {
                'UNKNOWN'
            }
            if ($firstMissCategoryCounts.Contains($category)) {
                $firstMissCategoryCounts[$category] += 1
            } else {
                $firstMissCategoryCounts.UNKNOWN += 1
            }
            if ($fields.ContainsKey('coveredBeforeCurrentBytes')) {
                $covered = ConvertTo-NullableLong $fields['coveredBeforeCurrentBytes']
                if ($null -ne $covered) { $coveredBeforeCurrentBytes.Add($covered) }
            }
            if ($fields.ContainsKey('extractorRangeSwitchCount')) {
                $switches = ConvertTo-NullableLong $fields['extractorRangeSwitchCount']
                if ($null -ne $switches) { $extractorRangeSwitchCount += $switches }
            }
            if (
                $fields.ContainsKey('currentReusedNextOwner') -and
                $fields['currentReusedNextOwner'] -in @('true', 'false')
            ) {
                $currentReusedNextOwnerEligibleCount += 1
                if ($fields['currentReusedNextOwner'] -eq 'true') {
                    $currentReusedNextOwnerCount += 1
                }
            }
            if ($fields.ContainsKey('candidate')) {
                $candidate = [string]$fields['candidate']
                if (-not $startupCandidateCounts.ContainsKey($candidate)) {
                    $startupCandidateCounts[$candidate] = 0
                }
                $startupCandidateCounts[$candidate] += 1
            }
            continue
        }
        if ($line -match 'CVF-TdFile.*\brequest begin\b.*\bresult=SWITCH\b') {
            $telegramRangeSwitchCount += 1
            continue
        }
        if ($line -match 'CVF-TdFile.*\brequest begin\b.*\bresult=MERGE\b') {
            $telegramRangeMergeCount += 1
            continue
        }
        if ($line -match 'CVF-TdFile.*\bcancel fileId=\S+\s+result=\S+') {
            $telegramRangeCancelCount += 1
            continue
        }
        if ($line -match 'CVF-TdFile.*\brange timeout\b.*\breason=NO_PROGRESS\b') {
            $noProgressTimeoutCount += 1
            continue
        }
        if ($line -match 'CVF-Player.*\brebuffer started count=') {
            $explicitRebufferCount += 1
            continue
        }
        if ($line -match 'CVF-Player.*\bplayable\b') {
            $fields = Get-CvfLogFields -Line $line
            if ($fields.ContainsKey('chatId') -and $fields.ContainsKey('messageId') -and $fields.ContainsKey('bindToReadyMs')) {
                $playableSamples.Add([pscustomobject]@{ Key = "$($fields['chatId']):$($fields['messageId'])"; Millis = [long]$fields['bindToReadyMs'] })
            }
            continue
        }
        if ($line -match 'CVF-Player.*\brebufferCount=(?<count>\d+)') {
            $currentCount = [int]$Matches['count']
            # Old builds repeat cumulative counts in state/sample/summary lines.
            if ($currentCount -gt $previousRebufferCount) { $rebufferCount += $currentCount - $previousRebufferCount }
            $previousRebufferCount = $currentCount
            continue
        }
        if ($line -match ('Process:\s*' + [regex]::Escape($PackageName) + '\s*,')) {
            $processCrashCount += 1
            continue
        }
        if ($line -match ('Cmdline:\s*' + [regex]::Escape($PackageName) + '\s*$')) {
            $cmdlineCrashCount += 1
        }
    }

    $metrics = [ordered]@{}
    foreach ($metricName in $metricNames) {
        $values = @(
            foreach ($fields in $successfulFields) {
                if ($fields.ContainsKey($metricName)) {
                    $value = ConvertTo-NullableLong $fields[$metricName]
                    if ($null -ne $value) { $value }
                }
            }
        )
        $metrics[$metricName] = [pscustomobject]@{
            Count = $values.Count
            P50 = Get-NearestRankPercentile -Values $values -Percentile 50
            P90 = Get-NearestRankPercentile -Values $values -Percentile 90
            P95 = Get-NearestRankPercentile -Values $values -Percentile 95
            Max = if ($values.Count -gt 0) { [long](($values | Measure-Object -Maximum).Maximum) } else { $null }
        }
    }

    $requiredFieldNames = @(
        'order',
        'direction',
        'randomRoundBoundary',
        'promoted',
        'planAgeMs',
        'refreshOutcome',
        'targetKnownToPlanReadyMs',
        'planReadyToSettleMs',
        'settleToPlanMs',
        'refreshMs',
        'planToBindMs',
        'bindToFirstByteMs',
        'bindToReadyMs',
        'bindToTerminalMs'
    )
    $requiredFieldViolationCount = 0
    foreach ($fields in $successfulFields) {
        $invalid = $false
        foreach ($fieldName in $requiredFieldNames) {
            if (-not $fields.ContainsKey($fieldName)) { $invalid = $true }
        }
        if ($fields.ContainsKey('direction') -and $fields['direction'] -notin @('FORWARD', 'REVERSE', 'INITIAL', 'UNCHANGED')) {
            $invalid = $true
        }
        if ($fields.ContainsKey('randomRoundBoundary') -and $fields['randomRoundBoundary'] -notin @('true', 'false')) {
            $invalid = $true
        }
        if ($fields.ContainsKey('promoted') -and $fields['promoted'] -notin @('true', 'false')) {
            $invalid = $true
        }
        if ($fields.ContainsKey('refreshOutcome') -and $fields['refreshOutcome'] -notin @('SUCCESS', 'FALLBACK', 'SKIPPED')) {
            $invalid = $true
        }
        foreach ($numericField in @('refreshMs', 'bindToFirstByteMs', 'bindToTerminalMs')) {
            if (
                -not $fields.ContainsKey($numericField) -or
                $null -eq (ConvertTo-NullableLong $fields[$numericField])
            ) {
                $invalid = $true
            }
        }
        if ($fields.ContainsKey('bindToReadyMs') -and $fields['bindToReadyMs'] -ne 'null' -and
            $null -eq (ConvertTo-NullableLong $fields['bindToReadyMs'])) {
            $invalid = $true
        }
        if (
            $fields.ContainsKey('promoted') -and
            $fields['promoted'] -eq 'true' -and
            (
                -not $fields.ContainsKey('planAgeMs') -or
                $null -eq (ConvertTo-NullableLong $fields['planAgeMs'])
            )
        ) {
            $invalid = $true
        }
        if ($invalid) { $requiredFieldViolationCount += 1 }
    }

    return [pscustomobject]@{
        OutcomeCounts = [pscustomobject]$outcomeCounts
        OrderCounts = [pscustomobject]$orderCounts
        DirectionCounts = [pscustomobject]$directionCounts
        RefreshOutcomeCounts = [pscustomobject]$refreshOutcomeCounts
        TransparentRecoveryOutcomeCounts = [pscustomobject]$transparentRecoveryOutcomeCounts
        TransparentRecoveryAttemptCount = $transparentRecoveryAttemptCount
        TransparentRecoveryEligibleCount = $transparentRecoveryEligibleCount
        Metrics = [pscustomobject]$metrics
        SuccessfulSampleCount = $successfulFields.Count
        PoolReadyHitSampleCount = @($successfulFields | Where-Object { $_['poolPromoted'] -eq 'true' -and $_['poolPreparedReady'] -eq 'true' }).Count
        PoolReadyHitBindMetric = New-CvfByteMetric -Values @(
            $successfulFields | Where-Object { $_['poolPromoted'] -eq 'true' -and $_['poolPreparedReady'] -eq 'true' } |
                ForEach-Object { ConvertTo-NullableLong $_['bindToTerminalMs'] } | Where-Object { $null -ne $_ }
        )
        RandomOrderConfirmed = $successfulFields.Count -gt 0 -and
            $orderCounts.RANDOM -eq $successfulFields.Count
        RequiredFieldsComplete = $successfulFields.Count -gt 0 -and
            $requiredFieldViolationCount -eq 0
        RequiredFieldViolationCount = $requiredFieldViolationCount
        RandomRoundBoundaryCount = $randomRoundBoundaryCount
        RandomRoundBoundaryEligibleCount = $randomRoundBoundaryEligibleCount
        RandomRoundBoundaryPlanPromotedCount = $randomRoundBoundaryPlanPromotedCount
        RandomRoundBoundaryPlanPromotedEligibleCount = $randomRoundBoundaryPlanPromotedEligibleCount
        RandomRoundBoundaryPlanPromotedRatePercent = if ($randomRoundBoundaryPlanPromotedEligibleCount -gt 0) {
            [Math]::Round(
                100.0 * $randomRoundBoundaryPlanPromotedCount /
                    $randomRoundBoundaryPlanPromotedEligibleCount,
                1
            )
        } else {
            $null
        }
        PromotedCount = $promotedCount
        PromotedEligibleCount = $promotedEligibleCount
        PromotedRatePercent = if ($promotedEligibleCount -gt 0) {
            [Math]::Round(100.0 * $promotedCount / $promotedEligibleCount, 1)
        } else {
            $null
        }
        PreloadYieldCount = $preloadYieldCount
        PreloadResumeCount = $preloadResumeCount
        PromotionAttemptCount = $promotionAttemptCount
        PromotionMatchedCount = $promotionMatchedCount
        PromotionTerminalCount = $promotionTerminalCount
        ReusedActiveRequestCount = $reusedActiveRequestCount
        CancelledBeforeCurrentAcquireCount = $cancelledBeforeCurrentAcquireCount
        SchedulerActiveRequestReuseCount = $schedulerActiveRequestReuseCount
        FirstMissCategoryCounts = [pscustomobject]$firstMissCategoryCounts
        StartupRangeSummaryCount = $startupRangeSummaryCount
        StartupRangeObservationComplete = $successfulFields.Count -gt 0 -and
            $startupRangeSummaryCount -ge $successfulFields.Count
        StartupCandidates = [pscustomobject]$startupCandidateCounts
        CoveredBeforeCurrentMetric = New-CvfByteMetric -Values $coveredBeforeCurrentBytes.ToArray()
        SpeculativeCoveredMetric = New-CvfByteMetric -Values $speculativeCoveredBytes.ToArray()
        SpeculativeExtraMetric = New-CvfByteMetric -Values $speculativeRequestedExtraBytes.ToArray()
        SpeculativeCompletedExtraMetric = New-CvfByteMetric -Values $speculativeCompletedExtraBytes.ToArray()
        SkippedNextWastedMetric = New-CvfByteMetric -Values $skippedNextWastedBytes.ToArray()
        PreloadHitRatePercent = if ($currentReusedNextOwnerEligibleCount -gt 0) {
            [Math]::Round(100.0 * $currentReusedNextOwnerCount / $currentReusedNextOwnerEligibleCount, 1)
        } else {
            $null
        }
        FeedInitialEmissionMetric = New-CvfByteMetric -Values $feedInitialEmissionMillis.ToArray()
        FeedHydrationMetric = New-CvfByteMetric -Values $feedHydrationMillis.ToArray()
        VisibleFirstFrameMetric = New-CvfByteMetric -Values $visibleFirstFrameMillis.ToArray()
        FeedSnapshotKeyMetric = New-CvfByteMetric -Values $feedSnapshotKeyCounts.ToArray()
        FeedHydratedItemMetric = New-CvfByteMetric -Values $feedHydratedItemCounts.ToArray()
        CacheTouchWrites = $cacheTouchWrites
        TdFileEventsReceived = $tdFileEventsReceived
        TdFileEventsApplied = $tdFileEventsApplied
        TdFileEventsCoalesced = $tdFileEventsCoalesced
        CurrentReusedNextOwnerCount = $currentReusedNextOwnerCount
        CurrentReusedNextOwnerEligibleCount = $currentReusedNextOwnerEligibleCount
        ExtractorRangeSwitchCount = $extractorRangeSwitchCount
        TelegramRangeSwitchCount = $telegramRangeSwitchCount
        TelegramRangeMergeCount = $telegramRangeMergeCount
        TelegramRangeCancelCount = $telegramRangeCancelCount
        NoProgressTimeoutCount = $noProgressTimeoutCount
        OwnerHandoffMetric = [pscustomobject]@{
            Count = $ownerHandoffMillis.Count
            P50 = Get-NearestRankPercentile -Values $ownerHandoffMillis.ToArray() -Percentile 50
            P90 = Get-NearestRankPercentile -Values $ownerHandoffMillis.ToArray() -Percentile 90
            Max = if ($ownerHandoffMillis.Count -gt 0) {
                [long](($ownerHandoffMillis | Measure-Object -Maximum).Maximum)
            } else {
                $null
            }
        }
        RebufferCount = if ($explicitRebufferCount -gt 0) { $explicitRebufferCount } else { $rebufferCount }
        PlayableReadyMetric = New-CvfByteMetric -Values @($playableSamples | Where-Object {
            $candidate = $_
            @($successfulFields | Where-Object { "$($_['chatId']):$($_['messageId'])" -eq $candidate.Key }).Count -gt 0
        } | ForEach-Object { [long]$_.Millis })
        CrashCount = [Math]::Max($processCrashCount, $cmdlineCrashCount)
    }
}

function New-CvfByteMetric {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][long[]]$Values)

    return [pscustomobject]@{
        Count = $Values.Count
        P50 = Get-NearestRankPercentile -Values $Values -Percentile 50
        P90 = Get-NearestRankPercentile -Values $Values -Percentile 90
        P95 = Get-NearestRankPercentile -Values $Values -Percentile 95
        Max = if ($Values.Count -gt 0) {
            [long](($Values | Measure-Object -Maximum).Maximum)
        } else {
            $null
        }
        Total = if ($Values.Count -gt 0) {
            [long](($Values | Measure-Object -Sum).Sum)
        } else {
            0L
        }
    }
}

function Test-CvfRequestedDirection {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Summary,
        [Parameter(Mandatory = $true)][ValidateSet('Forward', 'Reverse')][string]$Direction
    )

    $expectedDirection = $Direction.ToUpperInvariant()
    $directionProperty = $Summary.DirectionCounts.PSObject.Properties[$expectedDirection]
    return $Summary.SuccessfulSampleCount -gt 0 -and
        $null -ne $directionProperty -and
        $directionProperty.Value -eq $Summary.SuccessfulSampleCount
}

function Test-CvfPlaybackUiTree {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$UiTree)
    $start = $UiTree.IndexOf('<?xml', [StringComparison]::Ordinal)
    $end = $UiTree.IndexOf('</hierarchy>', [StringComparison]::Ordinal)
    if ($start -lt 0 -or $end -lt $start) { return $false }
    try {
        $document = [xml]$UiTree.Substring($start, $end + 12 - $start)
        # The loading state has no play button; auto-hide has only the reveal surface.
        # Both still belong to the player route. Order is checked from transition evidence.
        return $null -ne $document.SelectSingleNode(
            '//node[@package="com.qixuan.channelvideoflow" and (@content-desc="返回频道" or @content-desc="显示播放控制")]'
        )
    } catch { return $false }
}

function Test-CvfRandomSelectedUiTree {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$UiTree)

    $xmlStart = $UiTree.IndexOf('<?xml', [System.StringComparison]::Ordinal)
    $closingTag = '</hierarchy>'
    $xmlEnd = if ($xmlStart -ge 0) {
        $UiTree.IndexOf($closingTag, $xmlStart, [System.StringComparison]::Ordinal)
    } else {
        -1
    }
    if ($xmlStart -lt 0 -or $xmlEnd -lt 0) { return $false }

    try {
        $document = [System.Xml.XmlDocument]::new()
        $document.LoadXml($UiTree.Substring($xmlStart, $xmlEnd - $xmlStart + $closingTag.Length))
        foreach ($selectedNode in @($document.SelectNodes('//node[@selected="true"]'))) {
            if ($selectedNode.GetAttribute('text') -eq '随机') { return $true }
            if ($null -ne $selectedNode.SelectSingleNode('./node[@text="随机"]')) { return $true }
        }
    } catch {
        return $false
    }
    return $false
}

function Protect-CvfBenchmarkLog {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines)

    $safeLines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $Lines) {
        $allowed =
            $line -match 'CVF-Player.*\b(playable|rebuffer started)\b' -or
            $line -match 'CVF-Transition.*\bsummary\b' -or
            $line -match 'CVF-Preload.*\baction=(YIELD|RESUME|START)\b' -or
            $line -match 'CVF-Preload.*\baction=CANDIDATE_READY\b' -or
            $line -match 'CVF-Preload.*\bpromotion(Attempt|Terminal)=true\b' -or
            $line -match 'CVF-Adaptive.*\bstate=' -or
            $line -match 'CVF-Player.*\b(state state=BUFFERING|summary |error category=)' -or
            $line -match 'CVF-StartupRange.*\bsummary\b' -or
            $line -match 'CVF-FeedPerf.*\bsummary\b' -or
            $line -match 'CVF-CachePerf.*\bsummary\b' -or
            $line -match 'CVF-Preload.*\bskippedNextWastedBytes=' -or
            $line -match 'CVF-TdFile.*\bresult=(PREEMPTED_BY_CURRENT|REUSED_ACTIVE|SWITCH|MERGE)\b' -or
            $line -match 'CVF-TdFile.*\bcancel fileId=' -or
            $line -match 'CVF-TdFile.*\brange timeout\b.*\breason=NO_PROGRESS\b'
        if (-not $allowed) { continue }

        $sanitized = $line
        $sanitized = $sanitized -replace '(?i)\b(chatId|messageId|fileId|ownerToken|remoteUniqueId|path|ssid|bssid|ip)=\S+', '$1=[redacted]'
        $sanitized = $sanitized -replace '(?i)\bPID:\s*\d+', 'PID: [redacted]'
        $safeLines.Add($sanitized)
    }
    return $safeLines.ToArray()
}

Export-ModuleMember -Function @(
    'Get-NearestRankPercentile',
    'Get-CvfFastSwipeBatches',
    'ConvertFrom-CvfMemInfo',
    'ConvertFrom-CvfGfxInfo',
    'ConvertFrom-CvfBenchmarkLog',
    'Test-CvfRequestedDirection',
    'Test-CvfRandomSelectedUiTree',
    'Test-CvfPlaybackUiTree',
    'Protect-CvfBenchmarkLog'
)
