package com.winlator.cmod.shared.ui.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloaderDialogStateTest {
    @Test fun launchAfterCloudTransferDoesNotKeepTheTransferPercentage() {
        val state = PreloaderDialogState()
        state.setTitle("Test game")
        state.setBadge("STEAM")
        state.setSubtitle("Game profile")
        state.setStep("Downloading cloud saves")
        state.setIndeterminate(false)
        state.setProgress(100)

        state.setStep("Starting the game")

        assertEquals("Starting the game", state.text.value)
        assertTrue(state.isIndeterminate.value)
        assertEquals(0, state.progress.intValue)
        assertEquals("Test game", state.title.value)
        assertEquals("STEAM", state.badge.value)
        assertEquals("Game profile", state.subtitle.value)
    }

    @Test fun laterMeasuredTransferCanReportProgressAgain() {
        val state = PreloaderDialogState()
        state.setStep("Starting the game")
        state.setStep("Uploading cloud saves")
        state.setIndeterminate(false)
        state.setProgress(65)

        assertFalse(state.isIndeterminate.value)
        assertEquals(65, state.progress.intValue)
    }

    @Test fun measuredProgressRemainsWithinDisplayableBounds() {
        val state = PreloaderDialogState()
        state.setIndeterminate(false)
        state.setProgress(-5)
        assertEquals(0, state.progress.intValue)
        state.setProgress(105)
        assertEquals(100, state.progress.intValue)
    }
}
