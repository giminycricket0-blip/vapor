# Steam client shown as a game

## Reproduce

User reports Steam appears as an installed game. The Xiaomi is the intended physical test device. The inherited optional Linux-client install creates a CUSTOM shortcut marked runtime=linux, linux_session=steam, library_type=application. Native Xiaomi UI is not connected; source and a focused shortcut regression reproduce the path independently.

## Isolate

UnifiedActivityHub maps every CUSTOM shortcut into the Library, then explicitly overrides the Steam client to AppType.game. SetupWizardActivity presents the optional Linux client alongside a Windows client choice. StoresScreen already owns its installation panel under Advanced but has no launch action. LibraryCache retains prior cards and can briefly redraw an old client entry. The Add game dialog also offers Add Linux Steam, while the main hub can pop up the optional client updater. Those entry points must move out of the ordinary game flow together.

## Hypothesize

The explicit client-as-game override and unfiltered custom-shortcut enumeration produce the report. Falsification: generate the actual Steam shortcut with LinuxApps.ensureSteamShortcut, then test LibraryShortcutUtils.isCustomLibraryShortcut. A game named Steam without the client marker must remain visible. Existing cached cards must be filtered by shortcut identity, never by title.

## Verify

The regression reproduced both defects on the old code: the real installed-client shortcut was included as a custom game, and its old cached card was returned. The two control cases (a game named Steam and another Linux game) passed. Source confirms the explicit AppType.game override. The correction keeps the optional client in the existing Advanced panel with a launch action, removes the client-choice setup page and Add game/update entry points, and preserves ordinary custom/Steam games and installed client data. The four client/library regressions now pass within 71 selected JVM checks. All 22 selected Android Compose checks pass, including four new client-panel cases. A normal clean-emulator first run traversed the three setup pages and opened the optional panel without starting a download. Native client/game execution and Xiaomi review are not part of this verification. This is frontend integration work, not a SteamOS operating-system port.
