package com.winlator.cmod.feature.steamcloudsync

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SteamCloudConflictDialogTest {
    @Test fun cancellingTheDialogSelectsNeitherSaveAndNotifiesOnce() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var cancelled = 0
        var choices = 0
        val dialog = SteamCloudConflictDialog.show(activity.get(), SteamCloudConflictTimestamps("Today", "Yesterday"),
            onUseCloud = { choices++ }, onUseLocal = { choices++ }, onCancel = { cancelled++ })
        dialog.cancel()
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, cancelled)
        assertEquals(0, choices)
        assertFalse(dialog.isShowing)
        activity.pause().stop().destroy()
    }

    @Test fun dismissingAnUnansweredDialogCancelsWithoutChoosingASave() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val results = mutableListOf<String>()
        val dialog = SteamCloudConflictDialog.show(activity.get(), SteamCloudConflictTimestamps("Unknown", "Unknown"),
            onUseCloud = { results += "cloud" }, onUseLocal = { results += "local" }, onCancel = { results += "cancel" })
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("cancel"), results)
        activity.pause().stop().destroy()
    }

    @Test fun destroyingTheActivityCancelsItsUnansweredDialog() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val results = mutableListOf<String>()
        val dialog = SteamCloudConflictDialog.show(activity.get(), SteamCloudConflictTimestamps("Today", "Yesterday"),
            onUseCloud = { results += "cloud" }, onUseLocal = { results += "local" }, onCancel = { results += "cancel" })
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("cancel"), results)
        assertFalse(dialog.isShowing)
    }
}
