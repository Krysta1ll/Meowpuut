#!/usr/bin/env python3
"""Synthesise Meowpuut's typing sounds from formulas, measure them, write them.

    python3 scripts/key_sounds.py                       write assets/key-sounds/
    python3 scripts/key_sounds.py --ref click.wav       also measure a reference click
    python3 scripts/key_sounds.py --page out.html       also write the listening page

Standard library only, deterministic. Each set has five sounds (key, space,
delete, return, pick), each in a few variants. Every file is 44.1 kHz 16-bit
mono WAV. All sets are measured; only the kept ones are written.
measurements.md lists the numbers the criteria are checked against; the
script exits 1 if a kept set misses one.
"""
import argparse, base64, json, math, random, sys
from pathlib import Path

from key_sounds_measure import (  # noqa: F401
    KEY_ROWS, KIND_NAMES, KINDS, PHI, POSITIONAL, SENTENCE, SR,
    format_report, from_pcm, la, measure, penta_index, problems,
    read_wav, six_at_once, to_pcm, typed_peak, write_wav,
)

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "assets/key-sounds"
PAGE_TEMPLATE = Path(__file__).with_name("key_sounds_page.html")
TARGET_LA = -32.5
KIND_OFFSET = {"key": 0.0, "space": 0.0, "delete": -1.0, "return": 0.0, "pick": -3.0}
VARIANTS = [(0, 0.0, 1.0), (-5, -0.6, 0.94), (4, -0.3, 1.06), (8, -0.9, 0.9)]
N = int(0.15 * SR)
NR = int(0.22 * SR)


def attack(t, a):
    return 0.5 - 0.5 * math.cos(math.pi * t / a) if t < a else 1.0


def tone(f, parts, tau, n, att=0.003, glide=0.0, delay=0.0):
    """Partials (ratio, amp, tau factor) on f, exponential decay, raised-cosine attack."""
    out = [0.0] * n
    start = int(delay * SR)
    for ratio, amp, tf in parts:
        phase, d = 0.0, tau * tf
        for i in range(start, n):
            t = (i - start) / SR
            out[i] += amp * math.exp(-t / d) * attack(t, att) * math.sin(phase)
            phase += 2 * math.pi * f * ratio * 2 ** (glide * t) / SR
    return out


def biquad_bandpass(x, fc, q):
    w = 2 * math.pi * fc / SR
    al = math.sin(w) / (2 * q)
    b0, b2, a0, a1, a2 = al, -al, 1 + al, -2 * math.cos(w), 1 - al
    y, x1, x2, y1, y2 = [], 0.0, 0.0, 0.0, 0.0
    for v in x:
        o = (b0 * v + b2 * x2 - a1 * y1 - a2 * y2) / a0
        x2, x1, y2, y1 = x1, v, y1, o
        y.append(o)
    return y


def biquad_lowpass(x, fc, q=0.70710678):
    w = 2 * math.pi * fc / SR
    cw, sw = math.cos(w), math.sin(w)
    al = sw / (2 * q)
    b0, b1, b2 = (1 - cw) / 2, 1 - cw, (1 - cw) / 2
    a0, a1, a2 = 1 + al, -2 * cw, 1 - al
    y, x1, x2, y1, y2 = [], 0.0, 0.0, 0.0, 0.0
    for v in x:
        o = (b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
        x2, x1, y2, y1 = x1, v, y1, o
        y.append(o)
    return y


def noise_tap(fc, sigma, n, seed, q=1.2, att=0.004, delay=0.0):
    rng = random.Random(seed)
    pre = 2048
    x = [rng.gauss(0, 1) for _ in range(n + pre)]
    x = biquad_bandpass(biquad_bandpass(x, fc, q), fc, q)[pre:]
    start = int(delay * SR)
    out = [0.0] * n
    for i in range(start, n):
        t = (i - start) / SR
        out[i] = x[i] * attack(t, att) * math.exp(-t * t / (2 * sigma * sigma))
    return out


def mix(*xs):
    return [sum(v) for v in zip(*xs)]


def cents(c):
    return 2 ** (c / 1200)


def set_water():
    def drop(r_mm, tau, n=N, delay=0.0, c=0):
        return tone(3.26 / (r_mm / 1000) * cents(c), [(1, 1, 1)], tau, n, glide=12.5, delay=delay)
    return {
        "key": lambda v: drop(4.0, 0.014 * v[2], c=v[0]),
        "space": lambda v: drop(5.6, 0.020 * v[2], c=v[0]),
        "delete": lambda v: drop(4.6, 0.011 * v[2], c=v[0]),
        "return": lambda v: mix(drop(5.4, 0.018, NR), [0.8 * s for s in drop(3.6, 0.016, NR, 0.07)]),
        "pick": lambda v: drop(3.3, 0.011 * v[2], c=v[0]),
    }


def set_marimba():
    parts = [(1, 1, 1), (4, 0.10, 1 / 3), (10, 0.02, 1 / 6)]
    def bar(f, tau, n=N, delay=0.0, c=0, amp=1.0):
        return [amp * s for s in tone(f * cents(c), parts, tau, n, att=0.002, delay=delay)]
    return {
        "key": lambda v: bar(587.33, 0.022 * v[2], c=v[0]),
        "space": lambda v: bar(440.0, 0.030 * v[2], c=v[0]),
        "delete": lambda v: bar(523.25, 0.012 * v[2], c=v[0]),
        "return": lambda v: mix(bar(880.0, 0.020, NR), bar(587.33, 0.024, NR, 0.06)),
        "pick": lambda v: bar(783.99, 0.015 * v[2], c=v[0]),
    }


GLASS = [1, 7.589 / 2.683, 14.552 / 2.683]


def set_glass():
    parts = [(GLASS[0], 1, 1), (GLASS[1], 0.16, 0.5), (GLASS[2], 0.04, 0.3)]
    def tap(f, tau, n=N, delay=0.0, c=0):
        return tone(f * cents(c), parts, tau, n, att=0.0025, delay=delay)
    return {
        "key": lambda v: tap(987.77, 0.016 * v[2], c=v[0]),
        "space": lambda v: tap(659.26, 0.022 * v[2], c=v[0]),
        "delete": lambda v: tap(880.0, 0.011 * v[2], c=v[0]),
        "return": lambda v: mix(tap(1318.5, 0.016, NR), tap(879.0, 0.026, NR, 0.06)),
        "pick": lambda v: tap(1174.7, 0.011 * v[2], c=v[0]),
    }


def steady(make, seed):
    for s in range(seed, seed + 500):
        x = make(s)
        m = measure(x)
        if 1.5 <= m["attack_ms"] <= 4.5 and m["band_peak_db"] <= -14 and m["body_ms"] >= 26:
            return x
    raise SystemExit("no steady noise seed")


def set_felt():
    seed = lambda v, k: 100 * k + VARIANTS.index(v) * 7
    return {
        "key": lambda v: steady(lambda s: noise_tap(900, 0.018 * v[2], N, s), seed(v, 1)),
        "space": lambda v: steady(lambda s: noise_tap(600, 0.024 * v[2], N, s), seed(v, 2)),
        "delete": lambda v: steady(lambda s: noise_tap(1000, 0.013 * v[2], N, s, q=1.5), seed(v, 3)),
        "return": lambda v: steady(lambda s: mix(noise_tap(600, 0.020, NR, s), noise_tap(900, 0.022, NR, s + 1000, delay=0.07)), 400),
        "pick": lambda v: steady(lambda s: noise_tap(1100, 0.013 * v[2], N, s, q=2.0), seed(v, 5)),
    }


def set_paper():
    return {
        "key": lambda v: noise_tap(3000, 0.012 * v[2], N, 21 + int(v[2] * 100), q=0.8),
        "space": lambda v: noise_tap(2200, 0.016 * v[2], N, 22 + int(v[2] * 100), q=0.8),
        "delete": lambda v: noise_tap(3600, 0.009 * v[2], N, 23 + int(v[2] * 100), q=0.8),
        "return": lambda v: mix(noise_tap(2200, 0.014, NR, 24, q=0.8), noise_tap(3000, 0.014, NR, 25, q=0.8, delay=0.07)),
        "pick": lambda v: noise_tap(4000, 0.008 * v[2], N, 26 + int(v[2] * 100), q=0.8),
    }


PENTA = [1, 9 / 8, 5 / 4, 3 / 2, 5 / 3, 2]
PENTA_F0 = 587.33


def set_penta():
    parts = [(1, 1, 1), (2, 0.12, 0.5)]
    note = lambda f, tau, n=N, delay=0.0: tone(f, parts, tau, n, delay=delay)
    sounds = {f"key{i}": (lambda i: lambda v: note(PENTA_F0 * PENTA[i], 0.020 * v[2]))(i) for i in range(6)}
    sounds.update({
        "space": lambda v: note(PENTA_F0 * 3 / 4, 0.026 * v[2]),
        "delete": lambda v: tone(PENTA_F0, [(1, 1, 1)], 0.010 * v[2], N),
        "return": lambda v: mix(note(PENTA_F0 * 3 / 2, 0.020, NR), note(PENTA_F0, 0.024, NR, 0.06)),
        "pick": lambda v: note(PENTA_F0 * 2, 0.012 * v[2]),
    })
    return sounds


def set_harmonic():
    parts = [(1, 1, 1), (2, 0.25, 0.6), (3, 0.08, 0.4)]
    h = lambda k, tau, n=N, delay=0.0: tone(110 * k, parts, tau, n, delay=delay)
    return {
        "key": lambda v: h(6, 0.020 * v[2]),
        "space": lambda v: h(4, 0.028 * v[2]),
        "delete": lambda v: h(5, 0.011 * v[2]),
        "return": lambda v: mix(h(9, 0.020, NR), h(6, 0.024, NR, 0.06)),
        "pick": lambda v: h(8, 0.012 * v[2]),
    }


def set_golden():
    parts = [(1, 1, 1), (PHI, 1 / PHI, 1 / PHI), (PHI ** 2, 1 / PHI ** 2, 1 / PHI ** 2)]
    g = lambda f, tau, n=N, delay=0.0, c=0: tone(f * cents(c), parts, tau, n, delay=delay)
    f = 660.0
    return {
        "key": lambda v: g(f, 0.018 * v[2], c=v[0]),
        "space": lambda v: g(f / PHI, 0.026 * v[2], c=v[0]),
        "delete": lambda v: g(f, 0.011 * v[2], c=v[0]),
        "return": lambda v: mix(g(f * PHI, 0.018, NR), g(f, 0.028, NR, 0.065)),
        "pick": lambda v: g(f * PHI, 0.011 * v[2], c=v[0]),
    }


TAP_PITCH = [1.0, 0.94, 1.05, 0.97]
TAP_ALPHA = 1 - (1 - 0.22) ** (48000 / SR)


def tap(pitch, seed, n=N, delay=0.0):
    rng = random.Random(seed)
    out, low, start = [0.0] * n, 0.0, int(delay * SR)
    for i in range(start, n):
        t = (i - start) / SR
        low += TAP_ALPHA * ((rng.random() * 2 - 1) - low)
        out[i] = (low * 1.3 * math.exp(-t / 0.0045) + 0.5 * math.sin(2 * math.pi * 1250 * pitch * t) * math.exp(-t / 0.006)
                  + 0.75 * math.sin(2 * math.pi * 210 * pitch * t) * math.exp(-t / 0.016)) * min(1, t / 0.0006)
    return out


def mallet(f, decay, n, delay=0.0):
    return tone(f, [(1, 1, 1), (2, 0.22, 0.5), (4.02, 0.07, 0.18)], decay, n, att=0.004, delay=delay)


def set_tap():
    i = lambda v: VARIANTS.index(v)
    return {
        "key": lambda v: tap(TAP_PITCH[i(v)], 10 + i(v)),
        "space": lambda v: tap(0.85, 20 + i(v)),
        "delete": lambda v: tap(1.12, 30 + i(v)),
        "return": lambda v: mix(tap(0.93, 40, NR), [0.6 * s for s in mallet(261.63 * 2, 0.03, NR, 0.06)]),
        "pick": lambda v: tap(0.78, 50 + i(v)),
    }


THOCK_F0 = 224.0
THOCK_SCALE = [3 / 4, 5 / 6, 1, 9 / 8, 5 / 4, 3 / 2]


def thock_note(f, tau, n, seed, delay=0.0, lp_cut=1550.0, puff_amp=0.38, f1_w=0.28, att=0.0018, puff_sigma=0.0050):
    """Muffled gasket/POM cavity thock: harmonic partials with phi-scaled decay,
    syntonic-comma bottom-out settle, air puff, and 4th-order Butterworth low-pass."""
    out = [0.0] * n
    start = int(delay * SR)
    parts = [(1, f1_w, 1.0), (2, 0.90, 1.0 / PHI), (3, 0.68, 1.0 / (PHI ** 2)), (4, 0.28, 1.0 / (PHI ** 3))]
    for ratio, amp, tf in parts:
        phase, d = 0.0, tau * tf
        for i in range(start, n):
            t = (i - start) / SR
            f_inst = f * ratio * (1.0 + 0.018 * math.exp(-t / 0.004))
            out[i] += amp * math.exp(-t / d) * attack(t, att) * math.sin(phase)
            phase += 2 * math.pi * f_inst / SR
    rng = random.Random(seed)
    pre = 2048
    nz = [rng.gauss(0, 1) for _ in range(n + pre)]
    nz = biquad_bandpass(biquad_bandpass(nz, 680.0, 1.1), 680.0, 1.1)[pre:]
    for i in range(start, n):
        t = (i - start) / SR
        out[i] += puff_amp * nz[i] * attack(t, att) * math.exp(-t * t / (2 * puff_sigma * puff_sigma))
    return biquad_lowpass(biquad_lowpass(out, lp_cut), lp_cut)


def set_thock():
    idx = lambda v: next(j for j, (_, l, d) in enumerate(VARIANTS) if (l, d) == (v[1], v[2]))
    sounds = {}
    for k, r in enumerate(THOCK_SCALE):
        dly = 0.0015 if k == 0 else 0.0
        a = 0.0008 if k == 0 else 0.0016
        f1 = 0.22 if k == 0 else 0.28
        pa = 0.25 if k == 0 else 0.38
        sounds[f"key{k}"] = (lambda f, k, dly, a, f1, pa: lambda v: thock_note(
            f, 0.040 * v[2], N, 700 + k * 20 + idx(v), delay=dly, f1_w=f1, puff_amp=pa, att=a))(
            THOCK_F0 * r, k, dly, a, f1, pa)
    sounds.update({
        "space": lambda v: thock_note(THOCK_F0 * 2 / 3, 0.036 * v[2], N, 800 + idx(v), delay=0.0015, lp_cut=1450.0, puff_amp=0.20, f1_w=0.18, att=0.0008, puff_sigma=0.0045),
        "delete": lambda v: thock_note(THOCK_F0 * 4 / 3, 0.022 * v[2], N, 850 + idx(v), lp_cut=1600.0, puff_amp=0.35, f1_w=0.30),
        "return": lambda v: mix(
            thock_note(THOCK_F0 * 3 / 2, 0.032, NR, 900),
            thock_note(THOCK_F0, 0.038, NR, 901, delay=0.062)),
        "pick": lambda v: thock_note(THOCK_F0 * 3 / 2, 0.024 * v[2], N, 920 + idx(v), lp_cut=1500.0, puff_amp=0.28, f1_w=0.32),
    })
    return sounds


SETS = [
    ("water", "水滴", "水滴", "氣泡的共振頻率是 3.26 m/s 除以半徑（Minnaert）：4 毫米的泡泡響 815 Hz，之後每 80 毫秒往上滑一個八度。", set_water, True),
    ("marimba", "木琴", "木琴", "調音木琴音條的泛音在 1 : 4 : 10，越高的泛音消失得越快。", set_marimba, True),
    ("glass", "玻璃", "玻璃", "酒杯杯緣的振動模態 n = 2, 3, 4，頻率比 n(n²−1)/√(n²+1)，也就是 1 : 2.83 : 5.42。", set_glass, True),
    ("felt", "毛氈", "毛毡", "沒有音高：白噪音經過中心 900 Hz 的帶通，音量照半個高斯鐘形 e^(−t²/2σ²) 收掉，σ = 18 毫秒。", set_felt, True),
    ("penta", "五聲", "五声", "鍵越靠右音越高，音取自五聲音階的純律比 1, 9/8, 5/4, 3/2, 5/3, 2；沒有半音，打一句話就是一段小曲。", set_penta, True),
    ("harmonic", "泛音", "泛音", "每個頻率都是 110 Hz 的整數倍：空白 4 倍、刪除 5 倍、一般鍵 6 倍、選字 8 倍，換行從 9 倍落到 6 倍。", set_harmonic, True),
    ("tap", "輕敲", "轻敲", "第一支宣傳影片裡的敲擊聲：低通噪音、1250 Hz 的短促清音和 210 Hz 的低敲；一般鍵 0.94–1.05 倍，選字 0.78 倍。", set_tap, True),
    ("thock", "沉韻", "沉韵", "沉穩悶感與純律音韻：以 224 Hz 為中心取純律五聲比 3/4, 5/6, 1, 9/8, 5/4, 3/2（149–336 Hz 低頻腔體基頻），泛音衰減依黃金比例 φ 遞減並以 4 階低通濾除尖銳高頻。", set_thock, True),
    ("golden", "黃金", "黄金", "泛音在 f、φf、φ²f（φ ≈ 1.618），音量和長度每一層也縮小 φ 倍。", set_golden, False),
    ("paper", "紙", "纸", "沒有音高：白噪音經過中心 3 kHz 的帶通，半個高斯鐘形收掉。", set_paper, False),
]

RELAXED = {
    "tap": ("attack", "peak", "400-3k", ">4k", "six"),
    "thock": ("400-3k", "peak", "six"),
}


def variants_for(set_id, kind):
    if kind == "return":
        return VARIANTS[:1]
    if set_id in ("penta", "harmonic", "thock"):
        vs = [(0, l, d) for _, l, d in VARIANTS]
        return vs[:2] if kind.startswith("key") and set_id in POSITIONAL else vs[:4 if kind == "key" else 3]
    return VARIANTS[:4 if kind == "key" else 3]


def finish(x):
    m = sum(x) / len(x)
    x = [v - m * attack(i / SR, 0.003) for i, v in enumerate(x)]
    fade = int(0.008 * SR)
    n = len(x)
    for i in range(fade):
        x[n - 1 - i] *= 0.5 - 0.5 * math.cos(math.pi * i / fade)
    x[0] = x[-1] = 0.0
    return x


def build_set(set_id, builder):
    out = {}
    for kind, make in builder().items():
        base = "key" if kind.startswith("key") else kind
        vs = variants_for(set_id, kind)
        raw = [finish(make(v)) for v in vs]
        gains = [10 ** ((TARGET_LA + KIND_OFFSET[base] + v[1] - la(r)) / 20) for r, v in zip(raw, vs)]
        out[kind] = [to_pcm([s * g for s in r]) for r, g in zip(raw, gains)]
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ref", type=Path, action="append", default=[], help="a WAV to measure for comparison")
    ap.add_argument("--page", type=Path, help="write the listening page here")
    args = ap.parse_args()
    rows, failed, set_levels, page_sets = [], [], {}, []
    for set_id, name, simp, rule, builder, kept in SETS:
        sounds, files, ms = build_set(set_id, builder), {}, {}
        for kind in sorted(sounds, key=lambda k: (KINDS.index("key" if k.startswith("key") else k), k)):
            for i, pcm in enumerate(sounds[kind]):
                rel = f"{set_id}/{kind}-{i + 1}.wav"
                if kept:
                    write_wav(OUT / rel, pcm)
                files.setdefault(kind, []).append(pcm)
                ms[(kind, i)] = measure(from_pcm(pcm))
        keys = [m["la_db"] for (k, _), m in ms.items() if k.startswith("key")]
        key_la = sum(keys) / len(keys)
        set_levels[set_id] = key_la
        p5, _ = typed_peak(set_id, sounds, 5)
        p12, voices = typed_peak(set_id, sounds, 12)
        pitched = [m["dominant_hz"] for m in ms.values()]
        for (kind, i), m in ms.items():
            bad = problems(kind, m, key_la)
            strict = [b for b in bad if not b.startswith(RELAXED.get(set_id, ()))]
            shown = strict + [f"放寬：{b}" for b in bad if b not in strict]
            rows.append((name if kept else f"{name}（未採用）", f"{kind}-{i + 1}", m, shown))
            if strict and kept:
                failed.append(f"{set_id} {kind}-{i + 1}: {', '.join(strict)}")
        if p12 > -6 and kept:
            failed.append(f"{set_id}: typed peak {p12:.1f} dBFS at 12 keys/s")
        if kept:
            peaks = sorted((max(map(abs, pcm)) for group in sounds.values() for pcm in group), reverse=True)
            if sum(peaks[:6]) >= 32767 and "six" not in RELAXED.get(set_id, ()):
                failed.append(f"{set_id}: six overlapping samples could clip")
        rows.append((name, "typing", {"p5": p5, "p12": p12, "voices": voices, "six": six_at_once(sounds),
                                      "range": (min(pitched), max(pitched))}, []))
        if kept:
            page_sets.append({"id": set_id, "name": name, "simp": simp, "rule": rule, "files": {
                k: [base64.b64encode((OUT / f"{set_id}/{k}-{i + 1}.wav").read_bytes()).decode() for i in range(len(v))]
                for k, v in files.items()}})
    kept_levels = [set_levels[s[0]] for s in SETS if s[5]]
    if max(kept_levels) - min(kept_levels) > 1:
        failed.append("sets differ by more than 1 dB A-weighted")
    text = format_report(args.ref, rows, SETS, set_levels)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "measurements.md").write_text(text)
    print(text)
    if args.page:
        html = PAGE_TEMPLATE.read_text().replace("/*DATA*/null", json.dumps(
            {"sets": page_sets, "sentence": SENTENCE, "rows": KEY_ROWS, "kinds": KIND_NAMES}, ensure_ascii=False))
        args.page.parent.mkdir(parents=True, exist_ok=True)
        args.page.write_text(html)
        print(f"page: {args.page} ({len(html) / 1e6:.2f} MB)")
    if failed:
        print("FAILED:\n  " + "\n  ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()
