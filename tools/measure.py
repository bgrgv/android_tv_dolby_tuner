#!/usr/bin/env python3
"""
Acoustic measurement for Dolby Tuner: the TV plays an exponential sine sweep through its real
chain (EQ -> Dolby -> amp -> speakers + woofer -> room); a Mac microphone at the listening seat
records it; the impulse response is recovered by deconvolution and turned into a smoothed
frequency response.

Usage:
  measure.py sweep NAME [--level -12] [--secs 10]     record one sweep into out/NAME.wav
  measure.py analyze NAME [NAME ...]                  print 1/3-octave response table(s)
  measure.py adb ARGS...                              send a CtrlReceiver broadcast (see CtrlReceiver.java)

Env: TV (adb serial, e.g. 192.168.1.20:5555), OUT (default ./out)
Requires numpy, scipy and swiftc (records the default macOS input with tools/micrec.swift).
"""
import os, sys, subprocess, time
import numpy as np
from scipy.io import wavfile
from scipy.signal import resample_poly

TV = os.environ.get("TV", "")
HERE = os.path.dirname(os.path.abspath(__file__))
MICREC = os.path.join(HERE, "micrec")  # built from micrec.swift (ffmpeg avfoundation drops buffers)
OUT = os.environ.get("OUT", "out")
SR = 48000
PKG = "ai.brainback.dolbytuner/.CtrlReceiver"
THIRDS = [20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000,
          1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000]


def adb_args():
    return ["adb"] + (["-s", TV] if TV else [])


def ctrl(*extras):
    subprocess.run(adb_args() + ["shell", "am", "broadcast", "-n", PKG] + list(extras),
                   check=True, capture_output=True)


def sweep_signal(secs, level_db, f1=20.0, f2=20000.0):
    """Same signal CtrlReceiver.play() generates (0.5 s pre, sweep, 1 s post)."""
    n = int(round(secs * SR)); pre = SR // 2; post = SR
    t = np.arange(n) / SR
    L = secs / np.log(f2 / f1)
    s = np.sin(2 * np.pi * f1 * L * (np.exp(t / L) - 1))
    fade = SR // 20
    w = np.ones(n); w[:fade] = np.arange(fade) / fade; w[n - fade:] = np.arange(fade, 0, -1) / fade
    return np.concatenate([np.zeros(pre), 10 ** (level_db / 20) * s * w, np.zeros(post)])


def record_sweep(name, secs=10.0, level=-12.0):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".wav")
    if not os.path.exists(MICREC):
        subprocess.run(["swiftc", "-O", os.path.join(HERE, "micrec.swift"), "-o", MICREC], check=True)
    rec = subprocess.Popen([MICREC, path, str(secs + 4)], stdout=subprocess.DEVNULL)
    time.sleep(1.0)
    ctrl("--es", "cmd", "tone", "--es", "kind", "sweep", "--ef", "secs", str(secs), "--ef", "level", str(level))
    rec.wait()
    print("recorded", path)
    return path


def load(path):
    import warnings
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        sr, x = wavfile.read(path)
    x = x.astype(np.float64)
    if x.ndim > 1: x = x.mean(axis=1)
    if sr != SR:
        g = np.gcd(sr, SR); x = resample_poly(x, SR // g, sr // g)
    return x


def impulse_response(rec, ref):
    n = 1 << int(np.ceil(np.log2(len(rec) + len(ref))))
    R = np.fft.rfft(rec, n); X = np.fft.rfft(ref, n)
    f = np.fft.rfftfreq(n, 1 / SR)
    band = (f > 15) & (f < 21000)
    eps = 1e-3 * np.max(np.abs(X[band]) ** 2)
    H = R * np.conj(X) / (np.abs(X) ** 2 + eps)
    H[~band] = 0
    return np.fft.irfft(H, n)


def response(path, secs=10.0, level=-12.0, win_ms=300.0):
    rec = load(path)
    ir = impulse_response(rec, sweep_signal(secs, level))
    pk = int(np.argmax(np.abs(ir)))
    a, b = max(0, pk - int(0.002 * SR)), pk + int(win_ms / 1000 * SR)
    seg = ir[a:b].copy()
    tail = len(seg) // 4
    seg[-tail:] *= np.hanning(2 * tail)[tail:]
    # noise estimate: same-length window well before the direct sound (pre-roll region of the IR)
    nseg = ir[max(0, a - 3 * len(seg)):max(0, a - 2 * len(seg))]
    nfft = 1 << 17
    f = np.fft.rfftfreq(nfft, 1 / SR)
    P = np.abs(np.fft.rfft(seg, nfft)) ** 2
    Pn = np.abs(np.fft.rfft(nseg, nfft)) ** 2 if len(nseg) else np.zeros_like(P)
    peak = np.abs(rec).max()
    return f, P, Pn, peak


def third_octave(f, P):
    out = []
    for fc in THIRDS:
        lo, hi = fc / 2 ** (1 / 6), fc * 2 ** (1 / 6)
        m = (f >= lo) & (f < hi)
        out.append(10 * np.log10(P[m].mean() + 1e-30))
    return np.array(out)


def analyze(names):
    rows = {}
    for nm in names:
        f, P, Pn, peak = response(os.path.join(OUT, nm + ".wav"))
        r = third_octave(f, P); nz = third_octave(f, Pn)
        ref = r[(np.array(THIRDS) >= 300) & (np.array(THIRDS) <= 3000)].mean()
        rows[nm] = (r - ref, r - nz, peak)
    print("%7s " % "Hz" + " ".join("%14s" % n[:14] for n in names))
    for i, fc in enumerate(THIRDS):
        cells = []
        for nm in names:
            d, snr, _ = rows[nm]
            cells.append("%+7.1f (%3.0f)" % (d[i], snr[i]))
        print("%7g " % fc + " ".join("%14s" % c for c in cells))
    print("peaks: " + ", ".join("%s %.2f" % (n, rows[n][2]) for n in names))
    print("(dB relative to the 300-3000 Hz average; SNR dB in brackets)")
    return rows


if __name__ == "__main__":
    if len(sys.argv) < 2: print(__doc__); sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "sweep":
        args = sys.argv[2:]; name = args[0]
        lv = float(args[args.index("--level") + 1]) if "--level" in args else -12.0
        sc = float(args[args.index("--secs") + 1]) if "--secs" in args else 10.0
        record_sweep(name, sc, lv)
    elif cmd == "analyze":
        analyze(sys.argv[2:])
    elif cmd == "adb":
        ctrl(*sys.argv[2:])
    else:
        print(__doc__)
