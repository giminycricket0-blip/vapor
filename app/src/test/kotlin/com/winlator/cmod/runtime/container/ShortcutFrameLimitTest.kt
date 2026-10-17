package com.winlator.cmod.runtime.container

import android.app.Application
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShortcutFrameLimitTest {
    private fun shortcut(limit: String?): Shortcut {
        val context = RuntimeEnvironment.getApplication()
        val container = Container(1, ContainerManager(context)).apply {
            rootDir = File(context.filesDir, "fps-container").apply { mkdirs() }
        }
        val file = File(container.rootDir, "test.desktop").apply {
            writeText("[Desktop Entry]\nExec=wine game.exe\n[Extra Data]\nlaunch_args=keep-me\n" +
                (limit?.let { "fpsLimit=$it\n" } ?: ""))
        }
        return Shortcut(container, file)
    }

    @Test fun newGameStartingLimitSurvivesSaveAndReload() {
        val shortcut = shortcut(Shortcut.DEFAULT_FPS_LIMIT.toString())
        assertEquals("60", shortcut.getExtra("fpsLimit", "0"))
        shortcut.saveData()
        val loaded = Shortcut(shortcut.container, shortcut.file)
        assertEquals("60", loaded.getExtra("fpsLimit", "0"))
        assertEquals("keep-me", loaded.getExtra("launch_args"))
    }

    @Test fun savedOffAndCustomLimitSurviveReload() {
        val shortcut = shortcut("60")
        for (limit in listOf("0", "30", "45", "120")) {
            shortcut.putExtra("fpsLimit", limit)
            shortcut.saveData()
            assertEquals(limit, Shortcut(shortcut.container, shortcut.file).getExtra("fpsLimit", "0"))
        }
    }

    @Test fun legacyMissingLimitKeepsOffAfterUnrelatedSettingsSave() {
        val shortcut = shortcut(null)
        shortcut.putExtra("inputControlsProfile", "2")
        shortcut.saveData()
        assertEquals("0", Shortcut(shortcut.container, shortcut.file).getExtra("fpsLimit", "0"))
    }
}
