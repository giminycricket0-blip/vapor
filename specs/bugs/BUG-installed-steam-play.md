# Installed Steam games: recovery and Play

Status: source fix and installed APK entry points verified. Fresh download-to-Library and both Play entry points work in the emulator; gameplay and save qualification remain separate.

## Reproduce

The installed Steam details screen had no Play callback or action. With no pending update, it could present an installed game without a main action. The Library used a separate, working launch callback.

A regression test created completed Steam files and an existing `AppInfo(isDownloaded = false)` row. The ordinary `SteamService.isAppInstalled()` query incorrectly returned false. Before the fix, 1 of 5 recovery tests failed; the other four passed.

At the start of this investigation the previously tested Europa Universalis III demo had a downloaded database row, but its game files were parked outside the emulator to recover space. That historical disappearance is not proof that the stale-row bug caused it. A fresh download has since appeared in the Library automatically, and the normal Library and Store Play actions both entered the genuine-client launch path.

## Isolate

`getTrustedInstalledAppInfo()` recovered a missing row but accepted a stale false row without attempting the same recovery. The existing explicit disk-repair action already handled that case. Recovery checks the complete marker, incomplete marker, and store ownership.

`GameManagerDialog` uses `StoreGameDetailScreen`. That shared component had install/update/repair actions but no Play action. `launchSteamGame()` already owns genuine-client launching from the Library.

## Hypothesis

Use existing recovery when the row is absent or not downloaded. Add an optional Play callback to the existing shared details component and wire the Steam caller to `launchSteamGame()`. Prevent Play while this game's download or update is queued, downloading, paused or failed. Callers without a launch callback retain their existing behavior.

## Verify

- Focused Room/disk regression: 5/5 passed after the fix, including incomplete downloads, absent completion markers and foreign-store folders.
- Six new Compose checks pass for installed/uninstalled actions, metadata loading, blocked downloads, updates and callers without a launch callback. The broader selected Android run passes 16 tests, with the eight new Play/settings tests repeated after reboot.
- The APK installs in place. Native Store search, a fresh 381.91 MB Steam download, automatic Library insertion and both visible Play actions were exercised without a hidden launch intent or database patch. Game-specific graphics configuration was needed after launch; this UI/install fix does not claim to resolve that separate runtime issue.
- Actual game outcomes and save persistence are recorded separately in `docs/QUALIFICATION.md`.

No new download database, install state, launcher, service or dependency was introduced. Game Steam libraries and ownership checks remain on their existing paths.
