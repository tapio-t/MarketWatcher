# Working on MarketWatcher (instructions for Claude)

Personal Android app (Kotlin, Jetpack Compose) that describes the S&P 500 market state from free
FRED data as "Suggested: <State>". The owner, Tapio, uses it on his own phone and shows this public
repository on LinkedIn.

## How Tapio works
- He usually asks for a **proposal first** ("propose, don't implement"). Show the plan (and code
  when asked), then wait for **"Implement"**.
- End every reply that needs his input with a numbered list **"Questions that need your
  answering before implementation"**, each with a suggested answer, so he can reply briefly
  (e.g. "1 yes, 2 no, Implement").
- Every change goes on a branch `vX.Y-<topic>` and comes as a **pull request**. Tapio merges; never
  push to `main`. After he merges, tag `vX.Y` on the merge commit.
- In each pull request: raise `versionCode`/`versionName` in `app/build.gradle.kts`, add a
  `CHANGELOG.md` entry, and describe what changed, what was verified, what was not, and a short
  checklist to try on the phone.
- Wait for the GitHub build (`.github/workflows/android.yml`) and fix failures before asking him
  to review.

## Tools
- Claude Code in Android Studio (official Claude Code [Beta] plugin): code changes, backtests,
  engine research, pull requests.
- Claude in the browser or the desktop app: conversations, prototypes (artifacts) and market
  research. Decisions from there are brought back to Claude Code and recorded in NOTES.md
  (MarketWatcher-data) with the date; prototype links go under "Published prototypes".

## Session start
- If `../MarketWatcher-data` is missing, clone https://github.com/tapio-t/MarketWatcher-data
  next to this project; otherwise `git pull` it. Read its NOTES.md before proposing changes.

## Builds and tests
- Rely on the GitHub build ("Build and test") by default. Read the result with gh:
  `gh pr checks`, the notes (test summary, Gradle errors) with
  `gh api repos/tapio-t/MarketWatcher/check-runs/<id>/annotations`, and failures with
  `gh run view <run> --log-failed`.
- Build locally only to install on Tapio's phone or to debug a failure the notes don't explain.
  The computer is not powerful: no parallel or repeated local builds.
- To install on the phone, Tapio presses Run in Android Studio. Suggest `gradlew installDebug`
  only when he asks.
- Never use an emulator. Device tests run on Tapio's phone over USB (`adb devices` first):
  `gradlew connectedInstrumentedAndroidTest`, which installs the separate MarketWatcher test app
  (`fi.marketwatcher.test`), never the everyday app.

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
  Then update the commit and the sha256 values in `market-data.properties` in the same pull
  request. For local work, `marketwatcher.dataDir=../MarketWatcher-data` in `local.properties`.
  `SignalEngineGoldenTest` must pass: it fails on the first day that differs over the 4,710 days
  (2008–2026). Check the "Unit tests" summary notes on the GitHub build (none skipped).

## Background
- Decisions, open ideas, prototype links and the full archive of the earlier work: `NOTES.md` in
  the private MarketWatcher-data repository (see Session start).

## Data and secrets
- Copyrighted market data (S&P 500, VIX, NFCI history, Shiller CAPE, golden data, backtest CSVs)
  lives only in the private `tapio-t/MarketWatcher-data` repository. The build downloads the files
  named in `market-data.properties` with a read-only token (`marketwatcher.dataToken` in
  `local.properties`; `DATA_TOKEN` secret on GitHub). Never add data files to this public
  repository (`.gitignore` blocks the usual paths).
- Device tests clear the API key and settings: they run against the separate app
  `fi.marketwatcher.test` (build type `instrumented`), never the everyday `fi.marketwatcher`.
- The FRED API key is only on the phone (Android Keystore). Never put keys, tokens, keystores or
  `local.properties` in the repository.
