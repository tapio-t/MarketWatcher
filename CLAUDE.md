# Working on MarketWatcher (instructions for Claude)

Personal Android app (Kotlin, Jetpack Compose) that describes the S&P 500 market state from free
FRED data as "Suggested: <State>". The owner, Tapio, uses it on his own phone and shows this public
repository on LinkedIn.

## How Tapio works
- He usually asks for a **proposal first** ("propose, don't implement"). Show the plan (and code
  when asked), then wait for **"Implement"**.
- Every change goes on a branch `vX.Y-<topic>` and comes as a **pull request**. Tapio merges; never
  push to `main`. After he merges, tag `vX.Y` on the merge commit.
- In each pull request: raise `versionCode`/`versionName` in `app/build.gradle.kts`, add a
  `CHANGELOG.md` entry, and describe what changed, what was verified, what was not, and a short
  checklist to try on the phone.
- Wait for the GitHub build (`.github/workflows/android.yml`) and fix failures before asking him
  to review.

## Product rules
- No buy or sell signals or wording anywhere (a test checks the "What happened" notes).
  "Not investment advice."
- Dates on screen: DD.MM.YYYY; times HH:MM; real minus sign "−" in shown numbers.
- Amber tile edges mark conditions; thresholds compare values as shown (rounded), e.g. NFCI in
  thousandths, day change in hundredths of a percent.
- Notifications only 08:00–18:00 phone time (`work/QuietHours.kt`, `work/NotificationGate.kt`).
- The context layer (credit stress, recession, rate shock, valuation, CAPE, yield curve) never
  changes the state; it is not backtested.

## Engine changes
- `app/.../engine/SignalEngine.kt` is a line-by-line port of `python/engine_v4.py`. Change both.
- Regenerate the golden data with `python python/backtest_v4.py --golden` (writes
  `../MarketWatcher-data/test-resources/golden_v4.json`) and commit it in MarketWatcher-data.
  `SignalEngineGoldenTest` must report 0 mismatches over 4,710 days (2008–2026).

## Background
- Decisions, open ideas, prototype links and the full archive of the earlier work: `../MarketWatcher-data/NOTES.md`.

## Data and secrets
- Copyrighted market data (S&P 500, VIX, NFCI history, Shiller CAPE, golden data, backtest CSVs)
  lives only in the private `tapio-t/MarketWatcher-data` repository, cloned next to this one. Never
  add data files to this public repository (`.gitignore` blocks the usual paths).
- The FRED API key is only on the phone (Android Keystore). Never put keys, keystores or
  `local.properties` in the repository.
