"""
Backtest and golden-data export for engine v4.

  python backtest_v4.py                         # 2008-2026 report (merged S&P file + NFCI)
  python backtest_v4.py --golden                # also rewrite the golden test data

The input data is in the private MarketWatcher-data repository, cloned next to this one
(../../MarketWatcher-data from this folder; set MARKETWATCHER_DATA to use another place).
The golden file is written to MarketWatcher-data/test-resources/golden_v4.json, which the
Kotlin SignalEngineGoldenTest reads.

Context series other than NFCI (Sahm, DGS2, Buffett ratio) are not in the
offline files, so the backtest reports states only; the context layer does
not change states in v4 anyway.
"""
from __future__ import annotations

import csv
import json
import math
import os
import sys
from datetime import date, timedelta

import engine_v4 as E


def load_prices(path: str) -> list[E.Day]:
    days, seen = [], False
    with open(path, newline="") as f:
        rows = csv.reader(f)
        h = [x.strip().upper() for x in next(rows)]
        ix = {k: h.index(k) for k in ("SP500", "VIXCLS", "VXVCLS")}
        for r in rows:
            spx, vix, v3 = (r[ix[k]].strip() for k in ("SP500", "VIXCLS", "VXVCLS"))
            if spx in ("", ".") or vix in ("", "."):
                continue
            if v3 in ("", "."):
                if seen:
                    continue
                days.append(E.Day(r[0], float(spx), float(vix), float("nan")))
            else:
                seen = True
                days.append(E.Day(r[0], float(spx), float(vix), float(v3)))
    return days


def attach_weekly(days, path, col, lag_days, setter):
    obs = []
    with open(path, newline="") as f:
        rows = csv.reader(f)
        next(rows)
        for r in rows:
            if len(r) > 1 and r[1].strip() not in ("", "."):
                obs.append((date.fromisoformat(r[0]) + timedelta(days=lag_days), float(r[1])))
    obs.sort()
    k = -1
    for d in days:
        t = date.fromisoformat(d.date)
        while k + 1 < len(obs) and obs[k + 1][0] <= t:
            k += 1
        setter(d, obs, k)
    return days


def attach_nfci(days, path):
    def setter(d, obs, k):
        if k >= 1:
            d.nfci, d.nfci_prev = obs[k][1], obs[k - 1][1]
            d.nfci_4w = obs[k - 4][1] if k >= 4 else None
            d.nfci_8w = obs[k - 8][1] if k >= 8 else None
    return attach_weekly(days, path, "NFCI", 5, setter)


def episodes_ath(R):
    """Dips of >= 10% from the all-time high, lasting until a new high."""
    eps, peak_i, start, trough = [], 0, None, 0.0
    for i, r in enumerate(R):
        if r.spx >= r.ath:
            if start is not None and trough <= -0.10:
                eps.append((peak_i, i))
            peak_i, start, trough = i, None, 0.0
        else:
            start = start if start is not None else i
            trough = min(trough, r.drawdown)
    if start is not None and trough <= -0.10:
        eps.append((peak_i, len(R)))
    return eps


def first_state(R, a, b, state):
    return next((i for i in range(a, b) if R[i].state == state
                 and (i == 0 or R[i - 1].state != state)), None)


def report(R):
    print(f"{len(R)} readings {R[0].date} .. {R[-1].date}")
    print(f"\n{'dip (peak -> new high)':24} {'low date':10} {'maxDD':>6} | "
          f"{'first wave fading':17} {'vsLow':>6} {'further':>7} | "
          f"{'uptrend restored':16} {'vsLow':>6} {'further':>7}")
    rows = []
    for a, b in episodes_ath(R):
        lo = min(range(a, b), key=lambda k: R[k].spx)
        low = R[lo].spx
        cells = []
        rec = {"peak": R[a].date, "end": R[b - 1].date, "low_date": R[lo].date,
               "max_dd": low / R[a].spx - 1}
        for st in ("FIRST_WAVE_FADING", "UPTREND_RESTORED"):
            i = first_state(R, a, b, st)
            if i is None:
                cells.append(f"{'none':17} {'':>6} {'':>7}")
                rec[st] = None
                continue
            fd = min(x.spx for x in R[i:b]) / R[i].spx - 1
            vl = R[i].spx / low - 1
            rec[st] = {"date": R[i].date, "vs_low": vl, "further": fd}
            cells.append(f"{R[i].date:17} {vl:+6.1%} {fd:+7.1%}")
        rows.append(rec)
        print(f"{R[a].date}->{R[b-1].date[:7]}   {R[lo].date} {rec['max_dd']:+6.1%} | "
              + " | ".join(cells))
    for st in ("FIRST_WAVE_FADING", "UPTREND_RESTORED"):
        xs = [r[st] for r in rows if r[st]]
        ok = sum(1 for x in xs if x["further"] > -0.05)
        bad = sum(1 for x in xs if x["further"] <= -0.10)
        print(f"\n{st}: {len(xs)} of {len(rows)} dips; fell <5% more: {ok}; fell >10% more: {bad}")
    return rows


def golden(days, R, path):
    """Inputs + expected outputs for the Kotlin / JS parity tests."""
    def num(x):
        return None if x is None or (isinstance(x, float) and math.isnan(x)) else round(x, 6)
    data = {
        "days": [[d.date, d.spx, d.vix, num(d.vix3m), d.nfci, d.nfci_prev, d.nfci_4w, d.nfci_8w] for d in days],
        "expected": [[r.date, r.state, r.raw_state, r.uptrend_run,
                      r.context.credit_stress] for r in R],
    }
    with open(path, "w") as f:
        json.dump(data, f, separators=(",", ":"))
    print(f"\ngolden data: {len(data['days'])} days, {len(data['expected'])} readings -> {path}")


DATA = os.environ.get("MARKETWATCHER_DATA",
                      os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "MarketWatcher-data"))

if __name__ == "__main__":
    src = os.path.join(DATA, "python-data")
    days = attach_nfci(load_prices(os.path.join(src, "sp500_vix_2007_2026.csv")), os.path.join(src, "nfci.csv"))
    R = E.run(days)
    rows = report(R)
    with open(os.path.join(src, "results_v4.json"), "w") as f:
        json.dump(rows, f, indent=1)
    t = R[-1]
    print(f"\nToday {t.date}: {t.label} — {t.message}")
    for n in t.context.notes:
        print("  context:", n)
    if "--golden" in sys.argv:
        golden(days, R, os.path.join(DATA, "test-resources", "golden_v4.json"))
