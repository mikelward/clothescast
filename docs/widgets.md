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
app-open, or on a manual refresh. With scheduled delivery off, a user who hasn't
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
