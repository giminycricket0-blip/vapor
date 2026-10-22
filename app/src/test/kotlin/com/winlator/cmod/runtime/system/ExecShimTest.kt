package com.winlator.cmod.runtime.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecShimTest {
    private val shim = "/data/app/x/lib/arm64/libvapor_exec.so"

    @Test fun shippedTargetKeepsTheEnvironmentUnchanged() {
        assertFalse(ExecShim.required(28))
        assertEquals("/a/libredirect.so /a/libsysvshm.so", ExecShim.withShim("/a/libredirect.so /a/libsysvshm.so", shim, 28))
        assertEquals("", ExecShim.withShim(null, shim, 28))
    }

    @Test fun restrictedTargetsPutTheShimFirst() {
        assertTrue(ExecShim.required(29))
        assertEquals("$shim:/a/libnetshim.so:/a/libfakeinput.so", ExecShim.withShim("/a/libnetshim.so:/a/libfakeinput.so", shim, 35))
        assertEquals("$shim:/a/libredirect.so:/a/libsysvshm.so", ExecShim.withShim("/a/libredirect.so /a/libsysvshm.so", shim, 35))
        assertEquals(shim, ExecShim.withShim("", shim, 35))
    }

    @Test fun theShimIsNeverDuplicatedAndAMissingLibraryChangesNothing() {
        assertEquals("$shim:/a/b.so", ExecShim.withShim("/a/b.so:$shim", shim, 35))
        assertEquals("/a/b.so", ExecShim.withShim("/a/b.so", null, 35))
    }

    @Test fun launchesFromAppStorageAreWrappedOnlyWhenTheShimIsPreloaded() {
        val wine = arrayOf("/data/user/0/app.vapor.android/files/contents/Proton/x/bin/wine", "wineboot", "-u")
        val wrapped = ExecShim.launchArgv(wine, "$shim:/a/libnetshim.so")
        assertEquals(listOf("/system/bin/sh", "-c", "exec \"\$0\" \"\$@\"") + wine.toList(), wrapped.toList())
        // No shim in the environment (target 28): unchanged, same array.
        assertTrue(ExecShim.launchArgv(wine, "/a/libnetshim.so") === wine)
        assertTrue(ExecShim.launchArgv(wine, null) === wine)
        // System programs never need it.
        val ldd = arrayOf("/system/bin/ldd", "x")
        assertTrue(ExecShim.launchArgv(ldd, shim) === ldd)
        assertTrue(ExecShim.launchArgv(arrayOf(), shim).isEmpty())
    }
}
