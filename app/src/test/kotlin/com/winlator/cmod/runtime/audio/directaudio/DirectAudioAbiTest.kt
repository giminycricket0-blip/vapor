package com.winlator.cmod.runtime.audio.directaudio

import android.app.Application
import com.winlator.cmod.runtime.display.environment.ImageFs
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DirectAudioAbiTest {
    @get:Rule val temporary = TemporaryFolder()

    private val assets = sequenceOf(
        File("src/main/assets/directaudio"),
        File("app/src/main/assets/directaudio"),
    ).first { it.isDirectory }

    private fun unixlib(input: InputStream): ByteArray {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "aarch64-unix/winedirectaudio.so") {
                    return zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        throw AssertionError("no unixlib in archive")
    }

    private fun abiOf(archive: String): Int =
        DirectAudioDriver.unixAudioAbi(unixlib(File(assets, archive).inputStream()))?.entries ?: 0

    private fun classicLibrary() = unixlib(File(assets, "directaudio-wine11-arm64ec-sdk28.zip").inputStream())

    private fun systemThreadLibrary() = unixlib(checkNotNull(javaClass.classLoader!!.getResourceAsStream(
        "directaudio/directaudio-wine11-systhread-arm64ec-sdk28.zip")))

    private fun fixtureWine(library: ByteArray): ImageFs = ImageFs.find(temporary.newFolder()).also { fs ->
        fs.winePath = File(fs.rootDir, "selected-proton").path
        writeHost(fs, library)
    }

    private fun writeHost(fs: ImageFs, library: ByteArray) {
        File(fs.winePath, "lib/wine/aarch64-unix/winealsa.so").apply {
            parentFile!!.mkdirs()
            writeBytes(library)
        }
    }

    private fun install(fs: ImageFs) =
        DirectAudioDriver.install(RuntimeEnvironment.getApplication(), fs, "custom-arm64ec")

    private fun prefixPe(fs: ImageFs, dir: String) =
        File(fs.rootDir, "${ImageFs.WINEPREFIX}/drive_c/windows/$dir/winedirectaudio.drv")

    @Test
    fun everyBundledDriverAdvertisesAnAbi() {
        for (archive in assets.listFiles()!!.filter { it.name.endsWith(".zip") }) {
            assertTrue(
                "${archive.name} has no __wine_unix_call_funcs table",
                abiOf(archive.name) > 0,
            )
        }
    }

    @Test
    fun pageSizeVariantsShareOneAbi() {
        for (tag in listOf("wine10", "wine11")) {
            assertEquals(
                "$tag sdk28 and sdk35 must be the same driver built for two page sizes",
                abiOf("directaudio-$tag-arm64ec-sdk28.zip"),
                abiOf("directaudio-$tag-arm64ec-sdk35.zip"),
            )
        }
    }

    @Test
    fun theTwoDriverGenerationsAreDistinguishable() {
        val wine10 = abiOf("directaudio-wine10-arm64ec-sdk28.zip")
        val wine11 = abiOf("directaudio-wine11-arm64ec-sdk28.zip")
        assertEquals("Proton 9.0 and 10.0-4 both expose this table", 36, wine10)
        assertEquals("Proton 11.x exposes this table", 37, wine11)
        assertTrue("selection cannot work if both builds claim one ABI", wine10 != wine11)
    }

    @Test
    fun aTruncatedOrNonElfLibraryIsRejected() {
        assertNull(DirectAudioDriver.unixAudioAbi(ByteArray(0)))
        assertNull(DirectAudioDriver.unixAudioAbi("not an elf at all".toByteArray()))
        assertNull(DirectAudioDriver.unixAudioAbi(ByteArray(64) { 0x7F }))
    }

    @Test
    fun aSameCountSystemThreadInterfaceIsRefused() {
        val library = systemThreadLibrary()
        assertEquals("real upstream fixture reproduces the equal-size table", 37,
            DirectAudioDriver.unixAudioAbi(library)?.entries)
        assertFalse("no bundled driver implements this interface", install(fixtureWine(library)))
    }

    @Test
    fun switchingToASameCountIncompatibleHostRemovesTheOldPrefixDrivers() {
        val fs = fixtureWine(classicLibrary())
        assertTrue(install(fs))
        assertTrue(prefixPe(fs, "system32").isFile)
        assertTrue(prefixPe(fs, "syswow64").isFile)
        writeHost(fs, systemThreadLibrary())
        assertFalse(install(fs))
        assertFalse(prefixPe(fs, "system32").exists())
        assertFalse(prefixPe(fs, "syswow64").exists())
    }

    @Test
    fun sameSizeStalePrefixDriversAreReplacedOnRepeatInstall() {
        val fs = fixtureWine(classicLibrary())
        assertTrue(install(fs))
        val expected = listOf("system32", "syswow64").associateWith { dir -> prefixPe(fs, dir).readBytes() }
        expected.forEach { (dir, bytes) ->
            val stale = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            prefixPe(fs, dir).writeBytes(stale)
            assertEquals(bytes.size.toLong(), prefixPe(fs, dir).length())
        }
        assertTrue(install(fs))
        expected.forEach { (dir, bytes) -> assertArrayEquals(dir, bytes, prefixPe(fs, dir).readBytes()) }
    }

    @Test
    fun threadInterfaceIsPartOfTheSignatureEvenWhenTableSizesMatch() {
        val classic = checkNotNull(DirectAudioDriver.unixAudioAbi(classicLibrary()))
        val system = checkNotNull(DirectAudioDriver.unixAudioAbi(systemThreadLibrary()))
        assertEquals(classic.entries, system.entries)
        assertFalse(classic.systemThreads)
        assertTrue(system.systemThreads)
        assertTrue(classic != system)
    }

    @Test
    fun malformedSectionAndStringTableOffsetsAreRefused() {
        val original = classicLibrary()
        val overflow = original.copyOf()
        ByteBuffer.wrap(overflow).order(ByteOrder.LITTLE_ENDIAN).putLong(0x28, Long.MAX_VALUE)
        assertNull(DirectAudioDriver.unixAudioAbi(overflow))

        val badLink = original.copyOf()
        val elf = ByteBuffer.wrap(badLink).order(ByteOrder.LITTLE_ENDIAN)
        val sections = elf.getLong(0x28).toInt()
        val stride = elf.getShort(0x3a).toInt() and 0xffff
        val count = elf.getShort(0x3c).toInt() and 0xffff
        val symbols = (0 until count).map { sections + it * stride }.first { elf.getInt(it + 4) == 11 }
        elf.putInt(symbols + 40, Int.MAX_VALUE)
        assertNull(DirectAudioDriver.unixAudioAbi(badLink))
        assertNull(DirectAudioDriver.unixAudioAbi(original.copyOf(original.size - 1)))
    }

    @Test
    fun unchangedPrefixDriversAreNotRewrittenOnRepeatInstall() {
        val fs = fixtureWine(classicLibrary())
        assertTrue(install(fs))
        val time = 1_000_000L
        val files = listOf("system32", "syswow64").map { prefixPe(fs, it) }
        files.forEach { assertTrue(it.setLastModified(time)) }
        assertTrue(install(fs))
        files.forEach { assertEquals(time, it.lastModified()) }
    }

    @Test
    fun legacyInterfaceStillStagesBothGuestDrivers() {
        val fs = fixtureWine(unixlib(File(assets, "directaudio-wine10-arm64ec-sdk28.zip").inputStream()))
        assertTrue(install(fs))
        val installed = DirectAudioDriver.unixAudioAbi(
            File(fs.winePath, "lib/wine/aarch64-unix/winedirectaudio.so").readBytes())
        assertEquals(36, installed?.entries)
        assertFalse(checkNotNull(installed).systemThreads)
        assertTrue(prefixPe(fs, "system32").isFile)
        assertTrue(prefixPe(fs, "syswow64").isFile)
    }
}
