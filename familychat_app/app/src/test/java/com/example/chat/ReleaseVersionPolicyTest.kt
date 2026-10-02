package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionPolicyTest {
    @Test
    fun identicalBetaVersionsCompareEqual() {
        assertEquals(0, compareVersionNames("v2.1.5(beta)", "v2.1.5(beta)"))
    }

    @Test
    fun officialBuildIsNewerThanBetaWithSameNumbers() {
        assertTrue(compareVersionNames("v2.1.5", "v2.1.5(beta)") > 0)
        assertTrue(compareVersionNames("v2.1.5(beta)", "v2.1.5") < 0)
    }

    @Test
    fun missingNumericPartsAreTreatedAsZero() {
        assertEquals(0, compareVersionNames("v2.1", "2.1.0"))
        assertTrue(compareVersionNames("v2.1.10", "v2.1.9") > 0)
    }

    @Test
    fun betaChannelGetsComparableBetaSuffixOnlyOnce() {
        val plainBeta = AppReleaseInfo(
            channel = AppReleaseChannel.PRERELEASE,
            version = "v2.2.0",
            versionLabel = "v2.2.0(beta)",
            fileName = "",
            originalName = "",
            fileSize = 0L,
            uploadedAt = 0L,
            downloadUrl = "",
            sha256 = "",
        )
        val labeledBeta = plainBeta.copy(version = "v2.2.0(beta)")

        assertEquals("v2.2.0(beta)", comparableReleaseVersion(plainBeta))
        assertEquals("v2.2.0(beta)", comparableReleaseVersion(labeledBeta))
    }

    @Test
    fun sameVersionDoesNotOfferUpdate() {
        val release = AppReleaseInfo(
            channel = AppReleaseChannel.PRERELEASE,
            version = "v2.1.5",
            versionLabel = "v2.1.5(beta)",
            fileName = "familychat_v2.1.5(beta).apk",
            originalName = "familychat_v2.1.5(beta).apk",
            fileSize = 1L,
            uploadedAt = 1L,
            downloadUrl = "/downloads/familychat_v2.1.5(beta).apk",
            sha256 = "a".repeat(64),
        )

        assertFalse(isReleaseUpdateAvailable(release, "v2.1.5(beta)"))
        assertTrue(isReleaseUpdateAvailable(release.copy(version = "v2.1.6", versionLabel = "v2.1.6(beta)"), "v2.1.5(beta)"))
    }
}
