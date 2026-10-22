package com.winlator.cmod.runtime.system

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * libvapor_exec.so (docs/TARGET-SDK.md): with the shim preloaded, programs in app storage
 * that W^X denies still run through the system linker or their #! interpreter. At target
 * 28, where direct exec works, the shim must not change anything.
 */
@RunWith(AndroidJUnit4::class)
class ExecShimDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val target get() = ctx.applicationInfo.targetSdkVersion
    private val shim get() = File(ctx.applicationInfo.nativeLibraryDir, "libvapor_exec.so")
    private lateinit var dir: File
    private lateinit var toybox: File

    @Before fun setUp() {
        dir = File(ctx.filesDir, "exec-shim").apply { deleteRecursively(); mkdirs() }
        toybox = File(dir, "toybox").apply { writeBytes(File("/system/bin/toybox").readBytes()); setExecutable(true, false) }
    }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun sh(command: String, preload: Boolean, path: String? = null): Pair<Int, String> {
        val pb = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
        if (preload) pb.environment()["LD_PRELOAD"] = shim.path
        if (path != null) pb.environment()["PATH"] = path
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor(15, TimeUnit.SECONDS)) { "timeout: $command" }
        Log.i("ExecShim", "target=$target preload=$preload `$command` -> ${p.exitValue()} '$out'")
        return p.exitValue() to out
    }

    private fun script(name: String, body: String) = File(dir, name).apply { writeText(body); setExecutable(true, false) }

    @Test fun shimIsPackagedInNativeLibraryDir() {
        assertTrue("missing ${shim.path}", shim.isFile && shim.length() > 0)
    }

    @Test fun dynamicBinaryInAppStorageRunsThroughTheShim() {
        val withShim = sh("${toybox.path} echo ok", preload = true)
        assertEquals(0 to "ok", withShim)
        val without = sh("${toybox.path} echo ok", preload = false)
        if (target <= 28) assertEquals(0 to "ok", without) else assertNotEquals(0, without.first)
    }

    @Test fun relativeProgramPathsAreMadeAbsoluteForTheLinker() {
        // The system linker rejects relative program paths ("expected absolute path").
        assertEquals(0 to "relative-ok", sh("cd ${dir.path} && ./toybox echo relative-ok", preload = true))
        val nested = File(dir, "sub").apply { mkdirs() }
        assertEquals(0 to "parent-ok", sh("cd ${nested.path} && ../toybox echo parent-ok", preload = true))
    }

    @Test fun reroutedProgramsSeeTheirOwnPathAsProcSelfExe() {
        // Under the linker route /proc/self/exe is the linker; wineserver uses it to find its data.
        assertEquals(0 to toybox.path, sh("${toybox.path} readlink /proc/self/exe", preload = true))
        val (code, env) = sh("${toybox.path} env", preload = true)
        assertEquals(0, code)
        assertTrue("VAPOR_EXEC_SELF leaked into the program environment", env.lines().none { it.startsWith("VAPOR_EXEC_SELF=") })
    }

    @Test fun pathLookupThroughExecvpIsRerouted() {
        // toybox env resolves the command through PATH with execvp.
        val result = sh("/system/bin/env toybox echo via-path", preload = true, path = "${dir.path}:/system/bin")
        assertEquals(0 to "via-path", result)
    }

    @Test fun scriptsRunThroughTheirInterpreter() {
        val s = script("launcher.sh", "#!/system/bin/sh\necho script-ok \"$@\"\n")
        assertEquals(0 to "script-ok a b", sh("${s.path} a b", preload = true))
    }

    @Test fun scriptWhoseInterpreterIsInAppStorageIsReroutedTwice() {
        val s = script("nested.sh", "#!${toybox.path} echo\n")
        val (code, out) = sh("${s.path} tail", preload = true)
        assertEquals(0, code)
        assertEquals("${s.path} tail", out)
    }

    @Test fun staticExecutablesKeepTheOriginalError() {
        // The system linker is a static PIE with no interpreter, so the linker route cannot apply.
        val static = File(dir, "static-copy").apply { writeBytes(File("/system/bin/linker64").readBytes()); setExecutable(true, false) }
        val (code, _) = sh("${static.path} --help", preload = true)
        if (target > 28) assertNotEquals(0, code)
    }
}
