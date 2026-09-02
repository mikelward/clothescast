# Home-screen widgets — rendering approach

How ClothesCast's Glance widgets draw, why the feels-like chart takes the
route it does, and which alternatives were evaluated and rejected. Read this
before "simplifying" the off-screen chart render — every cheaper-looking path
here was tried and bit us.

## The widget set

All four are `GlanceAppWidget`s, refreshed by `updateAllClothesCastWidgets`
after each cache write / relevant settings change (no per-widget polling):

| Widget | Shows | Render strategy |
|---|---|---|
| `OutfitWidget` | The suggested outfit icons | Direct `Canvas`→`Bitmap` (`renderOutfitBitmap` etc.) |
| `ConditionsWidget` | A conditions strip (temp / rain / wind cells) | Direct `Canvas`→`Bitmap` (`renderConditionsStripBitmap`) |
| `FeelsLikeWidget` | Current 12-hour feels-like line (pager page 0) | Off-screen Compose render of the real chart |
| `SevenDayFeelsLikeWidget` | Next-7-days feels-like line (pager page 2) | Off-screen Compose render of the real chart |

### Freshness gate

There's no per-widget polling (`updatePeriodMillis=0`), so the cache only moves
when the fetch worker writes it: on a scheduled morning/tonight alarm, on
app-open, on a manual refresh, or on a widget-only refresh alarm (below). With
scheduled delivery off and nothing else driving a fetch, a user who hasn't
opened the app since yesterday would otherwise see *yesterday's* forecast
plotted as today's — the per-period feels-like chart plots hour-of-day points
for the snapshot's own date, so a day-old snapshot draws a previous day's curve
under today's axis.

So every widget loads through `loadCurrentInsight` (`WidgetInsightLoader.kt`),
which calls `widgetCacheAction` to pick one of three actions. The cached
snapshot is the *current window* when its `(period, date)` is what a silent
refresh kicked right now would target — computed with the same
`currentPeriodForSchedule` / `playTargetDate` the worker uses (the same
`bundle.today.date == playTargetDate(...)` test the worker's own cache match
runs), in the device's wall clock:

- **`RENDER`** — it's the current window (or freshly written, see below); draw it.
- **`REFRESH`** — stale and a refresh can replace it: draw the empty state and
  kick a silent refresh. A day-old snapshot fails the date match; an evening
  glance at a still-cached daytime snapshot fails the period match and self-heals
  to tonight; the wrapping tonight window and customized wake / evening cutoffs
  all fall out of the shared schedule logic. The worker's cache write then fires
  `updateAllClothesCastWidgets`, re-rendering off the fresh snapshot — so it
  self-heals on the next glance without the user opening the app.
- **`KEEP`** — stale, but a `dayOffset = 0` refresh can't reach the current
  window, so draw the last-known snapshot *without* refreshing. The only such
  case is the ongoing overnight window in the post-midnight tail: it's dated
  yesterday and would need a `dayOffset = -1` the worker doesn't support, so a
  refresh would write the *upcoming* night instead. Keeping the last render until
  the morning cutoff makes the daytime window reachable beats both blanking and
  churning toward a future-night snapshot.

### Widget-only refresh chain

The `REFRESH` self-heal above only runs when something *renders* the widget —
and with both delivery slots disabled every delivery alarm is cancelled, so
nothing repaints the launcher and a widget that went empty stayed empty until
the user opened the app (the "No forecast yet in the morning" report). The
enable toggles gate scheduled *delivery*, not refresh, so
`WidgetRefreshScheduler` keeps a separate self-re-arming inexact alarm going
while any ClothesCast widget is placed. It has two of them
(`WidgetRefreshKind`), deliberately **independent slots** — separate request
codes, each re-armed only by its own fire.

**Boundary alarm** — fires at both schedule boundary times every day, ignoring
the toggles and the schedules' day-of-week sets (the widget's window flips at
those times regardless). `WidgetRefreshReceiver` enqueues a silent refresh whose
cache write repaints the widgets; a fire that coincides with an armed delivery
alarm defers to it instead of double-fetching. `setAndAllowWhileIdle(RTC_WAKEUP)`:
a boundary may be the only refresh its window ever gets, so it is worth waking
for.

**Hourly alarm** — fires at the top of every hour. Everything a widget draws
that is anchored to *now* — the feels-like chart's current-time line and its
"64°F at 07:00" readout, the conditions strip — is computed at render time and
then frozen into a bitmap, so between boundaries a widget rendered at 07:00
still read 07:00 at half past two even though the cached forecast covered the
whole window. This fire repaints unconditionally (no network, so it works
offline) and refetches only once the cached snapshot is older than
`WIDGET_REFRESH_MAX_AGE` (6h). A plain non-wakeup `set(RTC)`, which is what
keeps the added cadence cheap — the tick lands the next time the device is up
anyway (approximately "the next time someone could be looking at the
launcher"), and overnight the ticks collapse into Doze's maintenance windows
instead of firing eight times at a screen nobody is watching.

**Same pipeline as the app, deliberately not the same threshold.** A widget
refresh and an app open run identical machinery: the same `InsightCache`
snapshot, the same staleness test, the same `enqueueSilentRefresh` onto the same
REPLACE-deduped `silent_insight_refresh` queue — so a tick and an open collapse
into one run rather than two. What differs is the age. `SILENT_REFRESH_MIN_AGE`
is 1h *because* that matches `CachingWeatherRepository`'s own 1h TTL: refreshing
sooner would only re-read the same in-memory bundle. That is the right number
for a screen a person is looking at. The hourly tick fires whether or not anyone
is, so `WIDGET_REFRESH_MAX_AGE` is 6h — half a 12-hour window, so a window whose
boundary fetch was missed still gets an attempt inside it, without spending a
person's battery on a launcher nobody has glanced at. Tapping the widget opens
the app and gets the 1h path.

**Nothing renders a window that has flipped.** `widgetCacheAction` refuses any
snapshot whose period and date are not the current window: it draws the empty
state — a "tap to open" — and kicks a refresh. Since a window runs boundary to
boundary, a snapshot old enough to matter is necessarily one whose window has
already flipped, so that check bounds staleness structurally rather than by
timer. Its age loop-breaker applies only to a *date* disagreement (the manual
-location cross-zone case it was written for); a *period* mismatch always
refreshes, because the window has flipped and a refresh resolves the period from
the wall clock, so it cannot loop. Widening it to period mismatches is how a
glance at the 07:00 boundary drew last night's window as if it were the day
ahead, silently, for up to an hour.

**Protecting the boundary alarm.** Everything below exists because arming a
matching `PendingIntent` *cancels* the pending one, and a re-arm computes the
next boundary strictly after now — so any re-arm landing on a boundary instant
silently drops that window's fetch, and the day's first glance finds the
previous window's snapshot and blanks to the empty state. Three rules keep that
from happening, and each closes a different route to it:

1. **Two slots, not one.** A single slot armed for whichever tick came first
   would let a *late* hourly tick swallow a boundary: the hourly alarm is
   non-wakeup, so with the device asleep the 06:00 tick is deferred until the
   device next wakes, and a re-arm from that fire computes the next boundary
   (19:00).
2. **A fire re-arms only its own slot.** Whenever a boundary sits on the hour —
   as the default 07:00 / 19:00 schedules do — both are due at once, so
   re-arming the other from a fire would cancel one that has not been delivered
   yet.
3. **A routine `schedule()` leaves a *pending* boundary alarm alone.** Rules 1
   and 2 are not enough on their own, because the hourly fire's first act is a
   repaint and *every widget render* calls `reconcileWidgetRefreshChain` — so
   the render itself re-arms both slots, right at the instant they coincide.

   "Pending" is read from a record of what was actually armed (a trigger time
   in a small `widget_refresh_chain` SharedPreferences file), not guessed from
   the clock. That distinction matters in both directions, and getting it wrong
   the first way is what an earlier version of this guard did: a window keyed on
   "are we near a boundary time" protects the pending alarm correctly but also
   skips arming one that is simply **absent** — a widget placed a few minutes
   past a boundary would render, reconcile, and get only the non-wakeup hourly
   alarm. An alarm counts as pending only while it is **in flight**: its
   trigger has arrived and it has not been delivered. Both ends are derived
   rather than assumed. A trigger still in the **future** is never protected —
   re-arming it computes the same instant and replaces it with an identical
   alarm, so nothing is lost, and that re-arm is what recovers the alarms a
   **force-stop** removed (Android drops an app's alarms but leaves this record
   behind). The far end is **the next boundary after the recorded one**, not a
   latency bound: `setAndAllowWhileIdle` has no maximum delay, so expiring the
   record after a fixed grace would cancel an overdue but still-live alarm and
   skip that window. A fire records its own re-arm, so a record still naming a
   past trigger means the fire has not run; once the successor boundary is due,
   the old one has been delivered or is never coming, and the slot needs arming
   either way.

   The record survives events the alarm does not — a force-stop, a reboot, a
   package replacement — so the record alone is not enough: the gate also asks
   Android whether the alarm is still there, via `FLAG_NO_CREATE`. Without that
   question, a launch two hours after a force-stopped 07:00 boundary reads
   "overdue, successor at 19:00, still in flight" and leaves the slot unarmed
   for eleven hours.

   Those three tests together make one gate that suits every caller, so there
   is no "the schedule changed, replace it regardless" mode to get wrong. Boot,
   a package replacement and a clock change all fail one of the first two, so
   they arm. A schedule edit measures the successor against the times the user
   just chose — and an alarm genuinely mid-flight when they edit is left to
   fire and re-arm itself on the new schedule, rather than being canceled in
   favor of a boundary hours away. The record must never outlive the alarm it
   names either way, so `cancel()` clears it first.

The chain is armed from every widget render via `reconcileWidgetRefreshChain`
(placement's first render starts it), re-armed on app start, by
`ScheduleRefreshReceiver` after boot / update / clock changes, and by
`ClothesCastApplication`'s schedule-time observer when either boundary time is
edited, and ends itself when a fire finds no widgets left. See
`docs/schedule-lifecycle.md` for how these silent runs differ from delivery
runs.

A leading age check is the loop-breaker: the worker stamps `forDate` from the
*forecast location's* zone but picks the period from the *device* clock, so for
a manual location whose calendar date differs from the device's the period/date
match can disagree with what a refresh just wrote — and re-kicking on that would
churn. Rendering any snapshot younger than `SILENT_REFRESH_MIN_AGE` guarantees a
freshly written one ends the staleness instead of feeding a loop. (In the common
case — device location, so forecast zone == device zone — `forDate` and the
device date never diverge, the age gate is moot, and the only `KEEP` is the
genuine post-midnight overnight window.) The pure period/date logic is
unit-tested (`WidgetInsightFreshnessTest`); the render plumbing stays on-device.

Glance emits `RemoteViews`, so it **cannot host Compose or Vico directly**.
Anything richer than Glance's own `Text`/`Image`/layout primitives has to be
rasterized to a `Bitmap` first and shown via `ImageProvider`. The two charts
and the outfit/conditions art all take that bitmap route — they just build the
bitmap differently.

## Two ways to build a bitmap

### 1. Direct `Canvas` drawing — outfit icons, conditions strip

The outfit icons and the conditions strip are drawn straight onto an
`android.graphics.Canvas`/`Bitmap` (`ui/garment/*` and
`renderConditionsStripBitmap`). This is synchronous, needs no window, and is
Robolectric-testable. It works because that art is *ours* — simple vector/text
drawing we author once, with no third-party Compose component or async data
load behind it.

### 2. Off-screen Compose render — the feels-like charts

The charts are different: they reuse the **real in-app Vico chart**
(`WidgetForecastChart` = the Today screen's `ForecastCard`/`ForecastChart`
with the legend dropped). Rendering the actual composable — not a hand-drawn
replica — is the whole point: it keeps the widget's colors, fonts, tick
spacing, and line shape identical to the screen, themed with the user's
palette + dark-mode preference. A replica always drifted and read as
"off-brand."

But you can't rasterize a live Vico chart with a plain off-screen
`Canvas` — it's a Compose component with an async data producer and a draw-in
animation, so it needs a real composition with a window and a frame clock.
That's what `ComposeRender.kt` (`renderComposableToBitmap`) provides:

- A `Presentation` hosted on a `VirtualDisplay` backed by an `ImageReader`,
  giving Compose a **real window + frame clock** so `LaunchedEffect`s run,
  Vico loads its series, and the chart draws.
- The `ImageReader` is sampled until the frame stabilizes (Vico settles a few
  frames after first composition, so capturing too early misses the line).
- A transparent `Presentation` window so only the card's opaque pixels land in
  the bitmap and the Glance `widgetBackground` shows through the rounded
  corners.
- Every failure path logs via `DiagLog` and degrades to the "tap to open"
  empty state — a blank widget must never fail silently again.

**Do not "simplify" this to an unattached `ComposeView`.** A detached
`ComposeView` *composes but never paints* — that was the original
blank-widget bug. See the dedicated warning in `ComposeRender.kt` and the
Compose gotchas in `AGENTS.md`/`CLAUDE.md`.

## Testing

`renderComposableToBitmap` itself can't run under Robolectric (no real
display/window), so the off-screen render path is verified **on-device**.

The chart's *appearance* is locked separately by Roborazzi snapshot tests
that drive `WidgetForecastChart` through `PreviewSnapshots`. `WidgetFrame`
sets `LocalInspectionMode = true`, which makes Vico's producer run on its
synchronous in-inspection dispatcher so the chart is fully drawn at capture
time — no window needed. So we get pixel coverage of *what the chart looks
like* in CI, and rely on-device only for *the rasterization plumbing*.

## Alternatives considered for the chart

| Approach | Verdict |
|---|---|
| **A. Hand-drawn `Canvas` lookalike** | Rejected — drifts from the in-app chart, reads "off-brand." Reproducing Vico's colors/ticks/line shape by hand is exactly what we're avoiding. |
| **B. Unattached `ComposeView` + `measure/layout/draw`** | Rejected — composes but never paints; yields a *blank* bitmap. This was the original bug. |
| **C. `Presentation` on a `VirtualDisplay` + `ImageReader`** | **Chosen.** Renders the real composable verbatim with a true window/frame clock; settles the async chart before capture. |
| **D. Vico compose-glance (`CartesianChartImage`)** | Evaluated, rejected — see below. |

### Why not Vico's `compose-glance` module?

Vico ships a `compose-glance` artifact with `CartesianChartImage`, a *Glance*
composable that rasterizes a chart for a widget. On paper it could delete all
of `ComposeRender.kt`. It was evaluated and deliberately not adopted:

- `CartesianChartImage` is a **Glance** composable, so the `CartesianChart`
  and `CartesianChartModel` it takes must be built **outside** a normal
  Compose composition. That rules out `rememberCartesianChart`, the
  axis/label `remember*` builders, and crucially
  `ProvideVicoTheme`/`rememberM3VicoTheme`.
- The entire themed chart — line layer, axes, grid, the current-time
  decoration, and the axis-label `TextComponent`s with their colors and 12sp
  sizing — would have to be **hand-assembled with raw constructors** and
  manually-supplied theme values, and the card chrome (title / min–max
  subhead / current-time readout) rebuilt as native Glance `Text`.
- That recreates exactly the **drift-prone "lookalike that diverges from the
  in-app screen"** problem the current approach exists to avoid. Today the
  widget renders the *real* `WidgetForecastChart`, so widget and screen stay
  in lockstep for free; the Glance path swaps that automatic parity for a
  parallel chart definition to keep in sync by eye.
- **No net testability win**, either: the live `CartesianChartImage` Glance
  render isn't Robolectric-testable any more than the current path is, and we
  already get appearance coverage from the `WidgetForecastChart` snapshots.

The conclusion: keep `ComposeRender.kt`. Revisit `compose-glance` only if the
blank-widget maintenance cost ever outweighs the automatic screen parity the
current approach buys.
