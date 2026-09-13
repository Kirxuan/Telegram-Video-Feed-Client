package com.qixuan.channelvideoflow.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-gated profile entry point.
 *
 * This flow intentionally stops at the credential screen. Authorized navigation and playback
 * profiles require a disposable test account and explicit device approval; they must never reuse
 * a real TDLib session. The generated profile is not checked in until this rule has run on an
 * approved target and its output has passed a cold-start A/B.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun coldStartupToCredentialGate() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
        profileBlock = {
            pressHome()
            startActivityAndWait()
        },
    )

    private companion object {
        const val TARGET_PACKAGE = "com.qixuan.channelvideoflow"
    }
}
