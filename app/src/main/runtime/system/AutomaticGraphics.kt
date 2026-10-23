package com.winlator.cmod.runtime.system

import android.content.Context
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.utils.SteamUtils
import com.winlator.cmod.feature.setup.VaporPreparation
import com.winlator.cmod.runtime.compat.gamefixes.GameEngineHints
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.content.ContentProfile.ContentType
import com.winlator.cmod.runtime.wine.PEHelper
import com.winlator.cmod.shared.util.KeyValueSet
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Owns the bounded automatic graphics path. Runtime preparation still owns component inputs.
 * No random Wine/FEX changes, downloads, second settings database, or save/prefix resets. */
object AutomaticGraphics {
    const val EXTRA_ATTEMPT = "vapor.graphics_attempt"
    private const val CHOICE = "vapor_graphics_choice"
    private const val TAG = "AutomaticGraphics"
    private val wineErrorPrefix = Regex("^[0-9a-f]+:err:")
    const val MAX_ATTEMPTS = 4

    data class Route(
        val driver: String,
        val dxvk: String,
        val vkd3d: String = "None",
        val wineD3D: Boolean = false,
        val emulateBC: Boolean = false,
    ) {
        val id = digest(listOf(driver, dxvk, vkd3d, wineD3D.toString(), emulateBC.toString())).take(24)
        val direct3d12 get() = vkd3d != "None"
        val description get() = "$driver · " + when {
            wineD3D -> "WineD3D"
            direct3d12 -> "DXVK ${dxvk.substringBefore('-')} + VKD3D ${vkd3d.substringBefore('-')}"
            else -> "DXVK ${dxvk.substringBefore('-')}"
        }

        fun apply(graphics: KeyValueSet, driverConfig: MutableMap<String, String>): String {
            driverConfig["version"] = driver
            if (emulateBC) {
                driverConfig["bcnEmulation"] = "full"
                driverConfig["bcnEmulationType"] = "cpu"
            }
            graphics.put("version", dxvk)
            graphics.put("vkd3dVersion", vkd3d)
            return if (wineD3D) "wined3d" else Container.DEFAULT_DXWRAPPER
        }
    }

    internal enum class Failure { VULKAN_STARTUP, DIRECT3D12_STARTUP, DIRECT3D11_DEVICE }

    // Exact translator diagnostics only. A timeout, missing VC runtime, generic crash,
    // Steam/Cloud failure, or a video/loading stall is not a graphics diagnosis.
    internal fun classify(line: String): Failure? {
        if (line.length > 8192) return null
        val text = line.lowercase(Locale.ROOT).trim()
        if ("[wn-launcher]" in text || "steam cloud" in text) return null
        if (!text.startsWith("err:") && !text.startsWith("error:") &&
            !wineErrorPrefix.containsMatchIn(text)) return null
        return when {
            "vkd3d_create_vk_instance" in text && "failed to create vulkan instance" in text -> Failure.DIRECT3D12_STARTUP
            "vkd3d_create_vk_device" in text && "failed to create vulkan device" in text -> Failure.DIRECT3D12_STARTUP
            // D3D11 device creation refused the requested feature level. Only the DXVK generation
            // can change this, so the retry tries a different one. The strings are the ones the
            // two bundled builds actually emit.
            "requested feature level not supported" in text ||
                "minimum required feature level not supported" in text ||
                "failed to create d3d11 device" in text ||
                "failed to create a dxgi factory" in text -> Failure.DIRECT3D11_DEVICE
            "failed to create vulkan device" in text ||
                "dxvkinstance::createinstance: failed to create vulkan instance" in text ||
                "dxvkinstance: failed to create instance" in text ||
                "dxvkinstance: no adapters found" in text ||
                "dxvk: no adapters found" in text ||
                "d3d11createdevice: no vulkan adapters found" in text -> Failure.VULKAN_STARTUP
            else -> null
        }
    }

    /** Per-launch state. Continuations contain only IDs checked against a fresh plan. */
    class Session internal constructor(
        val fingerprint: String,
        private val routes: List<Route>,
        private val previous: List<String>,
        private var needsD3D12: Boolean,
        val route: Route,
    ) {
        val attempt get() = previous.size + 1
        @Volatile private var cancelled = false
        private var failed = false
        private var processObserved = false
        private var firstFrameAt = -1L
        private var frames = 0
        private var healthy = false
        private var startedAt = -1L

        @JvmOverloads
        @Synchronized fun processObserved(now: Long = SystemClock.elapsedRealtime()) {
            processObserved = true
            if (startedAt < 0) startedAt = now
        }

        @JvmOverloads
        @Synchronized fun frame(now: Long = SystemClock.elapsedRealtime()) {
            if (failed || cancelled || healthy) return
            if (startedAt < 0) startedAt = now
            if (!processObserved) return
            if (firstFrameAt < 0) firstFrameAt = now
            frames++
            // Evidence of sustained rendering, not "Verified" or proof of game playability.
            if (frames >= 120 && now - firstFrameAt >= 20_000) healthy = true
        }

        @JvmOverloads
        @Synchronized fun observe(line: String, now: Long = SystemClock.elapsedRealtime()): Boolean {
            if (cancelled || failed || healthy || (startedAt >= 0 && now - startedAt > 90_000)) return false
            val failure = classify(line) ?: return false
            failed = true
            if (failure == Failure.DIRECT3D12_STARTUP) needsD3D12 = true
            return true
        }

        @Synchronized fun cancel() { cancelled = true }

        /** Called only after processes, host, environment, and the Cloud reservation are done. */
        @Synchronized fun afterCleanup(clean: Boolean, cloudSafe: Boolean, foreground: Boolean): Bundle? {
            if (!clean || !cloudSafe || !foreground || cancelled || !failed || attempt >= MAX_ATTEMPTS) return null
            val tried = previous + route.id
            if (routes.none { it.id !in tried && it.direct3d12 == needsD3D12 }) return null
            return Bundle().apply {
                putString("fingerprint", fingerprint)
                putStringArrayList("tried", ArrayList(tried))
                putBoolean("d3d12", needsD3D12)
            }
        }

        @Synchronized fun remember(shortcut: Shortcut, clean: Boolean) {
            if (!failed && (!clean || cancelled || !healthy)) return
            // Re-read the owner file so unrelated settings edited while playing are retained.
            Shortcut(shortcut.container, shortcut.file).apply {
                val choice = "$fingerprint|${route.id}"
                if (failed && getExtra(CHOICE) != choice) return
                putExtra(CHOICE, if (failed) null else choice)
                saveData()
            }
        }

        @Synchronized fun failed() = failed
        @Synchronized fun isCancelled() = cancelled
    }

    internal fun start(
        routes: List<Route>, fingerprint: String, d3d12: Boolean,
        continuation: Bundle?, remembered: String,
    ): Session {
        val tried = continuation?.getStringArrayList("tried")?.toList().orEmpty()
        check(continuation == null || (continuation.getString("fingerprint") == fingerprint &&
            tried.size in 1 until MAX_ATTEMPTS && tried.distinct().size == tried.size &&
            tried.all { id -> routes.any { it.id == id } })) { "Graphics components changed during retry. Launch again from the game page." }
        val need12 = d3d12 || continuation?.getBoolean("d3d12", false) == true
        val choices = routes.filter { it.direct3d12 == need12 && it.id !in tried }
        val saved = remembered.takeIf { it.substringBefore('|') == fingerprint }?.substringAfter('|')
        val route = choices.firstOrNull { continuation == null && it.id == saved }
            ?: choices.firstOrNull()
            ?: throw IllegalStateException(if (need12)
                "No installed driver passes the minimum Vulkan checks for the prepared DirectX 12 runtime. A different driver or supported hardware is required."
                else "No installed driver passes the minimum Vulkan checks for automatic graphics. Check Graphics in game settings.")
        return Session(fingerprint, routes, tried, need12, route)
    }

    internal fun eligible(shortcut: Shortcut, graphics: KeyValueSet): Boolean {
        val container = shortcut.container
        return shortcut.getSettingExtra("wineVersion", container.wineVersion) == VaporPreparation.packages.first().entryName &&
            shortcut.getSettingExtra("graphicsDriver", container.graphicsDriver) == Container.DEFAULT_GRAPHICS_DRIVER &&
            shortcut.getSettingExtra("dxwrapper", container.dxWrapper) == Container.DEFAULT_DXWRAPPER &&
            (shortcut.usesContainerDefaults() || listOf("dxwrapperConfig", "graphicsDriver", "graphicsDriverConfig")
                .all { shortcut.getExtra(it).isBlank() }) &&
            graphics.get("version") == VaporPreparation.packages.first { it.type == ContentType.CONTENT_TYPE_DXVK }.entryName.substringAfter('-') &&
            graphics.get("vkd3dVersion") in listOf("", "None")
    }

    /** Runs on the existing preparation worker. Only installed guided components are candidates. */
    @JvmStatic
    fun prepare(
        context: Context, contents: ContentsManager, shortcut: Shortcut,
        installRoot: File?, relativeExe: String?, graphics: KeyValueSet,
        driverConfig: MutableMap<String, String>, continuation: Bundle?,
    ): Session? {
        if (!eligible(shortcut, graphics)) {
            check(continuation == null) { "Graphics settings changed during retry. Launch again from the game page." }
            return null
        }
        val hints = GameEngineHints.inspect(installRoot, relativeExe)
        if (hints.architecture !in setOf("x86", "x86_64", "arm64ec") || hints.importStatus != PEHelper.ImportStatus.COMPLETE) {
            check(continuation == null) { "The game executable changed during retry. Launch again from the game page." }
            return null
        }
        val legacy = VaporPreparation.packages.first { it.type == ContentType.CONTENT_TYPE_DXVK }
        val modern = VaporPreparation.packages.last { it.type == ContentType.CONTENT_TYPE_DXVK }
        val vkd3d = VaporPreparation.packages.single { it.type == ContentType.CONTENT_TYPE_VKD3D }
        val d3d12 = PEHelper.GraphicsApi.D3D12 in hints.graphicsImports
        val ready = listOf(legacy, modern, vkd3d).associateWith { VaporPreparation.installed(context, contents, it) }
        check(!d3d12 || (ready[modern] != null && ready[vkd3d] != null)) { context.getString(R.string.vapor_d3d12_prepare_required) }
        val driverNames = linkedSetOf(driverConfig["version"].orEmpty().ifBlank { "System" }, "System")
        val drivers = driverNames.mapNotNull { driver -> GraphicsDriverCatalog.probe(context, driver)?.let { driver to it } }
        val routes = drivers.flatMap { (driver, caps) ->
            buildList {
                if (caps.legacyFloor && ready[legacy] != null)
                    add(Route(driver, legacy.entryName.substringAfter('-'), emulateBC = !caps.textureCompressionBC))
                if (caps.modernFloor && ready[modern] != null)
                    add(Route(driver, modern.entryName.substringAfter('-'), emulateBC = !caps.textureCompressionBC))
                if (caps.direct3d12Floor && ready[modern] != null && ready[vkd3d] != null)
                    add(Route(driver, modern.entryName.substringAfter('-'), vkd3d.entryName.substringAfter('-'), emulateBC = !caps.textureCompressionBC))
                // This WineD3D build uses Zink: it still needs a working Vulkan driver.
                if (caps.legacyFloor && hints.graphicsImports.any { it in setOf(PEHelper.GraphicsApi.D3D8, PEHelper.GraphicsApi.D3D9) })
                    add(Route(driver, legacy.entryName.substringAfter('-'), wineD3D = true, emulateBC = !caps.textureCompressionBC))
            }
        }.distinctBy { it.id }
        val inputs = mutableListOf("automatic-graphics-1", Build.FINGERPRINT, shortcut.file.absolutePath,
            shortcut.getSettingExtra("wineVersion", shortcut.container.wineVersion),
            shortcut.getSettingExtra("fexcoreVersion", shortcut.container.getFEXCoreVersion()),
            shortcut.getSettingExtra("fexcorePreset", shortcut.container.getFEXCorePreset()),
            shortcut.getSettingExtra("envVars", shortcut.container.envVars), graphics.toString(),
            SteamUtils.installedBuildId(shortcut.getExtra("app_id").toIntOrNull() ?: 0).toString(),
            relativeExe.orEmpty(), driverConfig.toSortedMap().toString())
        val exe = relativeExe?.let { installRoot?.resolve(it.replace('\\', '/')) }
        if (exe != null && exe.isFile) inputs += fileDigest(exe)
        drivers.forEach { (driver, caps) ->
            inputs += "$driver:${caps.identity}"
            if (driver != "System") {
                val manager = AdrenotoolsManager(context)
                inputs += fileDigest(File(manager.getDriverPath(driver), manager.getLibraryName(driver)))
            }
        }
        // Actual component payloads, not just their editable version labels, invalidate memory.
        VaporPreparation.packages.forEach { spec ->
            val profile = VaporPreparation.installed(context, contents, spec)
            inputs += spec.entryName
            if (profile != null) {
                spec.requiredFiles.filter { !it.endsWith(".tzst") }.forEach {
                    inputs += fileDigest(File(ContentsManager.getInstallDir(context, profile), it))
                }
            } else inputs += "missing"
        }
        val fexVersion = shortcut.getSettingExtra("fexcoreVersion", shortcut.container.getFEXCoreVersion())
        contents.getProfileByEntryName("FEXCore-$fexVersion")?.let { profile ->
            VaporPreparation.packages.single { it.type == ContentType.CONTENT_TYPE_FEXCORE }.requiredFiles.forEach {
                inputs += fileDigest(File(ContentsManager.getInstallDir(context, profile), it))
            }
        }
        inputs += context.assets.open("graphics_driver/wrapper.tzst").use(::streamDigest)
        // A community report for this game only *reorders* the eligible combinations: it can
        // make the reported DXVK generation be tried first, never add one this device cannot run.
        val appId = shortcut.getExtra("app_id").toIntOrNull() ?: 0
        val prior = CommunityPrior.prefersLegacy(context, appId)
        val ordered =
            CommunityPrior.orderByPrior(routes, prior) { route ->
                route.dxvk.startsWith("1.")
            }
        if (prior != null && ordered.size > 1 && ordered.first().id != routes.first().id) {
            Log.i(TAG, "Community prior: trying ${ordered.first().description} first for app $appId")
        }
        return start(ordered, digest(inputs), d3d12, continuation, shortcut.getExtra(CHOICE))
    }

    private fun fileDigest(file: File): String {
        if (!file.isFile) return "missing"
        return file.inputStream().buffered().use(::streamDigest)
    }

    private fun streamDigest(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(values: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
}
