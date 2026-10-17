package com.winlator.cmod.feature.library

import android.app.Application
import com.winlator.cmod.feature.shortcuts.LibraryShortcutUtils
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SteamClientLibraryTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun container(id: Int): Container = Container(id, ContainerManager(context)).apply {
        rootDir = File(context.filesDir, "imagefs/home/xuser-$id").apply { mkdirs() }
        configFile.writeText("""{"id":$id,"name":"Test container"}""")
        desktopDir.mkdirs()
    }

    private fun client(): Shortcut {
        val container = container(5)
        LinuxApps.ensureSteamShortcut(context, container)
        return Shortcut(container, File(container.desktopDir, "Steam.desktop"))
    }

    private fun game(name: String, extra: String = ""): Shortcut {
        val container = container(6)
        val file = File(container.desktopDir, "$name.desktop")
        file.writeText("[Desktop Entry]\nExec=wine game.exe\n[Extra Data]\ngame_source=CUSTOM\nuuid=test-game\n$extra")
        return Shortcut(container, file)
    }

    private fun oldLibraryId(shortcut: Shortcut): Int {
        val identity = shortcut.getExtra("uuid").ifEmpty { shortcut.file.absolutePath }
        return -(identity.hashCode().and(0x7FFFFFFF) + 1)
    }

    @Test fun installedSteamClientIsNotAPlayerLibraryGame() {
        val shortcut = client()
        val original = shortcut.file.readBytes()
        assertTrue(LinuxApps.isSteamClientShortcut(shortcut))
        assertFalse(LibraryShortcutUtils.isCustomLibraryShortcut(shortcut))
        assertArrayEquals(original, shortcut.file.readBytes())
    }

    @Test fun aRealCustomGameNamedSteamIsNotHiddenByItsTitle() {
        assertTrue(LibraryShortcutUtils.isCustomLibraryShortcut(game("Steam")))
    }

    @Test fun otherLinuxGamesRemainInTheLibrary() {
        assertTrue(LibraryShortcutUtils.isCustomLibraryShortcut(game("Linux game", "runtime=linux\n")))
    }

    @Test fun upgradeDoesNotRedrawAnOldCachedSteamClientCard() {
        val client = client()
        val game = game("Steam")
        val gameId = oldLibraryId(game)
        LibraryCache.save(context, listOf(
            SteamApp(id = oldLibraryId(client), name = "Steam"),
            SteamApp(id = gameId, name = "Steam"),
            SteamApp(id = 22150, name = "Europa Universalis III Demo"),
        ))
        assertEquals(listOf(gameId, 22150), LibraryCache.load(context).map { it.id })
        assertTrue(client.file.isFile)
    }
}
