package com.winlator.cmod.feature.steamcloudsync

import android.app.Activity
import android.app.Dialog
import android.util.TypedValue
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.bindPaneNav

object SteamCloudConflictDialog {
    @JvmStatic
    fun show(
        activity: Activity,
        timestamps: SteamCloudConflictTimestamps,
        onUseCloud: (keepBackup: Boolean) -> Unit,
        onUseLocal: (keepBackup: Boolean) -> Unit,
        onCancel: () -> Unit,
    ): Dialog {
        val dialog =
            Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(true)
                setCanceledOnTouchOutside(false)
                window?.apply {
                    setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                    )
                    setBackgroundDrawableResource(android.R.color.transparent)
                }
            }

        val navRegistry = PaneNavRegistry()
        var answered = false
        val cancel = {
            if (!answered) {
                answered = true
                dialog.dismiss()
                onCancel()
            }
        }
        dialog.setOnCancelListener { cancel() }
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        if (activity.isFinishing || activity.isDestroyed || lifecycle?.currentState == Lifecycle.State.DESTROYED) {
            cancel()
            return dialog
        }
        val destroyObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) cancel()
        }

        val composeView =
            ComposeView(activity).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                (activity as? ComponentActivity)?.let {
                    setViewTreeLifecycleOwner(it)
                    setViewTreeSavedStateRegistryOwner(it)
                }
                setContent {
                    WinNativeTheme {
                        SteamCloudConflictDialogContent(
                            navRegistry = navRegistry,
                            timestamps = timestamps,
                            initialKeepBackup = GameSaveBackupManager.isKeepReplacedBackupEnabled(activity),
                            onKeepBackupChanged = { enabled ->
                                GameSaveBackupManager.setKeepReplacedBackupEnabled(activity, enabled)
                            },
                            onUseCloud = { keepBackup ->
                                if (!answered) {
                                    answered = true
                                    dialog.dismiss()
                                    onUseCloud(keepBackup)
                                }
                            },
                            onUseLocal = { keepBackup ->
                                if (!answered) {
                                    answered = true
                                    dialog.dismiss()
                                    onUseLocal(keepBackup)
                                }
                            },
                            onCancel = cancel,
                        )
                    }
                }
            }

        dialog.setContentView(composeView)
        dialog.show()
        val restoreNav = dialog.window?.bindPaneNav(navRegistry, onDismiss = cancel)
        dialog.setOnDismissListener {
            lifecycle?.removeObserver(destroyObserver)
            restoreNav?.invoke()
            cancel()
        }
        lifecycle?.addObserver(destroyObserver)
        dialog.window?.apply {
            val dm = activity.resources.displayMetrics
            val horizontalMarginPx =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, dm).toInt()
            val maxDialogWidthPx =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 560f, dm).toInt()
            val targetWidth = (dm.widthPixels - (horizontalMarginPx * 2)).coerceAtMost(maxDialogWidthPx)
            setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }
}
