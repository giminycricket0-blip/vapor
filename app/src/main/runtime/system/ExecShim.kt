package com.winlator.cmod.runtime.system

import android.content.Context
import java.io.File

/**
 * Preloads libvapor_exec.so into guest processes when the app targets SDK > 28, where
 * Android's W^X rule denies execve() of Wine, Box64 and launcher scripts in app storage
 * (docs/TARGET-SDK.md). At target 28 and below nothing changes, so the shipped app keeps
 * its exact environment.
 */
object ExecShim {
    const val LIBRARY = "libvapor_exec.so"
    private const val FIRST_RESTRICTED_TARGET = 29

    @JvmStatic fun required(targetSdk: Int): Boolean = targetSdk >= FIRST_RESTRICTED_TARGET

    /**
     * [ldPreload] with the shim first (so its exec wrappers win), when [targetSdk] needs it and
     * the library exists. Entries may be ':' or ' ' separated; the shim is never duplicated.
     */
    @JvmStatic fun withShim(ldPreload: String?, shimPath: String?, targetSdk: Int): String {
        val current = ldPreload.orEmpty()
        if (!required(targetSdk) || shimPath.isNullOrEmpty()) return current
        val others = current.split(':', ' ').filter { it.isNotEmpty() && it != shimPath }
        return (listOf(shimPath) + others).joinToString(":")
    }

    private val SYSTEM_PREFIXES = listOf("/system/", "/apex/", "/vendor/", "/product/", "/odm/")

    /**
     * The app's own first exec cannot be helped by LD_PRELOAD (the shim only exists inside the new
     * process). When [ldPreload] carries the shim and [argv] starts a program outside the system
     * partitions, start it as `/system/bin/sh -c 'exec "$0" "$@"' argv...`: sh may run, loads the
     * shim, and its exec of the program is rerouted (including /proc/self/exe). Arguments are
     * passed positionally, so no quoting changes. Otherwise [argv] is returned unchanged.
     */
    @JvmStatic fun launchArgv(argv: Array<String>, ldPreload: String?): Array<String> {
        if (argv.isEmpty() || ldPreload.isNullOrEmpty()) return argv
        if (ldPreload.split(':', ' ').none { it.endsWith("/$LIBRARY") }) return argv
        if (SYSTEM_PREFIXES.any { argv[0].startsWith(it) }) return argv
        return arrayOf("/system/bin/sh", "-c", "exec \"\$0\" \"\$@\"") + argv
    }

    /** For launch sites with their own environment: adds the shim to [env] when needed and wraps [argv]. */
    @JvmStatic fun prepare(context: Context, argv: List<String>, env: MutableMap<String, String>): List<String> {
        val preload = withShim(context, env["LD_PRELOAD"])
        if (preload.isEmpty()) return argv
        env["LD_PRELOAD"] = preload
        return launchArgv(argv.toTypedArray(), preload).toList()
    }

    /** Context form used by the launchers: resolves the packaged library and the app's target. */
    @JvmStatic fun withShim(context: Context, ldPreload: String?): String {
        val info = context.applicationInfo
        val shim = File(info.nativeLibraryDir, LIBRARY).takeIf { it.isFile }?.path
        return withShim(ldPreload, shim, info.targetSdkVersion)
    }
}
