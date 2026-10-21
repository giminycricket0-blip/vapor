# Vulkan-wrapper source experiments

`image-create.patch` targets
[`leegao/bionic-vulkan-wrapper` at `3abf48704a4d9303967a0811c0dc8ff8567d613b`](https://github.com/leegao/bionic-vulkan-wrapper/blob/3abf48704a4d9303967a0811c0dc8ff8567d613b/src/vulkan/wrapper/wrapper_image.c).
It changes the existing library's `WRAPPER_CreateImage`, not Vapor's driver
selector. `trace-gate.patch` changes that tree's existing call generator and its
Meson dependency. These are local generated patches, not upstream submissions.

## Packaged source relationship

Vapor's default `wrapper.tzst`, optional `wrapper-leegao.tzst` and optional
`wrapper-gamenative.tzst` contain different libraries. This experiment concerns
the Leegao source; it does not establish the source of the other two variants.
The optional Leegao payload matches the upstream `v0.0.5r5` release binary exactly.
That tag resolves to `c8baafbd4f4835ca103acb55ec3ac13642b6b7e3`. Its image function,
call generator, trampoline generator and unwrapping generator are byte-identical
to the corresponding reviewed files at `3abf4870`; per-file SHA-256s are recorded
in the component receipt. This establishes the changed
source paths' relationship to the release, not reproduction of its complete binary.

A static payload check confirms all three archive hashes and separates the build
lines. The default and GameNative payloads are a later wrapper line: NDK
r29/clang 21, 16 KB `LOAD` alignment, an ICD-only 15-symbol export set, and
compile units that do not exist in the v0.0.5r5 tag or the reviewed tree. Their
exact source revisions remain open. The Leegao release recipe is the retained
Termux packaging repository (`leegao/vulkan_wrapper_termux-packages` at
`c5c45d88`, NDK r27c, API 26); a build of the tag source matches its SONAME,
20 `NEEDED` names, export family and 4 KB alignment but not its bytes, because
the release NDK, sysroot and SPIRV-Tools revision are not retained.
[Reproduction receipt](../../docs/evidence/2026-10-21-wrapper-release-repro.json).

## Image creation

The original function allocates a temporary create-info copy and a list node for
every image, then releases both before returning. A stack copy has the same
lifetime for the synchronous call and removes these two temporary allocations.
The patch also evaluates the BCn mask only for an image requiring BCn emulation,
avoiding an invalid shift for ordinary formats. If wrapper bookkeeping fails
after the underlying image was created, it destroys that image with the original
allocator rather than leaking it.

Tracing `WRAPPER_CreateImage` through the generated public wrapper shows that the
caller data reaches it directly. The lower dispatch trampoline runs afterwards;
it does not protect an earlier write. The original BC path casts away `const`
and rewrites the caller's format-list count and first array element. A read-only
format list reproduces an invalid write under ASan.

The candidate instead keeps its rewritten format list and format on the stack.
When other extension nodes precede that list, it copies only those nodes into one
scratch allocation using Mesa's registry-size helper. Their fields and order,
and the original tail after the list, are preserved. Scratch is released on
success, lower-driver error and bookkeeping failure. Empty format lists remain
empty. No format-list or no rewrite means no chain allocation.

An unknown-size node before a list requiring rewriting returns
`VK_ERROR_FORMAT_NOT_SUPPORTED`; it is not silently dropped or mutated. Unknown
nodes after the rewritten list, and untouched chains, pass through. This
conservative failure and actual supported extension combinations still need
driver qualification before promotion.

The [source test](../tests/vulkan-wrapper-image.py) extracts the actual function,
uses the upstream `TEMP_OBJECT` macros and Vulkan headers, and mocks the lower
driver, format helpers, registry-size lookup and object bookkeeping. It passes
26 cases plus a separate read-only-memory subprocess under ASan/UBSan. The
original fails caller-preservation/empty-list cases, reports an invalid shift,
and crashes on read-only input. Ordinary-image temporary allocations change from
two to zero. That is not a texture-upload, GPU-time or whole-allocation benchmark.

Reproduce with the pinned before/after files, the source header directory holding
`wrapper_checks.h`, and a Mesa source tree providing Vulkan and list headers:

```sh
python3 tools/tests/vulkan-wrapper-image.py before.c after.c wrapper-headers mesa-source /tmp/vapor-wrapper-image
```

## Disabled tracing

The public call generator uses `if (should_log)`, the always-true address of a
function, where it already computes `should_print`. The patch gates both input
and output details on that existing boolean. In the generated three-call
fixture, tracing off changes log-file lookups **175→0** and structure-printer
calls **34→0**. Names-only and trace-disabled cases no longer enter detailed
logging; explicitly enabled tracing and Vulkan results are preserved. These
counts include the chosen 64-buffer/32-submit inputs; they are not costs per draw
or an FPS estimate.

The [trace test](../tests/vulkan-wrapper-trace.py) generates real public wrappers
for image creation, vertex-buffer binding and queue submission and uses the
actual upstream logging macros. Vulkan execution and structure printers are
stubs. Seven cases pass under ASan/UBSan; the original fails five. The host
fixture permits upstream printf warnings caused by macOS's `int64_t` spelling;
it is not an Android ABI execution check.

```sh
python3 tools/tests/vulkan-wrapper-trace.py before-generator.py after-generator.py mesa-source /tmp/vapor-wrapper-trace
```

The initial incremental build revealed that Meson did not track the imported
local `vk_entrypoints.py`. The patch adds it to the existing generator's
`depend_files`. The completed candidate build regenerates the header and replaces
all 1,174 faulty conditions with the intended gate. A build that recompiles only
the image file does not qualify the tracing change.

## Native loading and BC probe

[`tools/tests/vulkan-wrapper-native.c`](../tests/vulkan-wrapper-native.c) is an
offscreen probe; it starts no Steam, Wine, window or game. It dlopens the system
loader or a wrapper library, creates an instance and device, creates an image
with a caller-owned format list, uploads BC1/BC3 blocks through
`vkCmdCopyBufferToImage`, reads the image back and checks the decoded RGBA
pixels. A read-only caller format list exercises the original write that the
source patch removes. Build it with the recorded NDK r27d (27.3.13750724) command
and 16 KB link flags; the receipt records the exact command and binary hashes.

| Emulator | Guest | Page size | Result |
| --- | --- | ---: | --- |
| `Vapor_UI_API36` | Android 16/API 36, arm64-v8a | 4096 | 47 runs: system, baseline and candidate rgba pass; baseline and candidate BC1/BC3 pass with `FORCE_BCN_EMULATION=1`; baseline readonly crashes 2/2 (expected control), candidate readonly passes 2/2 |
| `Vapor_API_37_16KB` | Android 17/API 37, 16 KB image | 16384 | 132 runs, every run `page_size=16384`: candidate rgba/bc1/bc3/readonly 36/36 and `USE_IMAGE_VIEW=1` 12/12; baseline rgba/bc1/bc3 27/27 and readonly SIGSEGV 9/9 (expected control); system-loader BC pixel checks fail as described below |

The wrapper libraries and every auxiliary dependency dlopen with `RTLD_NOW` on
the 16 KB guest; all staged libraries have `0x4000` `LOAD` alignment and none
failed to load. On the 16 KB AVD the guest's Goldfish GFXStream device reports
`textureCompressionBC` but returns undecoded BC block bytes for native
buffer-image copies; the same failure occurs against the system loader with no
wrapper loaded, so it is an emulator limitation, not a wrapper or page-size
regression. BC runs therefore force the wrapper's emulation path, matching the
4 KB matrix. The two AVDs differ in API level and GPU backend, so they are two
qualifications, not a matched differential.

Receipts: [4 KB runs](../../docs/evidence/2026-10-21-vulkan-wrapper-native-4k.json),
[16 KB runs](../../docs/evidence/2026-10-21-vulkan-wrapper-native-16k.json).
These results cover software Vulkan (llvmpipe) in an emulator only. They do not
qualify an Adreno/KGSL driver, a physical phone, game rendering, image quality or
performance, and the probe inputs are synthetic.

## Staging lifetime

The wrapper tracks staging storage per BCn-emulated region in
`wimg->staging_resources` (two objects per upload: `VkBuffer` and
`VkDeviceMemory`; the image-view path tracks one `VkImageView`). The source review
finds no fence-gated reclamation in effect: `ready` is never assigned, the fence
branch is unreachable because the only caller hard-codes `cleanup_everything`,
and the intended queue-fence cleanup remains a TODO. Resources are freed
unconditionally at image destroy or device destroy.

That is safe for the probe's waited submissions, but a long-lived image
accumulates two tracked resources plus its staging bytes per upload until
destroy: 32 probe uploads produced 64 tracked entries, released in one
`vkDestroyImage` call. The review records this as inherited upstream behavior;
the candidate's patches do not touch the staging files, which are byte-identical
to the pinned source. An open conditional risk is also recorded: if an
application enables `VK_EXT_map_memory_placed` with `memoryMapPlaced`, staging
memory allocated on the wrapper's emulated-memory path is freed through the raw
trampoline, leaving a wrapper entry that `vkDestroyDevice` can free again. No
probe path enables those features; the receipt states the fixture needed to
settle it and does not claim a defect.

[Staging review receipt](../../docs/evidence/2026-10-21-vulkan-wrapper-staging.json).

A follow-up patch ([`staging-reclaim.patch`](staging-reclaim.patch)) implements the
intended reclamation: staging ownership moves to the recording command buffer,
one-time-submit resources take the submit fence and are freed from
`vkWaitForFences`/`vkDeviceWaitIdle`, re-submittable command buffers release on
begin/reset/destroy, and internal staging buffer/memory use the raw dispatch table
so the emulated-memory double-free class is removed. The patch applies and
compiles cleanly, and the built library keeps the candidate's SONAME, 20 `NEEDED`
names, `0x4000` alignment and 4,073-export set. A/B probe runs on the emulator
confirm the intended release points: the unpatched control frees a single
64-resource batch inside `vkDestroyImage`, while the patched library frees two
resources per command-buffer free in the staging path, inside `vkWaitForFences`
for one-time-submit+fence, and inside `vkDeviceWaitIdle` for the device-idle path,
with equal tracking/release totals and no destroy-time crash. Deeper runtime
validation (23 runs on 4 KB and 16 KB) adds unfenced command-buffer-free
releases, reset/begin reclamation, `vkQueueSubmit2`-with-fence reclamation, a
documented semaphore-only limitation, stress peaks of 16 and 2 tracked resources
against 512 for the unpatched control, and 16 KB A/B passes. Validation layers,
the placed-memory path, concurrency and phone-duration growth remain pending.
A no-GPU loader-path lane now qualifies placed-memory capability reporting,
entry-point plumbing and the skip branch (lavapipe SKIP; a pinned patched
Vulkan-Tools mock ICD reports the feature and completes placed/range
`vkMapMemory2` with zero VUIDs), and the Android emulator paths are confirmed to
lack the extension. Real placement semantics still need RADV/ANV/NVK/Turnip or a
phone. See
[loader-path receipt](../../docs/evidence/2026-10-21-vulkan-loader-path.json) and
[Android presence receipt](../../docs/evidence/2026-10-21-vulkan-map-memory-presence.json).
See
[the staging review](../../docs/evidence/2026-10-21-vulkan-wrapper-staging.json)
and [the reclaim patch receipt](../../docs/evidence/2026-10-21-wrapper-staging-reclaim.json).

## Reference-image probe

[`tools/tests/vulkan-wrapper-reference.py`](../tests/vulkan-wrapper-reference.py)
drives an offscreen fixture with deterministic BC1/BC3 blocks and depth formats,
compares wrapper readbacks against an independent S3TC decoder, and includes the
system loader as a native-driver cross-check. On the emulator the wrapper
BC1/BC3 decode is exact (4096/4096 pixels, 0 LSB) for candidate and baseline in
both staging and image-view configurations; the CPU fallback stays within 1 LSB
and llvmpipe's native decode within 2 LSB. Depth D16/D24S8/D32 transfers are
bit-exact round trips. `--print-runbook` prints the phone command and expected
controls; these are software-Vulkan synthetic results, not Adreno/KGSL evidence.
[Probe receipt](../../docs/evidence/2026-10-21-vulkan-wrapper-reference.json).

Extended cases add the 16 KB matrix (43/43 pass, page size 16384), odd extents,
partial regions with sentinels, mips/array layers, sRGB variants and independent
BC4/BC5 (RGTC) decoders; the depth-reduction knob is reachable through
`DEPTH_STENCIL_ATTACHMENT` images and recorded per mode. Two suspected
wrapper-side defects are documented, both software-emulation only: BC_SRGB data
decodes differently between the compute path and the host-CPU fallback, and the
host-CPU path corrupts partial edge blocks for out-of-spec non-block-aligned copy
extents. The extended tool also fixes a missing `re` import in the first-pass
revision while keeping the default matrix/format names.
[Extended receipt](../../docs/evidence/2026-10-21-vulkan-wrapper-reference-extended.json).
The same extended families also pass on the 16 KB Android 17/API 37 emulator
(page_size=16384), where both suspected defects reproduce.
[16 KB extended receipt](../../docs/evidence/2026-10-21-vulkan-wrapper-reference-16k-extended.json).

## Android build and limits

Build the baseline before applying both patches with `patch -p1 --fuzz=0`, then
build the candidate from the same source/dependencies. Both complete Android
ARM64 libraries compile, including the custom SPIR-V passes. The pinned fork
needs `leegao/SPIRV-Tools` at `56db26cf58f2f1b1f70ce92fc733ae91e136094d`, using
`SPIRV-Headers` at `97e96f9e9defeb4bba3cfbd034dec516671dd7a3` from its `DEPS`.
Ordinary Khronos SPIRV-Tools does not provide this wrapper's three custom passes.

The checked build uses NDK r27d (27.3.13750724)/API 34, Meson 1.9.1, CMake 3.22.1, glslang 16.6.0
and X11 dependencies from the existing ARM64 Termux sysroot. Cross pkg-config must
place the sysroot's root include directory after NDK system headers, preserving
the NDK's libc/libc++ headers. Define `__TERMUX__`, as this fork's Android/X11 path
expects it, and include `fcntl.h` for the missing open-flag declarations. Use
16 KB maximum/common page-size linker flags. Explicitly link `libandroid-shmem`,
Adrenotools and shared `libc++`. Both final binaries retain the same SONAME,
4,073 exported-symbol entries, 434 strong undefined-symbol entries and 20 direct
dependencies; dependency names also match the optional release. Their four
`LOAD` segments are `0x4000` aligned. The receipt records the actual
compiler flags, dependency hashes and build commands.

Build the two SPIRV-Tools static targets with the NDK CMake toolchain, ARM64/API 34,
PIC, Release, tests/executables disabled and the pinned headers source. Place
`libSPIRV-Tools-opt.a` and `libSPIRV-Tools.a` in `src/vulkan/wrapper/lib/`, the
location the upstream Meson file already expects. Configure Mesa with the Android
cross file and the options shown in the
[build instructions](../../docs/BUILDING.md#vulkan-wrapper-source-build--21-october-2026).
Build `src/vulkan/wrapper/libvulkan_wrapper.so`; keep stubs as build inputs only.

No app asset, runtime selection or release changes. Native loading and BC decoding
pass the emulator probe above, but an effective physical driver (Adreno/KGSL),
reference-image/depth correctness, retention behavior under streaming workloads,
concurrency, GPU execution/performance and 16 KB kernel behavior on a device
remain unqualified. Build-tree libraries are not packaged replacements for Vapor's
shipped wrapper.

See [qualification](../../docs/QUALIFICATION.md#graphics-component-source-refactors--21-october-2026)
and [the source/build receipt](../../docs/evidence/2026-10-21-graphics-source.json).
