# Gemini TTS Voice Evaluation

Results from a manual listening session conducted against real app output
(en-AU and en-GB, Gemini 2.5 Flash Preview TTS). All scores are subjective
1–10 ratings by the primary user unless noted.

> **Note (post-PR #323):** the newsreader directive this eval was
> conducted under is now opt-in via the new "Style" picker, with `Normal`
> (the v505-equivalent studio-voice preamble) as the default. The default
> voice has moved from Erinome to Leda alongside that change. A
> re-audition under Normal is pending — sections below remain the
> historical record of the newsreader-only eval, not the current
> recommendation.

---

## Problem statement

The production directive at the start of the session was:

> "Read the following in a clean, crisp studio voice:"

Two separate issues were observed on different voices:

1. **Theatrical / villain delivery** — mostly on male voices (Orus, some
   Kore clips). The word "studio" likely pushes the model toward a dramatic
   broadcast register.
2. **Vinyl crackle / lo-fi texture** — an audio artifact baked into some
   clips by the model, audible as faint surface noise under the voice.

---

## Directive evaluation

Tests were run via `scripts/compare-tts-directives.py` against neutral
weather copy in en-GB, using Kore and Erinome as reference voices.

| ID | Directive | Erinome | Kore | Notes |
|---|---|---|---|---|
| A1 | `newsreader style — articulate but not theatrical, no audio effects` | 10 | 10 | Best overall; applied to production |
| A2 | `conversational — educated but not formal or posh` | 2 | 10 | Erinome sounds flat/unengaged |
| B1 | Current production (`clean, crisp studio voice`) | 10 | 10 | Identical text to A1 (sanity check) |
| B2 | `with a modern, approachable accent` | 5 | 7 | Loses clarity benefit |
| B3 | No style prefix (accent only) | 1 | — | Defaults to American English |

**Chosen directive:**

```
Read the following in a clear, educated newsreader style — articulate but
not theatrical or exaggerated. No audio effects, background noise, or
vinyl-style texture:
```

Rationale: "Newsreader" is the clearest single-word register signal the
model understands across locales. It suppresses theatrical delivery and
crackle without pushing into stiff/posh territory. It applies globally
before any per-locale accent clause.

---

## Voice evaluation

### en-AU testing

Tested with `scripts/compare-gemini-voices.py` and in the real app with
accent set to "Cultivated Australian — clear and educated, not broad."

| Voice | Score | Notes |
|---|---|---|
| **Erinome** | 10 | Perfect. Zero regional artifacts. |
| Kore | 7 | ABC regional news feel — acceptable |
| Aoede | 7 | Acceptable fallback |
| Iapetus | 6 | Slightly unnatural but clear |
| Orus | 5 | Play School / condescending register; acceptable if nothing better |
| Vindemiatrix | tested pre-newsreader | See en-GB row below |
| Achird | — | Bad; both testers agreed, dropped |
| Remaining ~9 voices | — | Theatrical or vowel issues; not listed |

After the newsreader directive was applied, Iapetus improved and Erinome
became clearly the best choice for en-AU.

### en-GB testing (Kore and Erinome)

`scripts/compare-tts-directives.py` with en-GB locale, newsreader directive.

| Voice | Score | Notes |
|---|---|---|
| **Erinome** | 10 | Crisp, educated, zero crackle |
| Kore | 10 | Clean newsreader; slightly regional on some clips |
| Aoede | — | "Alan Rickman" effect with newsreader + en-GB — dropped |

### en-GB testing (daughter's ratings, conducted on older "studio voice" directive)

The secondary tester evaluated the full picker list before the directive
change. These scores reflect the voice's character rather than directive
interaction.

| Voice | Score | Notes |
|---|---|---|
| **Aoede** | fave | Warm, smooth |
| Despina | 2nd fave | Gentle/smooth; added to picker |
| Vindemiatrix | liked | Gentle; added to picker |
| Achird | liked | Conflicts with primary tester's "bad" — not added |

Note: these ratings were on the old `studio voice` directive. Despina and
Vindemiatrix were added to the picker without re-testing on newsreader;
they may sound different. Aoede was excluded from the picker due to the
Alan Rickman issue under the production directive.

### Male voices

| Voice | Score | Notes |
|---|---|---|
| Charon | ok | Tested briefly in early sweep; Informative label fits weather |
| Sadaltager | untested in-app | Knowledgeable label; added alongside Charon |
| Orus | 4 | Theatrical affectations; dropped |

---

## Final picker list (shipped in PR #291)

```kotlin
val GEMINI_VOICES = listOf(
    TtsVoiceOption("Erinome",       "Erinome — Clear"),       // default
    TtsVoiceOption("Iapetus",       "Iapetus — Clear"),
    TtsVoiceOption("Kore",          "Kore — Firm"),
    TtsVoiceOption("Charon",        "Charon — Informative"),
    TtsVoiceOption("Sadaltager",    "Sadaltager — Knowledgeable"),
    TtsVoiceOption("Vindemiatrix",  "Vindemiatrix — Gentle"),
    TtsVoiceOption("Despina",       "Despina — Smooth"),
)
```

**Default: Erinome.** Scored 10/10 on neutral weather copy in both en-AU and
en-GB with the newsreader directive. Replaced previous default (Kore).

---

## Notes on voice reliability

- The directive suppresses the theatrical delivery register but **cannot
  override a voice's phonetic anchor.** Iapetus and Erinome went
  Queensland-broad on en-AU without the accent clause; the cultivated accent
  directive fixed it for Erinome but only partially for Iapetus.
- TTS responses are **not cached.** Emotionally charged weather copy
  ("dangerous icy conditions", "severe storm warning") may push voices toward
  dramatic delivery regardless of the directive. The newsreader directive
  reduces but doesn't eliminate this.
- **Voice Locale must be set explicitly** (not "System") to get the accent
  clause. With System/System, the locale falls back to `Locale.getDefault()`
  and Aoede or similar voices can sound unexpectedly regional.

---

## Weather-report style eval (2026-05)

Tests whether an explicit weather-broadcast directive improves on the
newsreader baseline. Run via `scripts/eval-weather-report-style.py` against
real InsightFormatter output:

> "Today will be cold to cool. Wear a jumper and jacket."

Seven voices × seven candidates × nine locales. en-GB and en-AU-general ran
all candidates; en-US ran B1/B2 only. The original en-AU (Cultivated) run is
stale — the prod accent was already changed to General Australian before that
eval; use en-AU-general results instead.

### Candidate averages

| Candidate | en-US | en-AU-gen | en-GB | Notes |
|---|---|---|---|---|
| weather-B2-with-emphasis *(→ prod)* | **9.1** | **7.9** | — | B1 + "gentle emphasis on clothing" |
| weather-B1-basic | 9.0 | 7.6 | **8.3** | "national news service, measured speed" |
| tweak-C1-authoritative | — | 7.4 | 7.6 | |
| tweak-C3-with-pauses | — | 7.1 | 7.3 | |
| tweak-C2-cadence *(was prod)* | — | 6.7 | 6.9 | |
| baseline-A2-newsreader | — | 6.3 | 5.9 | |
| baseline-A1-normal | — | 5.1 | 5.0 | |

B1 and B2 beat the old production directive (C2) by ~1 point consistently.
B2 wins en-US and en-AU-gen; B1 wins en-GB under the old accent directive.

### en-GB accent tuning

The original en-GB directive ("Standard Southern British") caused B2 to
underperform vs B1 (7.6 vs 8.3). Three replacements were tested with B2:

| Accent variant | B2 avg | Notes |
|---|---|---|
| en-GB-measured — "Standard British — clear and natural" | **8.3** | Matches B1; no degenerate cases |
| en-GB-presenter — "clear accent of a British television news presenter" | 7.6 | Charon collapses to 3 |
| en-GB-bbc — "Standard Southern British, as spoken by a BBC news presenter" | 6.9 | Flat across the board |

**en-GB-measured fixes B2 in en-GB.** Updated in prod.

### Final scores (B2 + updated accent directives)

| Locale | B2 avg |
|---|---|
| en-US | 9.1 |
| en-AU-general | 7.9 |
| en-GB-measured | 8.3 |

### Top voices

| Rank | Voice | Notes |
|---|---|---|
| 1 | **Aoede** | Consistent; one dip on old en-GB B2 (now fixed) |
| 1 | **Charon** | Rock-solid except en-GB-presenter (avoid that variant) |
| 1 | **Kore** | Consistent |
| 4 | **Despina** | Strong on B2; one degenerate on B1/en-AU-general |
| 5 | Erinome | Solid mid-tier |
| 6 | Leda | Degenerate on old en-AU (Cultivated) + B2; fine under current accents |
| 7 | Iapetus | Weakest overall |

### Shipped

- `GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER` → B2 wording
- en-GB accent directive → "Speak with a Standard British accent — clear and natural."
  *(Superseded by the clarity-trim eval below — now "Speak with a Standard
  British accent.")*

---

## Accent clarity-trim eval (2026-05)

Tests whether the "clear and natural" / "klaren" / "claro" / etc. clarity
adjectives in `ACCENT_DIRECTIVES` add value, given that the WEATHER_FORECASTER
directive already says "Enunciate clearly and use a measured speed."

Run via `scripts/eval-weather-report-style.py --filter en-GB-prod-weather-B2`
and `--filter en-GB-bare-weather-B2`. Same 7 voices, same B2 directive, en-GB
locale only. Two accent variants:

- **prod:** "Speak with a Standard British accent — clear and natural." (was shipped)
- **bare:** "Speak with a Standard British accent." (clarity trimmed)

### Per-voice scores (en-GB, B2 directive)

| Voice | prod | bare | Δ |
|---|---|---|---|
| Aoede | 8 | 8 | 0 |
| Charon | 8 | 9 | +1 |
| Despina | 8 | 9 | +1 |
| Erinome | 2 | 8 | +6 |
| Iapetus | 5 | 8 | +3 |
| Kore | 5 | 9 | +4 |
| Leda | 7 | 9 | +2 |
| **avg** | **6.1** | **8.6** | **+2.5** |

### Findings

- **No voice regressed** under the clarity trim; every voice was same-or-better.
- **Erinome (2/10 → 8/10) was the most striking lift** — degenerate under prod,
  fine under bare. Iapetus (5 → 8) and Kore (5 → 9) — voices most prone to
  RP/villain register — also lifted significantly. The clarity adjectives appear
  to actively destabilise some voices, likely by pushing toward
  over-articulation.
- The prior eval (Weather-report style eval above) recorded prod en-GB at 8.3
  avg vs. 6.1 here — TTS run-to-run variance, mainly Erinome going degenerate
  this run. Bare is uniformly in the 8–9 band so the conclusion holds even on
  the optimistic 8.3 read of prod.

### en-AU spot check (on-device, shipping voices only)

Tested on the Firebase APK against the trimmed
`"Speak with a General Australian accent, not broad."` and the same B2
directive. Single read per voice, no re-rolls.

| Voice | Trim today | Historical (B2 + "— clear and natural, not broad.") | Verdict |
|---|---|---|---|
| Charon | 9 | "Rock-solid" (tied #1 overall) | consistent |
| Iapetus | 8.5–9 | "Weakest overall" (#7) | **improvement** |
| Leda | 8 | "Fine under current accents" (#6) | consistent |
| Despina | flat | "One degenerate on B1/en-AU-general" (#4) | pre-existing |

Average across the three non-flat voices ≈ 8.5 — right in line with the
en-GB trimmed average of 8.6. The trim generalises well from en-GB to
en-AU on three of four voices.

**No new regression.** Despina-on-en-AU was already flagged in the prior
en-AU-general eval as the one degenerate combo for that voice; what
on-device testing surfaced is the same voice-anchor limitation surviving
through the trim, not a new flaw the trim introduced. Iapetus actually
improved (from "weakest overall" to 8.5–9). Charon and Leda are stable.

A `"...General Australian accent — natural, not broad."` partial-trim
(drop "clear" only, keep "natural" as positive shaping) is worth a
follow-up sniff test if we want to specifically rescue Despina-on-en-AU;
otherwise users who want en-AU liveliness pick Charon, Iapetus, or Leda.

Erinome was also tested ad hoc — flagged as flat on en-AU and as
having an over-rhotic "sweater" on en-US under the old prod accent.
Both look like Erinome's phonetic anchor leaking through; the
clarity-trim eval below rescues en-GB (2 → 8) and the B3 eval rescues
en-AU (7 → 9), so Erinome stays in the picker after #353.

A follow-up en-US sniff-test under the current shipping config —
B2 directive + `"Speak with a General American accent."` (post-#351
trim, no "clear and natural") — rated **10/10**. The over-rhotic
"sweater" issue was a casualty of the old "clear and natural" accent
prod string and is fixed by the trim. Erinome on en-US is now a
strong shipping candidate (and a strong contender for default-voice;
see the TODO at the end of this doc).

### Bonus finding: de-CH

Single-voice spot check (Leda, German weather text):

- Original (`klaren deutschschweizerischen Akzent`): weird mishmash of Swiss
  German and Hochdeutsch — neither register, just confused.
- Clarity-trimmed (`deutschschweizerischen Akzent`): clean Schwiizerdütsch
  dialect.

The clarity word was the destabiliser. Trimming it lets the model commit to a
register. Schwiizerdütsch isn't standard broadcast Swiss-German for weather
(Schweizer Hochdeutsch would be), but the dialect is recognisable and pleasant
and the previous mishmash was the actual bug. A separate follow-up could push
de-CH toward Schweizer Hochdeutsch via directive shaping; keeping the cute
Schwiizerdütsch render is also a defensible choice.

### Shipped

- Clarity adjectives ("clear and natural", "klaren", "claro", чёткий, jasnim,
  selkeällä, 清晰, स्पष्ट, واضح, etc.) removed from every entry in
  `ACCENT_DIRECTIVES`. Variety descriptors ("Standard", "General",
  "hochdeutsch", "castellano", "neutro", "标准", "Fuṣḥā", literary/literary
  register markers in Russian / Ukrainian / Swahili) remain — those carry
  per-locale work that the directive doesn't.
- For locales where the only thing left after the trim was a tautology
  (e.g. "Italian with an Italian accent"), the entry simplifies to a bare
  language directive (`"Parla in italiano."`).

---

## B3 register-in-directive eval (2026-05)

Tests whether the per-locale variety descriptors in `ACCENT_DIRECTIVES`
("Standard British", "General Australian", "in einem hochdeutschen Akzent")
can collapse into the directive once the directive itself carries the
register signal. Hypothesis: with the directive instructing "Use the
language's standard variety, not a regional dialect", the per-locale
"Standard / General" qualifiers become redundant.

Run via `scripts/eval-weather-report-style.py`. Two conditions per locale:

- **B2 + with-variety** (control): current shipped, e.g. en-AU =
  `"Speak with a General Australian accent, not broad."`
- **B3 + bare** (treatment): directive gains
  `"Use the language's standard variety, not a regional dialect."`,
  accent drops the variety qualifier (e.g.
  `"Speak with an Australian accent, not broad."`)

Two locales tested formally; a third spot-checked.

### en-AU — clear win

| Voice | B2 + prod | B3 + bare | Δ |
|---|---|---|---|
| Aoede | 9 | 9 | 0 |
| Charon | 9 | 9 | 0 |
| Despina | 9 | 9 (re-rolls 8/8/9) | 0 |
| Erinome | 7 | 9 | +2 |
| Iapetus | **5** | 9 | **+4** |
| Kore | 6 | 8 | +2 |
| Leda | 7 | 5/7.5/8/7 (avg ~6.9) | mixed |
| **avg** | 7.4 | 8.3 | +0.9 |

Iapetus 5 → 9 is the standout — lifts a near-degenerate voice into the
shipping band. Leda showed wide variance (5 to 8) on en-AU under B3; see
"Leda dropped" below.

### en-GB — slight regression, off-brand

| Voice | B2 + prod | B3 + bare | Δ |
|---|---|---|---|
| Aoede | 8 | 7 | −1 |
| Charon | 9 | 9 | 0 |
| Despina | 9 | 9 | 0 |
| Erinome | 8 | 9 | +1 |
| Iapetus | 8 | 8 | 0 |
| Kore | 9 | 8 | −1 |
| Leda | 9 | 8 | −1 |
| **avg** | 8.6 | 8.3 | −0.3 |

Numerically a mild −0.3, but **qualitatively worse than the numbers
suggest** — voices under B3 read as "serviceable but not on-brand" for the
cultivated Weather Forecaster identity. The "Standard British" qualifier
was doing real work on en-GB; the directive sentence couldn't replace it.

### de-CH spot check (one voice each)

The directive change does *not* fix de-CH's broader register question; the
post-#351 clarity trim alone produces clean Schwiizerdütsch dialect. B3 +
bare-er accent (drop the "deutsch-" prefix from `deutschschweizerischen`)
nudged Leda toward Hochdeutsch but isn't shipping in this PR — kept
de-CH on the post-#351 string.

### Asymmetry interpretation

The directive sentence helps when the locale's accent anchor is
comparatively weak (en-AU "General Australian" is a less common phrase
in Gemini training than the en-GB equivalent) and adds noise when the
anchor is already strong (en-GB "Standard British" carries cultivated
broadcast register on its own). One directive change isn't the right
shape for both locales — hence the per-locale routing rather than a
global update.

### Negative findings (kept for posterity)

Single-voice tests on Despina + en-AU + B3 against several "more
newsreader feel" expansions. **All caused regressions on at least one
shipping voice** while only marginally helping Despina:

| Variant added on top of B3 | Despina | Charon | Iapetus |
|---|---|---|---|
| `+ "engaged, articulate, professional, not theatrical"` | 8.5 | **7.5** (British "jackit") | 8.5 (a bit fast) |
| `+ "engaged…" + "unhurried, measured pace"` | 9 (single roll) | **5** (too regional) | 9 (still slightly fast) |
| `+ "pausing briefly between key points"` | **5** | (untested) | (untested) |

Same pattern as the historical en-GB-presenter eval (`voice-evals.md`
above): every additional shaping clause has pulled some voice off-register
or off-accent. The directive prose is at the variance ceiling — single-
word tweaks are at or below the noise floor on the voices that respond
well, and reliably regress voices that were already at 9.

### Despina-as-default decision

After Leda showed wide variance on en-AU under B3 (rolls 5, 7.5, 8, 7 — only
1/4 hits user's ≥8 acceptance bar), Despina was probed as the default-voice
replacement:

| Locale | Despina score |
|---|---|
| en-GB B2 prod | 9 |
| en-GB B3 bare | 9 |
| en-AU B2 prod | 9 / 8.5–9 / 8 / 6–7 (4-roll variance) |
| en-AU B3 bare | 9 / 8 / 8 / 9 (4-roll, all ≥8) |
| en-US B2 prod | 8 |
| de B2 prod | 8.5–9 |

Despina is reliable, not dazzling — distribution clusters at 8–9 with a
single outlier at 6–7 on en-AU B2 prod (the condition we're moving away
from). Under the actual en-AU shipping condition (B3 + bare), all four
rolls were ≥8.

**Leda dropped from picker** (see picker section below): default goes
Leda → Despina. Leda's 5–8 lottery on en-AU under B3 is the immediate
trigger; the broader pattern (Leda below the user's ≥8 bar across
multiple configurations) supports the broader picker decision.

### Shipped

- Per-locale directive routing in `directiveFor(style, locale)`:
  WEATHER_FORECASTER on en-AU gets `GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER_REGISTER`
  (B2 + the explicit "standard variety, not regional dialect" sentence);
  every other locale stays on the existing `GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER`.
- `ACCENT_DIRECTIVES["en-AU"]` drops "General": `"Speak with an Australian accent, not broad."`
- All other locales unchanged from the post-#351 clarity-trimmed strings.

---

## Picker + default (2026-05, post-#353)

After the directive landscape stabilised (clarity-trim in #351, en-AU
register routing in #353), re-evaluated the full 11-voice picker against
the shipping configuration: the user's bar is ≥8/10 on every roll, with
single-roll degeneracies disqualifying.

### Cross-voice German spot check

Three voices were probed across the German locales we ship — driven by
the question of whether the picker needs a "weather forecaster" male
alternative beyond Charon. Single read per cell.

| Voice | de | de-AT | de-CH |
|---|---|---|---|
| Despina | 9 | 9 | 9 |
| Charon | 9 | 8.5 | fine |
| Iapetus | 9 | 8 | fine |

All three clear the bar across all three German locales. Confirms
Despina-as-default extends to German territory and gives the picker two
shipping male alternatives (Charon + Iapetus).

### Picker decision

Trimmed picker from 11 voices to 7, ordered with default first then by
character cluster:

| Voice | Label | Status |
|---|---|---|
| **Despina** | Smooth | Default. Validated en-GB / en-AU B3 (4-roll all ≥8) / en-US / de / de-AT / de-CH |
| Charon | Informative | Solid across locales; user's preferred male voice |
| Iapetus | Clear | Rescued by B3 routing on en-AU (5 → 9); solid elsewhere |
| Kore | Firm | Lifted by clarity-trim on en-GB (5 → 9); solid on B3 en-AU (8) |
| Erinome | Clear | Rescued twice — clarity-trim on en-GB (2 → 8), B3 on en-AU (7 → 9) |
| Aoede | Breezy | Strong consistent voice across locales |
| Leda | Youthful | Loses default slot to Despina but kept on user preference (see note) |

**Note on Leda:** the en-AU B3 4-roll variance (5 / 7.5 / 8 / 7, only
1/4 above the ≥8 bar) put her on the drop list during this eval. She
came back after a real-world listen on an older build where the user
rated her qualitatively strong. The variance data is real; the user's
preference is the spec. She loses the default slot to Despina's tighter
distribution but stays in the picker for users who want her character.

**Dropped:**

- **Sadaltager, Vindemiatrix, Algieba, Sulafat** — untested under the
  post-#351 + post-#353 shipping config. Trim-rather-than-extend approach:
  better to ship a smaller picker of voices the eval has actually
  validated than carry voices forward unchecked. Re-add follows a
  sniff-test if a real use case surfaces.
  *(Vindemiatrix came back in 2026-10; see "Picker additions" below.)*

### Shipped

- `GEMINI_VOICES` trimmed to the seven above, Despina first.
- `DEFAULT_GEMINI_TTS_VOICE` and `UserPreferences.DEFAULT_GEMINI_VOICE`
  → "Despina".
- Existing user picks are persisted by id; users on any of the four
  dropped voices keep their pick (no migration), they just won't see
  those entries in the picker if they reset to default.

### TODO: revisit default — Despina vs. Erinome head-to-head

Erinome was rescued twice in this eval arc — clarity-trim on en-GB
(2 → 8 in #351) and B3 routing on en-AU (7 → 9 in #353) — and a
follow-up en-US sniff-test under the current shipping config rated
**10/10**, fixing the historical over-rhotic "sweater" anchor. She
may now actually be the winner over Despina under the post-#353
shipping configuration. Needs a dedicated head-to-head across the
same six locales we used for Despina (en-GB / en-AU B3 / en-US / de /
de-AT / de-CH), multiple rolls per cell, before any default switch.
Tracked in `GeminiTtsClient.kt` next to `DEFAULT_GEMINI_TTS_VOICE`.

| Locale | Despina | Erinome |
|---|---|---|
| en-GB B2 prod | 9 | 8 (post-#351 bare; was 2 under old "clear and natural") |
| en-AU B3 bare | 9 / 8 / 8 / 9 (4-roll) | 9 (single roll) |
| en-US B2 prod | 8 | **10** (post-#351 trim, single roll) |
| de B2 prod | 8.5–9 | untested |
| de-AT B2 prod | 9 | untested |
| de-CH (post-#351) | 9 | untested |

---

## Algenib: vampire persona and forecaster (2026-10)

Algenib had never been tried before this round. It came up in
`scripts/compare-persona-voices.py` while picking a voice for the
Halloween vampire, then got a forecaster spot check.

### Vampire persona (pinned to Algenib in #1319)

| Voice | en-GB (5 takes) | en-US (3 takes) |
|---|---|---|
| Algenib | 9.2 (9, 10, 9, 8, 10) | 9.3 (9, 9, 10) |
| Schedar | 9.2 (9, 10, 8, 9, 10) | 3.3 (1, 2, 7) |
| Iapetus (previous pin) | 8.8 (9, 9, 9, 9, 8) | 4.0 (4, 3, 5) |
| Enceladus | 8.8 (9, 10, 8, 8, 9) | 6.7 (7, 7, 6) |
| Alnilam | 8.2 (10, 8, 6, 8, 9) | not run |
| Orus, Charon, Rasalgethi | dropped after round 1 | not run |

Schedar tied Algenib on en-GB and collapsed on en-US, which is why the
pin went to Algenib.

These rounds read the earlier "Transylvanian accent" prompt. The prompt now
asks for Bela Lugosi's Hungarian accent and the Hungarian falling contour
after each accent (Mády, Reichel & Beňuš 2013), shipped unrated on the
maintainer's call. Re-run `scripts/compare-persona-voices.py` before trusting
the pin on it.

### Forecaster (#1320 adds it to the picker)

Shipped accent and directive per locale (Despina not re-rated; it already ships):

| Locale | Pair | Algenib |
|---|---|---|
| en-AU | no-variety + B3 | **8.5–9** (two listens) |
| en-US | General American + B2 | 7 |
| en-GB | bare + B2 | 5 ("pretty average") |

Off the shipped path, en-GB-no-variety + B3 scored **7–7.5**, so Algenib's
en-GB weakness is mostly the B2 directive, not the voice. en-GB still ships
B2 for every voice, because B3 hurt Despina there in May. Before moving en-GB
to B3, re-rate Despina (and the other shipped voices) on both directives.
- Full-grid pass across locales: B3 scored 8–9 throughout; B1 and B2 dipped to
  4 in one block.
- German: sounded good on a quick device listen; unscored.

The scores were quick single listens, not the multi-roll distributions
used for the Despina default decision, so treat Algenib as a strong
male alternative (best on en-AU) rather than a default contender until it
gets that treatment.

---

## Untried voices on the shipped forecaster pairs (2026-10)

Every voice not in the picker and not already ruled out, rendered once on
each locale's shipped pair: en-AU no-variety + B3, en-GB bare + B2, en-US
General American + B2. Single listens, rated as gut scores: "roughly
indicative", not precise. Despina wasn't re-rated.

| Voice | en-AU | en-GB | en-US | avg |
|---|---|---|---|---|
| Vindemiatrix (F) | 8 | 7 | 9 | **8.0** |
| Enceladus (M) | 7 | 8 | 8 | 7.7 |
| Rasalgethi (M) | 7 | 7 | 8 | 7.3 |
| Algenib (M, for comparison) | 8.5–9 | 5 | 7 | ~6.9 |
| Algieba (M) | 6 | 7 | 7 | 6.7 |
| Alnilam (M) | 7 | 6 | 7 | 6.7 |
| Sulafat (F) | 6 | 6 | 8 | 6.7 |
| Schedar (M) | 6 | 5 | 8 | 6.3 |
| Sadaltager (M) | 6 | 5 | 6 | 5.7 |

None clears 8 in every locale, which the May picker voices did apart
from Leda (kept on preference despite a 5–8 en-AU spread). The
maintainer added the top three (Vindemiatrix, Enceladus, Rasalgethi) to the
picker anyway, as options rather than defaults (#1325). A multi-roll pass
would firm up these numbers.

### Picker additions (2026-10)

The picker now lists eleven voices. Algenib (#1320) joined after the vampire
rounds; Enceladus, Rasalgethi and Vindemiatrix (#1325) after this pass, each
after the existing voices of its gender. Defaults are unchanged: Despina
first, Charon the first male voice, the vampire pinned to Algenib.

---

## Scripts

| Script | Purpose |
|---|---|
| `scripts/compare-tts-directives.py` | Generate clips for multiple directives × 2 voices in en-GB |
| `scripts/compare-gemini-voices.py` | Generate one clip per voice for a single directive |
| `scripts/eval-weather-report-style.py` | Generate clips for 7 candidates × 7 voices × 9 locales |
| `scripts/compare-persona-voices.py` | Render one persona's real prompt in several voices, N rolls each |
| `scripts/rate-tts-clips.py` | Play each clip with `aplay`, prompt for a score + notes |
| `scripts/say.py` | One-shot helper for rapid prompt iteration (prints / plays a single clip) |
