package com.winlator.cmod.runtime.compat.gamefixes

import com.winlator.cmod.runtime.compat.gamefixes.GameEngineHints.Engine
import com.winlator.cmod.runtime.wine.PEHelper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameEngineHintsTest {
    @get:Rule val files = TemporaryFolder()

    private fun exe(root: File, path: String = "Game.exe", machine: Int = 0x8664): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(88).also {
            it[0] = 77; it[1] = 90; it[60] = 64; it[64] = 80; it[65] = 69
            it[68] = machine.toByte(); it[69] = (machine ushr 8).toByte()
        })
    }

    private fun unity(root: File, path: String = "Game.exe", machine: Int = 0x8664): File =
        exe(root, path, machine).also {
            File(it.parentFile, "UnityPlayer.dll").writeText("marker")
            File(it.parentFile, "${it.nameWithoutExtension}_Data").mkdir()
        }

    @Test fun unityHintsDoNotWriteToTheGameOrCreateSettings() {
        val root = files.newFolder()
        val game = unity(root)
        val before = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
        val result = GameEngineHints.inspect(root, game.name)
        assertEquals(Engine.UNITY, result.engine)
        assertEquals("x86_64", result.architecture)
        assertEquals(setOf(Engine.UNITY), result.evidence)
        val after = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
        assertEquals(before, after)
    }

    @Test fun nestedWindowsPathsAndCaseVariantsResolveTheSelectedExecutable() {
        val root = files.newFolder()
        unity(root, "Build/Windows/My Game.EXE", 0x14c)
        val result = GameEngineHints.inspect(root, ".\\build\\WINDOWS\\my game.exe")
        assertEquals(Engine.UNITY, result.engine)
        assertEquals("x86", result.architecture)
    }

    @Test fun unrealMatchesTheSelectedProjectAndBothPackagedWindowsLayouts() {
        for (platform in listOf("Win32", "Win64")) {
            val root = files.newFolder()
            val path = "Project/Binaries/$platform/Project-$platform-Shipping.exe"
            exe(root, path)
            File(root, "Engine").mkdir()
            File(root, "Project/Content/Paks").mkdirs()
            assertEquals(Engine.UNREAL, GameEngineHints.inspect(root, path).engine)
            exe(root, "Project.exe")
            assertEquals(Engine.UNREAL, GameEngineHints.inspect(root, "Project.exe").engine)
        }
    }

    @Test fun anEngineFolderAloneDoesNotIdentifyAnUnrealGameOrAnUnrelatedTool() {
        val root = files.newFolder()
        exe(root, "Tool.exe")
        File(root, "Engine/Binaries/Win64").mkdirs()
        File(root, "AnotherGame/Content/Paks").mkdirs()
        assertEquals(Engine.UNKNOWN, GameEngineHints.inspect(root, "Tool.exe").engine)
    }

    @Test fun sourceNeedsBothAKnownExecutableAndEngineBinary() {
        val root = files.newFolder()
        exe(root, "tf_win64.exe")
        assertEquals(Engine.UNKNOWN, GameEngineHints.inspect(root, "tf_win64.exe").engine)
        File(root, "bin/x64").mkdirs()
        File(root, "bin/x64/engine.dll").writeText("marker")
        assertEquals(Engine.SOURCE, GameEngineHints.inspect(root, "tf_win64.exe").engine)
        exe(root, "unrelated.exe")
        assertEquals(Engine.UNKNOWN, GameEngineHints.inspect(root, "unrelated.exe").engine)
    }

    @Test fun conflictingEngineEvidenceIsUnknownWithoutChoosingTheFirstMatch() {
        val root = files.newFolder()
        unity(root, "tf_win64.exe")
        File(root, "engine.dll").writeText("marker")
        val result = GameEngineHints.inspect(root, "tf_win64.exe")
        assertEquals(Engine.UNKNOWN, result.engine)
        assertEquals(setOf(Engine.UNITY, Engine.SOURCE), result.evidence)
        assertEquals("x86_64", result.architecture)
    }

    @Test fun aDifferentExecutableDoesNotInheritTheUnityPlayersDataDirectory() {
        val root = files.newFolder()
        unity(root)
        exe(root, "Launcher.exe")
        assertEquals(Engine.UNKNOWN, GameEngineHints.inspect(root, "Launcher.exe").engine)
    }

    @Test fun missingNonPeAndUnknownInputsRemainUnknown() {
        val root = files.newFolder()
        unity(root).writeText("not a PE executable")
        for (path in listOf(null, "", "missing.exe", "Game.exe", "Game_Data")) {
            assertEquals(GameEngineHints.Result(), GameEngineHints.inspect(root, path))
        }
        assertEquals(GameEngineHints.Result(), GameEngineHints.inspect(null, "Game.exe"))
    }

    @Test fun traversalAbsoluteDriveAndMalformedPathsDoNotInspectOtherFiles() {
        val root = files.newFolder("game")
        val outside = files.newFolder("game-other")
        unity(outside)
        for (path in listOf("../game-other/Game.exe", "sub/../../game-other/Game.exe", "C:\\Game.exe",
            "\\\\server\\Game.exe", File(outside, "Game.exe").path, "Game.exe\u0000")) {
            assertEquals(GameEngineHints.Result(), GameEngineHints.inspect(root, path))
        }
    }

    @Test fun executableAndMarkerSymlinksCannotSupplyEvidenceOutsideTheInstallRoot() {
        val root = files.newFolder(); val outside = files.newFolder()
        val externalGame = unity(outside)
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "Game.exe").toPath(), externalGame.toPath())
        assertEquals(GameEngineHints.Result(), GameEngineHints.inspect(root, "linked/Game.exe"))
        assertEquals(GameEngineHints.Result(), GameEngineHints.inspect(root, "Game.exe"))
        File(root, "Game.exe").delete(); exe(root)
        File(root, "Game_Data").mkdir()
        Files.createSymbolicLink(File(root, "UnityPlayer.dll").toPath(), File(outside, "UnityPlayer.dll").toPath())
        assertEquals(Engine.UNKNOWN, GameEngineHints.inspect(root, "Game.exe").engine)
    }

    @Test fun linksWithinTheSameInstallationAreAllowed() {
        val root = files.newFolder()
        unity(root, "actual/Game.exe")
        Files.createSymbolicLink(File(root, "linked").toPath(), File(root, "actual").toPath())
        assertEquals(Engine.UNITY, GameEngineHints.inspect(root, "linked/Game.exe").engine)
    }

    @Test fun tooManyDirectoryEntriesOrPathSegmentsReturnUnknown() {
        val root = files.newFolder()
        unity(root)
        repeat(2048) { File(root, "unused-$it").createNewFile() }
        assertEquals(GameEngineHints.Result(), GameEngineHints.inspect(root, "Game.exe"))
        val nestedRoot = files.newFolder()
        val path = (1..17).joinToString("/") { "dir$it" } + "/Game.exe"
        unity(nestedRoot, path)
        assertEquals(GameEngineHints.Result(), GameEngineHints.inspect(nestedRoot, path))
    }

    @Test fun largeUnrelatedSubtreesAreNotScanned() {
        val root = files.newFolder()
        unity(root)
        val assets = File(root, "Assets").apply { mkdir() }
        repeat(2048) { File(assets, "unused-$it").createNewFile() }
        assertEquals(Engine.UNITY, GameEngineHints.inspect(root, "Game.exe").engine)
    }

    @Test fun diagnosticOutputContainsOnlyPublicIdAndFixedHints() {
        val root = files.newFolder("private-account-path")
        unity(root, "private-token.exe", 0xaa64)
        val line = GameEngineHints.inspect(root, "private-token.exe").diagnosticLine(123)
        assertEquals("app_id=123 engine_hint=UNITY architecture=arm64 evidence=UNITY " +
            "import_scan=UNKNOWN graphics_imports=none_observed xinput_imported=false settings_changed=false", line)
        assertFalse(line.contains("private"))
    }

    @Test fun selectedExecutableImportHintsReachTheExistingDiagnosticWithoutChangingSettings() {
        val root = files.newFolder("private-account")
        val game = unity(root)
        val bytes = game.readBytes().copyOf(1536)
        fun dword(at: Int, value: Int) { repeat(4) { bytes[at + it] = (value ushr (8 * it)).toByte() } }
        bytes[70] = 1
        bytes[84] = 240.toByte()
        bytes[88] = 11; bytes[89] = 2
        dword(148, 512); dword(196, 16)
        dword(208, 0x1000); dword(212, 40)
        dword(336, 1024); dword(340, 0x1000); dword(344, 1024); dword(348, 512)
        dword(524, 0x1100)
        "d3d11.dll\u0000".toByteArray().copyInto(bytes, 768)
        game.writeBytes(bytes)
        val result = GameEngineHints.inspect(root, game.name)
        assertEquals(Engine.UNITY, result.engine)
        assertEquals(PEHelper.ImportStatus.COMPLETE, result.importStatus)
        assertEquals(setOf(PEHelper.GraphicsApi.D3D11), result.graphicsImports)
        assertTrue(result.diagnosticLine(123).contains("graphics_imports=D3D11"))
        assertTrue(result.diagnosticLine(123).endsWith("settings_changed=false"))
        assertFalse(result.diagnosticLine(123).contains("private"))
        assertArrayEquals(bytes, game.readBytes())
    }
}
