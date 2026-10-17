# Android Turnip experiment

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
