"""Run the fog pile-up sweep and write results.md, results.json and a chart.

    python sim/run_sweep.py --runs 40

Two driver profiles bracket real behaviour. "Typical in fog" follows tail
lights closely with slow reactions; "Careful" keeps longer gaps, drives slower
and reacts faster. Neither is calibrated to Indian crash data: they show how
the benefit of early warning depends on driver behaviour and on how many
vehicles carry the app.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from fogsim import Params, simulate

PROFILES = {
    "typical": dict(speed_kmh=(55.0, 95.0), headway_s=(0.6, 1.6), reaction_s=(1.0, 2.0)),
    "careful": dict(speed_kmh=(40.0, 70.0), headway_s=(1.2, 2.5), reaction_s=(0.8, 1.5)),
}
SHARES = (0.0, 0.1, 0.25, 0.5, 0.75, 1.0)
VISIBILITIES = (25.0, 40.0, 60.0)
SERIES = ["#2a78d6", "#eb6834", "#1baf7a"]  # validated categorical slots 1 to 3
MARKERS = ["o", "s", "^"]


def run(runs: int):
    rows = []
    for profile, kw in PROFILES.items():
        for vis in VISIBILITIES:
            for share in SHARES:
                res = [simulate(Params(visibility_m=vis, equipped=share, **kw), seed=s) for s in range(runs)]
                cols = np.array([r.collisions for r in res])
                severe = np.array([sum(1 for i in r.impact_speeds_kmh if i >= 30) for r in res])
                rows.append({"profile": profile, "visibility_m": vis, "equipped": share, "runs": runs,
                             "collisions_mean": float(cols.mean()), "collisions_sd": float(cols.std()),
                             "severe_mean": float(severe.mean())})
                print(rows[-1])
    return rows


def table(rows, profile: str) -> str:
    head = "| visibility | " + " | ".join(f"{int(s * 100)}% equipped" for s in SHARES) + " |"
    sep = "|---|" + "---:|" * len(SHARES)
    out = [head, sep]
    for vis in VISIBILITIES:
        cells = []
        for s in SHARES:
            r = next(r for r in rows if r["profile"] == profile and r["visibility_m"] == vis and r["equipped"] == s)
            cells.append(f"{r['collisions_mean']:.1f}")
        out.append(f"| {int(vis)} m | " + " | ".join(cells) + " |")
    return "\n".join(out)


def chart(rows, path: Path, n_vehicles: int):
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    surface, ink, ink2, grid = "#fcfcfb", "#0b0b0b", "#52514e", "#e6e5e1"
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.2), sharey=True, facecolor=surface)
    for ax, profile, title in zip(axes, ("typical", "careful"), ("Typical driving in fog", "Careful driving in fog")):
        ax.set_facecolor(surface)
        for k, vis in enumerate(VISIBILITIES):
            pts = [(r["equipped"] * 100, r["collisions_mean"]) for r in rows
                   if r["profile"] == profile and r["visibility_m"] == vis]
            xs, ys = zip(*pts)
            ax.plot(xs, ys, color=SERIES[k], lw=2, marker=MARKERS[k], ms=7, label=f"{int(vis)} m visibility",
                    markeredgecolor=surface, markeredgewidth=1.5)
        ax.set_title(title, loc="left", fontsize=11, color=ink)
        ax.set_xlabel("Vehicles running Dhundh (%)", color=ink2, fontsize=9)
        ax.set_xlim(-3, 103)
        ax.set_ylim(0, n_vehicles)
        ax.grid(axis="y", color=grid, lw=0.8)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        for side in ("left", "bottom"):
            ax.spines[side].set_color(grid)
        ax.tick_params(colors=ink2, labelsize=8)
    axes[0].set_ylabel(f"Vehicles that crash (of {n_vehicles})", color=ink2, fontsize=9)
    axes[0].legend(frameon=False, fontsize=8, labelcolor=ink2, loc="upper right")
    fig.tight_layout()
    fig.savefig(path, dpi=160, facecolor=surface)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=40)
    ap.add_argument("--out", type=Path, default=Path("sim/results"))
    ap.add_argument("--chart-only", action="store_true", help="redraw the chart from results.json")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.chart_only:
        chart(json.loads((args.out / "results.json").read_text()), args.out / "collisions.png", Params().n_vehicles)
        return
    rows = run(args.runs)
    (args.out / "results.json").write_text(json.dumps(rows, indent=1))
    n = Params().n_vehicles
    md = [f"Mean number of vehicles (out of {n}) that crash, over {args.runs} random queues per cell.", "",
          "**Typical driving in fog**", "", table(rows, "typical"), "",
          "**Careful driving in fog**", "", table(rows, "careful"), ""]
    (args.out / "results.md").write_text("\n".join(md))
    chart(rows, args.out / "collisions.png", n)
    print("\n".join(md))


if __name__ == "__main__":
    main()
