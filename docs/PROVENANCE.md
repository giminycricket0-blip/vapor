# Component provenance and open gates

## Source foundation

WinNative source commit: `8409085f706acbc0749881d9bf1e85f2f347dfd4` from https://github.com/WinNative-Emu/WinNative. The local branch descends from that commit; history was retained. `origin` names https://github.com/giminycricket0-blip/vapor. The public default branch is `Vapor`; it retains the donor history. Vapor changes are published to its own origin.

| Source input | Pin |
| --- | --- |
| libadrenotools | `8483dfdaa2abf97ee89ad0e5f337e7b508550c6b` |
| linkernsbypass | `b10d48548d608dfca38ffc449d0350335b2f3737` |
| vkBasalt | `68843cad7f8b4db6ff9806b353776f6974f95e67` |
| zstd CMake input | v1.5.6, resolved to `794ea1b0afca0f020f4e57b6732332231fb23c70` |
| xz CMake input | v5.4.6, resolved to `6e8732c5a317a349986a4078718f1d95b67072c5` |
| LibretroDroid AAR | `build-20260802-79e724b`; SHA-256 `9b91b766fc8920b2bfb639c58d1057ce9fbab34252ea16fdee87cf5aea42a12d` |
| Rust dependencies | Existing Cargo.lock; native build now uses `--locked` |
| Rust toolchain | 1.99.0 in rust-toolchain.toml |
| Windows Steam host and probes | Rebuilt from this tree with LLVM-MinGW 20261006; see BUILDING.md |

## Steam host exit-request ownership — 16 October 2026

Vapor changes the inherited `wn-steam-launcher/clean_shutdown.cpp` directly:
duplicate sentinel requests no longer terminate a different thread's cleanup.
The existing public host entry points, Steam client DLLs and Cloud policy remain.
Both `steam.exe` and `steam32.exe` are rebuilt with the existing LLVM-MinGW
20261006 script/toolchain. Their sizes/digests and the new source digest are in
[runtime-inputs.json](runtime-inputs.json); all 77 binary entries are checked.
The interface-probe source is unchanged. Its rebuild only updates the COFF and
debug-directory timestamps, so the original probe bytes are retained.

The new `tests/shutdown_overlap_test.cpp` is an account-free regression fixture
linked to the actual shutdown source; it supplies no Steam protocol, ownership,
authentication or Cloud substitute. Original/corrected x86/x64 fixtures use
LLVM-MinGW 20250920/Clang 21.1.0, run through stock FEX 2607/Wine 11.0-2, and are
excluded from the APK. The APK differs from the audio build only in its two
Steam-host assets. See [qualification](QUALIFICATION.md#overlapping-steam-host-exit-requests--16-october-2026).

## Audio interface and native exit corrections — 16 October 2026

The existing DirectAudio selector/stager and preloaded controller library remain
their owning paths. Vapor changes the known private-interface comparison,
same-size prefix staging and Android native shutdown lifetime. No audio library,
service, runtime component or proprietary helper is added. Runtime DirectAudio
archives remain v1.3.2, with their original LGPL notices and digests recorded in
[the packaged provenance note](../app/src/main/assets/directaudio/PROVENANCE.md).

A test-only upstream fixture is added at
`app/src/test/resources/directaudio/directaudio-wine11-systhread-arm64ec-sdk28.zip`:
**23,261 bytes**, SHA-256
`30382cadbabc7e5d926db08166140f16e7e74eddbd2eb6d2baab0a8fae56099b`.
It is the unmodified [v1.4.0 release artifact](https://github.com/The412Banner/directaudio/releases/tag/directaudio-v1.4.0),
source `8cc1227bf1b929521e6b2df2509d8b309c4c4f25`, and is not packaged in the APK.
Its release build sources/recipe and the retained LGPL `COPYING`, `NOTICE` and
`AUTHORS` identify its provenance. The local evidence archive includes the tagged
source archive and inspected source/license/build files. Its purpose is to
reproduce the 37-call collision; it is not an enabled system-thread driver.

The APK's corrected `libfakeinput.so` is built from this repository and has
SHA-256 `47397cb647e91021817f9a0c0e3dcd6744628c15f8ff4716c767a30e30fa203b`.
The native negative control retains the original library and runs the same test
binary against both. NDK/Clang honors `no_destroy` for its four process-lifetime
holders. The optional Linux recipe uses GCC with ignored attributes and has not
received an equivalent exit qualification; source compatibility is retained,
but this Android result must not be promoted to that separate runtime.

See the [qualification record](QUALIFICATION.md#audio-interface-and-exit-corrections--16-october-2026)
for the APK identity, four Windows playback runs, native/JVM/UI cases and pending
physical audio/media gates. No latency or battery advantage is inferred from a
library version, another project's settings or file sizes.

## Wayland compositor fence correction — 16 October 2026

The compositor remains the inherited Bannerlator/WinNative implementation
credited in [CREDITS.md](../CREDITS.md). A fresh public source check resolves to
WinNative `8409085f706acbc0749881d9bf1e85f2f347dfd4` and matches all four original
files (`ahb_swapchain.c`, `sc_layer.c`, `vk_present.c`, `vk_present.h`). Vapor's
local change corrects fence readiness and release-fence ownership in those files;
no external code or dependency is imported. Existing source licenses and notices
remain in force.

The [qualification record](QUALIFICATION.md#wayland-fence-correction--16-october-2026)
contains the development APK identity and test boundaries. Its 222 compared
asset/native entries match the earlier APK except for the source-built
`lib/arm64-v8a/libwnwayland.so` (SHA-256
`ab4128f5d805723beb73d10a28026282970a655e2ec7f02c7e861f541f039704`).
No new Wine module, Wayland Turnip or ntsync component is packaged. The evidence
archive retains the original four files, changed source, regression fixture,
build/test logs and relevant licenses; the full dirty-tree source manifest
identifies the application snapshot. This is a local source/build checkpoint,
not a matched Wayland runtime or public-release qualification.

## Native network source build — 16 October 2026

[NativeNetwork.cmake](../app/src/main/cpp/NativeNetwork.cmake) replaces the inherited
OpenSSL/curl Prefab AAR payloads with source builds of the same versions. No new
runtime service or networking library is introduced. The existing native content-I/O
caller remains the owner; Rust `wn-steam-client` is separate.

| Source archive | SHA-256 | Notice included in the APK |
| --- | --- | --- |
| [OpenSSL 1.1.1q](https://github.com/openssl/openssl/releases/download/OpenSSL_1_1_1q/openssl-1.1.1q.tar.gz) | `d7939ce614029cdff0b6c20f0e2e5703158a489a72b2507b8bd51bf8c8fd10ca` | [OpenSSL / SSLeay license](../app/src/main/assets/licenses/OpenSSL-1.1.1q.txt) |
| [curl 7.85.0](https://curl.se/download/curl-7.85.0.tar.gz) | `78a06f918bd5fde3c4573ef4f9806f56372b32ec1829c9ec474799eeee641c27` | [curl license](../app/src/main/assets/licenses/curl-7.85.0.txt) |

The OpenSSL checksum was also matched to the upstream release's `.sha256` asset.
CMake checks both archive hashes before compiling. Source files are unmodified;
the recipe uses the Android NDK compiler, shared libraries, explicit 16 KB maximum
and common page-size linker flags, and Android-compatible unversioned SONAMEs.
The latter configure/make conventions were checked against AOSP's
[OpenSSL](https://android.googlesource.com/platform/tools/ndkports/+/2e149f73a8e792fff6dc6aadc18d0d919a071282/openssl/build.gradle.kts)
and [curl](https://android.googlesource.com/platform/tools/ndkports/+/2e149f73a8e792fff6dc6aadc18d0d919a071282/curl/build.gradle.kts)
recipes. This is an alignment rebuild, not an OpenSSL/curl version or security update.

## Bundled Leegao wrapper identity — 16 October 2026

The `usr/lib/libvulkan_wrapper.so` inside `graphics_driver/wrapper-leegao.tzst`
is **8,709,528 bytes**, SHA-256
`520ec0ba1b5a404b12282d18f863761a44703012be6e2421b0f5adcb9d47be11`.
It is byte-for-byte identical to the official [v0.0.5r5 release asset](https://github.com/leegao/bionic-vulkan-wrapper/releases/tag/v0.0.5r5),
including the release API's published digest. The release tag was resolved again
on 21 October to `c8baafbd4f4835ca103acb55ec3ac13642b6b7e3`.
The other files in the bundled archive
are separate inputs; this match does not prove their provenance or a reproducible
wrapper build from source. No wrapper binary or device-specific flags changed.

Vapor's default selection is `wrapper`, which extracts `wrapper.tzst`; the optional
`wrapper-leegao` and `wrapper-gamenative` selections extract their own archives.
Their `usr/lib/libvulkan_wrapper.so` payloads are distinct:

| Variant | Bytes | SHA-256 |
| --- | ---: | --- |
| Default | 19,146,576 | `ff43589bd34c236a048f93f0beb0af0ff0cbb5c8a0ecfe4227bacd5db7d2ba7a` |
| Leegao | 8,709,528 | `520ec0ba1b5a404b12282d18f863761a44703012be6e2421b0f5adcb9d47be11` |
| GameNative | 2,705,856 | `25e8010444dca69a448fa8bbd145b650e8283e329228fc7b7d1b70b79e63d1f3` |

The later source experiment maps the Leegao release's reviewed image and generator
files to the current pinned source. The default and GameNative variants were
source-identified on 22 October from public artifacts (see below); file sizes
describe payload storage, not resident RAM or speed.

A 21 October static check (no execution and no repackaging) confirms all three
hashes above and separates the build lines. The default and GameNative payloads
are not builds of the v0.0.5r5 source: both use NDK r29/clang 21, 16 KB `LOAD`
alignment and an ICD-only 15-export set, the default payload retains `debug_info`
and a Termux build directory, and compile units they name (`graphicsenv_hook.cpp`,
`spirv_patcher.cpp`, `wrapper_bcdec.c`) do not exist in the v0.0.5r5 tag, the
reviewed `3abf4870` tree or the 16 upstream branches examined. A follow-up public
source hunt identified both:
- **GameNative** (2,705,856 B) is byte-identical to the `libvulkan_wrapper.so`
  member of the public `wrapper-gamenative-20260719` release artifact, whose
  workflow builds the public **GameNative/mesa** fork (branch `wrapper-25`, NDK
  r29; commit `5212f63a`, merged `8e64d5f4` two days before the artifact, carries
  every previously-unidentified compile unit and the `WRAPPER_DIAG` code seen in
  the payload).
- **Default** (19,146,576 B) is a Termux debug build of the public
  **Pipetto-crypto/mesa** fork line (branch `wrapper-25`, at or after commit
  `ac54448c`): its fork-unique texture-cache strings and the
  `WRAPPER_SURFACE_FORMAT` literal match, while GameNative-only literals and
  `WRAPPER_DIAG` are absent. The exact packaged build commit is not embedded.
Neither variant is the reviewed v0.0.5r5 tree; they are two different public forks.
The exact GameNative build ref and the default build record remain open. The Leegao
release was packaged by the retained
`leegao/vulkan_wrapper_termux-packages` repository at `c5c45d88` (Termux Docker
build, NDK r27c, API 26). A build of the tag source reproduces that payload's
SONAME, 20 `NEEDED` names, export family and 4 KB `LOAD` alignment, but not its
bytes, because NDK r27c, the release sysroot/Docker image and the release
SPIRV-Tools revision are not retained.
See [the reproduction receipt](evidence/2026-10-21-wrapper-release-repro.json).

## Open-source reuse decision — 14 October 2026

Vapor remains a GPL-3.0 WinNative derivative. Retain the integration, source history,
license notices and attribution; an independent replacement is not the accepted scope.
The native Steam account/download service and genuine-client host remain the existing
owners. No JavaSteam dependency or GameNative proprietary helper was added. A JavaSteam
reference checkout used during research is outside the app and is not a build input.

The narrow close-upload reservation adaptation is credited in CREDITS.md with its exact
upstream merge. Public Kotlin code is reviewed separately from GameNative's proprietary
execution/bootstrap/KGSL components. Component licences still apply individually; for
example DirectAudio's own driver is LGPL-2.1-or-later. Source visibility alone does not
establish redistribution rights for an archive or generated compatibility dataset.

Keep corresponding source and required notices available for the binaries distributed.
An open GitHub repository is the chosen publication approach; it is not a substitute for
identifying the actual source and build inputs of each distributed component. The gates
below remain open even with a GPL application.

## Steam account and catalog additions — 14 October 2026

The free-license implementation extends the existing Rust CM client and its job router. Protocol
fields and message IDs were checked against [SteamKit's request method](https://github.com/SteamRE/SteamKit/blob/master/SteamKit2/SteamKit2/Steam/Handlers/SteamApps/SteamApps.cs),
[Steam message IDs](https://github.com/SteamRE/SteamKit/blob/master/Resources/SteamLanguage/emsg.steamd)
and the [SteamDatabase protobuf definitions](https://github.com/SteamDatabase/Protobufs/blob/master/steam/steammessages_clientserver_2.proto).
Catalog demo relationships follow the [StoreBrowse schema](https://github.com/SteamDatabase/Protobufs/blob/master/steam/steammessages_storebrowse.steamclient.proto).
These are protocol research inputs, not new packaged SteamKit/JavaSteam dependencies.

The web panel reuses the existing CM access-token request that chat image uploads already used.
Exact HTTPS origins are checked before embedded navigation, consistent with [Android's URI-loading guidance](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading).
This is an unofficial integration with changeable Steam interfaces. Passing parser, navigation and
cookie-format tests does not prove a live account can acquire a title or authenticate a web page.

## Binary identity

[runtime-inputs.json](runtime-inputs.json) records 77 checked-in binary/archive inputs, their sizes and SHA-256 hashes. These include imagefs, Wine, Steam client archives, driver bundles, native libraries and vendored Android libraries. The hash record includes source hashes for the rebuilt Steam host. Gradle also builds Android native code from source and fetches the checksum-pinned LibretroDroid AAR.

This is an identity inventory, not a completed source or rights audit. An archive hash does not identify every source revision inside it. Runtime components downloaded later through the inherited component catalog must be pinned and reviewed before game qualification. The current catalog can change independently of Vapor.

## Guided preparation downloads

`VaporPreparation.kt` owns the fixed initial download set. These are downloaded on the user's Prepare action, not newly bundled into the APK. Each URL is pinned to the byte size and SHA-256 below, independently checked against the release asset digest and a local download: Proton/DXVK on 14 October 2026, the FEX candidate on 16 October 2026, and the modern DXVK/VKD3D pair on 18 October 2026. The inherited manual component catalog remains optional under Advanced and may offer different versions.

| Component / exact installed entry | Original upstream release asset | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `Proton-11.0-2-arm64ec-steam-unixlib-3` | [Proton-11.0-2-arm64ec-steam-unix.wcp](https://github.com/nicholasx417/WinNative-Components/releases/download/Proton/Proton-11.0-2-arm64ec-steam-unix.wcp) | 105,027,943 | `6de8fe5581c04d87b5ae4cef23d96de136090c696e3733201a92b1ce59e5163e` |
| `FEXCore-2610-14c92681f-unix-0` | [FEXCore-2610-14c92681f-unix.wcp](https://github.com/nicholasx417/WinNative-Components/releases/download/fex-unix-2610-14c92681f/FEXCore-2610-14c92681f-unix.wcp) | 1,555,528 | `bc5c601b995acf19a958237d8aff5cb24de7aa8b96241a35b3c0ad9e03ca2178` |
| `DXVK-1.10.3-arm64ec-async-0` | [Dxvk-1.10.3-arm64ec-async.wcp](https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-Arm64ec-Dxvk/Dxvk-1.10.3-arm64ec-async.wcp) | 13,911,832 | `520291823b59843aa1af2682db482c3bc657ed8afb34903cb88145125ab54098` |
| `DXVK-2.7.1-1-gplasync-arm64ec-0` | [Dxvk-2.7.1-arm64ec-gplasync.wcp](https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-Arm64ec-Dxvk/Dxvk-2.7.1-arm64ec-gplasync.wcp) | 3,556,764 | `732c12ea3b23b86e0cd73271083b16dcfe8070214cbc4d9bbb6cea1027a02ae1` |
| `VKD3D-3.0.1-arm64ec-0` | [Vkd3d-3.0.1-arm64ec.wcp](https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-arm64ec-VKD3D/Vkd3d-3.0.1-arm64ec.wcp) | 2,804,432 | `1754504f81e6a7fccd697231d4a1a49e9c3039da20981e950ece38fda6f4d0cd` |

Proton and legacy DXVK retain the earlier emulator baseline. FEX 2610 and the modern DXVK/VKD3D pair are development inputs. Fresh guided containers keep legacy DXVK with VKD3D `None` as the older-game baseline. For a fresh Steam session using unchanged guided graphics, automatic routing checks the selected installed driver and System driver against a native Vulkan feature snapshot. A complete static D3D12 import scan selects the pinned modern pair first. A small set of explicit Vulkan initialization failures can select another eligible installed combination after clean teardown and Cloud-reservation release, with at most four attempts. Saved container data and explicit per-game graphics settings are retained. The static scan does not detect every dynamically loaded graphics API or downstream game executable. See [routing qualification](QUALIFICATION.md#automatic-graphics-routing--18-october-2026) for the bounded behavior and phone gate.

The new DXVK archive's installed profile identifies `2.7.1-1-gplasync-arm64ec`, despite the different word order in its filename. Its DXGI is selected together with VKD3D because [vkd3d-proton requires DXVK 2.1+ for DXGI](https://github.com/HansKristian-Work/vkd3d-proton/blob/master/README.md#using-vkd3d-proton); it is never automatically paired with the legacy DXVK 1.10.3 baseline. The VKD3D archive contains `d3d12.dll` and `d3d12core.dll` under both guest Windows system directories. LLVM recognizes ARM64EC with CHPE metadata in its `system32` pair; the `syswow64` pair is x86. This is archive/metadata inspection, not proof of execution, required Vulkan features or Onimusha compatibility. The packages' identities and nonempty required files are checked before fresh container creation and before automatic selection. Missing pair files stop that detected D3D12 launch with a Prepare Vapor repair instruction before Steam handoff.
These inputs do not establish best performance, Snapdragon qualification or working DirectX 12 gameplay. A fresh environment retains the existing system graphics-driver policy; no automatic Turnip download is added. Already installed components are checked for their expected identity and nonempty required files, not rehashed as a complete installed tree. Mapping these binary builds and patches to reproducible source and distribution conditions remains open. The optional Advanced catalog's ARM64EC 3.0.1 fallback now reuses this verified URL rather than the old filename that does not match the release asset.

The 18 October routing work rebuilds the application's `libwinlator.so` native
probe. It does not replace bundled Wine, FEX, DXVK, VKD3D or Vulkan-wrapper
libraries. The learned-choice fingerprint hashes relevant component files; that
is not complete installed-tree verification or a reproducible build of the
downloaded components. The newly inspected Wine Android X11 patches and wrapper
allocation fix remain source-review candidates, with no binary/default change.

The Wine source review sampled the pinned archive's `ntdll.so`, `winex11.so` and
`winegstreamer.so`. `ntdll.so` embeds `wine-8.0-20901-g71040f04bd3`; the revision
resolves to the public Proton 11.0-2 merge commit
[`71040f04bd3a451fd2c5d2552f0b64a23dd7c69b`](https://github.com/GameNative/proton-wine/commit/71040f04bd3a451fd2c5d2552f0b64a23dd7c69b).
This identifies a source base, not the complete `steam-unixlib-3` patch set.
On 18 October, three unmodified upstream Android patches were applied only to an
isolated source fixture; the fourth was omitted because its window property is
already set in that base. No Wine binary was built in that review pass. The
19 October component section below records the subsequent build/comparison.
Sampled hashes, patch identities
and reverse/application checks are retained in the
[18 October receipt](evidence/2026-10-18-automatic-graphics.json) and
[runtime review](RUNTIME-RESEARCH.md#wine-source-mapping-and-android-patch-review).


The FEX candidate release identifies source commit `14c92681f4d62cf84d901460e0358de09c8847a7`
(tag `FEX-2610`) and supplies both ARM64EC/WOW64 DLLs plus their Bionic Unix libraries.
The [package workflow](https://github.com/nicholasx417/WinNative-Components/blob/ce9f4d538c57ffae17ebafa9d6087dc377e0c11e/.github/workflows/FEXCore-unix-nightly.yml)
provides the build recipe; an independent source rebuild was not performed in this step.
Both Unix libraries have `PT_LOAD` alignment `0x1000`, so this package still belongs to
the 4 KB runtime experiment. It does not close downloaded-runtime 16 KB qualification.
The retained baseline is `FEXCore-2607-84e127a63-unix-0`, 1,398,972 bytes, SHA-256
`cb3984127f24b743da4fc4c2dae68eee2a0eb8ed610695f953e841a09f2c1e2c`, available through
the existing component selector after installation. See the [FEX candidate checks](QUALIFICATION.md#fex-2610-development-candidate--16-october-2026).

## Local FEX cache-patch component — 16 October 2026

`FEXCore-2610-14c92681f-vapor-cache-unix-0` is a separate local experimental
component, not a new guided download. Its WCP is **1,559,576 bytes**, SHA-256
`99c0e6d0da02b0f95991e4474c6158a8dbe470748e8561fc185ef1a2d02e1620`.

- FEX source: `14c92681f4d62cf84d901460e0358de09c8847a7`, with its pinned recursive
  submodules, upstream app-config patch (SHA-256
  `7c6888052eaf15461a17c0b10c8b6068b65bd0635e772f32dce97c27d095fa18`) and the
  [nine-line Windows cache patch](../tools/fex/patches/0001-scope-windows-disk-cache-pruning.patch)
  (SHA-256 `469142174f16ee25ceb04be0484bfb941485db21a67cb566b096d30e08c232d2`).
- Compiler: bylaws LLVM-MinGW 20250920 UCRT, macOS universal, archive SHA-256
  `30681fa016b4a8a5dad4918193f5f4f99a01361b9f44810cf2337cf74fdbf81c`.
  Clang reports 21.1.0. The existing upstream Windows build flags are retained.
- Rebuilt `libarm64ecfex.dll`: **6,279,168 bytes**, SHA-256
  `c3c13ead5df591d42ac20040c13eea94f476c41461a1379399f48559c048201e`.
- Rebuilt `libwow64fex.dll`: **5,648,384 bytes**, SHA-256
  `8069036fed5e9b27a9e85e2832901570ba34b4d3c3069b5d0b5704260cc5d812`.
- Both Unix `.so` files are reused byte-for-byte from the pinned stock 2610 WCP:
  **110,648 bytes each**, SHA-256
  `a2ab73baef634c9fce01286bfc28c84fcd07e6798db857b2511223f87cdd59db`.
  Their 4 KB ELF alignment and shared-memory stubs are unchanged. This checkpoint
  rebuilds the Windows DLLs, not the entire runtime.

The package retains FEX's MIT notice, both source patches and a build-input manifest
with submodule revisions and payload hashes. FEX and third-party component licenses
remain their upstream licenses; no proprietary Steam libraries are in this WCP.
See [build instructions](../tools/fex/README.md) and
[bounded runtime checks](QUALIFICATION.md#fex-disk-cache-experiment--16-october-2026).

The separate 18 October upstream FEX backport input is
[`b49837f0`](https://github.com/FEX-Emu/FEX/commit/b49837f0c3961194b35da2187e5f742982a0c716),
retained unmodified as
[`0002-upstream-disk-cache-blob-lifetime.patch`](../tools/fex/patches/0002-upstream-disk-cache-blob-lifetime.patch).
It is 2,108 bytes, SHA-256
`e6f91c005d323a6656de23db8ebfe960786e8967d88d3fe62185ee8ed0c6f4fc`.
Apply-check passed against the isolated 2610 source with the earlier two patches.
It is **not included** in the historical 16 October WCP above or the APK. The
separate 19 October candidate below applies and rebuilds it.

## Runtime component builds — 19 October 2026

The local `FEXCore-2610-14c92681f-vapor-cache2-unix-0.wcp` is **1,560,960 bytes**,
SHA-256 `93fcd0458fb3c93df5196bd8c1c542823c3fb30bbaa5461ac231ffb4ae77077d`.
It adds the unmodified upstream blob-lifetime fix to the earlier source experiment.
Both Windows DLLs were rebuilt using the same pinned toolchain:

| Payload | Bytes | SHA-256 |
| --- | ---: | --- |
| `libarm64ecfex.dll` | 6,279,168 | `12878e06771044950c5bf528d32615bbb88f60eb12dc5f9b1339e14c2f4f3f97` |
| `libwow64fex.dll` | 5,648,384 | `7ad04b8dedb41900b42102691b0d68b6c24b6e28624a52a3f9e673d2b906e9c5` |

Both retained Unix libraries still match
`a2ab73baef634c9fce01286bfc28c84fcd07e6798db857b2511223f87cdd59db`
and have 4 KB LOAD alignment. The WCP retains the MIT notice, three patches and
build inputs. Caching remains off; no guided package or bundled asset changed.

The separate **partial Wine build** uses source base
`71040f04bd3a451fd2c5d2552f0b64a23dd7c69b` and three unmodified Android X11 patches
from `00095e1a945e357c22a0987d70392b9b352c1749`, retained in
[tools/wine](../tools/wine/README.md). Wine's per-file LGPL notices remain applicable.
The 56,705,767-byte source archive has observed SHA-256
`2bdd1653b206de74bbc886957af99d771d077ba13f462fdcd74b3162c510e117`;
the 1,190,645,760-byte Termux dependency archive matches its published digest
`b088df560c9395d7c2cc797d208d8a727d1f06fc70e5ea34eef6118ef64a7e42`.
All 45 Android patches named by the base build recipe are already present.
This still does not establish every change in the provider's `steam-unixlib-3`.

The X11 target builds with native `ntdll`/`win32u` dependencies; all three outputs
have 16 KB LOAD alignment. Only the new `winex11.so` was temporarily installed in
the isolated runtime comparison: **604,624 bytes**, SHA-256
`610da4f8e10df323dbb9b459ff11658f4882ad5fc2cd7c3cb43804c9d3a77766`.
No new Wine WCP is designated. Tests correct lost clipped-pointer events in both
guest widths, then restore original module/settings bytes and modes. This is not
a complete corresponding-source map, Steam ABI qualification, codec improvement,
16 KB runtime test or phone benchmark.

All artifact/module identities, dependency inputs, patch hashes and restoration
checks are in the [portable receipt](evidence/2026-10-19-runtime-components.json).
See [bounded results](QUALIFICATION.md#runtime-components--19-october-2026).

## Android ntsync library audit — 16 October 2026

This checkpoint builds a standalone library and test fixtures; it produces **no
installable Wine component or new APK**. Vapor does not link or load the experimental
library, and its selected runtime/backend configuration remains unchanged.

- Library source: [GameNative/ntsync-android](https://github.com/GameNative/ntsync-android/tree/7ce6435e5979b1cb5341aa4b299f31e8937fe121),
  commit `7ce6435e5979b1cb5341aa4b299f31e8937fe121`, also the observed joshuatam
  upstream head. The source snapshot is 48,976 bytes, SHA-256
  `efafc9774c125c27fe3aab4354707b8ffa41312f2b821e7f478c4a1fcc33b04e`.
  Source/header SPDX terms are **LGPL-3.0-only**; the original
  [license text](../tools/ntsync/DONOR-LICENSE) is retained.
- Rust dependency: locked `libc 0.2.189`, checksum
  `3eaf3ede3fee6db1a4c2ee091bf8a8b4dccdc6d17f656fb07896ee72867612f2`.
  Build: Rust/Cargo 1.99.0, NDK `27.3.13750724`/Clang 18.0.4, Android API-28
  AArch64 target, using the donor's existing 16 KB Cargo flags.
- Local modification: [one `src/core.rs` patch](../tools/ntsync/patches/0001-require-recoverable-region-mutex.patch)
  corrects unsupported Android initialization and adds two focused regressions.
  The source patch replays without fuzz to reproduce the compiled source. It
  changes no public C header, shared-memory layout, Cargo dependency or app path.
  It does not implement a recoverable Android mutex or qualify the backend.
- The original and guarded `.so` and static `.a` files remain in the local
  experiment directory with hashes recorded. Checked ELF library/test LOAD
  segments are aligned to at least 16 KB. A static archive's existence is not
  proof that Wine links it or that a complete runtime works on a 16 KB kernel.
- Wine source audit: [GameNative/proton-wine d13b1d7e](https://github.com/GameNative/proton-wine/tree/d13b1d7efcadeda19341252a4406471a4bc513ca).
  Its vendored public header matches the library's bytes. The actual server patch
  applies without fuzz to the isolated source fixture. No Wine binary is rebuilt,
  copied into the current prefix or distributed. Its matching LGPL source and
  component notices remain separate from the LGPL-3.0-only library.

The native C-ABI check and additions to Vapor's existing Windows execution probe
are test code. They require no account or third-party game files. Library success,
source-level Wine dispatch tracing, existing Windows runtime behavior, actual
backend activation, Steam lifecycle and phone performance remain distinct checks.
The [qualification record](QUALIFICATION.md#android-ntsync-recovery-gate--16-october-2026)
documents the observed owner-death failure and the guarded build's explicit refusal.
No proprietary GameNative execution/bootstrap helper is imported.

## Local graphics source refactors — 21 October 2026

The new Turnip experiment uses Mesa
`f28352d1179a4a85b4be286a883747bae2d3786b`, the existing credited Android adaptation
patch and `tools/turnip/kgsl-wait-any.patch`. The latter is a local generated source
change to KGSL synchronization scratch storage. Both Android baseline and
candidate build; the candidate is kept outside APK assets as a separate manual
import ZIP with its source patches, input manifest, payload checksum and retained
Mesa/dependency/toolchain/donor notices. The existing donor-license distinction
below still applies. This is not a reproduction of the entire V38 patch stack.

`tools/vulkan-wrapper/image-create.patch` is a local generated change against
`leegao/bionic-vulkan-wrapper` commit
`3abf48704a4d9303967a0811c0dc8ff8567d613b`. It changes image-creation scratch
storage, mask evaluation, caller-owned pNext/format-list handling and error cleanup.
`tools/vulkan-wrapper/trace-gate.patch` corrects the existing public-call logging
gate and its Meson import dependency. Both complete Android baseline/candidate
builds succeed; 26 image cases plus a read-only subprocess and seven generated-call
cases pass on the candidate with clean sanitizers. Native GPU execution and
reproduction of the full packaged binary remain unqualified.

The four reviewed image/generator files at that source revision are byte-identical
to release tag `v0.0.5r5` at `c8baafbd4f4835ca103acb55ec3ac13642b6b7e3`.
The source archive is **47,251,711 bytes**, SHA-256
`fe1f7e32f0c4b151f74b252ffda3f3561d56df96e3b17863f66c89b01b698f3d`.
The build uses the custom `leegao/SPIRV-Tools` fork at
`56db26cf58f2f1b1f70ce92fc733ae91e136094d` and its `DEPS` SPIRV-Headers pin
`97e96f9e9defeb4bba3cfbd034dec516671dd7a3`, preserving the respective upstream
licenses. This is not a build using unmodified Khronos shader passes. Exact input,
dependency and output hashes are in the existing component receipt. The bundled
wrapper identities recorded above remain unchanged.

Both component experiments are AI-assisted local experiments. The reviewed wrapper
files retain their upstream Mesa MIT license, and the custom `leegao/SPIRV-Tools`
fork retains its Apache-2.0 license. Neither component is installed, bundled,
promoted or submitted upstream. No proprietary Valve component or game file is
modified; the source tree's contribution instructions and public distribution
review apply before any later publication. Do not treat local test
or build results as approval for redistribution, GPU conformance or game support.
[Input/output hashes and limits](evidence/2026-10-21-graphics-source.json),
[qualification](QUALIFICATION.md#graphics-component-source-refactors--21-october-2026).

## Local Android Turnip candidate — 16 October 2026

`Vapor-Turnip-a51a4189-balanced-experimental.zip` is a separate local imported-driver
experiment, **2,842,739 bytes**, SHA-256
`ddb5134a58c6fd175097741173dd5a1665cf30c8d13b3d70219a80588bcff822`.
It is not bundled, auto-downloaded or made a default. Minimum Android API is 34.

- Mesa source: `a51a418991f883413f10f66cd52398d590a8257b`, `26.3.0-devel`,
  containing the merged [KGSL sync series](https://gitlab.freedesktop.org/mesa/mesa/-/merge_requests/44838).
  The observed official source archive SHA-256 is
  `e99bf3a5256458c0c02fadb7684b92367c4ac02f5667c3107ab7963a854863cc`.
- Android/GPU patch source: [WinNative-Emu/Drivers c71f600a](https://github.com/WinNative-Emu/Drivers/tree/c71f600a2977a141f21a922c9ed2e14fb09b13d3),
  including Leb-Sun's IMapper/UBWC work. The [consolidated patch](../tools/turnip/android-driver.patch)
  changes/adds 13 files and replays onto pristine source to reproduce the compiled
  source bytes. Only the source-archive reset and obsolete A825 `magic_regs`
  parameter/anchor were adapted in the applicators. The missing old draw-count
  threshold was not replaced. The balanced bandwidth adjustment is retained;
  maximum-clock/performance variants are not applied.
- Compiler: Android NDK `27.3.13750724`, Clang 18.0.4, API-34 AArch64 target;
  Meson 1.9.1, Ninja 1.10.2, glslang 16.6.0 and local GNU Bison 3.8.2 on macOS.
  Exact dependency versions, Meson cross/native inputs, wrap source/patch digests
  and flags are in `build-inputs.json`. Build retries and warnings are retained.
- Stripped driver: **14,531,896 bytes**, all four ELF LOAD segments aligned to
  `0x4000`. Its Android dynamic dependencies and loading gate are documented in
  [BUILDING](BUILDING.md#android-turnip-candidate). This is packaging evidence,
  not driver initialization, Vulkan feature qualification or 16 KB kernel proof.

License evidence is mixed: the pinned driver README says MIT, while its actual
root [LICENSE](https://github.com/WinNative-Emu/Drivers/blob/c71f600a2977a141f21a922c9ed2e14fb09b13d3/LICENSE)
contains GPL-3.0. The package and [donor notice](../tools/turnip/DONOR-LICENSE)
retain that file; Mesa's per-file terms, the IMapper source's MIT SPDX identifier,
Mesa license texts, libarchive/libxml2 notices and the NDK toolchain notice remain
separate. Do not describe the entire donor patch set as uniformly MIT or the
complete Vapor runtime as GPL-only. Public redistribution review remains open.

The new source wait test and extended Windows probe are Vapor test code. They do
not implement a replacement Vulkan driver or Steam bridge. No proprietary Steam
libraries are in this ZIP. See [qualification](QUALIFICATION.md#turnip-queue-audit-and-driver-candidate--16-october-2026)
for source tests, software-rendered controls, the rejected DXVK queue patch and
pending phone/driver checks.

## Local DXVK release candidate — 16 October 2026

The separate local WCP `Dxvk-3.1.1-arm64ec-vapor.wcp` is **6,959,396 bytes**,
SHA-256 `12fddac9e250fe88a58f67b948d24ac88d997b7b2f4da9d757e3b562a3960afb`.
Its installed entry is `DXVK-3.1.1-arm64ec-vapor-0`. It does not replace the guided
`1.10.3-arm64ec-async-0` pin or add a public download URL.

This builds unmodified upstream DXVK tag `v3.1.1`, commit
`b1a1c99ab52b687cf950d62c88bc2fa316b41663`, with its recursive submodules. No async,
queue, device-feature or requirement-relaxation patch was applied. The ARM64EC/x86
cross-file and packaging pattern comes from the pinned
[component workflow](https://github.com/nicholasx417/WinNative-Components/blob/ce9f4d538c57ffae17ebafa9d6087dc377e0c11e/.github/workflows/Dxvk-nightly-Gplasync.yml);
the new component retains upstream author and dependency licenses. ARM64EC DLLs retain symbols; packaged x86 copies are stripped.

The build uses bylaws LLVM-MinGW 20250920/Clang 21.1.0 (archive digest recorded above),
Meson 1.9.1, Ninja 1.10.2 and Khronos glslang 16.6.0. The shader compiler's official
macOS universal release archive SHA-256 is
`4f8c05f2888a73ed0b7b274bc524af9130e09ae94b43c0f3f9ed83cfae852834`.

| Built file | Bytes | SHA-256 |
| --- | ---: | --- |
| ARM64EC `system32/d3d11.dll` | 8,421,376 | `5c9837b2a3aa8ddccd0d15a0a2c0983e74eb59c0ef93d573cb5935a2fff960b9` |
| x86 `syswow64/d3d11.dll` | 6,529,024 | `c89d2aff2f4462d5b8832b662fffad10621ed41ab70a6ff91d6eb90e65437c68` |
| ARM64EC `system32/dxgi.dll` | 5,644,288 | `689e05ad480b757c02094a1b7a14b7743682011669c214fa96fb415fcd8c4834` |
| x86 `syswow64/dxgi.dll` | 4,632,576 | `cec9518a8460fecab28e4116b12504d7cfac5df925c3e8d4bd2e917bff2dea42` |

The WCP maps ten DLLs: D3D8, D3D9, D3D10Core, D3D11 and DXGI for each architecture.
Its `provenance/build-inputs.json` contains all payload hashes and submodule commits;
it includes DXVK, dxbc-spirv and libdisplay-info notices and the release's Vulkan
requirements profile. This archive contains no Valve libraries or new Android ELF
libraries. A successful cross-build and DLL load do not establish graphics compatibility.
See the [graphics/cache checkpoint](QUALIFICATION.md#dxvk-311-candidate-and-cache-path--16-october-2026).

## Public distribution blockers

1. **Valve client binaries:** `app/src/main/assets/wnsteam/bionic/valve-steam-x86_64.tzst` and other genuine Steam assets are inherited. Their presence in a public upstream is not redistribution permission. Establish a supported acquisition route and applicable terms before publishing an APK containing them.
2. **Bundled runtime archives:** map the exact Wine/FEX/graphics builds and patches to corresponding source and licenses. Repository commits and SHA-256 alone are insufficient.
3. **Inherited optional integrations:** console, other-store, frame-generation and helper assets remain bundled. Narrow the shipped product and review those retained inputs before alpha.
4. **Modern Android:** target remains 28. The 16 October source rebuild resolves the three Prefab failures: all 39 APK native libraries now pass LOAD alignment and ZIP alignment passes. Nine actual JNI checks pass on both 4 KB and 16 KB emulators, and normal startup was checked on Android 17. Earlier APKs used page-size compatibility mode. These newer checks do not resolve target-36 downloaded-code execution, downloaded runtime ELF files or complete Wine/FEX game operation on 16 KB systems; see the [bounded result](QUALIFICATION.md#native-library-alignment-and-tls--16-october-2026).
5. **Account and game proof:** the owner now reports successful EU3 gameplay and TF2 training with Steam connected on Xiaomi; the exact APK/configuration is not yet identified. Earlier emulator builds completed an authenticated EU3 demo download and a genuine-client launch handoff with visible rendering; an earlier custom-game build also completed a local save/reopen round trip. These separate results are recorded in [QUALIFICATION.md](QUALIFICATION.md#owner-reported-xiaomi-gameplay--14-october-2026). Steam-game save/reopen, Steam Cloud correctness and the full reference-phone qualification protocol remain open.

The original license and attribution files remain intact. A local development build does not close these gates. CI only builds/tests and preserves test reports; it does not publish APKs or releases.
