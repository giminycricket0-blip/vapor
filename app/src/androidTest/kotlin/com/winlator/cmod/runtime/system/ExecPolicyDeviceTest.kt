package com.winlator.cmod.runtime.system

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The execution paths a target-SDK upgrade depends on (docs/TARGET-SDK.md).
 *
 * At targetSdk >= 29 Android denies execve() of files in app-writable storage (W^X), which
 * is how Box64, Wine and the launcher scripts start today. These checks record what still
 * works at the current target: running a dynamic bionic executable through
 * /system/bin/linker64, executing from nativeLibraryDir, and loading a .so from app data.
 */
@RunWith(AndroidJUnit4::class)
class ExecPolicyDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() { dir = File(ctx.filesDir, "exec-policy").apply { deleteRecursively(); mkdirs() } }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun run(vararg cmd: String): Pair<Int?, String> = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroy(); null to "timeout" } else p.exitValue() to out
    }.getOrElse { null to "${it.javaClass.simpleName}: ${it.message}" }

    private fun appDataBinary() = File(dir, "toybox").apply {
        writeBytes(File("/system/bin/toybox").readBytes()); setExecutable(true, false)
    }

    @Test fun directExecOfAppDataFilesFollowsTheTargetSdkRule() {
        val target = ctx.applicationInfo.targetSdkVersion
        val (code, out) = run(appDataBinary().path, "echo", "ok")
        Log.i("ExecPolicy", "targetSdk=$target direct exec -> $code $out")
        if (target <= 28) assertEquals("direct exec must work at targetSdk $target", 0, code)
        else assertTrue("W^X should deny direct exec at targetSdk $target: $out", code == null && out.contains("error=13"))
    }

    @Test fun dynamicBionicExecutablesRunThroughTheSystemLinker() {
        val (code, out) = run("/system/bin/linker64", appDataBinary().path, "echo", "ok")
        assertEquals(out, 0, code); assertEquals("ok", out)
    }

    @Test fun shellScriptsInAppDataRunThroughTheSystemShell() {
        val script = File(dir, "launcher.sh").apply { writeText("#!/system/bin/sh\necho script-ok\n"); setExecutable(true, false) }
        val (code, out) = run("/system/bin/sh", script.path)
        assertEquals(out, 0, code); assertEquals("script-ok", out)
    }

    @Test fun nativeLibraryDirExecutablesAndAppDataLibrariesStillLoad() {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val (code, out) = run("$nativeDir/libproot.so", "--version")
        assertEquals(out, 0, code)
        val lib = File(dir, "libcxx-copy.so").apply { writeBytes(File(nativeDir, "libc++_shared.so").readBytes()) }
        System.load(lib.path)
    }
}
