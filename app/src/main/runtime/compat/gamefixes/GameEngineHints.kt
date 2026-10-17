package com.winlator.cmod.runtime.compat.gamefixes

import com.winlator.cmod.runtime.system.LogManager
import com.winlator.cmod.runtime.wine.PEHelper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Read-only launch diagnostics. An engine hint never selects a fix, executable or preset. */
object GameEngineHints {
    enum class Engine { UNITY, UNREAL, SOURCE, UNKNOWN }

    data class Result(
        val engine: Engine = Engine.UNKNOWN,
        val architecture: String = "unknown",
        val evidence: Set<Engine> = emptySet(),
        val graphicsImports: Set<PEHelper.GraphicsApi> = emptySet(),
        val xInputImported: Boolean = false,
        val importStatus: PEHelper.ImportStatus = PEHelper.ImportStatus.UNKNOWN,
    ) {
        fun diagnosticLine(appId: Int): String =
            "app_id=$appId engine_hint=$engine architecture=$architecture " +
                "evidence=${evidence.joinToString(",").ifEmpty { "none" }} " +
                "import_scan=$importStatus graphics_imports=${graphicsImports.joinToString(",").ifEmpty { "none_observed" }} " +
                "xinput_imported=$xInputImported settings_changed=false"
    }

    @JvmStatic
    fun inspect(installRoot: File?, relativeExecutable: String?): Result {
        if (installRoot == null || relativeExecutable.isNullOrBlank()) return Result()
        return try {
            val root = installRoot.canonicalFile
            if (!root.isDirectory) return Result()
            val probe = Probe(root)
            val exe = probe.file(root, relativeExecutable) ?: return Result()
            val architecture = PEHelper.getArchitecture(exe).ifEmpty { return Result() }
            val parent = exe.parentFile ?: return Result()
            val stem = exe.nameWithoutExtension
            val matches = linkedSetOf<Engine>()

            if (probe.file(parent, "UnityPlayer.dll") != null &&
                probe.directory(parent, "${stem}_Data") != null) {
                matches.add(Engine.UNITY)
            }

            // A packaged project binary, or its same-name bootstrap executable at the root.
            val project = if (parent.name.equals("Win64", true) || parent.name.equals("Win32", true)) {
                parent.parentFile?.takeIf { it.name.equals("Binaries", true) }?.parentFile
            } else {
                probe.directory(parent, stem)
            }
            if (project != null && probe.directory(root, "Engine") != null &&
                probe.directory(project, "Content/Paks") != null &&
                (probe.directory(project, "Binaries/Win64") != null ||
                    probe.directory(project, "Binaries/Win32") != null)) {
                matches.add(Engine.UNREAL)
            }

            if (exe.name.lowercase(Locale.ROOT) in SOURCE_EXECUTABLES &&
                listOf(parent, root).distinct().any { base ->
                    listOf("engine.dll", "bin/engine.dll", "bin/x64/engine.dll")
                        .any { probe.file(base, it) != null }
                }) {
                matches.add(Engine.SOURCE)
            }
            val imports = PEHelper.inspectImports(exe)
            Result(matches.singleOrNull() ?: Engine.UNKNOWN, architecture, matches.toSet(),
                imports.graphicsApis, imports.xInputImported, imports.status)
        } catch (_: IOException) {
            Result()
        } catch (_: SecurityException) {
            Result()
        } catch (_: IllegalArgumentException) {
            Result()
        }
    }

    /** Called only after the existing Steam launch resolver has chosen its entry executable. */
    @JvmStatic
    fun logForSteamLaunch(appId: Int, installPath: String?, relativeExecutable: String?) {
        try {
            if (!LogManager.isDebugEnabled) return
            val started = System.nanoTime()
            val result = inspect(installPath?.takeIf { it.isNotBlank() }?.let(::File), relativeExecutable)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            LogManager.logI("GameEngineHints", result.diagnosticLine(appId) + " inspection_ms=$elapsedMs")
        } catch (_: RuntimeException) {
            // Diagnostics, including logger failure, must not prevent the existing launch.
        }
    }

    private val SOURCE_EXECUTABLES = setOf("hl2.exe", "hl2_win64.exe", "tf_win64.exe", "portal2.exe", "left4dead2.exe")

    /** Only named paths are visited; directory snapshots are bounded and discarded after inspection. */
    private class Probe(private val root: File) {
        private var remainingEntries = 2048
        private val directories = mutableMapOf<File, Map<String, File>>()

        fun file(base: File, path: String): File? = resolve(base, path)?.takeIf { it.isFile }
        fun directory(base: File, path: String): File? = resolve(base, path)?.takeIf { it.isDirectory }

        private fun resolve(base: File, relative: String): File? {
            val path = relative.replace('\\', '/')
            val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
            if (path.startsWith('/') || ':' in path || '\u0000' in path ||
                parts.isEmpty() || parts.size > 16 || ".." in parts) return null
            var current = base.canonicalFile
            if (!current.toPath().startsWith(root.toPath())) return null
            for (part in parts) {
                val next = entries(current)[part.lowercase(Locale.ROOT)] ?: return null
                current = next.canonicalFile
                if (!current.toPath().startsWith(root.toPath())) return null
            }
            return current
        }

        private fun entries(directory: File): Map<String, File> = directories.getOrPut(directory) {
            if (!directory.isDirectory) return@getOrPut emptyMap()
            val entries = mutableMapOf<String, File>()
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (entry in stream) {
                    if (remainingEntries-- <= 0) throw IOException("Engine hint directory budget reached")
                    val name = entry.fileName.toString().lowercase(Locale.ROOT)
                    if (entries.put(name, entry.toFile()) != null) throw IOException("Ambiguous Windows path")
                }
            }
            entries
        }
    }
}
