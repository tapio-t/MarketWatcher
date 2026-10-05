# MarketWatcher (Android, personal use)

Shows the S&P 500 market state as "Suggested: <State>" with the underlying numbers, a one-year
chart, and a landscape 2008–today replay. Uses free FRED data. No buy or sell signals.
Not investment advice.

## Screens

- **Today** (portrait)
  - App name, and "Data checked 08:45 02.10.2026": when FRED was last downloaded (the date of
    each value is under its tile). Opening or returning to the app downloads only if that is over
    30 minutes ago; pull down to download now.
  - States: Panic, First wave fading, New wave, Bear grind, Pullback, Uptrend restored, and
    **No panic** (not in a dip; called "Calm" before 0.12, internal name `CALM`).
  - "Suggested: <State>" box. Tap it to see why: the engine's rules in checking order, each
    marked ✓ (decided), ✗ (checked, not met) or · (not reached), with that day's numbers.
    The pop-up closes with the Close button at the bottom, back, or a tap outside it.
  - Values, each with the date of its observation under it:
    S&P 500 (close, all-time high, from high), Trend (200-day average, distance, direction),
    Volatility (VIX, VIX3M, VIX/VIX3M), and Economy in two rows:

    | Sahm rule | NFCI | Buffett ratio |
    |---|---|---|
    | Yield curve 10y–2y (T10Y2Y), with "last inverted DD.MM.YYYY" (or "inverted since …") | 2-year yield (DGS2), with its 6-month change | CAPE (total return) |

    Amber edges: "vs average" at 0.0% or below (as shown, rounded to 0.1%); "Average is" when
    Falling (200-day average lower than 20 trading days ago, the engine's own test); VIX at 30+ and in the top 5% of its past year (the VIX part of the Panic rule);
    VIX / VIX3M at 1.00+ (backwardation); Sahm rule at 0.50+; NFCI above 0 or up 0.05+ over 4 weeks (Credit stress; until 0.13 any weekly rise
    counted, which was on in 46% of weeks, now 22%); Buffett
    ratio in its top 10%; yield curve inverted (below 0); 2-year yield up 1.00 point or more in
    about 6 months (Rate shock); CAPE in the top 10% of its history since 1881.
    Every amber-capable tile shows ⓘ: tap for a pop-up with the value and date, what it means,
    how MarketWatcher uses it, whether its condition is on, and a source link.
    CAPE: Robert Shiller's official monthly value; from his latest month on, an estimate (≈) =
    latest official value × S&P 500 change since that month.
  - Past-year S&P 500 chart coloured by state, with the dashed 200-day average. The legend under
    it shows both latest values with their dates on their own lines.
  - Link to the full TradingView SPX chart: https://www.tradingview.com/chart/?symbol=SP%3ASPX
  - Quote (italic): "It is imminent — the only thing that's unknown is the timing, location,
    duration, magnitude, policy response, recovery dynamics, and cultural impact." — Morgan Housel
  - Pull down to refresh.
- **History** (landscape, navigation rail, scrolls up and down)
  - Chips: Today and 2008, 2009, 2011, 2018, 2020, 2022, 2025. Each year chip jumps to that
    year's lowest close.
  - 2008–today chart: state bands, S&P 500 (log) with 200-day average, VIX and VIX3M.
    Tap or drag sideways on the chart to pick a day.
  - Scrub bar with ‹ › buttons (tap = one day, hold = repeat), date and state of the selected day.
  - Directly above the Suggested box, while a year chip is selected: a **What happened** card
    with that year's lowest close (computed: date, close, fall from the high) and 2–3 sentences
    on why the market was there (`ui/YearNotes.kt`). It closes when you move the selection.
  - The selected day's "Suggested" box (tap for why), all its values with dates (same amber
    edges and ⓘ pop-ups as Today, for the selected day), and its past-year chart with legend.
- **Settings**
  - Data sources (bundled files, FRED series, how they combine), with the CAPE status and
    **Check now** (shillerdata.com) / **Import file** (a downloaded `ie_data.xls`).
  - FRED API key (see Security): password field with Show/Hide, 32-character format check, and a
    check with FRED before saving. After saving only "•••• •••• 1a2b" is shown, with
    **Replace key** and **Remove key**.
  - Notifications for state changes and economy flags, and for the S&P 500 closing below its
    200-day average (once per crossing). Each has a **Test** button (see below), and the last
    background check is shown.
  - Data sources: the bundled history file, the FRED files kept on the phone, and how they are
    combined.

## Security

- **API key encrypted.**
- **Automatic migration from plain text** (versions up to 0.10). At the start of the first data
  load (app or background check, whichever comes first), off the main thread: encrypt, read
  back, and only then delete the plain-text entry (`commit()`). If anything fails, the plain-text
  key keeps working and the move is retried next time. Nothing to do by hand.
- **Backups.** Cloud backup and device transfer exclude `marketwatcher_secrets.xml` and
  `marketwatcher_settings.xml` (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`).
  Backups stay on: Android keeps only the latest backup, so the next nightly backup replaces any
  older one that still held the plain-text key. A key restored onto another phone can't be
  decrypted there; the app then asks for it again.
- **Error texts** shown on screen or saved as the last background check pass through `Redact`,
  which replaces any `api_key=…` or key-shaped value with `***`. Requests never follow redirects.
- **Network:** HTTPS only to `api.stlouisfed.org`; cleartext traffic disabled in the manifest.
- **Robust parsing:** an unparseable FRED value is skipped instead of failing the refresh.
- **Lock screen:** notifications show only "MarketWatcher · Market update" until unlocked.
- **Links** inside pop-up text open through the same crash-safe handler as buttons.
- **Screen privacy while typing:** while the key field is shown, screenshots and the Recents
  preview are blocked (`FLAG_SECURE`).
- **One lock** guards saving, loading, migration and key creation, so the background check can
  never overwrite a key you are saving at the same moment. A temporary Keystore error never
  deletes the saved key; only a key that can truly never be decrypted is removed.
- **Gradle download check (one step by you):** add the official checksum for
  `gradle-9.3.1-bin.zip` from https://gradle.org/release-checksums/ as
  `distributionSha256Sum=…` in `gradle/wrapper/gradle-wrapper.properties`. Gradle then refuses a
  tampered download. (The checksum couldn't be fetched when this version was made.)
- Not changed on purpose: debug builds from Android Studio can be read with `adb run-as` by
  anyone with USB-debugging access to your unlocked phone.

## Notifications

- Two notification types (Android channels), both **high importance** so they pop up as banners:
  "State and economy" and "Below 200-day average". The old "Market state" type (default
  importance, no banners) is removed on start; an app cannot raise a type's importance itself.
- Notification permission (Android 13+) is asked for only when a notification switch is turned
  on or **Test** is tapped. If Android no longer shows the request, the app opens its
  notification settings instead.
- The rules live in one pure function (`work/NotificationRules.kt`), used by both the real
  twice-daily background check and the tests.

**Test** (one per switch) works at once, offline too:

1. Builds the notification from the latest data in the app, simulating only the previous check:
   a different state (Panic, or No panic if today is Panic) and no flags; for the 200-day test, a
   previous close above the average. If today's close is above the average, a simulated close 2%
   below it is used; if no economy flag is on, a test flag.
2. Runs the same rules and posts "Test: …" on the same notification type as the real one, with
   separate notification IDs. Nothing is saved, so real notifications are unaffected.
3. Checks Android's list of active notifications and reports under the switch: **Shown**;
   posted but hidden by Do Not Disturb; posted but not shown; or blocked (permission, app
   notifications off, or this type off), with a button to open notification settings.

Settings also shows the **last background check**: when it ran and what it did (no change,
notified, blocked, failed).

## Why the History slider is smooth

- The selected day is one `MutableIntState`. Only the small parts that show it (day line,
  Suggested box, values, legend) recompose; the charts read it while drawing.
- The big chart is drawn once into a bitmap (per size, data and theme) and copied each frame;
  only the cursor is redrawn.
- Sideways drags are claimed after the touch slop, so the page's vertical scroll can't steal them.
- The selection ticks the phone (haptic) at each new year.
- Judge smoothness in a release build: debug builds of Compose apps are much slower.

## Toolchain

Works on Android Studio versions that support AGP 9.1 and on newer ones.

| Part | Version |
|---|---|
| Gradle (wrapper) | 9.3.1 |
| Android Gradle plugin | 9.1.0 (built-in Kotlin, no `kotlin-android` plugin) |
| Kotlin / Compose compiler plugin | 2.3.20 |
| Compose BOM | 2026.06.01 (Compose 1.11) |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| JDK for Gradle | 17 or newer (Android Studio's bundled JetBrains Runtime works) |

Compose 1.12 (BOM 2026.08.00 or newer) needs AGP 9.1.1 and compileSdk 37; update all three
together once your Android Studio supports AGP 9.1.1.

## Open and build

1. Unzip. In Android Studio choose **File > Open** and select the **MarketWatcher** folder
   (the one containing `settings.gradle.kts`).
2. **Settings > Build, Execution, Deployment > Build Tools > Gradle**:
   *Distribution* = **Wrapper**, *Gradle JDK* = the bundled **jbr** (or any JDK 17+).
3. Let the first sync finish without closing the project (it downloads Gradle 9.3.1 and the
   libraries). If it reports a missing SDK platform or build tools, click the install link.
4. Run on a phone with Android 8.0+. Paste your free FRED API key in **Settings**.
5. Tests: `./gradlew test`
   - golden test: 4,710 days must match the Python reference engine;
   - explanation test: on every day the "why" list names exactly one deciding rule, and it gives
     the engine's state;
   - notification rules: state changes (No panic ↔ Pullback quiet), new economy flags, once per
     crossing below the 200-day average, switches, both test scenarios, and test channels;
   - VIX / VIX3M tile: amber on exactly the 478 days at 1.00 or above in 2008–2026;
   - redaction: API keys are removed from error texts.
6. On a phone or emulator: `./gradlew connectedAndroidTest` checks that both notification types
   are high importance, the old one is gone, a test notification is posted and listed, and in
   History the VIX / VIX3M tile is amber on 24.12.2018 (1.25) and not on 22.09.2026 (0.81),
   and the key is stored only encrypted, tampering is handled, migration deletes the plain text
   from disk, and both backup rule files exclude the key files.

The application ID is `fi.marketwatcher`. It installs next to the older Dip Watch app
(`fi.dipwatch`), which you can uninstall; enter the FRED key once in MarketWatcher.

## Data

`app/src/main/assets/cape_tr_shiller.json`: Robert Shiller's monthly total return CAPE and price
from `ie_data.xls` (shillerdata.com; Data sheet columns A, B and O), 1881-01 to 2026-09. The app
shows it from 2008; earlier months only rank values for the top-10% rule. The newest months are
provisional (September 2026 uses the 1 September close; recent CPI is estimated). Updates: the
background check looks weekly for a new file on shillerdata.com (the link changes with each
update, so the app finds it on the page) and keeps it only if complete and not older; or import
the file in Settings. The `.xls` is read by `ShillerXls.kt` (no library), which reads only the
Data sheet's numbers.

DGS2 and T10Y2Y are fetched from January 2007 so History shows them from 2008; a cache that
doesn't reach back far enough is backfilled once. Each refresh is 9 FRED requests.

`app/src/main/assets/replay_2007_2026.json` holds S&P 500, VIX, VIX3M and NFCI (with the NFCI
week date) from 2007-01-03 to 2026-09-22 (FRED keeps only ~10 years of S&P 500 data). Live FRED
data is appended after the last bundled date. Sahm rule, 2-year yield and Buffett ratio are
added to every day from FRED, using each value only from its publication date. Several series
are copyrighted: keep the app and data for personal use.
