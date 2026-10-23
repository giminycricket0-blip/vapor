Vapor is a derivative of [WinNative](https://github.com/WinNative-Emu/WinNative), initially imported at `8409085f706acbc0749881d9bf1e85f2f347dfd4`. The upstream acknowledgments below are preserved. See [Vapor provenance and open distribution gates](docs/PROVENANCE.md) for this checkout's verification status.

Vapor's Steam close-upload reservations are adapted from **Jeremy Bernstein's**
[GameNative PR #1962](https://github.com/utkarshdalal/GameNative/pull/1962), merge
`0fdaf7aba375c1510bec82c142a9b6941744aec6` (GPL-3.0). The adaptation tracks both the
Android cloud worker and the existing genuine Steam host's exit cleanup. DroidDeck
[PR #529](https://github.com/Droid-Deck/DroidDeck/pull/529), merge
`eadebc46d57b6fed3bbf4a2a39edfbbe61877fa4`, informed a source audit; its code was not imported.

Vapor builds its existing **OpenSSL 1.1.1q** and **curl 7.85.0** dependencies from
upstream source. Android configure and unversioned-SONAME conventions follow
[AOSP ndkports](https://android.googlesource.com/platform/tools/ndkports/+/2e149f73a8e792fff6dc6aadc18d0d919a071282/)
(Apache-2.0 build tooling). The OpenSSL/SSLeay and curl notices are retained in
`app/src/main/assets/licenses/` and included in the APK. See
[provenance](docs/PROVENANCE.md#native-network-source-build--16-october-2026) for exact inputs.

Vapor's separate Wine X11 experiment retains three unmodified Android patches
from [GameNative/proton-wine `00095e1a`](https://github.com/GameNative/proton-wine/commit/00095e1a945e357c22a0987d70392b9b352c1749),
on the public Proton 11.0-2 base. The input correction was reproduced in Vapor's
X11 host; it is upstream work, not an original Vapor driver implementation.
The FEX cache experiment also backports the unmodified
[upstream blob-lifetime fix `b49837f0`](https://github.com/FEX-Emu/FEX/commit/b49837f0c3961194b35da2187e5f742982a0c716).
See [Wine experiment](tools/wine/README.md) and [FEX experiment](tools/fex/README.md)
for source, patch and license boundaries.

<p align="center">
  <img src="logo.png" alt="WinNative" width="360">
</p>

# Credits & Acknowledgments

WinNative is a community project built on other people's work. This page is the full
attribution for everything WinNative is built from, including the parts we did not write.
Per-component license tables for the retro-console and emulator side live in
[EMULATOR_CREDITS.md](EMULATOR_CREDITS.md).

WinNative is distributed under the **GNU General Public License v3.0** ([LICENSE](LICENSE)).
Copyright and license notices are preserved in every file that carries upstream work, and the
corresponding source for every copyleft component is available from the projects linked here.

---

## Foundations

- **Original Winlator** by [brunodev85](https://github.com/brunodev85/winlator)
- **Winlator Bionic** by [Pipetto-crypto](https://github.com/Pipetto-crypto/winlator)
- **Pluvia** features by the [Pluvia](https://github.com/oxters168/Pluvia) /
  [GameNative](https://github.com/utkarshdalal/GameNative) community
- **Mesa/Turnip** contributions by the [Mesa3D](https://www.mesa3d.org/) team
- **Goldberg Steam Emulator** by [Mr. Goldberg](https://gitlab.com/Mr_Goldberg/goldberg_emulator),
  maintained by [Detanup01](https://github.com/Detanup01/gbe_fork) — **no longer bundled or
  invoked.** The `steampipe/` payload and the code that copied it next to a game executable
  were removed on 23 October 2026; the entry is kept for the record. A save-directory path
  for games that used it during migration is still recognised.
- **Wayland display server** — the embedded Wayland compositor (`app/src/main/cpp/waylandcomp`),
  its Wayland runtime design, clipboard and IME bridges, and the winewayland launch path by
  **Banner ([The412Banner](https://github.com/The412Banner))** from
  [Bannerlator](https://github.com/The412Banner/Bannerlator), ported to WinNative; ships the
  vendored `libwayland-server` 1.26 and `libffi` from the same project. See
  [docs/WAYLAND-DISPLAY.md](docs/WAYLAND-DISPLAY.md).

## Community compatibility reports

- **EmuReady** ([emuready.com](https://www.emuready.com/)) — the community compatibility
  "cheat sheet" behind the **Community reports** action on a Steam game page, and behind the
  configuration line described in the README.

  The app reads EmuReady's public mobile API at runtime; **no EmuReady data is bundled,
  scraped into, or redistributed with Vapor**. Each report links back to its own listing on
  the service. Reports are other people's results on other hardware and other emulators, so
  they are shown as attribution and used only as a *prior*: they can reorder launch
  combinations Vapor has already judged eligible for the current device, never select one,
  and never override an explicit setting. Vapor is not affiliated with EmuReady and does not
  treat a report as a compatibility verdict.

## Retro console emulation

- **LibretroDroid** by [Filippo Scognamiglio](https://github.com/Swordfish90/LibretroDroid)
  (GPL-3.0) — the embedded libretro host for retro console support
- **libretro / RetroArch** and the individual core authors, built from source:
  [FCEUmm](https://github.com/libretro/libretro-fceumm),
  [Snes9x](https://github.com/libretro/snes9x),
  [Gambatte](https://github.com/libretro/gambatte-libretro),
  [mGBA](https://github.com/libretro/mgba),
  [Genesis Plus GX](https://github.com/libretro/Genesis-Plus-GX),
  [Mupen64Plus-Next](https://github.com/libretro/mupen64plus-libretro-nx),
  [Beetle PSX](https://github.com/libretro/beetle-psx-libretro)
- **ARMSX2** by the [ARMSX2](https://github.com/ARMSX2/ARMSX2) team (GPL-3.0) — the PlayStation 2
  core, a fork of **[PCSX2](https://github.com/pcsx2/pcsx2)** (GPL-3.0), built from source into
  `libemucore`. PS2 online play uses PCSX2's DEV9 network adapter
- **Dolphin** by the [Dolphin team](https://github.com/dolphin-emu/dolphin) (GPL-2.0-or-later) —
  the GameCube and Wii core, including its native NetPlay engine

## Frame generation

- **lsfg-vk** by [PancakeTAS](https://github.com/PancakeTAS/lsfg-vk) (GPL-3.0-or-later) — the
  original Vulkan reimplementation of the Lossless Scaling frame generation chain
- **LSFG frame generation** by **Camille LaVey** of the
  [Eden Emulator Project](https://git.eden-emu.dev/eden-emu/eden) (GPL-3.0-or-later) — the Vulkan
  port of that chain that WinNative's frame generation is derived from.
  See [what came from Camille LaVey's Eden port](#frame-generation--what-came-from-camille-laveys-eden-port)
- **OpenFlow frame generation** (DIS optical flow) by **qwertypower**
  ([DEVAR Entertainment LLC](https://devar.ai/)) (GPL-3.0) — a complete open-source implementation
  of a Dense Inverse Search frame generator, the second frame generation engine in WinNative and
  the one that needs no shaders from anywhere else.
  See [the fully open-source engine](#dis-frame-generation--the-fully-open-source-engine)
- **DIS optical flow** — the algorithm and its reference implementation come from
  [OpenCV](https://github.com/opencv/opencv) (`DISOpticalFlow`,
  [LICENSE](https://github.com/opencv/opencv/blob/5.x/LICENSE)), which adopted Till Kroeger's
  original [OF_DIS](https://github.com/tikroeger/OF_DIS)
- **DXVK** by [Philip Rebohle and contributors](https://github.com/doitsujin/dxvk) (zlib/libpng) —
  the `dxbc` shader translator, vendored at `app/src/main/cpp/thirdparty/dxbc` to convert the
  frame generation shaders to SPIR-V
- **Lossless Scaling** (Steam) — the source of the LSFG frame generation shaders. They are read
  from the user's own installed copy at runtime; none are redistributed with WinNative

## Audio

- **DirectAudio** by [The412Banner](https://github.com/The412Banner/directaudio)
  (LGPL-2.1-or-later) — the native Wine → Android AAudio audio driver, and the only audio path in
  WinNative that carries a working microphone.
  See [what came from The412Banner's driver](#directaudio--what-came-from-the412banners-driver)

---

## Frame generation — what came from Camille LaVey's Eden port

WinNative's frame generation exists because **Camille LaVey**, working in the
[Eden Emulator Project](https://git.eden-emu.dev/eden-emu/eden), had already solved the hard
part: getting the Lossless Scaling compute chain running correctly on Vulkan, on mobile GPUs.
The port here started from that work and still carries it. The Eden Emulator Project copyright
notices are preserved in every file that derives from it, under GPL-3.0-or-later.

Derived from Camille LaVey's Eden port (jointly with **[lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)**,
which that port was in turn ported from):

| Source file | What it provides |
| --- | --- |
| `lsfg_chain.*` | The shape of the whole chain — which of the 25 shaders run, in what order, and what each stage feeds the next |
| `lsfg_mipmaps.*` | The flow pyramid the rest of the chain is built on |
| `lsfg_alpha.*` | Per-level feature extraction, including the batched-barrier dispatch pattern the rest of the chain follows |
| `lsfg_beta.*` | The coarse flow estimate the refinement stages start from |
| `lsfg_gamma.*` | Coarse-to-fine flow refinement, one instance per pyramid level |
| `lsfg_delta.*` | The extra refinement and detail passes on the finest levels |
| `lsfg_generate.*` | The final warp that produces the interpolated frame |
| `lsfg_common.*` | The Vulkan plumbing all of the above sit on — image, sampler and buffer wrappers, the barrier builder, the descriptor writer, and the pass/pipeline helper |

Derived from the Eden port specifically:

| Source file | What it provides |
| --- | --- |
| `lsfg_pacer.*` | Deciding how many frames to generate per real frame |
| `lsfg_shaders.*` | Turning the extracted shader blobs into Vulkan shader modules |

Getting the descriptor layouts, barrier placement and dispatch geometry of a 25-shader chain
right is not something you arrive at by reading the shaders; it is the part that takes the
debugging. Camille LaVey did that work, and this port would not have been possible without it.

What WinNative added on top is the Windows and Android side of it: reading the shader blobs out
of a user's own Lossless Scaling install (`lsfg_dll.*`), translating them when only DXBC is
available (`lsfg_dxbc.*`), the JNI surface (`lsfg_jni.*`), driver probing (`lsfg_probe.*`), and
wiring the chain into WinNative's compositor and swapchain (`vkr_lsfg.*`).

## OpenFlow frame generation — the fully open-source engine

WinNative's second frame generator is a complete open-source implementation of **Dense Inverse
Search** optical flow, contributed by **qwertypower** (DEVAR Entertainment LLC) under GPL-3.0.

Unlike the Lossless Scaling path it depends on nothing the user has to own or install. The whole
chain ships with the APK as fourteen compute shaders and runs in the same Vulkan compositor, so
frame generation is available on a fresh install with no Steam account and no `Lossless.dll`.

The algorithm is DIS, and its reference implementation is OpenCV's `DISOpticalFlow`
([OpenCV LICENSE](https://github.com/opencv/opencv/blob/5.x/LICENSE)). OpenCV in turn adopted
Till Kroeger's original [OF_DIS](https://github.com/tikroeger/OF_DIS). What is original here is
the Vulkan compute realisation of it — the pyramid, the descriptor and barrier layout, the
sparse-to-dense step and the pacing — built to run inside a mobile compositor at frame rate.

| Stage | Shaders |
| --- | --- |
| Pyramid and gradients | `dis_gradient` |
| Patch inverse search, coarse to fine | `dis_inverse_search`, `dis_propagate` |
| Sparse grid to dense flow | `dis_densify` |
| Variational refinement | `dis_vr_prep`, `dis_vr_d1`, `dis_vr_d2`, `dis_vr_w`, `dis_vr_coef`, `dis_vr_sor`, `dis_vr_add` |
| Warp to the in-between frame | `dis_interpolate` |

The engine sits behind its own settings — a Fast / Balance / Quality flow resolution and an
optional target frame rate — kept separate from the Lossless Scaling ones, and the two engines
are mutually exclusive because the compositor drives one interpolator per frame.

## DirectAudio — what came from The412Banner's driver

WinNative's microphone support exists because **The412Banner** wrote
**[DirectAudio](https://github.com/The412Banner/directaudio)**, a native Wine → Android AAudio
mmdevapi driver, and then completed its capture half. Nothing in that driver is WinNative's
work. We ship his release binaries unmodified and add only the host-side plumbing that selects
and configures them.

Wine has no AAudio backend upstream. DirectAudio is an original driver whose structure is
modelled on Wine's `winecoreaudio.drv`, with the CoreAudio device layer replaced by Android
AAudio — no PulseAudio daemon and no ALSA server anywhere in the path. That is also the reason
it is the only one of WinNative's three audio stacks that can carry a microphone at all: the
ALSA aserver protocol has no capture verb (its guest plugin refuses a non-playback PCM
outright), and the bundled PulseAudio ships no source module, so any capture endpoint winepulse
advertises would record silence.

| Component | What it provides |
| --- | --- |
| `winedirectaudio.drv` (arm64ec + i386 PE) | The mmdevapi driver the guest game loads |
| `winedirectaudio.so` (bionic unixlib) | The AAudio backend both PE halves share |
| Render mixer | One shared AAudio output stream, per-voice mixing, adaptive buffering with decay, and a dead-callback watchdog |
| Capture path *(v1.3.2)* | One shared AAudio `INPUT` stream (48 kHz / float / stereo, `VOICE_COMMUNICATION` preset for platform echo-cancel, noise-suppress and auto-gain), opened lazily on the first capture stream and started only on the first `Start` |

What WinNative added is only the host half: picking the driver build that matches the
container's Wine ABI and the device's kernel page size, overlaying the three files onto the
Proton layer, writing the Wine registry key that selects the driver, requesting `RECORD_AUDIO`,
and exposing the microphone opt-in per container and per shortcut.

The microphone is **off by default and per-container**, which is the driver's own design rather
than caution on our part: a capture endpoint a game can enumerate but not open makes titles that
probe the microphone while loading abandon audio init and boot to a black screen (God of War and
DiRT 3, device-proven upstream). With the gate off the driver is byte-identical to its
render-only build. WinNative additionally withholds the gate when `RECORD_AUDIO` has not been
granted, so a denied permission degrades to render-only rather than to an unopenable endpoint.

DirectAudio is licensed **LGPL-2.1-or-later** and remains so. The bundled binaries, their
checksums, the upstream source offer and the verbatim `COPYING`, `NOTICE` and `AUTHORS` files
live in [`app/src/main/assets/directaudio/`](app/src/main/assets/directaudio/). Integration
notes are in [`docs/direct-audio-integration.md`](docs/direct-audio-integration.md).

> DirectAudio by The412Banner (https://github.com/The412Banner/directaudio)

#### Steam Controller — what came from The412Banner's Bannerlator

WinNative's Steam Controller support, the animated controller test panel and the tap-the-pad
visual binder are **The412Banner's work**, written for
**[Bannerlator](https://github.com/The412Banner/Bannerlator)** and ported here. The design, the
SDL3 bridge, the input model, the pad artwork and the hotspot manifest are all his; WinNative's
own part is the adaptation to this fork's input plumbing (`WinHandler`'s slot machinery, the
`InputControlsView` binding lane and the Compose settings screen) plus full localization.

Out of the box a Steam Controller is not a gamepad: it presents as a keyboard and mouse
("lizard mode"), so Android never reports a controller and WinNative's normal `InputDevice` path
cannot see it. SDL3's HIDAPI Steam drivers speak Valve's protocol instead — over Bluetooth LE or
USB — keep lizard mode off and parse the reports. Only those drivers are enabled
(`SDL_HINT_JOYSTICK_HIDAPI=0`, `SDL_HINT_JOYSTICK_HIDAPI_STEAM=1`), so SDL never opens, claims or
requests USB permission for any other controller, and every non-Valve pad keeps the untouched
Android input path.

| Piece | What it does |
| --- | --- |
| `steamctrl/steam_controller_bridge.cpp` | JNI bridge over SDL3's gamepad API: flat int/float arrays for up to four Valve HIDAPI pads, plus rumble |
| `SteamControllerBackend` | ~250 Hz poll thread, ordered frame delivery onto the main thread, the 22-bit button model, trackpad-as-mouse and the back-paddle / "…" bindings |
| `WinHandler` SDL pads | Seats each pad in the ordinary player-slot machinery under a synthetic device id, shadows the Valve device Android also exposes, and routes rumble back through SDL |
| `ControllerTestPanel` | The animated pad picture: live button glow, movable stick nubs that glow with deflection, trigger and battery readouts, and the per-device inputs-verified tally, including paddles and touchpads |
| `VisualControllerBinder` | Tap a button on the pad art to rebind it, written into the same profile store the list editor uses |
| `pad_*.png` | The per-family controller artwork (Xbox 360 / Xbox / DualSense / DualShock 4 / DualShock 3 / Switch Pro / 8BitDo / GameCube / SNES / Steam / Generic) |

Support is **off by default** and lives in *Settings → Input Controls → Steam Controller*. While
it is off, SDL is never loaded and the normal controller path is byte-for-byte unchanged.

Credit history — every commit below is The412Banner's, in
[The412Banner/Bannerlator](https://github.com/The412Banner/Bannerlator):

| Commit | Date | Subject |
| --- | --- | --- |
| `9ca9bcf` | 2026-08-31 | Controller Test: reuse the visualizer in Settings > Input Controls |
| `d32a03f` | 2026-08-31 | Controller Test: accurate per-family pad art + smaller in-game popup |
| `1678f23` | 2026-08-31 | Controller Test: bound the pad picture to a compact size |
| `4344ef4` | 2026-08-31 | Visual controller binder: tap the pad to remap, in Settings + in-game |
| `475721e` | 2026-08-31 | Controller pads: pre-rendered PNG art + overlay; Settings test-first; fix live-input arm |
| `8d2e1e3` | 2026-08-31 | Controller test: movable stick nubs + pinned footer (Identify never clips) |
| `90a8286` | 2026-08-31 | Controller test: stick nubs glow on deflection + landscape pad-left / metrics-right |
| `1e0d46f` | 2026-08-31 | Controller test: landscape metrics as a 2x2 grid so all four tiles fit |
| `9722956` | 2026-08-31 | feat(controller-test): independent OSC/physical profile lanes + live profile lists |
| `095269d` | 2026-08-31 | Bind screen: add Delete profile + outline/dividers on menus and popup |
| `b02d981` | 2026-08-31 | Controller test: unify popup-menu styling on outlinedMenuCard; rename Settings card |
| `b9e6552` | 2026-09-10 | Steam Controller support via SDL3 (opt-in, Input Controls > Device) |
| `e5b1272` | 2026-09-10 | Steam Controller: fetch pad name/path on the poll thread |
| `6d3b2a7` | 2026-09-10 | Steam Controller: back buttons, profile bindings, settings test dialog |
| `d03f6b1` | 2026-09-10 | Steam Controller: make the "…" (Quick Access) button mappable |
| `3d243e4` | 2026-09-10 | Controller test: count the Steam Controller's "…" button (18 inputs) |
| `b597711` | 2026-09-10 | Steam Controller: pick which trackpad moves the mouse (right / left / both / off) |
| `c2acbbd` | 2026-09-10 | Merge branch 'feat/steam-controller-sdl': opt-in Steam Controller support via SDL3 |

Bannerlator is GPL-3.0, the same license as WinNative, and remains so. SDL3 is Zlib-licensed and
uses a separate Java/JNI namespace and native library for this integration, with a Bluetooth transport patch; see [`vendor/maven/README.md`](vendor/maven/README.md) and
[`EMULATOR_CREDITS.md`](EMULATOR_CREDITS.md).

> Steam Controller support, controller test and visual binder by The412Banner
> (https://github.com/The412Banner/Bannerlator)

