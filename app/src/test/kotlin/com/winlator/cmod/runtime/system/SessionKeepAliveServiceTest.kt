package com.winlator.cmod.runtime.system

import android.app.Application
import android.content.Context
import android.content.Intent
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SessionKeepAliveServiceTest {
    @Before fun preferencesWithoutAndroidKeystore() {
        // Only the chat keep-alive preference is relevant here; no account storage is tested.
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("session-test", Context.MODE_PRIVATE)
        ReflectionHelpers.setStaticField(PrefManager::class.java, "prefs", preferences)
        PrefManager.chatStayRunningOnExit = false
    }

    @After fun clearTestPreferences() {
        ReflectionHelpers.setStaticField(PrefManager::class.java, "prefs", null)
    }

    @Test fun fastFailureStillEntersForegroundBeforeStartCommandCanStopIt() {
        val context = RuntimeEnvironment.getApplication()
        SessionKeepAliveService.startDownload(context, "preflight-test")
        SessionKeepAliveService.stopDownload(context, "preflight-test")

        val controller = Robolectric.buildService(SessionKeepAliveService::class.java).create()
        try {
            val service = controller.get()
            assertNotNull(shadowOf(service).lastForegroundNotification)
            service.onStartCommand(Intent(), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test fun anActiveDownloadKeepsTheServiceRunningUntilItFinishes() {
        val context = RuntimeEnvironment.getApplication()
        SessionKeepAliveService.startDownload(context, "download-test")
        val controller = Robolectric.buildService(SessionKeepAliveService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertFalse(shadowOf(service).isStoppedBySelf)
            SessionKeepAliveService.stopDownload(context, "download-test")
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            SessionKeepAliveService.stopDownload(context, "download-test")
            controller.destroy()
        }
    }
}
