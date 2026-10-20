"""Fog pile-up simulator: does phone-to-phone warning reduce collisions?

A queue of vehicles drives down one lane of an expressway in fog towards a
stopped vehicle. Each driver follows the Intelligent Driver Model (IDM,
Treiber, Hennecke and Helbing, 2000) with two changes for fog:

* a driver can only see the vehicle ahead when it is within the visibility
  distance; until then they drive at their own chosen speed, which in real
  fog is often too fast for what they can see;
* a driver reacts to what they see with a personal reaction time.

Vehicles running Dhundh broadcast a warning when they brake hard or stop.
Other Dhundh vehicles within radio range hear it after a short delay, pass it
on once (so it travels back along the queue), and their drivers start slowing
for the hazard before it comes out of the fog. Vehicles without Dhundh get
no warning, but they still benefit when an equipped car ahead slows early.

Everything here is a model. The numbers show how much difference early
warnings could make under stated assumptions, not what will happen on a
real road. All assumptions are parameters in `Params`.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np


@dataclass
class Params:
    n_vehicles: int = 40
    visibility_m: float = 40.0         # dense fog: 20 to 50 m
    speed_kmh: tuple = (55.0, 95.0)    # range of chosen speeds, uniform
    headway_s: tuple = (0.6, 1.6)      # gaps drivers keep when they can see (they follow tail lights)
    reaction_s: tuple = (1.0, 2.0)     # perception-reaction time, longer in fog
    max_brake: float = 7.5             # emergency braking limit for cars, m/s^2
    truck_share: float = 0.25          # trucks and buses: longer, brake less hard
    truck_brake: float = 4.5
    truck_length_m: float = 12.0
    comfort_brake: float = 2.5         # IDM comfortable deceleration b
    accel: float = 1.2                 # IDM maximum acceleration a
    min_gap_m: float = 2.5             # IDM jam distance s0
    length_m: float = 4.5              # car length
    equipped: float = 0.0              # share of vehicles running Dhundh
    radio_range_m: float = 300.0       # long-range Bluetooth between phones
    hop_latency_s: float = 0.4         # one broadcast-receive step, incl. retries
    alert_reaction_s: float = 1.2      # driver reacting to an alarm sound
    brake_trigger: float = 4.0         # m/s^2 that counts as hard braking
    warn_horizon_m: float = 800.0      # how far behind a hazard drivers act on it
    dt: float = 0.05
    duration_s: float = 120.0
    start_gap_m: tuple = (25.0, 70.0)  # initial spacing of the queue
    first_distance_m: float = 250.0    # first vehicle's distance to the stopped car
    look_ahead: int = 5                # vehicles ahead a driver can react to, if visible


@dataclass
class Result:
    collisions: int
    impact_speeds_kmh: list = field(default_factory=list)
    warned: int = 0
    first_warning_s: float | None = None


def simulate(p: Params, seed: int = 0) -> Result:
    rng = np.random.default_rng(seed)
    n = p.n_vehicles
    # Vehicle 0 is the stopped car; 1..n are the moving queue behind it.
    x = np.zeros(n + 1)
    v = np.zeros(n + 1)
    x[0] = 0.0
    gaps = rng.uniform(*p.start_gap_m, n)
    v0 = rng.uniform(*p.speed_kmh, n + 1) / 3.6
    v[1:] = v0[1:]
    T = rng.uniform(*p.headway_s, n + 1)
    tau = rng.uniform(*p.reaction_s, n + 1)
    equipped = rng.random(n + 1) < p.equipped
    truck = rng.random(n + 1) < p.truck_share
    L = np.where(truck, p.truck_length_m, p.length_m)  # L[i]: length of vehicle i
    bmax = np.where(truck, p.truck_brake, p.max_brake)

    x[1] = -p.first_distance_m
    for i in range(2, n + 1):
        x[i] = x[i - 1] - L[i] - gaps[i - 1]  # x is the front bumper; vehicle i spans [x-L, x]
    steps = int(p.duration_s / p.dt)
    hist = int(max(p.reaction_s) / p.dt) + 2
    xb = np.repeat(x[None, :], hist, axis=0)  # ring buffer of past positions
    vb = np.repeat(v[None, :], hist, axis=0)
    delay = np.clip(np.round(tau / p.dt).astype(int), 1, hist - 1)

    crashed = np.zeros(n + 1, bool)
    crashed[0] = True  # the stopped car is already an obstacle
    impacts = []
    # warning state
    heard_at = np.full(n + 1, np.inf)       # when the phone received a warning
    hazard_x = np.full(n + 1, np.inf)       # position of the nearest known hazard
    sent = np.zeros(n + 1, bool)            # sent a hard-brake or crash warning
    sent_stop = np.zeros(n + 1, bool)       # sent a stopped-vehicle warning
    pending = []                            # (deliver_time, from_x, hazard_pos)
    if equipped[0]:
        # the stopped car itself announces it is stopped on a fast road
        sent_stop[0] = True
        pending.append((p.hop_latency_s, x[0], x[0] - L[0]))
    prev_v = v.copy()
    first_warn = None

    idx = np.arange(1, n + 1)
    cumL = np.cumsum(L)  # cumL[k] = L[0] + ... + L[k]
    v_fog = bmax * (-tau + np.sqrt(tau ** 2 + 2 * p.visibility_m / bmax))
    sqrt_ab = 2 * np.sqrt(p.accel * p.comfort_brake)

    def idm(vv, dv, gap, TT, vv0):
        s_star = p.min_gap_m + np.maximum(0.0, vv * TT + vv * dv / sqrt_ab)
        return p.accel * (1 - (vv / vv0) ** 4 - (s_star / np.maximum(gap, 0.1)) ** 2)

    for k in range(steps):
        t = k * p.dt
        cur = k % hist
        xb[cur], vb[cur] = x, v

        # Each driver acts now on the scene as it was tau seconds ago, and
        # reacts to every vehicle ahead they could see then, not only the
        # nearest: in clear weather brake lights far up the road give early
        # notice; in fog only the nearest one or two vehicles are visible.
        past = np.where(k >= delay[idx], (k - delay[idx]) % hist, 0)
        own_x = xb[past, idx]
        own_v = vb[past, idx]
        vi, v0i, Ti, taui = v[idx], v0[idx], T[idx], tau[idx]
        a = p.accel * (1 - (vi / v0i) ** 4)
        for m in range(1, p.look_ahead + 1):
            j = idx - m
            ok = j >= 0
            jj = np.maximum(j, 0)
            lead_x = xb[past, jj]
            lead_v = vb[past, jj]
            dist = lead_x - L[jj] - own_x                       # to that vehicle's rear
            between = (cumL[idx - 1] - cumL[jj]) + (m - 1) * p.min_gap_m
            gap = dist - np.where(m > 1, between, 0.0)          # room left once the cars between are packed
            seen = ok & (dist < p.visibility_m)
            gap_now = np.maximum(0.1, gap - taui * (own_v - lead_v))  # anticipation (HDM)
            a = np.where(seen, np.minimum(a, idm(vi, vi - lead_v, gap_now, Ti, v0i)), a)
        # alerted drivers treat the reported hazard as a stopped vehicle
        alerted = equipped[idx] & (t >= heard_at[idx] + p.alert_reaction_s)
        hgap = hazard_x[idx] - x[idx]
        act = alerted & (hgap > 0) & (hgap < p.warn_horizon_m)
        a = np.where(act, np.minimum(a, idm(vi, vi, hgap, Ti, v0i)), a)
        a = np.where(act & (vi > v_fog[idx]), np.minimum(a, -p.comfort_brake), a)
        acc = np.zeros(n + 1)
        acc[idx] = np.where(crashed[idx], 0.0, np.maximum(a, -bmax[idx]))

        v_new = np.maximum(0.0, v + acc * p.dt)
        x = x + 0.5 * (v + v_new) * p.dt
        v = v_new

        # collisions: a vehicle reaches the one in front
        for i in range(1, n + 1):
            if crashed[i]:
                continue
            if x[i] >= x[i - 1] - L[i - 1]:
                impacts.append((v[i] - v[i - 1]) * 3.6)
                x[i] = x[i - 1] - L[i - 1]
                crashed[i] = True
                v[i] = 0.0
                if not crashed[i - 1]:
                    v[i - 1] = max(0.0, v[i - 1])

        # Dhundh: equipped vehicles that brake hard or stop broadcast once
        dec = (prev_v - v) / p.dt
        for i in range(1, n + 1):
            if equipped[i] and not sent[i] and (dec[i] >= p.brake_trigger or crashed[i]):
                sent[i] = True
                # the hazard is what the braking driver saw: roughly the car ahead
                pending.append((t + p.hop_latency_s, x[i], min(x[i] + p.visibility_m, x[i - 1] - L[i - 1])))
                first_warn = t if first_warn is None else first_warn
            # a vehicle that has come to a stop warns that it is now the end of the queue
            if equipped[i] and not sent_stop[i] and v[i] < 1.5 and t > 1.0:
                sent_stop[i] = True
                pending.append((t + p.hop_latency_s, x[i], x[i] - L[i]))
        if first_warn is None and pending:
            first_warn = pending[0][0] - p.hop_latency_s
        still = []
        for (td, src, hz) in pending:
            if td > t:
                still.append((td, src, hz))
                continue
            for r in range(1, n + 1):
                if equipped[r] and src - p.radio_range_m <= x[r] < src and hz < hazard_x[r]:
                    new = np.isinf(heard_at[r])
                    hazard_x[r] = hz
                    heard_at[r] = min(heard_at[r], t)
                    if new:  # relay once so the warning runs back along the queue
                        still.append((t + p.hop_latency_s, x[r], hz))
        pending = still
        prev_v = v.copy()
        if crashed[1:].all() or (v[1:] < 0.05).all():
            break

    return Result(collisions=int(crashed[1:].sum()), impact_speeds_kmh=impacts,
                  warned=int(np.isfinite(heard_at[1:]).sum()), first_warning_s=first_warn)


def sweep(shares=(0.0, 0.1, 0.25, 0.5, 0.75, 1.0), visibilities=(25.0, 40.0, 60.0), runs=40, **kw):
    """Average collisions and severe impacts over many random queues."""
    rows = []
    for vis in visibilities:
        for share in shares:
            cols, severe = [], []
            for s in range(runs):
                r = simulate(Params(visibility_m=vis, equipped=share, **kw), seed=s)
                cols.append(r.collisions)
                severe.append(sum(1 for i in r.impact_speeds_kmh if i >= 30))
            rows.append({"visibility_m": vis, "equipped": share,
                         "collisions_mean": float(np.mean(cols)),
                         "severe_mean": float(np.mean(severe)),
                         "runs": runs})
    return rows
