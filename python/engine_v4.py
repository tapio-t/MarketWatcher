"""
Dip Watch engine v4 — reference implementation (Kotlin and JS are ports).

What changed from v2b
  * No buy signals and no buying plan. Every state is shown as "Suggested: <State>"
    and its text describes the market, never an action to buy or sell.
  * Drawdown is measured from the all-time high in the supplied history
    (v2b used a rolling 1-year high, which let long bears drift back to Calm).
  * UPTREND_RESTORED replaces RECOVERY and uses the rule validated on
    1950-2026: after a >= 10% fall that broke the 200-day SMA, 10 consecutive
    closes above a rising 200-day SMA.
  * NEW_WAVE (was SECOND_LEG) also fires when VIX exceeds the dip's earlier
    VIX peak after a fade, even while the 200-day SMA is still rising.
  * A context layer (credit stress, recession, rate shock, valuation) is
    reported next to the state. It does NOT change the state: it has not
    been backtested yet.

Daily inputs (Day):  spx, vix, vix3m                       (FRED SP500, VIXCLS, VXVCLS)
As-published context: nfci, nfci_prev                     (NFCI, weekly, +5 days)
                      sahm                                 (SAHMREALTIME, monthly)
                      dgs2, dgs2_6m                        (DGS2 today and ~126 trading days ago)
                      buffett, buffett_pct                 (NCBEILQ027S / GDP, quarterly, percentile)
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import List, Optional

# ------------------------------------------------------------ core thresholds
DIP_START = -0.07          # drawdown from all-time high that opens a dip
DIP_END = -0.03            # drawdown that closes it
BEAR_DD = -0.15
PANIC_RATIO = 1.00         # VIX / VIX3M >= 1: inverted curve
PANIC_VIX = 30.0
PANIC_VIX_PCT = 0.95
FADE_RATIO = 1.00
FADE_RATIO_DROP = 0.15     # or inversion down 0.15 from its dip peak
FADE_VIX_FRACTION = 0.80
LOW_HELD_DAYS = 3
BOUNCE_OFF_LOW = 0.02
RELAPSE_RATIO = 1.05       # re-inversion needed after a fade
RELAPSE_VIX_FRACTION = 0.90
UPTREND_MIN_DD = -0.10     # uptrend rule needs a dip of at least 10%
UPTREND_DAYS = 10          # consecutive closes above a rising SMA200
ENTER_DAYS = 2             # hysteresis for non-urgent states
LOOKBACK = 252
SMA_LEN = 200
SMA_SLOPE_DAYS = 20

# ------------------------------------------------------------ context thresholds (not backtested)
SAHM_ON = 0.50
CREDIT_FLOOR = -0.300      # a rise counts only when NFCI is at or above this
CREDIT_RISES = ((1, 0.050), (4, 0.115), (8, 0.200))   # (weeks, minimum rise) in v0.15
RATE_SHOCK_PP = 1.00       # 2-year yield up >= 1.00 percentage point in ~6 months
VALUATION_PCT = 0.90       # Buffett ratio in the top 10% of its own history

STATES = ["CALM", "PULLBACK", "PANIC", "FIRST_WAVE_FADING", "NEW_WAVE",
          "BEAR_GRIND", "UPTREND_RESTORED"]
IMMEDIATE = {"PANIC", "NEW_WAVE"}

TITLES = {
    "CALM": "No panic",
    "PULLBACK": "Pullback",
    "PANIC": "Panic",
    "FIRST_WAVE_FADING": "This wave fading",
    "NEW_WAVE": "New wave",
    "BEAR_GRIND": "Bear grind",
    "UPTREND_RESTORED": "Uptrend restored",
}

MESSAGES = {
    "CALM": "No dip. The S&P 500 is within 7% of its all-time high.",
    "PULLBACK": "A routine pullback. Declines of this size happen in most years.",
    "PANIC": "Panic selling: the volatility curve is inverted or VIX is extreme. "
             "The low may be days away or much further down.",
    "FIRST_WAVE_FADING": "This wave of fear is fading and the index has "
                         "stopped making new lows. In 2008-2026 about 4 in 10 of "
                         "these were followed by another leg down.",
    "NEW_WAVE": "Fear has returned after it faded: a new VIX high or a new low "
                "in a downtrend. The earlier fading did not hold.",
    "BEAR_GRIND": "A slow decline without panic, below a falling 200-day average. "
                  "Panic-based readings do not apply here.",
    "UPTREND_RESTORED": "The index has closed above a rising 200-day average for 10 "
                        "days after a fall of 10% or more. Since 1950 this was "
                        "followed by less than 5% further decline in 18 of 22 dips.",
}


def label(state: str) -> str:
    return f"Suggested: {TITLES[state]}"


@dataclass
class Day:
    date: str
    spx: float
    vix: float
    vix3m: float
    nfci: Optional[float] = None
    nfci_prev: Optional[float] = None
    nfci_4w: Optional[float] = None    # published NFCI 4 weeks earlier
    nfci_8w: Optional[float] = None    # published NFCI 8 weeks earlier
    sahm: Optional[float] = None
    dgs2: Optional[float] = None
    dgs2_6m: Optional[float] = None
    buffett: Optional[float] = None
    buffett_pct: Optional[float] = None


@dataclass
class Context:
    credit_stress: Optional[bool]
    recession: Optional[bool]
    rate_shock: Optional[bool]
    valuation_high: Optional[bool]
    notes: List[str] = field(default_factory=list)


@dataclass
class Reading:
    date: str
    state: str
    raw_state: str
    label: str
    message: str
    spx: float
    vix: float
    vix3m: float
    drawdown: float
    ath: float
    vix_ratio: float
    vix_pct: float
    sma200: Optional[float]
    sma200_rising: Optional[bool]
    dist_sma200: Optional[float]
    episode_active: bool
    episode_low: Optional[float]
    episode_max_dd: Optional[float]
    episode_vix_peak: Optional[float]
    days_since_low: Optional[int]
    uptrend_run: int
    context: Context
    reasons: List[str] = field(default_factory=list)

    def to_dict(self):
        return asdict(self)


def credit_rises(d: Day):
    """Windows whose rise meets its minimum, e.g. ["4 weeks +0.119"]."""
    if d.nfci is None:
        return []
    out = []
    for (weeks, minimum), prev in zip(CREDIT_RISES, (d.nfci_prev, d.nfci_4w, d.nfci_8w)):
        if prev is not None and round((d.nfci - prev) * 1000) >= round(minimum * 1000):
            out.append(f"{weeks} week{'s' if weeks > 1 else ''} {d.nfci - prev:+.3f}")
    return out


def context_for(d: Day) -> Context:
    # Credit stress (v0.15): tighter than average (NFCI > 0), or NFCI at or above -0.300 and a rise of
    # +0.050 in 1 week, +0.115 in 4 weeks or +0.200 in 8 weeks, compared in thousandths.
    rises = credit_rises(d)
    credit = None if d.nfci is None else (
        d.nfci > 0 or (round(d.nfci * 1000) >= round(CREDIT_FLOOR * 1000) and len(rises) > 0))
    rec = None if d.sahm is None else d.sahm >= SAHM_ON
    rate = None if d.dgs2 is None or d.dgs2_6m is None else (
        d.dgs2 - d.dgs2_6m >= RATE_SHOCK_PP)
    val = None if d.buffett_pct is None else d.buffett_pct >= VALUATION_PCT
    notes = []
    if credit:
        if d.nfci > 0:
            notes.append(f"Financial conditions are tighter than average (NFCI {d.nfci:+.3f}).")
        else:
            notes.append(f"Financial stress is rising fast (NFCI {d.nfci:+.3f}; " + ", ".join(rises) + ").")
    if rec:
        notes.append(f"Recession indicator is on (real-time Sahm rule {d.sahm:.2f}). "
                     "In 2008 every fading panic was followed by further declines.")
    if rate:
        notes.append(f"Rate shock: 2-year yield {d.dgs2:.2f}%, up "
                     f"{d.dgs2 - d.dgs2_6m:+.2f} points in about 6 months (2022-type pressure).")
    if val:
        notes.append(f"Valuation is high: stock market value / GDP {d.buffett:.0%}, "
                     f"in the top {100 - round(d.buffett_pct * 100)}% of its history. "
                     "Context only, not a timing signal.")
    return Context(credit, rec, rate, val, notes)


def _pct_rank(window: List[float], x: float) -> float:
    return sum(1 for v in window if v <= x) / len(window)


def run(days: List[Day]) -> List[Reading]:
    """Evaluate each day using only data up to that day. Readings start at
    index LOOKBACK-1. VIX3M may be NaN before it exists; only warm-up days may lack it."""
    out: List[Reading] = []
    closes = [d.spx for d in days]
    vixes = [d.vix for d in days]
    pref = [0.0]
    for c in closes:
        pref.append(pref[-1] + c)

    def sma(i: int) -> Optional[float]:
        if i < SMA_LEN - 1:
            return None
        return (pref[i + 1] - pref[i + 1 - SMA_LEN]) / SMA_LEN

    ath = 0.0
    active = False
    ep_low = ep_low_idx = ep_peak_vix = ep_start_idx = None
    ep_max_dd = 0.0
    ep_peak_ratio = 0.0
    peak_at_fade = None
    had_panic = had_fade = relapsed = broke_sma = restored = False
    up_run = 0

    state = "CALM"
    pending, pending_count = None, 0

    for i, d in enumerate(days):
        ath = max(ath, d.spx)
        s = sma(i)
        s_prev = sma(i - SMA_SLOPE_DAYS) if i >= SMA_SLOPE_DAYS else None
        rising = (s > s_prev) if (s is not None and s_prev is not None) else None
        if s is not None and d.spx > s and rising:
            up_run += 1
        else:
            up_run = 0
        if i < LOOKBACK - 1:
            continue

        dd = d.spx / ath - 1
        ratio = d.vix / d.vix3m
        vpct = _pct_rank(vixes[i - LOOKBACK + 1: i + 1], d.vix)
        dist = (d.spx / s - 1) if s is not None else None
        below_falling = s is not None and d.spx < s and rising is False

        # ---- episode bookkeeping
        if not active and dd <= DIP_START:
            active = True
            ep_start_idx = i
            ep_low, ep_low_idx = d.spx, i
            ep_max_dd = dd
            # VIX peak counted from the all-time-high day (within the last year)
            j = max(i - LOOKBACK + 1, 0)
            hi_idx = max(range(j, i + 1), key=lambda k: closes[k])
            ep_peak_vix = max(vixes[hi_idx: i + 1])
            ep_peak_ratio = ratio
            peak_at_fade = None
            had_panic = had_fade = relapsed = broke_sma = restored = False
        elif active and dd > DIP_END:
            active = False
            ep_low = ep_low_idx = ep_peak_vix = ep_start_idx = None
            ep_max_dd = 0.0
            ep_peak_ratio = 0.0
            peak_at_fade = None
            had_panic = had_fade = relapsed = broke_sma = restored = False

        new_low = False
        if active:
            if d.spx < ep_low:
                ep_low, ep_low_idx, new_low = d.spx, i, True
            ep_max_dd = min(ep_max_dd, dd)
            ep_peak_vix = max(ep_peak_vix, d.vix)
            ep_peak_ratio = max(ep_peak_ratio, ratio)
            if s is not None and d.spx < s:
                broke_sma = True
        days_since_low = (i - ep_low_idx) if active else None

        reasons = [f"{dd:.1%} from the all-time high ({ath:,.0f})"]
        if s is not None:
            reasons.append(f"{'Above' if d.spx >= s else 'Below'} the 200-day average "
                           f"({dist:+.1%}), which is {'rising' if rising else 'falling'}")
        reasons.append(f"VIX {d.vix:.1f} ({vpct:.0%} percentile of the past year), "
                       f"VIX/VIX3M {ratio:.2f}")

        panic_now = (ratio >= PANIC_RATIO or
                     (d.vix >= PANIC_VIX and vpct >= PANIC_VIX_PCT))
        if not active:
            raw = "CALM"
        else:
            curve_ok = ratio < FADE_RATIO or ratio <= ep_peak_ratio - FADE_RATIO_DROP
            faded = (curve_ok and d.vix <= FADE_VIX_FRACTION * ep_peak_vix and
                     days_since_low >= LOW_HELD_DAYS and
                     d.spx >= ep_low * (1 + BOUNCE_OFF_LOW))
            new_vix_high = had_fade and peak_at_fade is not None and d.vix > peak_at_fade
            relapse = had_fade and (new_vix_high or (below_falling and (
                new_low or ratio >= RELAPSE_RATIO or
                d.vix >= RELAPSE_VIX_FRACTION * ep_peak_vix)))
            restored_now = (ep_max_dd <= UPTREND_MIN_DD and broke_sma and
                            up_run >= UPTREND_DAYS and not panic_now)
            if restored_now and not restored:
                # the dip's panic cycle is over: later turbulence in the same
                # (long) drawdown starts a fresh cycle with fresh peaks
                restored = True
                had_panic = had_fade = relapsed = broke_sma = False
                ep_peak_vix, ep_peak_ratio, peak_at_fade = d.vix, ratio, None
                ep_low, ep_low_idx = d.spx, i
                days_since_low = 0
            elif restored_now:
                pass
            elif restored and (s is None or d.spx < s or panic_now):
                restored = False          # lost the 200-day average or panic returned

            if relapse:
                raw = "NEW_WAVE"
                had_fade, relapsed = False, True
                reasons.append("After fading: " + (
                    f"VIX {d.vix:.1f} above its level at the fade ({peak_at_fade:.1f})"
                    if new_vix_high else
                    "new low or renewed fear below a falling 200-day average"))
            elif restored:
                raw = "UPTREND_RESTORED"
                reasons.append(f"{up_run} closes above a rising 200-day average after a "
                               f"{ep_max_dd:.0%} fall; {d.spx / ep_low - 1:+.1%} off the low")
            elif had_panic and faded:
                raw = "FIRST_WAVE_FADING"
                if not had_fade:
                    peak_at_fade = ep_peak_vix
                had_fade, relapsed = True, False
                reasons.append(f"VIX at {d.vix / ep_peak_vix:.0%} of its dip peak "
                               f"{ep_peak_vix:.1f}; no new low for {days_since_low} days; "
                               f"{d.spx / ep_low - 1:+.1%} off the low")
            elif panic_now and (not had_fade or ratio >= RELAPSE_RATIO or
                                (d.vix >= PANIC_VIX and vpct >= PANIC_VIX_PCT)):
                raw = "NEW_WAVE" if relapsed else "PANIC"
                had_panic, had_fade = True, False
                reasons.append("Volatility curve inverted (VIX above VIX3M)"
                               if ratio >= 1 else "VIX in the top 5% of its past year")
            elif had_fade:
                raw = "FIRST_WAVE_FADING"
                reasons.append("Fear faded earlier and has not returned")
            elif had_panic:
                raw = "NEW_WAVE" if relapsed else "PANIC"
                reasons.append("Panic seen in this dip; fading not confirmed yet")
            elif dd <= BEAR_DD and below_falling:
                raw = "BEAR_GRIND"
                reasons.append("Below a falling 200-day average without panic")
            else:
                raw = "PULLBACK"

        # ---- hysteresis
        if raw == state:
            pending, pending_count = None, 0
        elif raw in IMMEDIATE:
            state, pending, pending_count = raw, None, 0
        else:
            if raw == pending:
                pending_count += 1
            else:
                pending, pending_count = raw, 1
            if pending_count >= ENTER_DAYS:
                state, pending, pending_count = raw, None, 0

        out.append(Reading(
            date=d.date, state=state, raw_state=raw, label=label(state),
            message=MESSAGES[state], spx=d.spx, vix=d.vix, vix3m=d.vix3m,
            drawdown=dd, ath=ath, vix_ratio=ratio, vix_pct=vpct, sma200=s,
            sma200_rising=rising, dist_sma200=dist, episode_active=active,
            episode_low=ep_low, episode_max_dd=ep_max_dd if active else None,
            episode_vix_peak=ep_peak_vix, days_since_low=days_since_low,
            uptrend_run=up_run, context=context_for(d), reasons=reasons))
    return out
