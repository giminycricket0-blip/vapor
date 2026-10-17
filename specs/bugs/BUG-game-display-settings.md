# Game display settings at launch

Status: source fix, 67 focused JVM checks, 18 selected Android UI checks, native automatic file correction and the native preparation-error/settings route pass. Rendering is recorded separately in docs/QUALIFICATION.md.

## Reproduce

The fresh Europa Universalis III Steam demo (22150) ships settings.txt requesting 1280x960. The emulator test selected a 1024x768 virtual screen. The game's log rejected its requested resolution and the display remained black. The private original configuration and the dated qualification record preserve that observation. An earlier log also says the game raises heights below 768 to 768.

## Isolate

Vapor resolves shortcut/container screenSize, applies render scaling, and builds ScreenInfo from it. The existing GameFixes launch hook handles other title-specific fixes but has no rule coordinating this demo's graphics/size fields with the actual virtual screen. Steam download and authentication had already succeeded before this failure.

## Hypothesize

A fresh default config can request a mode unavailable in the virtual screen. Falsification: run the existing hook with the real bundled shape (1280x960) and a 1024x768 screen; check whether the game settings match. Then change only the resolution fields and repeat at the same renderer/settings. Unknown title IDs and unrelated settings must remain untouched.

## Verify

Source trace confirms the two sizes are independent. Historical native evidence: matching game and virtual-screen resolution removed the logged resolution rejection. That experiment also disabled multisampling, so it does not prove resolution alone repairs all graphics failures. The regression failed on the old hook at the size assertion, then passed after the change. Ten focused cases now cover preparation, original backup preservation, effective minimum size, repeat-launch no-op, other stores/titles, missing/malformed/ambiguous data, failed backup creation, symlinks and byte/line-ending preservation. Driver/FPS/input claims remain outside this fix.

Implementation scope: extend the existing GameFixes owner for this exact Steam demo, pass the actual post-scale screen, preserve a one-time original file, write atomically, and propagate preparation errors through the existing session failure UI. No new profile database or automatic GPU driver substitution.

Native checks confirmed a malformed file remains unchanged and returns a readable error through cleanup; Game settings opens the correct editor. A valid 1280x960 fixture is changed to the real 1024x768 screen, with all other bytes unchanged and an exact original backup. Separately, the low-storage AVD needed empty runtime/prefix files restored from verified sources before game rendering could be assessed; this environment repair is not part of the source fix.
