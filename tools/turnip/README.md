# Android Turnip source experiments

## KGSL wait-any refactor — 21 October 2026

`kgsl-wait-any.patch` changes the actual Turnip driver at Mesa
[`f28352d1179a4a85b4be286a883747bae2d3786b`](https://gitlab.freedesktop.org/mesa/mesa/-/blob/f28352d1179a4a85b4be286a883747bae2d3786b/src/freedreno/vulkan/tu_knl_kgsl.cc).
A 21 October sample of Mesa main
(`c90ff433291a5c3ff5cea9913bbebeef9cd4e885`) has an identical `tu_knl_kgsl.cc`;
the sampled file SHA-256 is recorded in the receipt. This is a local generated
source change, not a patch submitted to Mesa or a reproduction of StevenMXZ's
complete V38 patch stack.

The function previously used a growable `u_vector` as a temporary linear list.
Timestamp-only waits inspected its length without initializing its element size,
which divides by zero. Allocation failure also reaches an invalid memory access.
The refactor classifies inputs in one pass, sizes the poll list for the actual
descriptors, uses Mesa's existing `STACK_ARRAY`, checks its heap fallback, and
preserves the owned-versus-borrowed fence cleanup. It adds no persistent state,
public interface, configuration flag or new allocation mechanism.

The same source fixture records these scratch-buffer costs:

| Input sync objects, all FDs | Before allocations | After allocations | Growth-copy bytes before → after |
| --- | --- | --- | --- |
| 2 | 1 | 0 | 0 → 0 |
| 8 | 2 | 0 | 32 → 0 |
| 9 | 3 | 1 | 96 → 0 |
| 32 | 4 | 1 | 224 → 0 |
| 64 | 5 | 1 | 480 → 0 |

These counts describe this function, not total game allocations or an FPS gain.
The 37 candidate checks cover early returns, timestamp ordering/wrap, mixed
queues, polling errors/timeouts, interruption, fence ownership and heap failure.
ASan/UBSan detect the original faults and are clean for the candidate. Kernel
waits, Vulkan runtime callbacks and descriptors are test boundaries; this does
not exercise a GPU or concurrent driver execution.

Both baseline and candidate Android ARM64 drivers build with the existing Android
adaptations and NDK r27/API 34. Both have 16 KB `LOAD` alignment and the same
exported `HMI` HAL module symbol. The candidate ZIP, stripped binary, patches, inputs
and notices are outside the checkout in `../turnip-refactor-qualification/`.
Neither binary is installed, bundled or selected by Vapor.

See [build instructions](../../docs/BUILDING.md#kgsl-wait-any-source-refactor--21-october-2026),
[qualification](../../docs/QUALIFICATION.md#graphics-component-source-refactors--21-october-2026)
and [the receipt](../../docs/evidence/2026-10-21-graphics-source.json). Actual GPU
loading, rendering correctness, sustained performance, 16 KB kernel execution
and distribution review remain open. Mesa's source contribution instructions
apply to any later submission; none was made here.

## Earlier KGSL timestamp-poll experiment — 16 October 2026

This is a separate imported-driver candidate for runtime-plan step 4. It does not
change Vapor's driver selection or `TU_DEBUG` defaults. No phone performance result
is claimed.

Use Mesa commit `a51a418991f883413f10f66cd52398d590a8257b`, containing the merged
[KGSL synchronization fixes](https://gitlab.freedesktop.org/mesa/mesa/-/merge_requests/44838).
In particular, commit `e984ef294eaf5729d8434694f27de32a8922205c` replaces an
expired/zero-timeout blocking timestamp wait with a retired-timestamp poll. This
keeps DXVK's existing timeline queue behavior instead of introducing the separate
binary-semaphore fallback.

`android-driver.patch` is the consolidated Android/GPU patch set from
[WinNative-Emu/Drivers at c71f600a](https://github.com/WinNative-Emu/Drivers/tree/c71f600a2977a141f21a922c9ed2e14fb09b13d3).
It retains A810/A829's `disable_gmem`, A825's donor profile, A7xx workarounds,
Leb-Sun's IMapper/UBWC integration, the gralloc fallback change and the balanced
bandwidth tweak. It adds no maximum-clock requests. The obsolete `magic_regs`
argument/anchor was removed from the A825 applicator. The old draw-count tuning
anchor no longer exists upstream; no replacement threshold was invented.

The donor README labels the repository MIT, but its pinned root `LICENSE` contains
GPL-3.0. `DONOR-LICENSE` preserves that actual file. Mesa's per-file licenses and
the IMapper source's MIT SPDX notice are retained separately; do not describe this
entire patch set as uniformly MIT. The package also retains Mesa, dependency and
toolchain notices. A public distribution review remains separate from this local
build checkpoint.

The patch changes/adds 13 files and was replayed without fuzz onto pristine archive
inputs, reproducing the same source bytes. See [BUILDING](../../docs/BUILDING.md#android-turnip-candidate)
for the build and [QUALIFICATION](../../docs/QUALIFICATION.md#turnip-queue-audit-and-driver-candidate--16-october-2026)
for the bounded results. The development archive contains `meta.json`, the AArch64
driver, source patch, build inputs and notices. Its minimum Android API is 34.

The source regression runs Mesa's extracted functions with a mocked clock/ioctl
boundary, not a simulated replacement implementation:

```sh
python3 tools/tests/turnip-kgsl-wait.py before.cc after.cc /tmp/vapor-kgsl-wait
```

Use the file `src/freedreno/vulkan/tu_knl_kgsl.cc` from immediately before commit
`e984ef294eaf5729d8434694f27de32a8922205c` and that commit (or the pinned full-MR
source). The old input must fail and the fixed input must pass all 14 cases. The
test compiler is Clang with C++17; the narrowing diagnostic suppression matches
the C-style initializers used by these pinned sources. No real kernel wait occurs.
