# FEX runtime patch candidate

This directory holds a narrow patch for the FEX 2610 cache experiment. It is not
applied by Gradle and does not change the pinned guided-preparation package.

`patches/0001-scope-windows-disk-cache-pruning.patch` applies to FEX commit
`14c92681f4d62cf84d901460e0358de09c8847a7` (tag `FEX-2610`). FEX is MIT-licensed;
the patch extends its existing `DiskCache::Init` owner.

The observed problem is cache eviction between Windows guest architectures.
WOW64 disables AVX in `HostFeatures`; ARM64EC does not. Their machine buckets differ
by that feature bit, and pruning removes every directory other than the current
bucket. Alternating x64/x86 programs therefore discards useful compiled code.

The patch gives the two Windows frontends their own directories before pruning.
Each frontend still validates guest code, partitions by host/configuration, limits
its main file and prunes its stale buckets. Linux paths and default-off behavior
are unchanged. Automatic partitioning belongs in the runtime: selecting a directory
from the launcher's first EXE would miss a child process of another architecture.

Existing unsuffixed caches are not migrated. A mixed old/new runtime can remove
new subdirectories if both are explicitly pointed at the same old root; qualify
component switching separately. Read-only cache providers would also need the new
frontend directory layout. Vapor does not currently configure those providers.

The source-build/packaging result and native checks are recorded in
[qualification](../../docs/QUALIFICATION.md#fex-disk-cache-experiment--16-october-2026).
Do not infer an installed runtime fix or game-speed improvement from patch presence.

## Reproduce the Windows DLL build

Use a separate source checkout, not the app's build directory. Check out FEX tag
`FEX-2610` with recursive submodules and verify that HEAD is the commit above.
Use the **bylaws** LLVM-MinGW 20250920 UCRT toolchain (Clang 21.1.0), matching the
[upstream package recipe](https://github.com/nicholasx417/WinNative-Components/blob/ce9f4d538c57ffae17ebafa9d6087dc377e0c11e/.github/workflows/FEXCore-unix-nightly.yml).
The macOS universal archive SHA-256 is
`30681fa016b4a8a5dad4918193f5f4f99a01361b9f44810cf2337cf74fdbf81c`.

Before configuring, apply both the upstream
[optional app-config fix](https://github.com/nicholasx417/WinNative-Components/blob/ce9f4d538c57ffae17ebafa9d6087dc377e0c11e/patch/fex-appconfig-nonfatal.patch)
(SHA-256 `7c6888052eaf15461a17c0b10c8b6068b65bd0635e772f32dce97c27d095fa18`)
and this directory's cache patch. The first preserves the existing package's handling
of empty/malformed optional configuration files. The second is the only new runtime
behavior in this experiment. `git diff --check` should pass.

With the toolchain, CMake and Ninja on PATH, run from the FEX checkout:

```sh
for architecture in arm64ec aarch64; do
  CXXFLAGS="-Wno-deprecated-enum-enum-conversion" \
  cmake -S . -B "build-$architecture" -G Ninja -Wno-dev \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG -g -fno-strict-aliasing -fwrapv" \
    -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG -fno-strict-aliasing -fwrapv" \
    -DCMAKE_TOOLCHAIN_FILE=Data/CMake/toolchain_mingw.cmake \
    -DMINGW_TRIPLE="$architecture-w64-mingw32" \
    -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_INSTALL_LIBDIR=/ \
    -DENABLE_JEMALLOC_GLIBC_ALLOC=False -DENABLE_LTO=False \
    -DENABLE_ASSERTIONS=False -DBUILD_TESTING=False \
    -DTUNE_ARCH=generic -DTUNE_CPU=generic -DOVERRIDE_VERSION=2610
  cmake --build "build-$architecture" --parallel 2
done
```

The outputs are `build-arm64ec/Bin/libarm64ecfex.dll` and
`build-aarch64/Bin/libwow64fex.dll`. The recorded local directories were named
`build_arm64ec` and `build_wow64`. Do not strip the DLLs. Both local builds completed
with inherited `_chsize` import warnings; ARM64EC also warned about X23/W23 assembly
register usage. These warnings were retained in the build logs, not fixed by this patch.

Package the two DLLs under `system32/`, with the **unchanged** two `aarch64-unix/`
libraries and four file mappings from the pinned 2610 WCP. Give the profile a distinct
`versionName`: `2610-14c92681f-vapor-cache-unix`, `versionCode: 0`. Use the existing
xz-compressed tar WCP format. Include FEX's license, both patches and build inputs.
This does not rebuild the Unix libraries or change their 4 KB alignment. It is not a
complete reproducible build of every component in the runtime.

## Qualify cache reuse

Use the account-free [Windows probes](../../docs/BUILDING.md#windows-translation-smoke-probes)
on an isolated prepared 4 KB ARM64 AVD. In the existing FEX application configuration,
set `DiskCache` to `1`, `DiskCacheMemorySize` to `0`, `DiskCacheMaxFileSize` to
`16777216`, and `DiskCachePath` to a dedicated Windows directory ending in `/`.
Apply the same path to both probes through `AppOverrides`; do not choose architecture
directories in the launcher. The patch must add those itself.

Run x64 cold/warm, x86 cold/warm, x64 again, then x86 again. Verify actual applied
DLL/Unix-library hashes, each probe's new result, both cache directories and warm
reuse. `DumpIR` with `PassManagerDumpIR: afteropt` can count unique compiled guest
entrypoints: cache hits return before `CompileCode`. This is diagnostic evidence,
not an exact hit rate or performance benchmark. FEX's SHM statistics are unavailable
in this upstream Bionic Unix package.

Also check a changed probe with the same filename/PE timestamp/image size, a small
main-file limit, cache-off behavior, and stale-machine cleanup within each frontend.
Use only test-owned binaries/cache directories. Force-stopping between console runs
does not qualify Steam exit or Cloud sync. Restore the prior container settings and
verify the applied baseline runtime after testing; restoring a saved version field
alone can leave a different DLL pair installed.
