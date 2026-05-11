package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateVersionComparatorTest {
    @Test
    fun parsesVersionAndBuildFromReleaseNotesHeading() {
        val version = AppUpdateVersionComparator.parseReleaseVersion(
            tag = "pre",
            title = "Pre-Release - Betas are stored in this release",
            notes = "# Nuvio Desktop 0.1.14 build 55 - Pre-release",
        )

        assertEquals("0.1.14", version.versionName)
        assertEquals(55, version.versionCode)
    }

    @Test
    fun parsesExplicitVersionAndBuildLinesFromReleaseNotes() {
        val version = AppUpdateVersionComparator.parseReleaseVersion(
            tag = "pre",
            title = "Pre-Release - Betas are stored in this release",
            notes = """
                version=0.1.15
                build=56
            """.trimIndent(),
        )

        assertEquals("0.1.15", version.versionName)
        assertEquals(56, version.versionCode)
    }

    @Test
    fun greaterVersionAndGreaterBuildCountsAsAvailable() {
        assertTrue(
            AppUpdateVersionComparator.isUpdateAvailable(
                remoteVersionName = "0.1.15",
                remoteVersionCode = 56,
                remoteTag = "pre",
                localVersionName = "0.1.14",
                localVersionCode = 55,
            ),
        )
    }

    @Test
    fun sameVersionAndEqualRemoteBuildDoesNotCountAsAvailable() {
        assertFalse(
            AppUpdateVersionComparator.isUpdateAvailable(
                remoteVersionName = "0.1.14",
                remoteVersionCode = 55,
                remoteTag = "pre",
                localVersionName = "0.1.14",
                localVersionCode = 55,
            ),
        )
    }

    @Test
    fun sameVersionAndGreaterRemoteBuildCountsAsAvailable() {
        assertTrue(
            AppUpdateVersionComparator.isUpdateAvailable(
                remoteVersionName = "0.1.14",
                remoteVersionCode = 56,
                remoteTag = "pre",
                localVersionName = "0.1.14",
                localVersionCode = 55,
            ),
        )
    }

    @Test
    fun sameVersionAndOlderRemoteBuildDoesNotCountAsAvailable() {
        assertFalse(
            AppUpdateVersionComparator.isUpdateAvailable(
                remoteVersionName = "0.1.14",
                remoteVersionCode = 54,
                remoteTag = "pre",
                localVersionName = "0.1.14",
                localVersionCode = 55,
            ),
        )
    }

    @Test
    fun sameVersionWithoutRemoteBuildDoesNotUseFixedPreTagAsUpdate() {
        assertFalse(
            AppUpdateVersionComparator.isUpdateAvailable(
                remoteVersionName = "0.1.15",
                remoteVersionCode = null,
                remoteTag = "pre",
                localVersionName = "0.1.15",
                localVersionCode = 59,
            ),
        )
    }

    @Test
    fun candidateComparisonPrefersStableWhenStableVersionIsNewerThanPre() {
        val comparison = AppUpdateVersionComparator.compareRemoteCandidates(
            firstVersionName = "0.1.16",
            firstVersionCode = 60,
            firstTag = "v0.1.16",
            secondVersionName = "0.1.15",
            secondVersionCode = 59,
            secondTag = "pre",
        )

        assertTrue(comparison > 0)
    }

    @Test
    fun candidateComparisonPrefersPreWhenPreBuildIsNewerForSameVersion() {
        val comparison = AppUpdateVersionComparator.compareRemoteCandidates(
            firstVersionName = "0.1.16",
            firstVersionCode = 61,
            firstTag = "pre",
            secondVersionName = "0.1.16",
            secondVersionCode = 60,
            secondTag = "v0.1.16",
        )

        assertTrue(comparison > 0)
    }
}
