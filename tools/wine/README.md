# Wine Android X11 component experiment

This directory retains three **unmodified upstream patches** from
[GameNative/proton-wine commit 00095e1a](https://github.com/GameNative/proton-wine/commit/00095e1a945e357c22a0987d70392b9b352c1749).
They belong to Wine's existing X11 driver, whose source retains its upstream
LGPL notices. They are not compiled by Gradle or activated in guided setup.

| Patch | Purpose |
| --- | --- |
| `0001-disable-cross-process-vulkan-default.patch` | Disable other-process Vulkan rendering by default on Android, retaining the environment override. |
| `0002-avoid-unchanged-clip-regrab.patch` | Avoid regrabbing an unchanged clipping rectangle. |
| `0003-grab-foreground-window.patch` | Deliver pointer-grab events through the foreground window while keeping the separate confinement window. |

The fourth upstream `_NET_WM_HWND` patch is omitted: the identified base already
sets the property at window creation. All **45 Android patches named by the base
ARM64EC build recipe** pass reverse-application checks against that base. Do not
apply those adaptations twice. This does not establish the complete patch set of
the downloaded `steam-unixlib-3` package.

## Inputs and build

Use an isolated source/build directory. The tested source base is
`71040f04bd3a451fd2c5d2552f0b64a23dd7c69b`, the public Proton 11.0-2 merge in
[GameNative/proton-wine](https://github.com/GameNative/proton-wine/tree/71040f04bd3a451fd2c5d2552f0b64a23dd7c69b).
The embedded version in the working WCP identifies this base, not every later
package change. Required inputs:

- Android NDK **27.3.13750724**, using the host's correct `prebuilt` directory and
  `aarch64-linux-android28-clang`.
- **bylaws LLVM-MinGW 20250920 UCRT / Clang 21.1.0**, as in the FEX experiment.
- Bison **3.8.2**, Flex, Autoconf, pkg-config, make and a native C compiler. This
  Mac's system Bison 2.3 is too old; the local build used a task-local Bison.
- The recipe's [Termux dependency archive](https://github.com/WinNative-Emu/proton-wine/releases/download/Assets/termuxfs-aarch64.tar),
  **1,190,645,760 bytes**, SHA-256
  `b088df560c9395d7c2cc797d208d8a727d1f06fc70e5ea34eef6118ef64a7e42`.
  Extract its `data/data/com.termux/files/usr/include`, `lib` and `share/pkgconfig`
  trees into the isolated dependency root; the full archive contains unrelated
  absolute manpage symlinks that are not needed by this build.

Patch hashes, source/archive hashes, the actual configure arguments, build-tool
versions and output identities are in the
[19 October receipt](../../docs/evidence/2026-10-19-runtime-components.json).
The source-archive hash was observed locally; the dependency-archive hash also
matches its release digest.

Apply the three patches in order with `git apply --check` followed by `git apply`.
Run the source's `autogen.sh`, using its local `vk.xml` / `video.xml`. Do not add
`tools/make_makefiles`: that extra maintenance script fails on this fork's existing
`amd_ags_x64` IDL metadata and is not part of its `autogen.sh` recipe.

Build the native Wine tools out of tree with the MinGW tools and modern Bison on
PATH:

```sh
mkdir wine-host-tools
cd wine-host-tools
CC=clang CXX=clang++ ../configure --without-x --without-gstreamer --without-vulkan --without-wayland
make -j2 __tooldeps__ nls/all
```

For the Android build, follow the pinned `build-scripts/build-step-arm64ec.sh`
configure options, resolving its Linux-specific tool paths to the actual host.
Keep all output/install directories inside the task workspace. Build the source's
`android/android_sysvshm/android_sysvshm.c` shared library into the dependency
directory first. The tested cross build uses:

- `--enable-archs=arm64ec,aarch64,i386`, `--host=aarch64-linux-android28`, and
  `--with-wine-tools` pointing at the native tools above.
- The recipe's include/library flags for X11, PulseAudio, SDL, FreeType,
  Fontconfig and GStreamer. Its `--without-ffmpeg` remains unchanged; this is
  not a codec upgrade.
- Explicit `READELF` pointing to NDK `llvm-readelf`, explicit `PKG_CONFIG`, and
  `PKG_CONFIG_SYSROOT_DIR` pointing to the extracted archive root. Otherwise
  successful EGL/GLES links can be misclassified when SONAME inspection fails.
- `X_CFLAGS=-I<dependency-prefix>/include`, **not** its `X11` subdirectory.
  On a case-insensitive macOS volume, the latter makes `<xlocale.h>` resolve
  to `X11/Xlocale.h` and breaks Android's `locale_t` declarations. Set this
  before configure: changing a make variable does not update the generated rules.
- `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` for this experimental
  module and its locally built native dependencies.

After cross-configure succeeds, build only the intended module and dependencies:

```sh
make -j2 dlls/winex11.drv/winex11.so
```

The resulting `winex11.so`, `ntdll.so` and `win32u.so` are AArch64 ELF libraries
with 16 KB LOAD alignment. This is a **partial Wine source build**, not a complete
Wine WCP or a qualification of 16 KB runtime execution. Do not replace the normal
package or copy all three modules into a game prefix based on this result.

## External-pointer regression

Build the small [Windows fixture](../tests/wine-input-smoke.c) in both widths:

```sh
for arch in x86_64 i686; do
  "$arch-w64-mingw32-clang" -std=c11 -O2 -Wall -Wextra -Werror -mwindows \
    tools/tests/wine-input-smoke.c -luser32 -o "wine-input-$arch.exe"
done
```

Use the existing `boot_exe` path on an isolated, account-free prepared emulator.
Preserve the container, registry and actual Wine module bytes/modes first.
Compare **only** `winex11.so`; keep the working native dependencies and all other
runtime settings fixed. At each `phase=... ready` marker, send five complete
external Android pointer clicks through the production display host. Do not use
`SendInput` or post Windows button messages, which would bypass the boundary.

Require unclipped and released phases to pass before interpreting clipped failures.
The GUI subsystem is necessary: a console build can take focus and invalidate
the comparison. The first console-based harness attempt was excluded for that reason.

On 19 October, both x64 and x86 original-module controls delivered all five clicks
before/after clipping and **zero** during either clipping phase. The rebuilt
module delivered all five complete clicks in **all eight phases** across the two
architectures. This qualifies the combined patch set for this observed input
regression; it does not isolate each patch's contribution or test the Vulkan policy.
No real game, Steam session, physical controller or new APK was checked.

Restore the original module, registry and container bytes/modes and remove only
test-owned files. The recorded experiment did so and stopped its headless AVD.
Full package/Steam ABI validation, fullscreen/Vulkan game checks, source mapping
of every packaged change and phone acceptance remain required before promotion.
