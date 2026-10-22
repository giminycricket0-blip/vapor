# Target SDK upgrade: what blocks it, measured

Updated 22 October 2026. Vapor targets **SDK 28**, compiles against 35 and needs at least
Android 10 (API 29). Google Play requires a recent target API level for new apps and
updates, and the requirement rises every year
([Google's target API policy](https://developer.android.com/google/play/requirements/target-sdk)).
Direct APK installs are unaffected, but staying on 28 rules out Play and newer
platform behaviour. This page records what actually breaks above 28, measured on the
API 36 emulator, and the work needed. Nothing here changes the shipped target.

## The rule

From target SDK 29, Android's W^X policy denies `execve()` of files in an app's
writable storage. The runtime starts Wine, Box64, FEX and their launcher scripts from
that storage (`imageFs/usr/bin`, downloaded components), so a plain target bump breaks
game launch.

## Measured on Android 16 (API 36 emulator, 4 KB pages)

`ExecPolicyDeviceTest` was run with the app built at target 28 and at target 35:

| Path | Target 28 | Target 29 | Target 35 |
| --- | --- | --- | --- |
| `execve` of a binary in app data | works | **denied, EACCES** | **denied, EACCES** |
| `/system/bin/linker64 <binary in app data>` | works | works | works |
| `/system/bin/sh <script in app data>` | works | works | works |
| executable in `nativeLibraryDir` (how proot starts) | works | works | works |
| `System.load()` of a `.so` in app data | works | works | works |

W^X therefore blocks only *directly executing* app-data files. Mapping and loading code
from app data still works, which is what the wrapper, the drivers and Wine's own
libraries rely on.

## What the runtime executes

| Program | Where | Kind | Route above 28 |
| --- | --- | --- | --- |
| `wine`, `wineserver` (Proton 9.0 ARM64EC) | app data | dynamic bionic PIE, interpreter `/system/bin/linker64` | run through `linker64` |
| `wineboot`, `winecfg`, `winepath` ... | app data | `#!/bin/sh` scripts | run through `/system/bin/sh` |
| `wine-preloader` | app data | **static** AArch64 executable | cannot use `linker64`: skip the preloader or ship it in `nativeLibraryDir` |
| Box64, FEX components | downloaded into app data | per component | inspect each; dynamic bionic builds can use `linker64` |
| proot, proot-loader (Linux runtime) | `nativeLibraryDir` | packaged as `lib*.so` | already compatible |
| PulseAudio | `nativeLibraryDir` plus app-data modules | executable plus loaded modules | already compatible |

Launches go through `GuestProgramLauncherComponent` (`ProcessHelper.exec` of
`.../usr/bin/box64` or the Wine launcher), `LinuxProgramLauncherComponent`,
`PulseAudioComponent` and `ComponentInstaller`.

## The real work

Rewriting Vapor's own launch commands is not enough. Wine and the translators spawn
child processes themselves (`wineserver`, every Windows process, scripts), and each of
those `execve` calls hits the same rule. The established approach is Termux's:

1. **An exec shim.** A small `LD_PRELOAD` library, shipped in `nativeLibraryDir`,
   intercepts `execve`/`posix_spawn` in every guest process. It re-runs dynamic bionic
   executables as `linker64 <path> ...`, re-runs `#!` scripts through their interpreter,
   and leaves system binaries alone. It must preserve `argv[0]` and the environment.
2. **Static executables.** Make `wine-preloader` unnecessary for the ARM64EC build (its
   address-space reservation has to be checked), or ship it in `nativeLibraryDir`.
3. **Components.** Box64/FEX/driver packages downloaded into app data must be dynamic
   bionic executables (or shared libraries) to work through the shim; static builds
   would need repackaging. Each component in the catalog needs an inventory.
4. **Everything else at the new target.** Scoped storage (game folders on shared
   storage), foreground-service types, notification permission, exact alarms,
   package visibility and the newer intent rules. These are ordinary migration items,
   but each needs a check.

## Plan

1. Inventory the downloadable components (Box64, FEX, Wine/Proton variants, drivers):
   ELF type, interpreter, and any static executables. No phone needed.
2. Build the exec shim with a JVM/NDK test suite. On the emulator, run it against the
   real `wine`/`wineserver` and the scripts at target 35, using `ExecPolicyDeviceTest`
   as the baseline.
3. Behind a build flag, raise the target and run the existing UI and launch suites on the
   emulator. Keep 28 as the shipped default until the phone pass.
4. On an Adreno phone, confirm game launch, audio, input and Cloud saves at the new
   target before switching the default.

Steps 1–3 need no phone. Step 4 does.
