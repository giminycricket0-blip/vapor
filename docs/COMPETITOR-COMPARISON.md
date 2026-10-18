# Vapor and other local PC gaming projects

Initial comparison checked **14 October 2026**; dated follow-ups below record later checks, including Mantis PlayHub and cross-chipset readiness on **15 October 2026**. This compares the current Vapor checkout with primary project documentation, public code descriptions and release notes. Competing apps were not installed or benchmarked in these passes. A documented feature, a maintainer's device result and a result reproduced in Vapor are different evidence.

**DroidDeck is the closest architectural match to Valve's exact SteamOS interface. Vapor's direction is a native Android application with a Steam-first interface, built on WinNative, and a qualified component/launch path. It does not yet have a proven performance or compatibility advantage.** This is an assessment of the architectures and current evidence, not a claim that every game works in DroidDeck.

## Positioning refresh — 19 October 2026

The public [GameNative README](https://github.com/utkarshdalal/GameNative) and
[DroidDeck README](https://github.com/Droid-Deck/DroidDeck) were checked again for
this update. GameNative advertises automatic known configurations, Cloud saves,
controllers, DLC, Workshop and branches. Native launching, direct Steam downloads
and automatic settings therefore are **shared approaches**, not unique Vapor claims.
DroidDeck uses Valve's actual Big Picture client and Linux runtime; that supplies
more authentic client behavior directly than rebuilding each feature in Compose.
Neither project was installed or benchmarked in this pass.

Vapor's present distinction is the combination of:

- A Steam / Big Picture-style native Android shell, with secondary stores in Settings.
- The source-visible host inherited from WinNative, with Android-service/genuine-host
  session ownership and explicit close-time Cloud reservations. GameNative's
  [current notices](https://github.com/utkarshdalal/GameNative/blob/master/THIRD_PARTY_NOTICES)
  identify proprietary redirect/bootstrap/launcher helpers; Valve libraries used
  by either approach remain proprietary too.
- Capability-filtered selection among installed graphics components, with bounded
  retries for explicit startup errors after clean teardown. This differs from a
  claim that every crash can be solved by trying random settings. Phone retry and
  Onimusha recovery remain unqualified.
- Reproducible work below the UI: the 19 October Wine comparison corrects lost
  clipped-pointer events using unmodified upstream patches, and the FEX cache
  candidate preserves mixed-architecture reuse through its recorded test sequence.
  The Wine patches come from the GameNative/proton-wine project and are credited;
  adopting them is integration work, not an exclusive Vapor invention.

Ghostrunner Demo now has a further phone confirmation of working gameplay and
sound with some hitching. Its supplied log is consistent with a D3D11 path;
Onimusha's GPU dialog and previously missing VKD3D selection are a separate D3D12
investigation. Lower settings may reduce cost, but stable 30–60 FPS, universal
older-chipset limits and a competitor speed/RAM ranking are not established.
See [component evidence](QUALIFICATION.md#runtime-components--19-october-2026)
and [Ghostrunner evidence](QUALIFICATION.md#ghostrunner-demo-loading-and-gameplay-observation--15-october-2026).

## Comparison

| Project | Interface and local execution | Source availability | What this means for Vapor |
| --- | --- | --- | --- |
| **Vapor** | Native Compose Home, Library, Store, Downloads and Settings. Existing Steam services and genuine-client launch host; Windows games use the inherited Wine/FEX graphics and input stack. | GPL-3.0 application and inherited host source. Proprietary Valve libraries and component-specific licenses remain separate; complete runtime distribution review is open. | Phone reports include EU3, account-connected TF2 training, Helltaker, You Have to Win the Game and Ghostrunner Demo with sound. Ghostrunner has some hitching. Matched performance and the separate six-core-game matrix remain pending. [Vapor evidence](QUALIFICATION.md) |
| **DroidDeck** | Runs Valve's actual Steam Big Picture client in a Linux environment inside Android, with ARM64 Proton for Windows games. | GPL-3.0 project code; Valve's Steam client remains proprietary. | Strongest reference for authentic Steam UI and client features. Current requirements specify supported Adreno hardware and a child-process setting; it is not a universal Android runtime. [Project and requirements](https://github.com/Droid-Deck/DroidDeck) |
| **WinNative** | Android launcher integrating Windows games, Steam/Epic/GOG and retro games, with substantial runtime and input work. | GPL-3.0 project; component-specific notices still matter. | Our direct foundation and first code donor. Changes should normally extend these existing owners. [Project](https://github.com/WinNative-Emu/WinNative) |
| **Winlator** | General Windows application/game environment using Wine and Box86/Box64, with containers and per-shortcut settings. | Public repository identifies LGPL-2.1; dependencies have their own licenses. | Useful execution and compatibility reference. Its documented workflow exposes more manual configuration than Vapor's intended install-to-Play journey. [Project](https://github.com/brunodev85/winlator) |
| **GameNative** | Native Android library/store workflow. Documents cloud saves, automatic known configurations, touch/controller controls, DLC, Workshop and branches. | Mixed: GPL-3.0 application plus important proprietary helpers. | Closest product comparison for making a native Android app convenient. We can review its open application code, but cannot treat its entire runtime as a reusable open-source package. [Project](https://github.com/utkarshdalal/GameNative), [component notices](https://github.com/utkarshdalal/GameNative/blob/master/THIRD_PARTY_NOTICES) |
| **GameHub / GameSir** | Commercially distributed gaming app offering local PC execution and Steam/Epic integration, alongside streaming features. The local execution mode is the relevant comparison here. | No complete buildable application/runtime source and reusable license were established from the official material reviewed. Treat as a closed product for reuse planning. | A product/workflow and physical-device benchmark candidate; its internal optimization claims are not an implementation recipe for Vapor. [Official product page](https://gamehub.xiaoji.com/) |
| **Mantis PlayHub** (15 October follow-up) | Its Play listing advertises local Steam games, downloads, supported Cloud saves, touch/controller input and performance information. | A complete buildable source distribution and reusable license were not established in this audit. The Play listing declares ads and in-app purchases. | Useful Android workflow and device benchmark candidate. The supplied library inventory does not establish implementation, memory usage or superior compatibility. See the dated audit below. [Official listing](https://play.google.com/store/apps/details?id=app.mantispro.pcgames) |
| **Armada** | Separate Linux distribution for supported ARM handhelds, with Steam Gaming Mode, KDE, FEX and Proton. Boots from supported storage configurations. | Its own code is GPL-2.0-or-later; packaged components keep their own licenses. | Closest comparison for a full operating system. Its device-specific boot and hardware integration cannot be installed as an ordinary Vapor APK. [Repository](https://github.com/armada-os/armada), [device list](https://armadaos.dev/devices/supported-devices/) |

The release pages reviewed identify [DroidDeck 0.4.0](https://github.com/Droid-Deck/DroidDeck/releases/tag/0.4.0), [GameNative 1.3.0](https://github.com/utkarshdalal/GameNative/releases/tag/v1.3.0), [WinNative 0.7.1-beta](https://github.com/WinNative-Emu/WinNative/releases/tag/v0.7.1-beta) and [Winlator 11.2](https://github.com/brunodev85/winlator/releases/tag/v11.2.0). Current branch documentation can describe work beyond a released APK; use exact APK/component versions in any later device comparison. Vapor remains pinned to WinNative commit `8409085f706acbc0749881d9bf1e85f2f347dfd4` plus its recorded working-tree changes.

## What “SteamOS-like” means here

There are three different arrangements:

1. **Vapor:** Android → Vapor's own interface → Steam services/game session → Windows compatibility and CPU translation → Android graphics/audio/input.
2. **DroidDeck:** Android → Linux environment → Valve's Steam interface → Proton and translation → Android hardware integration.
3. **Armada:** Boot a supported device into Linux → Steam Gaming Mode → Proton and translation → that Linux system's device drivers.

An ARM-native Steam client does not make an x86 Windows game native ARM software. FEX translates x86/x86-64 instructions; Wine/Proton supplies Windows-compatible interfaces. The precise split depends on the runtime build and game. [FEX documentation](https://github.com/FEX-Emu/FEX)

DroidDeck already uses the real client, so rebuilding Steam's whole Store, community, input-layout ecosystem and client behavior in Compose would be a substantial additional maintenance commitment for Vapor. Its 0.4.0 notes describe touch layouts, multiple controllers, per-game compatibility settings and resumed downloads. These are documented competitor capabilities, not tests performed here. [DroidDeck release notes](https://github.com/Droid-Deck/DroidDeck/releases/tag/0.4.0)

Armada can integrate system power, fans and suspend because it owns the operating system on supported devices. Those features are not UI controls that an ordinary Android app can simply copy. Its current device documentation does not establish support for either of our reference phones. [Armada features](https://armadaos.dev/), [supported manufacturers and models](https://armadaos.dev/devices/supported-devices/)

## Reuse that is worth investigating

| Work to study | Concrete source | Fit for Vapor and verification needed |
| --- | --- | --- |
| Avoid unnecessary work while a game starts | DroidDeck [performance change #529](https://github.com/Droid-Deck/DroidDeck/pull/529) | Review repeated library/prefix passes, input locking and sensor polling against Vapor's actual profiles. Adopt a fix only where the same cause exists; do not add another scheduler or cache by default. |
| Match audio helpers to the actual Wine interface | DroidDeck [DirectAudio change #439](https://github.com/Droid-Deck/DroidDeck/pull/439) | Vapor already inherits DirectAudio integration. Compare that implementation first. A matching version name alone may be insufficient; prove sound, route changes and fallback with the selected runtime. |
| Reduce unnecessary graphics copies | WinNative [Wayland documentation](https://github.com/WinNative-Emu/WinNative/blob/main/docs/WAYLAND-DISPLAY.md) | The donor already provides a coordinated compositor path. It requires compatible Wine and Turnip components; copying a driver from a different build can produce a black screen. Test on Adreno and retain the working baseline until a measured improvement is reproduced. |
| Use suitable graphics/translation components | [Mesa/Turnip](https://docs.mesa3d.org/drivers/freedreno.html), [DXVK](https://github.com/doitsujin/dxvk), [vkd3d-proton](https://github.com/HansKristian-Work/vkd3d-proton), [FEX](https://github.com/FEX-Emu/FEX) | These are shared building blocks, not unique competitor engines. Pin a compatible set and use existing per-game settings. Vulkan features, memory behavior and sustained thermals need actual phone tests. |
| Prevent save-related destructive races | GameNative [1.3.0 release notes](https://github.com/utkarshdalal/GameNative/releases/tag/v1.3.0) link the change preventing deletion during a closing cloud upload | Compare the open implementation with Vapor's current uninstall and cloud owners. Add a focused regression if the same race exists. Do not import a second save system. |
| Evaluate presentation smoothing separately | WinNative [frame-generation documentation](https://github.com/WinNative-Emu/WinNative/blob/main/docs/FRAME-GENERATION.md) | Frame generation needs spare GPU/display capacity and adds latency. Report the game's real frame rate separately from generated frames; it does not repair slow loading or a game that cannot launch. |

GameNative's notices explicitly withhold source for its execution redirect, Steam bootstrap, KGSL helper and several launcher helpers. Reusing its public UI or service code does not supply those missing pieces. Our genuine Steam host already has an open implementation in the chosen WinNative foundation; preserve that path instead of building a dependency on unavailable helpers. [GameNative notices](https://github.com/utkarshdalal/GameNative/blob/master/THIRD_PARTY_NOTICES), [pinned WinNative host documentation](https://github.com/WinNative-Emu/WinNative/blob/8409085f706acbc0749881d9bf1e85f2f347dfd4/app/src/main/cpp/wn-steam-launcher/steamlite-handoff/README.md)

Before distributing imported code, preserve its attribution, license and corresponding-source obligations and review the separate binary inputs. A public repository does not make every packaged component open source. The original comparison imported no competitor code. The later narrow GameNative reservation adaptation is recorded below and in CREDITS.md.

## Loading and performance: what we can actually say

**There is no measured Vapor-versus-competitor speed ranking.** A native library screen could avoid running the full Steam interface while browsing, but that is an architectural hypothesis, not an established RAM, battery or launch-time saving. Game execution still depends on translation, graphics, storage, synchronization and thermals.

DroidDeck's #529 reports lower idle CPU and fewer traced system-call stops on one RedMagic test setup. The same report says several game launch-to-focus times were unchanged within noise. This is useful evidence for choosing targeted work; it is not a general FPS increase and not a Vapor benchmark. Its PRoot fast paths are specific to that execution route and need not help Vapor's selected Bionic route. [Maintainer measurements](https://github.com/Droid-Deck/DroidDeck/pull/529)

The next fair comparison should use **Vapor, pinned WinNative and DroidDeck on the same Xiaomi**, with identical legitimate game builds, scene, render resolution, graphics settings, power/cooling conditions and recorded runtime versions. Add GameNative and GameHub after the first three can be compared consistently. Armada requires an explicitly supported handheld and belongs in a separate device comparison.

Use the existing [measurement procedure](QUALIFICATION.md#comparison-procedure-for-device-work): distinguish first setup, five fresh-session launches, five repeat launches and reattachment to an already-running session. Measure until a menu accepts input and until the same save becomes playable. Report failures, RAM, real frame times and longer-session thermals separately. Keep frame generation off for the base comparison. Do not use the emulator's software GPU to rank Snapdragon performance.

## Recommendation for the next milestone

Keep the accepted native Vapor product and WinNative foundation. Use DroidDeck as the reference for authentic Steam behavior, GameNative as the reference for a convenient Android workflow, and upstream projects as sources of narrowly justified fixes.

Vapor's next competitive result should be a repeatable **Home → game → Play → save → exit → reopen** journey on the Xiaomi, followed by the same comparison game in the other apps. Collections and inherited-form polish remain on the existing UI roadmap. No second runtime, full storefront rewrite, plugin architecture or new compatibility service is justified by this research alone.

For an exact Valve frontend today, DroidDeck's approach is closer. Making Vapor equally faithful while retaining its own native UI is continuing product work; this Home build does not establish SteamOS feature parity.

## Verified upstream reuse map — 14 October 2026

The latest proposals were checked against GitHub PR metadata and the local WinNative
`8409085f`-derived tree. Source presence is not a feature qualification result. Vapor uses
`WnSteamSession` and its native Rust service, not JavaSteam as its current account/download
implementation; port semantics through that owner instead of introducing a second client.

| Area | Checked upstream reference | Existing Vapor owner and decision |
| --- | --- | --- |
| Exit cloud upload vs. uninstall | [GameNative #1962](https://github.com/utkarshdalal/GameNative/pull/1962), `0fdaf7aba375c1510bec82c142a9b6941744aec6` | Adapted synchronous reservations and waiting deletion into SteamCloseSync/SteamService. Track native-host cleanup in XServerDisplayActivity too, because Android closeApp defers to that host. Both Steam uninstall entry points wait before touching files, markers or database state. This tracks scheduled close work; it is not a general lock for every game file mutation. |
| Idle/startup work | [DroidDeck #529](https://github.com/Droid-Deck/DroidDeck/pull/529), `eadebc46d57b6fed3bbf4a2a39edfbbe61877fa4` | Source audit found shell friends refresh loops at 30 s / 5 s and plain Compose state collection surviving a background activity. Changed those shell subscriptions/loops to use existing Android lifecycle primitives. Download and game services keep their own lifetimes. No PRoot code imported into the selected Bionic path. |
| Motion input | Same #529, PadMotion change | DroidDeck caches display rotation between motion samples. Vapor already checks gyro_enabled, unregisters on pause and uses SENSOR_DELAY_GAME; the proposed unconditional sensor-registration bug is absent. No sensor patch made. |
| Prefix preparation | Same #529, seed-redists change | DroidDeck fixes repeated registry-marker appends caused by mismatched stamps. Vapor already gates graphics/component extraction by selected versions/first boot, and has no matching seed-redists script. ContainerManager.activateContainer does launch a recursive chmod on each activation: retain as a specific timing/permission-repair investigation, not proof that removing it is safe. No broad new prefix stamp added. |
| Audio | [DroidDeck #439](https://github.com/Droid-Deck/DroidDeck/pull/439), `da42a5d7a782be423fe158276246f9bba0646468` | This is a DirectAudio interface/distribution change, not a gyro PR. Vapor already has DirectAudio. Qualify the chosen Wine interface and audio route before changing it. |
| DLC and family copy | [#1883](https://github.com/utkarshdalal/GameNative/pull/1883) `63a1c095`, [#1707](https://github.com/utkarshdalal/GameNative/pull/1707) `cd5ecbc8` | SteamService already queries owned/downloadable DLC and family group information. Check season-pass filtering and lender-copy selection against actual account cases; family metadata is not proof of a working lender selector. |
| Beta branches | [#1711](https://github.com/utkarshdalal/GameNative/pull/1711) `1b685829` | Existing SteamBranchSelection, PrefManager and branch picker own this. The PR allows branch installation without installing the full default branch first; it does not prove universal delta-only updates. |
| Achievements | [#1695](https://github.com/utkarshdalal/GameNative/pull/1695) `1e194837` | SteamAchievementsScreen and SteamService user-stat/schema methods already exist. Verify viewing and real in-game unlocks separately. Preserve the genuine-client path; do not add simulated ownership or replace a game's Steam DLLs. |
| Cloud paths and depot files | [#1957](https://github.com/utkarshdalal/GameNative/pull/1957) `660965e2`, [#2063](https://github.com/utkarshdalal/GameNative/pull/2063) `c00f92db` | Compare root-token behavior with existing PathType/SteamAutoCloud. #2063 creates parents for empty files during depot download; it is not a cloud-save-folder fix. Reproduce each case before porting it. |
| Library metadata and agreements | [#1965](https://github.com/utkarshdalal/GameNative/pull/1965) `cbf3fd82`, [#2069](https://github.com/utkarshdalal/GameNative/pull/2069) `270e509a` | Native catalog/details already exist; SteamApp parses launchEulaUrl. Displaying metadata or storing a URL does not establish a correct agreement-acceptance workflow. Audit that workflow after the phone baseline. |
| Workshop | [Claimed #380](https://github.com/utkarshdalal/GameNative/pull/380) | The supplied reference is an unmerged draft achievements PR, not Workshop. Vapor already has WorkshopScreen, subscribed-item RPCs, installWorkshopItem and WorkshopModsGenerator; test those existing owners. |
| Device presets | [#2010](https://github.com/utkarshdalal/GameNative/pull/2010) `ce15af1a` | This adds device/app/driver details to a remote best-config request, not a standalone local preset database. Do not introduce that remote service or copy unqualified device presets into Vapor's defaults. |
| Controller and mouse | [#1839](https://github.com/utkarshdalal/GameNative/pull/1839) `d4e6dabd`, [#1578](https://github.com/utkarshdalal/GameNative/pull/1578) `4d10a4ec`, [#1870](https://github.com/utkarshdalal/GameNative/pull/1870) `c94df3c3`, [#1891](https://github.com/utkarshdalal/GameNative/pull/1891) `627bf6f8` | References match composite-HID filtering, multiple controllers, Joy-Con pairing and relative mouse mode. Compare with ExternalController/ControllerManager and the existing input router when a corresponding device problem is reproduced; no wholesale input subsystem replacement. |

### Compatibility-data and synchronization candidates

[GameNative's protonfixes repository](https://github.com/GameNative/gamenative-protonfixes)
contains generated per-store/game data, verb metadata, hashes and conversion coverage. Its
README documents unsupported steps and a separate schema contract. A recipe is not simply
a map of DLL overrides: `vcrun2022` is an installation verb, not a DLL name to insert into
WINEDLLOVERRIDES. Before importing data, establish the licences/provenance of the generator
and derived inputs (no repository-wide licence was established from the root listing or
GitHub metadata), pin the chosen revision, and support only operations required by a
reproduced game failure through existing GameFixes/component owners. Desktop Proton rules
are not automatically correct for Bionic Wine or an Android GPU. No data or parser imported.

[ntsync-android](https://github.com/joshuatam/ntsync-android) is an LGPL-3.0 userspace
implementation with explicit differences from the kernel driver. Its integration guide
requires changes to Wine's ntdll synchronization calls and object-handle transport; adding
a library or environment variable alone is insufficient. Consider a matched, reproducible
Wine build only after a synchronization problem is measured on the baseline. No promised
FPS improvement, universal deadlock fix or new runtime input follows from the README.

The native interface can expose useful Steam services without rendering the full desktop
client. Neither JavaSteam protobuf availability nor these PRs establish full Steam client,
Steam Input, multiplayer, DRM, anti-cheat or SteamOS feature parity. Those claims require
separate account, game and device evidence.


## Steam ecosystem research follow-up — 15 October 2026

The [Steam feature roadmap](ROADMAP.md#steam-features-and-integration-plan--15-october-2026)
now maps the broader experience and the actual native-session suspension boundary. The current
repositories and official documentation were checked again for this comparison; no competitor
app was newly executed, no code was imported and no relative performance result was produced.

| Reference | What it contributes to this decision | What to reuse or learn |
| --- | --- | --- |
| [DroidDeck](https://github.com/Droid-Deck/DroidDeck) | Runs Valve's actual Steam/Big Picture client in its Linux runtime. This supplies Valve's frontend rather than rebuilding each screen. | Use it to compare user journeys and game-side Steam behavior. Importing its Linux/client architecture solely to add chat would change Vapor's chosen product. Actual feature behavior still needs device tests. |
| [WinNative](https://github.com/WinNative-Emu/WinNative) | Vapor's selected source foundation already contains native Friends/direct-chat, achievements, DLC, branch and Workshop owners. | Finish the existing paths. The current genuine-host handoff pauses Android chat; donor code presence does not solve in-game continuity. |
| [GameNative](https://github.com/utkarshdalal/GameNative) | A comparable Android-native library, store and game-management workflow. | Compare specific public changes against the existing owners. Its [component notices](https://github.com/utkarshdalal/GameNative/blob/master/THIRD_PARTY_NOTICES) identify proprietary redirect/bootstrap and other helpers; they are not available open-source donor implementations. |
| [Armada](https://github.com/armada-os/armada) | A Fedora bootc/ROCKNIX-based ARM Linux distribution with Steam Gaming Mode and KDE, not an Android skin. | Useful OS/user-experience reference. Its device drivers, boot/update and power controls are not drop-in Android app features. |
| [SteamKit](https://github.com/SteamRE/SteamKit) and [Steam protocol schemas](https://github.com/SteamTracking/Protobufs) | Existing interoperability work and separate direct-message/group-chat contracts. | Research protocol semantics and failure cases. Do not add a .NET runtime, second account owner or new protocol library to a Rust/JNI service that already implements direct chat. |
| [Valve's mobile apps](https://store.steampowered.com/mobile/) | Separate Steam account/store, Chat and Link products. | Keep Guard and confirmations in the official authenticator. Separate product availability does not prove that web chat voice, authentication or invitations work inside Vapor's current WebView. |

The fastest coherent path remains native daily screens plus official web modals for web-heavy
features, with a bounded proof for in-game chat. Full desktop Steam is closer to the exact Valve
frontend but brings a different runtime/UI tradeoff. No measured RAM or battery advantage is
claimed for either approach here.

## Cross-chipset and Mantis audit — 15 October 2026

**Vapor has an existing route for system Vulkan drivers; broad non-Adreno compatibility is not qualified.** ARM64 packaging is a CPU/ABI requirement, not proof that every ARM64 phone can run a particular Wine/FEX, graphics and Steam combination. The bundled `wnsteam/bionic/steam.exe` is PE x86-64 and `steam32.exe` is PE i386. The Android Rust client is native, while x86 game/client code still needs translation. [FEX describes that translation boundary](https://github.com/FEX-Emu/FEX).

### Existing graphics path

| Owner | What the checkout already does | Remaining limit |
| --- | --- | --- |
| [GPUInformation](../app/src/main/runtime/system/GPUInformation.java) and [native Vulkan probe](../app/src/main/cpp/winlator/vulkan.c) | Query renderer, vendor ID, Vulkan version and extensions. The built-in driver filter rejects non-System choices on non-Adreno. | A recognizable renderer does not prove all required features or texture formats are supported. No second GPU probe library is needed. |
| [GraphicsDriverCatalog](../app/src/main/runtime/system/GraphicsDriverCatalog.kt), [setup selection](../app/src/main/feature/setup/SetupWizardActivity.kt) and [container creation](../app/src/main/runtime/container/ContainerCreation.kt) | Reuse installed driver selections and component defaults. | Imported drivers are appended without the built-in support filter, and container creation reuses the last installed driver. Trace and validate incompatible or stale selections before changing automatic defaults; this is a source finding, not a reproduced non-Adreno crash. |
| [XServerDisplayActivity.extractGraphicsDriverFiles](../app/src/main/runtime/display/XServerDisplayActivity.java) | In the X11/Bionic path, sets `VK_ICD_FILENAMES` to `share/vulkan/icd.d/wrapper_icd.aarch64.json`. An imported driver is loaded through Adrenotools; otherwise the wrapper uses system Vulkan. Stock, Leegao and GameNative wrapper assets and BCn options already exist. | Switching the ICD filename alone is not the missing integration. Wrapper selection, underlying driver, Wine ABI and DXVK version must agree. BCn compute has vendor/wrapper exclusions; enabling it globally is unsafe. |
| [WineWaylandSupport](../app/src/main/runtime/display/wayland/WineWaylandSupport.java) | Requires Adreno and compatible Wine/display components. The Wayland route uses a bundled Turnip driver. | Keep non-Adreno investigation on the existing X11/system-wrapper route first. The separate Linux Steam route also has its own driver requirements. |
| [VaporPreparation](../app/src/main/feature/setup/VaporPreparation.kt) | The current guided package list includes the pinned `DXVK-1.10.3-arm64ec-async-0` component. | Do not describe preparation as automatically installing DXVK 2.4+ and Turnip on every Snapdragon. Existing user configuration may differ from the preparation defaults. |

The EU3 emulator result used **Lavapipe/llvmpipe software Vulkan and a working WineD3D route after the selected DXVK route failed**. It did not establish host-accelerated Metal/MoltenVK gameplay. WineD3D can itself use Zink, so an OpenGL option does not necessarily bypass Vulkan. Preserve this tested per-game result rather than applying it to all old or 32-bit games. See [the emulator record](QUALIFICATION.md#development-check--2026-10-13).

The [Leegao wrapper's v0.0.5r5 notes](https://github.com/leegao/bionic-vulkan-wrapper/releases/tag/v0.0.5r5) describe support and fixes for particular Mali, older Adreno and Xclipse cases, including BCn and version-dependent extension handling. They are not a guarantee for every device; the exact bundled binary still needs to be matched to source before applying those release-specific assumptions. [DXVK's requirements](https://github.com/doitsujin/dxvk/wiki/Driver-support) vary by version and explicitly exclude first-party Android/mobile-driver support. [vkd3d-proton](https://github.com/HansKristian-Work/vkd3d-proton#drivers) requires specific Vulkan features. Neither an Adreno model name nor Xclipse's RDNA ancestry establishes a DirectX tier or BCn format support.

### What the Mantis comparison establishes

The [official Play listing](https://play.google.com/store/apps/details?id=app.mantispro.pcgames), checked on 15 October, advertises the features above, declares ads/in-app purchases and includes v0.1.2b notes. No Mantis APK, process trace or same-device game benchmark was inspected in this audit. The supplied target/min SDK values, split sizes, 36-library alignment result, mandatory Google sign-in and internal implementation remain unverified here.

In particular:

- `libzstd-jni` and JNA filenames do not prove JavaSteam is the Steam owner. Vapor itself [depends on zstd-jni](../app/build.gradle), and the inspected APK contains `libzstd-jni-1.5.7-9.so` despite its native Rust Steam downloader. Native Rust does not by itself establish faster downloads, lower total RAM or deterministic Cloud completion.
- Packaged library size is not resident memory. Codec and crash-reporting libraries cannot be assigned a gameplay RAM cost from APK sizes alone. Compare the whole process tree and actual loaded components.
- A filename containing `patchelf`, `redirect` or `frame_pacing` does not establish how it works or that it solves Android execution, frame hitches or page-size compatibility. Renamed libraries and Play licensing components also do not establish a license violation.
- Vapor's application and inherited host source are GPL-3.0; Valve client libraries remain proprietary and other runtime components retain their own licenses. The complete stack is not entirely GPL/open source. See [provenance](PROVENANCE.md).

There is no measured winner for game compatibility, download throughput, idle/game RAM or frame pacing. Ghostrunner Demo has not been tested in Mantis in this work.

### Concrete Android readiness result

The existing [ELF alignment checker](../tools/check-apk-16kb.sh) was run against `Vapor-0.1.0-library-logo-dev.apk` (687,230,035 bytes; SHA-256 `2cda26b470470e96fdc3deacc9ec0c5bc004a7e52a4a8948aee4dfef32908853`). It **failed**: **36/39** APK ARM64 libraries have at least 16 KB LOAD alignment; `libcrypto.so`, `libssl.so` and `libcurl.so` have 4 KB LOAD alignment. The current [build configuration](../app/build.gradle) still targets SDK 28. This check covers APK native-library ELF segments, not ZIP alignment, downloaded runtime binaries or execution on a 16 KB system.

[Android's 16 KB guidance](https://developer.android.com/guide/practices/page-sizes) separates packaging, native linking and runtime page-size assumptions. [Android's writable-directory execution restriction](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission) is another constraint. `patchelf` and a path redirect do not by themselves satisfy these requirements. A small split download is also not directly comparable to Vapor's APK with bundled runtime assets; compare complete installed inputs.

[User-initiated data-transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt) are a relevant API 34+ migration candidate for long downloads, wrapping the existing queue and persisted transfer state. They can still stop because of constraints, system health or user action; some user-stop paths kill the process without a job callback. They do not remove the need for durable progress and recovery. No UIDT service exists in the inspected Vapor source.

Vapor already has `onTaskRemoved` cleanup in [SessionKeepAliveService](../app/src/main/runtime/system/SessionKeepAliveService.java) and [SteamService](../app/src/main/feature/stores/steam/service/SteamService.kt). Test those owners rather than adding a competing watchdog. Forced termination must remain distinct from confirmed close-time Cloud completion. The bounded next work is recorded in [the existing roadmap](ROADMAP.md#cross-chipset-and-service-readiness--15-october-2026); this audit made no runtime change or new APK.

### Implementation follow-up — 16 October 2026

The first driver-selection change is now implemented in the same owners: imported-driver
filtering, preservation of saved choices, explicit pre-launch errors and accurate custom-driver
probes. A native context-type crash was also reproduced and fixed. See the [before/after checks
and development APK](QUALIFICATION.md#driver-validation-development-check--16-october-2026).
This does not qualify a new GPU family, automatic BCn settings or the complete translator chain.

The three remaining LOAD-alignment failures come from the prebuilt OpenSSL `1.1.1q-beta-1`
and curl `7.85.0-beta-1` Prefab AARs declared in [Gradle](../app/build.gradle).
[CMake](../app/src/main/cpp/CMakeLists.txt) imports `curl::curl`, whose generated configuration
links `openssl::crypto` and `openssl::ssl`, into the native content-I/O library. Their package
metadata points to [Android ndkports](https://android.googlesource.com/platform/tools/ndkports).
The app already passes `-Wl,-z,max-page-size=16384` for its own shared/executable linking;
that flag does not rebuild imported AAR payloads. The new development APK passes ZIP alignment
but remains at 36/39 for ELF LOAD alignment. Source rebuilds and 16 KB runtime qualification
were still pending at that checkpoint; see the source-build follow-up below. There
is still no Mantis performance comparison.

### Upstream reuse audit — 16 October 2026

Useful implementations exist, but the supplied claim that every roadmap item is
already solved in standalone, license-compatible modules is too broad. Checked
upstream source and release records establish the following narrower conclusions:

| Candidate | What was checked | Decision for Vapor |
| --- | --- | --- |
| [OpenNative](https://github.com/RemiPelloux/OpenNative) | Its build selects SDK 36/NDK 27.3, and [commit 183458e7](https://github.com/RemiPelloux/OpenNative/commit/183458e717d5504b064542a29cda0dc241708512) adds shared prefixes. Its README documents cache generations, executable filtering and sampled progress. These are source features, not measured improvements in Vapor. | Review individual mechanisms against Vapor's existing owners when a matching failure is reproduced. NDK 27.3 alone does not ensure 16 KB ELF alignment, and the inspected app Gradle file does not supply the claimed OpenSSL/curl rebuild recipe. Use AOSP ndkports and the libraries' own Android builds for the present blocker. |
| [IsItCompatible](https://github.com/mayusi/IsItCompatible/tree/7e61d76da33a28685f64fa26b9bf883d08f9da5f) | MIT app code; the inspected `EmuReadyCompatSource` deliberately returns no live result. Its bundled `emuready-snapshot.json` identifies build-time ingestion and a 2026-06-08 data date. It includes source links and hardware fields; this is not a ready-made Vapor AppID verification database. | Keep report provenance, age, runtime and device scope. Do not label another emulator's success as Vapor Verified or assume the app license covers every external dataset. No new API, remote configuration layer or report schema is added here. |
| DroidDeck [#287](https://github.com/Droid-Deck/DroidDeck/pull/287), [#347](https://github.com/Droid-Deck/DroidDeck/pull/347) | Merged component-copy and MSI extraction work. The latter uses Linux `msiinfo`/`cabextract`, interprets MSI tables and names unsupported custom-action cases. | A useful implementation reference, not a drop-in Android/Bionic module. Verify component acquisition, exact prefix paths and completed installation before adapting; do not copy success markers alone. |
| DroidDeck [#295](https://github.com/Droid-Deck/DroidDeck/pull/295), [#413](https://github.com/Droid-Deck/DroidDeck/pull/413) | Merged device-information/fallback naming work and a change moving the environment-override file to private storage. | Reuse hardware-reporting ideas at the existing GPU probe/export boundary. Apply storage hardening only to an equivalent exposed file. These do not independently qualify a selected Vulkan driver or translator. |
| DroidDeck [#270](https://github.com/Droid-Deck/DroidDeck/pull/270) | Fixes duplicate export of Android's aggregate battery as BAT0 and BAT1 into DroidDeck's Linux bridge. MangoHud counted the same battery twice. | This is **not a universal dual-cell calibration fix**. Do not divide Vapor's readings by two. First trace Vapor's own telemetry source and any duplicate exports. |
| [Leegao v0.0.5r5](https://github.com/leegao/bionic-vulkan-wrapper/releases/tag/v0.0.5r5) | Vapor's bundled wrapper library exactly matches the release asset; see [byte identity](PROVENANCE.md#bundled-leegao-wrapper-identity--16-october-2026). | Keep the existing binary. Depth/BCn workarounds need a demonstrated device/game case; no global flags are enabled. Imported-driver failure handling was already implemented in the preceding checkpoint. |
| [ExynosTools](https://github.com/WearyConcern1165/ExynosTools/tree/3dcdbcb2034e5f19a1606e54668e6a87f92476c6) | Source for an Xclipse compatibility layer and its target-device list are public. The inspected tree has no top-level LICENSE/COPYING file, and the sampled layer entry has no license header. The README describes a bundle that can also contain Samsung's driver. | Source visibility does not establish reuse/redistribution permission or device qualification. Do not import this layer or driver bundle until those specific inputs are resolved. This does not block the existing Snapdragon path. |

The first actual adaptation is the [OpenSSL/curl source build](PROVENANCE.md#native-network-source-build--16-october-2026)
inside the existing CMake build. This keeps the same versions and library interfaces,
adds 16 KB linker flags, and removes the two prebuilt Prefab dependencies. Exact
APK results and remaining runtime/target-SDK gates belong in [qualification](QUALIFICATION.md),
separately from the upstream research above.

#### Launch-rule follow-up — 16 October 2026

The subsequent blueprint contains useful checks, but its proposed blanket rules
need these corrections before implementation:

- **DXGI pairing is component-specific.** Current upstream
  [vkd3d-proton instructions](https://github.com/HansKristian-Work/vkd3d-proton#using-vkd3d-proton)
  require DXVK **2.1+** for the shared DXGI implementation; the proposed `>= 2.0`
  cutoff is insufficient for that upstream version. Vapor's
  [starter packages](../app/src/main/feature/setup/VaporPreparation.kt) pin a custom
  ARM64EC DXVK 1.10.3 build and do not download VKD3D. A fresh installation defaults
  VKD3D to `None`; existing installed components can change that selection through
  `ContainerCreation`. `extractDXWrapperFiles` applies the chosen DXVK profile
  followed by the chosen VKD3D profile. A future guard must examine the selected
  builds, actual DXGI payload and D3D12 use. The presence of a VKD3D selection alone
  does not establish that a D3D9/11 game loads it. No broad launch block was added.
- **GPU vendor is not a D3D12 capability test.** Requirements must be checked
  against the effective wrapper/driver features and the selected translator
  version. A non-Adreno name alone does not prove missing functionality. The
  preceding imported-driver checks already cover selection/payload/probe failures;
  complete translator feature validation remains open.
- **Several proposed helpers already have an owner.** `findGameExe` excludes
  crash reporters, redistributable installers, setup and launcher names. Prefix
  setup already copies selected redistributables. These paths need targeted
  evidence for missing cases before importing another selector or installer.
  Generation-tagged per-game caches remain a candidate, not an implemented or
  benchmarked Vapor improvement.
- **Turnip flags are measured workarounds.** The inherited container defaults
  already include `TU_DEBUG=noconform,sysmem`; adding `sysmem` again changes
  nothing. `nolrz` is not established as a universal UE4/Adreno 8xx improvement.
  [Mesa's Turnip documentation](https://docs.mesa3d.org/drivers/freedreno.html#autotune)
  describes autotuning and recommends a preference mode over forcing `sysmem`
  for performance tuning where supported. Preserve the known working defaults
  until a matched device/game test demonstrates a better setting.

The battery correction in DroidDeck #270 is still duplicate-export removal, not
dual-cell calibration. For the proposed phone comparison, EU3 needs a supported
height of at least 768 pixels (for example 1024×768); a blanket 1280×720 test would
reintroduce its known display limitation. Neither commercial APK component names
nor library sizes establish mandatory sign-in behavior, resident RAM use or a
lowest-overhead winner. Those claims need direct product and matched-run evidence.
