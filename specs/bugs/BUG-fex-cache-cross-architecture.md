# FEX Windows cache eviction between architectures

Status: reproduced on the pinned FEX 2610 Unix package, then fixed and locally
checked with rebuilt ARM64EC/WOW64 DLLs in the isolated ARM64 Android 16/API 36,
4 KB AVD. The experimental package is separate from guided preparation; caching
remains off by default. Phone and mixed-child-process qualification remain open.

## Reproduce

Use `tools/tests/fex-smoke.c`, compiled for i686 and x86_64, through the production
Wine/FEX launcher. Enable `DiskCache` only for the two probe executables with the
existing FEX `AppOverrides` configuration. Give both the same dedicated cache base.
Leave pruning enabled, extra RAM cache disabled, and all Wine/driver/preset choices
fixed. Run x64 twice, then x86 twice, then x64 again.

The first x64 cache uses machine bucket `8e3ad3e52d2cf324`; the x86 run deletes it
and creates `6dfd13689411385b`. Returning to x64 deletes the x86 bucket. These hash
values describe this emulator, not universal bucket names.

IR tracing records 84 unique compiled entrypoints on the initial x64 run, one on
its warm run, then 84 again after x86. The x86 cold/warm runs record 687/10. All
program checks pass. These are unique IR dump filenames, not a hit counter, total
compilation count, benchmark or game test.

## Isolate

Source revision: `14c92681f4d62cf84d901460e0358de09c8847a7`.

- `Source/Windows/Common/CPUFeatures.cpp:FetchHostFeatures` explicitly clears
  `SupportsAVX` for WOW64.
- `FEXCore/include/FEXCore/Core/HostFeatures.h:HashForCaching` includes that bit
  in the machine feature mask.
- The two real cache metadata records have identical format version 30 and feature
  masks differing by exactly `0x1000`, the `SupportsAVX` bit.
- `FEXCore/Source/Interface/Core/DiskCache.cpp:PruneStaleEntries` deletes every
  directory whose name differs from the current machine bucket.

This reproduces a cleanup problem, not incorrect guest-code reuse. A separate
fixture update with unchanged PE timestamp, SizeOfImage and filename returned the
new arithmetic value, and restoring its original code returned the original value.

## Prior art and decision

Extend FEX's existing cache owner. Its release-pinned `DiskCache.cpp` already owns
lookup, guest-code validation, file limits and stale pruning; Windows
`CPUFeatures.cpp` explains the differing host mask. Reuse the pinned
[WinNative Components build recipe](https://github.com/nicholasx417/WinNative-Components/blob/ce9f4d538c57ffae17ebafa9d6087dc377e0c11e/.github/workflows/FEXCore-unix-nightly.yml).
An Android directory choice based on only the first EXE would miss child processes
of another architecture. Disabling cleanup would leave the reproduced design error
in place and lose its storage behavior. No new cache implementation is needed.

## Runtime fix

Extend the existing FEX cache initialization to append `arm64ec/` or `wow64/`
before machine-bucket pruning. Keep guest/configuration validation and pruning
inside each frontend directory. The patch lives in
`tools/fex/patches/0001-scope-windows-disk-cache-pruning.patch`.

Manual per-probe paths first isolated the problem. Both patched Wine DLLs were then
built with the upstream compiler family and recipe. The separate component
`FEXCore-2610-14c92681f-vapor-cache-unix-0` automatically creates both frontend
directories from one shared base. Its x64 cold/warm/after-x86 counts are 84/16/2;
x86 cold/warm/after-x64 counts are 694/6/6. All six console runs pass, both real
cache directories persist, and stale test directories are removed only by their
own frontend. Applied DLL/Unix-library hashes were checked during execution.

See [build and repeat instructions](../../tools/fex/README.md) and the
[qualification record](../../docs/QUALIFICATION.md#fex-disk-cache-experiment--16-october-2026).
Keep caching off by default pending mixed-child behavior, old/new runtime switching
against the same cache root, and matched phone game measurements. This result fixes
the reproduced eviction in this fixture; it is not a game-speed or Steam-host test.

No issue, comment, patch or pull request has been sent upstream.
