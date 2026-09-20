package com.buildsession.betterYAMF.xposed.hook

/** Release-only arbitration. Never delays or changes the live task animation. */
internal object LandscapeWindowCommitPolicy {
    fun allows(
        width: Int, height: Int, density: Float,
        rightTravel: Float, upwardTravel: Float, durationMs: Long,
        edgeDistance: Float, zoneRadius: Float
    ): Boolean {
        if (width <= height) return true // Preserve portrait behavior.
        // A wide task can overlap the corner during an almost vertical Home
        // swipe. Require pointer intent as well as actual corner overlap;
        // neither the clamped task bounds nor magnetic attraction proves intent.
        return durationMs >= 300L &&
            rightTravel >= maxOf(72f * density, width * .10f) &&
            rightTravel >= upwardTravel.coerceAtLeast(0f) * .35f &&
            edgeDistance <= zoneRadius
    }
}
