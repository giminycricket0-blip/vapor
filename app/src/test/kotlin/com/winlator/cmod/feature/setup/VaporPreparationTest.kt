package com.winlator.cmod.feature.setup

import android.app.Application
import com.winlator.cmod.runtime.container.Container
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

    private val dxvk get() = VaporPreparation.packages.single {
        it.type == ContentProfile.ContentType.CONTENT_TYPE_DXVK
    }

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
        installFex()
        for (required in dxvk.requiredFiles) {
            val profile = installDxvk()
            File(ContentsManager.getInstallDir(context, profile), required).writeText("")
            assertNull(required, VaporPreparation.ensureContainer(context, fixtureContainerManager(), contents(), runtime))
            assertFalse(File(context.filesDir, "imagefs/home/xuser-7").exists())
        }
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
