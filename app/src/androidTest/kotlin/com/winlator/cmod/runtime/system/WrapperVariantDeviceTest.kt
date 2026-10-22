package com.winlator.cmod.runtime.system

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.R
import com.winlator.cmod.shared.io.TarCompressorUtils
import com.winlator.cmod.shared.util.StringUtils
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every bundled wrapper package extracts through the app's extractor; the experimental one is the qualified build. */
@RunWith(AndroidJUnit4::class)
class WrapperVariantDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun extract(variant: WrapperVariant): File {
        val dir = File(context.cacheDir, "wrapper-variant-${variant.stateName}").apply { deleteRecursively(); mkdirs() }
        assertTrue("${variant.asset} failed to extract", TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, variant.asset, dir))
        return dir
    }

    @Test fun everyVariantPackageExtractsAWrapperAndTheIcdManifest() {
        for (variant in WrapperVariant.entries) {
            val dir = extract(variant)
            assertTrue("${variant.id}: wrapper library", File(dir, "usr/lib/libvulkan_wrapper.so").length() > 1_000_000)
            assertTrue("${variant.id}: ICD manifest", File(dir, "usr/share/vulkan/icd.d/wrapper_icd.aarch64.json").readText().contains("libvulkan_wrapper.so"))
            dir.deleteRecursively()
        }
    }

    @Test fun experimentalPackageIsTheQualifiedBuildWithTheStockHooks() {
        val vapor = extract(WrapperVariant.VAPOR)
        val stock = extract(WrapperVariant.STOCK)
        assertEquals("095c9a81e67c20155669b76ae9b39a6b79a79034f5e4109bde668cde989f1712",
            sha256(File(vapor, "usr/lib/libvulkan_wrapper.so")))
        for (hook in listOf("libadrenotools.so", "libmain_hook.so", "libhook_impl.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so"))
            assertEquals(hook, sha256(File(stock, "usr/lib/$hook")), sha256(File(vapor, "usr/lib/$hook")))
        vapor.deleteRecursively(); stock.deleteRecursively()
    }

    @Test fun everyGraphicsDriverChoiceMapsToItsOwnPackage() {
        val ids = context.resources.getStringArray(R.array.graphics_driver_entries).map(StringUtils::parseIdentifier)
        assertEquals(listOf("wrapper", "wrapper-leegao", "wrapper-gamenative", "wrapper-vapor"), ids)
        assertEquals(WrapperVariant.entries.toList(), ids.map(WrapperVariant::forDriver))
    }
}
