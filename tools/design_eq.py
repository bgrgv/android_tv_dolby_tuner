#!/usr/bin/env python3
"""
Room-correction EQ design from measure.py sweeps.

  design_eq.py fit TARGET MEAS [--prev PREVGAINS.json] -> writes OUT/TARGET.json (edges, gains, input)
  design_eq.py push TARGET                              -> sends OUT/TARGET.json to the TV as a custom EQ
  design_eq.py check TARGET MEAS                        -> prints target vs measured, and the error

Targets are in-room curves in dB relative to the 300-3000 Hz average, on 1/3-octave centres.
Correction = target - measured, smoothed across neighbouring bands and clamped (boosts are
cheap to overdo; cuts fix room modes). Below the woofer's limit nothing is boosted.
"""
import json, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import measure as m

T = np.array(m.THIRDS, dtype=float)

TARGETS = {
    # Punchy 50-80 Hz for the woofer, controlled 100-250 Hz (no boom), flat mids, gentle air.
    "music": {50: 7, 63: 10, 80: 10, 100: 9, 125: 8, 160: 6, 200: 4, 250: 2, 315: 0, 4000: 0,
              5000: 1, 6300: 1, 8000: 1, 10000: 1, 12500: 1},
    # Speech: lean below 80 Hz, a little warmth at 100-250 Hz, presence at 2-4 kHz, soft top end.
    "voice": {50: -8, 63: -6, 80: -2, 100: 2, 125: 3, 160: 3, 200: 2, 250: 1, 315: 0, 1600: 0,
              2000: 2, 2500: 3, 3150: 3, 4000: 2, 5000: 1, 6300: 0, 8000: -1, 10000: -2, 12500: -3},
}
WOOFER_MIN = 45.0      # measured: nothing useful below ~45 Hz
MAX_BOOST, MAX_CUT = 6.0, -15.0
CUT_LIMIT = {"voice": -20.0}   # speech wants far less of the TV's 80-200 Hz hump than music does
FIT_LO, FIT_HI = 50, 12500


def target_curve(name):
    pts = TARGETS[name]
    xs = np.log10(sorted(pts)); ys = [pts[k] for k in sorted(pts)]
    return np.interp(np.log10(T), xs, ys)


def measured(name):
    f, P, _, _ = m.response(os.path.join(m.OUT, name + ".wav"), level=-18)
    r = m.third_octave(f, P)
    return r - r[(T >= 300) & (T <= 3000)].mean()


def fit(target, meas, prev=None, step=1.0):
    tgt = target_curve(target); mes = measured(meas)
    err = tgt - mes
    base = np.zeros_like(T) if prev is None else np.array(prev["third_gains"])
    g = base + step * err
    g = np.convolve(np.pad(g, 1, mode="edge"), [0.25, 0.5, 0.25], mode="valid")  # light smoothing
    g = np.clip(g, CUT_LIMIT.get(target, MAX_CUT), MAX_BOOST)
    g[(T < FIT_LO) | (T > FIT_HI)] = 0.0
    g[T < WOOFER_MIN] = -12.0 if target == "voice" else -6.0   # do not drive the woofer below its range
    edges = list(T[:-1] * 2 ** (1 / 6)) + [20000.0]
    inp = -max(0.0, float(g.max())) - 1.0
    out = {"target": target, "edges": [round(e, 1) for e in edges], "third_gains": [round(x, 2) for x in g],
           "gains": [round(x, 2) for x in g], "input": round(inp, 1)}
    with open(os.path.join(m.OUT, target + ".json"), "w") as fh: json.dump(out, fh, indent=1)
    return out


def push(target, frame=None):
    d = json.load(open(os.path.join(m.OUT, target + ".json")))
    extra = ["--ef", "frame", str(frame)] if frame else []
    m.ctrl("--es", "cmd", "eq", "--es", "id", "custom", *extra,
           "--efa", "edges", ",".join(map(str, d["edges"])),
           "--efa", "gains", ",".join(map(str, d["gains"])),
           "--ef", "input", str(d["input"]))


def check(target, meas):
    tgt = target_curve(target); mes = measured(meas)
    sel = (T >= FIT_LO) & (T <= FIT_HI)
    print("%7s %8s %8s %7s" % ("Hz", "target", "meas", "error"))
    for i, fc in enumerate(T):
        print("%7g %+8.1f %+8.1f %+7.1f%s" % (fc, tgt[i], mes[i], mes[i] - tgt[i], "" if sel[i] else "  (outside fit)"))
    e = (mes - tgt)[sel]
    print("RMS error 50-12500 Hz: %.1f dB, max |error| %.1f dB" % (np.sqrt((e ** 2).mean()), np.abs(e).max()))


if __name__ == "__main__":
    c = sys.argv[1]
    if c == "fit":
        prev = None
        if "--prev" in sys.argv: prev = json.load(open(sys.argv[sys.argv.index("--prev") + 1]))
        d = fit(sys.argv[2], sys.argv[3], prev)
        print("input %.1f dB" % d["input"])
        print(" ".join("%g:%+.1f" % (fc, g) for fc, g in zip(T, d["gains"])))
    elif c == "push": push(sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else None)
    elif c == "check": check(sys.argv[2], sys.argv[3])
