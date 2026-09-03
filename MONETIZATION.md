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
viable. **The license half has landed — $29/month** (maintainer, 2026-09-03) —
and it is a fixed floor rather than a per-subscriber cost, so it converts into a
subscriber count: **the license alone** takes ~12 subscribers at $2.99 and ~35 at
$1, net of Play's fee, and ~14 and ~60 once per-subscriber Gemini spend is
counted — still a subtotal, since the paid plan also forces an unpriced proxy.
**Every one of those counts assumes the $29 Standard tier fits** (Codex,
2026-09-03), which is not established: 1M calls is a capacity limit and the
weighted workload has not been computed. If it does not fit, the floor is $99
**for the 1M–5M band** — Professional stops at 5M too, and past that neither
published tier applies — and none of these numbers survives either way. Trial acquisition is still unmeasured. The four inputs below are how it gets settled.

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
  **would likely mean editing `PRIVACY.md`**, which today rules out ad
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
  bullet and the license section below, where that floor is now a real number
  ($29/month, maintainer 2026-09-03) and therefore a real subscriber count.
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
  question** — that half only, since trial acquisition is still a cost question
  with no numbers in it, and the Open-Meteo license, now priced at $29/month, is
  a fixed floor that no per-subscriber argument reaches. What
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
that is not an ad ID), and neither it nor `PRIVACY.md` forbids one.

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
  - **A paid Open-Meteo plan — $29/month** (maintainer, 2026-09-03, read off
    open-meteo.com/en/pricing; the plan prices render through a Stripe table that
    no `curl` or headless browser from this sandbox could execute, so the figure
    was owed by hand). **API Standard** grants the commercial license and a
    dedicated endpoint (`customer-api.open-meteo.com`, same syntax plus an
    `apikey` parameter), 1M calls/month, a 99.9% uptime target, and fixed monthly
    pricing with no per-call overage — their words — covering Forecast plus Air
    Quality, Marine, Flood, Elevation and Geocoding. **API Professional is
    $99/month** for 5M calls and historical/climate data, which this app does not
    use.

    **Standard is the tier to price against on *features*, and that is not the
    same as it being the tier this app fits** (Codex, 2026-09-03). 1M calls/month
    is a **capacity** limit, consumed by every install rather than every
    subscriber — free ones included, since one commercial key serves the whole
    app. `OpenMeteoClient.fetchForecast` issues a primary *and* a multi-model
    request per forecast, so **a forecast is at least two HTTP requests**, before
    geocoding — but **two requests is not two billed calls, and the install
    estimate this paragraph used to give was wrong** (Codex, 2026-09-03).
    Open-Meteo accounts for usage by *weighted* calls, and both requests are
    larger than a request count suggests: the primary asks for 14 variables
    across 15 days (`past_days=1` plus `forecast_days=14`, 7 daily and 7
    hourly), while the side-band request is **not the same set and not smaller**
    (Codex, 2026-09-03) — `MultiModelConfidenceFetcher.requestModels` asks for
    **11 hourly variables plus 3 daily**, and expands them across up to five
    models. So one refresh may consume many times two calls. **No install
    ceiling is stated here, because deriving one from request counts produced a
    figure that flattered the cheap tier.** Four things are needed before $29 is
    established as the applicable floor:

    1. **Open-Meteo's own weighted-call definition**, which this sandbox has not
       read — everything else is unusable without it.
    2. **Refreshes per active install, plus retry behavior and cold-process
       misses** (Codex, 2026-09-03 and 2026-09-04), which an earlier two-input
       version of this recipe omitted and a later one counted the cache against
       without asking whether the process was alive to hold it. Refreshes are
       not user-initiated events that can be estimated from habit: the hourly
       widget tick repaints unconditionally and *refetches* whenever the cached
       snapshot has aged past `WIDGET_REFRESH_MAX_AGE` (`WidgetRefreshReceiver`),
       on top of the scheduled, app-open and manual paths.
       **`WIDGET_REFRESH_MAX_AGE` is 6 hours, not 1** (maintainer, 2026-09-03;
       `WidgetRefreshReceiver.kt` confirms `Duration.ofHours(6)`) — so the widget
       path is often around **four *successful* refreshes a day** — a rough figure and
       **not a cap** (Codex, 2026-09-03), because a separate `BOUNDARY` alarm
       enqueues a silent refresh at each configured window boundary whenever
       delivery does not already cover it (`WidgetRefreshReceiver`), on top of
       the stale checks; with uneven user-chosen boundaries the longer window can
       hold several six-hour refreshes of its own. So the widget path alone can
       exceed four before retries, app opens or manual refreshes are counted — which is not
       a cap on request *attempts* (Codex, 2026-09-03), and an earlier revision
       of this line said "fetches" as though it were. On a transient Open-Meteo
       failure `FetchAndNotifyWorker` returns `Result.retry()` — network errors,
       429s, 5xx, connect and socket timeouts all take that path — so
       `WorkManager` retries with backoff while the snapshot stays stale. So
       attempts are not bounded by the TTL — but **a later hourly tick replaces
       the pending retry rather than adding to it** (Codex, 2026-09-03, and an
       earlier revision of this line said "on top", which overcounted):
       `enqueueSilentRefresh` uses `UNIQUE_WORK_NAME_SILENT` with
       `ExistingWorkPolicy.REPLACE`, so the tick cancels work sitting in backoff
       and restarts it. **But that model covers the silent queue only, and
       scoping it to "one queue" was still too generous** (Codex, 2026-09-03):
       `FetchAndNotifyWorker` defines four deliberately distinct unique-work
       names — `UNIQUE_WORK_NAME` (alarm-driven delivery), `..._PLAY`,
       `..._SILENT` and `..._LOCATION_CACHE` — precisely so they cannot cancel
       each other, and REPLACE dedupes only *within* a queue. So an alarm
       delivery or a Play tap sitting in backoff is untouched by the hourly
       widget tick. **But "runs concurrently" was the overcorrection** (Codex,
       2026-09-04, and the third time this passage has swung past the claim it
       was fixing): distinct queues can overlap in WorkManager, and they still
       cannot each produce a concurrent Open-Meteo fetch, because they share one
       lazily-built `CachingWeatherRepository` whose `fetchForecast` holds a
       single mutex across the cache lookup *and* the delegate network call. So
       they **serialize**, and a success populates a one-hour location-keyed
       cache the next one hits — a second, tighter dedup layer than the widget's
       six-hour TTL, which this recipe had not counted at all. `..._LOCATION_CACHE`
       is a cache-only worker rather than a fourth forecast pipeline, too.

       **And that cache is process-local, which is the fourth correction and the
       one that matters most for sizing** (Codex, 2026-09-04). `entry` is a
       plain `private var` on a repository the Application builds `by lazy`, so
       the mutex and the cached bundle both die with the process — and the
       normal shape of this app's load is precisely a process that is *not*
       alive between firings: an alarm wakes a killed app at 07:00, serves one
       forecast, and is killed again. Two jobs an hour apart on a phone that
       reclaimed the app in between are two cold starts and two real requests,
       whatever the TTL says. (It is a **single-slot** cache besides — one
       `Entry`, not a map — so within a live process two locations evict each
       other as well.)

       The model that survives all four corrections: back-to-back successful
       jobs collapse into the one-hour cache **only while the process lives**,
       and then regardless of which queue they came from; a cold process is a
       guaranteed miss; **failed** attempts populate nothing, so each is a real
       request; and jobs proceed serially rather than in parallel *within* a
       process, with no serialization at all across one that has been restarted.
       Sizing that treats the cache as unconditional overstates its help on the
       alarm path, which is the path the 07:00 burst is made of; sizing that
       ignores the failures understates an outage. So **cold-process requests
       belong in input (2) alongside retries**, and the honest ceiling on what
       the cache saves is "repeat requests inside one live process", not
       "repeat requests inside an hour". Persisting the cache would change that
       and is the obvious lever, but it is a design change with its own
       staleness and storage questions, not a fact about today.

       **And there are two retry layers, not one** (Codex, 2026-09-03).
       `MultiModelConfidenceFetcher` retries *inside itself* and never reaches
       the worker's path: a transient I/O failure is retried up to
       `MAX_TRANSIENT_RETRIES` (2) and then swallowed, and an invalid or
       withdrawn `models=` id makes it prune and reissue, bounded by
       `MAX_FETCH_ATTEMPTS` (6) — **worst case seven requests, not six**
       (Codex, 2026-09-03): `attempts` increments *before* each call and the
       loop gives up only once a request lands beyond the constant, which the
       source comment says in as many words. Transient retries spend from the
       same budget. A withdrawn model id makes that extra weighted request
       recur on **every** refresh until the model list changes, which is the
       part worth sizing — it is a standing cost, not a one-off. So one side-band fetch can be several requests
       on its own — and because each `WorkManager` retry of the primary re-runs
       the whole refresh, it starts a **fresh** set of those attempts. The two
       layers multiply rather than add, which is the shape most likely to
       undercount weighted usage badly, and both belong in the sizing. The 6 is a considered guess rather
       than a measurement (maintainer, 2026-09-03): nobody knows how often users
       look at the widget, and the theory is that they don't need to, since
       someone who set their schedule correctly is already dressed. **A stronger
       version of that argument does not need the user behavior at all**: a
       forecast fetched at 07:00 already contains the 18:00 hours, so a refetch
       buys the forecast's *revision*, not new coverage — and within six hours,
       temperature and general conditions rarely revise enough to change what
       someone should wear.

       **Recorded lean, not a decision** (2026-09-03): the exception is
       fast-moving rain, where the three-hours-out probability genuinely does
       revise and a stale widget says dry under a darkening sky. So an
       **adaptive TTL** — 6 hours by default, shorter when the next few hours
       carry meaningful precipitation probability — is worth more than any
       universal value.

       **Its load is bounded but not estimated, and calling it negligible was
       unsupported** (Codex, 2026-09-03). An adaptive TTL sits somewhere between
       the 6-hour cadence and whatever the shortened one costs, and where it
       sits is set by the **fraction of days that trigger it** — which is
       unmeasured, climate-dependent, and in a wet region or a wet season could
       be most days, at which point it approaches universal shortening rather
       than the current cadence. So it is not a free upgrade, and an earlier
       revision of this line asserting it "barely moves" total load was making
       exactly the kind of unmeasured capacity claim the rest of this section
       refuses to make. The lean stands on the *product* argument — a stale
       widget saying dry under a darkening sky — with its cost left open.

       The maintainer considers 6 hours about right and tunable. Cadence stays
       an input because it is a *policy*: retries, and any change to the TTL,
       adaptive or not, move total load without any user behavior changing.
    3. Then an estimate of **active installs** — not subscribers, and **not the
       cumulative installed base** either (Codex, 2026-09-03; an earlier
       revision said "total installs", which contradicts input (2)'s own *per
       active install* and would overstate traffic enough to select $99
       wrongly). Only installs that open the app, run an enabled schedule, or
       carry a placed widget issue forecast requests at all; dormant ones cost
       nothing.
    4. And the plan's **short-window ceilings — per minute, hour and day —
       against the distribution of scheduled times** (Codex, 2026-09-03), which
       the first three inputs miss entirely by reasoning only about a monthly
       total. Those are not independent here: `FetchAndNotifyWorker` spreads
       alarm-triggered requests across a window of only **30 seconds**
       (`ALARM_FETCH_JITTER_MS`, verified), so installs firing at the same
       instant arrive as a synchronized burst rather than spread over the day —
       and each refresh is several weighted requests, not one.

       **But the burst is per time zone, not global** (Codex, 2026-09-03), and
       an earlier version of this input applied the whole active-install count
       to a single window, which overstates peak badly enough to select a tier —
       or a jitter change — that nothing needed. `Schedule` carries a `zoneId`
       and the default is 07:00 *local*, so installs group by **UTC firing
       instant**: the collision is real and full within a zone, and a globally
       spread base spreads across the day by itself. So the input is the
       distribution of schedule times **bucketed by UTC instant** — and each
       ceiling reads a different figure off those buckets (Codex, 2026-09-03,
       correcting the previous revision's "the largest bucket" as though one
       number served all three): every ceiling takes the **maximum sum over its own
       window**. For the **hourly** and **daily** ones that is every bucket
       falling inside it. For the **minute** ceiling it is *not* simply the
       largest bucket (Codex, 2026-09-03, catching the same overcorrection a
       second time): the 30-second jitter lets nearby buckets bleed into one
       rolling minute, and the widget, app-open, manual and retry traffic that
       lands there regardless of anyone's schedule counts against every window,
       not only the wide ones. Reducing any of them to the peak bucket
       understates it and picks a tier — or a jitter — that still 429s. Which also says where the risk is: a user base concentrated in
       one country is the case where peak and total are the same problem.

       With that correction, the point stands — a workload whose monthly total
       fits Standard comfortably can still take 429s at 07:00 in its largest
       zone, which the user experiences as the forecast failing at exactly the
       moment the app exists for. Sizing that ignores the shape of the traffic
       can pick the right tier and still be wrong. Widening the jitter belongs in
       the same conversation but is not cheap — it is bounded by the delivery
       alignment, and both ways of widening it cost something visible (see the
       burst-overshoot paragraph below).

    If the *monthly* load exceeds Standard, the floor is $99 **for the 1M–5M band
    only** (Codex, 2026-09-03) — despite none of Professional's extra datasets
    being used. An earlier revision made $99 the answer to *any* overshoot,
    which the plan description above contradicts: Professional is 5M calls, so a
    workload past that fits neither published tier and its price and terms are
    simply unknown from here. With no overage on either plan, the alternative to
    upgrading is failing forecasts, and above 5M the alternative to a quote
    nobody has is self-hosting or reducing the request shape.

    **A short-window failure is a different failure and does not map onto that
    band at all** (Codex, 2026-09-03, on the input added above). A workload
    comfortably under 1M a month can still breach the minute or hour ceiling in
    its largest bucket, and *nothing about being under 1M* says which tier fixes
    that: whether Professional's short-window ceilings are higher — or published
    at all — is unread here alongside the weighted-call definition. So the two
    failure modes route differently. **Monthly overshoot** is a tier question,
    answered by the band above. **Burst overshoot** is a *shape* question, and
    its first answer is not money — but it is also **not a free knob**, which an
    earlier version of this paragraph implied by offering "widen
    `ALARM_FETCH_JITTER_MS`" at the cost of "a little punctuality" (Codex,
    2026-09-04). The jitter is bounded from above by a second constant.
    `FetchAndNotifyWorker` realigns the notification and TTS to
    `DELIVERY_ALIGN_AFTER_ALARM_MS` (60 s), so every device in a household
    delivers at the same wall-clock moment, and the 30 s jitter is held
    *strictly below* it precisely so the worst roll plus a slow fetch still
    lands before the barrier — the source says so in as many words, including
    that going wider would let some rolls overshoot it. So there are two moves
    and both cost something visible: widen the jitter alone and late rolls miss
    the barrier, delivering at whatever time their fetch happens to finish,
    which loses the synchronized delivery the constant exists to provide; widen
    both and *every* briefing arrives later, every day, for a ceiling problem
    most users never trigger. Neither is "a little punctuality". Only once that
    trade is priced does it become a tier question, and then it needs
    Professional's short-window limits — which have to be read before $29 can be
    ruled out or $99 ruled in on burst grounds.

    **What the number does to the arithmetic.** $29/month is a floor that exists
    in full at one subscriber, so it converts directly into a subscriber count
    before anything else is paid for. Net of Play's 15%, **$2.99/month clears it
    at ~12 paying subscribers and $1/month at ~35** — and that is the license
    alone. Counting the per-subscriber Gemini spend too, **license plus Gemini**
    is cleared at roughly **14 subscribers at $2.99 and 60 at $1** (73 at $1
    against the $0.45 max-use ceiling). **All of these are Standard-tier
    scenarios** (Codex, 2026-09-03) — the paragraph above says $29 is not
    established as the applicable floor until the weighted workload, refresh
    cadence, retries and install volume are known, and at $99 every count here
    rises 3.41x: ~46 at $2.99 and ~203 at $1.

    **Those are a subtotal, not the tier's break-even** (Codex, 2026-09-03), and
    calling them break-even was wrong for a reason this same bullet supplies four
    paragraphs down: taking the paid plan means the key cannot ship in the app, so
    forecasts have to route through an **authenticated proxy of ours** — a term
    this page has never priced, **and its dollar cost is unknown rather than
    ~$0** (Codex, 2026-09-03, correcting an earlier line here that claimed the
    latter). The repo does already deploy an authenticated HTTP function in the
    same Firebase codebase, and `docs/ROADMAP.md` does place two more inside the
    free tier — but that sizing is **invocation counts for low-volume billing
    functions**, and it does not transfer to proxying full forecast responses.
    The term that would decide it is **egress**, for a payload that is large and
    generated by **every active install**, free ones included — the same
    population the capacity paragraph above says is unmeasured. So a free tier
    sized against a handful of billing calls proves nothing about this.

    What can be said without the number: the cost is at least **engineering** —
    App Check, a second surface to maintain, added latency, and a new failure
    mode on the forecast path — and the dollar term is unpriced in both
    directions. It is not grounds for claiming the threshold moves, and it is
    not grounds for claiming it doesn't. Every trial that never converts is on top of these figures
    as well. Two things follow. The recurring-spend argument above is unaffected
    **by the license**, which is genuinely fixed — it was always scoped to
    per-subscriber cost, and $29 does not move with usage. **The proxy is not
    fixed and must not be folded into that conclusion** (Codex, 2026-09-03): its
    egress is generated by every active install, free ones included, so it is a
    *variable* cost that grows with installs and can move margins while the
    subscriber count stands still. The fixed-cost reasoning here covers the
    license only. But **the "safe range widens downward" conclusion does not reach $1**
    at any plausible early volume — at ten subscribers $1/month is $8.50 against
    $29 — so the case for the bottom of the range now needs a subscriber count
    nobody has, on top of the trial numbers it already needed. $2.99 needs the
    same count argument, just a shallower one.

    **This is not a reason to price higher; it is a reason the first question is
    volume.** A fixed floor is cleared by subscribers, not by price: $9.99 clears
    it at four, and a price nobody pays clears it at none. What the figure
    settles is that **any** paid tier here starts $29/month underwater, so it is
    a decision about whether there is an audience at all — which is what the
    discovery argument elsewhere on this page already says is the binding
    constraint.

    **And the key cannot live in the app** (Codex, 2026-09-03). `OpenMeteoClient`
    calls the forecast endpoint straight from the device, so "same syntax plus an
    `apikey`" would ship the shared paid credential inside the APK and send it on
    every request: anyone can extract it, replay it, and burn the 1M-call
    allowance, at which point forecasts fail for everyone who paid. Taking this
    branch therefore means routing forecasts through an authenticated proxy of
    ours — a second always-on service whose dollar cost is unpriced (its egress
    scales with active installs, not subscribers) and whose other costs are
    certain: added latency on the forecast path, an outage that takes every
    user's forecast with it, and a privacy boundary the app does not have today, since the proxy would
    see the coordinates the device currently sends direct.

    **Authentication is not a quota, and the proxy needs one** (Codex,
    2026-09-03). App Check and anonymous auth establish *who* is calling, not
    *how much* — so one genuine install hammering manual refresh can spend the
    shared weighted-call allowance until forecasts fail for everybody, which is
    precisely the quota-exhaustion failure that moving the key out of the APK
    exists to prevent. The repo already has the pattern: the TTS function
    reserves a daily slot per caller (`reserveDailySlot`). The forecast proxy
    needs the equivalent, and a **shared response cache** is the better half of
    the answer here than a pure rate limit — many installs in one area want the
    same forecast, so caching cuts the upstream bill and the abuse surface at
    once.

    **A response cache alone does not survive the burst it is being counted
    against, though** (Codex, 2026-09-04). Input (4) above is a crowd of installs
    firing inside one 30-second jitter window in a single zone; a plain
    read-through cache is *empty or just-expired* for that key at exactly that
    instant, so every one of them misses, and every one forwards upstream before
    the first response is stored. The cache then fills — after the spike it was
    supposed to absorb has already been spent against a per-minute allowance. So
    counting cache sharing as burst capacity, or as abuse mitigation, requires
    **per-key single-flight**: the first miss fetches, the rest await that same
    in-flight result. **In memory that is per-instance**, and the deployment
    this would sit on sets no instance constraint, so a burst that scales
    horizontally still forwards one miss per instance. **Coalescing across
    instances is unsolved here** — it is a design and a cost of its own, and it
    belongs with the rest of the proxy's unsized pricing in `docs/ROADMAP.md`
    open question 5 rather than being named as a solution this page has not
    priced. Single-flight also has its own contract to specify rather than
    assume —
    what the waiters get when the leader fails (an error each, or one shared
    failure), how long they wait before giving up, and whether a failure is
    negative-cached at all, since caching an upstream 429 turns one refusal into
    a quiet outage for a whole cell. Without it, the honest claim is that the
    cache cuts the *monthly* bill and does nothing for the minute ceiling.

    **That cache is itself a privacy decision, not just a cost control**
    (Codex, 2026-09-03): it *stores* a location-keyed forecast on infrastructure
    this project operates, where the proxy above only forwarded coordinates in
    flight. So it cannot be recommended without settling what it retains — the
    key's granularity (grid-snapped, per the paragraph below, is the privacy
    answer as much as the abuse one), how long an entry lives and what evicts
    it, and whether any caller identifier is stored beside the entry, which is
    the difference between a cache of *places* and a log of *who asked about
    where*. The default that needs no argument is: coarse key (plus the caller's
    zone, which also has to travel upstream — see the boundary correction below),
    short TTL, no identifier stored, and the privacy policy says so before it
    ships.

    **But neither half survives a determined caller as specified** (Codex,
    2026-09-03), and reusing the TTS pattern unexamined was the mistake. That
    quota keys on the **anonymous Firebase uid**, which this page establishes
    elsewhere *rotates* on a reinstall or an app-data clear — so the same
    physical install can mint a fresh allowance at will. And a shared cache keys
    on location, so varying the coordinates slightly misses it every time. The
    two weaknesses compose: rotate the uid, jitter the coordinates, and both
    defenses are gone. What is actually needed is a **durable or aggregate
    server-side boundary**, and **these are layers, not alternatives** (Codex,
    2026-09-03) — offering them as a menu was the second version of the same
    mistake, because none of them bounds a single caller on its own. Grid
    snapping only makes coordinate variation coarser, so a scripted caller walks
    distinct cells; an attestation that survives a reinstall supplies a *durable
    identity* and imposes no limit by itself; and a global ceiling, alone, just
    lets one caller reach the everybody-fails state sooner. So the shape is a
    composition — a per-caller rate limit, with coarse-keyed caching **plus
    per-key single-flight** underneath it to cut what reaches upstream, and an
    aggregate circuit breaker above it as the last stop — and **the key that
    per-caller limit sits on is the unresolved part**: what follows is why Play
    offers a durable *flag* and no durable *key*. The single-flight is not a refinement of the cache
    but the half that makes it count against a burst at all, per the paragraph
    above.

    **A durable identity sounds like a disclosure, and the mechanism Play
    actually offers is not one** — but the first version of this paragraph
    named the wrong primitive (Codex, 2026-09-03, correctly). Play Integrity
    tokens are **per-request attestations carrying no stable per-device
    identifier**, so an opaque id derived from one links nothing across
    reinstalls; proposing that was proposing something that does not work.

    What does is **device recall** (`developer.android.com/google/play/integrity/device-recall`,
    read 2026-09-03, beta): Google stores **three bits per device** on this
    app's behalf — three flags, or eight labels combined — and they **survive an
    app reinstall and a factory reset**, held for three years after the last
    access. Crucially, "the requesting app can only recall the limited data that
    it associated with devices, without accessing any device or user
    identifiers": nothing durable is sent to or stored by us at all, because
    Google holds the association and hands back only our own bits. So the
    trade-off this paragraph previously recorded as a genuine tension is
    **narrower than it looked** — the reinstall-resistant half costs no
    identifier.

    **What it narrows is what *we* receive; what Google holds is a separate
    flow, and this page owed it** (Codex, 2026-09-04, P2). The sentence above
    is true about the app and stops there. On the other side of it Google is
    storing **per-device state written on this app's behalf** — surviving a
    reinstall and a factory reset, held three years past last access — that
    would not exist but for our writing it. "No identifier comes back" does
    not make that undeclared: it is a persistent third-party data flow whose
    **purpose and retention need a Data Safety answer and a `PRIVACY.md`
    line before the mechanism is adopted**, not after. Two concrete gaps.
    `docs/ROADMAP.md`'s privacy checklist covers the purchase token and, since
    the last round, the coordinates and typed place names the proxy would
    receive — device recall has no slot in it, which is the same shape of
    omission that bullet was itself added to fix. And `PRIVACY.md`'s
    provider table closes with *"These providers act as service providers
    fulfilling a single request and returning the result"* — accurate for
    every row it has today, and exactly what device recall is not. A write
    that outlives its request needs its own sentence, not another row under
    that one.

    **It is not free of cost or failure modes, though, and this page owes both
    before treating it as the mechanism** (Codex, 2026-09-04, against the repo's
    own cost-and-reliability rule). Device recall is a **second, beta**
    dependency on top of the App Check flow the proxy already needs: another
    integration, another SDK surface, another thing that can change under a
    beta label. Its own request quota and any cost are **unsized here** — writes
    carry rate limits separate from the integrity-token quota, and nobody has
    counted what this app would spend. And the outage behavior is the part that
    has to be decided rather than discovered: **when a recall verdict or write
    is unavailable, failing open removes the durable boundary exactly when an
    abuser would want it gone, while failing closed denies forecasts to
    legitimate installs for an outage that is not theirs.** Given this app's
    stakes — a wrong forecast is an annoyance, a blocked one is the product not
    working — the defensible default is **fail open on the durable layer and
    lean on the aggregate ceiling**, which fails everybody rather than singling
    out the innocent, with the outage logged so a sustained one is visible. Not
    a decision; the point is that the choice exists and the fail-closed version
    would be a quiet way to break the app for real users.

    **But three bits is a flag, not a counter, which changes the design rather
    than completing it.** It cannot carry a rolling per-caller quota; it can
    carry *"this device has already been caught abusing"* or *"this device has
    had the free trial"*. So the shape is: ordinary rate limiting keyed on the
    app-instance identity for routine load — which a reinstall resets, and that
    is an acceptable cost for the ordinary case — with device recall as the
    durable mark for a caller already caught, and the aggregate ceiling behind
    both. **That leaves a gap this page should not pretend is closed** (Codex,
    2026-09-03): a caller who reinstalls *before* crossing the per-instance
    threshold is never marked, so repeated sub-threshold batches spend
    arbitrarily much and the aggregate breaker — which fails everybody, not
    them — becomes the only thing that stops it. Closing it needs accounting
    that survives a reset *before* the limit is reached, not a post-detection
    flag. Three bits still cannot count, so the candidates are marking on
    something cheaper than proven abuse (a first bit set at first use, making
    reinstall itself visible), a much lower ceiling for an instance with no
    history, or accepting the gap explicitly on the grounds that this app's
    traffic is not worth scripting. Unresolved; naming it beats a design that
    reads complete. Its own constraints belong in any estimate: bits are writable only
    within **14 days** of the verdict they cite, propagation to the next read is
    ~30 s, and writes carry their own rate limits.

    What still needs settling is the **quota row itself**: how long it lives (an
    hour or a day of counters, not a history) and whether it can be joined to
    anything else the proxy holds — a quota row beside a cache entry is a log of
    who asked about where, which the paragraph above already ruled against.
    **Coarsening the coordinate is not the same as keying on it** (Codex,
    2026-09-03): `OpenMeteoClient.fetchForecast` fires the primary request and
    the multi-model confidence fetch at the *same* location concurrently, each
    asking for different variables, and the model set is user-selectable — so a
    location-only key would serve one request's payload to the other and produce
    either a parse failure or a forecast blended from somebody else's
    configuration. The key is the whole request: endpoint plus normalized
    non-location parameters, with only the coordinate component coarsened.
    **`past_days` and `forecast_days` are relative, so the key needs the target
    zone's local date too** (Codex, 2026-09-04) — otherwise an entry cached just
    before midnight there keeps serving after it, and the mapper reads daily
    index 1 as today whatever day the bundle is actually for. Either that date is
    a key component or each entry's expiry is capped at that zone's next
    midnight; not picked here.
    **And coarsening the key alone is not enough — the coordinate sent upstream
    has to be snapped too** (Codex, 2026-09-03). Cache the first caller's
    *exact-location* response under a coarse key and the second caller at a
    different point inside that cell is served weather for somewhere they are
    not; with `timezone=auto` (both clients set it) the response also carries
    the first caller's resolved zone, so the dates and times can be wrong as
    well, not only the numbers. It is a privacy fault before it is a correctness
    one: the stored payload retains a finer location than the key admits to, in
    a store this page has already said should hold no more than it must. The
    proxy therefore requests the canonical snapped point, and what is cached is
    the answer for *that* point — which is what makes the entry honestly
    shareable.

    **Snapping alone still gets the dates wrong across a zone boundary, though**
    (Codex, 2026-09-04). A coarse cell is a rectangle on a map and a time-zone
    line does not respect it: with `timezone=auto` the upstream resolves the zone
    at the *snapped* point, so every caller on the other side of the line inside
    that cell receives a forecast labeled with the wrong local day. That is worse
    than a numeric error, because this app's whole output is day-shaped —
    "today", "tonight", the morning insight — so a caller one cell-width from the
    boundary is told about the wrong day entirely, consistently, and with nothing
    that looks like a fault. Snapping makes the entry *consistent*; it does not
    make it *correct*, and the previous paragraph conflated the two.

    **And keying on the zone is only half of that fix** (Codex, 2026-09-04,
    against the first version of this paragraph, which stopped at the key). A key
    decides which entry a caller is served; it does not change what the entry
    contains. Leave `timezone=auto` in place and the upstream still resolves the
    zone at the *snapped* point, so a straddling cell yields two entries that both
    carry the same wrong zone — the same bad answer, filed twice. The zone has to
    travel **upstream as well as into the key**: send the caller's resolved zone
    as an explicit `timezone=<IANA name>` instead of `auto`, and key on it, so the
    response is dated for the caller and the entry is shared only with others in
    that zone. The alternative — request UTC and localize on the device — removes
    the zone from the cache dimension, but it is the weaker of the two: it works
    only if every daily aggregate is rebuilt from hourly data, since
    `fetchPrimary` asks for day-bucketed extrema and totals that cannot be
    re-bucketed after the fact.

    **Calling the explicit-zone version "the smaller change" skipped a step: the
    client has no zone to send** (Codex, 2026-09-04). `Location` carries
    latitude, longitude, a display name, a country code and an address
    detail — no zone. `GeocodingResult` drops Open-Meteo's `timezone` field on
    the floor. And `forecastZone` is *learned from the forecast response*, which
    is the thing being requested. So for a manually picked location the value
    simply does not exist yet, and the device's system zone is the wrong answer
    for a location the device is not in — which is exactly the remote-location
    case the feature exists for. The three sources, once separated, are not
    equally hard:

    - **Device location** — the system zone is usually right for a fresh fix,
      and it is free, but it is **not implied by one**: automatic time-zone
      detection can be off, or not yet caught up after travel, and then sending
      it upstream is worse than today's `timezone=auto`. And `LocationResolver`
      can fall back to an old cached coordinate, which carries no zone at all —
      `toDomain` copies latitude, longitude and a display name and then
      coarsens. So this source is free only where the system zone can be
      trusted; both other cases land in the unsolved one below.
    - **A geocoded manual pick** — Open-Meteo's geocoding response already
      returns `timezone`; the DTO discards it. Capturing it, carrying it on
      `Location`, and persisting it closes this case with no new network call
      and no new disclosure. That is the whole fix, and it is small — but it is
      a schema and migration change, not a query-parameter change.
    - **A location persisted before that field exists** — the unsolved one, and
      the previous version of this bullet answered it with two non-answers
      (Codex, 2026-09-04). "Learn it from the first response" cannot work: the
      proxy asks for the *snapped* point, so `timezone=auto` returns that
      point's zone, and a legacy location near a boundary would permanently
      record the wrong one — the precise failure this whole passage is about.
      "Take the UTC route" cannot work either, because localizing a UTC
      response still needs the target IANA zone. The zone has to be resolved
      **independently, before snapping**, and the candidates each cost
      something: **re-geocode the persisted display name** (Open-Meteo's
      geocoding returns `timezone`, and manual picks are exactly the ones that
      persist a name — null for a location saved without one, and on the proxy
      branch it sends that stored name to our own infrastructure). It is one
      geocoding request per affected saved location, once, so the volume is
      trivial against any of the ceilings above — but it counts against the same
      unsized geocoding path, and a no-match or an unavailable service leaves the
      zone unresolved, which drops back to the other candidates rather than
      guessing one; or simply **asking on the Location page** during
      migration. **Nothing that resolves from the persisted coordinate is on
      the list** — neither a lookup through the proxy nor a bundled offline
      tz-boundary database — because those records were coarsened before they
      were stored, so a 2-decimal point can land on the wrong side of the very
      boundary this is about. That leaves only independent sources. Unresolved
      here, and naming it is the point.

    So the explicit-zone version is the answer: UTC needs a persisted zone to
    render anyway, and it additionally needs every daily aggregate rebuilt. The
    legacy-location problem above survives either choice. A cache keyed on a
    coarsened coordinate without a zone is quietly wrong for everyone near a
    boundary.
    **The third concurrent call is not Open-Meteo's** (Codex, 2026-09-03) and
    was wrong to count here: `extraModelHourly` is the Google Weather path,
    fired against the user's own Google Cloud key with its own device-side
    `GoogleForecastCache`, and a miss there is roughly ten paginated Google
    calls. It spends none of the Open-Meteo allowance, and it must stay outside
    this proxy and its cache — routing BYOK Google traffic through
    developer-operated infrastructure is a different decision, taken nowhere on
    this page.
    Unresolved here; naming it is the point, because "reuse `reserveDailySlot`"
    reads like a solved problem and is not one. Whichever is chosen, the **user-visible behavior at the limit has to
    be specified**, in both cases: where the device or the cache still holds a
    forecast, show it labeled as stale rather than a blank screen. Where neither
    does — a fresh install, or a location nobody has asked about yet — there is
    nothing stale to fall back on (Codex, 2026-09-03), so that path needs its own
    explicit state: a named "forecast unavailable, try later" screen with a retry,
    never an empty one and never a spinner that never resolves. Saying "never a
    blank screen" without defining the cold case leaves the worst version of it
    undefined.

    **And it is not only forecasts** (Codex, 2026-09-03). `OpenMeteoGeocodingClient`
    calls `geocoding-api.open-meteo.com/v1/search` straight from the device with
    the user's **typed place name** as the `name` parameter — a separate host,
    keyless today, and one the commercial plan also covers. So either that
    endpoint stays licensed for this app's commercial use without a key, which
    nobody here has confirmed, or it is proxied too; scoping the analysis to
    forecasts understated all three costs — and, on the proxied branch, the
    abuse controls above apply to it too (Codex, 2026-09-03). Everything in this
    section has been scoped to the forecast proxy while this branch listed only
    capacity, engineering and privacy, which leaves the second endpoint open: an
    authenticated caller can issue arbitrary search strings, and neither the
    forecast cache nor grid snapping absorbs any of that — search text does not
    snap to a grid, and a novel string is a miss by definition. So proxied
    geocoding inherits the same per-caller limit and aggregate circuit breaker
    — and with them the same unresolved key and the same pre-threshold
    reinstall gap — or one client exhausts the shared allowance and takes
    everybody's forecasts down through the endpoint nobody was watching. Its own
    cacheability is worth a separate look, since repeated searches for the same
    place are common and a normalized query string is a fair **component** of a key
    — though not the key itself (Codex, 2026-09-04): `OpenMeteoGeocodingClient.search`
    takes `limit` and `languageTag` too and sends `name`, `count`, `language` and
    `format`, so a query-only key hands one caller another's language or result
    count. The forecast cache above already established "key on the whole
    request"; failing to carry that one paragraph later is the same miss this
    page keeps making. Same rule here, or canonicalize those parameters at the
    proxy so there is only one shape to cache. It is also a
    saving rather than a control, **and the same storage decision the forecast
    cache already had to make** (Codex, 2026-09-03), which offering it as a
    plain optimization skipped. A query-string key *is* the user's typed
    location, persisted server-side rather than forwarded in transit — and a
    typed search can be a street address, which is finer than any coordinate the
    forecast path sends. So it carries the forecast cache's conditions and one
    more: stated retention and eviction, **no caller linkage of any kind** (a
    query keyed beside an identity is a search history, which is worse than the
    "who asked about where" the forecast cache was already told to avoid), and
    the disclosure written before it ships. Cheaper than the forecast cache it
    is not; whether the saving is worth that is undecided here.

    - **Capacity, but only on one branch** (Codex, 2026-09-03): geocoding
      requests count against the same allowance **if that endpoint is proxied
      too**. On the other branch — the one where keyless commercial use of the
      geocoding host turns out to be licensed — searches keep going direct from
      each device with no customer key, and consume none of the paid account's
      allowance. Counting them unconditionally would inflate the sizing and
      could select the $99 tier for calls that were never billed to it, so they
      belong inside the weighted-call sizing *in the proxied branch* and are
      sized against the direct endpoint's own limits otherwise. Which branch
      holds is the unconfirmed question above; this is a consequence of it, not
      a separate one.
    - **Engineering**: a second proxied surface, a second failure mode, and
      added latency on a path where the user is typing.
    - **Privacy, which is not just arithmetic**: the proxy would receive **what
      the user typed into a location search**, not only coordinates. Under this
      repo's own rules that is user data crossing the device boundary to *us*,
      which changes the Data Safety declaration and is a product decision rather
      than an implementation detail. Nothing here decides it; it is named so the
      paid-plan branch is not costed as if it were forecast traffic alone.
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
  break-even subscriber count is the arithmetic that actually matters — **and it
  is now computable** (maintainer supplied $29/month, 2026-09-03). Net of Play's
  fee and the realistic ~$0.36/subscriber Gemini spend, **license plus Gemini** is
  cleared at roughly **14 subscribers at $2.99 and 60 at $1** (73 at $1 against
  the $0.45 max-use ceiling), **on the $29 Standard tier, whose fit is itself
  unestablished** — a subtotal rather than the tier's break-even,
  since the paid plan also forces an authenticated proxy this page has never
  priced (Codex, 2026-09-03). What is still *not* computable is whether a price
  is viable, because that needs trial cost and conversion, which nothing here
  measures — so the earlier conclusion holds for a different reason than it used
  to: no price on this page is established as viable, $2.99 included.
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
  commercial license above is a **$29/month** fixed floor underneath all of it
  (maintainer, 2026-09-03). At $1/month that floor alone takes ~35 subscribers to
  cover, and **the tier does not break even there** (Codex, 2026-09-03): 35
  subscribers net $29.75, which clears the license by $0.75 while their Gemini
  use costs ~$12.60 at the realistic $0.36 each. Counting both, $1/month turns
  positive at roughly **60 subscribers** — 73 against the $0.45 max-use ceiling —
  versus ~14 at $2.99. Those clear the license and Gemini only, **and only if
  the $29 tier fits at all** (Codex, 2026-09-03) — its 1M-call allowance is a
  capacity limit nobody has sized, and $99 multiplies each count by 3.41. The proxy
  the paid plan forces is unpriced too, and trial cost is on top of all of it.

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
