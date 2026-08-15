package com.kzkt.buildsrc

/**
 * Release versioning helpers shared by the app build script and unit tests.
 * See AGENTS.md rule 3: versionCode must always increase per release.
 */
object Versioning {
    /** Per-segment weights: major*10^7 + minor*10^5 + patch*10^3 + build*1. */
    private val SEGMENT_WEIGHTS = listOf(10_000_000, 100_000, 1_000, 1)

    /**
     * Derive a monotonically increasing versionCode from a versionName like
     * "1.35.0" → 13500000 or "1.25.1.23" → 12501023.
     *
     * The 4th segment is included so patch+build releases (1.25.1.22 vs
     * 1.25.1.23) never collide — that would make Android reject the update as
     * "older version". Safe bounds: build < 1000 and patch < 100, beyond which
     * segments would overlap (not realistic for this app's versioning).
     */
    fun deriveVersionCode(versionName: String): Int =
        versionName
            .split('.')
            .take(4)
            .mapIndexed { i, s ->
                (s.toIntOrNull() ?: 0) * SEGMENT_WEIGHTS[i]
            }.sum()
}
