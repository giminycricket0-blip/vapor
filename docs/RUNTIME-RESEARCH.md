# Vapor runtime efficiency and translation research

Research date: 16 October 2026. This is a source investigation and a proposed experiment order, not a runtime upgrade or a performance result.

## Recommendation

Keep the native Steam frontend and the Bionic Wine/FEX foundation. The strongest next work is below the UI: update and qualify the translation components, remove unnecessary serialization in graphics and Windows synchronization, and complete the existing Android display path.

The earlier compatibility database, save history and launch diagnostics proposals improve the product. They do not, by themselves, improve translation quality. A distinctive technical contribution would be a reproducible fix to Wine/Android synchronization or Vulkan presentation that makes games smoother at the same power draw, with its correctness demonstrated and its patch available upstream.

Do not start a new emulator, graphics API, container framework or optimization service. Most of the proposed mechanisms already have public implementations. Extend their existing owners, and select original work from an observed bottleneck.

## Scope and evidence

The live checkout is `Vapor`, based on WinNative commit `8409085f706acbc0749881d9bf1e85f2f347dfd4`, with retained Vapor changes. The investigation sampled the current launch, preparation, display, graphics, audio and component-selection code. It also inspected selected files from the cached Proton/DXVK packages and the supplied Ghostrunner log/CSV.

The repository inventory below records 35 pinned public repository snapshots. Review depth varies: implementation and build files for the most relevant paths; documentation, release notes or metadata for comparison projects. This is not an exhaustive audit of every public fork or every line in those repositories. Mesa documentation and MiceWine's public project page were also consulted. GitHub API retrieval of the MiceWine repository failed, so its current implementation is not independently verified here.

Raw research inputs, revision identities and downloaded source excerpts are preserved outside the app checkout in `../runtime-research-20261016/`. Existing user settings can differ from guided preparation defaults. No phone, Steam account, game execution or comparative benchmark was used in this investigation.

## 1. The starting runtime is older than the available translation work

[VaporPreparation.kt](../app/src/main/feature/setup/VaporPreparation.kt) pinned these fresh-install inputs at the start of this investigation:

| Component | Guided preparation selection | Consequence |
| --- | --- | --- |
| Wine/Proton | `Proton-11.0-2-arm64ec-steam-unixlib-3` | Preserve its genuine Steam host integration when comparing builds. |
| CPU translator | `FEXCore-2607-84e127a63-unix-0` | Later FEX improvements are not obtained just by updating the APK. |
| D3D9/10/11 | `DXVK-1.10.3-arm64ec-async-0` | Modern DXVK fixes and pipeline handling deserve a supported-device comparison. |
| D3D12 | No vkd3d package in this fixed set | A `dxvk+vkd3d` setting does not establish an installed, qualified D3D12 implementation. |

The subsequent [implementation plan](ROADMAP.md#runtime-improvement-implementation-plan--16-october-2026) starts with a release-pinned FEX 2610 development candidate. Its [local checks](QUALIFICATION.md#fex-2610-development-candidate--16-october-2026) are separate from this research baseline and from a performance comparison.

These baseline defaults are not proof of the Xiaomi's currently selected DXVK or driver. The supplied Wine log identifies the same Proton package, but does not establish the complete effective graphics configuration.

The inspected DXVK DLL contains the `v1.10.3-arm64ec-async` version string. The cached Proton archive contains ARM64 Android `ntdll.so` and `wineserver`; its file listing has no `winewayland.so`.

**Decision: adopt a newer FEX candidate first, then compare a modern ARM64EC DXVK build separately.** Preserve the current known-working package until the comparison passes. This is use of the existing component selector, not a new runtime manager.

## 2. FEX: improve translated execution before inventing another cache

[FEX 2610](https://fex-emu.com/FEX-2610/) adds instruction support and correctness/performance work, including AVX-VNNI, SSE4.2 string operations and an x87 behavior used by Mono. Its persistent code cache gained a configurable main-file limit, stale-version pruning and an optional RAM buffer. These changes make it a concrete candidate beyond Vapor's 2607 pin; they do not establish a particular FPS gain.

Source inspection of [DiskCache.cpp](https://github.com/FEX-Emu/FEX/blob/5285020de0b30dbdae7b44d0d83541af2d13d002/FEXCore/Source/Interface/Core/DiskCache.cpp) found machine/configuration buckets and guest-code validation. [Windows ImageTracker.cpp](https://github.com/FEX-Emu/FEX/blob/5285020de0b30dbdae7b44d0d83541af2d13d002/Source/Windows/Common/ImageTracker.cpp) supplies PE mapping identity; cache validity is not based on the executable filename alone. `DiskCache` is still off by default in the inspected configuration. The 1 GiB main-file limit is not a guarantee that all cache files together occupy at most 1 GiB.

Use the existing [FEX Unix package build](https://github.com/nicholasx417/WinNative-Components/blob/ce9f4d538c57ffae17ebafa9d6087dc377e0c11e/.github/workflows/FEXCore-unix-nightly.yml), pinned to a release, to produce matching ARM64EC/WOW64 DLLs and Bionic Unix libraries. Compare 2607 against 2610 with caching off; only then compare the same 2610 build with `FEX_DISKCACHE=1`. Require evidence that this Bionic ARM64EC build creates and reuses cache entries; a Linux release announcement alone does not establish that. Measure cold launch, repeated launch, traversal hitches, disk use and memory. Leave the extra RAM cache off initially.

Translation quality also includes memory ordering and floating-point behavior. [FEXCorePresetManager.java](../app/src/main/runtime/compat/fexcore/FEXCorePresetManager.java) distinguishes those settings already. The normal Performance + TSO preset enables scalar TSO but does not enable every conservative option. Turning off ordering or self-modifying-code checks globally is not a sound way to claim an optimization. A reproducible correctness issue should be reduced and compared with the existing Compatibility preset before changing defaults.

The subsequent [local cache experiment](QUALIFICATION.md#fex-disk-cache-experiment--16-october-2026)
confirms disk reuse in both Windows frontends and exposes a concrete pruning bug
in release 2610: WOW64 masks AVX, producing a different machine bucket, and the
other architecture deletes that bucket on startup. The [runtime patch](../tools/fex/README.md)
extends FEX's existing cache owner with separate frontend directories. Rebuilt
DLLs preserve both caches across alternating console runs. This is a measured
reason to patch the translator; it does not justify a second Android cache manager
or establish a game-loading/FPS improvement. Extra RAM caching and ordinary disk
caching remain disabled by default.

### Why Box64 is not an obvious replacement

Vapor's [Container.java](../app/src/main/runtime/container/Container.java) forces FEX for the 64-bit ARM64EC path; it already permits a different translator for 32-bit applications. In the inspected Box64 revision, [the Box64EC target](https://github.com/ptitSeb/box64/blob/e296745e8252520ad17708ccb13a6b6a42182719/wine/box64ec/CMakeLists.txt) builds an interpreter library, and [its execution path](https://github.com/ptitSeb/box64/blob/e296745e8252520ad17708ccb13a6b6a42182719/wine/box64ec/box64ec_runtime.c) calls `Run`. This does not support replacing the FEX JIT with that path as a speed upgrade. Ordinary Box64 dynarec performance and caching cannot be assumed to describe this separate ARM64EC path.

[Hangover](https://github.com/AndreRH/hangover) likewise uses ARM64EC/FEX for 64-bit applications and native Wine boundaries. Vapor is already pursuing that useful architecture. Switching between Bionic/FEX and glibc/Box64 changes more than a C library; a successful community workaround does not isolate which component fixed the game.

## 3. Graphics: three concrete issues to investigate

### Modern DXVK, with the actual required features

[DXVK 3.1.1](https://github.com/doitsujin/dxvk/releases/tag/v3.1.1) is a candidate to build for ARM64EC. Modern DXVK's graphics-pipeline-library support can move compilation earlier, but games that create shaders during gameplay can still hitch. Check the chosen release's required Vulkan features against the effective driver, not only its Vulkan version string. The inspected [requirements profile](https://github.com/doitsujin/dxvk/blob/e8c80a8d1cdaa0ad21516bf64d9cb073cb57eec3/VP_DXVK_requirements.json) is more detailed than “Vulkan 1.3 supported.”

At the research baseline, [DXVKConfigUtils.java](../app/src/main/feature/settings/drivers/DXVKConfigUtils.java) configured only the legacy state-cache path. Modern DXVK uses a different shader-cache interface. A version upgrade must audit that path rather than assuming the old environment variable still controls the new cache.

The subsequent [3.1.1 build experiment](QUALIFICATION.md#dxvk-311-candidate-and-cache-path--16-october-2026)
produces a source-pinned ARM64EC/x86 WCP without extra patches. Both guest paths
load the rebuilt code, but emulator graphics eligibility is insufficient: SwiftShader
is rejected for `dualSrcBlend`, while the Lavapipe path is rejected for
`robustBufferAccess2`. The bundled Leegao wrapper also reports that missing feature
on Lavapipe. These are observed properties of those complete emulator paths, not
claims that every Lavapipe implementation or the Xiaomi has the same limits.
Retain the legacy guided DXVK pin until a physical eligible driver passes rendering.

The app now supplies `DXVK_SHADER_CACHE_PATH` as `Z:\home\xuser\.cache`, through
the existing imagefs mapping. The release uses Win32 file APIs and stores `.dxvk.bin`
and `.dxvk.lut` files; the legacy state-cache variable remains for older components.
Without an explicit modern path, DXVK can use its own default under `LOCALAPPDATA`;
the old Android setting never controlled that newer interface. Windows probes verify
write/read access to the selected folder. Modern shader creation/reuse and its storage
over many games remain unqualified because device initialization stops first. Mesa's
cache-size environment setting is not a global bound on these DXVK files.

[DXVK-Sarek](https://github.com/pythonlover02/DXVK-Sarek) is a useful lower-feature fallback candidate. Its documentation explicitly describes degraded rendering when required behavior is approximated or absent. “Starts on Mali” is not equivalent to correct rendering. Keep it as a focused compatibility option, not the default for every GPU. [vkd3d-proton](https://github.com/HansKristian-Work/vkd3d-proton) is a separate D3D12 path with substantial feature requirements; it does not improve a D3D11 game merely by being installed.

### A global setting overrides Turnip's renderer choice

[Container.java](../app/src/main/runtime/container/Container.java) sets `TU_DEBUG=noconform,sysmem` in the default environment. `sysmem` forces direct system-memory rendering instead of allowing Turnip to choose tiled GMEM rendering where beneficial. [Mesa documents the two modes and its autotuner](https://docs.mesa3d.org/drivers/freedreno.html).

This is a testable efficiency hypothesis: compare the same qualified driver and scene with the inherited forced mode versus its normal autotuner. Do not simply remove it everywhere. [WinNative's driver patches](https://github.com/WinNative-Emu/Drivers/blob/c71f600a2977a141f21a922c9ed2e14fb09b13d3/patches/apply_a8xx_gpus.py) explicitly disable GMEM on A810/A829 as a correctness workaround. The GPU identity and driver patch set must determine whether that experiment is appropriate.

Also distinguish a better driver from higher clocks. The public [performance-variant patch](https://github.com/WinNative-Emu/Drivers/blob/c71f600a2977a141f21a922c9ed2e14fb09b13d3/patches/apply_perf_variant.py) requests KGSL maximum-power constraints. Compare the balanced build first when the goal is sustained performance per watt. A burst FPS gain under a maximum-clock request is not evidence of better translation efficiency.

### Queue synchronization is a serious source-level candidate

The WinNative components repository carries an actual [DXVK binary-semaphore fallback patch](https://github.com/nicholasx417/WinNative-Components/blob/ce9f4d538c57ffae17ebafa9d6087dc377e0c11e/patch/dxvk-binary-semaphores.patch). It changes command submission, sparse/transfer dependencies and completion tracking; it is not just a launcher flag. [DXVK issue 4484](https://github.com/doitsujin/dxvk/issues/4484) records Android/Turnip timeline-synchronization problems, but does not prove the Xiaomi currently has that problem.

**16 October follow-up:** [Mesa MR 44838](https://gitlab.freedesktop.org/mesa/mesa/-/merge_requests/44838)
merged on 8 October and fixes the concrete zero-timeout KGSL poll that could issue
an indefinite timestamp wait, alongside related sync fixes. Prefer this driver-side
fix before altering DXVK queue semantics. The pinned full series at
`a51a418991f883413f10f66cd52398d590a8257b` now builds as an isolated Android
Turnip candidate with the donor GPU/Android patches. The old source fails 8/14
mocked wait cases; the fixed/candidate sources pass 14/14. This is not measured
phone performance. See [the bounded checkpoint](QUALIFICATION.md#turnip-queue-audit-and-driver-candidate--16-october-2026).

The binary fallback applies cleanly to DXVK 3.1.1 but is **not adopted**: source
review found a single fence reused across potentially multiple pending submissions,
unchecked sync allocation and incomplete pool/dependency handling. Passing a patch
application check does not establish correct synchronization. Keep the unmodified
queue behavior and compare prior/fixed KGSL drivers first. A future binary fallback
would need its own correction and synchronization tests. `DXVK_DISABLE_TIMELINE_SEMAPHORES`
is meaningful only in a build that implements it; no such toggle is added here.

**Original-work opportunity:** if a trace demonstrates unnecessary serialization in the current KGSL/wrapper/DXVK combination, reduce it and submit the fix to the responsible upstream. The fallback itself is existing prior art, not a Vapor invention.

## 4. Windows synchronization: borrow the implementation, verify the semantics

[XServerDisplayActivity.java](../app/src/main/runtime/display/XServerDisplayActivity.java) removes `WINEFSYNC`, sets `PROTON_NO_FSYNC=1` and ordinarily enables esync. It preserves explicit ntsync preferences, but a preference does not prove that the selected Wine build implements an Android userspace backend.

Static inspection of the cached Wine package found `/dev/ntsync` and kernel-ntsync-related strings in `wineserver`, alongside esync. The inspected `ntdll.so` contains esync strings. No userspace-fallback marker was found in these sampled strings. This is evidence to check the linked build and runtime backend, not a complete proof of absence.

[ntsync-android](https://github.com/GameNative/ntsync-android) implements Windows synchronization objects with shared memory, atomics and futexes. It documents differences from kernel ntsync, including object lifetime and interrupted-wait behavior. Those differences matter for crashes, suspended threads and process cleanup; its microbenchmark improvements are not game FPS measurements.

The public [GameNative Wine integration](https://github.com/GameNative/proton-wine/blob/d13b1d7efcadeda19341252a4406471a4bc513ca/android/ntsync_android/README.md) links matching code into ntdll and wineserver. It probes a real kernel operation before selecting kernel ntsync, otherwise uses the userspace implementation. This is much stronger prior art than inventing a new futex shim in Vapor. Preserve one shared region for the entire Wine session, including the genuine Steam host.

**Decision: compose the existing implementation with a matched Bionic Wine build as an isolated experiment.** Compare against esync. Test wait-any/wait-all, auto-reset events, abandoned mutexes, process death, thread suspension and clean Steam-host exit. Keep optional spinning off initially. Integrate cleanup through existing session owners; do not add a second synchronization service. This is medium-to-high integration risk because an error can appear as a rare game or exit hang.

**16 October execution result: hold activation.** The inspected heads of the
GameNative and joshuatam libraries remain the same pinned revision. Its original
15 functional tests pass on the API-36 ARM64 emulator under Vapor's app UID, and
an independent `exec` shares the same region inode despite different `TMPDIR`s.
However, the library's assumption that Bionic supplies robust pthread mutex APIs
is wrong for the tested environment: both symbols are absent, consistent with
[Android's status document](https://android.googlesource.com/platform/bionic/+/main/docs/status.md).
The donor replaces them with successful no-ops. A child dying while holding the
actual shared-region mutex leaves a timed locker returning `ETIMEDOUT`, not
`EOWNERDEAD`. Ordinary synchronization success does not establish crash recovery.

The [local source patch](../tools/ntsync/patches/0001-require-recoverable-region-mutex.patch)
refuses initialization before opening/mapping a region when the APIs are missing.
Its focused tests and C-ABI negative control verify explicit `-ENOSYS`, preserved
outputs and no new shared file. This does not implement Android recovery, and no
ntsync component is installed or enabled. The existing Windows probe separately
passes its eight synchronization/lifecycle scenarios on both guest architectures.
See the [qualification gate](QUALIFICATION.md#android-ntsync-recovery-gate--16-october-2026).

The matched donor Wine source handles rejected initialization by selecting fsync
or server-side synchronization; it does not establish automatic esync fallback.
Its matching header, static ntdll/wineserver linkage and server/client dispatch
remain required. A library `.so` or an environment flag alone is not integration.
Continue only after selecting or implementing a genuinely recoverable primitive,
then compare the matched Wine backend with the retained baseline. No game FPS or
power improvement is claimed from this checkpoint.

## 5. Display: the zero-copy code already exists, but preparation cannot use it yet

[WAYLAND-DISPLAY.md](WAYLAND-DISPLAY.md) describes an inherited Wayland compositor and the requirement for a matching Wine Wayland module and Turnip build. The pinned guided Proton archive lacks that module. Copying one from a different Wine build can break private interfaces.

The existing [ahb_swapchain.c](../app/src/main/cpp/waylandcomp/src/ahb_swapchain.c) and [sc_layer.c](../app/src/main/cpp/waylandcomp/src/sc_layer.c) already implement an Android hardware-buffer presentation route. Zero-copy is opt-in. The code exports a GPU completion fence for Android, and falls back to waiting on the CPU when that export fails. Its buffer-release path also has a wait fallback when fence import is unavailable.

**Decision: qualify a coherent Wine/Wayland/Turnip package, then extend this implementation.** Compare the ordinary X11 route, Wayland composition and the supported AHardwareBuffer route. Measure copy counts, fallback counts, time blocked on fences, input-to-display latency and overlay correctness. Test resize, background/return and surface recreation. An AHardwareBuffer path does not guarantee hardware-overlay scanout; Android can still composite it.

**Original-work opportunity:** eliminate a measured avoidable wait or copy at this boundary while maintaining correct acquire/release fencing. Use the existing compositor's scheduling and logging. Merely exposing the existing zero-copy toggle would be integration work, not new rendering technology.

### Prior Art and local fence correction — 16 October 2026

**Extend the existing compositor.** A fresh public-source check finds the same
WinNative `8409085f706acbc0749881d9bf1e85f2f347dfd4` implementation as Vapor's
pre-change files. No new renderer, dependency, display setting or compatibility
service is needed. The current driver exports a completion fence to Android;
the copy path imports a display-release fence into a temporary Vulkan semaphore.
When these interfaces fail, CPU polling must establish actual completion.

The source audit reproduced three related faults: acquire polling ignored its
result; release polling treated any returned event as success; and failed copy
attempts could discard the original display-release fence. A delayed release
also stopped deferring the buffer as soon as its layer callback arrived, even
while its fallback fence was still pending. The focused fix checks `POLLIN` and
rejects error/hangup/invalid-descriptor results, keeps pending releases deferred,
and returns an unconsumed release fence to its pool slot after a failed copy.
Vulkan receives a close-on-exec duplicate, so failed import or submission cannot
consume the caller's only fence. An unrecoverable release-watch failure reports
a Wayland client error rather than releasing an unprotected buffer. A destroyed
client is never dereferenced to report that error.

These contracts follow the primary
[Linux dma-buf documentation](https://docs.kernel.org/driver-api/dma-buf.html),
[Vulkan FD-import ownership rules](https://docs.vulkan.org/refpages/latest/refpages/source/VkImportSemaphoreFdInfoKHR.html)
and [Android SurfaceControl fence contract](https://developer.android.com/ndk/reference/group/native-activity#asurfacetransaction_setbuffer).
The [local checks](QUALIFICATION.md#wayland-fence-correction--16-october-2026)
exercise actual extracted functions with controlled API boundaries, plus four
real pipe/poll checks. They establish the tested failure handling, not successful
Wayland game rendering or a speed improvement.

The ordinary X11 route remains the default. The selected guided Wine package
still lacks **both** `winewayland.so` and its matching Wayland Turnip. Existing
ten-second compositor logs already separate whole-scene copies, zero-copy frames,
layer copies, Vulkan fence waits and buffer-release latency. They do not yet
separately measure every CPU `poll()` fallback. Before changing scheduling, trace
those fallback counts/times on a coherent Wine/Wayland/driver package and test
presentation/retirement of the same buffer, effects/overlay transitions, resize,
background/return and surface recreation. The retained 100 ms fallback and
compositor GPU-completion waits are not removed by this patch; they are remaining
measurement targets. Android API 36 also offers per-buffer release callbacks;
adopting a second API path without a demonstrated callback failure is deferred.

## 6. Audio and video: maintain a coherent native build

Vapor already bundles DirectAudio 1.3.2, while the general container default is PulseAudio. [DirectAudioDriver.kt](../app/src/main/runtime/audio/directaudio/DirectAudioDriver.kt) selects between two private Wine audio interfaces. [DirectAudio 1.4.0](https://github.com/The412Banner/directaudio/releases/tag/directaudio-v1.4.0) adds a third system-thread interface needed by newer Wine variants; its release explicitly says the existing driver's latency behavior is unchanged.

First compare the already available matching DirectAudio driver against PulseAudio, measuring underruns, CPU and latency. If adopting a newer Wine/CachyOS-derived build, update the interface detection and package together. Do not claim an audio speedup from the version number. FAudio and DirectAudio solve different layers: XAudio behavior versus output to Android.

### Prior Art and local audio corrections — 16 October 2026

**Keep the known runtime bundles; extend their existing selector and staging.**
The upstream `directaudio-v1.4.0` release resolves to
`8cc1227bf1b929521e6b2df2509d8b309c4c4f25`. Its classic and system-thread Wine
11 unixlibs both export a **296-byte / 37-call** table. The system-thread build
also imports `PsCreateSystemThread`; the classic build does not. This is a real
same-count interface collision, not a version-name heuristic. The selected Wine
11.0-2's actual `winealsa.so` and `winepulse.so` have the classic signature.

Vapor previously accepted the system-thread host based on count alone. The
existing `DirectAudioDriver` now compares both facts and refuses a host for which
no bundled driver matches, allowing the existing PulseAudio fallback. It also
checks PE contents before skipping a same-size prefix copy. Real-archive tests
reproduce both problems, including a switch after an earlier successful install.
The v1.4.0 archive is a **test-only fixture**, excluded from the APK. The four
v1.3.2 runtime archives, bridge, audio presets and default backend remain fixed.
No additional audio engine, download, service or selector is added.

The existing native DirectAudio/fakeinput test also exposed an actual process-exit
failure: the preloaded `writev` hook used a destroyed controller mutex while libc
flushed streams after C++ finalization. With the same shared-C++ test binary, the
original library exits with SIGSEGV; the NDK/Clang build retaining its four shared
state holders through process death passes. Ordinary `close()` still removes
per-controller data. Static initialization is preserved; no extra initialization
path or runtime allocation is introduced. The optional GCC-built Linux preload
does not implement Clang's `no_destroy` attribute and retains its separate gate.
This Android result is not a Linux exit qualification or an explanation of the
owner's Steam Cloud timeout.

The new account-free Windows probe tests two repeated PCM16/44.1 kHz and
float32/48 kHz output cycles, sample progress, pause/restart, reset and handle
cleanup. Both guest architectures pass on each configured backend, confirmed by
the guest registry and loaded driver modules. These are functionality checks,
not acoustic latency, fidelity or underrun measurements. See the
[audio checkpoint](QUALIFICATION.md#audio-interface-and-exit-corrections--16-october-2026)
for negative controls, exact build identity and remaining device checks.

**Media remains separate.** The selected `winegstreamer.so` declares dependencies
on the GStreamer audio/video/app/GL stack and GLib/GObject/GIO. That inventory
does not establish working codec discovery or a decoded cutscene. No reported
video failure was reproduced in this checkpoint, so no global Media Foundation,
GStreamer or XAudio override is added. Route changes, real sound and cutscenes
remain physical-session checks; there is no universal 21 ms Android latency floor
to infer from another project's single-device measurements.

The launch path already extracts FFmpeg 8 and exposes GStreamer plugins. [GuestProgramLauncherComponent.java](../app/src/main/runtime/display/environment/components/GuestProgramLauncherComponent.java) disables Proton's media-converter paths. That does **not** mean every video decoder is disabled or that copying `mfplat.dll` is the fix. Compare actual failing cutscene formats, Wine media behavior and the matching native plugin dependencies. No media failure was established by the Ghostrunner initial-loading screenshot.

## 7. What to reuse from the other projects

| Project family | Useful material | Boundary |
| --- | --- | --- |
| WinNative and component/driver repositories | Closest Bionic build recipes, Steam host, FEX Unix libraries, graphics patches | Extend the inherited owners and retain attribution. Current upstream WinNative HEAD matched the donor commit during this check. |
| DroidDeck / DroidDeck-Proton | Pinned FEX/Wine/DXVK combinations, GE/CachyOS-derived game fixes, Linux execution investigations | Its Linux/glibc packages are not drop-in Bionic libraries. The inspected Proton pins include FEX 2610; port specific source fixes with their dependencies. |
| GameNative's public Wine and ntsync repositories | Android Wine build and shared-memory synchronization integration | Public components can be evaluated individually. The application's proprietary helpers do not become reusable because neighboring repositories are public. |
| Bannerlator / DirectAudio / Nightlies | Wine/Wayland presentation and matching audio/build integration | Much of this display/audio approach is already inherited. Inspect the delta before duplicating it. |
| Valve Wine/Proton, GE-Proton, CachyOS | Windows API fixes and game-specific patches | Linux release archives assume a different host environment. Carry a small justified patch set in a reproducible Bionic build. |
| Box64 / Hangover | Alternate translation path and native Wine boundary work | Different execution modes have different maturity and performance. Do not equate Box64EC with ordinary Box64 dynarec. |
| Turnip / Adrenotools / Vulkan wrapper | Driver loading, GPU correctness and missing-format adaptation | Feature availability and correctness must be checked on the actual effective driver. |
| Winlator / Mobox / Box64Droid / MiceWine | Integration choices and reproduction leads | Launcher comparisons are not controlled component benchmarks. MiceWine was a documentation-only review here. |
| Armada | ARM Linux runtime combinations and device integration | It owns an operating system/kernel; an unrooted Android app does not have the same driver or power controls. |

For non-Adreno expansion, the wrapper's [ETC2 experiment](https://github.com/leegao/bionic-vulkan-wrapper/releases/tag/ETC2-Milestone-2) is interesting: it trades texture fidelity for lower memory use. This is relevant where BC textures otherwise expand, not an automatic improvement on hardware that supports them. Evaluate image quality alongside memory and speed. Keep Snapdragon qualification first.

## 8. Concrete experiment order

These experiments support the existing roadmap. They do not replace the open download, Cloud, controller or Android-platform gates.

| Order | One change to compare | Existing owner | Pass evidence |
| --- | --- | --- | --- |
| 1 | FEX 2607 → release-pinned 2610, cache off | Component packaging + FEX launcher | Both guest architectures, genuine host, matching game behavior and no material timing regression. |
| 2 | Same FEX, persistent cache off → on | FEX's own cache/configuration | Repeated-run improvement where JIT work is costly, bounded observed storage, no stale-code failures after update. |
| 3 | Legacy → supported modern ARM64EC DXVK | Component packaging + existing graphics selection | Required features, correct images, shader/queue trace and comparable frame times. |
| 4 | Same qualified Turnip, forced sysmem → normal mode where supported | Existing environment/driver selection | Better sustained energy or frame timing without visual regressions. Preserve chip-specific GMEM workarounds. |
| 5 | Same DXVK, prior → fixed KGSL driver polling | Existing imported-driver path; merged Mesa sync fix | Reduced measured queue stalls without synchronization failures. Binary fallback is held after its source audit. |
| 6 | esync → matched Android userspace ntsync | Wine ntdll/wineserver + existing session cleanup | Synchronization correctness, lower relevant wait cost, repeated launch/exit and Cloud handoff intact. |
| 7 | Matched X11 → Wayland/AHB; audio separately | Existing display/audio owners | Lower presentation/audio overhead with input, overlays, sound and surface recovery intact. |

Start with the user's existing Helltaker, TF2 training and Ghostrunner Demo; EU3 remains the older-game regression check and needs at least 768 pixels of vertical resolution. Use the same scene and settings within each comparison. A fixed cap helps energy comparisons; a separate uncapped run can reveal throughput. Keep frame generation off when measuring rendered frames. Report warm and cold behavior separately.

The supplied Ghostrunner CSV has 347 samples spanning approximately 346 seconds. It mixes startup and gameplay, lacks GPU-busy samples and records app-self CPU rather than a complete Wine/FEX process total. It supports investigation but cannot decide between shader, JIT, wait, thermal or presentation bottlenecks. Its temperature source also needs validation. Do not derive whole-session 1% lows by averaging per-window counters.

## 9. What would count as a real Vapor contribution

The first original-work target should be **correct Android graphics synchronization and presentation under a translated game workload**. There is already concrete code to investigate: DXVK queue waits, KGSL driver interaction, and AHardwareBuffer fence import/export fallbacks. A useful result would be a small upstreamable patch that removes a reproduced stall, passes synchronization/visual checks and improves sustained frame delivery at matched power settings.

A second candidate is a reduced FEX instruction or memory-ordering failure from a real game, fixed with a translator regression test. That improves translation quality for everyone. It requires CPU/compiler expertise and a reproducible case; an engine-name guess does not justify changing memory semantics.

A third, later candidate is better quality/performance in BC texture adaptation for unsupported GPUs, measured against reference images. The wrapper already supplies decoding/transcoding mechanisms; original work needs to improve an identified algorithm, format case or synchronization cost.

There is no evidence here to promise novelty across the entire scene, a fixed efficiency percentage, or compatibility with most of Steam. The contribution becomes defensible when it improves a demonstrated workload and survives correctness and sustained-device testing. The best immediate engineering choice is to reuse the newer public components, establish which remaining cost dominates, and contribute at that specific boundary.

## Repository inventory

The following snapshot inventory records review coverage; metadata-only entries are not source audits. Source URLs above identify the files supporting the concrete recommendations. Full revisions and cached excerpts are in the research directory.

| Repository | Snapshot | Review scope |
| --- | --- | --- |
| [AndreRH/hangover](https://github.com/AndreRH/hangover) | [`56e385b7ff49`](https://github.com/AndreRH/hangover/tree/56e385b7ff490edd281b288f420677093563560b) | Project/build documentation |
| [CachyOS/proton-cachyos](https://github.com/CachyOS/proton-cachyos) | [`c75a016add0e`](https://github.com/CachyOS/proton-cachyos/tree/c75a016add0e855ff571943c86e2830f13e1b59d) | Project/build documentation |
| [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck) | [`21a78a1eb2e4`](https://github.com/Droid-Deck/DroidDeck/tree/21a78a1eb2e4ccb56c798b7bc2ec9149274ec7a0) | Selected synchronization/pack code and project docs |
| [Droid-Deck/DroidDeck-Components](https://github.com/Droid-Deck/DroidDeck-Components) | [`a9ce53ffb304`](https://github.com/Droid-Deck/DroidDeck-Components/tree/a9ce53ffb3042d8d360d6e7854034c5d69bef32c) | Project/build documentation |
| [Droid-Deck/DroidDeck-Proton](https://github.com/Droid-Deck/DroidDeck-Proton) | [`bc343c2557bf`](https://github.com/Droid-Deck/DroidDeck-Proton/tree/bc343c2557bf75df18bda4173c97d5afcf3f7f26) | Build pins, submodules, FEX config and docs |
| [FEX-Emu/FEX](https://github.com/FEX-Emu/FEX) | [`5285020de0b3`](https://github.com/FEX-Emu/FEX/tree/5285020de0b30dbdae7b44d0d83541af2d13d002) | Cache and Windows integration source; configuration and release notes |
| [FNA-XNA/FAudio](https://github.com/FNA-XNA/FAudio) | [`6839b88e304a`](https://github.com/FNA-XNA/FAudio/tree/6839b88e304a046ae1a609ff14a085371e02a4e0) | Project/build documentation |
| [GameNative/ntsync-android](https://github.com/GameNative/ntsync-android) | [`7ce6435e5979`](https://github.com/GameNative/ntsync-android/tree/7ce6435e5979b1cb5341aa4b299f31e8937fe121) | Shared-memory/futex implementation and integration contract |
| [GameNative/proton-wine](https://github.com/GameNative/proton-wine) | [`d13b1d7efcad`](https://github.com/GameNative/proton-wine/tree/d13b1d7efcadeda19341252a4406471a4bc513ca) | Android synchronization patches and build integration |
| [GloriousEggroll/proton-ge-custom](https://github.com/GloriousEggroll/proton-ge-custom) | [`01bfbf67b354`](https://github.com/GloriousEggroll/proton-ge-custom/tree/01bfbf67b35418fdd705f2be09e0bfb43eece24b) | Project/build documentation |
| [HansKristian-Work/vkd3d-proton](https://github.com/HansKristian-Work/vkd3d-proton) | [`0bb64c56c8f0`](https://github.com/HansKristian-Work/vkd3d-proton/tree/0bb64c56c8f0dcc11ecc0f6c56e00e4f7bd8d506) | Project/build documentation |
| [Ilya114/box64droid](https://github.com/Ilya114/box64droid) | [`ecdbaec1e5b1`](https://github.com/Ilya114/box64droid/tree/ecdbaec1e5b1b40ee08382a975bfd1afaed4c5c3) | Project/build documentation |
| [K11MCH1/AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers) | [`3945daf08904`](https://github.com/K11MCH1/AdrenoToolsDrivers/tree/3945daf08904f8799dc31f5f120430835fcdbc52) | Repository/release inventory; not a full driver patch audit |
| [SilentBob347/armada-os](https://github.com/SilentBob347/armada-os) | [`0116caddb2d9`](https://github.com/SilentBob347/armada-os/tree/0116caddb2d9de0e9723c86b62f82268af92a6b4) | OS architecture/build documentation |
| [The412Banner/Bannerlator](https://github.com/The412Banner/Bannerlator) | [`8f02f24aa41d`](https://github.com/The412Banner/Bannerlator/tree/8f02f24aa41d7fe349aeac3e4e899a75767f343d) | Project docs; inherited display code inspected in Vapor |
| [The412Banner/Nightlies](https://github.com/The412Banner/Nightlies) | [`14a305549bce`](https://github.com/The412Banner/Nightlies/tree/14a305549bce7c932ebafb5604a719a345c3a4d7) | FEX build workflow |
| [The412Banner/directaudio](https://github.com/The412Banner/directaudio) | [`52aedbf8ddd4`](https://github.com/The412Banner/directaudio/tree/52aedbf8ddd42874a5bbd21df20e1046367f442e) | Driver source excerpts, integration docs and release interfaces |
| [The412Banner/proton-wine](https://github.com/The412Banner/proton-wine) | [`256288853b20`](https://github.com/The412Banner/proton-wine/tree/256288853b206373cbf65b775143d33bb11035dd) | Repository/release identity and downstream build comparison |
| [ValveSoftware/Proton](https://github.com/ValveSoftware/Proton) | [`bc896217ffcd`](https://github.com/ValveSoftware/Proton/tree/bc896217ffcd67f892f952b010f898fe7bd0f0a1) | Repository/release identity and downstream pin comparison |
| [ValveSoftware/wine](https://github.com/ValveSoftware/wine) | [`e60892cc2398`](https://github.com/ValveSoftware/wine/tree/e60892cc2398b0a83c332a2078c7b85e47bdb42b) | Repository/release identity and downstream pin comparison |
| [WinNative-Emu/Drivers](https://github.com/WinNative-Emu/Drivers) | [`c71f600a2977`](https://github.com/WinNative-Emu/Drivers/tree/c71f600a2977a141f21a922c9ed2e14fb09b13d3) | GPU workaround and tuning patch scripts; build and release docs |
| [WinNative-Emu/WinNative](https://github.com/WinNative-Emu/WinNative) | [`8409085f706a`](https://github.com/WinNative-Emu/WinNative/tree/8409085f706acbc0749881d9bf1e85f2f347dfd4) | Selected live donor/Vapor launch, graphics, display and audio code |
| [WinNative-Emu/directaudio](https://github.com/WinNative-Emu/directaudio) | [`2461bf545c10`](https://github.com/WinNative-Emu/directaudio/tree/2461bf545c10b08c015d37cb28a116d069901b75) | Repository identity; bundled provenance inspected in Vapor |
| [brunodev85/winlator](https://github.com/brunodev85/winlator) | [`b6b2259158cf`](https://github.com/brunodev85/winlator/tree/b6b2259158cf38d06067c34430d840d56b46d220) | Project/build documentation |
| [bylaws/libadrenotools](https://github.com/bylaws/libadrenotools) | [`8fae8ce254df`](https://github.com/bylaws/libadrenotools/tree/8fae8ce254dfc1344527e05301e43f37dea2df80) | Project/build documentation |
| [bylaws/wine](https://github.com/bylaws/wine) | [`b2a099b3ceec`](https://github.com/bylaws/wine/tree/b2a099b3ceec6fef05c455408c05714232cb466f) | Repository identity only; requested README was unavailable |
| [doitsujin/dxvk](https://github.com/doitsujin/dxvk) | [`e8c80a8d1cda`](https://github.com/doitsujin/dxvk/tree/e8c80a8d1cdaa0ad21516bf64d9cb073cb57eec3) | Adapter/features, requirements profile, configuration and release docs |
| [joshuatam/ntsync-android](https://github.com/joshuatam/ntsync-android) | [`7ce6435e5979`](https://github.com/joshuatam/ntsync-android/tree/7ce6435e5979b1cb5341aa4b299f31e8937fe121) | Same revision as inspected GameNative fork; no duplicate independent audit |
| [leegao/bionic-vulkan-wrapper](https://github.com/leegao/bionic-vulkan-wrapper) | [`c8baafbd4f48`](https://github.com/leegao/bionic-vulkan-wrapper/tree/c8baafbd4f4835ca103acb55ec3ac13642b6b7e3) | Device/image/feature adaptation and shader source; releases |
| [leegao/dxvk](https://github.com/leegao/dxvk) | [`60978eb94c9a`](https://github.com/leegao/dxvk/tree/60978eb94c9ae51609b155f4f7e4c7f6f2fb6105) | Selected device source and documentation; related queue patch inspected separately |
| [nicholasx417/WinNative-Components](https://github.com/nicholasx417/WinNative-Components) | [`ce9f4d538c57`](https://github.com/nicholasx417/WinNative-Components/tree/ce9f4d538c57ffae17ebafa9d6087dc377e0c11e) | FEX/DXVK build recipes, package metadata and queue patch |
| [olegos2/mobox](https://github.com/olegos2/mobox) | [`24e5611620ef`](https://github.com/olegos2/mobox/tree/24e5611620efbb133e42d4b26191bf2ef7b21768) | Project/build documentation |
| [ptitSeb/box64](https://github.com/ptitSeb/box64) | [`e296745e8252`](https://github.com/ptitSeb/box64/tree/e296745e8252520ad17708ccb13a6b6a42182719) | ARM64EC build and execution source; general project docs |
| [pythonlover02/DXVK-Sarek](https://github.com/pythonlover02/DXVK-Sarek) | [`0c0e8f6f62c7`](https://github.com/pythonlover02/DXVK-Sarek/tree/0c0e8f6f62c7cb37dbf5d38413517efa00c08fef) | Project/build documentation |
| [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative) | [`e9d7f9ae60af`](https://github.com/utkarshdalal/GameNative/tree/e9d7f9ae60afd3b9c68651c7347a27683c6452c4) | Repository identity and public component notices |
