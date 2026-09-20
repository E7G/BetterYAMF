package com.buildsession.betterYAMF.xposed.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeWindowCommitPolicyTest {
    private fun allows(dx: Float, up: Float, ms: Long, distance: Float = 100f) =
        LandscapeWindowCommitPolicy.allows(1920, 1200, 1.75f, dx, up, ms, distance, 168f)

    @Test fun fastDiagonalRemainsHome() = assertFalse(allows(900f, 1000f, 260))
    @Test fun slowVerticalDriftIsNotIntent() = assertFalse(allows(210f, 1000f, 650))
    @Test fun slightRightDriftCannotCommitWideTask() = assertFalse(allows(90f, 600f, 500))
    @Test fun leftwardSwipeCannotCommit() = assertFalse(allows(-500f, 800f, 650))
    @Test fun deliberateContinuousDiagonalNeedsNoPause() = assertTrue(allows(700f, 900f, 650))
    @Test fun magnetFringeOutsideVisibleCornerCannotCommit() = assertFalse(allows(700f, 900f, 650, 190f))
    @Test fun exactThresholdIsAllowed() = assertTrue(allows(192f, 500f, 300, 168f))
    @Test fun quickReleaseBelowThresholdIsNotAllowed() = assertFalse(allows(192f, 500f, 299, 168f))
    @Test fun portraitRemainsUnchanged() = assertTrue(
        LandscapeWindowCommitPolicy.allows(1200, 1920, 1.75f, 0f, 900f, 200, 190f, 168f)
    )
}
