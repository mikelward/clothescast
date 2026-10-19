# Delete your ClothesCast account

ClothesCast needs no account. The only account you can create is through
**Sign in with Google**, for the free online voice or for the Google forecaster
without a key of your own. Here's how to delete it, and what happens to your
data. Older versions also used anonymous IDs; see the end of
[What is kept](#what-is-kept-and-for-how-long).

## In the app

1. Open ClothesCast.
2. Go to **Settings → Speech** (or **Settings → Forecasters**).
3. Tap **Delete account** and confirm.

## Without the app

Email **play@mikelward.com** from the Google account you signed in with,
with the subject **Delete my ClothesCast account**. If you write from a
different address, say which Google account to delete; we'll then email that
account and delete it only once you confirm from it. We'll delete the account within 2 months and
reply when it's done. That's the same deletion as the in-app button, so
everything under [What is deleted](#what-is-deleted) applies, just later. We
delete your request email and our reply within 2 months of that, or, if the
deletion can't go ahead (no such account, or no confirmation), within 2
months of your request.

Deleting in the app is the fastest way.

## What is deleted

Your ClothesCast account in Firebase Authentication, and with it:

- your email address
- your name
- your profile photo URL
- your Firebase user ID, and any usage limit set on it

You're signed out at once. A sign-in still open on another device can use
the free voice and Google forecast for up to an hour, until its token
expires. Firebase then removes the deleted details from its own systems on
its schedule: see
[Firebase's privacy and security information](https://firebase.google.com/support/privacy).

## What is kept, and for how long

- **Today's usage counts**: the number of free voice clips and Google forecast
  requests used today. They're stored under a keyed hash of your Google
  account ID, hold only the date and the count, and expire at the end of the
  day (midnight UTC), when Firestore's
  [automatic cleanup](https://firebase.google.com/docs/firestore/ttl)
  deletes them.
  They're kept so that deleting and re-creating an account doesn't reset the
  day's limit, and can't identify you without your Google account.
- **Server request logs**: Google Cloud's infrastructure logs record the IP
  address and status of each request to the ClothesCast servers. They aren't
  linked to your account. They're kept for 30 days, Cloud Logging's
  [default retention](https://cloud.google.com/logging/docs/routing/overview#logs-retention),
  which this project uses.
- **Older versions' anonymous IDs**: versions before Google sign-in used a
  random ID with no name or email: first a Firebase Installation ID, later
  an anonymous sign-in. The voice proxy counted each one's use (first use,
  last use and number of clips). Nothing links these IDs to you, so they
  can't be found and deleted on request. When the free voice stops
  supporting those older versions, we delete every anonymous sign-in and
  every one of these usage records. The Installation ID itself belongs to
  the Firebase libraries on your phone, not to us; uninstalling the app
  removes it.

## Data on your phone

Settings, API keys and cached forecasts are stored on your phone and are
never part of your account. Uninstalling ClothesCast deletes them.
Uninstalling does **not** delete your account; delete it first, as above.

If you turned on crash and usage reporting (Settings → Privacy), some data
also left the phone. None of it is tied to your account, so deleting the
account doesn't remove it:

- **Crash reports** (Firebase Crashlytics) carry an install identifier used
  to group duplicate crashes. Turning reporting off doesn't reset it;
  uninstalling the app does. Crashlytics deletes crash reports on its own
  schedule (see
  [Firebase's privacy and security information](https://firebase.google.com/support/privacy)).
- **Usage events** (Google Analytics for Firebase), including some settings
  values, carry a random analytics ID. Turning reporting off stops them and
  resets that ID. We set Analytics to keep event data for 2 months and the
  data tied to an analytics ID for 14 months after it was last active;
  Google then deletes it as its
  [data retention help](https://support.google.com/analytics/answer/7667196)
  describes. The aggregate reports built from it don't identify an install.

See [Analytics and crash reporting](../PRIVACY.md#analytics-and-crash-reporting)
for exactly what each carries.

See the [privacy policy](../PRIVACY.md) for everything ClothesCast handles.
