package com.winlator.cmod.runtime.system

import android.app.Application
import android.content.Context
import com.winlator.cmod.R
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerCreation
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.wine.WineInfo
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [DriverProbeShadow::class])
class GraphicsDriverCatalogTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        DriverProbeShadow.renderers.clear()
        DriverProbeShadow.renderers["System"] = "Adreno (TM) 830"
        GraphicsDriverCatalog.invalidate()
        SetupWizardActivity.recordInstalledDriver(context, "")
    }

    private fun install(id: String, renderer: String = "Adreno (TM) 830"): File {
        val root = File(context.filesDir, "contents/adrenotools/$id").apply { mkdirs() }
        File(root, "meta.json").writeText("""{"name":"$id","libraryName":"driver.so"}""")
        DriverProbeShadow.renderers[id] = renderer
        return File(root, "driver.so").apply { writeText("JVM fixture, not executable") }
    }

    @Test fun importedDriversUseTheSameDeviceGateAsBundledChoices() {
        install("turnip-fixture")
        for (renderer in listOf("Mali-G715", "Immortalis-G925", "Xclipse 940", "llvmpipe", "Unknown")) {
            DriverProbeShadow.renderers["System"] = renderer
            GraphicsDriverCatalog.invalidate()
            assertEquals(renderer, listOf("System"), GraphicsDriverCatalog.supportedVersions(context))
            assertEquals(context.getString(R.string.vapor_driver_requires_adreno, "turnip-fixture"),
                GraphicsDriverCatalog.unavailableReason(context, "turnip-fixture"))
        }
    }

    @Test fun workingImportedDriverRemainsAvailableOnAdreno() {
        install("working")
        assertEquals(listOf("System", "working"), GraphicsDriverCatalog.supportedVersions(context))
        assertNull(GraphicsDriverCatalog.unavailableReason(context, "working"))
    }

    @Test fun metadataWithoutLibraryDoesNotCountAsInstalledDriver() {
        install("incomplete").delete()
        assertEquals(listOf("System"), GraphicsDriverCatalog.supportedVersions(context))
        assertEquals(context.getString(R.string.vapor_driver_missing, "incomplete"),
            GraphicsDriverCatalog.unavailableReason(context, "incomplete"))
    }

    @Test fun malformedMetadataDoesNotMakeAnImportedDriverSelectable() {
        val file = install("broken-meta")
        File(file.parentFile, "meta.json").writeText("not json")
        assertEquals(listOf("System"), GraphicsDriverCatalog.supportedVersions(context))
    }

    @Test fun installedDriverWithFailedProbeDoesNotBecomeSupported() {
        install("cannot-load", "Unknown")
        assertEquals(listOf("System"), GraphicsDriverCatalog.supportedVersions(context))
        assertEquals(context.getString(R.string.vapor_driver_unavailable, "cannot-load"),
            GraphicsDriverCatalog.unavailableReason(context, "cannot-load"))
    }

    @Test fun launchRechecksFilesInsteadOfTrustingThePickerCache() {
        val library = install("removed")
        assertTrue(GraphicsDriverCatalog.supportedVersions(context).contains("removed"))
        library.delete()
        val failure = assertThrows(IllegalStateException::class.java) {
            GraphicsDriverCatalog.requireAvailable(context, "removed")
        }
        assertEquals(context.getString(R.string.vapor_driver_missing, "removed"), failure.message)
    }

    @Test fun launchRechecksDriverInitializationAfterSuccessfulListing() {
        install("changed")
        assertTrue(GraphicsDriverCatalog.supportedVersions(context).contains("changed"))
        DriverProbeShadow.renderers["changed"] = "Unknown"
        assertThrows(IllegalStateException::class.java) {
            GraphicsDriverCatalog.requireAvailable(context, "changed")
        }
    }

    @Test fun aMissingPreferenceDoesNotPickAnotherImportedDriverOrEraseThePreference() {
        install("unrelated")
        SetupWizardActivity.recordInstalledDriver(context, "chosen")
        assertEquals("", SetupWizardActivity.getLastInstalledDriverId(context))
        install("chosen")
        assertEquals("chosen", SetupWizardActivity.getLastInstalledDriverId(context))
    }

    @Test fun incompatiblePreferenceDoesNotBecomeANewContainerDefault() {
        install("chosen")
        SetupWizardActivity.recordInstalledDriver(context, "chosen")
        DriverProbeShadow.renderers["System"] = "Mali-G715"
        val data = ContainerCreation.buildLaunchReadyData(
            context, ContentsManager(context), "Vapor", WineInfo.MAIN_WINE_VERSION.identifier(),
        )
        assertEquals(Container.DEFAULT_GRAPHICS_DRIVER, data.getString("graphicsDriver"))
        assertTrue(data.getString("graphicsDriverConfig").split(';').contains("version=System"))
        DriverProbeShadow.renderers["System"] = "Adreno (TM) 830"
        assertEquals("chosen", SetupWizardActivity.getLastInstalledDriverId(context))
    }

    @Test fun aValidDefaultKeepsWrapperAndDriverIdentitySeparate() {
        install("chosen")
        SetupWizardActivity.recordInstalledDriver(context, "chosen")
        val data = ContainerCreation.buildLaunchReadyData(
            context, ContentsManager(context), "Vapor", WineInfo.MAIN_WINE_VERSION.identifier(),
        )
        assertEquals("wrapper", data.getString("graphicsDriver"))
        assertTrue(data.getString("graphicsDriverConfig").split(';').contains("version=chosen"))
    }

    @Test fun savedUnavailableChoiceRemainsSelectedWhenEditingUnrelatedSettings() {
        val entries = GraphicsDriverCatalog.selectionEntries(context, "removed")
        assertEquals(listOf("System", "removed"), entries)
        assertNotNull(GraphicsDriverCatalog.unavailableReason(context, "removed"))
    }

    @Test fun systemChoiceDoesNotImplySuccessfulVulkanInitialization() {
        DriverProbeShadow.renderers["System"] = "Unknown"
        assertEquals(listOf("System"), GraphicsDriverCatalog.supportedVersions(context))
        assertEquals(context.getString(R.string.vapor_driver_system_unavailable),
            assertThrows(IllegalStateException::class.java) {
                GraphicsDriverCatalog.requireAvailable(context, "System")
            }.message)
    }

    @Test fun defaultSystemRouteRemainsUsableForNonAdrenoRenderers() {
        for (renderer in listOf("Mali-G715", "Xclipse 940", "llvmpipe")) {
            DriverProbeShadow.renderers["System"] = renderer
            GraphicsDriverCatalog.requireAvailable(context, null)
            GraphicsDriverCatalog.requireAvailable(context, "System")
        }
    }
}

/** Replace the native-backed renderer query; production device gates and file checks still run. */
@Implements(GPUInformation::class)
class DriverProbeShadow {
    companion object {
        val renderers = mutableMapOf<String, String>()

        @JvmStatic @Implementation fun __staticInitializer__() = Unit

        @JvmStatic @Implementation
        fun getRenderer(driverName: String?, context: Context?): String =
            renderers[driverName ?: "System"] ?: "Unknown"
    }
}
