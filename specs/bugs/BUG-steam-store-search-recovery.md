# Steam Store search clutter and network recovery

Status: corrected in the local UI-continuation APK; Xiaomi acceptance pending.

## Reproduce

On 17 October the owner reports no search results and `Games: 84`, then confirms
search works again and attributes the first issue to connectivity. The remaining
reported problem is four repeated `Try “…”` spelling prompts, with a request to
improve network recovery and caching.

The unmodified account-free Android app finds Factorio and its demo. Exact public
Steam requests return Hot games total 84, All games total 193802, and matching
Factorio, Hollow Knight, EU3 and Ghostrunner entries. Those totals are snapshots,
not fixed limits. EU3 also returns six spelling suggestions despite real matches.

## Isolate

`SteamCatalogBrowser` renders every suggestion before checking whether games
matched. Its count label calls the selected subset `Games`, and each request
clears the current page, including Refresh. IME submit increments retry even if
the successful search is unchanged. Existing tests mock a single empty-result
suggestion; they do not cover suggestions alongside matches or failed refreshes.

## Hypothesize

1. Unconditional suggestion rendering produces the repeated prompts. Verify with
   a matching result plus four suggestions, then an empty result plus several.
2. A successful page is discarded on refresh failure. Verify with the same query,
   one successful load, then a transport failure and explicit retry.
3. Unchanged IME submit dispatches another request. Verify request count after a
   successful search and repeated keyboard submit.
4. The catalog itself is limited to 84. Live All/search responses falsify this;
   84 is the selected top-seller subset and needs a scoped label.

## Verify

The unmodified UI fails six of 21 cases for unchanged-query resubmission,
connection-return retry, multiple empty-result prompts, scoped result counts and
keeping the current page after a failed refresh. A normal-app capture also shows
five spelling prompts above matching results. Implement the corrections inside
the existing browser: one useful suggestion on empty results only, scoped counts,
no duplicate successful IME request, query-keyed current-page retention, and the
existing NetworkMonitor for offline/reconnect behavior. The retained page must
be labeled when refresh fails and a new query must not inherit it.

Steam sends
`Cache-Control: max-age=0, no-cache, no-store`; preserve that HTTP policy. Keep
only the current query's already-rendered page in Compose memory during refresh
and its recovery. Do not add a response disk cache, account cache, TTL framework,
background retry service or alternate Steam connection. Existing Coil image
caching and OkHttp connection recovery remain the owners. A new query must not
show a different query's retained page as a successful result.

A second focused case reproduces a lost reconnect edge while the prior request
is still loading. That request now restarts on the connection-return edge as well;
a late cancelled response cannot replace the recovered page. The old condition
fails the regression and the corrected 22-case Store suite passes.

The normal app also exposes an inset invisible drawer swipe area over the Store
options centre. The pointer regression records zero of two button taps before
the fix, then both button taps and both edge swipes after restricting the targets
to the outer 8 dp gutters. Normal-app Store options then opens on its centre tap.

Real radio-off/return checks retain and automatically refresh the current Factorio
results. A new offline Hollow Knight query correctly removes the old cards but
does not automatically recover on the second radio-return sequence; one manual
Retry recovers it. Keep this remaining limitation explicit. All games subsequently
shows 193807; the original 193802 was an earlier live snapshot.

The corrected APK passes all 22 Store UI cases, including failed refresh → Retry
→ changed-query failure without stale cards. These are included in a 42/42 native
UI run alongside Home/shelf and shell/layout checks. Fourteen catalog and 12
Library JVM checks also pass. See the [dated qualification
record](../../docs/QUALIFICATION.md#native-cover-shelf-and-store-recovery--17-october-2026)
for the artifact hash, runtime-preservation evidence and remaining live/device
boundaries.

## UI-continuation follow-up — 17 October 2026

The details-modal reconnect case loses its retry edge while the browser is
inactive. A failing regression reproduces it; the existing effect now retains a
pending reconnect until the browser is active. Offline checks read the seeded
network StateFlow; the existing unknown-network fallback runs on IO rather than
making a synchronous ConnectivityManager call on Main. An unvalidated connection
is not automatically blocked. Offline pages distinguish a retained result from a new failed query, and
stacked filters receive their missing 8 dp gap.

The normal two-disconnect sequence still fails after that correction. Application
logging proves the new-query retry fires but reaches the existing 20-second HTTP
deadline with InterruptedIOException. The existing HTTP owner's idle pool is
discarded on IO at reconnect. The same sequence then recovers both the retained
Factorio page and the new Hollow Knight search without manual Retry. This closes
the specific emulator limitation recorded above; it does not qualify every
phone/network transition. No new client, persistent response cache or retry loop
is introduced.

The current Store class passes 25/25 checks. One assertion originally assumed a
synchronous retry; it now waits for dispatch after IO cleanup. The final Home
10/10, shared UI 11/11 and catalog JVM 14/14 results are recorded separately in
[the follow-up qualification record](../../docs/QUALIFICATION.md#store-recovery-and-shelf-geometry--17-october-2026).
