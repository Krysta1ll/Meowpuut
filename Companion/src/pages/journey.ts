// 行旅 (FINAL.md §2.2, the b-scroll prototype): one long scroll. The first day is on the left, today on the right
// where the two cats stand. Three distances, by meaning not by magnification: 年 (one mountain per typed day),
// 月 (one mountain per day with its date and seal, months parted by silk), 日 (the road itself).
//
// Every picture is the Rust 字景 engine's (src-tauri/src/glyph.rs, crate meowpuut-glyph), under every skin. This page
// lays the scroll out by the engine's geometry and keeps only what is near the window:
//
//   - the world is `total` points wide; an engine point is `s` CSS pixels (s = k / devicePixelRatio, so the pictures
//     land one to one on the display's pixels), and the world only ever moves by whole points: whole device pixels;
//   - tiles are 512 points; the window's width ahead and behind is asked for, nearest first, at most three at a time,
//     each by an async command that answers with raw RGBA (no base64) that is put on a canvas;
//   - a different distance, size, appearance or skin makes a new scroll and an empty cache;
//   - under a paper skin the painting is a rounded plate on the skin's paper; under 字景 it is the whole page.
//
// Days before the ledger kept places read「更早的路已經捲起來了」(the engine writes it on the left axis), never "missed".

import { invoke } from "@tauri-apps/api/core";
import type { SkinKey } from "../api";
import { CAT } from "../art";
import { iso, ymd } from "../dates";
import { esc, fmt, monthName, t, tt } from "../i18n";
import { canvasOf, float, hasEngine, kOf, paint, paperList, picture, pointCss, report, word, type Picture } from "../glyph";
import { nav } from "../nav";
import { SKINS, isDark, tokensOf } from "../skins";
import { store, today } from "../store";

type Lens = "year" | "month" | "day";

export const BANDS: { name: [string, string]; note: [string, string] }[] = [
  { name: ["小丘", "小丘"], note: ["百字內", "百字内"] },
  { name: ["山坡", "山坡"], note: ["百字起", "百字起"] },
  { name: ["山", "山"], note: ["五百起", "五百起"] },
  { name: ["高山", "高山"], note: ["千五起", "千五起"] },
  { name: ["雪峰", "雪峰"], note: ["四千起", "四千起"] },
];
export const PARTS: [string, string][] = [["清晨", "清晨"], ["白天", "白天"], ["傍晚", "傍晚"], ["晚上", "晚上"], ["深夜", "深夜"]];

const LENS_NAMES: Record<Lens, [readonly [string, string], readonly [string, string]]> = {
  year: [["年", "年"], ["行旅", "行旅"]],
  month: [["月", "月"], ["爪印", "爪印"]],
  day: [["日", "日"], ["那段路", "那段路"]],
};

/** The engine's answer to scroll_open (glyph.rs `ScrollView`). */
interface Geo {
  total: number;
  lead: [number, number];
  painting: [number, number];
  rolled: [number, number] | null;
  daysAt: [number, number];
  tail: [number, number];
  top: number;
  height: number;
  col: number;
  rolledDays: number;
  days: { day: number; size: number; who: number; place: number | null; span: [number, number] | null }[];
}

interface LeadInfo {
  characters: number;
  stop: number;
  visits: number[];
  placesMet: number;
  names: string[];
  here: number;
  mimimo: { level: number; pets: number };
  momo: { level: number; pets: number };
}

const TILE = 512;
const AHEAD = 3; // tiles asked for at once
/** Room above and below the scroll for the rollers' rods (points). */
const HEAD = 18;
const FOOT = 28;

export function renderJourney(app: HTMLElement, params: URLSearchParams): () => void {
  let lens: Lens = (["year", "month", "day"] as Lens[]).find((l) => l === params.get("lens")) ?? "year";
  const s = store.summary;
  const now = today();
  const first = s.marks[0]?.day ?? now;
  const skinKey: SkinKey = store.companion.skin;
  const paperSkin = SKINS[skinKey].world === "paper";
  const bench = params.has("bench");

  // ---- state of the open scroll ----
  let geo: Geo | null = null;
  let lead: LeadInfo | null = null;
  let k = kOf();
  let sp = pointCss(k); // CSS px per point
  let viewW = 0; // window width in points
  let pos = 0; // the world's left edge in the window, whole points
  let target = 0;
  let gen = 0;
  const tiles = new Map<number, { el: HTMLCanvasElement | null; asked: boolean }>();
  let inflight = 0;
  let raf = 0;
  let disposed = false;
  let openToken = 0;
  const latency: number[] = [];
  const frames: number[] = [];
  const rollerKey: Record<"l" | "r", string> = { l: "", r: "" };
  let jview!: HTMLElement;
  let world!: HTMLElement;
  let plate!: HTMLElement;

  const maxPos = () => Math.max(0, (geo?.total ?? 0) - viewW);

  const shell = () => {
    const a = ymd(first);
    const b = ymd(now);
    app.innerHTML = `
      <header class="jbar">
        <div class="seg lens" role="tablist">
          ${(["year", "month", "day"] as Lens[])
            .map((l) => `<button class="${l === lens ? "on" : ""}" data-lens="${l}" role="tab">${tt(LENS_NAMES[l][0])}<small>${tt(LENS_NAMES[l][1])}</small></button>`)
            .join("")}
        </div>
        <div class="range mut">${a.y} ${t("年", "年")} ${monthName(a.m)} ${a.d} ${t("日", "日")} — ${b.y} ${t("年", "年")} ${monthName(b.m)} ${b.d} ${t("日", "日")} · ${t(`${s.days} 座山`, `${s.days} 座山`)}</div>
        <button class="btn to-time" data-time>${t("時間花在哪裡", "时间花在哪里")}</button>
        <button class="btn" data-share ${hasEngine && s.days > 0 ? "" : "disabled"}>${t("截一段", "截一段")}</button>
      </header>
      <div class="jview ${paperSkin ? "paper" : "glyph"}" tabindex="0"></div>
      <footer class="jfoot">
        <div class="bands">${BANDS.map((bd, i) => `<span><i class="tri b${i}"></i>${tt(bd.name)}<small>${tt(bd.note)}</small></span>`).join("")}</div>
        <span class="mut">${t("滾輪 前後 · ← → 一天 · Home 引首 · End 此刻 · Esc 回首頁", "滚轮 前后 · ← → 一天 · Home 引首 · End 此刻 · Esc 回首页")}</span>
      </footer>`;
    jview = app.querySelector<HTMLElement>(".jview")!;
    for (const tab of app.querySelectorAll<HTMLElement>("[data-lens]"))
      tab.addEventListener("click", () => {
        if (tab.dataset.lens === lens) return;
        lens = tab.dataset.lens as Lens;
        history.replaceState(null, "", `#/journey?lens=${lens}`);
        for (const b of app.querySelectorAll<HTMLElement>("[data-lens]")) b.classList.toggle("on", b === tab);
        void open("keep");
      });
    app.querySelector<HTMLElement>("[data-time]")?.addEventListener("click", () => nav.go("time"));
    app.querySelector<HTMLElement>("[data-share]")?.addEventListener("click", () => startPick());
  };

  // ---- opening the scroll ----
  const open = async (where: "now" | "lead" | "keep") => {
    if (!hasEngine) {
      jview.innerHTML = `<div class="jnote">${t("字景的畫由 App 裡的 Rust 引擎畫；在瀏覽器預覽裡看不到。", "字景的画由 App 里的 Rust 引擎画；在浏览器预览里看不到。")}</div>`;
      return;
    }
    const token = ++openToken;
    const keepCenter = where === "keep" && geo ? centerDay() : null;
    k = kOf();
    sp = pointCss(k);
    viewW = Math.floor(jview.clientWidth / sp);
    const viewH = Math.floor(jview.clientHeight / sp);
    const dark = isDark();
    const asked = { lens, width: viewW, height: viewH - HEAD - FOOT, dark, paper: paperSkin ? paperList(tokensOf(skinKey, store.settings?.settings.palette ?? null, dark)) : null, today: now };
    const [g, l] = await Promise.all([invoke<Geo>("scroll_open", { ask: asked }), invoke<LeadInfo>("lead_info")]);
    if (token !== openToken || disposed) return;
    geo = g;
    lead = l;
    gen++;
    tiles.clear();
    inflight = 0;
    rollerKey.l = rollerKey.r = "";
    build();
    if (where === "lead") pos = 0;
    else if (where === "now") pos = maxPos();
    else pos = keepCenter ? Math.min(maxPos(), Math.max(0, dayCenter(keepCenter) - viewW / 2)) : Math.min(pos, maxPos());
    pos = target = Math.round(pos);
    applyPos();
    pump();
  };

  /** The day nearest the window's middle (the engine's `day_at`, from the spans it gave). */
  const dayAt = (x: number): number => {
    let best = -1;
    let gap = Infinity;
    geo?.days.forEach((d, i) => {
      if (!d.span) return;
      const [a, b] = d.span;
      if (x >= a && x < b) {
        best = i;
        gap = -1;
      } else if (gap >= 0 && Math.abs((a + b) / 2 - x) < gap) {
        best = i;
        gap = Math.abs((a + b) / 2 - x);
      }
    });
    return best;
  };
  const centerDay = (): number => dayAt(pos + viewW / 2);
  const dayCenter = (i: number): number => {
    const sp2 = geo?.days[i]?.span;
    return sp2 ? (sp2[0] + sp2[1]) / 2 : 0;
  };

  // ---- the page's own DOM over the world ----
  const build = () => {
    const g = geo!;
    jview.innerHTML = "";
    world = document.createElement("div");
    world.className = "jworld";
    world.style.cssText = `top:${HEAD * sp}px;height:${(jview.clientHeight - (HEAD + FOOT) * sp)}px;width:${g.total * sp}px`;
    const leadEl = document.createElement("section");
    leadEl.className = "lead";
    leadEl.style.cssText = `left:${g.lead[0] * sp}px;width:${g.lead[1] * sp}px;height:${g.height * sp + (g.top * 2) * sp}px`;
    world.append(leadEl);
    plate = document.createElement("div");
    plate.className = `plate${paperSkin ? " paper" : ""}`;
    plate.style.cssText = `left:${g.painting[0] * sp}px;top:${g.top * sp}px;width:${g.painting[1] * sp}px;height:${g.height * sp}px`;
    world.append(plate);
    jview.append(world);
    for (const side of ["l", "r"] as const) {
      const cv = document.createElement("canvas");
      cv.className = `roller ${side}`;
      cv.style.top = `${(HEAD - 10) * sp}px`;
      jview.append(cv);
    }
    void buildLead(leadEl, g, gen);
    if (g.days.every((d) => d.span === null) && g.days.length === 0) {
      const n = document.createElement("div");
      n.className = "jnote";
      n.textContent = t("還沒有打過字的日子。打幾個字，路就長出來了。", "还没有打过字的日子。打几个字，路就长出来了。");
      jview.append(n);
    }
  };

  // ---- the lead (引首) ----
  const buildLead = async (el: HTMLElement, g: Geo, mine: number) => {
    const l = lead!;
    const dark = isDark();
    const inner = Math.floor(g.lead[1] - 52);
    const cols = jview.clientHeight / sp >= 900 && g.lead[1] < 640 ? 4 : 6;
    const gap = 8;
    const tw = Math.floor((inner - gap * (cols - 1)) / cols);
    const th = Math.round(tw * 0.56);
    const half = Math.floor((inner - 16) / 2);
    const bandsH = [...Array(5)].map((_, i) => s.marks.filter((m) => m.size === i).length);
    void bandsH;
    el.innerHTML = `
      <div class="lead-in" style="padding:${18 * sp}px ${26 * sp}px">
        <div class="big"><b>${fmt(l.characters)}</b><span>${t(`個字，一路打過來。走過 ${fmt(l.stop)} 個地方。`, `个字，一路打过来。走过 ${fmt(l.stop)} 个地方。`)}</span></div>
        <h3>${t("去過的地方", "去过的地方")}</h3>
        <p class="note">${t("走得夠遠就會到下一個地方。有的地方，很少遇見。", "走得够远就会到下一个地方。有的地方，很少遇见。")}</p>
        <div class="places" style="grid-template-columns:repeat(${cols}, ${tw * sp}px);gap:${gap * sp}px">${l.names
          .map(
            (n, i) =>
              `<div class="pl" data-i="${i}"><span class="pic" style="width:${tw * sp}px;height:${th * sp}px"></span><b>${l.visits[i] > 0 ? n : "？"}</b><small>${l.visits[i] > 0 ? t(`到過 ${l.visits[i]} 次`, `到过 ${l.visits[i]} 次`) : t("還沒遇見", "还没遇见")}</small></div>`,
          )
          .join("")}</div>
        <div class="two-col">
          <div><h3>${t("一天裡的時刻", "一天里的时刻")}</h3><div class="pic" data-pic="parts"></div></div>
          <div><h3>${t("山有多高", "山有多高")}</h3><div class="pic" data-pic="bands"></div></div>
        </div>
        <h3>${t("兩隻貓", "两只猫")}</h3>
        <p class="note">${t(`山有多高，是牠們和你有多熟。摸了：米米莫 ${fmt(l.mimimo.pets)} 下，莫莫 ${fmt(l.momo.pets)} 下。`, `山有多高，是它们和你有多熟。摸了：米米莫 ${fmt(l.mimimo.pets)} 下，莫莫 ${fmt(l.momo.pets)} 下。`)}</p>
        <div class="pic" data-pic="cats"></div>
      </div>`;
    const put = async (host: Element, kind: string, w: number, h: number, index = 0) => {
      const p = await picture("lead_picture", { ask: { kind, w, h, index, dark, k } });
      if (mine !== gen || disposed) return;
      host.append(canvasOf(p));
    };
    const jobs: Promise<void>[] = [];
    el.querySelectorAll<HTMLElement>(".pl").forEach((pl) => jobs.push(put(pl.querySelector(".pic")!, "place", tw, th, Number(pl.dataset.i))));
    jobs.push(put(el.querySelector('[data-pic="parts"]')!, "parts", half, 0));
    jobs.push(put(el.querySelector('[data-pic="bands"]')!, "bands", half, 0));
    jobs.push(put(el.querySelector('[data-pic="cats"]')!, "cats", inner, 0));
    await Promise.all(jobs);
  };

  // ---- tiles ----
  const spanOf = (i: number): [number, number] | null => {
    const [px, pw] = geo!.painting;
    const x0 = Math.max(i * TILE, Math.ceil(px));
    const x1 = Math.min((i + 1) * TILE, Math.floor(px + pw));
    return x1 > x0 ? [x0, x1] : null;
  };

  const pump = () => {
    if (!geo || disposed) return;
    const lo = pos - viewW;
    const hi = pos + 2 * viewW;
    const mid = pos + viewW / 2;
    const want: { i: number; d: number }[] = [];
    for (let i = Math.floor(lo / TILE); i <= Math.floor(hi / TILE); i++) {
      const sp2 = spanOf(i);
      if (!sp2 || sp2[1] < lo || sp2[0] > hi) continue;
      if (!tiles.get(i)?.asked) want.push({ i, d: Math.abs((sp2[0] + sp2[1]) / 2 - mid) });
    }
    want.sort((a, b) => a.d - b.d);
    for (const { i } of want) {
      if (inflight >= AHEAD) break;
      void request(i);
    }
    // what is far behind goes
    for (const [i, tile] of tiles) {
      const sp2 = spanOf(i);
      if (sp2 && (sp2[1] < pos - 3 * viewW || sp2[0] > pos + 4 * viewW) && tile.asked) {
        tile.el?.remove();
        tiles.delete(i);
      }
    }
  };

  const request = async (i: number) => {
    const sp2 = spanOf(i)!;
    const mine = gen;
    tiles.set(i, { el: null, asked: true });
    inflight++;
    const t0 = performance.now();
    try {
      const p = await picture("scroll_tile", { x: sp2[0], w: sp2[1] - sp2[0], k });
      if (mine !== gen || disposed) return;
      const cv = canvasOf(p, "tile");
      cv.style.left = `${(sp2[0] - geo!.painting[0]) * sp}px`;
      plate.append(cv);
      tiles.set(i, { el: cv, asked: true });
      latency.push(performance.now() - t0);
    } catch {
      if (mine === gen) tiles.delete(i);
    } finally {
      if (mine === gen) inflight--;
      pump();
    }
  };

  // ---- the rollers ----
  const rollers = () => {
    if (!geo) return;
    const total = Math.max(1, geo.total);
    for (const [side, wound] of [["l", pos], ["r", maxPos() - pos]] as const) {
      const cv = jview.querySelector<HTMLCanvasElement>(`.roller.${side}`);
      if (!cv) continue;
      const th = wound >= 6 ? 10 + Math.round(16 * Math.sqrt(Math.min(1, wound / total))) : 0;
      const key = `${th}`;
      if (rollerKey[side] === key) continue;
      rollerKey[side] = key;
      const mine = gen;
      void picture("scroll_roller", { right: side === "r", wound: th ? Math.max(wound, 6) : 0, k }).then((p) => {
        if (mine !== gen || disposed) return;
        cv.style.display = p.w ? "" : "none";
        if (p.w) paint(cv, p);
      });
    }
  };

  // ---- moving ----
  const applyPos = () => {
    world.style.transform = `translateX(${-pos * sp}px)`;
    rollers();
    app.querySelector<HTMLElement>(".lead")?.classList.toggle("far", pos > geo!.lead[1]);
  };

  const tick = (stamp: number) => {
    if (disposed) return;
    if (bench) frames.push(stamp);
    if (geo && pos !== target) {
      const d = target - pos;
      pos = Math.abs(d) < 1 ? target : Math.round(pos + d * 0.3);
      applyPos();
      pump();
    }
    raf = requestAnimationFrame(tick);
  };

  const go = (x: number, instant = false) => {
    target = Math.round(Math.min(maxPos(), Math.max(0, x)));
    if (instant) {
      pos = target;
      applyPos();
      pump();
    }
  };

  const stepDay = (dir: 1 | -1) => {
    if (!geo) return;
    const here = dayAt(pos + viewW / 2);
    const mid = pos + viewW / 2;
    let i = here;
    if (here < 0) return;
    const c = dayCenter(here);
    // already on a day: the next one; else the nearest one that way
    if (dir === 1 ? c <= mid + 1 : c >= mid - 1) {
      do i += dir;
      while (geo.days[i] && !geo.days[i].span);
    }
    if (!geo.days[i]?.span) return;
    go(dayCenter(i) - viewW / 2);
  };

  // ---- 截一段 ----
  let picking = false;
  let sel: { from: number; to: number } | null = null;
  const card = () => app.querySelector<HTMLElement>(".sharecard");

  const xOf = (e: MouseEvent) => pos + (e.clientX - jview.getBoundingClientRect().left) / sp;

  const startPick = () => {
    if (!geo) return;
    picking = !picking;
    jview.classList.toggle("picking", picking);
    app.querySelector("[data-share]")?.classList.toggle("on", picking);
    card()?.remove();
    world.querySelector(".pickbox")?.remove();
    sel = null;
    if (picking) showCard(null);
  };

  const rangeText = (a: number, b: number): { text: string; hills: number; places: number } => {
    const days = geo!.days.slice(a, b + 1);
    const da = ymd(days[0].day);
    const db = ymd(days[days.length - 1].day);
    const places = new Set(days.map((d) => d.place).filter((p) => p !== null)).size;
    return { text: `${da.y} ${t("年", "年")} ${monthName(da.m)} ${da.d} ${t("日", "日")} — ${monthName(db.m)} ${db.d} ${t("日", "日")}`, hills: days.length, places };
  };

  const showCard = (pick: { from: number; to: number } | null) => {
    card()?.remove();
    const el = document.createElement("div");
    el.className = "sharecard";
    const connected = !!store.steam?.connected;
    if (!pick) {
      el.innerHTML = `<h4>${t("截一段", "截一段")}</h4><p class="note">${t("在畫上從一天拖到另一天。", "在画上从一天拖到另一天。")}</p><div class="row"><button class="btn" data-x>${t("不要了", "不要了")}</button></div>`;
    } else {
      const r = rangeText(pick.from, pick.to);
      el.innerHTML = `<h4>${t("截一段", "截一段")}</h4><p>${esc(r.text)}</p>
        <p class="note">${t(`${r.hills} 座山・走過 ${r.places} 種地方`, `${r.hills} 座山・走过 ${r.places} 种地方`)}</p>
        <p class="note">${t("只有畫、日子與地方的名字，沒有你打過的字；數字預設不寫。", "只有画、日子与地方的名字，没有你打过的字；数字默认不写。")}</p>
        <p class="status note" aria-live="polite"></p>
        <div class="row"><button class="btn go" data-file>${t("存成長條", "存成长条")}</button>${connected ? `<button class="btn" data-steam>${t("放進 Steam 截圖", "放进 Steam 截图")}</button>` : ""}<button class="btn" data-x>${t("不要了", "不要了")}</button></div>`;
    }
    jview.append(el);
    el.querySelector("[data-x]")?.addEventListener("click", () => startPick());
    for (const [attr, target2] of [["data-file", "file"], ["data-steam", "steam"]] as const)
      el.querySelector(`[${attr}]`)?.addEventListener("click", () => void publish(pick!, target2, el));
  };

  const publish = async (pick: { from: number; to: number }, dest: "file" | "steam", el: HTMLElement) => {
    const status = el.querySelector<HTMLElement>(".status")!;
    el.querySelectorAll("button").forEach((b) => ((b as HTMLButtonElement).disabled = true));
    status.textContent = t("畫著…", "画着…");
    try {
      const shotK = Math.max(1, k);
      const [p, place] = await Promise.all([picture("scroll_share", { from: pick.from, to: pick.to, k: shotK }), invoke<string>("scroll_last_place", { from: pick.from, to: pick.to })]);
      const png = await sharePng(p);
      const a = ymd(geo!.days[pick.from].day);
      const b = ymd(geo!.days[pick.to].day);
      const name = `${t("喵撲行旅", "喵扑行旅")} ${iso(geo!.days[pick.from].day)} ${a.y === b.y ? "" : ""}— ${iso(geo!.days[pick.to].day)}.png`;
      const out = await invoke<{ kind: string; path?: string; location?: string; why?: string }>("share_publish", png, {
        headers: { name: encodeURIComponent(name), location: encodeURIComponent(place), target: dest },
      });
      status.textContent =
        out.kind === "steam" ? t("已放進 Steam 截圖。", "已放进 Steam 截图。") : out.kind === "saved" ? t("存好了。", "存好了。") : out.kind === "cancelled" ? t("沒有存。", "没有存。") : t("沒存成。", "没存成。");
    } catch {
      status.textContent = t("沒存成。", "没存成。");
    }
    el.querySelectorAll("button").forEach((b) => ((b as HTMLButtonElement).disabled = false));
  };

  /** The picture with, under a paper skin, the clay cats leaning on the plate and the wordmark (the engine says where). */
  const sharePng = async (p: Picture): Promise<Uint8Array> => {
    const cv = canvasOf(p);
    cv.style.cssText = "";
    const ctx = cv.getContext("2d")!;
    if (word(p, 0)) {
      // The engine says: the first cat's left x, the plate's top edge, the cats' height h. Each clay cat's top is at
      // edge - 0.84 h, the second 0.82 h to the right of the first (the prototype's `clayOnEdge`).
      const [cx, edge, ch] = [float(p, 1), float(p, 2), float(p, 3)];
      const load = (src: string) =>
        new Promise<HTMLImageElement>((ok) => {
          const im = new Image();
          im.onload = () => ok(im);
          im.onerror = () => ok(im);
          im.src = src;
        });
      const [a, b] = await Promise.all([load(CAT.mimimoPeek), load(CAT.momoPeek)]);
      [a, b].forEach((im, n) => {
        if (!im.naturalHeight) return;
        const h = ch * k;
        const w = (im.naturalWidth / im.naturalHeight) * h;
        ctx.drawImage(im, Math.round((cx + n * 0.82 * ch) * k), Math.round((edge - 0.84 * ch) * k), Math.round(w), Math.round(h));
      });
    }
    if (word(p, 4)) {
      const [tx, ty] = [float(p, 5), float(p, 6)];
      const dark = isDark();
      const tk = tokensOf(skinKey, store.settings?.settings.palette ?? null, dark);
      ctx.fillStyle = tk.ink;
      ctx.font = `700 ${26 * k}px ${getComputedStyle(document.body).getPropertyValue("--face")}`;
      ctx.fillText(t("喵撲", "喵扑"), tx * k, ty * k);
      ctx.fillStyle = tk.mute;
      ctx.font = `600 ${12 * k}px ${getComputedStyle(document.body).getPropertyValue("--face")}`;
      ctx.fillText("Meowpuut", tx * k + 66 * k, ty * k);
    }
    const blob = await new Promise<Blob | null>((ok) => cv.toBlob(ok, "image/png"));
    if (!blob) throw new Error("no png");
    return new Uint8Array(await blob.arrayBuffer());
  };

  // ---- input ----
  let drag: { x0: number } | null = null;
  const onDown = (e: MouseEvent) => {
    if (!picking || !geo || (e.target as HTMLElement).closest(".sharecard,.lead")) return;
    const i = dayAt(xOf(e));
    if (i < 0) return;
    drag = { x0: xOf(e) };
    sel = { from: i, to: i };
    e.preventDefault();
  };
  const onMove = (e: MouseEvent) => {
    if (!drag || !geo) return;
    const i = dayAt(xOf(e));
    const j = dayAt(drag.x0);
    if (i < 0 || j < 0) return;
    sel = { from: Math.min(i, j), to: Math.max(i, j) };
    let box = world.querySelector<HTMLElement>(".pickbox");
    if (!box) {
      box = document.createElement("div");
      box.className = "pickbox";
      world.append(box);
    }
    const a = geo.days[sel.from].span!;
    const b = geo.days[sel.to].span!;
    box.style.cssText = `left:${a[0] * sp}px;width:${(b[1] - a[0]) * sp}px;top:${geo.top * sp}px;height:${geo.height * sp}px`;
  };
  const onUp = () => {
    if (!drag) return;
    drag = null;
    if (sel) showCard(sel);
  };
  const onWheel = (e: WheelEvent) => {
    const leadEl = (e.target as HTMLElement).closest<HTMLElement>(".lead");
    if (leadEl && Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
      const room = leadEl.scrollHeight - leadEl.clientHeight;
      const next = leadEl.scrollTop + e.deltaY;
      if (room > 0 && next >= 0 && next <= room && pos < (geo?.lead[1] ?? 0) * 0.5) {
        leadEl.scrollTop = next;
        e.preventDefault();
        return;
      }
    }
    const d = (Math.abs(e.deltaY) >= Math.abs(e.deltaX) ? e.deltaY : e.deltaX) * (e.deltaMode === 1 ? 16 : 1);
    go(target + d / sp);
    e.preventDefault();
  };
  const onKey = (e: KeyboardEvent) => {
    if (!geo || e.metaKey || e.ctrlKey || e.isComposing || (e.target as HTMLElement).tagName === "INPUT") return;
    if (e.key === "ArrowLeft") stepDay(-1);
    else if (e.key === "ArrowRight") stepDay(1);
    else if (e.key === "Home") go(0);
    else if (e.key === "End") go(maxPos());
    else if (e.key === "Escape" && picking) startPick(); // the first Esc ends the selection; main.ts leaves Esc alone while it is on
    else return;
    e.preventDefault();
    e.stopPropagation();
  };

  let resizeTimer = 0;
  const onResize = () => {
    clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(() => void open("keep"), 160);
  };

  shell();
  jview.addEventListener("wheel", onWheel, { passive: false });
  jview.addEventListener("mousedown", onDown);
  addEventListener("mousemove", onMove);
  addEventListener("mouseup", onUp);
  addEventListener("keydown", onKey, true);
  addEventListener("resize", onResize);
  raf = requestAnimationFrame(tick);
  void open(params.get("at") === "lead" ? "lead" : "now").then(() => {
    const day = Number(params.get("day"));
    if (day && geo) {
      const i = geo.days.findIndex((d) => d.day === day);
      if (i >= 0 && geo.days[i].span) go(dayCenter(i) - viewW / 2, true);
    }
    const at = params.get("pos");
    if (at !== null && geo) go(Number(at), true);
    if (params.get("pick") && geo) {
      const [f, tt2] = params.get("pick")!.split("-").map(Number);
      startPick();
      sel = { from: Math.max(0, geo.days.length - 1 - f), to: geo.days.length - 1 - tt2 };
      sel = { from: Math.min(sel.from, sel.to), to: Math.max(sel.from, sel.to) };
      const a = geo.days[sel.from].span;
      const b = geo.days[sel.to].span;
      if (a && b) {
        const box = document.createElement("div");
        box.className = "pickbox";
        box.style.cssText = `left:${a[0] * sp}px;width:${(b[1] - a[0]) * sp}px;top:${geo.top * sp}px;height:${geo.height * sp}px`;
        world.append(box);
        showCard(sel);
        // &shoot=1 (previews): press 「存成長條」 for me.
        if (params.get("shoot")) setTimeout(() => app.querySelector<HTMLElement>("[data-file]")?.click(), 400);
      }
    }
    if (bench) runBench();
  });

  /** MEOWPUUT_START=journey?bench=1&lens=day: scrolls the whole scroll back and forth for 8 seconds and reports the frame
   *  times and the tile latency to the terminal (the Rust side prints them in debug builds). */
  const runBench = () => {
    const begin = performance.now();
    frames.length = 0;
    latency.length = 0;
    let dir = 1;
    const drive = () => {
      if (disposed) return;
      if (performance.now() - begin < 8000) {
        pos = target = Math.max(0, Math.min(maxPos(), pos + dir * 24));
        if (pos === 0 || pos === maxPos()) dir = -dir;
        applyPos();
        pump();
        requestAnimationFrame(drive);
        return;
      }
      const dt = frames.slice(1).map((v, i) => v - frames[i]).sort((a, b) => a - b);
      const q = (a: number[], p: number) => (a.length ? a[Math.min(a.length - 1, Math.floor(a.length * p))] : 0);
      const lt = [...latency].sort((a, b) => a - b);
      report(
        JSON.stringify({
          lens, k, scale: devicePixelRatio, viewPoints: [viewW, Math.floor(jview.clientHeight / sp)], totalPoints: geo!.total,
          frames: dt.length, frameMsMean: +(dt.reduce((a, b) => a + b, 0) / Math.max(1, dt.length)).toFixed(2), frameMsP95: +q(dt, 0.95).toFixed(2), frameMsMax: +q(dt, 1).toFixed(2),
          droppedOver25ms: dt.filter((v) => v > 25).length, over50ms: dt.filter((v) => v > 50).length,
          tiles: lt.length, tileMsMean: +(lt.reduce((a, b) => a + b, 0) / Math.max(1, lt.length)).toFixed(1), tileMsP50: +q(lt, 0.5).toFixed(1), tileMsP95: +q(lt, 0.95).toFixed(1), tileMsMax: +q(lt, 1).toFixed(1),
        }),
      );
    };
    requestAnimationFrame(drive);
  };

  return () => {
    disposed = true;
    gen++;
    cancelAnimationFrame(raf);
    clearTimeout(resizeTimer);
    removeEventListener("mousemove", onMove);
    removeEventListener("mouseup", onUp);
    removeEventListener("keydown", onKey, true);
    removeEventListener("resize", onResize);
  };
}
