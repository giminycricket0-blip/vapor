package com.winlator.cmod.feature.steamcloudsync

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.data.PostSyncInfo
import com.winlator.cmod.feature.stores.steam.enums.SaveLocation
import com.winlator.cmod.feature.stores.steam.enums.SyncResult
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.feature.sync.google.GoogleAuthMode
import com.winlator.cmod.runtime.container.Shortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

object SteamLaunchCloudSync {
    fun interface StatusSink {
        fun show(text: String)
    }

    /** Launch-time Steam cloud reconciliation. Steam-only — never starts Google Play Games or Drive consent. */
    @JvmStatic
    fun syncBeforeLaunch(
        activity: Activity,
        shortcut: Shortcut?,
        cloudSyncEnabled: Boolean,
        statusSink: StatusSink,
    ): Boolean {
        if (shortcut == null) return true
        if (shortcut.getExtra("game_source") != "STEAM") return true
        if (!cloudSyncEnabled || SteamCloudSyncHelper.isOfflineMode(shortcut)) return true

        SteamCloudSyncHelper.forceDownloadOnContainerSwap(activity, shortcut)

        statusSink.show(activity.getString(R.string.preloader_checking_cloud))
        val initialSync =
            runCatching {
                SteamCloudSyncHelper.syncBeforeLaunchBlocking(activity, shortcut, SaveLocation.None)
            }.onFailure { e ->
                Timber.tag("SteamLaunchCloudSync").w(e, "Steam launch cloud sync failed")
            }.getOrNull()

        when (initialSync?.syncResult) {
            SyncResult.Success,
            SyncResult.UpToDate,
            null,
            -> {
                statusSink.show(activity.getString(R.string.preloader_initializing))
                return true
            }
            SyncResult.Conflict -> {
                return showConflictAndResolve(activity, shortcut, statusSink, initialSync)
            }
            SyncResult.PendingOperations -> {
                Timber.tag("SteamLaunchCloudSync").w(
                    "Steam launch cloud sync has pending remote operations for %s: %s",
                    shortcut.name,
                    initialSync.pendingRemoteOperations,
                )
                statusSink.show(activity.getString(R.string.preloader_initializing))
                return true
            }
            else -> {
                Timber.tag("SteamLaunchCloudSync").w(
                    "Steam launch cloud sync returned %s for %s",
                    initialSync.syncResult,
                    shortcut.name,
                )
                statusSink.show(activity.getString(R.string.preloader_initializing))
                return true
            }
        }
    }

    private fun showConflictAndResolve(
        activity: Activity,
        shortcut: Shortcut,
        statusSink: StatusSink,
        initialSync: PostSyncInfo,
    ): Boolean {
        // A single completed future makes timeout/dismissal and a late UI choice mutually exclusive.
        val decision = CompletableFuture<Pair<SaveLocation, Boolean>>()
        val cancelled = SaveLocation.None to false
        val timestamps = SteamCloudSyncHelper.timestampsFromSyncInfo(activity, shortcut, initialSync)
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val cancelObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) decision.complete(cancelled)
        }
        if (activity.isFinishing || activity.isDestroyed ||
            lifecycle?.currentState == Lifecycle.State.DESTROYED
        ) return false

        // Only read/written on the UI thread, including the cleanup posted below.
        var dialog: android.app.Dialog? = null
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                decision.complete(cancelled)
                return@runOnUiThread
            }
            lifecycle?.addObserver(cancelObserver)
            try {
                dialog = SteamCloudConflictDialog.show(
                    activity, timestamps,
                    onUseCloud = { keep -> decision.complete(SaveLocation.Remote to keep) },
                    onUseLocal = { keep -> decision.complete(SaveLocation.Local to keep) },
                    onCancel = { decision.complete(cancelled) },
                )
            } catch (t: Throwable) {
                Timber.tag("SteamLaunchCloudSync").w(t, "Could not show the cloud-conflict choice; cancelling launch")
                decision.complete(cancelled)
            }
        }
        val choice = try {
            decision.get(10, TimeUnit.MINUTES)
        } catch (_: TimeoutException) {
            decision.complete(cancelled)
            decision.getNow(cancelled)
        } catch (_: InterruptedException) {
            decision.complete(cancelled)
            Thread.currentThread().interrupt()
            return false
        } finally {
            activity.runOnUiThread {
                lifecycle?.removeObserver(cancelObserver)
                dialog?.dismiss()
            }
        }
        val (preferredSave, keepBackup) = choice
        if (preferredSave == SaveLocation.None) return false
        val useCloud = preferredSave == SaveLocation.Remote

        if (keepBackup && useCloud) {
            // "Use Cloud" overwrites the LOCAL save — snapshot it first.
            backupDiscardedSave(activity, shortcut, GameSaveBackupManager.BackupOrigin.LOCAL)
        }
        if (keepBackup && !useCloud) {
            // "Use Local" overwrites the CLOUD save (maybe newer progress from another device); Steam keeps no server-side history, so capture the cloud copy first.
            backupDiscardedCloudSave(activity, shortcut)
        }
        statusSink.show(
            activity.getString(
                if (useCloud) R.string.preloader_downloading_cloud else R.string.preloader_uploading_cloud,
            ),
        )
        runCatching {
            SteamCloudSyncHelper.syncBeforeLaunchBlocking(activity, shortcut, preferredSave)
        }.onFailure { e ->
            Timber.tag("SteamLaunchCloudSync").w(e, "Steam conflict resolution sync failed")
        }
        statusSink.show(activity.getString(R.string.preloader_initializing))
        return true
    }

    private fun backupDiscardedSave(
        activity: Activity,
        shortcut: Shortcut,
        origin: GameSaveBackupManager.BackupOrigin,
    ) {
        val gameId = shortcut.getExtra("app_id").takeIf { it.isNotEmpty() } ?: return
        val gameName = shortcut.name ?: "Unknown"
        try {
            val result =
                runBlocking(Dispatchers.IO) {
                    GameSaveBackupManager.backupDiscardedSave(
                        activity = activity,
                        gameSource = GameSaveBackupManager.GameSource.STEAM,
                        gameId = gameId,
                        gameName = gameName,
                        origin = origin,
                        authMode = GoogleAuthMode.RESUME,
                        containerHint = SteamCloudSyncHelper.resolveShortcutContainer(activity, shortcut),
                    )
                }
            Timber.tag("SteamLaunchCloudSync").i("Discarded Steam save backup: %s", result.message)
        } catch (e: Exception) {
            Timber.tag("SteamLaunchCloudSync").w(e, "Failed to back up discarded Steam save")
        }
    }

    /** Capture the Steam Cloud save before "Use Local" overwrites it — non-destructive pre-capture (never touches the live local save). Best-effort. */
    private fun backupDiscardedCloudSave(
        activity: Activity,
        shortcut: Shortcut,
    ) {
        val appId = shortcut.getExtra("app_id").takeIf { it.isNotEmpty() }?.toIntOrNull() ?: return
        try {
            val captured =
                runBlocking(Dispatchers.IO) {
                    SteamSaveSnapshotManager.captureCloudSnapshot(
                        activity.applicationContext,
                        appId,
                        SteamCloudSyncHelper.resolveShortcutContainer(activity, shortcut),
                    )
                }
            Timber.tag("SteamLaunchCloudSync").i(
                "Cloud save pre-capture before Use-Local for appId=%d: %s",
                appId,
                captured,
            )
        } catch (e: Exception) {
            Timber.tag("SteamLaunchCloudSync").w(e, "Failed to capture cloud save before Use-Local")
        }
    }
}
