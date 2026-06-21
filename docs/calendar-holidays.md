# Calendars and holidays

With your permission (`READ_CALENDAR`), ClothesCast can read the calendars
already synced to your phone to make the daily forecast more useful:

- **Evening extras** — if you have an evening event somewhere, the forecast
  can fold in the weather for *that* part of the day (e.g. "Tonight, rain,
  bring a jacket").
- **Birthdays and public holidays** — these can theme the outfit card with
  special colors and messages.

The **Calendar settings** screen has two listings — **Birthdays** and
**Public holidays** — that show the upcoming celebrations ClothesCast found in
your synced calendars for the year ahead. Event titles stay on your device:
they never appear in notifications, the spoken forecast, Cast, MQTT, or any
analytics. See [PRIVACY.md](../PRIVACY.md) for the full boundary.

## Why a holiday sometimes shows on the wrong date

You may notice a public holiday listed on a date that your Google Calendar app
no longer shows — for example, a "King's Birthday" appearing a week after the
real one.

This isn't ClothesCast inventing a date. ClothesCast reads holidays straight
from your phone's shared calendar storage — the same place your calendar app
syncs Google's "Holidays in <country>" calendars into.

The catch: **Google revises those holiday calendars, sometimes only days
before the date, and the change isn't always clean.** A real example — the UK
"King's Birthday" for 2026 was moved from 20 June to 13 June on 16 June, four
days beforehand. When Google moves a holiday like this, your phone usually
picks up the new date, but the *old* copy can be left behind in your phone's
calendar storage as a leftover ("ghost") entry. Google's own Calendar app
tidies that away in its display, but other apps that read your calendar —
ClothesCast included — still see it until the storage is cleaned up.

So the wrong date you're seeing is a stale entry sitting on your device, not a
date ClothesCast made up.

## How to fix it

A normal "sync now" or toggling account sync off and on usually **won't**
remove a ghost entry — your phone only deletes an event when the server
explicitly tells it to, and a quietly-revised holiday sometimes never sends
that signal. You have two reliable options, mild to thorough:

1. **Re-subscribe to the holiday calendar.** In the Google Calendar app:
   Menu (☰) → **Settings** → the affected **Holidays in <country>** calendar →
   turn it **off**, then **on** again. This re-fetches that calendar.
2. **Clear the calendar cache** (thorough). This rebuilds the shared calendar
   database from scratch and drops leftover entries. Clearing it while a sync
   is mid-flight tends to leave the calendar half-populated, so do it in this
   order:
   1. **Settings → Passwords & accounts** (or **Accounts**) → your Google
      account → **Account sync** → turn **Calendar** sync **off**.
   2. **Settings → Apps**, turn on **Show system apps**, open **Calendar
      Storage** → **Storage & cache** → **Clear storage**.
   3. **Restart the phone.**
   4. Turn **Calendar** sync back **on**, then open Google Calendar. The first
      full re-sync can take a few minutes — and sometimes a couple of attempts.
      If the calendar looks incomplete at first, give it time or pull down to
      refresh.

   > **Caution:** this clears the calendar database for *every* account on the
   > phone, not just a throwaway cache. Anything backed by a sync account
   > (Google, Exchange, and the like) comes back on the next sync — but events
   > in **local, on-device-only calendars**, or events you created that never
   > synced anywhere, are **not** recoverable. If you have any local calendars,
   > use option 1 instead, or back them up first (for example, export to iCal).

After either step, reopen ClothesCast's Calendar settings and the stale entry
should be gone.

## Or just turn the calendar off in ClothesCast

If you'd rather ClothesCast ignore a particular holiday calendar entirely —
ghost entries and all — open **Calendar settings → Calendars** and switch it
off. That stops it theming outfits and appearing in the listings, and it
doesn't touch your Google Calendar in any way.

## Reporting a wrong holiday to Google

Because the date originates in Google's holiday calendar, the source fix is on
Google's side. You can report it from the Google Calendar app:
Menu (☰) → **Help & feedback → Send feedback**, with a screenshot of the wrong
date attached.
