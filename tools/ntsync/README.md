# Android ntsync investigation

This experiment does **not** enable ntsync in Vapor or replace its current Wine
component. Step 5 remains gated by process-death recovery and a matched Wine build.

The inspected library is
[GameNative/ntsync-android at 7ce6435e](https://github.com/GameNative/ntsync-android/tree/7ce6435e5979b1cb5341aa4b299f31e8937fe121),
also the observed head of its joshuatam upstream. It is LGPL-3.0-only; the original
notice is preserved in `DONOR-LICENSE`. Its Rust dependency is locked to
`libc 0.2.189`. The native C ABI is intended for a matched Bionic ntdll/wineserver,
not a Windows DLL to copy into a prefix.

## Observed failure and bounded patch

The donor assumes Android exports `pthread_mutexattr_setrobust` and
`pthread_mutex_consistent`. It substitutes successful no-ops when they are absent.
Initialization then succeeds with an ordinary shared mutex. If a process exits
while owning the shared-region lock, other processes can remain stuck on it.

[Android's Bionic status](https://android.googlesource.com/platform/bionic/+/main/docs/status.md)
lists robust mutexes as missing. On the isolated API-36 ARM64 emulator, under the
Vapor app UID, both symbols are absent. A child acquired the actual library region
lock and exited without dropping it. A subsequent bounded lock attempt returned
`ETIMEDOUT` (110), not `EOWNERDEAD` (130). This is an observed process-death failure,
not a claim about every vendor libc or the cause of Vapor's earlier Cloud issues.

`patches/0001-require-recoverable-region-mutex.patch` rejects initialization with
`ENOSYS` before opening/mapping a region when either API is absent. The fallback
functions also return `ENOSYS` rather than pretending success. It adds two focused
tests: initialization must require the APIs, and a backend must either recover
after owner death or refuse initialization. On this Android environment the latter
test passes through **refusal**, with recovery explicitly unexercised.

This patch prevents the unsupported backend from being accepted. It does not
implement a recoverable Android mutex, repair an already-poisoned region, or make
the donor backend suitable for production. No new shared-memory format or runtime
environment option is added.

## What ran

- Unmodified upstream: 15 functional tests pass; 13 benchmark/worker tests remain
  ignored. The added owner-death case fails, and the added initialization case
  demonstrates false success without the patch.
- [The C-ABI probe](../tests/ntsync-abi.c) performs an independent `exec` in a
  second process with a different `TMPDIR`. The stock library maps the same
  device/inode/path in both processes via explicit `NTSYNC_SHM`, and a semaphore
  handoff succeeds. This is library-level evidence, not a Wine or Steam session.
- The guarded build returns `-ENOSYS` consistently for initialization and object
  creation, preserves the output handle on failure and creates no region file.
- The existing [Windows probe](../tests/fex-smoke.c) now accepts `--sync`. Its
  eight extra scenarios pass in both x86 and x64 on the current Wine/FEX baseline:
  auto/manual events, wait-any, atomic wait-all, recursive mutexes, ordinary and
  forced process abandonment, and suspend/resume. These runs do not use this new
  ntsync library and do not establish a performance comparison.

## Wine boundary

The public integration is
[GameNative/proton-wine d13b1d7e](https://github.com/GameNative/proton-wine/tree/d13b1d7efcadeda19341252a4406471a4bc513ca).
Its vendored public header matches the library header exactly. Its real
`server/inproc_sync.c` patch applies without fuzz in the isolated source fixture.
The build statically links the matching library into both ntdll and wineserver.
All Wine clients, including the on-demand genuine Steam host, must share one region.

The server advertises userspace handles through `NTSYNC_ANDROID_USED_BY_SERVER`
only after successful initialization. If the kernel device is unavailable and
the library rejects initialization, the inspected source chooses fsync when
enabled, otherwise server-side synchronization. That is **not automatic esync
fallback**. Thread abandonment and process cleanup also require the matching
server patches. No matched Wine build or Steam handoff was executed here.

See [BUILDING](../../docs/BUILDING.md#android-ntsync-library-experiment) and the
[qualification record](../../docs/QUALIFICATION.md#android-ntsync-recovery-gate--16-october-2026).
