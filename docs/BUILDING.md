# Building Vapor

## Baseline

The starting source is WinNative commit `8409085f706acbc0749881d9bf1e85f2f347dfd4`. Build it before comparing Vapor changes. Keep the Git history, LFS objects, submodules and license notices.

| Tool/input | Version used |
| --- | --- |
| JDK | 17 (local baseline: OpenJDK 17.0.2) |
| Gradle wrapper | 8.10.2 |
| Android Gradle Plugin | 8.8.0 |
| Kotlin | 2.1.10 |
| Compile SDK / build tools | 35 / 35.0.0 |
| NDK | 27.3.13750724 |
| CMake | 3.22.1 |
| Rust | 1.99.0, `aarch64-linux-android`; pinned in `rust-toolchain.toml` |
| Windows host cross compiler used locally | LLVM-MinGW 20261006 UCRT, macOS universal |
| Minimum / target SDK | 29 / 28 (legacy execution baseline) |
| APK ABI | arm64-v8a |

## Prepare the checkout

```sh
git lfs install --local
git lfs pull origin
git submodule update --init --recursive
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.3.13750724' 'cmake;3.22.1'
rustup show
```

`origin` is Vapor and supplies the committed LFS assets. The original development checkout also has an `upstream` remote for WinNative; add `https://github.com/WinNative-Emu/WinNative.git` as `upstream` only when comparing the donor baseline. Set `sdk.dir` in local, ignored `local.properties`, or use `ANDROID_HOME`. Do not commit signing keys, machine paths, accounts, or tokens.

## Rebuild the Windows Steam host

Gradle packages these executable assets; it does not compile their C++ source. **After changing `wn-steam-launcher` source, rebuild both 32-bit and 64-bit hosts before building the APK.**

The existing script accepts GNU MinGW POSIX compiler names by default. On macOS, use the pinned [LLVM-MinGW archive](https://github.com/mstorsjo/llvm-mingw/releases/download/20261006/llvm-mingw-20261006-ucrt-macos-universal.tar.xz), verify SHA-256 `a7a19b11048e89372637d26279cdb7c430ed3cf8c1e224310421312bf986ebf6`, extract it, and put its `bin` directory on PATH for this command:

```sh
CXX=x86_64-w64-mingw32-clang++ STRIP=x86_64-w64-mingw32-strip CXX32=i686-w64-mingw32-clang++ STRIP32=i686-w64-mingw32-strip bash app/src/main/cpp/wn-steam-launcher/build.sh
```

The script also rebuilds both interface probes. It fails if the 32-bit host compiler is missing. Keep source and executable changes together. GNU and LLVM toolchains need not produce byte-identical executables; record the actual toolchain with each qualification result.

The conflict-answer parser has a portable regression check. Run it with the host C++ compiler; this does not launch Steam or exercise Windows cloud synchronization:

```sh
c++ -std=c++17 -Wall -Wextra app/src/main/cpp/wn-steam-launcher/tests/cloud_conflict_answer_test.cpp -o /tmp/vapor-cloud-conflict-answer-test
/tmp/vapor-cloud-conflict-answer-test
```

## Overlapping Steam host exit checks

`shutdown_overlap_test.cpp` links the production shutdown source. Build both
guest widths with LLVM-MinGW (the local fixture record uses 20250920/Clang 21.1;
packaged hosts use the existing 20261006 toolchain above):

```sh
for arch in x86_64 i686; do
  "$arch-w64-mingw32-clang++" -std=c++17 -O2 -Wall -Wextra -Werror \
    -Wno-unused-parameter -static \
    app/src/main/cpp/wn-steam-launcher/tests/shutdown_overlap_test.cpp \
    app/src/main/cpp/wn-steam-launcher/clean_shutdown.cpp \
    -lkernel32 -luser32 -o "shutdown-overlap-$arch.exe"
done
```

Run each executable through Vapor's existing `boot_exe` path in an **isolated,
account-free** container. The fixture uses the production
`C:\wn-launcher.shutdown` sentinel: do not run it beside a game or genuine
Steam host, and preserve any pre-existing sentinel instead of deleting it. It
writes `<exe>.result` plus eight child traces beside itself. Require
`result=PASS checks=8 failures=0` for each architecture. The game-first traces
must show `sentinel deferred` before `TEST owner released` and then one
`clean logoff complete`. This confirms watcher participation while the original
owner is held, rather than merely observing process exit code 0.

Each architecture runs two cycles of natural, game-first, sentinel-first and
concurrent duplicate requests. The original source fails both game-first cases;
the corrected source passes all eight. The owner is paused in a test log sink;
there is no Steam module, account or Cloud transfer. Preserve negative-control
traces, keep the same Wine/FEX/settings, restore the container and registry
snapshots, and remove only the test-owned executables/results afterward.

Pair with the existing `SteamCloseSyncTest`, `SteamHandoffTest`,
`SteamInstallRecoveryTest`, `SteamExitCloudTest`, `WnLauncherStatusTailerTest` and
`GameSessionFailureTest` (53 JVM checks in the current record). The existing
`SteamCloseSyncUiTest` and `GameSessionFailureDialogTest` supply four simulated
reservation/error UI cases. None replaces real-account repeat-exit and remote
save/restore qualification. See [results](QUALIFICATION.md#overlapping-steam-host-exit-requests--16-october-2026).

## Native network dependencies

The existing native content downloader now builds **OpenSSL 1.1.1q and curl 7.85.0**
from checksum-pinned source archives via [NativeNetwork.cmake](../app/src/main/cpp/NativeNetwork.cmake).
The previous ndkports Prefab AARs shipped 4 KB-aligned libraries that could not be
fixed by the application's linker flags. There is no manual `.so` replacement or
change to the global Gradle cache. Gradle's normal native build owns these outputs.

Use a Linux or macOS build host with **Perl and make**, in addition to the toolchain
above. The first build downloads about 14 MB of source and compiles these libraries;
subsequent builds reuse CMake's existing outputs. Native Windows hosts are not
supported by this Autoconf/OpenSSL path; use Linux/WSL for that workflow. The build
retains the library versions, `libcrypto.so` / `libssl.so` / `libcurl.so` SONAMEs,
OpenSSL TLS backend and Android CA-directory setting. It passes both
`-Wl,-z,max-page-size=16384` and `-Wl,-z,common-page-size=16384` to the three library
links. Source URLs, checksums and packaged notices are listed in [provenance](PROVENANCE.md).

Check the resulting APK with both:

```sh
bash tools/check-apk-16kb.sh app/build/outputs/apk/standard/debug/standard.apk
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 app/build/outputs/apk/standard/debug/standard.apk
```

`com.winlator.cmod.shared.io.NativeContentIOTest` runs the actual JNI curl/OpenSSL
path against an on-device loopback TLS server. Its five checks cover trusted
HEAD/download, untrusted certificates, hostname mismatch, truncated bodies and HTTP
failure. Failure checks retain an existing file and remove the native partial file.
The public synthetic certificates/key are packaged only in the test APK. No Steam
account, external server, replacement trust store or new test dependency is involved.
Run the suite with `StoreUiTestRunner` on isolated 4 KB and 16 KB AVDs. These checks
qualify native content I/O, not the Rust Steam depot queue or Wine/FEX execution.

## Build and focused tests

For driver selection, include `--tests '*GraphicsDriverCatalogTest'`,
`--tests '*VaporPreparationTest'` and `--tests '*GameSessionFailureTest'` in
`:app:testStandardDebugUnitTest`. The driver suite replaces the native renderer query
with fixtures while exercising the real setup, catalog, file checks and container defaults.
It does not qualify a physical GPU. `GraphicsDriverProbeDeviceTest` in
`com.winlator.cmod.runtime.system` runs the actual JNI probe: missing or broken custom
drivers must not report the system renderer, and failed probes must leave the system
probe usable. Run it with the existing `StoreUiTestRunner` on an isolated, account-free
AVD, alongside `GameSessionFailureDialogTest` and `GameSettingsNavigationTest` for the
error recovery controls. The emulator needs working system Vulkan for this selection.

For automatic graphics attempts, run `AutomaticGraphicsTest`, `VaporPreparationTest`,
`GraphicsDriverCatalogTest`, `WnLauncherStatusTailerTest`, `SteamCloseSyncTest` and
`SteamExitCloudTest` through the same JVM task. These 79 selected cases cover the
bounded plan, explicit-setting preservation, minimum feature bits, recognized
Vulkan initialization errors, benign DirectX feature probes, cancellation,
component/game fingerprints, process/frame evidence and cleanup/Cloud gates.
`PreloaderDialogUiTest` checks the production loading layout and one-shot controller
B cancellation; pair it with `GraphicsDriverProbeDeviceTest` for real JNI features.
The tests do not launch a Steam game or validate a physical driver's recovery.

For shared Steam web sign-in and the frame limit, include `--tests '*SteamWebSessionTest'` and
`--tests '*ShortcutFrameLimitTest'`. `SteamWebPanelUiTest` uses local HTML and synthetic authentication,
including real platform cookie cleanup and gamepad events delivered to the dialog window.
The shared dialog modifier translates A at that window boundary; X/Y must not activate controls. It never
requests a Steam page. `GameMenuUiTest` exercises the existing Performance control and touch isolation.
Run these with the bare-Application `StoreUiTestRunner` on an isolated, account-free AVD; do not use
an owner's signed-in emulator for fixtures. These checks do not qualify Steam-side acceptance,
phone framerate/power, live Cloud/achievements/chat, or physical controllers.

For achievements, run the existing JVM task with `--tests '*SteamAchievementsTest'`,
`--tests '*SteamHandoffTest'` and `--tests '*SteamWebSessionTest'` (see qualification for current counts). Synthetic native
responses exercise unknown/locked/unlocked states, malformed data, fresh reads, account changes
and ownership guards. Pair `com.winlator.cmod.feature.stores.steam.achievements.SteamAchievementsUiTest`
with `com.winlator.cmod.app.shell.SteamShellUiTest` using `StoreUiTestRunner` for 15 Android UI
checks. The achievement suite saves two explicitly synthetic screen captures to the app's external
files directory. These tests do not unlock achievements or qualify real web authentication.

For Friends and chat, run the existing JVM task with `--tests '*SteamConversationTest'`,
`--tests '*SteamChatServiceTest'` and `--tests '*SteamHandoffTest'` (24 checks). These use
synthetic messages and a mocked native session to exercise acknowledgements, draft retention,
deduplication, cancellation and refusal to reconnect during game ownership. They send no messages.
`SteamChatUiTest` adds eight Compose checks, including keyboard send, offline/handoff state,
explicit retry and a narrow layout at 150% text size. Pair
`com.winlator.cmod.feature.stores.steam.friends.SteamChatUiTest` with
`com.winlator.cmod.app.shell.SteamShellUiTest` using `StoreUiTestRunner` for the 15-check UI
selection. Test the normal application after instrumentation ends; account-side delivery,
restrictions, unread state and real reconnect remain separate acceptance checks.

`GameEngineHintsTest` and `PEHelperTest` check read-only engine hints and PE CPU metadata using
temporary file fixtures. Pair them with `GameDisplaySettingsTest` and `GameLaunchTimingTest`
for the 33-check JVM selection. No fixture runs a PC game.

`com.winlator.cmod.runtime.compat.gamefixes.GameEngineHintsDeviceTest` adds six Android checks
using the existing `StoreUiTestRunner`: case-sensitive filename collisions, Windows-style paths,
symlink containment, PE parsing and the production logging wrapper. The wrapper test restores
logging preferences and writes `vapor-engine-hints-fixture.log` to the test device's app external
files directory, explicitly labeled as synthetic evidence. Finish instrumentation before testing
the normal application.

On an actual Steam game launch, open **Settings → Debug**, enable **Application logging**,
expand **Logcat** and enable its **Application logging** switch to record the `GameEngineHints`
line in the exported application log. It describes the selected launch executable, which may be a launcher
rather than the eventual game process. `settings_changed=false` means the inspector applies no
fix; existing game-specific fixes remain separate. Missing, ambiguous or unsupported layouts
produce Unknown and continue through the existing launcher. With diagnostic logging disabled,
the inspector does not run. Use the existing log export; no separate diagnostics service is needed.

`GameMenuUiTest` exercises the production session display host over an Android View fixture. It checks menu/return routing, outside-touch isolation, profile selection surviving dismissal, popup cleanup and a 280×320 dp viewport at 150% text size. Run `com.winlator.cmod.runtime.display.GameMenuUiTest` with the existing `StoreUiTestRunner`; pair it with `com.winlator.cmod.app.shell.HomeUiTest` for the 11-check menu/Home selection. These tests start no Wine process or Steam session. The normal application and a real game must be checked separately.

The install-to-Play regression is `SteamInstallRecoveryTest`. It exercises the existing Room records and file markers, including a stale not-downloaded record, incomplete downloads and foreign-store folders.

`GameDisplaySettingsTest` exercises the existing launch-fix hook with actual temporary files and Room metadata. It checks resolution matching, the original backup, repeated launches, malformed files, failed backup creation, and isolation from unrelated titles. `GameSessionFailureTest` covers the optional game-settings destination surviving the existing Intent/saved-state handoff.

`SteamClientLibraryTest` exercises real generated client shortcuts, preserves real games (including a game named Steam), and filters an old cached client card during upgrade.

`SteamCatalogTest` also checks separate demo AppIDs on paid parent pages. `SteamWebSessionTest` checks exact HTTPS Steam origins, install-link routing and secure cookie construction. `SteamCatalogInstallUiTest` covers native demo/owned-game actions, explicit account setup, native connection for web features and failure/retry. Its signed-out JNI check confirms the packaged methods exist, not that Steam grants a license. Use an isolated AVD for the bare-Application runner and wait for instrumentation to finish before launching the normal application.

Run `cargo test --locked` from `app/src/main/cpp/wn-steam-client/rust` for native protocol checks. They include free-license wire fields, response parsing and job routing. A live account is still required to qualify license acquisition and web authentication; never invent ownership or clear a user's account to simulate that test.

`VaporPreparationTest` verifies archive size/hash validation, incomplete installed files, reuse of a customized container and save file, and default-container selection. `SessionKeepAliveServiceTest` covers foreground registration before a fast operation can stop the service, plus an active download retaining it. For a real first-run test, use an isolated AVD with no account or games, launch the normal Application, grant file access and run Prepare Vapor. Test a network failure/retry, automatic completion, return to Library and a repeated preparation without duplicate environments. Never clear a user’s existing account/game data to perform this check.

The Android UI checks `StoreGameDetailPlayTest`, `StoreGameDetailBranchPickerTest`, `GameSettingsNavigationTest`, `GameSessionFailureDialogTest`, `SteamClientDialogTest`, `PrepareVaporScreenTest`, `LibraryViewUiTest`, `SteamShellUiTest`, `HomeUiTest` and `SteamCloseSyncUiTest` use the existing `StoreUiTestRunner`. That runner supplies a bare Application: these tests verify Compose actions and layout, not Steam authentication, runtime startup or game play. Build them with `:app:assembleStandardDebugAndroidTest`, install the app and test APKs on the test device, then select these classes with `am instrument`. Normal application testing is a separate required step. `GameSettingsNavigationTest` is in `com.winlator.cmod.feature.library`; the new close-sync UI test is in `com.winlator.cmod.app.shell`.

`SteamCloseSyncTest` exercises pending/overlapping uploads, failure cleanup, cancelled deletion, and save-file ordering without a Steam account. `SteamInstallRecoveryTest` also enters the real `SteamService.deleteApp` method and checks that a pending upload keeps its marker, save and install record intact. `SteamCloseSyncUiTest` uses a simulated reservation with the production game page and theme; it scrolls to the actions, verifies the disabled status and closing of an already-open confirmation, and captures the test-game state in portrait and landscape in the test app's external files directory. These do not replace a genuine Steam-host exit/upload/uninstall check on a phone.

```sh
./gradlew :app:assembleStandardDebug :app:testStandardDebugUnitTest \
  --tests '*SteamCatalogTest' \
  --tests '*SteamWebSessionTest' \
  --tests '*SteamLaunchRequirementsTest' \
  --tests '*WnLauncherStatusTailerTest' \
  --tests '*SteamLaunchExeFamilyTest' \
  --tests '*GameLaunchTimingTest' \
  --tests '*PreloaderDialogStateTest' \
  --tests '*GameSessionFailureTest' \
  --tests '*SteamCloudConflictDialogTest' \
  --tests '*SteamInstallRecoveryTest' \
  --tests '*SteamCloseSyncTest' \
  --tests '*GameDisplaySettingsTest' \
  --tests '*GameEngineHintsTest' \
  --tests '*PEHelperTest' \
  --tests '*SteamClientLibraryTest' \
  --tests '*LibraryViewTest' \
  --tests '*DeviceProfileTest' \
  --tests '*LibraryStoreLinkTest' \
  --tests '*VaporPreparationTest' \
  --tests '*SessionKeepAliveServiceTest' \
  --tests '*ScreenSizesTest' \
  --max-workers=4 --console=plain
```

APK: `app/build/outputs/apk/standard/debug/standard.apk`. Package: `app.vapor.android`. Development default: version `0.1.0-dev`, version code `1`; `VERSION_NAME` and `VERSION_CODE` can be supplied for a deliberate release. `BUILD_ID` identifies the source revision; local dirty builds must record their diff separately.

Clean rebuilds of the identical source state are **not byte-for-byte
reproducible** here: three dex entries and `libcrypto.so` differed between two
builds, and ZIP entry ordering/alignment padding differed, while the entry set,
uncompressed sizes, signing certificate and 16 KB alignment stayed valid. Do not
treat APK hashes as reproducible across machines; see the
[reproducibility receipt](evidence/2026-10-21-apk-rebuild-reproducibility.json).

Debug builds use the normal local debug key unless signing is explicitly configured. They are development artifacts, not official Vapor releases. Runtime redistribution review and release signing remain open gates.

The 15 October Cloud-exit reporting update is recorded in
[qualification](QUALIFICATION.md#exit-cloud-status-and-diagnostics--15-october-2026).
Its focused JVM check uses:

```sh
./gradlew :app:testStandardDebugUnitTest \
  --tests 'com.winlator.cmod.runtime.display.SteamExitCloudTest' \
  --tests 'com.winlator.cmod.feature.stores.steam.service.SteamCloseSyncTest' \
  --tests 'com.winlator.cmod.feature.stores.steam.service.SteamHandoffTest' \
  --max-workers=2 --console=plain
```

This is a log/status and shutdown-guard check, not a Steam Cloud transfer test. Reproduce the
phone exit with its actual account and save before claiming upload or restore success.

## Emulator smoke test

Use a fresh ARM64 AVD (Apple Silicon hosts can accelerate ARM64). The tested image is `system-images;android-36;google_apis;arm64-v8a`, revision 7. Keep other projects' AVDs separate.

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/standard/debug/standard.apk
adb -s emulator-5554 shell am start -W -n app.vapor.android/com.winlator.cmod.app.shell.UnifiedActivity
```

Check first launch, permission requests, system-file extraction, navigation, Steam login display, process restart, and installation over the same signed package. An emulator with SwiftShader does not test Adreno/Turnip, Snapdragon CPU translation performance, phone thermals, or physical controller/audio behavior. Do not bypass Android execution restrictions to make a modern-target claim.

The existing `StoreUiTestRunner` substitutes a bare Application for isolated tests. Such instrumentation does not replace testing the actual `PluviaApp` startup above.

## 16 KB check

```sh
bash tools/check-apk-16kb.sh app/build/outputs/apk/standard/debug/standard.apk
```

The [16 October native-library rebuild](QUALIFICATION.md#native-library-alignment-and-tls--16-october-2026)
replaces the three inherited 4 KB Prefab libraries with source-built OpenSSL/curl.
The current check passes **39/39** APK native libraries. Also run
`zipalign -c -P 16 -v 4 app/build/outputs/apk/standard/debug/standard.apk` using the
Android SDK build-tools. APK alignment and the recorded JNI checks on 4 KB/16 KB
emulators do not qualify downloaded runtime binaries or complete game execution.
In particular, the FEX 2610 candidate's two Unix libraries still have 4 KB LOAD
alignment. Target SDK remains 28; modern-target execution remains a separate gate.

`LibraryViewTest` checks the owned/installed projection, completion/removal transitions, shared-install aliases and search. `DeviceProfileTest` includes the five-column wide default; explicit profile choices remain intact. `LibraryViewUiTest` covers tabs, signed-out/empty recovery, card selection, controller A release routing and an explicitly labeled sample render. Its optional public artwork download is not an assertion dependency. The sample rows are never inserted into an account or install database.

`SteamShellUiTest` checks the compact account row, cancellation versus confirmed sign-out, narrow layouts with larger text, Quick Access callback routing, and the dedicated More stores account/browse actions. `LibraryViewUiTest` requires Connect Steam in the signed-out view and keeps other store choices out of that screen. `SteamCatalogInstallUiTest` also checks the Steam-only welcome screen, connected-account continuation and Set up later. Account states are artificial; these checks never sign in, sign out, change storage, or modify media volume.

For the Steam-focused navigation change, select the 26 checks in `SteamCatalogInstallUiTest` (11),
`SteamShellUiTest` (7), `HomeUiTest` (6) and `SteamCloseSyncUiTest` (2), all under
`com.winlator.cmod.app.shell`. Pass their comma-separated fully qualified names using `-e class`
to `am instrument -w -r app.vapor.android.test/com.winlator.cmod.StoreUiTestRunner`.
Run the normal app only after instrumentation exits. Separately check Settings → Accounts & storage
→ Other stores → each Browse action, and L1/R1 cycling through Home, Library, Store and Downloads.
The optional stores should never appear as primary tabs. This does not qualify physical controllers
or authenticated store behavior.

`HomeUiTest` checks selected-game actions, honest no-history labeling, card callbacks, small/large-text layouts, and Home controller routing. Its populated review capture uses labeled sample data without database writes. `LibraryViewTest` also covers the bounded Home projection and existing Windows/retro history-key formats.

## Windows translation smoke probes

For the **19 October Wine X11 source build and external-pointer comparison**, see
[the Wine component instructions](../tools/wine/README.md). They use the identified
Proton base, pinned Termux dependencies and three unmodified upstream patches.
The GUI-subsystem probe is built in both guest widths and receives real Android
pointer events while testing cursor clipping. This is separate from the console
translation checks below and does not build or requalify an APK.

`tools/tests/fex-smoke.c` is an account-free Windows program for comparing installed
FEX components through Vapor's normal display-host launch path. Build the same source
for both guest architectures with LLVM-MinGW (the local check used 20261006 UCRT):

```sh
i686-w64-mingw32-clang -std=c11 -O2 -Wall -Wextra -Werror tools/tests/fex-smoke.c -o fex-smoke-i686.exe
x86_64-w64-mingw32-clang -std=c11 -O2 -Wall -Wextra -Werror tools/tests/fex-smoke.c -o fex-smoke-x86_64.exe
adb -s emulator-5554 shell mkdir -p /sdcard/Download/VaporFexSmoke
adb -s emulator-5554 push fex-smoke-i686.exe fex-smoke-x86_64.exe /sdcard/Download/VaporFexSmoke/
```

Use an isolated, prepared ARM64 AVD with 4 KB pages, the existing `D:` Downloads
mapping, and no Steam account or real game saves. Select the test container's FEX
version while keeping Wine, graphics, preset and cache setting fixed. The example
uses container 1; use the actual fixture ID. Remove any earlier `.result` file before
each run. Keep the Windows-path quotes inside the remote shell command:

```sh
adb -s emulator-5554 shell rm -f /sdcard/Download/VaporFexSmoke/fex-smoke-x86_64.exe.result
adb -s emulator-5554 shell 'am start -n app.vapor.android/com.winlator.cmod.runtime.display.XServerDisplayActivity --ei container_id 1 --es boot_exe "D:\VaporFexSmoke\fex-smoke-x86_64.exe"'
adb -s emulator-5554 shell cat /sdcard/Download/VaporFexSmoke/fex-smoke-x86_64.exe.result
```

Wait for the result before reading it and repeat with `i686`. Each program checks
integer arithmetic, overlapping memory moves and Windows event/thread/atomic
behavior, writes `result=PASS failures=0` beside itself, and exits. Record the
launcher-selected FEX version and hashes of both applied DLL/Unix-library pairs;
a success from an old result file or a different selected component does not count.
Close the fixture session between runs. If using force-stop for isolation, record
that fact rather than treating it as a clean Steam shutdown test.

The [2610 candidate record](QUALIFICATION.md#fex-2610-development-candidate--16-october-2026)
compares 2607 and 2610 using this probe. It does not test DirectX, a game, Steamworks,
Cloud, sound, translation performance or a 16 KB runtime. Keep those checks separate.

For the separate cache experiment, see [the FEX patch build and repeat protocol](../tools/fex/README.md).
That builds ARM64EC/WOW64 DLLs with bylaws LLVM-MinGW 20250920, preserves the existing
Unix libraries, and packages a distinct WCP. It does not run through Gradle or change
guided preparation. [The cache result](QUALIFICATION.md#fex-disk-cache-experiment--16-october-2026)
records actual reuse, cross-architecture eviction before/after, executable invalidation,
file-limit checks and remaining phone/game gates.

## DXVK release candidate and graphics probe

The local graphics candidate builds unmodified DXVK tag `v3.1.1`, commit
`b1a1c99ab52b687cf950d62c88bc2fa316b41663`, with recursive submodules. Use the
bylaws LLVM-MinGW 20250920 compiler described in the FEX build instructions.
Meson 1.9.1, SDK CMake's Ninja 1.10.2 and official glslang 16.6.0 were used locally.
The [glslang macOS universal release archive](https://github.com/KhronosGroup/glslang/releases/download/16.6.0/glslang-16.6.0-macos-universal-release.tar.gz)
is 14,356,510 bytes, SHA-256
`4f8c05f2888a73ed0b7b274bc524af9130e09ae94b43c0f3f9ed83cfae852834`.
These are build tools outside the app dependency graph.

Reuse the cross files from the pinned
[component workflow](https://github.com/nicholasx417/WinNative-Components/blob/ce9f4d538c57ffae17ebafa9d6087dc377e0c11e/.github/workflows/Dxvk-nightly-Gplasync.yml):
ARM64EC uses `arm64ec-w64-mingw32-clang{,++}`, `cpu_family = aarch64`;
x86 uses `i686-w64-mingw32-clang{,++}`, `cpu_family = x86`. Both use LLVM ar/strip,
the corresponding windres/widl, Windows little endian and `needs_exe_wrapper = true`.
Do not apply the workflow's async or binary-semaphore patches in this experiment.

```sh
meson setup build-ec --cross-file ../ec.txt --buildtype release -Dstrip=true
meson compile -C build-ec -j 2
meson setup build-x86 --cross-file ../x86.txt --buildtype release -Dstrip=true
meson compile -C build-x86 -j 2
```

Copy `d3d8.dll`, `d3d9.dll`, `d3d10core.dll`, `d3d11.dll` and `dxgi.dll` from each
build. The existing WCP format maps ARM64EC files from `system32/` and x86 files
from `syswow64/`. Keep ARM64EC symbols and strip only the packaged x86 copies with
`llvm-strip --strip-all`, matching that workflow. The profile's distinct version is
`3.1.1-arm64ec-vapor`, code `0`; include upstream licenses, build inputs and the
release's `VP_DXVK_requirements.json`. The profile contains ten DLL mappings. Verify
COFF machine types, required exports and payload hashes; PE section alignment is
not the APK ELF page-size check.

Build the original account-free probe with the same toolchain:

```sh
i686-w64-mingw32-clang -std=c11 -O2 -Wall -Wextra -Werror tools/tests/dxvk-smoke.c -o dxvk-smoke-i686.exe -ld3d11 -ldxgi -ldxguid
x86_64-w64-mingw32-clang -std=c11 -O2 -Wall -Wextra -Werror tools/tests/dxvk-smoke.c -o dxvk-smoke-x86_64.exe -ld3d11 -ldxgi -ldxguid
```

Use the same isolated 4 KB ARM64 AVD and normal `XServerDisplayActivity` boot-EXE
path described above, with `D:\VaporDxvkSmoke\<probe>.exe`. Hold Wine, FEX, preset,
resolution and graphics settings fixed while changing DXVK. Record each APK's hash
and emulator backend. `DXVK_LOG_PATH` can point to a pre-created per-run directory
under `D:/VaporDxvkSmoke/logs/`. Capture `.result`, DXVK logs and actual applied
DLL hashes. The probe writes a test marker through `DXVK_SHADER_CACHE_PATH`; verify
its bytes in the container's `.cache` directory. This verifies path access only.
Actual modern shader-cache files require successful device/shader initialization.

The graphics check creates a D3D11 device/swapchain, compiles original HLSL using
Wine's `d3dcompiler_47.dll`, draws, reads back a pixel and presents. Success requires
RGBA approximately `(64,128,191,255)`. A device-creation failure is recorded as a
failure, with its HRESULT and DXVK explanation; loading DLLs alone is insufficient.
Software rendering is a separate environment from a phone GPU and supplies no FPS
comparison. Keep the original component and restore both selected settings and
applied payloads after testing. See [the bounded result](QUALIFICATION.md#dxvk-311-candidate-and-cache-path--16-october-2026).

The focused JVM checks are `DXVKConfigUtilsTest`, `VaporPreparationTest` and
`GraphicsDriverCatalogTest`. Guided preparation must select its pinned DXVK even
when a newer experimental component is installed, require both guest DLL sets,
and preserve an existing container's settings and saves on retry.

## Android Turnip candidate

The local step-4 candidate uses Mesa `a51a418991f883413f10f66cd52398d590a8257b`
and [the consolidated donor patch](../tools/turnip/android-driver.patch). This is
the Bionic Android driver, separate from `tools/linuxfs/build-turnip.sh` and its
glibc output. Do not replace one with the other. Attribution, patch adaptations
and license scope are recorded in [tools/turnip](../tools/turnip/README.md).

Source archive URL:
`https://gitlab.freedesktop.org/api/v4/projects/176/repository/archive.tar.gz?sha=a51a418991f883413f10f66cd52398d590a8257b`.
The observed download is 134,201,401 bytes, SHA-256
`e99bf3a5256458c0c02fadb7684b92367c4ac02f5667c3107ab7963a854863cc`.
Use a fresh source directory, verify this digest, unpack with one leading directory
removed, and apply the patch with `patch -p1 --fuzz=0`. Its 13 output files were
checked against the actual compiled source; do not silently accept skipped patches.

The checked build used NDK `27.3.13750724`/Clang 18.0.4, Meson 1.9.1, Ninja 1.10.2,
glslang 16.6.0, GNU Bison 3.8.2, Mako 1.3.10, MarkupSafe 3.0.4, PyYAML 6.0.3 and
packaging 25.0 on Apple Silicon macOS. macOS's bundled Bison 2.3 cannot generate the
IR3 parser. A local Bison build used the official 3.8.2 tarball, SHA-256
`9bba0214ccf7f1079c5d59210045227bcf619519840ebfa80cd3849cff5a5bf2`.
No system-wide tool installation is required. Meson's pinned wraps resolved
libarchive 3.7.2 and libxml2 2.11.6; their source/patch digests are in the build inputs.

Create an Android Meson cross file using the NDK's
`toolchains/llvm/prebuilt/darwin-x86_64/bin` (or the corresponding host directory):

```ini
[binaries]
c = '/absolute/ndk/bin/aarch64-linux-android34-clang'
cpp = ['/absolute/ndk/bin/aarch64-linux-android34-clang++', '-static-libstdc++']
ar = '/absolute/ndk/bin/llvm-ar'
strip = '/absolute/ndk/bin/llvm-strip'
pkg-config = 'false'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
[built-in options]
c_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384']
cpp_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384']
```

Put the listed host tools on `PATH`; a native Meson file can explicitly name the
modern `bison`. Build only the Android KGSL driver:

```sh
meson setup build source --cross-file android.ini --native-file native.ini \
  --buildtype release -Dcpp_args=-Wno-c++11-narrowing \
  -Dplatforms=android -Dplatform-sdk-version=34 -Dandroid-stub=true \
  -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl -Dgallium-drivers= \
  -Dvideo-codecs= -Dglx=disabled -Degl=disabled -Dgbm=disabled \
  -Dllvm=disabled -Dopengl=false -Dandroid-libbacktrace=disabled
ninja -C build -j 3 src/freedreno/vulkan/libvulkan_freedreno.so
```

Strip a copy with the selected NDK `llvm-strip`; retain the original and logs.
Package it through the existing Adrenotools ZIP format (`meta.json` at the root,
`libraryName=libvulkan_freedreno.so`, `minApi=34`), including the source patch,
build inputs and all notices. This source revision needs the recorded Clang
narrowing diagnostic suppression; warning/build retries are retained in the logs.

The four driver LOAD segments must have `0x4000` alignment. Inspect `DT_NEEDED`
as well: this build links Android `libhardware`, `liblog`, `libnativewindow`,
`libsync`, `libm`, `libz`, `libdl` and `libc`. Alignment alone does not establish
loading inside the app's driver namespace, GPU support, or 16 KB runtime behavior.

The existing Windows graphics probe now also accepts `--stress` (pass it through
the launch activity's `boot_exe_args` for the test fixture). It renders 240 changing
colors, checks five pixels in each of 18 readbacks, presents each frame and resizes
the buffers twice. Frames between readbacks exercise repeated uploads/presentation
without a readback after every frame. This is a correctness probe, not a queue
profiler or game benchmark. The separate [source wait test](../tools/tests/turnip-kgsl-wait.py)
checks the KGSL fix with mocked clock/ioctl calls. Keep those evidence categories
separate from actual Turnip phone rendering and power measurements.

### KGSL wait-any source refactor — 21 October 2026

The later source experiment uses Mesa
`f28352d1179a4a85b4be286a883747bae2d3786b`, with archive URL
`https://gitlab.freedesktop.org/api/v4/projects/176/repository/archive.tar.gz?sha=f28352d1179a4a85b4be286a883747bae2d3786b`.
Observed archive: **134,338,825 bytes**, SHA-256
`0e1068773f16d89f6836969d4ff1b9188769fbb8cf1454b6c6a88db648e150f8`.
Use a separate source/build directory; retain the preceding experiment.

The toolchain and API-34 configuration above remain the same. Put the retained
glslang 16.6.0 binary directory on `PATH` alongside modern Bison and the build
Python environment. Apply `tools/turnip/android-driver.patch` without fuzz and
build the baseline first. Save its driver and original `tu_knl_kgsl.cc`. Then apply
`tools/turnip/kgsl-wait-any.patch` without fuzz, rebuild the same target and save
the candidate. The latter rebuild compiles the changed object and links; all
other source inputs stay the same.

The sanitizer fixture takes the actual before/after source plus the Mesa tree:

```sh
python3 tools/tests/turnip-kgsl-wait-any.py before.cc after.cc mesa-source /tmp/vapor-kgsl-wait-any
```

It extracts the real wait-any/timeout helpers and vector implementation, using
Mesa's stack-array primitive. Only kernel/FD/Vulkan boundaries are mocked. The
original must expose division-by-zero and failed-allocation access; the candidate
must preserve the behavioral cases and pass allocation bounds with clean
sanitizers. This host fixture requires Clang with ASan/UBSan.

The local ZIP is `Vapor-Turnip-f28352d1-wait-any-experimental.zip`, outside the
checkout in `../turnip-refactor-qualification/`. It uses the existing Adrenotools
format with `minApi=34`, a payload checksum, both patches, build inputs and
component notices. It is not a reproduction of the full V38 vendor patch set.
Do not replace a phone's working driver or claim a performance gain from a build
or ELF-alignment result alone. [Qualification](QUALIFICATION.md#graphics-component-source-refactors--21-october-2026).

## Vulkan-wrapper source build — 21 October 2026

This experiment builds the existing Leegao wrapper source for Android ARM64;
it does not replace a bundled wrapper. [Component notes](../tools/vulkan-wrapper/README.md)
describe the image-ownership and trace-gating patches and their source tests.

The v0.0.5r5 release itself was packaged by a separate retained Termux repository,
`leegao/vulkan_wrapper_termux-packages` at `c5c45d88` (Docker with NDK r27c,
API 26, `-landroid-shmem -ladrenotools`, then zstd/tar). The tag source contains
no release workflow. Exact byte reproduction of the release payload is therefore
bounded by that Docker image/sysroot, NDK r27c and the release SPIRV-Tools
revision, none of which is retained; the receipt records how close the local build
gets and what remains open.

Pinned build inputs:

- Wrapper: `leegao/bionic-vulkan-wrapper`,
  `3abf48704a4d9303967a0811c0dc8ff8567d613b`.
- Custom shader passes: `leegao/SPIRV-Tools`,
  `56db26cf58f2f1b1f70ce92fc733ae91e136094d`.
- Its `DEPS` headers: `KhronosGroup/SPIRV-Headers`,
  `97e96f9e9defeb4bba3cfbd034dec516671dd7a3`.
- NDK `27.3.13750724`, API 34, CMake 3.22.1, Meson 1.9.1, Ninja 1.10.2,
  glslang 16.6.0 and the existing Wine experiment's ARM64 Termux sysroot.

The [receipt](evidence/2026-10-21-graphics-source.json) records archive hashes,
dependency hashes, compiler/link flags, source-test results and output identities.
Keep this source/build directory separate from the Turnip tree. The wrapper
fork's Vulkan utility macros and custom SPIRV-Tools API are version-sensitive.

Build SPIRV-Tools with the NDK CMake toolchain, `ANDROID_ABI=arm64-v8a`,
`ANDROID_PLATFORM=android-34`, `ANDROID_STL=c++_static`,
`CMAKE_POSITION_INDEPENDENT_CODE=ON`, `CMAKE_BUILD_TYPE=Release`,
`SPIRV_SKIP_TESTS=ON`, `SPIRV_SKIP_EXECUTABLES=ON`,
`SPIRV_TOOLS_BUILD_STATIC=ON` and `SPIRV-Headers_SOURCE_DIR` pointing to the pinned
headers. Build targets `SPIRV-Tools-opt` and `SPIRV-Tools-static`. Copy the two
archives into the wrapper source's existing `src/vulkan/wrapper/lib/` directory.

Use an Android cross file and X11 dependencies from the retained ARM64 sysroot.
Its cross pkg-config must translate the root `-I.../usr/include` to `-idirafter`,
leaving NDK libc/libc++ headers ahead of the dependency headers. Set
`-D__TERMUX__` and `-include fcntl.h` in both C/C++ arguments for this fork.
Link to `libandroid-shmem` and Adrenotools and explicitly to shared `libc++`, with
`-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`. The final baseline and
candidate use identical flags and dependencies. Android stub libraries remain
link-time inputs, not files to ship in place of platform libraries.

Configure the Mesa fork with:

```sh
meson setup wrapper-build wrapper-source --cross-file android.ini --native-file native.ini \
  --buildtype release \
  --prefix=/usr -Dplatforms=x11 -Dplatform-sdk-version=34 -Dvulkan-drivers=wrapper \
  -Dgallium-drivers= -Dopengl=false -Dglx=disabled -Degl=disabled -Dgbm=disabled \
  -Dllvm=disabled -Dandroid-stub=true -Dandroid-libbacktrace=disabled -Dxmlconfig=disabled
ninja -C wrapper-build src/vulkan/wrapper/libvulkan_wrapper.so
```

`native.ini` pins the Bison binary the recorded configuration used; without that
override the newer default Bison fails on this wrapper tree. The cross file and
native file are retained with the build inputs described in the receipt.

Retain the baseline library and three source files named by the patches. Apply
`tools/vulkan-wrapper/image-create.patch` and `tools/vulkan-wrapper/trace-gate.patch`
with `patch -p1 --fuzz=0`, then rebuild the same target. The latter patch also
repairs the local Python import dependency: the completed candidate regenerates
`wrapper_trampolines.h` and replaces all 1,174 `if (should_log)` conditions.
A rebuild that only compiles `wrapper_image.c` has not incorporated that change.

Run the image and generated-call sanitizer commands in the component notes.
After building, compare ELF `LOAD` alignment, SONAME, dynamic imports and exports
between baseline and candidate. Build-tree RUNPATHs and dependency/runtime
availability still require packaging and native-load qualification. This is not
an APK build, installed runtime, reproduced release binary or GPU result.

### Running the offscreen native probe

`tools/tests/vulkan-wrapper-native.c` is an offscreen Android probe; it starts no
Steam, Wine, window or game. Build it with the recorded NDK r27d release and the
16 KB page-size flags, using the include directory from the wrapper source tree
described in the receipt:

```sh
"$ANDROID_HOME/ndk/27.3.13750724/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android34-clang" \
  -std=c11 -O2 -g -Wall -Wextra -Werror -D_GNU_SOURCE \
  -I../turnip-refactor-qualification/wrapper-build/source/include \
  tools/tests/vulkan-wrapper-native.c -ldl \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -o /tmp/vapor-probe/probe
```

Stage the probe, the stripped baseline/candidate wrapper builds and their
auxiliary libraries in one directory, push it to `/data/local/tmp/`, and run with
`LD_LIBRARY_PATH`, `PREFIX`, `WRAPPER_LOG_LEVEL=none`,
`WRAPPER_CMD_LOG_LEVEL=none`, `FORCE_BCN_EMULATION=1` and `USE_IMAGE_VIEW=0`.
Arguments are `LIBRARY rgba|bc1|bc3|readonly [CYCLES] [EDGE]`. The expected
controls are: baseline readonly crashes, candidate readonly passes, and
baseline/candidate rgba/bc1/bc3 exit zero. Use the 4 KB AVD (`Vapor_UI_API36`,
`-gpu lavapipe`) or the 16 KB AVD (`Vapor_API_37_16KB`); confirm the guest page
size with `getconf PAGE_SIZE` and the probe's printed `page_size`. The BC paths
need forced emulation on these emulators because the virtual device's native BC
buffer-image copy is unreliable; see the
[wrapper notes](../tools/vulkan-wrapper/README.md#native-loading-and-bc-probe)
and the two receipts.

### Running the reference-image and depth probe

`tools/tests/vulkan-wrapper-reference.py` builds its own offscreen fixture with
the recorded NDK, stages the pinned wrapper libraries and auxiliary libraries,
runs deterministic BC1/BC3 and depth cases, and compares readbacks against an
independent S3TC decoder. `--self-test` validates the decoder, `--print-runbook`
prints the phone procedure, `--replay` re-assembles evidence from saved runs and
`--cases default|extended|all` (with `--extended-cases`) selects the original
matrix or the odd-extent, partial-copy, mip/array, sRGB, BC4/BC5 and depth-reduce
families. Run it with `--serial emulator-5556` for the emulator, or
`--serial <PHONE_SERIAL> --label phone-adreno` for the effective-driver pass. It
does not modify the app or the pinned libraries.

## Android ntsync library experiment

This is a library/source investigation, **not an installable Wine component**.
The recovery gate is held; keep Vapor's current runtime selection. See
[the local patch and boundaries](../tools/ntsync/README.md).

Source: `GameNative/ntsync-android`, commit
`7ce6435e5979b1cb5341aa4b299f31e8937fe121`. The observed GitHub source tarball is
48,976 bytes, SHA-256
`efafc9774c125c27fe3aab4354707b8ffa41312f2b821e7f478c4a1fcc33b04e`.
Preserve an untouched build for the negative controls before applying
`tools/ntsync/patches/0001-require-recoverable-region-mutex.patch` with
`patch -p1 --fuzz=0`. The patched `src/core.rs` must match the recorded source.
Keep its LGPL-3.0-only source/license and the locked Cargo dependency inputs.

The checked build uses Rust/Cargo 1.99.0, the `aarch64-linux-android` standard
library, NDK `27.3.13750724`/Clang 18.0.4 and API 28. `Cargo.lock` pins
`libc 0.2.189`, checksum
`3eaf3ede3fee6db1a4c2ee091bf8a8b4dccdc6d17f656fb07896ee72867612f2`.
Set `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` to the NDK host toolchain's
`aarch64-linux-android28-clang`. Run from the **donor project directory**, so
Cargo reads its existing `.cargo/config.toml` 16 KB linker flags:

```sh
cargo build --locked --release --target aarch64-linux-android
cargo test --locked --release --target aarch64-linux-android --no-run --message-format=json
```

The donor build shell script assumes Linux host paths; the macOS experiment used
the same Cargo/NDK inputs directly. A `--manifest-path` call from a parent directory
does not load the donor's target config. One such intermediate build was corrected
by rebuilding from the proper directory; verify the final LOAD alignments rather
than trusting a successful compile.

The `--no-run` command produces an Android test executable; run it on the isolated
AVD, not on macOS. Under the Vapor app UID with a dedicated writable `TMPDIR`, run
the original functional suite with `--test-threads=1 --nocapture` (13 performance
and worker tests are ignored). Run each new private recovery/init test in a fresh
process with `--exact`; the donor keeps a process-global region in `OnceLock`.
The original owner-death test is bounded by a 250 ms timed lock.

Build [the native C-ABI probe](../tools/tests/ntsync-abi.c) with the same NDK:

```sh
aarch64-linux-android28-clang -std=c11 -O2 -Wall -Wextra -Werror \
  -Wl,-z,max-page-size=16384 -I /absolute/ntsync-source/include \
  tools/tests/ntsync-abi.c -ldl -o ntsync-abi
```

Run it with an absolute library path and either `--shared-exec /absolute/child-tmp`
or `--expect-unavailable`. Use a dedicated `TMPDIR` and `NTSYNC_SHM`, leave
`NTSYNC_SPIN_ITERS=0`, and collect parent/child mapping device, inode and path.
The shared-exec control uses the original library; the guarded build must reject
initialization on this stock Bionic environment. Verify repeated `-ENOSYS`, an
unchanged output handle and an empty fixture directory. Do not mistake those
checks for working crash recovery or Wine integration.

`fex-smoke.c --sync` separately exercises the **current Windows runtime**. Build
both i686 and x86_64 variants with the existing LLVM-MinGW commands, pass `--sync`
through the display activity's `boot_exe_args`, and collect each `.result` and log.
It adds eight checks to the existing three: auto/manual events, wait-any, atomic
wait-all, recursive mutexes, normal/forced child abandonment and suspend/resume.
Its child modes operate only on test-created named objects/processes. These
functional results do not identify the selected backend by themselves; the
observed logs do not contain an explicit esync activation line.

The public Wine integration links the static archive into **both** matching
ntdll and wineserver and changes their protocol/backend dispatch. Copying the
`.so` into a prefix or setting an app environment flag is insufficient. In the
inspected donor source, failed userspace initialization falls back to fsync or
server synchronization, depending on configuration. A matched Wine build, safe
recovery implementation, actual backend trace, Steam exit/Cloud behavior and
phone comparison remain pending.

## Wayland fence regression experiment

The compositor fix is built by the normal APK task; it adds no dependency or
runtime package. Build the Android library/APK with the existing NDK 27.3 setup.
The standalone source-boundary checks need only Python 3 and a C11 compiler:

```sh
python3 tools/tests/wayland-fences.py \
  app/src/main/cpp/waylandcomp/src /absolute/output/after
```

Expected corrected result: **55 cases, zero failures**. The script extracts the
real source functions and compiles them with strict warnings. Four cases use
real OS pipe readiness/error events; the other cases inject poll/ioctl,
Vulkan-import, Wayland-watch and SurfaceControl outcomes. It is not a rendering
test. Original WinNative `8409085f706acbc0749881d9bf1e85f2f347dfd4` versions
of `ahb_swapchain.c`, `sc_layer.c` and `vk_present.c` can be placed together in
another directory and passed as the first argument. That negative control has
48 cases and **28 failures**; seven new copy-ownership cases apply only to the
new helper. A negative-control exit code of 1 is expected, not a failed fix.

`CC` selects a compiler, including the Android NDK's
`aarch64-linux-android28-clang`. `RUNNER` can name a command that accepts the
compiled binary path, copies it into a dedicated test directory and executes it
on the account-free `Vapor_UI_API36` device under `run-as app.vapor.android`.
The local runner and exact commands are retained in
`../wayland-qualification/` and the evidence archive. Native execution still
uses controlled API boundaries; it does not turn the fixture into a GPU test.

The existing in-game overlay checks use the project's actual instrumentation
runner (discover it with `adb shell pm list instrumentation`):

```sh
adb -s emulator-5554 shell am instrument -w \
  -e class com.winlator.cmod.runtime.display.GameMenuUiTest \
  app.vapor.android.test/com.winlator.cmod.StoreUiTestRunner
```

Check the device name before operating it. The local run passes all seven
tests. A separate `dxvk-smoke.c --stress` run on the retained X11/Lavapipe path
passes 240 presents, 18 readbacks and two resizes. Restore the container's exact
configuration and all applied FEX/DXVK files after this component comparison.

Run `tools/check-apk-16kb.sh` and `zipalign -c -P 16 -v 4` on the resulting
development APK. These pass for the recorded build, but neither establishes
game execution on a 16 KB kernel. See the
[checkpoint](QUALIFICATION.md#wayland-fence-correction--16-october-2026) for
the APK hash, original/fixed source identities and remaining matched-runtime
and physical-device gates. Do not copy a foreign `winewayland.so` into the
guided Wine build to bypass those gates.

## Audio interface and playback checks

The audio correction adds no runtime dependency. The existing selector matches
the host and bundled unixlib's call count **and** undefined system-thread import;
the current runtime bundles remain v1.3.2. A v1.4.0 system-thread archive lives
only in `src/test/resources` to reproduce the equal-count mismatch.

Run the focused suite with the normal JDK 17 environment:

```sh
./gradlew :app:testStandardDebugUnitTest --tests '*DirectAudioAbiTest' --console=plain
```

The current suite has 11 passing cases. The first seven cases against the original
implementation produce three failures: unsupported same-count host selection,
switching away from an installed driver, and same-size stale prefix files.
The evidence retains that negative-control XML/log and the corrected result.

Build the account-free Windows output probe with the same LLVM-MinGW toolchain
used for the CPU/graphics checks:

```sh
vapor_audio_mingw=/absolute/path/to/llvm-mingw/bin
for arch in i686 x86_64; do
  "$vapor_audio_mingw/$arch-w64-mingw32-gcc" -std=c11 -O2 -Wall -Wextra -Werror \
    tools/tests/audio-smoke.c -lwinmm -ladvapi32 -o "/absolute/output/audio-smoke-$arch.exe"
done
```

Run each executable through the existing display activity in the account-free
`Vapor_UI_API36` container, selecting PulseAudio and DirectAudio separately. Keep
Wine, FEX, preset and graphics settings fixed. Clear the container's *applied*
`extraData.audioDriver` marker when restoring an earlier config between cases so
the normal registry owner applies that case's choice; do not forge a guest
result or infer the loaded backend from the selected UI alone. The result file
records the registry value and loaded driver modules. Every positive run must
report `result=PASS checks=53 failures=0`. The four runs exercise 16 streams,
not real games or acoustic output. `--invalid-device` must instead report the
expected `waveOutOpen` failure with code 2.

The existing native `directaudio_input_test.cpp` opens five Android output streams
through the production bridge and checks callback progress while input hooks are
preloaded. Its normal process exit is part of the result. The original library
reproduces exit 139; the corrected NDK/Clang library passes with logging off/on.
Its state stays statically initialized and alive through finalization, while
normal controller close operations still free entries. The 17 existing native
input cases pass too. On Linux, `tools/test-fakeinput.sh` owns this test recipe;
its compiler paths are Linux-specific. The recorded Mac run selects the NDK's
`darwin-x86_64` compiler, runs under the Vapor app UID, and supplies the packaged
`libc++_shared.so` through `LD_LIBRARY_PATH`. Exact commands and the local runner
are retained in `../audio-qualification/`. The optional GCC-built Linux preload
is a separate exit-qualification gate.

Before the device runs, snapshot the selected Wine's three DirectAudio files and
stamp, both prefix PE files, audio shim and user registry. Restore their original
existence and contents afterward, together with the exact container configuration
and applied FEX/DXVK baseline. The input shim staged by the updated app should
match the corrected APK's library. The isolated fixture uses 4 KB kernel pages;
16 KB APK checks do not establish complete runtime support.

Build with the normal `:app:assembleStandardDebug` task and check the final APK
with `tools/check-apk-16kb.sh` and `zipalign -c -P 16 -v 4`. See
[qualification](QUALIFICATION.md#audio-interface-and-exit-corrections--16-october-2026)
for exact hashes, results and physical sound/route/media/Steam gates. Do not
claim a latency improvement from sample-position progress or enable a global
media override without a reproduced decoder failure.
