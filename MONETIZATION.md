# Monetization

**Status: exploration, and nothing here is being locked in** (maintainer,
2026-09-03) — except where a line says the maintainer settled something, which
this page then argues from. It is written as options and their costs, not as a
set of decisions waiting for a signature.

This page is the *product* half of the money question — what is being sold, at
what price, to whom, and how anyone finds out the app exists. The *mechanical*
half is worked out in [docs/ROADMAP.md](docs/ROADMAP.md#paid-tier): Play
Billing, RTDN, server-side entitlement, the Firestore shape, and the real
infrastructure cost table. **Read that first; nothing here restates it.** Where
this page answers one of its open questions, it says which one.

**"Worked out" is not "finished", and an earlier version of this line said
"already worked out"** (Codex, 2026-09-03). The subscribe/lapse machinery is
designed; the *trial* the maintainer's direction adds is not. That plan now says
so itself — it does not yet specify when the trial starts, when it ends, or what
quota follows it, and it names retiring the spoofable install-id fallback as a
prerequisite for enforcing any of that. So an implementer reading both pages
today still cannot tell a trial user from a lapsed one, which is the central
branch of everything below. Treat the mechanical half as ahead of this page, not
as done.

## What is being sold

ClothesCast is the only app in this family with a **genuine marginal cost per
user** — the shared-key Gemini path bills a real API key on every synthesis. That
makes it both the easiest to justify charging for and the easiest to get wrong,
because the free tier is not a marketing choice here, it is a spending limit.

`docs/ROADMAP.md`'s working recommendation — **sell Gemini quota only** — is
right, and this page endorses it rather than reopening it. The addition is a
reason it gives less weight: an unenforceable paywall is worse than no paywall.
**What is unenforceable is the Smart Home *gate*, not the whole feature** (Codex,
2026-09-03) — an earlier draft said Smart Home "runs entirely on-device against
the user's own broker", which is wrong twice: a direct Cast target is a separate
transport that never touches the broker, and the `audio`/`video` payloads are PCM
synthesized through the remote Gemini path (see the modality breakdown below). The
enforcement point survives that correction intact, because it was never about
where the bytes come from: the gate itself is a client-side check with a
user-controlled endpoint on the other side of it, so gating Smart Home is an honor
system, and the first user who discovers that the lock does nothing learns that
*this app's locks do nothing*. That lesson does not stay in one feature.

Stated for a buyer rather than for an engineer, the product is:

> **Keep the good voice.** ClothesCast tells you what to wear, and reads it out
> in a natural voice. Try that free; subscribe to keep it.

**This line has been wrong twice, in opposite directions**, and both are worth
keeping visible because the tempting version keeps changing:

- **Not "a better voice."** The free path uses the *same* Gemini voice, so a user
  below the cap would pay and hear no difference at all.
- **Not "more of the good voice" / "paying lifts the limit"** either — that was
  the correction to the first error, and the maintainer's trial shape falsified it
  (Codex, 2026-09-03). Paying does **not** lift the limit: it keeps the same 5/day
  after the trial ends. Someone already using five a day who bought on that promise
  would get no additional usage, which is the same refund-and-one-star failure in a
  new costume.

So what is sold is **continuation, not quantity and not quality**: the natural
voice keeps working. That is the honest pitch and it is a decent one — but it is
**modality-specific, not global** (Codex, 2026-09-03). It is exact for someone
reading or hearing the insight in the app. For a Smart Home user on the
`audio`/`video` topics or the spoken half of a Cast target, expiry removes the PCM buffer and
the endpoint stops working altogether, so what they lose is the feature, not a
nicer voice. The warning further down says buyer-facing copy must not promise
otherwise; this summary is bound by it too.

---

## Price: recommend $2.99/month, with the range open

**The recommendation is $2.99/month**, with an annual alongside it, on the volume
argument below: $2.18/user of margin against $0.49 at $1, for identical support
and feature work.

**What changed is the floor, not the answer.** This section originally leaned on
two things — that volume argument, and an asymmetry where one heavy user at a
50/day paid cap ate nine subscribers' margin. The maintainer's shape (5/day during
a trial, then pay to keep the same 5/day) bounds worst-case spend at ~$0.45/month,
so **the asymmetry no longer applies** — see "The trial question" below.

The consequence is that the *safe range widens*, not that it points downward:
$1/month stops being underwater against a heavy user on the recurring
arithmetic. That is narrower than "safe" — the trial is spend before any
receipt, and the section below works through why $1 is not established as
positive once it is counted. Nothing about the widening makes it preferable — it makes **recurring
per-subscriber spend** a market question rather than a cost question, that
clause only (Codex, 2026-09-03): trial acquisition and the fixed Open-Meteo
license sit outside it, and until those land no price here is established as
viable. The four inputs below are how it gets settled.

**But the price properly follows from four things nobody has measured yet**
(maintainer, 2026-09-03), and this is the conversation to have before committing:

1. **How many sales do we expect?** Margin per user only matters multiplied, and
   at very low volume no monthly price funds anything — which is an argument about
   whether to charge at all, not about $1 vs $2.99.
2. **What are the margins really**, once the cap is set and fixed costs are known?
3. **What is the ceiling a user will bear** for this specific thing? Nobody has
   tested it, and it is the one input a competitor scan could actually answer.
4. **What is the goal** — cover the Gemini bill, fund the next feature, or find
   out whether anyone will pay at all? The third implies the lowest price of the
   three, possibly lower than $2.99, because it optimizes for *a* conversion
   rather than for margin.

So take $2.99 as the recommendation to argue against, not a settled number. The
arithmetic below is what holds either way.

From `docs/ROADMAP.md`:

- A **$2.99/month** subscription nets **~$2.54**.
- **Realistic** paid use — a smart-home user on twice-daily delivery, four
  syntheses a day — is about **$0.36/month** in Gemini spend.
- **Worst case at a 50/day paid cap** is about **$4.50/month**, which is *more
  than the revenue*.

A $1/month price nets roughly $0.85, leaving **$0.49** on the realistic user;
$2.99 nets ~$2.54, leaving **~$2.18**. So:

- **4.4× the margin per user for identical effort** — the same support load, store
  admin and feature work either way. At fifty subscribers that is $24.50/month
  against ~$109.
- **The downside was asymmetric — and under the trial shape it is not.** This
  argument assumed a 50/day paid cap, where one user costs ~$4.50: nine realistic
  users' margin at $1, two at $2.99. **With paying users held to 5/day it does not
  apply**, and the figure to use is ~$0.45. Kept here because the asymmetry
  returns the moment anyone proposes a higher paid cap.

**An annual (~$19.99–24.99) is worth considering whatever the monthly is.** It
cuts churn on a product whose value is seasonal (an outfit briefing matters more
in a changeable spring than a settled summer), takes payment before the quiet
months rather than during them, and the discount is the cheapest retention
available.

**The cap is what makes any of this safe — and this comparison is now history,
kept for the reasoning rather than the numbers** (Codex, 2026-09-03). The
maintainer's trial shape settles the paid tier at the *same* 5/day the trial
gives, and `docs/ROADMAP.md` says outright not to ship 50/day or reuse the
figures derived from it. The live number is **~$0.45/month**, derived further
down; nothing in the table below should be used to price anything.

What is worth keeping is why 50/day was never a measurement: `docs/ROADMAP.md`
proposed it as "a generous cap (say 50/day, ~10× the free tier and far past any
honest use)" — an abuse ceiling picked relative to the free tier, with nobody
having measured real paid use. **It should probably be lower** (maintainer,
2026-09-03), and that changed the pricing arithmetic rather than sitting beside
it — which is how the shape below was arrived at. Historical, at ~$0.003 a
synthesis:

| Paid cap | Worst case / month |
|---|---|
| 50/day | ~$4.50 |
| 15/day | ~$1.35 |
| 10/day | ~$0.90 |

At 15/day the "one heavy user eats nine subscribers" asymmetry above mostly
disappears — which **weakened the case for $2.99 rather than strengthening it.**
That was the argument that carried; at the settled 5/day the asymmetry is gone
altogether. The durable lesson is the one this section was written to make: the
cap and the price want deciding together, not in sequence.

### The metering unit, and the privacy question under it

**Per account, not per device** (maintainer, 2026-09-03) — and `docs/ROADMAP.md`
already lands there for the paid tier, for the same reason: the quota bucket
should key on the **purchase token**, not the uid, since per-uid multiplies the
cap by linked installs (at the 5/day the trial shape keeps, 5 devices is 25
syntheses a day against one subscription; the old 50/day draft made it 250). The purchase token is also a better unit than an account
identifier, because Play already ties it to the buyer without this app learning
who they are — which is why ROADMAP says *don't* set `obfuscatedAccountId`.

**The free cap is where this gets a privacy question, and it is open — nothing
here is ruled out** (maintainer, 2026-09-03). It is enforced against the anonymous
Firebase uid, which **rotates on reinstall**, so the free cap is per-install today
and trivially resettable by clearing app data. Three ways to answer it:

- **Leave it leaky.** Nothing to build, nothing new to declare, and the leak is
  bounded in practice: someone willing to clear app data every day for five extra
  syntheses is not a lost sale. What it cannot do is hold a cap that has to hold.
- **A durable device identifier.** The only thing that makes a per-device cap
  actually a cap. Its costs are real and belong in the decision rather than
  standing in for one: it changes the **Play Data Safety declaration**, it is
  collected from *free* users — the population least compensated for it — and it
  **would likely mean editing `docs/PRIVACY.md`**, which today rules out ad
  identifiers. **That is a document this project writes, not a constraint handed
  to it** (maintainer, 2026-09-03): a policy change is a cost to weigh, not a
  veto, and an app-scoped ID that survives a reinstall is a different thing from
  an advertising ID in any case.
- **An aggregate per-purchase ceiling.** Bounds spend without identifying anyone,
  because it counts against the purchase token. It answers the *paid* side and
  does nothing for the free cap, where there is no purchase to count against.

The choice is the maintainer's, with the Data Safety consequence named. What this
page should not do — and an earlier draft did — is settle it by declaring a leaky
cap "the price of not fingerprinting devices": that reads an existing policy line
as a permanent prohibition, which is neither what it says nor something a
monetization page gets to decide.

**The rate is settled for a subscription, and an earlier draft of this section
made it look open.** Google's subscription service fee has been **15% on
auto-renewing subscription revenue from day one** since January 2022,
independent of the separate first-$1M program a developer account has to enroll
in (Codex, 2026-09-03). So:

- `~$2.54` on a $2.99 subscription is the figure to plan against.
- The **~$2.09 unenrolled scenario this page previously carried does not apply to
  this app.** Enrollment governs *one-time* products, which is why the sibling
  typelauncher, simmo and snoozemo pages keep that caveat and this one drops it.
- **`docs/ROADMAP.md` carried the wrong framing** — "Play takes 15% of the first
  $1M/year" reads as 30% above the threshold, conflating the two programs and
  understating the margin of the only app in this family with a real marginal cost
  per user. **Corrected there in the same commit as this line**, rather than left
  as a defect this page merely points at.

Still read the current Play terms before the price is committed rather than after.
Service-fee terms have moved before, and this page should not be anyone's
authority on what Google charges today.

---

## The trial question, and why the maintainer's answer inverts this section

**Maintainer direction (2026-09-03): keep the 5/day figure, but make it a free
trial rather than a permanent free tier.** That reverses what this section
originally argued, and the argument is worth keeping visible because it was
correct *given a permanent free tier* and stops being correct without one.

**What this section used to say:** don't ship a trial, because the free tier
already *is* one — real Gemini syntheses, every day, forever, up to the cap, so
the user hears exactly what they would be buying. Better than a countdown,
because it never expires and never produces the "my app broke" moment a lapsed
trial creates.

**Why that dissolves under the new direction:** if the free path is time-limited,
there is no permanent tier demonstrating the product, so the trial is not a
*second* copy of the demo — it is the only demo. The redundancy argument was the
whole load-bearing half; with the premise gone, ship the trial.

**And it improves the economics — for the installs that don't reset.** Under a
permanent free tier a non-converting user costs Gemini spend **forever**. Under a
trial they cost one bounded period and then nothing.

**That is qualified, not absolute** (Codex, 2026-09-03), and the qualification is
the same hole as point 3 below: clearing app data rotates the anonymous uid and
starts a fresh trial, so a determined non-converter can keep consuming
developer-funded Gemini calls indefinitely. So the honest comparison is:

- **A non-resetting non-converter** costs one bounded period. That is most of
  them, and it is a real improvement on "forever".
- **A resetting one** costs a trial per reset — bounded per cycle, unbounded in
  total, and cheaper for them to do than a permanent free tier ever made necessary
  (under a permanent tier there was nothing to reset *for*).

Net: the trial still widens the range of defensible prices rather than arguing for
any particular one, but the free-rider cost is *reduced*, not eliminated, and the
residual is exactly what a stable identifier would close — the privacy decision in
the pricing section. Settle the trial shape before the price, because the trial is
what determines how much of the price is covering cost at all.

**Three things to get right, and one of them is a live cost:**

1. **For the existing users, this is the revoke move — acknowledged, not
   forbidden** (Codex, 2026-09-03). They have 5/day today and would stop having
   it. An earlier draft called that "the one act the top of this page rules out",
   which contradicts the never-sold list itself: lowering an existing cap is
   explicitly **not ruled out** there (maintainer, 2026-09-03), and the chosen
   trial shape ends the open-ended 5/day for everyone at expiry, so the page was
   simultaneously banning and recommending the same move. What is true is that
   this is the revoke category rather than a neutral change, and that at roughly
   two users it is a conversation rather than a policy problem — the trade is
   taken with its name on it, not disallowed.
2. **A lapsed trial changes the voice on the phone — and *silences the audio
   paths* for a Smart Home user.** Which paths, exactly, is the whole point: the
   documented `text` automation keeps speaking through Home Assistant's own TTS,
   and only ClothesCast-provided `audio`/`video` and the spoken half of a Cast
   target go quiet. An earlier heading here said the briefing stopped *entirely*,
   which is the blanket claim the detail below retracts (Codex, 2026-09-03) — and
   a heading is what gets scanned. The second half is not what an earlier draft
   of this bullet said, and it is the sharpest consequence of the whole trial shape
   (Codex, 2026-09-03). Verified in the code: `DeliveryGates` records that **Gemini
   is the only producer of routable PCM** — "Device TTS does its own synth at
   playback time and exposes no buffer" — and `FetchAndNotifyWorker` accordingly
   drops the MQTT audio publish when no Gemini buffer exists, and hands Cast a
   `silentWavStub` — which a display still loads image-only, and only an
   audio-only speaker refuses (`SkippedNoAudio`). So:
   - **On the phone**, the trial ends by changing the voice. Platform TTS still
     speaks, and costs nothing to run.
   - **On a speaker it depends on the modality**, and an earlier draft of this
     bullet got that wrong by claiming a blanket silence (Codex, 2026-09-03).
     `docs/ROADMAP.md` has the table: `text`, `image` and `has_events` need **no
     Gemini** and are rendered on-device; only `audio` and `video` need the PCM
     buffer. So:
     - **An MQTT automation that speaks the `text` topic keeps working** — and
       that is the *canonical* setup `docs/smart-home.md` describes, where "Home
       Assistant is just the dumb plumbing that reads a string and speaks it."
       Nothing is lost there but the Gemini voice, and Home Assistant's own TTS
       replaces it.
     - **What does go silent** is anything consuming ClothesCast's own buffers:
       the `audio` and `video` topics, and the spoken half of a direct Cast
       target. **A Cast *display* does not stop, it goes image-only** (Codex,
       2026-09-03): `FetchAndNotifyWorker.castDestination` feeds
       `CastInsightController.silentWavStub` when there is no Gemini buffer and
       the receiver still shows the outfit PNG. Only an audio-only speaker has
       nothing to play, and there the controller skips the load
       (`SkippedNoAudio`). So the loss on a display is the voice; the endpoint
       keeps working.

   Two things follow. **The copy must not promise "just a different voice"
   either** — for an `audio`/`video` or Cast consumer that is false, and those
   users lose function rather than quality. But the conversion pressure is
   **narrower than the previous draft claimed**: the documented, recommended
   Smart Home path survives trial expiry intact, so this is not the product's
   strongest lever on its most likely buyers — it bites only the subset routing
   ClothesCast's rendered audio. Worth knowing in that scoped form rather than
   the dramatic one. If the remaining loss is judged too harsh, the fix is a
   routable non-Gemini fallback, which is real work and does not exist today. Say
   plainly, in the app, which case a given user is in.
3. **Trial resets are the cheapest abuse, and no cap size fixes them.** Clearing
   app data rotates the anonymous Firebase uid, which starts a fresh trial. That
   is a bigger hole under a trial than under a permanent free tier, because the
   free syntheses are now supposed to *end*. Closing it needs a stable
   identifier — see the privacy question in the pricing section, which is a
   maintainer decision rather than an implementation detail.

### The shape, stated plainly

**5/day during the trial; pay to keep the 5/day** (maintainer, 2026-09-03). So
the paid tier is not *more* quota — it is the **same** quota, continued.

**Except that "the same" needs the metering unit to match, and today it doesn't**
(Codex, 2026-09-03). The trial/free allowance is keyed on the **anonymous uid**,
which is per install; `docs/ROADMAP.md` meters the paid bucket on the **purchase
token**, which is per subscription. For a one-device user those coincide. For
someone with a phone and a tablet they do not: **two trial installs get 5+5 = 10
a day, and after subscribing they share 5** — so paying would *reduce* their
allowance, on a pitch that promises continuation. That is a refund and a
one-star review, and it is the same failure the pitch line has already been
corrected for twice.

Two ways out, and this is a decision rather than a detail:

- **Define both on the same unit.** Per-install for both is simplest and matches
  the promise, at the cost of the multi-device exposure in the table below.
  Per-subscription for both needs an identity during the *trial*, which the
  privacy question above says is exactly what this app does not want.
- **Or disclose the consolidation** — "one subscription, 5 a day across your
  devices" — which is honest and normal, but is no longer "keep what you had"
  for the multi-device user, so the pitch has to stop saying that to them. That is a
much simpler product than the one the rest of this page was pricing, and it
changes the cost side out of recognition:

- **Worst case on the paid tier is ~$0.45/month**, not ~$4.50. 5/day is 150
  syntheses at ~$0.003 each. Two conditions on that figure, both established
  below: it needs **per-purchase-token metering** (per-device has no ceiling
  without an aggregate bound), and it describes **normal operation** — a
  Firestore outage takes `reserveDailySlot` fail-open and constrains nothing.
- **So recurring per-subscriber Gemini spend stops constraining the price** —
  that clause only, not cost in general (Codex, 2026-09-03). The asymmetry
  argument above — one capped user eating nine subscribers' margin — was built on
  a 50/day paid cap and **does not survive this shape.** At 5/day the recurring
  arithmetic is bounded: a $1/month price nets ~$0.85 against a ~$0.45 ceiling.
  Trial acquisition and the fixed Open-Meteo floor sit outside that bound, and at
  low conversion or low volume either can still decide the price — the next
  bullet and the license section below.
- **But that does not make $1 safe, because it counts no trial** (Codex,
  2026-09-03). A max-use install on the 30-day trial proposed below costs ~$0.45
  before earning anything, and its first max-use paid month costs ~$0.45 again —
  already past the ~$0.85 that month receives, *at 100% conversion*. Every
  install that never converts is that trial cost with no receipt against it. So
  what the 5/day cap establishes is that the **recurring** relationship is
  bounded and positive; whether a given price clears the trial depends on trial
  length, conversion rate, how often a trial can be restarted, and how long a
  subscriber stays — none of which this page has numbers for. A shorter trial, or
  a trial metered below 5/day, moves it as much as the price does.
- **Which makes the *recurring* half a market question rather than a cost
  question** — that half only, since trial acquisition and the fixed Open-Meteo
  license are still cost questions with no numbers in them. What
  will someone pay to keep a spoken briefing in a natural voice? Nobody here
  knows, and the recurring arithmetic no longer answers it. The **safe range
  widens downward** — $1/month is no longer underwater against a heavy
  subscriber the way it was — but the bullet above is the limit of that: it is
  not shown to clear the trial, and nothing here recommends the bottom of the
  range, and the case for $2.99 stands on
  volume. What the shape buys is freedom to choose on market grounds, which is
  the conversation the pricing section defers to.

**Metering unit: per device is acceptable if the cap is low** (maintainer,
2026-09-03), and at 5/day it nearly is. The exposure is *cap × devices linked*:

| Metering | Cost / month at 5/day |
|---|---|
| Per purchase token | ~$0.45 — **a true ceiling** |
| Per device × 3 | ~$1.35 — nominal only |
| Per device × 5 | ~$2.25 — nominal only |

**The per-device rows are not ceilings, and calling them "worst case" was wrong**
(Codex, 2026-09-03). Per-device quota keys on the **anonymous Firebase uid, which
rotates on reinstall** — so a subscriber can spend a device's five, clear app
data, relink the same purchase under a fresh uid, and get a fresh bucket. The
five-install cap does not stop it: eviction bounds *current membership*, not
cumulative spend, and the evicted uid's usage is not carried forward. So one
replacement past five installs already exceeds the $2.25 row, and nothing in the
design bounds the next one. **Per-device is unbounded per subscriber unless it
gains either a durable device identity or an aggregate per-purchase ceiling.**
Both stay live options (maintainer, 2026-09-03) — the free-cap section sets out
what a durable identifier would cost (a Data Safety answer, and an identifier
that is not an ad ID), and neither it nor `docs/PRIVACY.md` forbids one.

**That changes the trade rather than settling it.** Per-purchase-token metering
is bounded by construction at ~$0.45; per-device is a simplification whose cost
is *an extra mechanism to make it bounded at all*, not the ~$1.35–2.25 the table
used to imply. `docs/ROADMAP.md` recommends per-purchase-token anyway and it
costs nothing extra to build, which is now a stronger argument than it was. The
maintainer's *"acceptable if the cap is low"* still holds for the honest per-device
figures — it just needs the ceiling, since a low cap alone does not produce one.

**The 50/day figure elsewhere on this page is now historical**, and *`docs/ROADMAP.md`
is corrected to match rather than left contradicting it* (Codex, 2026-09-03, twice) — it
was still actively prescribing 50/day as the mechanical plan, so an implementer
following it could have shipped the very number this direction replaces. The
first pass caught only the paid-cap paragraph and left the figure alive in three
downstream places — the plan-change reset, the per-device multiplication, and the
worst-case cost table, which was an order of magnitude out at ~$4.50 — plus an
open question still calling an unchanged 5/day "a genuine upsell", which under
this shape it is not: the cap does not move, so the pitch is continuation. Both that
cap and the unentitled branch (which read `DAILY_LIMIT` (5) with no expiry, leaving
a non-subscriber on 5/day forever) now name the trial direction and say what is
still unspecified: **server-verifiable trial state** — when it began, when it ends,
what the post-trial quota is. The trial start can't be a client claim, for the same
reason the cap can't: the anonymous uid rotates on reinstall.

---

## BYOK stays free, forever, and it is an asset

Anyone who pastes their own Gemini key bypasses the proxy and pays their own
bill. `docs/ROADMAP.md` asks what to do with them; the answer is **nothing, and
never show them a quota upsell**.

**The implementation consequence is not a presence check, and getting that wrong
would sell a subscription that cannot work.** When a stored key stops decrypting —
a Keystore rotation, a device transfer — `SecureKeyStore` removes the ciphertext
and sets `GEMINI_NEEDS_REENTRY`, so `read` throws `InvalidApiKeyException` rather
than `MissingApiKeyException`. `AppCheckGeminiCallPlanner.readOwnKey` catches only
the latter, so the invalid state deliberately does *not* fall through to the shared
proxy — it keeps the BYOK privacy boundary until the user re-enters or clears the
key. Meanwhile `geminiKeyConfiguredFlow` reads the ciphertext's presence, and the
ciphertext is gone, so a presence check says "no BYOK" and offers the upsell to
exactly the user whose purchased quota the planner will refuse to use.

So the gate is **configured OR needs-reentry**, and the needs-reentry state wants
its own copy — a prompt to re-enter or clear the key, not a subscription offer.

They are not lost revenue. Someone willing to create a Google AI Studio key and
paste it into a settings field was never the $2.99 buyer; they are the user who
would have written a one-star review about being forced to pay for something they
could self-host. Keeping BYOK converts that person into an advocate.

It is also **a marketing asset**, and an unusual one in this category: *this app
will happily let you cut it out of the loop.* That is a credible privacy and
control claim, it costs nothing, and it pairs with the existing `PRIVACY.md`
posture rather than fighting it.

---

## What must never be paywalled

- **The free voice.** Platform TTS costs the developer nothing to run, so it stays
  free and unmetered. The paid tier sells *continued access* to the Gemini voice,
  never *a* voice — and under the maintainer's trial shape not *more* of it
  either, since the cap is the same 5/day before and after (see "What is being
  sold"). **Note the careful wording: "no developer-funded cost", not
  "on-device".** `pickBestVoice` ranks by `Voice.quality` and uses non-network
  only as a tie-break, so where the installed engine exposes a higher-quality
  *network* voice it wins — and a user can pin one explicitly. Speech text can
  therefore leave the device on the free path — and **the guarantee has already
  been made**, so this is a live disclosure problem rather than a constraint on
  future copy (Codex, 2026-09-03, P1). `PRIVACY.md` tells users to "switch the
  voice engine to Device in Settings → Voice **to keep all spoken text
  on-device**", while `pickBestVoice` (`AndroidTtsEngine.kt:79`) sorts
  `compareBy { it.quality }.thenBy { !it.isNetworkConnectionRequired }` — quality
  first, offline only as a tie-break — so a higher-quality network voice wins and
  insight prose, which is calendar- and location-derived, goes to the platform TTS
  provider. Two fixes, and **it is the maintainer's call which**: make
  `pickBestVoice` prefer offline voices when the engine is Device, or correct the
  disclosure to say what it actually does. Tracked in `TODO.md`; it wants doing
  whether or not anything here is monetized, and it is not a monetization
  decision.
- **The insight itself.** The clothes rules, the comparative summary, the prose
  assembly, the widgets and the notification — the deterministic rendering, all of
  it on-device — stay free, because none of it costs anything per user and it is
  the reason to install. **The *forecast* is a network fetch, not part of that
  pipeline** (Codex, 2026-09-03): `PRIVACY.md` documents approximate location and
  search text going to Open-Meteo, and the Google forecaster can send coordinates
  and a BYOK key. Calling the whole thing on-device repeats exactly the false
  privacy premise the TTS bullet above warns against.

  **And it stops being free the moment there is a paid tier** (Codex,
  2026-09-03; verified against open-meteo.com/en/pricing). The keyless API this
  app uses today is **for non-commercial use only** — the pricing page's own
  comparison marks "Commercial use ❌" against the free tier, alongside 10,000
  calls/day and 300,000/month with no uptime guarantee. Shipping a subscription
  makes the use commercial, so it needs one of:
  - **A paid Open-Meteo plan.** Standard grants the commercial license and a
    dedicated endpoint (`customer-api.open-meteo.com`, same syntax plus an
    `apikey` parameter), 1M calls/month, a 99.9% uptime target, and **fixed
    monthly pricing with no per-call overage** — their words. **The figure itself
    is the one number this page cannot state** (Codex, 2026-09-03, asking for it):
    the plan prices render through a Stripe pricing table, which neither a `curl`
    nor a headless browser from this sandbox could execute. It is a two-minute
    look at open-meteo.com/en/pricing and it is **owed before any break-even is
    claimed**, because a fixed monthly cost is the wrong shape for everything else
    here — it exists in full at one subscriber.

    **And the key cannot live in the app** (Codex, 2026-09-03). `OpenMeteoClient`
    calls the forecast endpoint straight from the device, so "same syntax plus an
    `apikey`" would ship the shared paid credential inside the APK and send it on
    every request: anyone can extract it, replay it, and burn the 1M-call
    allowance, at which point forecasts fail for everyone who paid. Taking this
    branch therefore means routing forecasts through an authenticated proxy of
    ours — which is a second always-on service with its own dollar cost, added
    latency on the forecast path, an outage that takes every user's forecast with
    it, and a privacy boundary the app does not have today, since the proxy would
    see the coordinates the device currently sends direct.
  - **Self-hosting** the server, which is open source (AGPLv3, data CC BY 4.0).
    No license fee — but **"a few dollars a month" was wrong, and the driver is
    not request volume** (Codex, 2026-09-03). A self-hosted instance continuously
    downloads, processes and stores the weather-model datasets it serves, and
    `ForecastModelDefaults` selects across ECMWF, ICON, UKMO, ARPEGE, JMA, GEM and
    AIFS by region — so the bill is fixed storage, bandwidth and ingest compute
    for that set, times whatever redundancy the reliability claim below assumes,
    and it is owed a real figure before it is compared with the paid plan. The
    cheaper alternative is not a smaller instance: it is dropping models or
    regions, which is a product change (the divergence check and the second-week
    coverage are what the set exists for), not a saving. Its real cost is **reliability**: it becomes a dependency this
    project owns, and when it is down every forecast fails — where today an
    Open-Meteo outage is somebody else's on-call. For an app whose whole output
    is a daily briefing, that is a worse trade than the price difference suggests.

  Attribution is required under the license either way. This is the one cost the
  page had been treating as structurally zero.

  **Two consequences for the pricing section**, both of which cut against the low
  end: at $1/month the fixed floor is divided over very few subscribers, so the
  break-even subscriber count is the arithmetic that actually matters and it is
  not computable until the figure above is filled in — and until it is, no price
  on this page is established as viable, $2.99 included.
- **A cap an existing install already has — the one item here that is NOT an
  absolute** (Codex, 2026-09-03), listed because it is where a reader expects to
  find it, not because it belongs to the floor. Everything else in this section
  is never sold; this one is a judgment call. *Lowering* a cap for someone who
  has been using five a day is revoking something they have, which is what earns
  review bombs — but setting a lower free cap for *new* installs while existing
  ones keep theirs is ordinary, and lowering an existing one is **not ruled out**
  (maintainer, 2026-09-03). The chosen trial shape ends the current open-ended
  5/day for everyone at expiry, so this was never categorical. At two users,
  whether an install is "existing" is a judgment call rather than a mechanism;
  see "Migrating existing free users".
- **The Smart Home *gate***, per the top of this page — and it is the gate that is
  never sold, not every modality behind it (Codex, 2026-09-03). The local MQTT
  bridge and the `text` / `image` / `has_events` topics need no Gemini and stay
  rendered on-device whatever the entitlement says, which is the documented,
  recommended setup. The `audio` and `video` topics and the spoken half of a Cast
  target consume ClothesCast's own PCM buffer, and that is Gemini quota — so those
  stop at trial expiry like any other Gemini route (a Cast *display* keeps
  working image-only; only an audio-only speaker stops entirely). Selling quota is not the same
  as gating the feature, but for those users the difference does not show, so the
  in-app copy has to say which case they are in rather than promising an
  unqualified "Smart Home is free".

---

## Migrating existing free users

**Don't turn this into a policy** (maintainer, 2026-09-03). An earlier draft of
this section made "never lower the cap" an absolute rule and then spent a page on
what the server can and cannot prove about who an existing user is. At roughly two
users, that is painting into a corner: it commits the app to a promise for a
population small enough to talk to.

**Decide it case by case, on the evidence.** If the cap changes and someone says
they had more before, at this scale you can just look — what they have configured,
when they installed, whether the server has quota history for their uid. Then use
judgment. No mechanism, no rule, no promise the code has to keep later.

Two facts are worth keeping, because they stop anyone building a mechanism on a
false premise:

- **A client-side entitlement cannot work here at all.** The cap is enforced by
  the Cloud Function against an anonymous Firebase uid that rotates on reinstall,
  and `allowBackup="false"` means nothing survives a device transfer. A
  client-side claim on a server-enforced quota is a request, not a fact — so the
  usual `firstInstallTime` stamp is not merely lossy here, it is inert. **That
  premise expires in 2027**, when the platform requires backup and restore
  (maintainer, 2026-09-03) — and independently of the mandate, **we don't hold a
  user's data captive**, so nothing here may be designed to rely on their data
  being unable to follow them. It does not rescue a client-side entitlement,
  which fails on the server-enforcement argument regardless, but it does mean
  `allowBackup="false"` cannot be assumed permanent when anything else is built
  on it.
- **The server's own signal is real but partial.** A uid with quota history
  predating a cutoff is provably an existing user, and no sibling app has anything
  equivalent. But it is **not an install census**: the quota document is written
  inside `reserveDailySlot`, which runs only on a *shared-proxy synthesis*, so an
  install that only ever used platform TTS or BYOK has no server record at all.
  Useful as evidence in a judgment call; not a basis for an automated rule.

**Lowering the cap is still the risky direction** — it is the one change that takes
something away from someone who has it — so treat it as a decision to make
deliberately rather than a knob to turn. That is a smaller claim than "never", and
it does not commit anyone to anything.

- **Raising it needs no identity and cannot break anyone — but it is not free**
  (Codex, 2026-09-03), and calling it free contradicts this page's central premise
  that every shared-proxy synthesis bills a real key. At ~$0.003 per synthesis,
  **+5/day is up to ~$0.45 per active free install-month**, worst case, and the
  worst case is what a free tier has to be budgeted against because there is no
  revenue on the other side of it. It also widens the quota and rate-limit exposure
  by the same factor. So if measurement says 5/day is too tight, raising it for
  everyone is the right move and the cheapest way to buy a free tier that actually
  demonstrates the product — with the spend named, and done before pricing
  anything.
- **Say it plainly, and don't say "nobody lost anything"** (Codex, 2026-09-03).
  That wording was written for a paywall over *unbuilt* features and is false under
  the trial direction: an existing user's 5/day becomes time-limited unless they
  subscribe, which this page's own migration section calls the revoke move. So the
  notice names the loss — what becomes paid, what stays free, **when the existing
  allowance ends**, and what happens then — **stated by modality, since the loss is
  not uniform** (Codex, 2026-09-03): platform TTS on the phone; on a Smart Home
  setup, the `text` automation keeps speaking through Home Assistant's own TTS and
  only ClothesCast-provided `audio`/`video` and the spoken half of a Cast target go
  quiet — a Cast display keeps showing the outfit image, silently. Telling every Smart Home user to expect silence overstates it, and is the
  same over-claim the trial section already retracted. Reserve "you keep everything you
  had" for a migration that actually preserves the allowance — which, with ~2
  users, is a decision that can simply be made in their favor if it is worth it.

---

## Marketing

### Do not compete as a weather app

The weather category is one of the most crowded on Play, dominated by apps with
budgets, and ClothesCast loses that fight on radar maps and hourly grids it does
not have and should not build.

It is not a weather app. **It is a one-sentence answer to "what do I wear".** The
things that make it different are the things nobody else does:

- **The comparison.** "4°C warmer than yesterday, leave the sweater at home" is a
  different product from "9°C". Every other app tells you the number; this one
  tells you what changed, which is what a person actually wants to know.
- **Feels-like, always.** It answers what stepping outside is like, not what a
  thermometer reads.
- **It comes to you.** A scheduled briefing at a time you set — nothing to open.
- **It can say it out loud, on a speaker.** The smart-home announcement is the
  single most distinctive thing here and the hardest to copy.

The positioning line follows from that, and it is a category of one: *the only
weather app you never open.*

### Listing

Play's title field allows 30 characters and is weighted for search. "ClothesCast"
alone is a coined word with no search volume; `ClothesCast: what to wear` (25)
spends the remainder on the query people actually type. Count the separator and
its spaces before proposing one — the first draft of this line offered a
32-character title under a 30-character limit. The short description carries
search weight too: *what to wear*, *outfit*, *daily weather briefing*,
*feels like*.

Screenshots should lead with the notification and the spoken briefing, not a
settings screen — the product is a sentence arriving at 7am, and that is what has
to be on the first card.

### Where the audience is

The smart-home communities are the ones to reach: Home Assistant, MQTT and
Google/Nest speaker forums. An outfit briefing announced on a kitchen speaker is
a genuinely novel automation, and that audience shares automations, buys
software, and does not need to be sold on why a weather app would talk to a
broker. Cycling and running communities are the second tier — feels-like plus
rain probability is exactly the pre-ride question.

---

## What this shares with the sibling apps

**ClothesCast should ship billing first in this family**, and this is the reason
to say so out loud: it is the only sibling with a real per-user cost, its
**subscription** machinery is designed end to end — the trial's start, expiry,
post-trial quota and enforceable identity are not, per the top of this page and
`docs/ROADMAP.md`, so this is a head start rather than implementation-readiness
(Codex, 2026-09-03) — and everything learned here — Play
Console setup, entitlement caching, the "can't determine entitlement" degradation
rule, testing tracks — transfers to the others for free.

Do not build a shared billing library on the way. Build it once, here, end to
end; extract only if a second app needs it and the shape has settled — the order
`androidlog` was extracted in, after the copies existed rather than ahead of them.

---

## Open questions, and what each way out costs

Nothing here needs an answer today. Each question is written as the choice plus
what each branch costs — a map, not a form. Where `docs/ROADMAP.md`'s open
questions overlap, this defers to them; these are the ones this page moves.

**1. The trial's length, and when the clock starts.**
- *Short (7 days)*: converts on impulse, and a user who happens not to check the
  weather that week never sees the good voice at all.
- *Longer (30 days)*: the voice becomes habit before it is taken away, which is
  the strongest version of this pitch, and it is 30 days of shared-proxy Gemini
  cost per install that may never pay.
- *Starts at install*: reads as the simple option and is not one (Codex,
  2026-09-03). For an install that has never invoked shared Gemini the server has
  observed *nothing* — no anonymous uid, no quota document — so install-start
  needs a **trusted install signal that does not exist today**, on top of the same
  reinstall-resistant identity the other branch needs. `docs/ROADMAP.md` forbids
  taking the start from the client for the reason that decides both branches: the
  anonymous uid rotates on reinstall, so a client-asserted start is resettable.
  Its real cost is therefore *more* server work than first-use, plus the same
  durable-identity privacy decision, and it still burns trial days on someone who
  has not set the app up.
- *Starts at first shared-Gemini use*: measures the thing the trial is for, and
  needs a stored start marker that survives reinstall — which nothing today has.
  It is the cheaper of the two only because that moment is one the server already
  sees; the identity problem underneath is the same, and it is question 2's
  privacy question, not a separate one.

**2. Whether new installs get a different free cap from existing ones.** A real
option, but narrower than it first looked: server-side quota history proves an
existing install **only for someone who has already used shared Gemini**, since
that is when the quota document is written. A long-time user reaching for the
shared voice for the first time after a cutoff reads as new.
- *Same cap for everyone*: nothing to explain, nothing to get wrong.
- *Grandfather the identifiable ones*: keeps faith with the users it can see, and
  silently fails the ones it can't — which is worse than not trying.
- Either way, a cap tight enough to convert is also a cap tight enough to look
  broken. That is question 1 from the other direction.

**3. The price anchor.** $2.99/month with an annual around $19.99–24.99 is what
this page argues for; it is not the only defensible number.
- *$0.99–1.49*: covers the ~$0.45/mo worst-case cost several times over and reads
  as a rounding error to the buyer, so the sale has to come from volume.
- *$2.99*: the recommendation, on the argument that a monthly under $2 is not
  meaningfully easier to say yes to than $2.99 while being worth a third as much.
- *$4.99+*: needs the app to feel like a daily habit rather than a nice voice.
- The trial shape removed the *per-subscriber recurring* cost argument for the
  higher end — at 5/day that ceiling is ~$0.45/mo, not the ~$4.50 an earlier
  50/day draft assumed. **It did not make this a pure willingness-to-pay
  question** (Codex, 2026-09-03), and calling the range "safe" here contradicts
  the section above: a max-use 30-day trial plus its first paid month is ~$0.90
  against ~$0.85 received at $1, before any non-converter or trial restart. So
  the range is wide on *recurring* grounds and unproven at the bottom until trial
  length, conversion, restartability and retention are known — and the Open-Meteo
  commercial license above is a fixed floor underneath all of it.

**4. Whether the trial and the paid tier meter on the same unit.** They don't
today: the trial meters per install, the paid tier per purchase token. A
two-device user gets 10/day free and 5/day shared after paying, so paying
*reduces* their allowance.
- *Both per subscription*: the pitch survives, and a single-device user's free
  allowance is unchanged.
- *Both per device*: simplest, and **it needs one of the two bounding mechanisms
  above before those figures mean anything** (Codex, 2026-09-03). The ~$1.35/mo at
  three devices and ~$2.25 at five are nominal — the anonymous uid rotates on
  reinstall, so without either a durable device identity or an aggregate
  per-purchase ceiling, per-device spend per subscriber is unbounded, and picking
  this branch off the summary alone would take the simplification without the
  thing that makes it affordable. The maintainer named per-account first and then
  reopened per-device *"provided we think we're protecting ourselves
  sufficiently"* (2026-09-03); that protection is exactly this mechanism, and with
  it the bar at a ~$0.45/mo base is much easier than it was at 50/day.
- *Leave it mismatched*: not viable — see the trial section.

**5. Whether a Smart Home user gets a routable fallback at trial expiry.** Only
Gemini produces routable PCM, so today the `audio` and `video` topics stop and a
Cast target loses its voice — a display continues image-only, an audio-only
speaker stops — while the `text` path keeps working through Home Assistant's own
TTS.
- *Ship a fallback*: trial expiry becomes a quality drop everywhere, matching the
  rest of the app, and it is real work on the delivery path.
- *Leave it*: the one place expiry is a loss of *function*, for the users most
  likely to pay — which is either the strongest paywall in the app or the most
  resented, depending on how it reads on a device.

**6. What the post-trial free state is.** Platform TTS with the full text is what
falls out of the code today.
- *Keep that*: the app still works, just in the worse voice — which is exactly
  what "keep the good voice" implies.
- *Narrow it*: more pressure to convert, and it breaks the promise above.

**7. ~~Reconcile the Play cut~~ — settled, and no longer a question** (Codex,
2026-09-03). The subscription fee is 15% from day one, `docs/ROADMAP.md`'s
contradicting framing was corrected in the same commit as the section above, and
leaving this listed sends a reader to investigate a fact this page states. Kept
struck through rather than deleted so the numbering below does not shift under
anyone who has referred to it.

Before any of them: the listing work, which needs no decision and no code.
