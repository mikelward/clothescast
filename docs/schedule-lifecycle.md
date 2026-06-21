# Schedule lifecycle — alarm to delivery

What happens between the exact alarm firing and the forecast landing in the
user's notification shade. Read this before changing `AlarmReceiver`,
`ScheduledDeliveryService`, or the `setForeground` / FGS handling inside
`FetchAndNotifyWorker` — the moving parts span four classes and the
manifest, and the failure modes are subtle.

## What the user sees

| Stage | User-visible notification | Notification id | Channel | FGS type |
|---|---|---|---|---|
| Preparing | "Preparing your ClothesCast" (silent, ongoing) | 1005 | `playback_v2` | `mediaPlayback` if speech plays, else `dataSync` |
| Delivering — speech / MQTT / cast | "Delivering your ClothesCast" (silent, ongoing) | 1005 | `playback_v2` | `mediaPlayback` if speech plays, else `dataSync` |
| Delivered — notification-only mode | The forecast notification | 1001 (daily) / 1003 (tonight) | `daily_insight_v1` / `tonight_insight_*_v1` | n/a |
| Delivered — speech / MQTT / cast | "Delivering" + the forecast notification together, then "Delivering" disappears when the run completes | 1005 + 1001/1003 | both | `mediaPlayback` / `dataSync` |

There is one transient overlap on the speech path: the "Delivering" service
notification and the forecast notification sit side-by-side while the run
completes. That's deliberate — they communicate different things ("audio is
playing now" vs. "here is today's outfit") — and lasts at most a few seconds.
The notification-only path has no overlap.

`SILENT` delivery (no notification, no audio, no MQTT, no cast) shows *no*
notification at all — see [SILENT skips the Service](#silent-skips-the-service)
below.

## The components

```
┌──────────────────┐  exact alarm fires
│  AlarmManager    │ ─────────────────────────┐
└──────────────────┘                          ▼
                                  ┌──────────────────────┐
                                  │   AlarmReceiver       │
                                  │  (BroadcastReceiver)  │
                                  └──────────┬────────────┘
                          enqueueOneShot ┌───┴───┐ startForegroundService
                                         │       │      (only when the
                                         ▼       ▼       run needs an FGS
              ┌──────────────────────────┐ ┌─────────────  surface)
              │   FetchAndNotifyWorker   │ │ ScheduledDelivery
              │   (WorkManager worker)   │ │  Service (FGS)
              └──────────────────────────┘ └────────┬─────┘
                          ▲                         │
                          └──── watches by UUID ────┘
```

`AlarmReceiver` reads preferences once, then routes:

1. **Slot disabled** → cancel the alarm, do nothing else.
2. **`SILENT` delivery, no MQTT, no cast** → enqueue the worker, re-arm. No
   Service, no FGS notification.
3. **Otherwise** → enqueue the worker, re-arm, and (best-effort) start the
   Service to watch that worker's specific UUID. The Service owns only the
   FGS notification; it does not enqueue the worker or re-arm.

`FetchAndNotifyWorker` does the fetch, insight, notification post, TTS /
MQTT / cast fan-out. When the Service is shepherding the run, the worker's
own `promoteToPlaybackServiceIfNeeded` skips its `setForeground` because the
Service is already holding notification id 1005 — see
[Owner gate](#owner-gate-foreground-id-1005) below.

## States

The Service is a small state machine driven by the WorkInfo flow for the
specific worker UUID `AlarmReceiver` handed it.

### `PREPARING`

Entered when the Service is started by the receiver. The Service:

1. Reads the worker UUID and the mode flags (`playsSpeech`,
   `deliveringWantsForeground`) from the intent extras the receiver stamped.
2. Calls `startForeground(1005, prepareNotification, type)` and flips the
   process-local `isHoldingForeground` flag to `true`. The `type` is chosen
   up front from `playsSpeech`: `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` for
   speech runs, `FOREGROUND_SERVICE_TYPE_DATA_SYNC` otherwise.
3. Subscribes to `WorkManager.getWorkInfoByIdFlow(workId)` — the specific
   request the receiver enqueued, not the unique-work-name flow (which can
   surface stale terminal rows from yesterday's run and tear the Service
   down immediately).

For non-speech runs `dataSync` is the right type for "the app is fetching
forecast data in the background". For speech runs we deliberately claim
`mediaPlayback` from `PREPARING` rather than waiting for `DELIVERING`: the
worker can reach TTS before the async progress observer would upgrade the type
(a slow fetch can swallow the alignment wait, and the cached-redelivery path
goes straight from `setProgress` to `deliver()`), and on Android 15+ a
`dataSync` FGS can't grant audio focus, so the speech would be silenced.
Starting `mediaPlayback` up front closes that race; the cost is a slightly
broader FGS type held for the whole `PREPARING` phase before `deliver()`
actually speaks — usually brief, but on a slow fetch or the cached-redelivery
path that phase can span the full fetch / generation / alignment window.

### `DELIVERING` (only if speech / MQTT / cast)

Entered when the WorkInfo for this run reports `KEY_FETCH_COMPLETE=true` in
its progress data — the worker has finished fetch + insight generation and
is about to start the deliver fan-out. The Service swaps to the "Delivering"
notification, keeping the same FGS type it picked in `PREPARING`:

- if `playsSpeech` is true, call
  `startForeground(1005, deliverNotification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`
  — already the type held since `PREPARING`. Audio focus on Android 15+
  requires the process to be hosting an FGS of type `mediaPlayback` while it
  plays.
- else (MQTT bridge or cast only), call
  `startForeground(1005, deliverNotification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`.
  No audio focus to claim, just keep the user informed that the run is still
  publishing.

Either path replaces the "Preparing" content with "Delivering" in place —
same notification id, same channel, no transient gap.

### Notification-only path — skip `DELIVERING`

When the period's delivery mode is `NOTIFICATION_ONLY` *and* MQTT *and* cast
are both off, the Service stays in `PREPARING` for the whole worker run. The
forecast notification posts (under id 1001 / 1003 via `InsightNotifier`), the
worker reaches terminal state, and the Service tears down.

The brief "Preparing" notification is visible from alarm-fire through the
forecast post. That's the trade discussed in the design — confirmation that
the schedule fired vs. a flash of a placeholder when the run is fast.

### `DONE`

Entered when the WorkInfo reports any terminal state (`SUCCEEDED`, `FAILED`,
`CANCELLED`) or when the Service hits its safety timeout (5 min). The Service
calls `stopForeground(STOP_FOREGROUND_REMOVE)`, flips
`isHoldingForeground` back to `false`, and `stopSelf()`. The forecast
notification (id 1001 / 1003) — if posted — stays in the shade.

## SILENT skips the Service

`DeliveryMode.SILENT` documents the contract "the worker still runs (fetch,
cache, insight generation, widget refresh), but nothing is surfaced to the
user." Posting a "Preparing" foreground-service notification across the
whole run would regress that. So `AlarmReceiver` short-circuits before the
Service start: when the period's delivery mode is `SILENT` *and* the MQTT
bridge is off *and* casting is off for this period, the receiver enqueues
the worker and re-arms without ever calling `startForegroundService`. No
FGS, no notification.

The worker's `promoteToPlaybackServiceIfNeeded` is also a no-op on this
path — the early-out reads the SILENT mode and skips before any
`setForeground` would fire.

## Owner gate: foreground id 1005

`FOREGROUND_NOTIFICATION_ID = 1005` is shared between
`ScheduledDeliveryService` (primary FGS owner for alarm-driven runs) and
`FetchAndNotifyWorker.promoteToPlaybackServiceIfNeeded` (fallback FGS owner,
also used by the non-alarm Play path). Only one process component can hold
a given FGS notification id at a time, so the worker must skip its
`setForeground` while the Service has the id.

The gate is a per-worker UUID on `ScheduledDeliveryService.Companion`:

- Stamped with the worker's UUID immediately after a successful
  `startForeground` in the Service.
- Reset to `null` in the Service's `teardown` and `onDestroy`.
- Read by the worker's `promoteToPlaybackServiceIfNeeded` via
  `isHoldingForegroundFor(id)` — if the gate matches the worker's own
  UUID, skip. Otherwise promote itself.

A per-worker gate (not a process-wide bool) is what correctly handles the
corner cases:

- **Service safety timeout fires before the worker gets to run.** Service
  exits → gate clears → when the worker eventually runs (network returns),
  `promoteToPlaybackServiceIfNeeded` sees no match and promotes itself.
  Background TTS audio still works on Android 15+.
- **Process restart between Service start and worker execution.** Gate is
  per-process and resets to `null` on a fresh process. Worker promotes
  itself. Same outcome.
- **Receiver couldn't start the Service** (background-restricted, lapsed
  exemption window, OEM quirk). Service never stamped the gate. Worker
  promotes itself. The pre-Service experience.
- **Overlapping runs** — second alarm fires (e.g. evening) while we're
  still shepherding the first (morning TTS still mid-flight). The second
  `onStartCommand` is rejected by the "already shepherding a different
  workId" guard; the second worker's `isHoldingForegroundFor(id)` returns
  false (different UUID) and it promotes itself via its own setForeground
  path. The first worker stays correctly under the Service's wing.

## One queue, `REPLACE`, cancel-on-new-alarm

Both TODAY and TONIGHT scheduled runs share a single unique-work queue
(`FetchAndNotifyWorker.UNIQUE_WORK_NAME`). Combined with
`ExistingWorkPolicy.REPLACE`, a new alarm fire — regardless of which slot it
fires for — cancels any in-flight previous run, including the *other*
slot's. The semantics: only one scheduled-delivery worker is ever alive at
a time. The period each run is for travels on the WorkInfo's input data
(`KEY_PERIOD`), not the queue name, so observers like the Today and
Schedule view-models read it there when they need to know which slot is
running.

Always-REPLACE also means `request.id` is always the id WorkManager kept,
so the receiver gets the right UUID to hand to the Service without any
read-back-after-enqueue dance.

On the Service side, a new `onStartCommand` with a different `workId`
cancels the in-flight watcher coroutine and re-enters `startForeground` in
place (same notification id, content updates atomically) for the new
`workId`. The old worker has already been canceled at the WorkManager
layer by REPLACE, so it never reaches another promotion check.

## Fallback

`AlarmReceiver` calls `ContextCompat.startForegroundService` and treats both
the exception path *and* a null `ComponentName` return as failure. On
failure, it logs and continues — the worker was already enqueued and runs
without the Service shepherding it. The worker's
`promoteToPlaybackServiceIfNeeded` then handles the speech-window FGS via
the owner-gate mechanism above — same as before the Service existed.
Worst-case the user gets the pre-Service experience, never nothing.

When the worker owns id 1005 itself this way, it also makes the
`PREPARING → DELIVERING` swap on its own: `markFetchComplete()` re-posts the
notification with the "Delivering" title at the same point it stamps
`KEY_FETCH_COMPLETE` (the flag the Service watches when *it* owns the id).
Without this the self-promoted notification would sit on "Preparing" for the
whole run — the visible symptom whenever the worker wins the startup race for
id 1005 (it's enqueued before the receiver starts the Service, and
`startForegroundService` dispatches to a possibly-contended main thread), or
on any of the other owner-gate fallbacks above.

## When the Service is *not* involved

- **`SILENT` delivery (no MQTT, no cast).** Receiver short-circuits as above.
- **Manual `Play now`** (Today screen Play button, Schedule preview buttons).
  Routes through `enqueuePlay` directly, not the alarm receiver. The user is
  in the app — flashing "Preparing your ClothesCast" at them is redundant
  noise. The worker's own `setForeground` continues to handle the speech
  window on this path because the Play queue (`UNIQUE_WORK_NAME_PLAY`)
  doesn't go through `AlarmReceiver`, and `isHoldingForeground()` is `false`
  for the Play run.
- **Silent refresh** (app-open opportunistic, manual Refresh).
  `KEY_SILENT_REFRESH=true` means no notification / TTS / MQTT / cast at all.
  No FGS needed; the Worker runs at normal priority.
- **Location-cache refresh** (device-location toggle on). Network-only side
  effect, no delivery pipeline.

## Manifest

Three permissions are required:

- `FOREGROUND_SERVICE` — base permission for any FGS (already declared).
- `FOREGROUND_SERVICE_DATA_SYNC` — the `dataSync` type used in `PREPARING`
  and in the no-speech `DELIVERING` path.
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — the `mediaPlayback` type used in the
  speech `DELIVERING` path (already declared for the Worker's fallback FGS).

All three `FOREGROUND_SERVICE*` permissions are normal-level: auto-granted at
install, no runtime prompt.

The Service declaration in `AndroidManifest.xml` carries the union of the
two types it ever runs with:

```xml
<service
    android:name=".alarm.ScheduledDeliveryService"
    android:exported="false"
    android:foregroundServiceType="dataSync|mediaPlayback" />
```

## Failure modes the design has to absorb

- **Worker never starts.** `NetworkType.CONNECTED` constraint on the Worker
  means a no-network device defers the run indefinitely. The Service's
  **pre-RUNNING 5-minute cap** triggers and the Service exits (`isHoldingForegroundFor`
  returns false for the workId from then on). The Worker stays queued and
  runs when connectivity returns; the owner gate then lets it promote itself
  for the speech window. The cap deliberately does *not* apply once the
  worker reaches `RUNNING` — TTS + cast + delivery alignment can legitimately
  run several minutes, and WorkManager's own ~10-minute foreground-worker
  cap is the upper bound there. Racing those two caps was the previous
  risk; the phase split eliminates it.
- **Stale terminal WorkInfo for the same unique work name.** The Service
  watches `getWorkInfoByIdFlow(workId)` — the specific UUID the receiver
  enqueued — not the unique-work-name flow. Yesterday's `SUCCEEDED` row for
  the same queue has a different UUID and never trips this Service.
- **Worker is canceled mid-run.** `CANCELLED` is a terminal state, so the
  flow collector trips the `DONE` transition and the Service tears down
  cleanly.
- **Worker fails / retries.** Each `setForeground` is idempotent on the same
  id — the Service updates the notification in place across retries. The
  Worker's WorkManager-side backoff is unchanged.
- **Process killed mid-`DELIVERING`.** The FGS notification disappears with
  the process; `isHoldingForeground` resets to `false` on the new process so
  a future worker resumption can promote itself.
- **User dismisses the "Preparing" / "Delivering" notification.** Both are
  `ongoing` — system suppresses the swipe-to-dismiss. The user can still
  block the channel from Settings, which would suppress the notification
  but leave the FGS running.

## Why a `Service` and not just the Worker's existing FGS

The Worker's `setForeground` already covers the speech window — it's been
shipping for months. The Service exists because the speech window is too
late to be useful as a "the schedule fired" signal: by the time the Worker
gets to `setForeground` it's already past fetch + insight, and on the
notification-only path it doesn't fire at all. The Service brings the FGS
forward to the alarm-fire instant so the user gets one consistent affordance
("ClothesCast is working on it") for every delivery mode that opted in to a
visible surface.

It also moves the FGS owner out of WorkManager's `SystemForegroundService`
on the primary path: WorkManager keeps its `mediaPlayback` declaration for
the fallback (worker-self-promotion) path, and the new Service owns the
`dataSync|mediaPlayback` declaration for the primary path.
