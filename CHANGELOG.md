# Changelog

Versions are tagged `vX.Y` on `main`. Dates are DD.MM.YYYY.

## 0.19 (20.10.2026)
- Notifications held overnight are shown at 08:00 even while the phone sleeps: an Android alarm
  (allowed while idle, may be a few minutes late) next to the WorkManager job, which stays as a
  backup. On 20.10.2026 the job waited until the phone was woken at 08:06.
- If another app removes a notification right after it is shown, "Last background check" says so
  and names the apps allowed to remove notifications. On 20.10.2026 a launcher's notification
  filter removed "Economy: Rate shock" after 0.24 s, so it never reached the notification shade.
- Settings > Test checks again 2 seconds after posting, so a notification another app removes is
  no longer reported as "Shown".
- The economy notification shows only the note of the flag that just turned on, not every
  economy note.
- Tapping a notification opens MarketWatcher.

## 0.18 (20.10.2026)
- No changes to the app. CLAUDE.md records the way of working: which Claude tool is used for
  what, the GitHub build as the default check (read with gh), local builds only to install on
  the phone or to debug, device tests on the phone over USB (no emulator), and cloning
  MarketWatcher-data at the start of each session.
- `gradle/gradle-daemon-jvm.properties` added: pins JVM 21 for the Gradle daemon.

## 0.17 (16.10.2026)
- All market data is in the app again after Git > Pull and Run, with no separate clone: the build
  downloads the files named in `market-data.properties` (commit and sha256 of each) from the
  private MarketWatcher-data repository once, with a read-only token in `local.properties`. In
  0.16 a build without a MarketWatcher-data clone next to the project silently lost the
  2007–2016 history and the CAPE file.
- A build without the data says so: a Gradle warning, and one line in History and Settings.
- "First wave fading" is now "This wave fading" (state name, its description, the "why" list
  and the VIX / VIX3M info text). Engine and golden data unchanged.
- Device tests run against a separate app, "MarketWatcher test" (`fi.marketwatcher.test`), so they
  no longer clear the everyday app's API key and settings: `connectedInstrumentedAndroidTest`.
- GitHub build: requires the data, prints a test summary on the pull request, also compiles the
  device tests, and uses current versions of the actions.

## 0.16 (16.10.2026)
- The S&P 500 close no longer waits for VIX: FRED adds it about 03:00 Helsinki time, VIX/VIX3M
  about 16:40 the next day. Price-only days update the day change, S&P 500 and Trend tiles; the
  Suggested state stays on the last day with VIX and shows its date.
- Today: "S&P 500 day change" group below the Suggested box (Close, Prev. close, Day change;
  amber at −2.00% or worse; Sharp / Very sharp / Extreme fall at −2 / −3 / −5%).
- History: Day change tile in the S&P 500 group.
- Early background check on US weekday nights at 20:30 New York time (03:30 Helsinki), S&P 500
  only, retrying hourly until 10:30 Helsinki.
- "Sharp falls" notification (once per trading day, on by default, with a Test button).
- All notifications are shown only 08:00–18:00 phone time; others are held until 08:00.
- Repository: market data moved to the private MarketWatcher-data repository; GitHub build and
  unit tests on every pull request; Python reference engine in `python/`.

## 0.15 (15.10.2026)
- Credit stress (NFCI): above 0, or at −0.300 or higher and up +0.050 in 1 week, +0.115 in 4
  weeks or +0.200 in 8 weeks (148 weeks on in 2007–2026, 7 turn-ons). The NFCI pop-up shows the
  three windows.
- History: Recovery time tile (all-time high, date it was reached again, "X years, Y months") and
  the "Ingredients of economic bubbles" card (Heikki Keskiväli, 13.12.2020).
- Today: day scroller under the past-year chart with the selected day's six price and trend tiles.

## 0.14
- Credit stress: NFCI above 0 or up 0.05 over 4 weeks, compared in thousandths.
- History: "What happened" notes for the year chips.

## Earlier
See the README for features up to 0.13 (encrypted API key, notification tests, CAPE, yield curve,
2-year yield, 30-minute download rule and more).
