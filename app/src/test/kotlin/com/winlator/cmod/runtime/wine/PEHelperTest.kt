package com.winlator.cmod.runtime.wine

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PEHelperTest {
    @get:Rule val files = TemporaryFolder()

    private fun executable(machine: Int, offset: Int = 64): File {
        val bytes = ByteArray(88)
        bytes[0] = 'M'.code.toByte(); bytes[1] = 'Z'.code.toByte()
        repeat(4) { bytes[60 + it] = (offset ushr (it * 8)).toByte() }
        bytes[64] = 'P'.code.toByte(); bytes[65] = 'E'.code.toByte()
        bytes[68] = machine.toByte(); bytes[69] = (machine ushr 8).toByte()
        return files.newFile().apply { writeBytes(bytes) }
    }

    @Test fun distinguishesCpuArchitecturesInsteadOfReportingArmAsX86() {
        for ((machine, expected) in mapOf(0x14c to "x86", 0x8664 to "x86_64",
            0x1c0 to "arm", 0x1c4 to "armv7", 0xaa64 to "arm64", 0xa641 to "arm64ec")) {
            assertEquals(expected, PEHelper.getArchitecture(executable(machine)))
        }
    }

    @Test fun preservesTheExistingBitnessApi() {
        for (machine in listOf(0x14c, 0x1c0, 0x1c4)) {
            val exe = executable(machine)
            assertEquals(PEHelper.Architecture.X86, PEHelper.detectArchitecture(exe))
            assertFalse(PEHelper.is64Bit(exe))
        }
        for (machine in listOf(0x8664, 0xaa64, 0xa641)) {
            val exe = executable(machine)
            assertEquals(PEHelper.Architecture.X64, PEHelper.detectArchitecture(exe))
            assertTrue(PEHelper.is64Bit(exe))
        }
    }

    @Test fun malformedOffsetsDoNotThrowOrGuessAnArchitecture() {
        for (offset in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE, 0, 63, 65, 4096)) {
            val exe = executable(0x8664, offset)
            assertEquals("", PEHelper.getArchitecture(exe))
            assertEquals(PEHelper.Architecture.UNKNOWN, PEHelper.detectArchitecture(exe))
        }
    }

    @Test fun missingDirectoriesTruncationAndInvalidSignaturesAreUnknown() {
        val unknown = listOf(null, File(files.root, "missing.exe"), files.newFolder(),
            files.newFile().apply { writeBytes(ByteArray(63)) },
            executable(0x8664).apply { writeBytes(readBytes().copyOf(87)) },
            executable(0x8664).apply { writeBytes(readBytes().also { it[0] = 0 }) },
            executable(0x8664).apply { writeBytes(readBytes().also { it[64] = 0 }) },
            executable(0x9999))
        for (file in unknown) {
            assertEquals("", PEHelper.getArchitecture(file))
            assertEquals(PEHelper.Architecture.UNKNOWN, PEHelper.detectArchitecture(file))
        }
    }

    private fun imports(pe64: Boolean = true, normal: List<String> = emptyList(),
        delayed: List<String> = emptyList(), legacyDelay: Boolean = false): File {
        val bytes = ByteArray(8192)
        fun word(at: Int, value: Int) { repeat(2) { bytes[at + it] = (value ushr (8 * it)).toByte() } }
        fun dword(at: Int, value: Int) { repeat(4) { bytes[at + it] = (value ushr (8 * it)).toByte() } }
        bytes[0] = 77; bytes[1] = 90; dword(60, 64)
        bytes[64] = 80; bytes[65] = 69
        word(68, if (pe64) 0x8664 else 0x14c); word(70, 1)
        val optionalSize = if (pe64) 240 else 224
        word(84, optionalSize)
        word(88, if (pe64) 0x20b else 0x10b)
        dword(88 + (if (pe64) 24 else 28), 0x400000)
        dword(148, 512)
        val directories = 88 + if (pe64) 112 else 96
        dword(directories - 4, 16)
        val section = 88 + optionalSize
        dword(section + 8, 7680); dword(section + 12, 0x1000)
        dword(section + 16, 7680); dword(section + 20, 512)
        var nameOffset = 2560
        fun directory(index: Int, rva: Int, names: List<String>, delay: Boolean) {
            if (names.isEmpty()) return
            val stride = if (delay) 32 else 20
            dword(directories + index * 8, rva)
            dword(directories + index * 8 + 4, (names.size + 1) * stride)
            names.forEachIndexed { i, name ->
                val descriptor = rva - 0x1000 + 512 + i * stride
                if (delay) dword(descriptor, if (legacyDelay) 0 else 1)
                val nameRva = nameOffset - 512 + 0x1000
                dword(descriptor + if (delay) 4 else 12,
                    nameRva + if (delay && legacyDelay) 0x400000 else 0)
                name.toByteArray(Charsets.US_ASCII).copyInto(bytes, nameOffset)
                nameOffset += name.length + 1
            }
        }
        directory(1, 0x1000, normal, false)
        directory(13, 0x1400, delayed, true)
        return files.newFile().apply { writeBytes(bytes) }
    }

    private fun patch(file: File, offset: Int, value: Int): File = file.apply {
        writeBytes(readBytes().also { bytes -> repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() } })
    }

    @Test fun pe32AndPe64ReadBothImportTablesWithoutMutatingTheExecutable() {
        for (pe64 in listOf(false, true)) {
            val file = imports(pe64, listOf("D3D9.DLL", "d3d11.dll", "XInput1_4.dll"), listOf("d3d12.dll"))
            val original = file.readBytes()
            val result = PEHelper.inspectImports(file)
            assertEquals(PEHelper.ImportStatus.COMPLETE, result.status)
            assertEquals(setOf(PEHelper.GraphicsApi.D3D9, PEHelper.GraphicsApi.D3D11, PEHelper.GraphicsApi.D3D12), result.graphicsApis)
            assertTrue(result.xInputImported)
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun reportsMultipleGraphicsApisWithoutChoosingARenderer() {
        val result = PEHelper.inspectImports(imports(normal = listOf("d3d8.dll", "d3d10core.dll", "opengl32.dll", "vulkan-1.dll")))
        assertEquals(PEHelper.ImportStatus.COMPLETE, result.status)
        assertEquals(setOf(PEHelper.GraphicsApi.D3D8, PEHelper.GraphicsApi.D3D10,
            PEHelper.GraphicsApi.OPENGL, PEHelper.GraphicsApi.VULKAN), result.graphicsApis)
        assertFalse(result.xInputImported)
    }

    @Test fun noImportIsNotEvidenceOfNoGraphicsOrControllerSupport() {
        for (names in listOf(emptyList(), listOf("kernel32.dll", "dxgi.dll", "d3d11.dll.bak", "xinput1_4.dll.bak"))) {
            val result = PEHelper.inspectImports(imports(normal = names))
            assertEquals(PEHelper.ImportStatus.COMPLETE, result.status)
            assertTrue(result.graphicsApis.isEmpty())
            assertFalse(result.xInputImported)
        }
    }

    @Test fun legacyPe32DelayImportsUseTheImageBase() {
        val result = PEHelper.inspectImports(imports(false, delayed = listOf("d3d12.dll", "xinput9_1_0.dll"), legacyDelay = true))
        assertEquals(PEHelper.ImportStatus.COMPLETE, result.status)
        assertEquals(setOf(PEHelper.GraphicsApi.D3D12), result.graphicsApis)
        assertTrue(result.xInputImported)
    }

    @Test fun unsupportedDelayAttributesDiscardPartialHints() {
        val legacy64 = imports(normal = listOf("d3d11.dll"), delayed = listOf("d3d12.dll"), legacyDelay = true)
        val reserved = patch(imports(delayed = listOf("d3d12.dll")), 1536, 2)
        for (file in listOf(legacy64, reserved)) assertUnknown(file)
    }

    @Test fun outOfRangeRvasAndUnterminatedTablesAreUnknown() {
        assertUnknown(patch(imports(normal = listOf("d3d11.dll")), 524, -16))
        assertUnknown(patch(imports(normal = listOf("d3d11.dll")), 212, 20))
        assertUnknown(patch(imports(normal = listOf("d3d11.dll")), 196, -1))
        val zeroFilled = imports(normal = listOf("d3d11.dll"))
        patch(zeroFilled, 328 + 8, 0x4000)
        patch(zeroFilled, 524, 0x1000 + 7680)
        assertUnknown(zeroFilled)
    }

    @Test fun overlappingSectionsAndExcessiveSectionCountsAreUnknown() {
        val file = imports(normal = listOf("d3d11.dll"))
        file.writeBytes(file.readBytes().also {
            it[70] = 2
            it.copyInto(it, 368, 328, 368)
        })
        assertUnknown(file)
        file.writeBytes(file.readBytes().also { it[70] = 97 })
        assertUnknown(file)
    }

    @Test fun missingTruncatedAndUnterminatedNamesAreUnknown() {
        assertUnknown(null)
        assertUnknown(files.newFolder())
        for (length in listOf(0, 63, 87, 200, 400, 2575)) {
            val file = imports(normal = listOf("d3d11.dll"), delayed = listOf("xinput1_4.dll"))
            file.writeBytes(file.readBytes().copyOf(length))
            assertUnknown(file)
        }
        val file = imports(normal = listOf("d3d11.dll"))
        file.writeBytes(file.readBytes().also { java.util.Arrays.fill(it, 2560, 2816, 65.toByte()) })
        assertUnknown(file)
    }

    @Test fun descriptorBudgetBoundsAHostileTable() {
        val file = imports(normal = listOf("d3d11.dll"))
        val bytes = file.readBytes()
        val descriptor = bytes.copyOfRange(512, 532)
        // All descriptors share a valid name after the table; no terminator within the budget.
        repeat(4) { descriptor[12 + it] = (0x2a00 ushr (8 * it)).toByte() }
        repeat(256) { descriptor.copyInto(bytes, 512 + it * 20) }
        "d3d11.dll\u0000".toByteArray().copyInto(bytes, 7168)
        file.writeBytes(bytes)
        patch(file, 212, 257 * 20)
        assertUnknown(file)
    }

    private fun assertUnknown(file: File?) {
        val result = PEHelper.inspectImports(file)
        assertEquals(PEHelper.ImportStatus.UNKNOWN, result.status)
        assertTrue(result.graphicsApis.isEmpty())
        assertFalse(result.xInputImported)
    }
}
