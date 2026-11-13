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
import argparse, base64, cmath, json, math, random, struct, sys, wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "assets/key-sounds"
PAGE_TEMPLATE = Path(__file__).with_name("key_sounds_page.html")
SR = 44100
PHI = (1 + 5 ** 0.5) / 2
KINDS = ["key", "space", "delete", "return", "pick"]
KIND_NAMES = {"key": "一般鍵", "space": "空白", "delete": "刪除", "return": "換行", "pick": "選字"}
# A-weighted level of the ordinary key, and each kind's offset from it (dB).
TARGET_LA = -32.5
KIND_OFFSET = {"key": 0.0, "space": 0.0, "delete": -1.0, "return": 0.0, "pick": -3.0}
# Variant i: pitch in cents, level in dB, decay factor. Small on purpose.
VARIANTS = [(0, 0.0, 1.0), (-5, -0.6, 0.94), (4, -0.3, 1.06), (8, -0.9, 0.9)]
# The sentence typed in the fast-typing test and on the page:
# letters are keys, ' ' space, '*' picking a candidate, '<' delete, '\n' return.
SENTENCE = "nihao* wojia youliangzhi mao* mimimo he momo* tamwn<<en douhen guai*\n"
KEY_ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]


# ---------- synthesis ----------

def attack(t, a):
    return 0.5 - 0.5 * math.cos(math.pi * t / a) if t < a else 1.0


def tone(f, parts, tau, n, att=0.003, glide=0.0, delay=0.0):
    """Partials (ratio, amp, tau factor) on f, exponential decay, raised-cosine
    attack; glide is the upward glide in octaves per second."""
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


def noise_tap(fc, sigma, n, seed, q=1.2, att=0.004, delay=0.0):
    """White noise through two band-passes at fc, half-Gaussian decay."""
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


# Each set: kind -> function(variant) returning samples. n is the buffer length.
N = int(0.15 * SR)
NR = int(0.22 * SR)  # return runs a little longer


def set_water():
    # Minnaert: a bubble of radius r rings at f = 3.26 m/s / r. The pitch then
    # rises one octave every 80 ms as the bubble nears the surface.
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
    # A tuned marimba bar: partials at 1 : 4 : 10, the higher ones dying faster.
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


GLASS = [1, 7.589 / 2.683, 14.552 / 2.683]  # n(n^2-1)/sqrt(n^2+1), n = 2, 3, 4


def set_glass():
    # Wine-glass rim modes n = 2, 3, 4: f ~ n(n^2-1)/sqrt(n^2+1) = 1 : 2.83 : 5.42.
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
    """Noise is uneven; take the first seed from `seed` on whose tap has a
    smooth onset (1.5-4.5 ms), a body of 26 ms or more and no 2-5 kHz bump, so every file is judged by
    the same rule rather than picked by ear."""
    for s in range(seed, seed + 500):
        x = make(s)
        m = measure(x)
        if 1.5 <= m["attack_ms"] <= 4.5 and m["band_peak_db"] <= -14 and m["body_ms"] >= 26:
            return x
    raise SystemExit("no steady noise seed")


def set_felt():
    # Unpitched: white noise through a band-pass, then a half Gaussian.
    seed = lambda v, k: 100 * k + VARIANTS.index(v) * 7
    return {
        "key": lambda v: steady(lambda s: noise_tap(900, 0.018 * v[2], N, s), seed(v, 1)),
        "space": lambda v: steady(lambda s: noise_tap(600, 0.024 * v[2], N, s), seed(v, 2)),
        "delete": lambda v: steady(lambda s: noise_tap(1000, 0.013 * v[2], N, s, q=1.5), seed(v, 3)),
        "return": lambda v: steady(lambda s: mix(noise_tap(600, 0.020, NR, s), noise_tap(900, 0.022, NR, s + 1000, delay=0.07)), 400),
        "pick": lambda v: steady(lambda s: noise_tap(1100, 0.013 * v[2], N, s, q=2.0), seed(v, 5)),
    }


def set_paper():
    # Unpitched, crisper: the same tap centred at 3 kHz.
    return {
        "key": lambda v: noise_tap(3000, 0.012 * v[2], N, 21 + int(v[2] * 100), q=0.8),
        "space": lambda v: noise_tap(2200, 0.016 * v[2], N, 22 + int(v[2] * 100), q=0.8),
        "delete": lambda v: noise_tap(3600, 0.009 * v[2], N, 23 + int(v[2] * 100), q=0.8),
        "return": lambda v: mix(noise_tap(2200, 0.014, NR, 24, q=0.8), noise_tap(3000, 0.014, NR, 25, q=0.8, delay=0.07)),
        "pick": lambda v: noise_tap(4000, 0.008 * v[2], N, 26 + int(v[2] * 100), q=0.8),
    }


PENTA = [1, 9 / 8, 5 / 4, 3 / 2, 5 / 3, 2]
PENTA_F0 = 587.33


def penta_index(x):
    """Note for a key whose centre is x keys from the left edge of a 10-key row."""
    return min(5, int(x * 6 / 10))


def set_penta():
    # Pentatonic in just ratios; the key's position picks the note. Variants
    # change only level and decay, so the ratios stay exact.
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
    # Every frequency in this set is a whole multiple of 110 Hz.
    parts = [(1, 1, 1), (2, 0.25, 0.6), (3, 0.08, 0.4)]  # 1/n^2-ish, also harmonics of 110
    h = lambda k, tau, n=N, delay=0.0: tone(110 * k, parts, tau, n, delay=delay)
    return {
        "key": lambda v: h(6, 0.020 * v[2]),
        "space": lambda v: h(4, 0.028 * v[2]),
        "delete": lambda v: h(5, 0.011 * v[2]),
        "return": lambda v: mix(h(9, 0.020, NR), h(6, 0.024, NR, 0.06)),
        "pick": lambda v: h(8, 0.012 * v[2]),
    }


def set_golden():
    # Partials f, phi f, phi^2 f with amplitude and decay shrinking by 1/phi.
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


SETS = [
    # id, Traditional, Simplified, the rule, builder, kept
    ("water", "水滴", "水滴", "氣泡的共振頻率是 3.26 m/s 除以半徑（Minnaert）：4 毫米的泡泡響 815 Hz，之後每 80 毫秒往上滑一個八度。", set_water, True),
    ("marimba", "木琴", "木琴", "調音木琴音條的泛音在 1 : 4 : 10，越高的泛音消失得越快。", set_marimba, True),
    ("glass", "玻璃", "玻璃", "酒杯杯緣的振動模態 n = 2, 3, 4，頻率比 n(n²−1)/√(n²+1)，也就是 1 : 2.83 : 5.42。", set_glass, True),
    ("felt", "毛氈", "毛毡", "沒有音高：白噪音經過中心 900 Hz 的帶通，音量照半個高斯鐘形 e^(−t²/2σ²) 收掉，σ = 18 毫秒。", set_felt, True),
    ("penta", "五聲", "五声", "鍵越靠右音越高，音取自五聲音階的純律比 1, 9/8, 5/4, 3/2, 5/3, 2；沒有半音，打一句話就是一段小曲。", set_penta, True),
    ("harmonic", "泛音", "泛音", "每個頻率都是 110 Hz 的整數倍：空白 4 倍、刪除 5 倍、一般鍵 6 倍、選字 8 倍，換行從 9 倍落到 6 倍。", set_harmonic, True),
    ("golden", "黃金", "黄金", "泛音在 f、φf、φ²f（φ ≈ 1.618），音量和長度每一層也縮小 φ 倍。", set_golden, False),
    ("paper", "紙", "纸", "沒有音高：白噪音經過中心 3 kHz 的帶通，半個高斯鐘形收掉。", set_paper, False),
]


def variants_for(set_id, kind):
    if kind == "return":
        return VARIANTS[:1]
    if set_id in ("penta", "harmonic"):  # exact ratios: no pitch variation
        vs = [(0, l, d) for _, l, d in VARIANTS]
        return vs[:2] if kind.startswith("key") and set_id == "penta" else vs[:4 if kind == "key" else 3]
    return VARIANTS[:4 if kind == "key" else 3]


def finish(x):
    """Remove DC, fade the last 8 ms to zero, so the file starts and ends at 0."""
    m = sum(x) / len(x)
    x = [v - m * attack(i / SR, 0.003) for i, v in enumerate(x)]
    fade = int(0.008 * SR)
    n = len(x)
    for i in range(fade):
        x[n - 1 - i] *= 0.5 - 0.5 * math.cos(math.pi * i / fade)
    x[0] = x[-1] = 0.0
    return x


# ---------- measurement ----------

def fft(a):
    n = len(a)
    j = 0
    a = list(a)
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j |= bit
        if i < j:
            a[i], a[j] = a[j], a[i]
    size = 2
    while size <= n:
        w = cmath.exp(-2j * math.pi / size)
        half = size // 2
        tw = [w ** k for k in range(half)]
        for s in range(0, n, size):
            for k in range(half):
                u, v = a[s + k], a[s + k + half] * tw[k]
                a[s + k], a[s + k + half] = u + v, u - v
        size *= 2
    return a


def a_weight(f):
    f2 = f * f
    r = 12194 ** 2 * f2 * f2 / ((f2 + 20.6 ** 2) * math.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2)) * (f2 + 12194 ** 2))
    return (r * 10 ** (2.0 / 20)) ** 2  # power weight, 0 dB at 1 kHz


def db(v, floor=1e-12):
    return 10 * math.log10(max(v, floor))


def spectrum(x):
    n = 1 << max(13, (len(x) - 1).bit_length())
    X = fft([complex(v) for v in x] + [0j] * (n - len(x)))
    # one-sided power per bin, scaled so sum == sum(x^2)
    p = [abs(X[k]) ** 2 / n * (1 if k in (0, n // 2) else 2) for k in range(n // 2 + 1)]
    return [k * SR / n for k in range(n // 2 + 1)], p


def la(x):
    """A-weighted energy as a mean square over 100 ms, in dB re full scale."""
    f, p = spectrum(x)
    return db(sum(pi * a_weight(fi) for fi, pi in zip(f[1:], p[1:])) / (0.1 * SR))


def envelope(x, w=0.002):
    h = int(w * SR / 2)
    c = [0.0]
    for v in x:
        c.append(c[-1] + v * v)
    n = len(x)
    return [math.sqrt((c[min(n, i + h)] - c[max(0, i - h)]) / (min(n, i + h) - max(0, i - h))) for i in range(n)]


def measure(x):
    env = envelope(x)
    top = max(env)
    onset = next(i for i, e in enumerate(env) if e >= top * 10 ** (-3 / 20))
    last = lambda lvl: max(i for i, e in enumerate(env) if e >= top * 10 ** (lvl / 20)) / SR
    f, p = spectrum(x)
    total = sum(p)
    share = lambda lo, hi: sum(pi for fi, pi in zip(f, p) if lo <= fi < hi) / total
    k = max(range(1, len(p)), key=lambda i: p[i])
    h = round(50 / f[1])  # smooth over 100 Hz so a noise spectrum's bin-to-bin scatter is not read as a peak
    c = [0.0]
    for v in p:
        c.append(c[-1] + v)
    sm = [(c[min(len(p), i + h + 1)] - c[max(0, i - h)]) for i in range(len(p))]
    pk = max(sm)
    band = max(v for fi, v in zip(f, sm) if 2000 <= fi <= 5000)
    return {
        "attack_ms": onset / SR * 1000,
        "body_ms": last(-20) * 1000,
        "gone_ms": last(-40) * 1000,
        "peak_db": 20 * math.log10(max(abs(v) for v in x)),
        "la_db": la(x),
        "centroid_hz": sum(fi * pi for fi, pi in zip(f, p)) / total,
        "mid_share": share(400, 3000),
        "above4k": share(4000, SR),
        "above6k": share(6000, SR),
        "band_peak_db": db(band / pk),
        "dominant_hz": f[k],
        "dc_db": db((sum(x) / len(x)) ** 2),
        "edges": (abs(x[0]), abs(x[-1])),
    }


# ---------- files ----------

def to_pcm(x):
    return [max(-32767, min(32767, round(v * 32767))) for v in x]


def from_pcm(s):
    return [v / 32767 for v in s]


def write_wav(path, pcm):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(struct.pack(f"<{len(pcm)}h", *pcm))


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        ch, width, n = w.getnchannels(), w.getsampwidth(), w.getnframes()
        assert width == 2 and w.getframerate() == SR
        s = struct.unpack(f"<{n * ch}h", w.readframes(n))
    return [v / 32768 for v in s[::ch]]


def build_set(set_id, builder):
    """Returns {kind: [pcm, ...]} normalised to the target A-weighted level."""
    out = {}
    for kind, make in builder().items():
        base = "key" if kind.startswith("key") else kind
        vs = variants_for(set_id, kind)
        raw = [finish(make(v)) for v in vs]
        gains = [10 ** ((TARGET_LA + KIND_OFFSET[base] + v[1] - la(r)) / 20) for r, v in zip(raw, vs)]
        out[kind] = [to_pcm([s * g for s in r]) for r, g in zip(raw, gains)]
    return out


def sentence_events(set_id, rate, offset=0):
    """(time, kind, variant) for SENTENCE at `rate` keys per second; variants
    rotate, starting at `offset`."""
    ev, counter = [], {}
    for i, ch in enumerate(SENTENCE):
        kind = {" ": "space", "*": "pick", "<": "delete", "\n": "return"}.get(ch, "key")
        if kind == "key" and set_id == "penta":
            kind = f"key{penta_index(key_x(ch))}"
        c = counter.get(kind, offset)
        counter[kind] = c + 1
        ev.append((i / rate, kind, c))
    return ev


def key_x(ch):
    for r, row in enumerate(KEY_ROWS):
        if ch in row:
            return row.index(ch) + 0.5 + r * 0.5
    return 5.0


def typed_peak(set_id, sounds, rate):
    worst, voices = 0.0, 0
    for offset in range(4):
        ev = sentence_events(set_id, rate, offset)
        n = int((ev[-1][0] + 0.3) * SR)
        buf = [0.0] * n
        spans = []
        for t, kind, c in ev:
            pcm = sounds[kind][c % len(sounds[kind])]
            s = int(t * SR)
            for i, v in enumerate(pcm):
                buf[s + i] += v / 32767
            spans.append((s, s + len(pcm)))
        worst = max(worst, max(abs(v) for v in buf))
        voices = max(voices, max(sum(1 for a, b in spans if a <= s < b) for s, _ in spans))
    return 20 * math.log10(worst), voices


def six_at_once(sounds):
    """Six different sounds of the set started on the same sample."""
    pcms = [p for kind in sounds for p in sounds[kind]][:6]
    return 20 * math.log10(max(abs(sum(p[i] for p in pcms if i < len(p))) for i in range(max(map(len, pcms)))) / 32767)


# ---------- criteria ----------

def problems(kind, m, key_la):
    base = "key" if kind.startswith("key") else kind
    longer = base == "return"
    p = []
    if not (25 <= m["body_ms"] <= (130 if longer else 90)):
        p.append(f"body {m['body_ms']:.0f} ms")
    if m["gone_ms"] > (200 if longer else 150):
        p.append(f"-40 dB at {m['gone_ms']:.0f} ms")
    if not (1 <= m["attack_ms"] <= 5):
        p.append(f"attack {m['attack_ms']:.1f} ms")
    if m["peak_db"] > -12:
        p.append(f"peak {m['peak_db']:.1f}")
    if m["edges"] != (0, 0) or m["dc_db"] > -80:
        p.append("edges/DC")
    if m["mid_share"] < 0.8:
        p.append(f"400-3k {m['mid_share']:.0%}")
    if m["above4k"] > 0.03 or m["above6k"] > 0.005:
        p.append(f">4k {m['above4k']:.1%}")
    if m["band_peak_db"] > -12:
        p.append(f"2-5k peak {m['band_peak_db']:.0f} dB")
    lim = (-4.5, -1.5) if base == "pick" else (-2, 2)
    if not (lim[0] <= m["la_db"] - key_la <= lim[1]):
        p.append(f"level {m['la_db'] - key_la:+.1f} dB vs key")
    return p


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ref", type=Path, action="append", default=[], help="a WAV to measure for comparison")
    ap.add_argument("--page", type=Path, help="write the listening page here")
    args = ap.parse_args()

    rows, failed, set_levels, page_sets = [], [], {}, []
    for set_id, name, simp, rule, builder, kept in SETS:
        sounds = build_set(set_id, builder)
        files = {}
        key_la = None
        ms = {}
        for kind in sorted(sounds, key=lambda k: (KINDS.index("key" if k.startswith("key") else k), k)):
            for i, pcm in enumerate(sounds[kind]):
                rel = f"{set_id}/{kind}-{i + 1}.wav"
                if kept:
                    write_wav(OUT / rel, pcm)
                files.setdefault(kind, []).append(pcm)
                m = measure(from_pcm(pcm))
                ms[(kind, i)] = m
        keys = [m["la_db"] for (k, _), m in ms.items() if k.startswith("key")]
        key_la = sum(keys) / len(keys)
        set_levels[set_id] = key_la
        p5, _ = typed_peak(set_id, sounds, 5)
        p12, voices = typed_peak(set_id, sounds, 12)
        pitched = [m["dominant_hz"] for m in ms.values()]
        for (kind, i), m in ms.items():
            bad = problems(kind, m, key_la)
            rows.append((name if kept else f"{name}（未採用）", f"{kind}-{i + 1}", m, bad))
            if bad and kept:
                failed.append(f"{set_id} {kind}-{i + 1}: {', '.join(bad)}")
        if p12 > -6 and kept:
            failed.append(f"{set_id}: typed peak {p12:.1f} dBFS at 12 keys/s")
        if kept:
            peaks = sorted((max(map(abs, pcm)) for group in sounds.values() for pcm in group), reverse=True)
            if sum(peaks[:6]) >= 32767:
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

    lines = ["# 按鍵聲量測", "", "由 `scripts/key_sounds.py` 產生，不要手改。",
             "", "長度：包絡（2 ms RMS）最後一次高於最大值 −20 dB／−40 dB 的時間。起音：包絡第一次到達最大值 −3 dB 的時間。",
             "LA：A 加權能量換算成 100 ms 的均方，dB 對滿刻度。400–3k、>4k：能量占比。2–5k 峰：頻譜以 100 Hz 寬平滑後，該頻段的最高點比整體最高點低多少。",
             "連打峰值：照固定句子每秒 5 鍵／12 鍵疊起來的最大值（四種變體輪替起點取最差）；六聲同時起：六個不同的檔在同一個取樣點開始，最壞的情況。", ""]
    for path in args.ref:
        m = measure(read_wav(path))
        lines.append(f"參考 `{path.name}`：峰值 {m['peak_db']:.1f} dBFS，LA {m['la_db']:.1f} dB，−40 dB 在 {m['gone_ms']:.0f} ms，"
                     f"重心 {m['centroid_hz']:.0f} Hz，>4k {m['above4k']:.0%}。")
    lines += ["", "| 組 | 聲音 | −20 dB ms | −40 dB ms | 起音 ms | 峰值 dBFS | LA dB | 重心 Hz | 主頻 Hz | 400–3k | >4k | 2–5k 峰 dB | 問題 |",
              "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |"]
    for name, label, m, bad in rows:
        if label == "typing":
            lo, hi = m["range"]
            lines.append(f"| {name} | 連打 | 每秒 5 鍵峰值 {m['p5']:.1f} dBFS | 每秒 12 鍵峰值 {m['p12']:.1f} dBFS | 最多 {m['voices']} 聲重疊；六聲同時起 {m['six']:.1f} dBFS | "
                         f"主頻 {lo:.0f}–{hi:.0f} Hz（{math.log2(hi / lo):.2f} 八度） | | | | | | | |")
            continue
        lines.append(f"| {name} | {label} | {m['body_ms']:.0f} | {m['gone_ms']:.0f} | {m['attack_ms']:.1f} | {m['peak_db']:.1f} | "
                     f"{m['la_db']:.1f} | {m['centroid_hz']:.0f} | {m['dominant_hz']:.0f} | {m['mid_share']:.0%} | {m['above4k']:.1%} | "
                     f"{m['band_peak_db']:.0f} | {'; '.join(bad)} |")
    lines += ["", "各組一般鍵的平均 LA：" + "、".join(f"{s[1]} {set_levels[s[0]]:.2f}" for s in SETS if s[0] in set_levels) + " dB。"]
    text = "\n".join(lines) + "\n"
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
