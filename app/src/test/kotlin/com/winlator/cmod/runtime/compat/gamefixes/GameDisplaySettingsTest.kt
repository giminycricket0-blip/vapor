package com.winlator.cmod.runtime.compat.gamefixes

import android.app.Application
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import com.winlator.cmod.runtime.display.xserver.ScreenInfo

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GameDisplaySettingsTest {
    private fun fixture(id: Int = 22150, source: String = "STEAM"): Triple<Container, Shortcut, File> {
        val context = RuntimeEnvironment.getApplication()
        val db = PluviaDatabase.init(context)
        val install = File(context.filesDir, "demo").apply { mkdirs() }
        runBlocking {
            db.steamAppDao().insert(SteamApp(id = id, name = "Demo", installDir = install.absolutePath))
        }
        val settings = File(install, "settings.txt").apply {
            writeText("graphics={\r\nsize={\r\n    x=1280\r\n    y=960\r\n}\r\nmultisampling=2\r\n}\r\nmusic_volume=75.000000\r\n")
        }
        val container = Container(1, ContainerManager(context)).apply {
            rootDir = File(context.filesDir, "container").apply { mkdirs() }
            screenSize = "1024x768"
        }
        val desktop = File(container.rootDir, "demo.desktop").apply {
            writeText("[Desktop Entry]\nExec=wine demo.exe\n[Extra Data]\ngame_source=$source\napp_id=$id\n")
        }
        return Triple(container, Shortcut(container, desktop), settings)
    }

    private fun launch(container: Container, shortcut: Shortcut, size: String = container.screenSize) {
        GameFixes.applyForLaunch(container, shortcut,
            ScreenInfo(GameFixes.resolveScreenSize(shortcut, size)))
    }

    @Test fun demoUsesTheVirtualScreenInsteadOfItsUnsupportedBundledResolution() {
        val (container, shortcut, settings) = fixture()
        val original = settings.readBytes()
        launch(container, shortcut)
        assertArrayEquals(original, File(settings.parentFile, "settings.txt.vapor-original").readBytes())
        assertTrue(settings.readText().contains("x=1024\r\n    y=768"))
        assertTrue(settings.readText().contains("multisampling=2"))
        assertTrue(settings.readText().contains("music_volume=75.000000"))
    }

    @Test fun effectiveScreenAfterUpscalingStillMeetsTheDemoMinimum() {
        val (container, shortcut, settings) = fixture()
        assertEquals("1280x768", GameFixes.resolveScreenSize(shortcut, "1280x720"))
        assertEquals("1024x768", GameFixes.resolveScreenSize(shortcut, "640x360"))
        assertEquals("1920x1080", GameFixes.resolveScreenSize(shortcut, "1920x1080"))
        launch(container, shortcut, "640x360")
        assertTrue(settings.readText().contains("x=1024\r\n    y=768"))
        assertEquals("1024x768", container.screenSize)
        assertEquals("", shortcut.getExtra("screenSize"))
    }

    @Test fun unknownSteamTitlesKeepTheirSettingsAndScreen() {
        val (container, shortcut, settings) = fixture(id = 999999)
        val original = settings.readBytes()
        assertEquals("1280x720", GameFixes.resolveScreenSize(shortcut, "1280x720"))
        launch(container, shortcut)
        assertArrayEquals(original, settings.readBytes())
        assertFalse(File(settings.parentFile, "settings.txt.vapor-original").exists())
    }

    @Test fun anotherStoreWithTheSameIdIsUnchanged() {
        val (container, shortcut, settings) = fixture(source = "ITCH")
        val original = settings.readBytes()
        assertEquals("640x360", GameFixes.resolveScreenSize(shortcut, "640x360"))
        launch(container, shortcut)
        assertArrayEquals(original, settings.readBytes())
    }

    @Test fun repeatedLaunchDoesNotRewriteOrReplaceTheOriginalBackup() {
        val (container, shortcut, settings) = fixture()
        val original = settings.readBytes()
        launch(container, shortcut)
        settings.setLastModified(100000L)
        launch(container, shortcut)
        assertEquals(100000L, settings.lastModified())
        launch(container, shortcut, "1920x1080")
        assertTrue(settings.readText().contains("x=1920\r\n    y=1080"))
        assertArrayEquals(original, File(settings.parentFile, "settings.txt.vapor-original").readBytes())
        assertTrue(settings.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun malformedOrAmbiguousSettingsStopPreparationAndRemainUntouched() {
        val (container, shortcut, settings) = fixture()
        val valid = settings.readText()
        for (invalid in listOf("graphics={size=unknown}", valid + valid)) {
            settings.writeText(invalid)
            assertThrows(IOException::class.java) { launch(container, shortcut) }
            assertEquals(invalid, settings.readText())
            assertFalse(File(settings.parentFile, "settings.txt.vapor-original").exists())
        }
    }

    @Test fun missingSettingsStopPreparationInsteadOfGuessingAFile() {
        val (container, shortcut, settings) = fixture()
        assertTrue(settings.delete())
        assertThrows(IOException::class.java) { launch(container, shortcut) }
        assertFalse(settings.exists())
    }

    @Test fun failedBackupLeavesTheCurrentSettingsIntact() {
        val (container, shortcut, settings) = fixture()
        val original = settings.readBytes()
        File(settings.parentFile, "settings.txt.vapor-original").mkdir()
        assertThrows(IOException::class.java) { launch(container, shortcut) }
        assertArrayEquals(original, settings.readBytes())
        assertTrue(settings.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun settingsSymlinkCannotRewriteAnotherFile() {
        val (container, shortcut, settings) = fixture()
        val outside = File(settings.parentFile!!.parentFile, "other-game.txt")
        val original = settings.readBytes()
        outside.writeBytes(original)
        settings.delete()
        Files.createSymbolicLink(settings.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { launch(container, shortcut) }
        assertArrayEquals(original, outside.readBytes())
    }

    @Test fun nonAsciiBytesAndLfLineEndingsArePreserved() {
        val (container, shortcut, settings) = fixture()
        val original = (settings.readText().replace("\r\n", "\n") + "player=José\n")
            .toByteArray(Charsets.ISO_8859_1)
        settings.writeBytes(original)
        launch(container, shortcut)
        val expected = String(original, Charsets.ISO_8859_1)
            .replace("x=1280", "x=1024").replace("y=960", "y=768")
        assertArrayEquals(expected.toByteArray(Charsets.ISO_8859_1), settings.readBytes())
    }
}
