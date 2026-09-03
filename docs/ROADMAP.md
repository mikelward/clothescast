# ClothesCast roadmap

Forward-looking design notes for changes big enough to need a decision
before they're worth building. Near-term task tracking lives in
[TODO.md](../TODO.md); shipped behavior is described in
[SPEC.md](../SPEC.md). Nothing on this page is committed work.

## Paid tier

**Status: exploration. Nothing here is built, and the tier shape isn't
settled.** This section exists to capture how a Play subscription would
actually work end to end — particularly how the Gemini TTS proxy would
learn about it — so the decision can be made against real mechanics
rather than assumptions.

The motivation is cost recovery: the shared-key Gemini path bills the
developer's API key, and the Cloud Function / Firestore / Play
infrastructure has a running cost. Today that's bounded by a hard cap of
5 syntheses per install per UTC day (see
[gemini-tts-proxy.md](gemini-tts-proxy.md)). A subscription is the
alternative to lowering that cap or dropping the shared key entirely.

**Working recommendation, not a decision: sell Gemini quota only.** The
smart-home features worth charging for — spoken and video announcements
— can't be produced without a Gemini synthesis, so they already consume
the metered quota. That makes a separate Smart Home entitlement
redundant, unenforceable, and burdened with a grandfathering problem
that has no durable implementation. The reasoning is in the next two
sections; the mechanics follow.

### The enforcement asymmetry

The structural fact that splits the feature set in two:

| | Gemini shared-key TTS | Smart Home (MQTT bridge) |
|---|---|---|
| Where the **gate** runs | Developer-operated Cloud Function | On the device |
| Who pays to run it | Developer (Gemini API key) | Nobody — the user's own broker |
| Can gating be enforced? | **Yes** — the proxy holds the key | **No** — honor system only |
| Bypass | Impossible without the key | Patch the APK |

Gemini can be genuinely gated because the developer holds the API key
and the proxy can refuse to forward. Smart Home cannot: a modified build
flips any local boolean, and the delivery paths have no developer-operated
hop to intercept — the MQTT publish goes to the user's own broker over their
own LAN, and a direct Cast target is reached from the device without the
broker at all.

**The rows above are about the gate, not about where the content comes
from** (Codex, 2026-09-03). An earlier version of this table said Smart
Home runs "entirely on-device", which is false in two ways and was carried
into `MONETIZATION.md` as an architecture and privacy claim before being
retracted there: the `audio` and `video` payloads are PCM synthesized
through the *remote* Gemini path (see the modality table below), so
developer money is spent producing what the bridge carries, and direct Cast
is a second transport that never touches the broker. Neither changes the
enforcement conclusion, which turns only on there being no developer-held
chokepoint at delivery time.

That doesn't make gating Smart Home pointless — the overwhelming
majority of users won't sideload a patched APK — but it does mean the
two features would be sold on different footings. Gemini access is a
*service*; Smart Home would be a *license*.

### Smart Home already meters through Gemini

The asymmetry above matters less than it first appears, because the
smart-home features people actually want are **already** gated by the
Gemini quota — no new enforcement, no client-side boolean, no
grandfathering.

The bridge's richest modalities can't be produced without a Gemini
synthesis. `DeliveryGates.kt:96` puts it plainly: *"Gemini is the only
producer of routable PCM, so `geminiAvailable` is a hard prerequisite —
Device TTS does its own synth at playback time and exposes no buffer."*
Concretely:

| Topic | Needs Gemini? | Metered by the proxy? |
|---|---|---|
| `<prefix>/<period>/text` | No | No — rendered on-device |
| `<prefix>/<period>/image` | No | No — rendered on-device |
| `<prefix>/<period>/has_events` | No | No |
| `<prefix>/<period>/audio` | **Yes** — needs the PCM buffer | **Yes** |
| `<prefix>/<period>/video` | **Yes** — muxes card + PCM (`png != null && wav != null`) | **Yes** |

And each scheduled run synthesizes **twice**, not once: the active
period on the main delivery path (`FetchAndNotifyWorker.kt:1229`) and
the paired next window in `publishPairedPeriodToMqtt`
(`FetchAndNotifyWorker.kt:1103`), which calls `synthesizeForDelivery`
independently so the `day` and `night` bundles are each self-coherent.

So a smart-home user on the Gemini engine spends **4 of the 5 free
slots per day** on scheduled runs alone, before a single preview tap.
They aren't merely the population that *would* exhaust the free tier —
they're already at its ceiling, and it's reached without writing a line
of entitlement code.

This reframes the whole question. **The paid tier can be Gemini and
nothing else,** and smart-home users are still a natural audience for
it — but **the upsell has to name the modality, not the Hub** (Codex,
2026-09-03). It isn't "pay to unlock MQTT", and it isn't "pay so your Hub
keeps talking" either: the canonical setup in `docs/smart-home.md` speaks
the `text` topic through Home Assistant's own TTS, which needs no Gemini
and never stops. What stops at expiry is anything carrying ClothesCast's
own PCM — the `audio` and `video` topics, and the *spoken* half of a
Cast target. A Cast **display** keeps working image-only at expiry
(`FetchAndNotifyWorker.castDestination` feeds `silentWavStub` and the
receiver still shows the outfit PNG); only an audio-only speaker skips
the load entirely. So the honest pitch is *"pay to keep the good voice on your
speaker"*, aimed at that subset; promising continuation to a `text`
subscriber sells them something they already have. Text, image, and
`has_events` stay free forever, which is right — they cost the developer
nothing and they're what the bridge's privacy story is built on.

The rest of this document covers the entitlement mechanics for that
Gemini-only tier. The client-side Smart Home gate is documented further
down for completeness, but on this reading it isn't needed.

### How the proxy would know about a paid subscription

The proxy already has everything it needs to key an entitlement: a
server-verified anonymous Firebase Auth `uid`, minted by Firebase and
unforgeable by the client. It's used today as the quota key
(`functions/src/index.ts` → `quotaKey`). Entitlement becomes a second
lookup on that same key.

The `purchaseToken` from Play Billing must **never** be trusted on the
TTS request itself — a modded client can send arbitrary bytes. It gets
verified once, out of band, and the result is cached server-side.

#### 1. Client links the purchase

After a successful purchase — and on every app start, from
`BillingClient.queryPurchasesAsync`, which is what makes reinstalls and
device changes work — the app POSTs the `purchaseToken` to a new
`linkPurchase` function. It authenticates with exactly the headers
`AppCheckGeminiCallPlanner.kt` already assembles for TTS: an App Check
token plus `Authorization: Bearer <idToken>`.

#### 2. Function verifies against Google

`linkPurchase` calls the Play Developer API
(`purchases.subscriptionsv2.get`) authenticated as the function's own
runtime service account via Application Default Credentials — grant that
identity Play Developer API access in the Play Console rather than
minting a separate key. A long-lived service-account JSON in Secret
Manager would add rotation and exfiltration risk for no benefit; keep
Secret Manager for things with no ambient-credential equivalent, like
`GEMINI_API_KEY`.

**Throttle before that call, not after.** App Check and a verified uid
prove the caller is a real install; they don't stop a real install from
calling repeatedly. Since the client links on *every* app start, and
nothing else bounds this endpoint — the TTS daily cap is a different
function — an unthrottled `linkPurchase` turns one looping client into a
drain on the shared 200k/day Play quota, and the visible symptom lands on
*other* users whose purchases then fail to activate. Three cheap guards,
in order:

- **Serve from cache first.** If `purchases/<purchaseToken>` already
  exists, is fresh (`nextCheckAt` in the future, per step 4), and already
  lists this uid, return success without calling Play at all. That covers
  the overwhelmingly common case: an unchanged subscription re-linking on
  every launch.
- **Per-uid cooldown.** Cap Play-hitting links at a handful per uid per
  day. Beyond it, respond `429` with a `Retry-After`; the client treats
  that as "already linked, try later", never as a downgrade.
- **Per-token cooldown** as well, so rotating uids against one token
  doesn't multiply the ceiling.

Retry behavior on the client is the same shape as the post-purchase
retry described under failure modes below: bounded backoff, resumed on
connectivity or `BillingClient` reconnect — with a `429` treated as a
successful no-op rather than an error, since it means the link already
exists.

**Check the product, not just the token's validity.** A successful
`subscriptionsv2.get` proves only that the token names *some*
subscription in this package — not that it names the paid tier. If the
app ever ships a second product (a cheaper tier, a one-off, a
promotional plan), a valid purchase of the wrong one would otherwise be
cached as this entitlement and granted the higher quota. Match
`lineItems[].productId` (and base plan, if plans differ in what they
grant) against an explicit allowlist before writing anything.

The response also carries the state, expiry timestamp, and auto-renew
flag. **Entitlement is not the same question as "is the state
ACTIVE"** — a user who turns off auto-renew reports `CANCELED` while
remaining paid until the period ends, so keying access off the state
alone would downgrade them the moment they cancel (and again, visibly,
if they reinstall and re-link before expiry):

| State | Paid quota? |
|---|---|
| `ACTIVE` | Yes |
| `IN_GRACE_PERIOD` | Yes — payment retrying, access continues |
| `CANCELED` | **Yes, until `expiresAt`** — auto-renew off, period still paid |
| `ON_HOLD` | No — payment failed past grace; restore on recovery |
| `PAUSED` | No — user-initiated, resumes on schedule |
| `PENDING` | No — payment not yet completed |
| `EXPIRED` | No |
| Refunded / revoked | No — immediately, regardless of `expiresAt` |

**Express this as an allowlist, never a denylist.** "Anything that isn't
hold / pause / expired" grants paid quota to `PENDING` — a purchase whose
payment hasn't cleared — and, worse, fails *open* for any state Google
adds to the enum later. The rule is: state ∈ {`ACTIVE`,
`IN_GRACE_PERIOD`, `CANCELED`} **and** `expiresAt` is in the future
**and** the purchase isn't flagged refunded or revoked. Everything else,
including states this document doesn't list, resolves to the free tier.

Store the state, `expiresAt`, and the matched product on the purchase
record described under "The identity gotcha" below, and evaluate them
together at read time.

**Retire the old token on an upgrade, downgrade, or resubscribe.** Play
issues a *new* `purchaseToken` for these and sets `linkedPurchaseToken`
on the response pointing at the one it replaces. Writing only the new
record would leave the old purchase doc active — and since paid quota
meters per token, a single subscription would hold two paid buckets at
once. RTDN normally expires the old token shortly after, but "normally"
isn't a guarantee: a missed or delayed notification leaves the duplicate
open indefinitely. Correctness here shouldn't depend on RTDN arriving.

So when `linkedPurchaseToken` is present, do the whole swap in **one
transaction**: **migrate** the old record's `installs` into the
replacement (merging on `lastSeenAt`, then applying the five-install cap
so the oldest are dropped if the union overflows), **carry today's quota
usage across** to the replacement's bucket — **and today's carried-uid
markers with it** (Codex, 2026-09-03): the idempotence record below lives
alongside the count, so migrating the count without it leaves the new
token believing it has carried nobody, and the next link re-adds a uid's
trial usage on top of the figure just migrated — the same double-count,
reached by a different route. Then repoint every surviving
`entitlement/<uid>` at the new token, and only then revoke the old
purchase record and retire its quota key.

**The same carry is needed on *every* `linkPurchase`, and this rule as
written covers only a plan change** (Codex, 2026-09-03).
`linkedPurchaseToken` is present only on a plan change, so a first
subscribe writes a fresh token bucket starting at zero — and under the
maintainer's trial shape the user who subscribes is precisely one who has
been using a 5/day allowance. Someone who spends today's five as a trial
user and then subscribes gets **five more the same day**, breaking both
the advertised cap and the cost bound below.

**And it is not only the first caller.** Each device links itself, so a
later join has exactly the same shape one device further along: a tablet
spends its five as a trial user, the phone — which has spent none —
subscribes and opens an empty purchase bucket, and linking the tablet
afterwards contributes nothing, so the pair gets five more that day. So
the rule is per link, not per purchase: on **any** `linkPurchase`, merge
the calling uid's current-day usage into the purchase bucket in the same
transaction that adds the uid, the same way a plan change merges the old
token's. Merging in the transaction is what stops two devices linking
concurrently and each reading a bucket that does not yet hold the
other's.

**And the merge has to be fenced against in-flight reservations** (Codex,
2026-09-03). A trial request can resolve its uid as unentitled, then reserve its
slot *after* the link transaction has copied that uid's count — the reservation
lands in the old uid bucket while every later call reads the purchase bucket, and
enough concurrent requests escape the carry to exceed 5/day. The inverse
overcounts: a reservation carried while in flight, then rolled back only from the
uid bucket, charges a paying user for a synthesis that never happened. So
entitlement has to be re-resolved inside the same transaction that reserves the
slot, or uid reservations fenced for the duration of the link. Whichever, the
plan owes it explicitly — the per-link merge below is not sufficient on its own.

**Neither of those two covers the rollback direction** (Codex, 2026-09-03), and
that is a third requirement rather than a restatement. A reservation that commits
*before* the link and rolls back *after* it escapes both: re-resolving entitlement
at reservation time legitimately sees the pre-link state, which was correct when
it was read, and a fence held only for the link transaction has been released long
before synthesis fails. The carried count is then in the purchase bucket while the
decrement lands on the old uid bucket, so the paid bucket stays inflated and the
subscriber's allowance is spent on a synthesis that never happened. **A rollback
must resolve the bucket that currently owns the reservation, not the one that
owned it when it was taken** — which means a reservation carries an identity the
rollback can follow across a link, or the link waits for in-flight requests to
settle. The first is cheaper; the second is simpler and costs a link a few
seconds.

**The merge has to be idempotent, and the transaction alone does not make
it so** (Codex, 2026-09-03). Linking is not a once-per-purchase event — a
client re-links whenever its cached entitlement is stale, so the same uid
can call `linkPurchase` several times in one UTC day, and a merge that
just adds the uid's counter would add the same trial usage again each
time. That is the failure this rule exists to prevent, inverted: instead
of a subscriber getting extra syntheses, a paying one is cut off early by
usage counted twice. So the purchase bucket records **which uids it has
already carried for the current UTC day**, and the transaction checks that
record before adding: a repeat link for a uid already carried today is a
no-op on the counter. The record rolls over with the day, like the counter
it guards, and being inside the same transaction is what makes the check
and the add atomic.

**A no-op is not enough on its own** (Codex, 2026-09-03). A carried uid can
later be evicted from `purchases.installs`, fall back to its own still-live
uid-keyed trial, spend more calls that day, and relink — and an unconditional
no-op leaves that second tranche outside the purchase bucket, so the account
exceeds both 5/day and the cost ceiling this rule is meant to hold. **The delta is accounting, not
enforcement, and cannot be the answer on its own** (Codex, 2026-09-03): by the
time a later link merges it, the evicted uid has already run those syntheses off
its own trial bucket while the purchase bucket was exhausted, so the merge only
records an overrun it did nothing to prevent. What actually closes it is stopping
the second tranche from happening — **retire that uid's trial eligibility
permanently the first time it links**, or route every later reservation for a
carried uid through the purchase bucket even while it is evicted. Retirement is
the simpler of the two and costs a re-linking user the remainder of a trial they
have already stopped using; the delta is worth keeping alongside either one for
the counter's accuracy, never instead of them.

Carrying the usage matters because the paid bucket is keyed on the
purchase token: a fresh token otherwise starts the day at zero, so
changing plan would reset the paid daily cap. Repeated plan changes would
then lift the ceiling arbitrarily and break the worst-case cost bound in
the cost section — which assumes one cap per subscriber per day, not one
per token they can mint.

**Migrate, don't just revoke.** The upgrade is performed on one device,
so the linking request carries only *that* uid. Retiring the old token
and writing the replacement for the caller alone would silently drop
every other linked install to the free tier — the tablet stops speaking
because the phone upgraded, and stays broken until someone happens to
open the app on it. The subscriber did nothing wrong and has no way to
connect cause to effect. Carrying the install set across is what makes
the upgrade invisible, which is what it should be.

`linkPurchase` should also `acknowledge` the purchase — Play auto-refunds
any subscription left unacknowledged for 3 days.

#### 3. `tts` reads the entitlement

Resolve `entitlement/<uid>` → `purchases/<purchaseToken>`, then grant the
paid limit only if **both** hold: the state/expiry rule from step 2
passes, **and** this uid is present in `purchases.installs`. Meter the
paid bucket on the purchase token, not the uid.

**The unentitled branch is no longer simply `DAILY_LIMIT` (5)** — the
product direction is a *time-limited trial* at 5/day, then pay to keep
5/day (maintainer, 2026-09-03; see
[MONETIZATION.md](../MONETIZATION.md)). So this step needs a third piece
this plan does not yet specify: **server-verifiable trial state** — when
the trial began, when it ends, and what the post-trial quota is (today's
code would leave a non-subscriber on 5/day forever, which is the shape
the direction replaces). The trial start cannot be a client claim for the
same reason the cap cannot: the anonymous uid rotates on reinstall, so a
client-asserted start is resettable. Design it with the free-cap privacy
question in MONETIZATION.md, not separately.

**That state has to outlive the entitlement, and be consulted on every
paid-entitlement failure** (Codex, 2026-09-03). Two paths in this plan
resolve to "the free tier" — an ineligible purchase state, and the RTDN
cleanup that deletes a terminal subscriber's entitlement pointer — and
once the permanent 5/day is gone, "the free tier" means *the trial*. So
an expired, refunded or canceled subscriber would be handed a fresh
allowance the moment their pointer is deleted, which is also a renewable
one. The trial/lapse record therefore survives entitlement deletion and
is read whenever the paid check fails, so a lapse resolves to
*post-trial*, never to *new*.

**And retiring the legacy `X-Install-Id` fallback is a prerequisite for the
trial, not a later cleanup** (Codex, 2026-09-03). Today a caller with a valid
App Check token but no `Authorization: Bearer` header has its *caller-chosen*
`X-Install-Id` accepted as the quota key (`functions/src/index.ts`), and
`docs/gemini-tts-proxy.md` says outright that rotating it evades the cap for as
long as the fallback exists. So trial state keyed on the anonymous uid is not
server-enforceable while that branch is live: expiry is escaped by omitting the
Bearer token and picking a fresh id, without even reinstalling. Two ways to
satisfy it — close the fallback before the trial ships (drop the header from the
client, delete the branch, `missing_identity` becomes `missing_auth_token`
again), or design the expiry to key on something that survives it. The first is
already the planned cleanup; the trial is what makes its timing load-bearing.

**Don't try to shortcut this with a Firebase custom claim.** It's the
obvious optimization — put `tier: "pro"` in the ID token the proxy
already verifies and skip both reads — and it doesn't work, for three
compounding reasons:

- A custom claim **never expires on its own.** The Admin SDK's stored
  value is copied into every newly minted ID token until something
  overwrites it, so a refund or hold would keep granting paid quota
  indefinitely — not "until the token refreshes".
- Skipping the reads **loses the purchase token**, which is what the
  paid quota bucket is keyed on. Metering would fall back to per-uid,
  and five linked installs would each get the full paid cap.
- Skipping the reads **also skips the membership check**, so an evicted
  install keeps paid access — the five-install cap stops bounding
  anything.

A claim carrying the purchase identifier and cleared on eviction *and*
on every subscription state change could work, but that's a fan-out
write per change plus a reconciliation sweep, to save two reads costing
~$0.0000012 against a ~$0.003 clip — four orders of magnitude apart. Not
a trade worth making. Resolve the purchase record every time.

One refinement that *is* worth it:

- **Keep a cap on the paid tier too.** "Unmetered" means the developer
  eats unbounded Gemini spend if a paid account is scripted.
  **Superseded figure:** this originally proposed 50/day as "~10× the
  free tier and far past any honest use". Under the current direction the
  paid tier is the *same* 5/day the trial gave (maintainer, 2026-09-03),
  so there is no higher tier for a 50/day ceiling to cap — **do not ship
  50/day**, and do not reuse the worst-case cost figures derived from it
  (~$4.50/month; the real ceiling at 5/day is ~$0.45). The principle
  stands: whatever the paid number is, cap it.

#### 4. RTDN keeps it honest

Play's Real-time Developer Notifications publish to a Pub/Sub topic on
renewal, cancellation, refund, grace-period entry, hold, and recovery. A
third function subscribes and updates the **purchase** record —
`purchases/<purchaseToken>`, which every linked uid resolves through.

Updating `entitlement/<uid>` here would be wrong on both counts: RTDN is
keyed by purchase token and carries no uid, and a subscription can have
several linked installs, so writing state onto one of them would leave
the authoritative record stale and the other devices entitled. Keeping
state on the purchase means a cancellation lands once and every linked
install sees it.

**This is not optional.** Without RTDN, the only signal that a
subscription lapsed comes from the client — which has no incentive to
report its own downgrade, and won't if it's been modified. Re-verifying
on every app start covers the honest case; RTDN covers the rest and
catches refunds and chargebacks the client would never surface.

**But don't treat RTDN as exhaustive either.** The same "a notification
might not arrive" argument that forces the `linkedPurchaseToken` swap to
be transactional applies to *renewals*, and there it cuts against the
paying user. If a renewal notification is delayed or exhausts its
Pub/Sub retries while the app process stays resident — no restart, so no
relink — the cached `expiresAt` is never extended, and step 3 downgrades
every linked device at the old expiry on a subscription that in fact
renewed. Silent, and it hits people who are paying.

So reconcile lazily at the boundary rather than trusting the stream:
when step 3 finds `expiresAt` in the past, **re-verify once against
`subscriptionsv2.get` before downgrading**, and write back whatever Play
says. No scheduler or sweep job needed. If Play is unreachable at that
moment, hold the existing entitlement for a bounded grace window (a day
or two) rather than downgrading on our own inability to check; a
subscriber must never lose access because our verification call failed.

**Gate the re-verification on a stored `nextCheckAt`, or it isn't
lazy at all.** A past `expiresAt` is not a one-shot condition: when Play
confirms the subscription really did lapse, writing that back leaves
`expiresAt` in the past *permanently*, so every later synthesis re-enters
reconciliation. That's one Play call per request forever — for every
lapsed subscriber, and for any modified client happy to keep calling —
not one per billing period. It would burn the shared 200k/day quota and
delay genuine purchase activations for everyone else.

Two bounds, both cheap:

- Persist `nextCheckAt` on the purchase record and only re-verify when
  `now` is past **both** `expiresAt` and `nextCheckAt`. Set it on every
  reconciliation — a short cooldown (an hour) while a renewal is
  plausibly in flight, backing off to daily once Play has confirmed a
  terminal state.
- On a **confirmed terminal** result (`EXPIRED` with auto-renew off,
  revoked, refunded), also delete the `entitlement/<uid>` pointers.
  Step 3 then resolves nothing and returns the free tier without
  touching the purchase record at all, so a long-dead subscription
  costs zero Play calls rather than a decaying trickle. A later
  resubscribe mints a fresh token and re-links normally.

With both in place the cost is what the original claim assumed: roughly
one extra Play Developer API call per subscription per billing period,
against a free 200k/day quota. The failure mode it removes — a paying
user silently downgraded by a dropped notification — is exactly the kind
that generates refund requests and one-star reviews, because from the
user's side nothing they did caused it.

#### The identity gotcha

The purchase belongs to a **Google account**; the quota key is an
**anonymous Firebase uid**. These are different identities with
different lifetimes, and they come apart in a predictable way:
reinstalling or clearing app data mints a fresh anonymous uid, while
Play restores the same purchase.

So linking must be idempotent and re-linkable. But the obvious
implementation — transfer the entitlement to the newest uid on every
link — is wrong, because it can't tell a reinstall from a second
device. One subscriber with a phone and a tablet gets the same purchase
restored on both; each app start would move the entitlement across and
revoke the other, and the subscription would ping-pong between them.

Model the purchase as **one entitlement authorizing a bounded set of
installs**, not as a token that lives on exactly one uid:

```
purchases/<purchaseToken>  {
  installs: { <uid>: { lastSeenAt } },   // recency per install
  product, tier, state, expiresAt,
  nextCheckAt                            // re-verification cooldown, see step 4
}
entitlement/<uid>          { purchaseToken }   // pointer, not the source of truth
```

Every link — new uid or not — writes that uid's `lastSeenAt`. **A bare
append-if-absent list can't express "least recently seen"**: position
would record when each install *first* linked, not when it was last
used, so a phone that linked on day one and is used daily would be
evicted ahead of a stale uid left over from a reinstall two months ago.
That's precisely backwards, and it takes paid access away from the most
active device. Keyed timestamps make eviction mean what it says.

**Refresh `lastSeenAt` on use, not just on link.** Linking happens at
app start, but a resident process runs its twice-daily syntheses for
weeks without ever relinking — so "last linked" is still the wrong
clock, just a slower-drifting one. An install that's actively speaking
the forecast every day could hold the oldest timestamp and get evicted
after enough device churn. Bump it from step 3 instead: that path
already fetches the purchase record on every synthesis, so a
server-side write there makes recency track *use of the paid feature*,
which is exactly what eviction should rank on. Throttle the write to
at most once a day per uid — a `lastSeenAt` already within today needs
no update, which keeps this off the per-call cost sheet.

Cap the set (5 is generous for one household) and evict the oldest
`lastSeenAt` on overflow, so a reinstall churning through uids
self-heals while a shared token can't fan out indefinitely. Without a
cap, one purchase could be replayed by an arbitrary number of installs.

**Eviction has to invalidate the pointer, not just the set entry.**
Dropping a uid from `installs` doesn't remove its `entitlement/<uid>` doc,
and the step-3 lookup follows that pointer straight to a purchase
that's still active — so an evicted install would keep paid quota and
the cap would bound nothing. Two ways to close it, and the second is
the safe default:

- Delete `entitlement/<uid>` in the same transaction that evicts the
  uid. Correct, but it relies on that write always landing.
- Have step 3 verify the uid is actually present in `purchases.installs` before
  granting the paid limit. One comparison on data already fetched, and
  it fails closed if an eviction write is ever lost or racy.

Do the membership check regardless; treat the pointer delete as
housekeeping rather than the enforcement mechanism.

This has a consequence worth stating: for paid users the **quota bucket
should key on the purchase token, not the uid**. Keeping it per-uid
would multiply the cap by the number of linked installs — 5 devices × a
5/day cap is 25 syntheses a day against one subscription. Per-purchase
metering also matches how the user thinks about it ("my subscription"),
and makes the worst-case cost figure below hold regardless of device
count.

**Don't set `obfuscatedAccountId`.** It's the reflex move — stamp the
uid on the billing flow so RTDN payloads tie back to an install — and on
this design it buys nothing: RTDN is keyed by purchase token (step 4),
the `purchases/<purchaseToken>` record already holds the install set, and
no verification, restoration, or quota path reads the field. Setting it
would hand Google one more stable identifier and add a line to the
privacy disclosure in exchange for no behavior. Same test as the Play
order ID below: if a concrete fraud check is ever designed that consumes
it, add it then — hashed, per Google's contract for that field — with the
reason written down.

One supporting measure:

- Consider whether the anonymous-account auto-deletion suggested in
  [gemini-tts-proxy.md](gemini-tts-proxy.md) step 4 (30 days inactive)
  could delete a paying user's uid. It re-links on next launch via
  `queryPurchasesAsync`, so this is survivable — but it's worth
  exempting entitled uids rather than relying on the recovery path.

#### Offline and outage behavior

The twice-daily worker runs whether or not the network is cooperative,
and a paying user must not lose their voice because Play or Firestore
had a bad afternoon. The two gates degrade differently, and conflating
them is a security hole:

**Server-side (Gemini quota) — the proxy can never trust a client
cache.** A cached boolean sent up by the app is exactly as forgeable as
the `purchaseToken`; honoring it would let any modified free client
assert a paid entitlement. Fortunately the proxy doesn't need one: the
`purchases/<purchaseToken>` doc *is* the server-side cache, written at
link time and refreshed by RTDN, so a Play Developer API outage doesn't
touch the hot path at all — the proxy never calls Play during a
synthesis. That leaves two real cases:

- **Firestore unavailable.** Fail open for *every* request, paid and
  free alike, exactly as `reserveDailySlot` already does today. The
  free-tier cap lapsing for the duration of a Firestore outage is the
  status quo and an acceptable cost; it doesn't require trusting anyone.
- **No client-asserted fallback, ever.** If the reads can't be made, the
  answer is the free tier or the fail-open above — never a boolean the
  client sends up, which is as forgeable as the token itself. Step 3
  explains why the signed-custom-claim variant of this idea doesn't
  rescue it either: it would drop the purchase identity the quota is
  keyed on and skip the install-membership check.

**Client-side (Smart Home) — a local cache is fine,** because the gate
is already honor-system (see the asymmetry table above). There's no
threat model in which caching it locally makes anything worse, so cache
the last-known-good entitlement with its expiry and fail open within a
grace window of a few days. A paying user's automations must not stop
firing because their phone spent a weekend offline.

### Gating Smart Home in the app

**Probably unnecessary — see "Smart Home already meters through Gemini"
above.** Recorded here so the option is costed rather than assumed away.

There's a clean chokepoint already: `isMqttPublishable(prefs)` in
`core/domain/.../usecase/DeliveryGates.kt:210`, which the worker
consults before every publish and which the KDoc explicitly nominates as
the one place downstream code should read (rather than touching
`mqttBridgeEnabled` directly). An entitlement term folds in there and
every publish path inherits it.

Two constraints on how that's done:

- **`:core:domain` must stay pure Kotlin.** Billing is an Android
  concern, so the entitlement arrives as a plain boolean on
  `UserPreferences` (or as a parameter, matching how `geminiAvailable`
  is already threaded in from the worker's keystore check). No Play
  Billing types below `:app`.
- **The UI needs to degrade honestly.** A Settings → Smart Home page
  that silently stops working is worse than one that says why. The
  existing `mqttLastError` surface is the natural place to say "Smart
  Home requires a subscription" rather than failing silently.

#### Grandfathering — the real problem

The bridge already shipped, free, and is documented at length in
[smart-home.md](smart-home.md). Anyone using it today configured a
broker, created an HA user, possibly wrote an ACL and a handful of
automations. Taking that away retroactively is the kind of thing that
earns one-star reviews from exactly the users who invested most in the
app.

Play has no built-in grandfathering mechanism, so it has to be
constructed — and **there is no clean way to construct one here.** The
obvious approach is a marker ("MQTT was configured before version N")
treated as a permanent entitlement, but neither place to put it works:

- **On-device** dies on reinstall or clear-data, which is precisely when
  the user most needs it restored.
- **Server-side, keyed on the anonymous uid,** dies the same way. A
  reinstall mints a fresh uid, and unlike a paying user, a grandfathered
  one has *no purchase token* to re-link with — nothing ties the new uid
  back to the old record. The recovery path that saves subscribers
  doesn't exist for legacy users.

Making it durable would require a recoverable identity — a real
Google sign-in — which trades away the anonymity that PRIVACY.md
currently treats as a feature, and imposes a sign-in on users who never
asked for an account, in order to keep something they already have.

That leaves accepting the loss on reinstall (a support burden and a
bad look), or not creating the problem.

**Not creating it is the better option, and it costs nothing.** Between
this and the Gemini-metering point above, the case is one-sided: legacy
entitlement has no durable implementation, while the smart-home
modalities worth charging for are already metered by the proxy. Leave
the bridge free, let `audio` and `video` ride the Gemini quota they
already consume, and the paid tier's story stays clean: **the
subscription pays for the things that cost money to run.**

### Cost and reliability

**Revenue side.** Play's service fee on auto-renewing subscription revenue is
**15% from day one** — not a first-$1M rate that rises to 30% above the
threshold, which is a separate program for one-time products that a developer
account must enroll in. A $2.99/month subscription nets ~$2.54. (Corrected
2026-09-03; the earlier "15% of the first $1M/year" wording conflated the two
and understated the margin. Confirm against the current Play terms before the
price is committed — service-fee terms have moved before.)

**Infrastructure cost of the subscription machinery itself:**

- Play Developer API — free; 200k requests/day quota. One call per link
  plus one per RTDN event is nowhere near it.
- Pub/Sub for RTDN — comfortably inside the free tier at any plausible
  volume (a few events per subscriber per month).
- Firestore — **two** extra reads per TTS call, not one: step 3 resolves
  `entitlement/<uid>` and then `purchases/<purchaseToken>`. Plus one
  `lastSeenAt` write per active paid install per day (throttled — a
  timestamp already within today is left alone). At roughly $0.06 per
  100k document reads and $0.18 per 100k writes:

  | | Per unit | At 100 paying users |
  |---|---|---|
  | 2 reads × 4 syntheses/day | ~$0.0000012 per call | ~$0.0003/day, **~$0.01/month** |
  | 1 write per install per day | ~$0.0000018 | ~$0.0004/day (200 installs), **~$0.01/month** |

  So **~$0.02/month at 100 subscribers** — against ~$254 of gross
  revenue at that scale. The free tier (50k reads + 20k writes/day)
  covers roughly 6,000 paying users on reads and 20,000 installs on
  writes before any of it is billable at all. Note the existing
  `reserveDailySlot` already performs a transactional read + write per
  call, so this shares a free-tier budget rather than opening a new one;
  the free-tier headroom above is the marginal figure, not the total.

  Denormalizing tier and expiry onto `entitlement/<uid>` would make it
  one read, but then RTDN has to fan out every state change to every
  linked uid, and the membership check loses the data it compares
  against — the same reasons the custom-claim shortcut fails in step 3.
  Two reads is the correct choice, not merely the cheap one.
- Two additional Cloud Functions — within the ~2M invocation free tier.

So the marginal infrastructure cost is effectively zero; Gemini remains
the only bill that scales. **At the 5/day cap the maintainer's trial shape
keeps** (`MONETIZATION.md`), worst-case exposure per paying user is
**~$0.45/month** against ~$2.54 net revenue at $2.99 — a ~5.6× margin even
for a user who never misses a slot. The earlier figure here was
~$4.50/month, derived from the superseded 50/day cap; it is an order of
magnitude out and must not be reused.

**That margin bounds the *recurring* cost only, and does not make the price
a willingness-to-pay question** (Codex, 2026-09-03) — an earlier version of
this paragraph said it did, and `MONETIZATION.md`'s question 3 no longer
does. Two costs sit outside the per-subscriber recurring quota: trial
acquisition, where a max-use 30-day trial is ~$0.45 spent before any
revenue and is spent again on every non-converter and every trial restart;
and the **commercial Open-Meteo license**, a fixed monthly floor whose
figure is not yet known (`MONETIZATION.md`). Both are divided by the
subscriber count rather than charged per subscriber, so at low conversion
or low volume they dominate this ratio. Cost recovery is settled for a
*steady-state* subscriber and unsettled for the business. Realistic use
is lower still: a smart-home user on twice-daily delivery spends 4
syntheses/day (two runs × the active and paired windows), about
$0.36/month. All of it holds only if the cap is metered **per purchase
token** rather than per uid; see the identity section — per-uid metering
multiplies it by the number of linked devices (~$1.35/month at three,
~$2.25 at five) and, because the uid rotates on reinstall, has no ceiling
at all without an aggregate per-purchase bound (`MONETIZATION.md`).

**These are normal-operation figures, not an absolute bound** (Codex,
2026-09-03). `reserveDailySlot` **fails open** — it logs "Quota
reservation failed; failing open" and proceeds — so during a Firestore
outage neither the daily counter nor the entitlement check constrains
anything, and a scripted client (a lapsed trial included) can reach Gemini
at whatever rate it likes. That is the right availability choice and this
plan keeps it, but it means the outage exposure is a **separate bound that
does not exist yet**: it wants a rate limit that survives the store being
down, or a spend alarm that notices. Sizing it is open work; what is not
open is that ~$0.45 describes the healthy path only.

**New failure modes**, each of which the user experiences as "my voice
stopped working":

- Play Developer API outage during linking → the proxy never calls Play
  in the hot path, so this only delays a *new* purchase taking effect.
  But "delays" is doing real damage here: the user has been charged and
  is still on the free limit. **Don't wait for the next app start** — an
  app that stays resident would never retry, so someone who just paid
  watches their voice keep cutting out with no way to fix it. Persist
  the unlinked `purchaseToken` locally, retry with bounded backoff, and
  retry again on connectivity or `BillingClient` reconnect. Surface the
  state in Settings ("Subscription active — activating…") so the gap
  reads as pending rather than broken, and so a user who contacts
  support has something to describe.
- RTDN delivery lag → a cancellation takes minutes to land. Harmless;
  errs toward the user.
- Billing library returning `SERVICE_UNAVAILABLE` on older devices or
  builds without Play Services → the app must treat "can't determine
  entitlement" as "use the last known value", never as "downgrade".
  Applies to the client-side Smart Home gate only; the proxy resolves
  entitlement server-side and never asks the client.
- Two more Firestore reads in the TTS hot path, plus a throttled
  `lastSeenAt` write at most once a day per install — ~$0.02/month at
  100 subscribers, quantified in the cost table above. Negligible beside
  the Gemini call they precede; see step 3 for why the custom-claim
  shortcut that would avoid them doesn't actually work.

### Privacy

Introducing billing sends new data off-device, so PRIVACY.md needs a
section before any of this ships. Specifically:

- The `purchaseToken` travels to the developer-operated function and is
  stored in Firestore against the anonymous uid.
- It's pseudonymous — no name, no email — but it's a stable identifier
  linkable to a Google account **by Google**, and it associates a payment
  identity with the anonymous uid that was previously the whole point of
  being anonymous.
- **Store the token and nothing else identifying.** The Play order ID in
  particular is a second stable payment identifier that this design never
  consumes: verification, RTDN, entitlement lookup, and quota all key on
  the `purchaseToken`. Persisting the order ID would widen what a breach
  or export exposes while buying nothing, which is exactly the "less, not
  more" default PRIVACY.md sets. If a support workflow later needs it,
  add it then, with a reason.
- Nothing about the forecast, calendar, or location is involved, and
  none of it should ever be attached to an entitlement doc. The hard
  "do not transmit" list in PRIVACY.md applies unchanged.
- Play Billing itself requires no new Android permission beyond
  `com.android.vending.BILLING`.

The user-facing disclosure should say plainly what's stored and why:
"When you subscribe, ClothesCast sends the purchase receipt to its
server to verify it with Google Play, and stores it against your
anonymous install ID so the app knows your subscription is active."

### Open questions

Ordered by what blocks what — the first one determines the shape of
everything else.

1. **What is the subscription actually selling?** Current answer:
   **Gemini quota only.** It has a real marginal cost, it's the one
   thing genuinely enforceable, it needs no grandfathering, and — per
   the metering section above — it already captures the smart-home
   users worth charging, because spoken and video announcements can't
   be produced without it. Revisit only if a future feature has a
   developer-side cost that Gemini quota doesn't already track.
2. **Price point and free-tier cap.** These move together: the current
   5/day cap was set to bound developer cost, not to create upgrade
   pressure. **The maintainer's trial shape settles the direction and
   removes the "upsell" reading**: the cap stays at 5/day and the trial
   expiring is what creates the pressure, so what is sold is *continued
   access at the same cap*, never a lifted limit — a subscription pitched
   as an upsell would promise usage it does not deliver
   (`MONETIZATION.md`). What stays open is the trial's length and the
   price. **The cap may already be too tight**: a smart-home user on twice-daily delivery
   spends 4 of 5 slots on scheduled runs alone (the active window plus
   the paired one, per the metering section), leaving a single preview
   tap. Worth measuring the real distribution before setting a price.
   **It cannot be an upgrade trigger, though, and the question as first
   written asked whether it was** (Codex, 2026-09-03): paid access is the
   *same* 5/day, so a user who has hit the cap gains nothing by
   subscribing — they stay capped until the UTC reset either way. Prompting
   there sells something that does not arrive, which is the "upsell
   reading" this paragraph rejects two sentences earlier. **Trial expiry is
   the only conversion moment under this shape.** So the open question is
   narrower: whether a smart-home user spending 4 of 5 slots on scheduled
   runs is an accident of how the paired publish works — and if it is,
   whether the fix is a higher cap, a cheaper scheduled path, or counting
   the pair as one.
3. **One-time purchase vs. subscription.** Recurring Gemini cost argues
   for a subscription: a one-time payment can't fund an ongoing
   per-synthesis bill. Play supports both, but the cost structure only
   fits the recurring model here.
4. **BYOK users.** Anyone who pastes their own Gemini key already
   bypasses the proxy and pays their own bill. They should presumably
   never see an upsell for TTS quota — but **the gate is not the key's
   presence**, and building it that way ships a broken paywall. Once a
   stored key fails to decrypt, `SecureKeyStore.read` removes the
   ciphertext and sets `GEMINI_NEEDS_REENTRY`, then throws
   `InvalidApiKeyException`; `AppCheckGeminiCallPlanner.readOwnKey`
   catches only `MissingApiKeyException`, so that state deliberately does
   *not* fall through to the shared proxy. Meanwhile
   `geminiKeyConfiguredFlow` reads ciphertext presence, which is now
   false — so a presence check offers the quota upsell to exactly the
   user whose purchased quota the planner will refuse to use. Gate on
   **configured OR needs-reentry**, and give the needs-reentry state its
   own copy (re-enter or clear the key) rather than a subscription offer.
   See [MONETIZATION.md](../MONETIZATION.md).

### Related

- [gemini-tts-proxy.md](gemini-tts-proxy.md) — the proxy, App Check,
  anonymous auth, and the existing daily quota this would extend.
- [smart-home.md](smart-home.md) — the MQTT bridge as shipped.
- [play-store-internal-testing.md](play-store-internal-testing.md) —
  Play Console setup; subscriptions are configured in the same console
  and need a testing track to exercise.
- PRIVACY.md — the data-handling contract a billing flow would amend.
- [MONETIZATION.md](../MONETIZATION.md) — the product half: what is being
  sold, the price, the free cap as a product decision rather than a spend
  limit, and how anyone finds the app. It answers some of the open
  questions above and restates none of the mechanics here.
