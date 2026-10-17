package com.winlator.cmod.feature.stores.steam.service

import android.app.Application
import org.robolectric.RuntimeEnvironment
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.stores.common.InstallOwnership
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.steam.data.AppInfo
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SteamInstallRecoveryTest {
    private fun fixture(id: Int, downloaded: Boolean?): Pair<PluviaDatabase, File> {
        val app = RuntimeEnvironment.getApplication()
        val db = PluviaDatabase.init(app)
        val dir = File(app.filesDir, "SteamTest$id").apply { mkdirs() }
        runBlocking {
            db.steamAppDao().insert(SteamApp(id = id, name = "Restored demo", installDir = dir.absolutePath))
            if (downloaded != null) db.appInfoDao().insert(AppInfo(id, isDownloaded = downloaded, installPath = dir.absolutePath))
        }
        return db to dir
    }

    @Test fun completedFilesRepairAnExistingNotDownloadedRecord() {
        val (db, dir) = fixture(22150, false)
        File(dir, ".download_complete").writeText("")
        assertTrue(SteamService.isAppInstalled(22150))
        assertTrue(runBlocking { db.appInfoDao().getInstalledApp(22150) }!!.isDownloaded)
        assertEquals(dir.canonicalPath, runBlocking { db.appInfoDao().getInstalledApp(22150) }!!.installPath)
    }

    @Test fun completedFilesRecoverAMissingRecord() {
        val (_, dir) = fixture(22151, null)
        File(dir, ".download_complete").writeText("")
        assertTrue(SteamService.isAppInstalled(22151))
    }

    @Test fun anUnfinishedDownloadCannotBecomeInstalled() {
        val (_, dir) = fixture(22152, false)
        File(dir, ".download_complete").writeText("")
        File(dir, ".download_in_progress").writeText("")
        assertFalse(SteamService.isAppInstalled(22152))
    }

    @Test fun missingCompletionMarkerCannotBecomeInstalled() {
        fixture(22153, false)
        assertFalse(SteamService.isAppInstalled(22153))
    }

    @Test fun anotherStoresFolderCannotBecomeASteamInstall() {
        val (_, dir) = fixture(22154, false)
        File(dir, ".download_complete").writeText("")
        InstallOwnership.claim(dir.absolutePath, InstallStore.ITCH)
        assertFalse(SteamService.isAppInstalled(22154))
    }

    @Test(timeout = 10000) fun deletionWaitsBeforeChangingInstallFilesOrMetadata() {
        val (db, dir) = fixture(22155, true)
        val marker = File(dir, ".download_complete").apply { writeText("") }
        val save = File(dir, "save.dat").apply { writeText("keep this save") }
        runBlocking(Dispatchers.IO) {
            val upload = SteamCloseSync.reserve(22155)
            // Same dispatcher as deleteApp: enter the actual method up to its first suspension.
            val deletion = launch(start = CoroutineStart.UNDISPATCHED) { SteamService.deleteApp(22155) }
            try {
                assertFalse(deletion.isCompleted)
                assertTrue(marker.isFile)
                assertEquals("keep this save", save.readText())
                assertTrue(db.appInfoDao().getInstalledApp(22155)!!.isDownloaded)
                deletion.cancelAndJoin()
                assertTrue(upload.isActive)
                assertTrue(marker.isFile)
            } finally {
                deletion.cancelAndJoin()
                upload.complete()
            }
        }
    }
}
