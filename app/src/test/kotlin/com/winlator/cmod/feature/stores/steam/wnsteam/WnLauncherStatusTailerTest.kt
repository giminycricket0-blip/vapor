package com.winlator.cmod.feature.stores.steam.wnsteam

import android.app.Application
import android.os.Looper
import com.winlator.cmod.R
import com.winlator.cmod.runtime.system.GameLaunchTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WnLauncherStatusTailerTest {
    @Test fun playingSessionIgnoresLateLoginAndCloudMessages() {
        val context = RuntimeEnvironment.getApplication()
        val phases = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var exits = 0
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = phases::add, onLaunchFailed = failures::add, onGameExited = { exits++ })
        tailer.consumeLine("[wn-launcher] game is running via LaunchApp")
        tailer.consumeLine("[wn-launcher] Steam_BLoggedOn=true")
        tailer.consumeLine("[wn-launcher] cloud: exit sync starting")
        tailer.consumeLine("[wn-launcher] game \"game.exe\" exited (test)")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(phases.isEmpty())
        assertTrue(failures.isEmpty())
        assertEquals(1, exits)
    }

    @Test fun timingObservesSteamStagesWithoutCopyingRawAccountOrPathData() {
        val context = RuntimeEnvironment.getApplication()
        var now = 200L
        val lines = mutableListOf<String>()
        val timing = GameLaunchTiming(100L, { now }, lines::add)
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = {}, launchTiming = timing)

        tailer.consumeLine("[wn-launcher] in-process Steam launcher starting (pid=44)")
        now = 300L
        tailer.consumeLine("[wn-launcher] Steam_BLoggedOn=true account=private-account")
        tailer.consumeLine("[wn-launcher] Steam_BLoggedOn=true account=private-account")
        now = 400L
        tailer.consumeLine("[wn-launcher] IClientAppManager.LaunchApp(appId=123)")
        now = 500L
        tailer.consumeLine("[wn-launcher] game C:\\private-folder\\game.exe is running via LaunchApp")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("STEAM_HOST_STARTED", "STEAM_SIGNED_IN", "STEAM_LAUNCH_DISPATCHED", "STEAM_PROCESS_OBSERVED"),
            lines.map { it.substringAfter("event=").substringBefore(' ') })
        assertEquals(listOf("100", "200", "300", "400"), lines.map { it.substringAfter("elapsed_ms=") })
        assertTrue(lines.none { "private" in it || "appId=" in it || "pid=" in it })
    }

    @Test fun watchForExitDoesNotEstablishThatAGameProcessWasObserved() {
        val context = RuntimeEnvironment.getApplication()
        val lines = mutableListOf<String>()
        val timing = GameLaunchTiming(0L, { 100L }, lines::add)
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = {}, launchTiming = timing)
        tailer.consumeLine("[wn-launcher] watching \"game.exe\" for exit")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(lines.isEmpty())
    }

    @Test fun rejectionCannotBeFollowedBySuccessfulTimingObservations() {
        val context = RuntimeEnvironment.getApplication()
        val lines = mutableListOf<String>()
        val timing = GameLaunchTiming(0L, { 100L }, lines::add)
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = {}, launchTiming = timing)
        tailer.consumeLine("[wn-launcher] VAPOR-ERROR: steam-sign-in-failed")
        tailer.consumeLine("[wn-launcher] Steam_BLoggedOn=true")
        tailer.consumeLine("[wn-launcher] game is running via LaunchApp")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("LAUNCH_FAILED"), lines.map { it.substringAfter("event=").substringBefore(' ') })
    }

    @Test fun nativeRejectionsReachTheUserWithoutSignalingSuccess() {
        val context = RuntimeEnvironment.getApplication()
        for ((code, message) in listOf(
            "steam-sign-in-failed" to R.string.vapor_steam_sign_in_failed,
            "unsupported-launch-option" to R.string.vapor_steam_launch_option_unsupported,
            "cloud-conflict-unresolved" to R.string.vapor_cloud_conflict_unresolved,
            "cloud-launch-failed" to R.string.vapor_cloud_launch_failed,
            "steam-launch-failed" to R.string.preloader_steam_launcher_game_never_started,
        )) {
            val failures = mutableListOf<String>()
            var completed = false
            val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
                onPhase = {}, onLaunchComplete = { completed = true }, onLaunchFailed = { failures += it })
            tailer.consumeLine("[wn-launcher] VAPOR-ERROR: $code")
            tailer.consumeLine("[wn-launcher] game is running via LaunchApp")
            tailer.consumeLine("[wn-launcher] VAPOR-ERROR: $code")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(context.getString(message)), failures)
            assertTrue(!completed)
        }
    }

    @Test fun silentLauncherTimesOutAsFailureOnlyOnce() {
        val context = RuntimeEnvironment.getApplication()
        var completed = false
        val failures = mutableListOf<String>()
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = {}, onLaunchComplete = { completed = true }, onLaunchFailed = { failures += it })
        ReflectionHelpers.setField(tailer, "startedAt", System.currentTimeMillis() - 600_000L)
        repeat(2) { tailer.watchdogTick() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(context.getString(R.string.preloader_steam_launcher_start_failed)), failures)
        assertTrue(!completed)
    }

    @Test fun steamLaunchSuccessStillCompletesThePreloader() {
        val context = RuntimeEnvironment.getApplication()
        var completed = false
        val failures = mutableListOf<String>()
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = {}, onLaunchComplete = { completed = true }, onLaunchFailed = { failures += it })
        tailer.consumeLine("[wn-launcher] game is running via LaunchApp")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(completed)
        assertTrue(failures.isEmpty())
    }

    @Test fun stalledCloudBeforeDispatchFailsOnceInsteadOfWaitingForever() {
        val context = RuntimeEnvironment.getApplication()
        val failures = mutableListOf<String>()
        var completed = 0
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = {}, onLaunchComplete = { completed++ }, onLaunchFailed = failures::add)
        tailer.consumeLine("[wn-launcher] cloud: IClientRemoteStorage -> ptr")
        repeat(2) { tailer.watchdogTick(System.currentTimeMillis() + 136_000L) }
        tailer.consumeLine("[wn-launcher] game is running via LaunchApp")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(context.getString(R.string.vapor_cloud_launch_timeout)), failures)
        assertEquals(0, completed)
    }

    @Test fun emptySuccessfulCloudDoesNotWaitForAFileTransferOrReportFailure() {
        val context = RuntimeEnvironment.getApplication()
        val failures = mutableListOf<String>()
        var completed = 0
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = {}, onLaunchComplete = { completed++ }, onLaunchFailed = failures::add)
        tailer.consumeLine("[wn-launcher] cloud: SynchronizeApp(app=286100 mode=1 flags=0 down: cloud -> local)")
        tailer.consumeLine("[wn-launcher] cloud: launch sync COMPLETE for app=286100 — saves are synchronized (0 file(s) transferred)")
        tailer.watchdogTick(System.currentTimeMillis() + 136_000L)
        tailer.consumeLine("[wn-launcher] game is running via LaunchApp")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(failures.isEmpty())
        assertEquals(1, completed)
    }

    @Test fun saveChoicePausesCloudDeadlineAndAnswerStartsAFreshBudget() {
        val context = RuntimeEnvironment.getApplication()
        val failures = mutableListOf<String>()
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = {}, onLaunchFailed = failures::add)
        tailer.consumeLine("[wn-launcher] cloud: SynchronizeApp(app=286100)")
        tailer.consumeLine("[wn-launcher] CLOUD-CONFLICT: appid=286100 local=1 remote=2")
        tailer.watchdogTick(System.currentTimeMillis() + 300_000L)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(failures.isEmpty())
        tailer.cloudConflictAnswered()
        tailer.watchdogTick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(failures.isEmpty())
        tailer.watchdogTick(System.currentTimeMillis() + 136_000L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, failures.size)
    }

    @Test fun gameExitIsDeliveredOnceAfterLaunchAndLongPlayDoesNotTimeOut() {
        val context = RuntimeEnvironment.getApplication()
        var exits = 0
        val failures = mutableListOf<String>()
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = {}, onLaunchFailed = failures::add, onGameExited = { exits++ })
        tailer.consumeLine("[wn-launcher] cloud: RunAutoCloudOnAppLaunch(app=286100)")
        tailer.consumeLine("[wn-launcher] game is running via LaunchApp")
        tailer.watchdogTick(System.currentTimeMillis() + 3_600_000L)
        repeat(2) { tailer.consumeLine("[wn-launcher] game \"game.exe\" exited (LaunchApp path)") }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, exits)
        assertTrue(failures.isEmpty())
    }

    @Test fun reportedCloudFailureCannotDismissAsASuccessfulLaunch() {
        val context = RuntimeEnvironment.getApplication()
        val failures = mutableListOf<String>()
        var completed = false
        val tailer = WnLauncherStatusTailer(context, File(context.cacheDir, "unused.log"), "Test game",
            onPhase = {}, onLaunchComplete = { completed = true }, onLaunchFailed = failures::add)
        tailer.consumeLine("[wn-launcher] cloud: launch sync FAILED for app=286100 — Steam returned EResult 16")
        tailer.consumeLine("[wn-launcher] game is running via LaunchApp")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(context.getString(R.string.vapor_cloud_launch_failed)), failures)
        assertTrue(!completed)
    }
}
