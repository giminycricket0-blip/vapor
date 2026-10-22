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

### Component inventory (22 October 2026)

The components the guided setup and Prepare Vapor download from WinNative-Components,
classified from the actual release assets:

| Component | Native code | Executables | W^X route |
| --- | --- | --- | --- |
| Proton 11.0-2 ARM64EC (Prepare Vapor runtime) | 31 shared libraries | `wine`, `wineserver`: dynamic bionic (`linker64`); `wine-preloader`: static | `linker64` for both; preloader needs a decision |
| Proton 9.0 ARM64EC (bundled) | shared libraries | same three as above | same |
| Box64 0.4.2 | — | `box64`: dynamic bionic (`linker64`) | `linker64` |
| FEXCore 2610 unix | `libarm64ecfex.so`, `libwow64fex.so` | none (loaded by Wine) | unaffected |
| FEX 2605, Wowbox64 | Windows DLLs only | none | unaffected |
| DXVK, VKD3D-Proton | Windows DLLs only | none | unaffected |

The only executable the `linker64` route cannot run is the static `wine-preloader`. The
other static or non-bionic risk is any future component built that way. The inventory
step of the plan below is therefore done for the guided-setup set; the user-installable
catalog still needs the same check.

Launches go through `GuestProgramLauncherComponent` (`ProcessHelper.exec` of
`.../usr/bin/box64` or the Wine launcher), `LinuxProgramLauncherComponent`,
`PulseAudioComponent` and `ComponentInstaller`.

## The exec shim (built and measured, 22 October)

`libvapor_exec.so` (`app/src/main/cpp/vapor-exec`, 12.5 KB in `nativeLibraryDir`, 16 KB
aligned) is the `LD_PRELOAD` shim from the plan. It is packaged but **not preloaded
anywhere yet**, so shipped behaviour is unchanged.

- It calls the real `execve` first and reroutes only on `EACCES`, so wherever direct exec
  works (target 28 today) it changes nothing.
- Denied dynamic bionic executables run as `/system/bin/linker64 <absolute path> args`
  (the linker rejects relative paths). `#!` scripts run through their interpreter,
  which is itself rerouted if it lives in app storage. Static executables keep the
  original error.
- It covers `execve`, `execv`, `execvp`, `execvpe`, `posix_spawn` and `posix_spawnp`.
- Under the linker route `/proc/self/exe` would name the linker, and `wineserver` uses it to
  find its NLS data. The shim passes the real path to the child in `VAPOR_EXEC_SELF`. Inside
  the child it answers `readlink`/`readlinkat`/`realpath("/proc/self/exe")` (and their
  fortified `_chk` forms) with it. The variable is adopted only when the real executable is
  the system linker (`/system/bin/linker64` or its APEX target), and is removed so
  children that run directly do not inherit it. The shim is built without
  `_FORTIFY_SOURCE` because it defines the `_chk` entry points itself; an early build
  that did not recursed forever.

`ExecShimDeviceTest` passes 8/8 at target 35 and at target 28. It covers:
- denied without the shim, running with it;
- relative paths and PATH lookup;
- scripts, including a script whose interpreter is in app storage;
- `/proc/self/exe` reporting the program, with no leaked variable;
- static executables staying denied.

**Real Wine through the shim.** Proton 11.0-2 ARM64EC was staged in app storage and run
from the app process at target 35. The `run-as` domain is not subject to W^X, so it was
used only as a direct-exec baseline.

| Stage | Direct exec (baseline) | Target 35 without shim | Target 35 with shim |
| --- | --- | --- | --- |
| `wineserver --version` | Wine 11.0 | denied (126) | Wine 11.0 |
| `wine --version` | runs | denied | runs |
| loader finds its own install | yes | — | yes (`.../files/proton/lib/wine/...`) |
| `wineserver` loads its NLS data | yes | — | yes, after the `/proc/self/exe` emulation (failed before it) |
| prefix registry written | yes | — | yes (`system.reg`, `user.reg`, `userdef.reg`) |
| `wine cmd /c echo` | stops at `kernel32.dll` (c0000135) | — | stops at the same point |

The shim path now matches direct execution as far as this ad-hoc staging reaches. The
`kernel32.dll` stop comes from the missing container setup (prefix template, ARM64EC
environment), not the shim. A full game launch needs the shim wired into the launcher
with a prepared container. [Shim receipt](evidence/2026-10-22-exec-shim.json).

## End to end in the real app (22 October)

`ExecShim` now feeds the shim into the guest launchers:
- the game/Wine launch environment (`GuestProgramLauncherComponent`, both preload sites);
- the app's own first exec (`ProcessHelper`, `execShellCommand`, `cabextract` in
  `ComponentInstaller`).

`LD_PRELOAD` cannot help that first exec, because the shim only exists inside the new
process. So above target 28 those launches start as
`/system/bin/sh -c 'exec "$0" "$@"' <program> args`: `sh` may run, loads the shim, and
its exec of Wine is rerouted. At target 28 both steps return their input unchanged.

On the API 36 emulator, a clean install ran Prepare Vapor and then booted the default
container's Wine desktop (`XServerDisplayActivity`, `container_id=1`):

| Run | Result |
| --- | --- |
| target 35, before the first-exec wrapping | `wine` denied by the app's own exec (`error=13`) |
| target 35, shim wired in | the Wine process tree starts; Windows processes run as `/system/bin/linker64 C:\windows\system32\winedevice.exe`; Proton logs 956 handled executable-memory remaps under W^X (`mprotect_exec ... anon-remap success`) |
| target 35 (3 runs) | the explorer desktop logs "no driver could be loaded" and "The explorer process failed to start"; the session ends at once |
| target 28, no or minimal logging (4 runs) | session stays up (13–14 Wine/Windows processes) |
| target 28 without `wine-preloader` (`WINELOADERNOEXEC=1`, 2 runs) | session stays up, so the static preloader is not needed here |
| target 28 with heavy `WINEDEBUG` | **the same** "no driver could be loaded" failure as target 35 |

**Conclusion so far.** The shim and launcher wiring work: Wine and its child processes
start at target 35. The remaining failure has the same signature as a target-28 run
slowed down by heavy logging. That points at a startup timing race in the explorer
desktop's display-driver handshake, which W^X's extra startup cost (the executable-memory
remaps) is enough to trigger. Netlink route `bind` (EACCES) appears at both targets and
is not the cause.

Follow-up checks at target 35:
- **No crash record.** There are no fatal signals, tombstones or SELinux denials in
  logcat, so the explorer desktop process exits quietly. Its last output is X11's
  OpenGL initialisation; at target 28 that same warning appears and the session
  survives.
- **Software OpenGL is not an escape hatch.** `GALLIUM_DRIVER=llvmpipe` and
  `MESA_LOADER_DRIVER_OVERRIDE=llvmpipe` are ignored (Zink is still chosen).
- **The failure is early.** The session's main process ends about 90 ms after it starts,
  which suggests the explorer errors happen during launch preparation (the `wineboot`
  steps) rather than in the main command.

**Next:** instrument Wine's process creation and the desktop's driver registration (the
`GraphicsDriver`/`DriverError` values explorer writes) at both targets, ideally with a
timestamped trace that does not slow start-up the way `WINEDEBUG` does.

## Resolved: the "explorer failed" launches were not a target-SDK problem (22 October)

Bisecting the target (29, 32, 35) and repeating fresh install cycles showed the failure
did not follow the target at all. It was an intermittent **first-launch** failure in the
shipped app, present at target 28 without the shim:

| Fresh install, first launch | Before the fix | After the fix |
| --- | --- | --- |
| target 28 (shipped, no shim) | failed 2/2 in one session, passed 1/1 in another | **3/3 up** |
| target 35 (shim) | failed 4, passed 4 across sessions | **1/1 up** |
| second launches, any target | always up | always up (4/4) |

The first launch after Prepare Vapor runs a prefix update (`wineboot -h`). When Wine
processes were still alive 30 s later, the launcher only sent SIGTERM and continued.
`winedevice.exe` survives SIGTERM, so the session's `wine explorer /desktop` started
beside the dying prefix-update session and exited at once, logging "The explorer
process failed to start". The launcher now waits for those processes to exit and
force-kills after 5 s (`terminateSessionProcessesAndWait`). The heavy-`WINEDEBUG`
"reproduction" at 28 was the same race, made more likely by slower start-up.

With that fixed, the shim path at target 35 boots the container's Wine desktop on
first and second launches (21–22 and 12–13 Wine/Windows processes). The shim gained
a diagnostic switch, `VAPOR_EXEC_FORCE=1`, which takes the linker route even where
direct exec works. With it, target 28 ran the whole session through the linker
(13 processes) once the race was out of the way.

## A Windows program runs at the new target (22 October)

With the first-launch race fixed, the container session at target 35 runs Windows
programs, not just the desktop:

```
linker64 C:\windows\system32\notepad.exe          <- a real Windows PE
linker64 C:\windows\system32\services.exe
linker64 C:\windows\system32\winedevice.exe
linker64 C:\windows\system32\svchost.exe -k LocalServiceNetworkRestricted
linker64 C:\windows\system32\explorer.exe /desktop=shell,1280x720 winhandler.exe "C:\windows\system32\notepad.exe"
```

`notepad.exe` runs as its own process through the shim's linker route and stayed up
for 150 s with no session teardown. The whole 202-test instrumented suite
(UI screens, settings, onboarding, game menu, plus the shim, exec-policy and wrapper
package tests) passes at target 35 with the same count as at target 28. The manifest
already carries the target-34 foreground-service types (`dataSync`, `specialUse`) and
`POST_NOTIFICATIONS`; `ChatOverlayService` never calls `startForeground`, so it needs
no type.

**Methodology note.** An earlier run appeared to show `boot_exe` losing its
backslashes (`C:windowssystem32notepad.exe`). It was a test-harness artefact: adb
hands `shell` arguments to the device's `sh`, which eats unquoted backslashes. The
app, `splitCommand` and the shim's `sh -c 'exec "$0" "$@"'` wrapper all preserve them
exactly. Pass Windows paths through a device-side script (single-quoted) when driving
these intents.

[Run receipt](evidence/2026-10-22-target35-windows-run.json).

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

1. Inventory the downloadable components: **done for the guided-setup set** (table
   above). Extend it to the rest of the user-installable catalog. No phone needed.
2. Build the exec shim and wire it into the launchers: **done** (above). At target 35 the
   real app boots the container's Wine desktop (after fixing a first-launch race that
   also affected target 28). `wine-preloader` is not needed for the container session.
   A Windows program (notepad) now runs at the new target and the whole instrumented
   suite passes there. Next: the remaining migration items below, then the phone pass.
3. Behind a build flag, raise the target and run the existing UI and launch suites on the
   emulator. Keep 28 as the shipped default until the phone pass.
4. On an Adreno phone, confirm game launch, audio, input and Cloud saves at the new
   target before switching the default.

Steps 1–3 need no phone. Step 4 does.
