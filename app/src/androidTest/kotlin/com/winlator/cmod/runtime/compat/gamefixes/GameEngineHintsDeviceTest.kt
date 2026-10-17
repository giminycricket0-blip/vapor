package com.winlator.cmod.runtime.compat.gamefixes

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.runtime.compat.gamefixes.GameEngineHints.Engine
import com.winlator.cmod.runtime.system.LogManager
import com.winlator.cmod.runtime.system.GameLaunchTiming
import com.winlator.cmod.runtime.wine.PEHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import timber.log.Timber

/** Android filesystem and production logging checks; these fixtures never start a game. */
@RunWith(AndroidJUnit4::class)
class GameEngineHintsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val files = TemporaryFolder(context.cacheDir)

    private fun unity(root: File, path: String = "Game.exe", machine: Int = 0x8664): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(88).also {
            it[0] = 77; it[1] = 90; it[60] = 64; it[64] = 80; it[65] = 69
            it[68] = machine.toByte(); it[69] = (machine ushr 8).toByte()
        })
        File(parentFile, "UnityPlayer.dll").writeText("marker")
        File(parentFile, "${nameWithoutExtension}_Data").mkdir()
    }

    @Test fun windowsCaseAndSeparatorsResolveOnAndroidWithoutEditingTheFiles() {
        val root = files.newFolder(); val exe = unity(root, "Build/My Game.exe")
        val before = exe.readBytes()
        val result = GameEngineHints.inspect(root, "build\\MY GAME.EXE")
        assertEquals(Engine.UNITY, result.engine)
        assertEquals("x86_64", result.architecture)
        assertArrayEquals(before, exe.readBytes())
    }

    @Test fun caseAmbiguousPathsAreUnknownOnTheCaseSensitiveAndroidFilesystem() {
        val root = files.newFolder(); unity(root)
        Files.createFile(File(root, "game.exe").toPath())
        assertEquals(2, root.listFiles()!!.count { it.name.equals("game.exe", true) })
        assertEquals(GameEngineHints.Result(), GameEngineHints.inspect(root, "Game.exe"))
    }

    @Test fun androidSymlinksCannotEscapeTheInstallation() {
        val root = files.newFolder(); val outside = files.newFolder(); unity(outside)
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        assertEquals(GameEngineHints.Result(), GameEngineHints.inspect(root, "linked/Game.exe"))
    }

    @Test fun androidSymlinksWithinTheInstallationRetainTheirHints() {
        val root = files.newFolder(); unity(root, "actual/Game.exe")
        Files.createSymbolicLink(File(root, "linked").toPath(), File(root, "actual").toPath())
        assertEquals(Engine.UNITY, GameEngineHints.inspect(root, "linked/Game.exe").engine)
    }

    @Test fun armCpuMetadataAndMalformedOffsetsUseTheExistingInspector() {
        val root = files.newFolder(); val exe = unity(root, machine = 0x1c4)
        assertEquals("armv7", GameEngineHints.inspect(root, "Game.exe").architecture)
        exe.writeBytes(exe.readBytes().also { bytes -> (60..63).forEach { bytes[it] = -1 } })
        assertEquals("", PEHelper.getArchitecture(exe))
        assertEquals(GameEngineHints.Result(), GameEngineHints.inspect(root, "Game.exe"))
    }

    @Test fun bundledSteamHostImportsAreReadableOnAndroidWithoutExecutingThem() {
        for ((filename, architecture) in mapOf("steam.exe" to "x86_64", "steam32.exe" to "x86")) {
            val root = files.newFolder()
            val executable = File(root, filename)
            context.assets.open("wnsteam/bionic/$filename").use { input ->
                executable.outputStream().use { input.copyTo(it) }
            }
            val result = GameEngineHints.inspect(root, filename)
            assertEquals(architecture, result.architecture)
            assertEquals(PEHelper.ImportStatus.COMPLETE, result.importStatus)
        }
    }

    @Test fun theLaunchHooksUseExistingLoggingAndLoggingFailureCannotStopLaunch() {
        val root = files.newFolder(); unity(root)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = listOf("enable_app_debug", "enable_filtered_logs", "enable_event_watch_log")
        val before = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val lines = mutableListOf<String>()
        val timingLines = mutableListOf<String>()
        var rejectLog = false
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "GameLaunchTiming") timingLines.add(message)
                if (tag != "GameEngineHints") return
                if (rejectLog) throw IllegalStateException("logger unavailable")
                lines.add(message)
            }
        }
        val androidLogTree = Timber.DebugTree()
        Timber.plant(tree)
        Timber.plant(androidLogTree)
        try {
            prefs.edit().apply { keys.forEach { putBoolean(it, false) } }.commit()
            LogManager.init(context)
            GameEngineHints.logForSteamLaunch(123, root.path, "Game.exe")
            assertTrue(lines.isEmpty())
            prefs.edit().putBoolean("enable_app_debug", true).commit()
            LogManager.init(context)
            GameEngineHints.logForSteamLaunch(123, root.path, "Game.exe")
            assertEquals(1, lines.size)
            assertTrue(lines.single().matches(Regex("app_id=123 engine_hint=UNITY architecture=x86_64 evidence=UNITY " +
                "import_scan=UNKNOWN graphics_imports=none_observed xinput_imported=false settings_changed=false inspection_ms=\\d+")))
            val previousLogs = LogManager.getShareableLogFiles(context).map { it.absolutePath }.toSet()
            LogManager.startAppLogging(context)
            GameEngineHints.logForSteamLaunch(124, root.path, "Game.exe")
            val exportedLine = lines.last()
            val timing = GameLaunchTiming.start(-1L)
            timing.mark(GameLaunchTiming.Event.DRIVER_CHECK_STARTED)
            timing.mark(GameLaunchTiming.Event.LAUNCH_FAILED)
            val exportedTiming = timingLines.last()
            assertTrue(exportedTiming.contains("driver=DRIVER_CHECK_STARTED@"))
            // logcat's file writer buffers output; flush the fixture batch without changing
            // the production logger or treating an unflushed tail as a missing tag.
            repeat(8) {
                android.util.Log.w("GameEngineHintsDeviceTest", "Synthetic export fixture: " + "x".repeat(1_024))
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            var captured = false
            while (!captured && android.os.SystemClock.elapsedRealtime() < deadline) {
                captured = LogManager.getShareableLogFiles(context)
                    .filter { it.name.startsWith("application_") && it.absolutePath !in previousLogs }
                    .any { file ->
                        ByteArrayOutputStream().use { exported ->
                            LogManager.copyShareable(context, file, exported)
                            val text = exported.toString("UTF-8")
                            text.contains(exportedLine) && text.contains(exportedTiming)
                        }
                    }
                if (!captured) Thread.sleep(50)
            }
            assertTrue("The exported application log must include the engine hint and launch summary", captured)
            File(context.getExternalFilesDir(null), "vapor-engine-hints-fixture.log")
                .writeText("Synthetic Android fixture; no game or Steam account.\n$exportedLine\n$exportedTiming\nExported application log contains both production records: true\n")
            rejectLog = true
            GameEngineHints.logForSteamLaunch(123, root.path, "Game.exe")
        } finally {
            LogManager.stopAppLogging()
            Timber.uproot(tree)
            Timber.uproot(androidLogTree)
            prefs.edit().apply {
                before.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
            }.commit()
            LogManager.init(context)
        }
    }
}
