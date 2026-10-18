package com.winlator.cmod.feature.setup

import android.content.Context
import com.winlator.cmod.R
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerCreation
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.compat.gamefixes.GameEngineHints
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentProfile.ContentType
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.wine.PEHelper
import com.winlator.cmod.shared.util.KeyValueSet
import java.io.File
import java.security.MessageDigest

/** The small, fixed starting runtime. The Advanced catalog remains user-selected. */
internal object VaporPreparation {
    data class Package(
        val type: ContentType,
        val entryName: String,
        val url: String,
        val sha256: String,
        val downloadBytes: Long,
        val requiredFiles: List<String>,
    ) {
        val filename get() = url.substringAfterLast('/')
    }

    val packages = listOf(
        Package(
            ContentType.CONTENT_TYPE_PROTON, "Proton-11.0-2-arm64ec-steam-unixlib-3",
            "https://github.com/nicholasx417/WinNative-Components/releases/download/Proton/Proton-11.0-2-arm64ec-steam-unix.wcp",
            "6de8fe5581c04d87b5ae4cef23d96de136090c696e3733201a92b1ce59e5163e", 105027943,
            listOf("bin/wine", "bin/wineserver", "lib/wine/aarch64-unix/ntdll.so", "prefixPack.tzst"),
        ),
        Package(
            ContentType.CONTENT_TYPE_FEXCORE, "FEXCore-2610-14c92681f-unix-0",
            "https://github.com/nicholasx417/WinNative-Components/releases/download/fex-unix-2610-14c92681f/FEXCore-2610-14c92681f-unix.wcp",
            "bc5c601b995acf19a958237d8aff5cb24de7aa8b96241a35b3c0ad9e03ca2178", 1555528,
            listOf("aarch64-unix/libarm64ecfex.so", "aarch64-unix/libwow64fex.so", "system32/libarm64ecfex.dll", "system32/libwow64fex.dll"),
        ),
        Package(
            ContentType.CONTENT_TYPE_DXVK, "DXVK-1.10.3-arm64ec-async-0",
            "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-Arm64ec-Dxvk/Dxvk-1.10.3-arm64ec-async.wcp",
            "520291823b59843aa1af2682db482c3bc657ed8afb34903cb88145125ab54098", 13911832,
            listOf("system32/d3d9.dll", "system32/d3d11.dll", "system32/dxgi.dll", "syswow64/d3d9.dll", "syswow64/d3d11.dll", "syswow64/dxgi.dll"),
        ),
        Package(
            ContentType.CONTENT_TYPE_DXVK, "DXVK-2.7.1-1-gplasync-arm64ec-0",
            "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-Arm64ec-Dxvk/Dxvk-2.7.1-arm64ec-gplasync.wcp",
            "732c12ea3b23b86e0cd73271083b16dcfe8070214cbc4d9bbb6cea1027a02ae1", 3556764,
            listOf("system32/d3d9.dll", "system32/d3d11.dll", "system32/dxgi.dll", "syswow64/d3d9.dll", "syswow64/d3d11.dll", "syswow64/dxgi.dll"),
        ),
        Package(
            ContentType.CONTENT_TYPE_VKD3D, "VKD3D-3.0.1-arm64ec-0",
            "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-arm64ec-VKD3D/Vkd3d-3.0.1-arm64ec.wcp",
            "1754504f81e6a7fccd697231d4a1a49e9c3039da20981e950ece38fda6f4d0cd", 2804432,
            listOf("system32/d3d12.dll", "system32/d3d12core.dll", "syswow64/d3d12.dll", "syswow64/d3d12core.dll"),
        ),
    )

    private val legacyDxvk get() = packages.first { it.type == ContentType.CONTENT_TYPE_DXVK }
    private val direct3d12Dxvk get() = packages.last { it.type == ContentType.CONTENT_TYPE_DXVK }

    /** Per-launch defaults only. Explicit graphics choices and saved settings are retained. */
    @JvmStatic
    fun applyDirect3D12Defaults(
        context: Context,
        contents: ContentsManager,
        shortcut: Shortcut,
        installRoot: File?,
        relativeExecutable: String?,
        graphics: KeyValueSet,
    ): Boolean {
        val container = shortcut.container
        if (shortcut.getSettingExtra("wineVersion", container.wineVersion) != packages.first().entryName ||
            shortcut.getSettingExtra("dxwrapper", container.dxWrapper) != Container.DEFAULT_DXWRAPPER ||
            (!shortcut.usesContainerDefaults() && shortcut.getExtra("dxwrapperConfig").isNotBlank()) ||
            graphics.get("version") != legacyDxvk.entryName.substringAfter('-') ||
            graphics.get("vkd3dVersion") !in listOf("", "None")) return false

        val imports = GameEngineHints.inspect(installRoot, relativeExecutable)
        if (imports.architecture !in setOf("x86", "x86_64", "arm64ec") ||
            imports.importStatus != PEHelper.ImportStatus.COMPLETE ||
            PEHelper.GraphicsApi.D3D12 !in imports.graphicsImports) return false

        val dxvk = installed(context, contents, direct3d12Dxvk)
        val vkd3d = installed(context, contents, packages.single { it.type == ContentType.CONTENT_TYPE_VKD3D })
        check(dxvk != null && vkd3d != null) { context.getString(R.string.vapor_d3d12_prepare_required) }
        // VKD3D-proton shares DXVK's modern DXGI. Never pair it with the legacy baseline.
        graphics.put("version", ContentsManager.getEntryName(dxvk).substringAfter('-'))
        graphics.put("vkd3dVersion", ContentsManager.getEntryName(vkd3d).substringAfter('-'))
        return true
    }

    fun archiveMatches(file: File, spec: Package): Boolean {
        if (!file.isFile || file.length() != spec.downloadBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256
    }

    fun installed(context: Context, manager: ContentsManager, spec: Package): ContentProfile? {
        val profile = manager.getProfileByEntryName(spec.entryName) ?: return null
        if (!profile.isInstalled || profile.type != spec.type) return null
        val root = ContentsManager.getInstallDir(context, profile)
        return profile.takeIf { spec.requiredFiles.all { File(root, it).let { file -> file.isFile && file.length() > 0 } } }
    }

    /** Reuse by runtime identity without reapplying defaults to an existing user's settings. */
    fun ensureContainer(context: Context, manager: ContainerManager, contents: ContentsManager, runtime: ContentProfile): Container? {
        val version = ContentsManager.getEntryName(runtime)
        manager.containers.firstOrNull { !it.isGamescopeRuntime && it.wineVersion == version }?.let { return it }

        val fex = installed(context, contents, packages.single { it.type == ContentType.CONTENT_TYPE_FEXCORE })
            ?: return null
        val dxvk = installed(context, contents, legacyDxvk)
            ?: return null
        if (installed(context, contents, direct3d12Dxvk) == null ||
            installed(context, contents, packages.single { it.type == ContentType.CONTENT_TYPE_VKD3D }) == null) return null
        return ContainerCreation.createContainerForProfile(context, manager, contents, runtime, "Vapor")?.also {
            // The manual factory chooses the newest installed component. Guided setup uses its
            // verified package instead, without changing any existing container or game override.
            it.setFEXCoreVersion(ContentsManager.getEntryName(fex).substringAfter('-'))
            val graphics = KeyValueSet(it.dxWrapperConfig)
            graphics.put("version", ContentsManager.getEntryName(dxvk).substringAfter('-'))
            graphics.put("vkd3dVersion", "None")
            it.setDXWrapperConfig(graphics.toString())
            it.saveData()
        }
    }
}
