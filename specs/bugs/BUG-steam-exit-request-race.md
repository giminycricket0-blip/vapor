# Overlapping Steam host shutdown requests

Status: host race reproduced locally; the phone's intermittent Cloud timeout is
not yet reproduced with a real account.

## Reproduce

The phone report is normal game exit followed by an upload/Steam-close wait, and
occasionally a second launch that needs an app restart. A later Ghostrunner Demo
log confirmed a two-file upload; that success does not establish repeated exits.

Local target: the account-free `Vapor_UI_API36` ARM64 Android emulator, Wine/FEX,
and a Windows fixture linked directly to the current `clean_shutdown.cpp`.
No Steam module, account, game save or network transfer is used. Hold the first
shutdown inside its log sink, then create the real Android shutdown sentinel.
Check whether that second request exits the process before the owner completes.

## Isolate

`src/main.cpp` starts shutdown after reporting game exit. The Android status
tailer can observe that report and enter `sanitizeSteamStateForNextSession`,
which writes `C:\wn-launcher.shutdown`. In `clean_shutdown.cpp`, `teardown`
returns immediately if another thread already owns cleanup. The original
`watch_loop` called `ExitProcess(0)` even after that immediate duplicate return.

## Hypothesize

1. A duplicate sentinel request terminates the thread already performing exit
   cleanup. Falsify by holding that owner and checking completion before process
   exit; compare game-first and sentinel-first orderings in both guest widths.
2. Android's 90-second deadline is shorter than normal bounded cleanup. Source
   inspection gives 10 seconds for game close, a 60-second Cloud budget and a
   short logoff flush, so ordinary bounded work fits. A blocking private Steam
   call could still exceed it; this fixture cannot establish that behavior.
3. The Android reconnect reservation itself leaks. Existing reservation/handoff
   suites cover overlap and cleanup; rerun them after isolating the host race.

## Verify

The original x64 Windows host code fails both game-first cases: the child exits
with code 0 while its cleanup owner is still blocked, with one start and zero
completion markers. Natural, sentinel-first and concurrent duplicate calls all
complete once (six controls pass). This isolates the unconditional sentinel
`ExitProcess`, not a network, private Steam interface or save-path failure.

Implemented fix: let `teardown` return whether this caller performed cleanup. Only a
sentinel caller that owned and finished cleanup may terminate the process. A
duplicate sentinel retires its watcher and leaves the existing owner in charge.
Keep public entry points, timeouts, Cloud decisions and Android reservations.

The x86 original repeats the same two failures. With the ownership correction,
all eight cases pass on each guest width (**16/16**). Both game-first traces
per width show the production watcher deferring while the owner is still held,
followed by exactly one completion. The fixture links the actual shutdown source;
it does not call a Steam implementation or transfer saves. The 53 existing JVM
session/Cloud-status/handoff/install tests and four Android guard/error UI cases
also pass.

[Microsoft's ExitProcess contract](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-exitprocess)
confirms that the call terminates other threads; it is not a join. The existing
shutdown owner is therefore the direct place to fix this failure. No new Steam
connection, recovery worker, state model or timeout is introduced.

See [qualification](../../docs/QUALIFICATION.md#overlapping-steam-host-exit-requests--16-october-2026)
and [repeat instructions](../../docs/BUILDING.md#overlapping-steam-host-exit-checks).
Repeated real-account exits, network interruptions and remote save/restore remain
required before declaring the phone's intermittent Cloud issue resolved.
