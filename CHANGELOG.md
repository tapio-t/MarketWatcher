# Changelog

Versions are tagged `vX.Y` on `main`. Dates are DD.MM.YYYY.

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
