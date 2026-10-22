<div align="center">
  <img src="docs/images/vapor-icon.svg" alt="Vapor mechanical V logo" width="104" height="104">
  <h1>Vapor</h1>
  <p><strong>Your PC games. In your hands.</strong></p>
  <p>A native Android launcher for downloading and running compatible PC games locally.</p>
  <p>
    <a href="LICENSE"><img alt="Application source: GPL-3.0" src="https://img.shields.io/badge/source-GPL--3.0-66c0f4?style=flat-square&amp;labelColor=1b2838"></a>
    <img alt="Android ARM64" src="https://img.shields.io/badge/Android-ARM64-66c0f4?style=flat-square&amp;labelColor=1b2838">
    <img alt="Development build" src="https://img.shields.io/badge/status-development-e5b567?style=flat-square&amp;labelColor=1b2838">
  </p>
  <p>
    <a href="#the-experience">The experience</a> &nbsp;·&nbsp;
    <a href="#drivers-runtimes-and-automatic-graphics">Drivers &amp; runtimes</a> &nbsp;·&nbsp;
    <a href="#compatibility-and-status">Compatibility</a> &nbsp;·&nbsp;
    <a href="docs/BUILDING.md">Build</a> &nbsp;·&nbsp;
    <a href="docs/ROADMAP.md">Roadmap</a>
  </p>
</div>

![Vapor Home: a cover shelf with Steam artwork, the selected game highlighted, and an Open game action](docs/images/ui-2026-10-22-home-landscape.png)

<p align="center"><sub>Native Android interface · Home with public Steam artwork and sample installs, no account connected</sub></p>

Connect Steam, choose a game, and play what your device can handle. Vapor pairs a Steam / Big Picture-inspired interface with local Windows-game execution through Wine/Proton, FEX or Box64 CPU translation, and your choice of Vulkan drivers: system, bundled wrappers or custom Turnip. Graphics selection can be automatic. Snapdragon devices are the first qualification target.

> **In active development.** Local phone gameplay is demonstrated; compatibility and Steam feature qualification continue. No public alpha or official release APK is designated yet. See [current evidence](docs/QUALIFICATION.md) before treating a game or device as supported.

## The experience

- **Steam at the centre.** Home, Library, Store, and Downloads form the main navigation. Search the Steam catalog, browse categories, and manage owned games. Additional stores remain in **Settings → More stores**.
- **A native game library.** Cover shelves, controller focus, portrait and landscape layouts, and a game page that keeps Play, Settings, Cloud Saves, and supported Achievements close at hand. Maintenance tools live in Manage.
- **Community compatibility reports.** Steam game pages can read EmuReady reports in a native modal, with device, emulator and reported settings. Console ports are filtered out, title-only matches are labeled, known non-Windows entries hide the action, and external responses are never treated as compatibility verdicts. Reports are informational; applying settings automatically is not implemented. [Integration and evidence](docs/QUALIFICATION.md#emuready-community-reports--21-october-2026).
- **Direct downloads.** The connected Steam account supplies licenses, manifests, and game depots. Owned paid games, free/demo acquisition, and checkout are separate actions; public catalog information never grants ownership.
- **Graphics that pick themselves.** Guided Steam launches probe your real Vulkan driver and choose an eligible driver/translator combination. They retry a few known start-up errors (visibly, at most four times) and remember what worked for each game. Your explicit per-game settings always win. [How it works](#drivers-runtimes-and-automatic-graphics).
- **Your drivers, your runtime.** Use system Vulkan, one of three bundled Vulkan wrappers (plus an opt-in experimental build with Vapor's own fixes), or custom Turnip drivers (downloaded in **Settings → Drivers** or imported, then validated before launch). The runtime is the bundled Proton ARM64EC with FEX or Box64, plus DXVK, VKD3D-Proton or WineD3D. A Linux runtime can add official GE-Proton and Proton-CachyOS ARM64 builds.
- **Your controls.** Touch gamepad, RTS and FPS layouts, keyboard/mouse bindings, and an in-game Vapor menu. New supported-store shortcuts start with a **60 FPS limit**; existing choices are preserved.
- **Official Steam pages when needed.** Checkout, Wishlist, Community, guides, and Workshop browsing open in a dedicated modal web panel. Web install links return to the native game page. Shared web sign-in is implemented; live authentication and payment flows still need qualification.

![Vapor game page with a gradient Play button, Manage, Settings, Cloud Saves and Achievements over Helltaker artwork](docs/images/ui-2026-10-22-game-page-landscape.png)

<p align="center"><sub>Game page · Sample Helltaker play history, not a live account result</sub></p>

<table>
  <tr>
    <td align="center" width="50%"><img src="docs/images/ui-2026-10-22-library-landscape.png" alt="Library grid with Steam cover art, rounded cards, install-status chips and a focused card halo"></td>
    <td align="center" width="50%"><img src="docs/images/ui-2026-10-22-downloads-landscape.png" alt="Downloads queue with a game header image, a rounded progress bar and tonal Pause and Cancel buttons"></td>
  </tr>
  <tr>
    <td align="center"><sub>Library · install status at a glance, controller focus halo</sub></td>
    <td align="center"><sub>Downloads · sample progress, no transfer running</sub></td>
  </tr>
  <tr>
    <td align="center" width="50%"><img src="docs/images/ui-2026-10-22-store-landscape.png" alt="Steam Store with search, Hot games and category filters, and a large Hades spotlight card"></td>
    <td align="center" width="50%"><img src="docs/images/ui-2026-10-22-settings-landscape.png" alt="Settings with the sidebar and the Drivers page listing the system driver and two Turnip builds"></td>
  </tr>
  <tr>
    <td align="center"><sub>Store · public Steam artwork, sample prices</sub></td>
    <td align="center"><sub>Settings → Drivers · sample installed drivers</sub></td>
  </tr>
</table>

<details>
<summary><strong>Portrait layouts and phone gameplay</strong></summary>

<br>

<table>
  <tr>
    <td align="center" width="25%"><img src="docs/images/ui-2026-10-22-home-portrait.png" alt="Home in portrait with a large focused cover and full-width Open game button" width="200"></td>
    <td align="center" width="25%"><img src="docs/images/ui-2026-10-22-library-portrait.png" alt="Library in portrait with a two-column cover grid" width="200"></td>
    <td align="center" width="25%"><img src="docs/images/ui-2026-10-22-game-page-portrait.png" alt="Game page in portrait with play stats and actions at the bottom" width="200"></td>
    <td align="center" width="25%"><img src="docs/images/ui-2026-10-22-downloads-portrait.png" alt="Downloads queue in portrait with downloading, paused and completed games" width="200"></td>
  </tr>
  <tr>
    <td align="center"><sub>Home</sub></td>
    <td align="center"><sub>Library</sub></td>
    <td align="center"><sub>Game page</sub></td>
    <td align="center"><sub>Downloads</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/ui-2026-10-22-store-portrait.png" alt="Store in portrait with search, filters, a spotlight and a two-column game grid" width="200"></td>
    <td align="center"><img src="docs/images/ui-2026-10-22-settings-portrait.png" alt="Settings Drivers page in portrait" width="200"></td>
    <td></td><td></td>
  </tr>
  <tr>
    <td align="center"><sub>Store</sub></td>
    <td align="center"><sub>Settings → Drivers</sub></td>
    <td></td><td></td>
  </tr>
</table>

<p align="center"><sub>Captured on an Android 16 emulator (1080×2400, 420 dpi) from the production Compose components with public Steam artwork and sample state (prices and installs are samples).</sub></p>

![Helltaker running on the Xiaomi 17 Pro with Vapor touch controls](docs/images/helltaker-xiaomi.jpg)

*User-supplied Xiaomi 17 Pro gameplay, 15 October 2026. Sound was separately confirmed. HUD values are a screenshot observation, not a controlled performance comparison. [Phone evidence](docs/QUALIFICATION.md#helltaker-and-audio-follow-up--15-october-2026).*

</details>

## Latest development work

**22 October: a more modern look.** Rounded cover cards with a soft controller-focus
halo, tinted per-game placeholders when artwork is missing, install-status chips, a
gradient Play button, pill-shaped tabs, smooth progress bars, tonal buttons, and real
bold display type (Bricolage Grotesque) for titles. Behaviour, navigation and
controller handling are unchanged. Settings, sheets, Cloud Saves, Achievements and the
retro screens now share the shell's blue-slate surfaces instead of WinNative's purple
tint. Store search no longer reports "could not be loaded" for broad terms such as
"rpg", and a descriptive or misspelled search ("hollw nite", "open world car") shows
clearly labelled closest matches instead of nothing. 186 instrumented UI tests pass.
[Screenshots above](#the-experience) · [UI notes](docs/UI-UX.md#modern-visual-refresh--22-october-2026).

**21–22 October: the graphics stack and the build itself got test benches.**

- **Vulkan wrapper verification.** Reference-image, rendering and validation-layer
  probes now cover the wrapper that emulates desktop texture formats on phone
  GPUs. They found five wrapper defects, each now with a local patch and
  before/after evidence. [Details](#vulkan-wrapper-verification).
- **Reproducible APKs.** The differences between two builds were traced to a
  build timestamp, the git build ID and packaging history. With those pinned, two
  clean builds are byte-identical, on one machine and on two independent CI
  runners; a CI lane now checks this on every push.
  [Reproducibility evidence](docs/QUALIFICATION.md#emuready-community-reports--21-october-2026).
- **Placed-memory capability lane.** A no-GPU CI lane runs the real Vulkan loader
  against Lavapipe and a patched mock driver, covering the wrapper's
  placed-memory feature detection and its skip on drivers that lack it.
- **Wrapper provenance.** All three bundled wrappers are now traced to source: the
  GameNative variant is byte-identical to GameNative's published release.
  [Provenance](docs/PROVENANCE.md).

The 20 October UI pass made Friends, Chat and per-game settings more consistent with
Vapor’s handheld interface: shared branding, reachable close actions, larger touch
targets, scrollable chat settings, and layouts that adapt to narrow screens and
larger text. Compact game settings use a horizontal section rail with controller
navigation; wide layouts retain their sidebar.

<table>
  <tr>
    <td align="center" width="33%"><img src="docs/images/emulator-20261020-friends.png" alt="Signed-out Friends drawer in the installed Android app" width="240"></td>
    <td align="center" width="33%"><img src="docs/images/emulator-20261020-chat-settings.png" alt="Chat settings modal in the installed Android app" width="240"></td>
    <td align="center" width="33%"><img src="docs/images/emulator-20261020-search.png" alt="Native Steam Store search showing Portal results" width="240"></td>
  </tr>
  <tr>
    <td align="center"><sub>Friends drawer</sub></td>
    <td align="center"><sub>Chat settings</sub></td>
    <td align="center"><sub>Steam catalog search</sub></td>
  </tr>
</table>

*Actual signed-out Android emulator captures, 20 October 2026. The UI build passes
8 selected Android instrumentation tests and 40 selected JVM/Compose tests.
[Layouts, controller-focus previews and screenshot gallery](docs/UI-UX.md#actual-emulator-review--20-october-2026). Physical-controller and live-account checks remain pending.*

Native Steam social diagnostics now also have controlled Windows DLL and process
execution checks inside Android Wine/FEX, in both 32-bit and 64-bit guests. These
exercise callback handling, cancellation, cleanup and timeouts without sending
messages or loading Valve libraries. **They are groundwork for genuine-host
social integration, not proof of live in-game chat.** The diagnostic source has
not replaced the runtime bundled in the tested UI APK.
[Native execution evidence and limits](docs/QUALIFICATION.md#production-parent-runner-execution--20-october-2026).

## Games running on the Xiaomi

![Ghostrunner Demo gameplay on the Xiaomi 17 Pro, extracted from the supplied phone video](docs/images/ghostrunner-demo-xiaomi.jpg)

<p align="center"><sub>Ghostrunner Demo · Still from the supplied Xiaomi gameplay video, 15 October 2026 · Not an FPS benchmark</sub></p>

Physical-phone results on the **Xiaomi 17 Pro / Snapdragon 8 Elite Gen 5** include:

| Game | Observed result |
| --- | --- |
| Europa Universalis III | Smooth gameplay and working sound reported. |
| Team Fortress 2 | Training runs with the Steam account connected and sound working. Online matches are not yet qualified. |
| You Have to Win the Game | Gameplay, CRT effects, touch controls and sound work. |
| Helltaker | Gameplay and sound work; the game recognizes controller input and shows controller prompts. |
| **Ghostrunner Demo** | **The demanding UE4 demo reaches controllable gameplay, wallrunning and checkpoint respawn, with sound working. Some hitching remains.** |

The latest Ghostrunner report confirms that the game runs well enough to play,
with some lag. Lower settings and resolution are the next tuning steps; sustained
**30–60 FPS is a test target, not a measured result**. These observations do not
establish full-campaign completion or the same performance on other chipsets.
[Gameplay and Cloud evidence](docs/QUALIFICATION.md#ghostrunner-demo-loading-and-gameplay-observation--15-october-2026).

DirectX 12 has a separate compatibility gate: the required Vulkan features and a
working VKD3D/driver combination must be present. An older chipset may lack those
features or usable performance; its age alone is not a universal DX12 cutoff.
Onimusha: Way of the Sword Demo's reported GPU-support error remains unresolved
on the phone. [Investigation](docs/QUALIFICATION.md#onimusha-way-of-the-sword-demo--18-october-2026).

## Drivers, runtimes and automatic graphics

Vapor keeps WinNative's component system: graphics drivers, CPU translators and
Windows/Linux runtimes are installed once, selected per container or game, and
remembered. On top of that it adds driver validation, capability-checked automatic
graphics and a test bench for its Vulkan wrapper.

| Layer | What's available | Notes |
| --- | --- | --- |
| **Graphics drivers** | System Vulkan; three bundled Vulkan **wrapper** builds (default, Leegao v0.0.5r5, GameNative) that emulate desktop BCn textures, plus an opt-in **Wrapper-Vapor (experimental)** build with Vapor's six wrapper patches; custom **Turnip** drivers loaded through Adrenotools, either downloaded in **Settings → Drivers** (WN-Turnip balanced and performance builds) or imported from a ZIP; VirGL and Zink for OpenGL paths. | Imported drivers pass device, payload and initialization checks, and the selection is checked again before launch. A broken custom driver reports its own failure instead of the system driver's identity. On non-Adreno GPUs the built-in filter offers System only. [Driver review](docs/DRIVERS.md) |
| **Translation and runtime** | Bundled **Proton 9.0 (ARM64EC)** with **FEX** or **Box64**, selectable per game; **DXVK** (guided setup pins an ARM64EC 1.10.3 build), **VKD3D-Proton** for DirectX 12, and **WineD3D**. A separate Linux runtime runs the Linux Steam client with its own glibc Turnip and can download official **GE-Proton** and **Proton-CachyOS** ARM64 builds (SHA-256 checked). | Rebuilt Wine X11 and FEX components with fixes for lost clipped clicks and mixed-architecture cache reuse exist as credited experiments; the guided defaults stay on the tested baseline. [Runtime research](docs/RUNTIME-RESEARCH.md) · [Linux Proton builds](docs/LINUX-PROTONS.md) |
| **Automatic graphics** | Guided Steam launches probe the real Vulkan driver (feature bits, limits and identity) and choose among eligible installed combinations: your driver or System, with legacy DXVK, modern DXVK, DXVK + VKD3D, or WineD3D. | Only specific Vulkan start-up errors trigger another attempt: at most four, visible and cancellable, each after a clean Steam/Cloud teardown. A combination is remembered for a game only after a clean, observed session, and is re-checked against hardware, game build and file hashes. **Explicit per-game settings always win.** 79 JVM and 39 emulator checks pass; phone retries are still pending. [Routing record](docs/QUALIFICATION.md#automatic-graphics-routing--18-october-2026) · [source](app/src/main/runtime/system/AutomaticGraphics.kt) |

### Vulkan wrapper verification

The wrapper sits between Windows graphics translation and Android's Vulkan driver,
decoding the BCn texture formats desktop games use but phone GPUs lack. It now
has its own test bench, run on 4 KB and 16 KB emulators:

- **Decode accuracy.** Reference-image probes check BC1, BC3, BC4, BC5, BC6H and
  BC7 against independent decoders. The wrapper's GPU decode matches exactly
  (sRGB within 1 LSB). Depth formats, odd sizes, mips, array layers and sRGB are
  also covered.
- **Real rendering.** A one-command graphics-pipeline probe samples
  wrapper-decoded textures and compares the rendered image (31/31 cases).
- **Validation layers.** Khronos validation chained in front of the wrapper finds
  no app-facing errors; placed behind it, it found two internal defects.
- **Five defects root-caused and patched:**
  - sRGB textures decoding differently on the CPU fallback;
  - out-of-bounds writes on odd-size copies;
  - all-black BC6H image-view output;
  - device-shutdown leaks (14 objects reduced to 2);
  - a source mapping never released.

  The full six-patch stack builds with the recorded library identity and passes
  every probe family.

The patches live in [`tools/vulkan-wrapper`](tools/vulkan-wrapper/README.md). The
app now offers them as an opt-in graphics driver, **Wrapper-Vapor (experimental)**,
in container and game settings. It is the exact qualified full-stack build, packaged
with the stock wrapper's hooks, and is never chosen automatically; the default is
unchanged. It has run only on software-Vulkan emulators, so the Adreno phone pass is
what decides whether it should become a default. Each bundled wrapper's source line
is identified:
- the default build comes from the Pipetto-crypto/mesa fork;
- the GameNative build is byte-identical to GameNative's published release;
- the Leegao build is v0.0.5r5.

[Wrapper evidence](docs/QUALIFICATION.md#wrapper-reference-image-bcdepth-probe) ·
[provenance](docs/PROVENANCE.md).

## Compatibility and status

**The app running, a game launching, and a game playing well are different results.** Vapor does not promise the entire Steam catalog. GPU features, drivers, the runtime, game dependencies, available memory, and anti-cheat can all affect compatibility.

| Area | Current evidence | Still to qualify |
| --- | --- | --- |
| **Local gameplay** | Xiaomi 17 Pro / Snapdragon 8 Elite Gen 5 reports cover Europa Universalis III, Team Fortress 2 training, You Have to Win the Game, Helltaker, and Ghostrunner Demo. Sound is reported; Ghostrunner Demo has visible hitching. | Repeatable game/device settings, longer sessions, measured comparisons, and the full reference-device matrix. |
| **Steam session** | Account-connected TF2 training works through the genuine-client launch path. Original game Steam API libraries are retained. | Inventory / Game Coordinator traces, counted playtime, and online multiplayer. |
| **Steam Cloud** | One confirmed Ghostrunner Demo upload contains two files. Exit-sync guards and recovery have local regression coverage. | Remote restore/reopen and reliable repeated uploads; intermittent phone exit timeouts remain open. |
| **Friends, Chat, Achievements** | Native screens, request states, refresh, message acknowledgements, and session-ownership handling have local coverage; the latest UI has emulator checks. Controlled native receiver diagnostics run under Wine/FEX. | Live message delivery, in-game Android chat integration, and newly earned achievements reaching Steam. |
| **Store and controls** | Public Store recovery, native UI layouts, and injected controller navigation pass local checks. | Authenticated free/demo and paid flows, live web sign-in, physical controllers, and phone UX acceptance. |
| **Graphics stack** | Driver validation and automatic graphics pass local and emulator checks. Wrapper decode, rendering and validation-layer probes pass on 4 KB / 16 KB emulators (software Vulkan), and five wrapper defects have local patches with before/after evidence. | Adreno/KGSL phone runs, automatic retries on the phone, packaging the wrapper patches, and the last two shutdown frees on a real driver. |
| **Build reproducibility** | Two clean builds give byte-identical APKs on one machine and on two independent CI runners (pinned build ID and source date, CI-only signing key); CI checks this on every push. | Release signing and cross-toolchain builds. |
| **Android platform** | ARM64 development APK; minimum Android 10. **39/39** APK native libraries and ZIP packaging pass 16 KB alignment. Native TLS/probe checks and startup pass on 4 KB / 16 KB emulators. | Modern target SDK 36 and complete Wine/FEX gameplay on 16 KB kernels. The current build targets SDK **28** and compiles against **35**. |

<details>
<summary><strong>Engineering checks and current development builds</strong></summary>

<br>

The 20 October UI pass passes **8 selected Android instrumentation tests** and
**40 selected JVM/Compose tests**. These cover Friends/Chat states, responsive
settings and injected controller navigation. Native contract suites pass **61
cases** (15 capability, 33 receiver and 13 mode-parser cases). Under emulator
Wine/FEX, each Windows architecture passes the 33 receiver scenarios, 9
controlled-DLL scenarios and 7 production parent-runner scenarios. The first
32-bit parent-fixture startup timed out before producing a result; an unchanged
retry passed. These are bounded fixture checks, not real Steam delivery or a
whole-project test total. [Latest qualification](docs/QUALIFICATION.md#production-parent-runner-execution--20-october-2026).

The earlier automatic-graphics development build passes **79 selected JVM tests**
and **39 emulator checks** (34 Compose and 5 native GPU-probe cases). Guided Steam
launches can try eligible installed graphics combinations after specific Vulkan
startup failures, with clean Steam/Cloud teardown, visible cancellation and at most
four attempts. Explicit game settings remain authoritative. Onimusha phone recovery,
real retry/Cloud handoffs and performance comparisons remain pending.
[Automatic graphics and component-review record](docs/evidence/2026-10-18-automatic-graphics.json).

The preceding Downloads/D3D12 build passes **27 selected Android UI tests** and
**48 JVM tests**, plus two landscape repetitions. Downloads show recovery and
completion actions; guided setup prepares the matched DirectX 12 component pair
used by the subsequent routing work.
[Downloads and preparation record](docs/evidence/2026-10-18-downloads-d3d12.json).

The preceding onboarding build passes **31 selected Android UI tests** and **25 JVM tests**, with three additional portrait rerenders of the same preview cases. Preparation, Steam sign-in and Steam Guard now share Vapor's native branding and layout. Live account completion and phone acceptance remain pending. [Onboarding record](docs/evidence/2026-10-18-onboarding.json).

The preceding game-page build records **26 selected Android UI tests**; the earlier Store/Home pass records **46 UI tests and 14 catalog JVM tests**. These are distinct scopes, not a whole-project total. Full Android lint remains unqualified. [Game-page record](docs/evidence/2026-10-17-ui.json) · [Full qualification history](docs/QUALIFICATION.md).

The separate 19 October component work rebuilt both FEX Windows DLLs and the Wine
X11 module. **15/15 FEX cache phases** pass. External-pointer tests reproduce lost
clipped clicks with the original Wine module and pass with the rebuilt module in
both guest architectures. These experiments leave the guided runtime defaults
unchanged; no new APK or phone performance result is claimed.
[Component record](docs/QUALIFICATION.md#runtime-components--19-october-2026).

</details>

## What makes Vapor different

Vapor focuses on a **Steam-first handheld experience in a native Android app**,
with source-visible Steam session handling, validated custom drivers and
capability-checked automatic graphics. It builds on WinNative and shared Wine,
Proton, FEX and Mesa work; it does not claim a new emulation engine or a proven
speed lead.

| Project | Approach | Drivers and runtime | Automatic settings |
| --- | --- | --- | --- |
| **Vapor** | Native Android shell, Steam-first; genuine Steam host started on demand. | Inherits WinNative's catalog (bundled wrappers, Turnip downloads and imports, Proton ARM64EC with FEX or Box64, Linux runtime with GE/CachyOS Proton) and adds driver validation plus a tested wrapper bench. | Probes the real Vulkan driver, picks an eligible driver/translator, retries only known start-up errors (at most four, visible), learns per game; explicit settings win. |
| [WinNative](https://github.com/WinNative-Emu/WinNative) | Vapor's foundation: a launcher for Windows games, Steam/Epic/GOG and retro games. | The same component system and driver feeds; Vapor builds on these owners rather than replacing them. | Container and per-game settings, which Vapor's routing uses. |
| [GameNative](https://github.com/utkarshdalal/GameNative) | Native Android launcher with several stores and Cloud saves. | Its own runtime; its notices list proprietary redirect, bootstrap and launcher helpers. | Applies known game configurations automatically; a recent change sends device and driver details to its remote best-config request. |
| [Winlator](https://github.com/brunodev85/winlator) | General Windows app/game environment. | Wine with Box86/Box64 in containers. | Manual per-container and per-shortcut configuration. |
| [DroidDeck](https://github.com/Droid-Deck/DroidDeck) | Valve's actual Steam Big Picture client in a Linux environment. | ARM64 Proton; documented for supported Adreno hardware. | Per-game compatibility settings through the Steam client. |

Native launching and automatic configuration are shared ideas, not Vapor
exclusives. Vapor's emphasis is the combination: Steam / Big Picture-style
navigation, the open inherited Steam host, driver checks before launch, bounded
graphics retries, and published evidence for each change, including this week's
wrapper and reproducibility test benches. Upstream fixes are credited and
experimental builds stay separate. **There is no measured
Vapor-versus-competitor FPS, RAM or compatibility ranking yet.**
[Detailed comparison](docs/COMPETITOR-COMPARISON.md).

## How Steam runs

Vapor uses a **native Android launcher with an on-demand headless Steam host**. Browsing does not require starting a Windows or Linux Steam desktop.

```mermaid
flowchart LR
    UI[Native Android UI] --> Service[Android Steam service]
    Service --> Native[Native wn-steam-client]
    Native <--> Steam[Steam servers]
    UI -->|Play| Host[Genuine Steam host]
    Host --> Game[Windows game]
    Game --> Runtime[Wine + FEX + graphics translation]
    Runtime --> Device[Android display, audio and GPU]
```

1. **Browse and download.** Compose talks to the Android Steam service and native `wn-steam-client` through JNI. Authentication, library metadata, manifests, and depot downloads work without a Wine/FEX game session.
2. **Play.** The service hands account ownership to the genuine host derived from WinNative’s `steamlite-handoff`. Valve client libraries provide the game’s Steam session and Steamworks IPC alongside Bionic Wine/FEX, without starting the desktop Steam browser UI in this path.
3. **Exit.** The host attempts Cloud synchronization, logs off, and returns ownership to the Android service. Reserved close-time work blocks removal. A timeout or offline exit is never reported as a successful upload.

During genuine-host gameplay, the Android Steam connection is intentionally
suspended. Friends status can be cached and the native chat interface does not
yet provide a qualified live in-game connection. Native receive diagnostics are
being tested separately before integration into that session lifecycle.

Depot chunk decoding is native Rust; the Android UI/service and JNI bridges also use managed memory. Comparative speed, RAM, and garbage-collection gains have **not** been benchmarked. FEX translates PC instructions, including applicable host code; the whole runtime is not native ARM code.

Returning to Library does not automatically stop a running game. Inherited manual/background pause controls are not yet a qualified SteamOS-style suspend feature. Vapor runs as an Android app; streaming and replacing Android with SteamOS are outside its scope.

## Build and contribute

The application package is `app.vapor.android`; the current build is the `standard` ARM64 flavor. Start with the [build instructions](docs/BUILDING.md) for the complete pinned toolchain, Git LFS assets, submodules, and Steam-host rebuild steps.

```sh
git lfs install
git clone --recurse-submodules https://github.com/giminycricket0-blip/vapor.git
cd vapor
git lfs pull
# Install the pinned prerequisites and rebuild the Steam hosts as documented.
./gradlew :app:assembleStandardDebug
```

Development APKs use a debug key. Runtime provenance, redistribution conditions, release signing, and device qualification remain separate public-APK gates.

Useful contributions include reproducible game/device reports, fixes in the existing runtime and service owners, controller/accessibility improvements, and focused regression tests. Include the app build, device, Android version, runtime/driver versions, steps, and expected versus actual behavior. Remove account tokens and personal data from diagnostics.

| Explore | Purpose |
| --- | --- |
| [Build instructions](docs/BUILDING.md) | Toolchains, native components, tests, and packaging |
| [Roadmap](docs/ROADMAP.md) | Current work, system map, and remaining milestones |
| [Qualification](docs/QUALIFICATION.md) | Game observations, test records, and explicit limits |
| [UI and controls](docs/UI-UX.md) | Navigation, layouts, and interaction decisions |
| [Runtime research](docs/RUNTIME-RESEARCH.md) | Translation, graphics, and upstream investigations |
| [Drivers](docs/DRIVERS.md) | Android and Linux Turnip builds, packages, and installation |
| [Vulkan wrapper experiments](tools/vulkan-wrapper/README.md) | Wrapper probes, local patches, and their stacking order |
| [Project comparisons](docs/COMPETITOR-COMPARISON.md) | Source-backed alternatives and comparison limits |
| [Provenance](docs/PROVENANCE.md) | Component identities, sources, licenses, and open distribution gates |

## Source and credits

Vapor builds on [WinNative](https://github.com/WinNative-Emu/WinNative) commit `8409085f706acbc0749881d9bf1e85f2f347dfd4`, retaining its source history and the existing account/download, Wine/FEX, display, and input integration. Upstream project and contributor attribution remain in [CREDITS.md](CREDITS.md) and [EMULATOR_CREDITS.md](EMULATOR_CREDITS.md).

Vapor and its inherited host source are licensed under [GPL-3.0](LICENSE). **Component licenses differ:** genuine Valve Steam client libraries are proprietary, and the complete bundled runtime is not entirely open source or GPL. See [provenance](docs/PROVENANCE.md) before redistributing a build.

Vapor is an independent project, unaffiliated with Valve. Steam, SteamOS, and game artwork belong to their respective owners. The current mechanical V is placeholder branding.
