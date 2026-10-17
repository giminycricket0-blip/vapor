package com.winlator.cmod.runtime.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamExitCloudTest {
    @get:Rule val files = TemporaryFolder()

    private fun status(vararg lines: String): XServerDisplayUtils.SteamExitStatus {
        val log = files.newFile()
        log.writeText(lines.joinToString("\n") { "[wn-launcher] $it" })
        return XServerDisplayUtils.readSteamExitStatus(log)
    }

    private fun needsAttention(vararg lines: String): Boolean {
        val log = files.newFile()
        log.writeText(lines.joinToString("\n") { "[wn-launcher] $it" })
        return XServerDisplayUtils.wnLauncherExitCloudNeedsAttention(log)
    }

    @Test fun noNewSaveFilesCanStillBeSynchronized() {
        assertFalse(needsAttention("cloud: exit sync starting",
            "cloud: exit sync COMPLETE for app=286100 — saves are synchronized (0 file(s) transferred)"))
    }

    @Test fun failedOrAbandonedUploadsNeedAnExplanation() {
        assertTrue(needsAttention("cloud: exit sync starting"))
        assertTrue(needsAttention("cloud: RunAutoCloudOnAppExit(app=286100) attempt 1 -> 1"))
        assertTrue(needsAttention("cloud: exit sync FAILED for app=286100"))
        assertTrue(needsAttention("cloud: exit sync did NOT reach synchronized for app=286100 — local saves kept",
            "clean logoff complete"))
    }

    @Test fun offlineDisabledAndNeverStartedAreNotCalledUploadFailures() {
        assertFalse(needsAttention("cloud: exit upload skipped — Steam Offline Mode"))
        assertFalse(needsAttention("cloud: exit sync starting", "cloud: exit sync skipped for app=286100 — Steam Cloud disabled"))
        assertFalse(needsAttention("cloud: exit sync skipped — game launch was not confirmed"))
        assertFalse(needsAttention("cloud: launch sync FAILED for app=286100"))
    }

    @Test fun launchSyncSuccessCannotHideAnExitFailure() {
        assertTrue(needsAttention("cloud: launch sync COMPLETE for app=286100",
            "cloud: exit sync starting", "cloud: exit sync FAILED for app=286100"))
    }

    @Test fun repeatedPollsOfCompletedCloudStayInClosingSteam() {
        val log = files.newFile()
        log.writeText("""
            [wn-launcher] graceful close "Ghostrunner-Win64-Shipping.exe"
            [wn-launcher] cloud: exit sync starting
            [wn-launcher] cloud: RunAutoCloudOnAppExit(app=1249200) attempt 1 -> 1
            [wn-launcher] cloud: exit sync COMPLETE for app=1249200 — saves are synchronized (0 file(s) transferred)
        """.trimIndent())
        repeat(3) {
            val observed = XServerDisplayUtils.readSteamExitStatus(log)
            assertEquals(XServerDisplayUtils.SteamExitPhase.CLOSING_STEAM, observed.phase)
            assertEquals(XServerDisplayUtils.SteamExitCloud.SYNCHRONIZED, observed.cloud)
        }
    }

    @Test fun privateCloudSetupIsAlreadyPartOfTheSyncPhase() {
        val observed = status("cloud: exit sync starting")
        assertEquals(XServerDisplayUtils.SteamExitPhase.SYNCING_CLOUD, observed.phase)
        assertTrue(observed.cloudNeedsAttention())
    }

    @Test fun cleanLogoffDoesNotConvertFailedCloudIntoSuccess() {
        val observed = status("cloud: exit sync starting", "cloud: exit sync FAILED for app=1249200",
            "Steam_LogOff sent", "Steam_ReleaseUser done", "clean logoff complete")
        assertEquals(XServerDisplayUtils.SteamExitPhase.CLOSED, observed.phase)
        assertEquals(XServerDisplayUtils.SteamExitCloud.UNCONFIRMED, observed.cloud)
        assertTrue(observed.cloudNeedsAttention())
    }

    @Test fun aLaterExitAttemptSupersedesAnEarlierCompleteMarker() {
        assertTrue(needsAttention("cloud: exit sync COMPLETE for app=1249200",
            "cloud: exit sync starting", "cloud: exit sync FAILED for app=1249200"))
    }

    @Test fun skippedCloudCanCloseSteamWithoutClaimingAnUpload() {
        val observed = status("cloud: exit upload skipped — Steam Offline Mode", "Steam_LogOff sent")
        assertEquals(XServerDisplayUtils.SteamExitPhase.CLOSING_STEAM, observed.phase)
        assertEquals(XServerDisplayUtils.SteamExitCloud.SKIPPED, observed.cloud)
        assertFalse(observed.cloudNeedsAttention())
    }

    @Test fun missingLogAndLaunchOnlySuccessCannotConfirmExitCloud() {
        val missing = XServerDisplayUtils.readSteamExitStatus(null)
        assertFalse(missing.readable)
        assertEquals(XServerDisplayUtils.SteamExitCloud.NOT_STARTED, missing.cloud)
        val launchOnly = status("cloud: launch sync COMPLETE for app=1249200")
        assertEquals(XServerDisplayUtils.SteamExitPhase.WAITING_FOR_GAME, launchOnly.phase)
        assertEquals(XServerDisplayUtils.SteamExitCloud.NOT_STARTED, launchOnly.cloud)
    }

    @Test fun shareableDiagnosticContainsOnlyFixedStatesAndNumericMetadata() {
        val observed = status("account=private-name token=private-token path=C:/users/private-name",
            "cloud: exit sync starting", "cloud: exit sync did NOT reach synchronized for app=1249200",
            "Steam_LogOff sent")
        assertEquals("appId=1249200\nphase=CLOSING_STEAM\ncloud=UNCONFIRMED\n" +
            "hostLogReadable=true\nlauncherExited=false\nelapsedMs=90000\n",
            observed.diagnostic(1249200, 90000, false))
    }
}
