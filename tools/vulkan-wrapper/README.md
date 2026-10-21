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

No app asset, runtime selection or release changes. Native loading, reference
image/BC decoding, staging lifetime, concurrency, GPU execution/performance and
16 KB kernel behavior remain unqualified. Build-tree libraries are not packaged
replacements for Vapor's shipped wrapper.

See [qualification](../../docs/QUALIFICATION.md#graphics-component-source-refactors--21-october-2026)
and [the source/build receipt](../../docs/evidence/2026-10-21-graphics-source.json).
