"""Measurement, reporting, and typing-load checks for scripts/key_sounds.py."""
import cmath, math, struct, wave

SR = 44100
PHI = (1 + 5 ** 0.5) / 2
KINDS = ["key", "space", "delete", "return", "pick"]
KIND_NAMES = {"key": "一般鍵", "space": "空白", "delete": "刪除", "return": "換行", "pick": "選字"}
POSITIONAL = {"penta", "thock"}
SENTENCE = "nihao* wojia youliangzhi mao* mimimo he momo* tamwn<<en douhen guai*\n"
KEY_ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]


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
    h = round(50 / f[1])  # smooth over 100 Hz so noise bin scatter is not read as a peak
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


def penta_index(x):
    """Note for a key whose centre is x keys from the left edge of a 10-key row."""
    return min(5, int(x * 6 / 10))


def key_x(ch):
    for r, row in enumerate(KEY_ROWS):
        if ch in row:
            return row.index(ch) + 0.5 + r * 0.5
    return 5.0


def sentence_events(set_id, rate, offset=0):
    """(time, kind, variant) for SENTENCE at `rate` keys per second; variants rotate."""
    ev, counter = [], {}
    for i, ch in enumerate(SENTENCE):
        kind = {" ": "space", "*": "pick", "<": "delete", "\n": "return"}.get(ch, "key")
        if kind == "key" and set_id in POSITIONAL:
            kind = f"key{penta_index(key_x(ch))}"
        c = counter.get(kind, offset)
        counter[kind] = c + 1
        ev.append((i / rate, kind, c))
    return ev


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


def format_report(refs, rows, sets, set_levels):
    lines = [
        "# 按鍵聲量測", "", "由 `scripts/key_sounds.py` 產生，不要手改。", "",
        "長度：包絡（2 ms RMS）最後一次高於最大值 −20 dB／−40 dB 的時間。起音：包絡第一次到達最大值 −3 dB 的時間。",
        "LA：A 加權能量換算成 100 ms 的均方，dB 對滿刻度。400–3k、>4k：能量占比。2–5k 峰：頻譜以 100 Hz 寬平滑後，該頻段的最高點比整體最高點低多少。",
        "連打峰值：照固定句子每秒 5 鍵／12 鍵疊起來的最大值（四種變體輪替起點取最差）；六聲同時起：六個不同的檔在同一個取樣點開始，最壞的情況。", "",
    ]
    for path in refs:
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
    lines += ["", "各組一般鍵的平均 LA：" + "、".join(f"{s[1]} {set_levels[s[0]]:.2f}" for s in sets if s[0] in set_levels) + " dB。"]
    return "\n".join(lines) + "\n"
