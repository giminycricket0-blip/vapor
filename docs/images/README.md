# README images

The logo is exported from `app/src/main/res/drawable/vapor_logo.xml`; that Android vector remains the artwork source. It is the current placeholder, not a new logo design.

## Current set (22 October 2026, modern visual refresh)

- `ui-2026-10-22-{home,library,game-page,downloads}-{landscape,portrait}.png`: produced by `ReadmeScreenshotsTest` on the API 36 ARM64 emulator (1080×2400, 420 dpi; landscape via a 2400×1080 display override) from the production Compose components. They use public Steam catalog artwork and sample install, progress and play-history state: no Steam account, no transfer, no game launch. The captures were downscaled to 1600 px (landscape) or 720 px (portrait) wide and PNG-optimised; nothing else was edited.
- `social-preview.png`: 1280×640 GitHub social card, composed from the Home landscape capture and the bundled Bricolage Grotesque and Inter fonts. Upload it in the repository's **Settings → General → Social preview**; GitHub has no API for this.

## Earlier captures (kept for the dated records that hash them)

- `home-landscape.png`: 17 October native Home fixture; sample games/installs, no Steam account.
- `game-page-landscape.png` and `game-page-portrait.png`: 17 October native game-page fixture; labeled sample history, no Steam account.
- `store-portrait.png`: 17 October normal signed-out app, public Steam catalog. Counts and prices are a historical snapshot, and it predates the 22 October visual refresh.
- `helltaker-xiaomi.jpg`: user-supplied Xiaomi 17 Pro gameplay screenshot, 15 October. Audio was separately confirmed by the user. This is an observation, not a controlled benchmark.

Game artwork belongs to its respective owners. The [17 October UI record](../evidence/2026-10-17-ui.json) and the [22 October UI refresh record](../evidence/2026-10-22-ui-refresh.json) hold file hashes and qualification limits.
