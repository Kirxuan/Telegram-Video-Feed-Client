[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidateSet('emulator-5580')][string]$Serial)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$adb = if ($env:ADB) { $env:ADB } else { 'E:\AndroidStudio2.0\platform-tools\adb.exe' }
$avdName = @(& $adb -s $Serial emu avd name)
if ($LASTEXITCODE -ne 0 -or $avdName[0].Trim() -ne 'CVF_STAGE25_API36_X86_64') {
    throw 'Stage 25 requires the explicitly authorized CVF_STAGE25_API36_X86_64 AVD.'
}
if ((& $adb -s $Serial shell getprop ro.build.version.sdk).Trim() -ne '36' -or
    (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim() -ne 'x86_64') {
    throw 'Only the authorized API 36 x86_64 emulator is supported.'
}
$profiles = @(
    @{ Name='small-large-font'; Size='960x1600'; Density='480'; Font='2.0' },
    @{ Name='landscape'; Size='1920x1080'; Density='420'; Font='1.0' },
    @{ Name='tablet'; Size='1600x2560'; Density='240'; Font='1.0' }
)
$classes = @(
    'com.qixuan.channelvideoflow.feature.auth.LoginScreenTest',
    'com.qixuan.channelvideoflow.feature.channels.ChannelSelectionScreenTest',
    'com.qixuan.channelvideoflow.feature.tags.TagFilterScreenTest',
    'com.qixuan.channelvideoflow.feature.settings.CacheSettingsScreenTest',
    'com.qixuan.channelvideoflow.test.ComposeSmokeTest',
    'com.qixuan.channelvideoflow.feature.video.VideoPlaybackScreenTest#playbackFailureUsesImmersiveChineseStateAndFakePlayerReceivesRetry',
    'com.qixuan.channelvideoflow.feature.video.VideoPlaybackScreenTest#unsupportedStreamShowsExactMessageWithoutAttachingPlayer',
    'com.qixuan.channelvideoflow.feature.video.VideoPlaybackScreenTest#contentUsesCompactTabsTapPauseProgressAndRightSideActions',
    'com.qixuan.channelvideoflow.feature.video.VideoPlaybackScreenTest#landscapeVideoOffersFullscreenAndFullscreenKeepsOnlyPlaybackControls',
    'com.qixuan.channelvideoflow.feature.video.VideoPlaybackScreenTest#longDetailsScrollToTheLastFieldOnASmallScreen'
) -join ','
$reports = Join-Path $repo 'build/reports/stage25-experiments/layouts'
New-Item -ItemType Directory -Path $reports -Force | Out-Null
try {
    foreach ($profile in $profiles) {
        & $adb -s $Serial shell wm size $profile.Size
        & $adb -s $Serial shell wm density $profile.Density
        & $adb -s $Serial shell settings put system font_scale $profile.Font
        $output = & $adb -s $Serial shell am instrument -w -r --no-window-animation `
            -e timeout_msec 120000 -e class $classes com.qixuan.channelvideoflow.instrumentation.test/androidx.test.runner.AndroidJUnitRunner 2>&1
        $instrumentExitCode = $LASTEXITCODE
        $output | Set-Content -LiteralPath (Join-Path $reports "$($profile.Name).log")
        if ($instrumentExitCode -ne 0 -or ($output -join "`n") -notmatch '(?m)^OK \(40 tests\)$' -or
            ($output -join "`n") -match 'Process crashed|FAILURES!!!') {
            Write-Output "LAYOUT=$($profile.Name) RESULT=FAIL"
            throw "Layout proof failed; see $reports"
        }
        Write-Output "LAYOUT=$($profile.Name) RESULT=PASS"
    }
} finally {
    & $adb -s $Serial shell wm size reset
    & $adb -s $Serial shell wm density reset
    & $adb -s $Serial shell settings put system font_scale 1.0
}
