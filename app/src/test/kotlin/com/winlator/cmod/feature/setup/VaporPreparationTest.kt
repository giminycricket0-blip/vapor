package com.winlator.cmod.feature.setup

import android.app.Application
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.shared.util.KeyValueSet
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VaporPreparationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val runtime get() = VaporPreparation.packages.first()

    private fun installProfile(includeFiles: Boolean = true): ContentProfile {
        val profile = ContentProfile().apply {
            type = runtime.type
            verName = "11.0-2-arm64ec-steam-unixlib"
            verCode = 3
        }
        val root = ContentsManager.getInstallDir(context, profile).apply { mkdirs() }
        File(root, "profile.json").writeText("""{
            "type":"Proton", "versionName":"${profile.verName}", "versionCode":3,
            "description":"Test", "files":[],
            "wine":{"binPath":"bin","libPath":"lib","prefixPack":"prefixPack.tzst"}
        }""")
        if (includeFiles) runtime.requiredFiles.forEach { path ->
            File(root, path).apply { parentFile!!.mkdirs(); writeText("runtime fixture") }
        }
        return profile
    }

    private fun contents() = ContentsManager(context).also { it.syncContents() }

    private val fex get() = VaporPreparation.packages.single {
        it.type == ContentProfile.ContentType.CONTENT_TYPE_FEXCORE
    }

    private val dxvk get() = VaporPreparation.packages.first {
        it.type == ContentProfile.ContentType.CONTENT_TYPE_DXVK
    }

    private val modernDxvk get() = VaporPreparation.packages.last {
        it.type == ContentProfile.ContentType.CONTENT_TYPE_DXVK
    }

    private fun installModernDxvk() = installDxvk(modernDxvk.entryName.substringAfter('-'))

    private fun installDxvk(version: String = dxvk.entryName.substringAfter('-')): ContentProfile {
        val profile = ContentProfile().apply {
            type = ContentProfile.ContentType.CONTENT_TYPE_DXVK
            verName = version.substringBeforeLast('-')
            verCode = version.substringAfterLast('-').toInt()
        }
        val root = ContentsManager.getInstallDir(context, profile).apply { mkdirs() }
        File(root, "profile.json").writeText("""{
            "type":"DXVK", "versionName":"${profile.verName}", "versionCode":${profile.verCode},
            "description":"Non-executable test fixture", "files":[]
        }""")
        dxvk.requiredFiles.forEach { path ->
            File(root, path).apply { parentFile!!.mkdirs(); writeText("DXVK fixture") }
        }
        return profile
    }

    private val vkd3d get() = VaporPreparation.packages.single {
        it.type == ContentProfile.ContentType.CONTENT_TYPE_VKD3D
    }

    private fun installVkd3d(version: String = vkd3d.entryName.substringAfter('-')): ContentProfile {
        val profile = ContentProfile().apply {
            type = ContentProfile.ContentType.CONTENT_TYPE_VKD3D
            verName = version.substringBeforeLast('-')
            verCode = version.substringAfterLast('-').toInt()
        }
        val root = ContentsManager.getInstallDir(context, profile).apply { mkdirs() }
        File(root, "profile.json").writeText("""{
            "type":"VKD3D", "versionName":"${profile.verName}", "versionCode":${profile.verCode},
            "description":"Non-executable test fixture", "files":[]
        }""")
        vkd3d.requiredFiles.forEach { path ->
            File(root, path).apply { parentFile!!.mkdirs(); writeText("VKD3D fixture") }
        }
        return profile
    }

    private fun installFex(version: String = fex.entryName.substringAfter('-')): ContentProfile {
        val profile = ContentProfile().apply {
            type = ContentProfile.ContentType.CONTENT_TYPE_FEXCORE
            verName = version.substringBeforeLast('-')
            verCode = version.substringAfterLast('-').toInt()
        }
        val root = ContentsManager.getInstallDir(context, profile).apply { mkdirs() }
        File(root, "profile.json").writeText("""{
            "type":"FEXCore", "versionName":"${profile.verName}", "versionCode":${profile.verCode},
            "description":"Non-executable test fixture", "files":[]
        }""")
        fex.requiredFiles.forEach { path ->
            File(root, path).apply { parentFile!!.mkdirs(); writeText("FEX fixture") }
        }
        return profile
    }

    /** Exercise production default selection without invoking native prefix extraction. */
    private fun fixtureContainerManager() = object : ContainerManager(context) {
        override fun createContainer(data: JSONObject, contents: ContentsManager): Container {
            return Container(7, this).apply {
                rootDir = File(context.filesDir, "imagefs/home/xuser-7").apply { mkdirs() }
                loadData(data)
            }
        }
    }

    @Test fun freshGuidedContainerUsesPinnedFexEvenWhenANewerComponentIsInstalled() {
        val runtime = installProfile()
        installVkd3d()
        installModernDxvk()
        installFex()
        installDxvk()
        installFex("9999-unrelated-99")
        val manager = fixtureContainerManager()
        val created = VaporPreparation.ensureContainer(context, manager, contents(), runtime)!!
        assertEquals(fex.entryName.substringAfter('-'), created.getFEXCoreVersion())
        assertEquals(fex.entryName.substringAfter('-'), JSONObject(created.configFile.readText()).getString("fexcoreVersion"))
        assertFalse(created.envVars.contains("FEX_DISKCACHE"))
    }

    @Test fun freshGuidedContainerRequiresBothFexDllAndUnixLibraryPairs() {
        val runtime = installProfile()
        installVkd3d()
        installModernDxvk()
        installDxvk()
        for (required in fex.requiredFiles) {
            val profile = installFex()
            File(ContentsManager.getInstallDir(context, profile), required).writeText("")
            assertNull(required, VaporPreparation.ensureContainer(context, fixtureContainerManager(), contents(), runtime))
            assertFalse(File(context.filesDir, "imagefs/home/xuser-7").exists())
        }
    }

    @Test fun freshGuidedContainerUsesPinnedDxvkEvenWhenANewerComponentIsInstalled() {
        val runtime = installProfile()
        installVkd3d()
        installModernDxvk()
        installFex()
        installDxvk()
        installDxvk("3.1.1-arm64ec-experiment-99")
        val created = VaporPreparation.ensureContainer(context, fixtureContainerManager(), contents(), runtime)!!
        assertEquals(dxvk.entryName.substringAfter('-'), KeyValueSet(created.dxWrapperConfig).get("version"))
        val persisted = JSONObject(created.configFile.readText()).getString("dxwrapperConfig")
        assertEquals(dxvk.entryName.substringAfter('-'), KeyValueSet(persisted).get("version"))
        assertEquals("None", KeyValueSet(persisted).get("vkd3dVersion"))
    }

    @Test fun freshGuidedContainerRequiresBothDxvkGuestArchitectures() {
        val runtime = installProfile()
        installVkd3d()
        installModernDxvk()
        installFex()
        for (required in dxvk.requiredFiles) {
            val profile = installDxvk()
            File(ContentsManager.getInstallDir(context, profile), required).writeText("")
            assertNull(required, VaporPreparation.ensureContainer(context, fixtureContainerManager(), contents(), runtime))
            assertFalse(File(context.filesDir, "imagefs/home/xuser-7").exists())
        }
    }

    @Test fun freshGuidedContainerKeepsLegacyGraphicsUntilD3d12IsDetected() {
        val runtime = installProfile()
        installFex(); installDxvk(); installModernDxvk(); installVkd3d(); installVkd3d("9999-arm64ec-experiment-99")
        val created = VaporPreparation.ensureContainer(context, fixtureContainerManager(), contents(), runtime)!!
        val saved = JSONObject(created.configFile.readText()).getString("dxwrapperConfig")
        assertEquals("None", KeyValueSet(saved).get("vkd3dVersion"))
    }

    @Test fun freshGuidedContainerRequiresBothD3d12DllsInBothGuestArchitectures() {
        val runtime = installProfile()
        installFex(); installDxvk(); installModernDxvk()
        for (required in vkd3d.requiredFiles) {
            val profile = installVkd3d()
            File(ContentsManager.getInstallDir(context, profile), required).writeText("")
            assertNull(required, VaporPreparation.ensureContainer(context, fixtureContainerManager(), contents(), runtime))
            assertFalse(File(context.filesDir, "imagefs/home/xuser-7").exists())
        }
    }

    @Test fun freshGuidedContainerRequiresTheModernDxgiPairToBeReady() {
        val runtime = installProfile()
        installFex(); installDxvk(); installVkd3d()
        for (required in modernDxvk.requiredFiles) {
            val profile = installModernDxvk()
            File(ContentsManager.getInstallDir(context, profile), required).writeText("")
            assertNull(required, VaporPreparation.ensureContainer(context, fixtureContainerManager(), contents(), runtime))
            assertFalse(File(context.filesDir, "imagefs/home/xuser-7").exists())
        }
    }

    private fun launchShortcut(): Shortcut {
        val container = Container(9, ContainerManager(context)).apply {
            rootDir = File(context.filesDir, "imagefs/home/xuser-9").apply { mkdirs() }
            loadData(JSONObject().apply {
                put("wineVersion", this@VaporPreparationTest.runtime.entryName)
                put("dxwrapper", Container.DEFAULT_DXWRAPPER)
                put("dxwrapperConfig", "version=${dxvk.entryName.substringAfter('-')},vkd3dVersion=None,async=0")
            })
            saveData()
        }
        val file = File(container.rootDir, "Game.desktop").apply {
            writeText("[Desktop Entry]\nName=Game\nExec=wine Game.exe\n")
        }
        return Shortcut(container, file)
    }

    /** Minimal PE with one normal import; no Wine or game code is executed. */
    private fun game(import: String = "d3d12.dll", machine: Int = 0x8664): File {
        val root = File(context.filesDir, "game").apply { mkdirs() }
        val bytes = ByteArray(1536)
        fun dword(at: Int, n: Int) { repeat(4) { bytes[at + it] = (n ushr (8 * it)).toByte() } }
        val pe64 = machine != 0x14c
        val section = if (pe64) 328 else 312
        val directory = if (pe64) 200 else 184
        bytes[0] = 77; bytes[1] = 90; dword(60, 64)
        bytes[64] = 80; bytes[65] = 69
        bytes[68] = machine.toByte(); bytes[69] = (machine ushr 8).toByte(); bytes[70] = 1
        bytes[84] = (if (pe64) 240 else 224).toByte()
        bytes[88] = 11; bytes[89] = (if (pe64) 2 else 1).toByte()
        dword(148, 512); dword(directory - 4, 16)
        dword(directory + 8, 0x1000); dword(directory + 12, 40)
        dword(section + 8, 1024); dword(section + 12, 0x1000)
        dword(section + 16, 1024); dword(section + 20, 512)
        dword(524, 0x1100)
        import.toByteArray().copyInto(bytes, 768)
        File(root, "Game.exe").writeBytes(bytes)
        return root
    }

    @Test fun d3d12ImportsSelectOnePreparedPairWithoutChangingSavedSettings() {
        installModernDxvk(); installVkd3d(); installVkd3d("9999-arm64ec-experiment-99")
        val shortcut = launchShortcut()
        val configBefore = shortcut.container.configFile.readBytes()
        val shortcutBefore = shortcut.file.readBytes()
        val graphics = KeyValueSet(shortcut.container.dxWrapperConfig)
        assertTrue(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut, game(), "Game.exe", graphics))
        assertEquals(modernDxvk.entryName.substringAfter('-'), graphics.get("version"))
        assertEquals(vkd3d.entryName.substringAfter('-'), graphics.get("vkd3dVersion"))
        assertEquals("0", graphics.get("async"))
        assertArrayEquals(configBefore, shortcut.container.configFile.readBytes())
        assertArrayEquals(shortcutBefore, shortcut.file.readBytes())
    }

    @Test fun olderUnknownAndEscapedExecutablesKeepTheExistingGraphicsChoice() {
        val shortcut = launchShortcut()
        val graphics = KeyValueSet(shortcut.container.dxWrapperConfig)
        for (root in listOf(game("d3d11.dll"), null)) {
            assertFalse(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut, root, "Game.exe", graphics))
        }
        assertFalse(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut, game(), "../Game.exe", graphics))
        assertEquals(dxvk.entryName.substringAfter('-'), graphics.get("version"))
        assertEquals("None", graphics.get("vkd3dVersion"))
    }

    @Test fun explicitGameGraphicsRemainAuthoritativeButIgnoredOverridesDoNotDisableDefaults() {
        installModernDxvk(); installVkd3d()
        val shortcut = launchShortcut()
        val graphics = KeyValueSet(shortcut.container.dxWrapperConfig)
        shortcut.putExtra("dxwrapperConfig", shortcut.container.dxWrapperConfig)
        assertFalse(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut, game(), "Game.exe", graphics))
        shortcut.putExtra("use_container_defaults", "1")
        assertTrue(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut, game(), "Game.exe", graphics))
    }

    @Test fun customRuntimeWrapperAndContainerGraphicsAreNotReplaced() {
        val shortcut = launchShortcut()
        val graphics = KeyValueSet(shortcut.container.dxWrapperConfig)
        val root = game()
        shortcut.container.wineVersion = "custom-arm64ec-0"
        assertFalse(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut, root, "Game.exe", graphics))
        shortcut.container.wineVersion = runtime.entryName
        shortcut.putExtra("wineVersion", "custom-arm64ec-0")
        assertFalse(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut, root, "Game.exe", graphics))
        shortcut.putExtra("wineVersion", "")
        shortcut.putExtra("dxwrapper", "wined3d")
        assertFalse(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut, root, "Game.exe", graphics))
        shortcut.putExtra("dxwrapper", "")
        graphics.put("version", "custom-kept-0")
        assertFalse(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut, root, "Game.exe", graphics))
    }

    @Test fun aMissingModernComponentFailsBeforeChangingEitherHalfOfThePair() {
        val shortcut = launchShortcut()
        val root = game()
        for (spec in listOf(modernDxvk, vkd3d)) {
            for (required in spec.requiredFiles) {
                installModernDxvk(); installVkd3d()
                val profile = contents().getProfileByEntryName(spec.entryName)!!
                File(ContentsManager.getInstallDir(context, profile), required).writeText("")
                val graphics = KeyValueSet(shortcut.container.dxWrapperConfig)
                try {
                    VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut, root, "Game.exe", graphics)
                    fail("Missing $required was accepted")
                } catch (error: IllegalStateException) {
                    assertTrue(error.message!!.contains("Prepare Vapor"))
                }
                assertEquals(dxvk.entryName.substringAfter('-'), graphics.get("version"))
                assertEquals("None", graphics.get("vkd3dVersion"))
            }
        }
    }

    @Test fun x86AndX64D3d12ImportsAreEligibleButUnknownNativeArmIsNot() {
        installModernDxvk(); installVkd3d()
        val shortcut = launchShortcut()
        for (machine in listOf(0x14c, 0x8664)) {
            assertTrue(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut,
                game(machine = machine), "Game.exe", KeyValueSet(shortcut.container.dxWrapperConfig)))
        }
        assertFalse(VaporPreparation.applyDirect3D12Defaults(context, contents(), shortcut,
            game(machine = 0xaa64), "Game.exe", KeyValueSet(shortcut.container.dxWrapperConfig)))
    }

    @Test fun downloadMustMatchBothSizeAndHashBeforeInstallation() {
        // SHA-256("abc"), independently known rather than derived by the code under test.
        val spec = runtime.copy(downloadBytes = 3, sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val archive = File(context.cacheDir, "download.wcp")
        assertFalse(VaporPreparation.archiveMatches(archive, spec))
        archive.writeText("abc")
        assertTrue(VaporPreparation.archiveMatches(archive, spec))
        archive.writeText("abd")
        assertFalse(VaporPreparation.archiveMatches(archive, spec))
        archive.writeText("ab")
        assertFalse(VaporPreparation.archiveMatches(archive, spec))
    }

    @Test fun metadataAloneDoesNotMakeAComponentReady() {
        installProfile(includeFiles = false)
        assertTrue(contents().getProfileByEntryName(runtime.entryName).isInstalled)
        assertNull(VaporPreparation.installed(context, contents(), runtime))
    }

    @Test fun completedComponentsCanBeReusedOnRetry() {
        installProfile()
        assertEquals(runtime.entryName, ContentsManager.getEntryName(VaporPreparation.installed(context, contents(), runtime)))
    }

    @Test fun emptyCoreFileCannotPassReadiness() {
        val profile = installProfile()
        File(ContentsManager.getInstallDir(context, profile), "bin/wine").writeText("")
        assertNull(VaporPreparation.installed(context, contents(), runtime))
    }

    private fun container(id: Int, name: String): Container {
        val root = File(context.filesDir, "imagefs/home/xuser-$id").apply { mkdirs() }
        val container = Container(id, ContainerManager(context)).apply { rootDir = root }
        container.configFile.writeText("""{"id":$id,"name":"$name","wineVersion":"${runtime.entryName}","screenSize":"960x540","graphicsDriver":"wrapper","fexcoreVersion":"2607-84e127a63-unix-0","dxwrapperConfig":"version=custom-kept-0,async=0"}""")
        return container
    }

    @Test fun preparationRetryReusesRuntimeContainerWithoutResettingSettingsOrSaves() {
        val profile = installProfile()
        installFex()
        val existing = container(4, "My custom setup")
        val before = existing.configFile.readBytes()
        val save = File(existing.rootDir, ".wine/drive_c/game/save.dat").apply { parentFile!!.mkdirs(); writeText("keep my save") }
        repeat(2) {
            val manager = ContainerManager(context)
            val reused = VaporPreparation.ensureContainer(context, manager, contents(), profile)
            assertEquals(4, reused!!.id)
            assertEquals("My custom setup", reused.name)
            assertEquals("960x540", reused.screenSize)
            assertEquals("2607-84e127a63-unix-0", reused.getFEXCoreVersion())
            assertEquals("version=custom-kept-0,async=0", reused.dxWrapperConfig)
            assertEquals(1, manager.containers.size)
        }
        assertArrayEquals(before, existing.configFile.readBytes())
        assertEquals("keep my save", save.readText())
    }

    @Test fun guidedArmDefaultIsSelectedBeforeAnUnrelatedContainer() {
        installProfile()
        container(1, "Other")
        container(4, "Vapor")
        SetupWizardActivity.saveDefaultArm64ContainerId(context, 4)
        assertEquals(4, SetupWizardActivity.getPreferredGameContainer(context, ContainerManager(context))!!.id)
    }

    @Test fun anExistingExplicitX86DefaultStillWins() {
        installProfile()
        container(1, "Existing preference")
        container(4, "Vapor")
        SetupWizardActivity.saveDefaultX86ContainerId(context, 1)
        SetupWizardActivity.saveDefaultArm64ContainerId(context, 4)
        assertEquals(1, SetupWizardActivity.getPreferredGameContainer(context, ContainerManager(context))!!.id)
    }
}
