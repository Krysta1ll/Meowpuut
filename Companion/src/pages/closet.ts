// 鍵盤衣櫥 (FINAL.md §2.3, the c-porch prototype): five skins in the phone's numbering. One outfit decides three
// things: the candidate window, the home keyboard and the pages' paper. Each card previews the candidate window and
// the keycaps in its material.
//
// Honest about today: the companion dresses its own pages and the home keyboard in all five. The candidate window
// wears 日常玻璃 (on macOS with its six colour families and three shapes, written to settings.json; on Windows fixed to
// 膠囊・玫瑰奶茶) or 字景; wearing 字景 writes `candidateSkin` (macOS) / `CandidateSkin` (Windows) for the input method
// (the companion does it, see settings::sync_candidate_skin), every other skin puts the glass back. The other three
// say so on their card.

import { api, type SkinKey } from "../api";
import { catPair } from "../art";
import { esc, t, tt } from "../i18n";
import { nav } from "../nav";
import { store } from "../store";
import { invoke } from "@tauri-apps/api/core";
import { canvasOf, hasEngine, kOf, pointCss, picture, reducedMotion, dpr } from "../glyph";
import { ORDER, PALETTE_KEYS, SHAPES, SKINS, applySkin, isDark, paletteName, paletteSwatch } from "../skins";

function candidate(key: SkinKey): string {
  if (key === "glyph")
    return hasEngine
      ? `<div class="strip" data-strip></div>`
      : `<div class="glyph-slot cand"><span>${t("字景候選窗：太陽在水上的倒影", "字景候选窗：太阳在水上的倒影")}</span><small>${t("穿上後，輸入法的候選窗就是這一條", "穿上后，输入法的候选窗就是这一条")}</small></div>`;
  const cats = catPair(key);
  const words = ["你好", "妳好", "你好呀", "擬好"];
  return `
    <div class="gc ${cats.kind}">
      <div class="gc-cats"><img class="l" src="${cats.left}" alt=""><img class="r" src="${cats.right}" alt=""></div>
      <div class="gc-bar">${words.map((w, i) => `<span class="c${i === 0 ? " on" : ""}"><small>${i + 1}</small>${w}</span>`).join("")}<span class="chev">﹀</span><i class="caus"></i></div>
    </div>`;
}

const minikeys = () =>
  `<div class="mini-keys">${["q", "w", "e"].map((k) => `<div class="key"><div class="cap"><b class="ch">${k}</b></div></div>`).join("")}<div class="key accent"><div class="cap"><b class="sym">↵</b></div></div></div>`;

export function renderCloset(app: HTMLElement): void {
  const win = store.info.platform === "windows";
  const worn = store.companion.skin;
  const st = store.settings?.settings;
  const can = new Set(store.settings?.candidateSkins ?? ["daily"]);
  const dark = isDark();

  const options = (key: SkinKey): string => {
    if (key === "daily") {
      if (win || !st?.palette)
        return `<p>${t("Windows 的候選窗目前固定是膠囊・玫瑰奶茶。", "Windows 的候选窗目前固定是胶囊・玫瑰奶茶。")}</p>`;
      return `
        <div class="opt"><span>${t("色系", "色系")}</span><span class="dots">${PALETTE_KEYS.map((p) => `<button class="dot${p === st.palette ? " on" : ""}" data-palette="${p}" title="${esc(paletteName(p))}" style="--c:${paletteSwatch(p, dark)}"></button>`).join("")}</span><small>${esc(paletteName(st.palette))}</small></div>
        <div class="opt"><span>${t("形狀", "形状")}</span><span class="seg small">${Object.entries(SHAPES).map(([k, name]) => `<button data-shape="${k}" class="${k === st.glassShape ? "on" : ""}">${t(...name)}</button>`).join("")}</span></div>`;
    }
    return `<p>${t(...SKINS[key].note)}</p>${can.has(key) ? "" : `<p class="later">${t("候選窗之後才換得上；現在穿上，換的是首頁那把鍵盤和頁面的紙。", "候选窗之后才换得上；现在穿上，换的是首页那把键盘和页面的纸。")}</p>`}`;
  };

  app.innerHTML = `
    <header class="page-hd">
      <h1>${t("鍵盤衣櫥", "键盘衣橱")}</h1>
      <span class="mut">${t("一件衣服決定三樣東西：候選窗、首頁那把鍵盤、頁面的紙。", "一件衣服决定三样东西：候选窗、首页那把键盘、页面的纸。")}</span>
    </header>
    <div class="closet">
      ${ORDER.map(
        (key) => `
        <div class="wrow${key === worn ? " on" : ""}" data-skin="${key}">
          <div class="wn"><small>${SKINS[key].no}</small><b>${t(...SKINS[key].name)}</b>
            <button class="wear" data-wear="${key}">${key === worn ? t("正在穿", "正在穿") : t("穿上", "穿上")}</button></div>
          <div class="wpv">${candidate(key)}</div>
          <div class="wkeys">${minikeys()}</div>
          <div class="wopt">${options(key)}</div>
        </div>`,
      ).join("")}
    </div>
    <footer class="wfoot">
      <span>${t("候選字大小", "候选字大小")} <b>${tt(({ small: ["小", "小"], standard: ["標準", "标准"], large: ["大", "大"] } as const)[st?.candidateTextSize ?? "standard"])}</b> <a href="#/settings">${t("鍵盤設定 ›", "键盘设置 ›")}</a></span>
      <span>${t("遊戲裡", "游戏里")} <b>${t("收成一行", "收成一行")}</b></span>
      <span>${t("桌面貓", "桌面猫")} <a href="#/settings?at=desk">${t("鍵盤設定 ›", "键盘设置 ›")}</a></span>
      <span>${t("增加對比、減少動態", "增加对比、减少动态")} <b>${t("跟著系統", "跟着系统")}</b></span>
    </footer>`;

  // Each row wears its own skin, so the previews show the real material.
  for (const rowEl of app.querySelectorAll<HTMLElement>(".wpv, .wkeys"))
    applySkin(rowEl, rowEl.closest<HTMLElement>(".wrow")!.dataset.skin as SkinKey, rowEl.closest<HTMLElement>(".wrow")!.dataset.skin === "daily" ? st?.palette ?? null : null, dark);

  const strip = app.querySelector<HTMLElement>("[data-strip]");
  if (strip) void drawStrip(strip, dark);

  app.addEventListener("click", async (e) => {
    const el = e.target as HTMLElement;
    const wear = el.closest<HTMLElement>("[data-wear]")?.dataset.wear as SkinKey | undefined;
    const palette = el.closest<HTMLElement>("[data-palette]")?.dataset.palette;
    const shape = el.closest<HTMLElement>("[data-shape]")?.dataset.shape;
    try {
      if (wear && wear !== worn) store.companion = await api.updateCompanion({ skin: wear });
      else if (palette) store.settings = await api.writeSetting({ key: "palette", value: palette });
      else if (shape) store.settings = await api.writeSetting({ key: "glassShape", value: shape });
      else return;
    } catch (error) {
      store.error = String(error);
    }
    nav.refresh();
  });
}

interface StripLayout {
  w: number;
  h: number;
  cells: { numberX: number; textX: number; baseline: number; numberColor: number; textColor: number; halo: number | null; selected: boolean }[];
}

const argb = (c: number): string => `rgba(${(c >> 16) & 255},${(c >> 8) & 255},${c & 255},${((c >>> 24) / 255).toFixed(3)})`;

/** The 字景 candidate window (FINAL.md §3), the engine's `strip`: the place the cats are in, the sun behind the selected
 *  word, the cats on the ridge. The words are the platform's: the page measures them in the pixel face at 12 px, the
 *  engine says where each goes, and they are set over the picture at 12 × k pixels with the engine's halo. */
async function drawStrip(host: HTMLElement, dark: boolean): Promise<void> {
  const k = kOf();
  const sp = pointCss(k);
  const face = '"Meowpuut Pixel A"';
  await document.fonts.load(`12px ${face}`).catch(() => []);
  const measure = document.createElement("canvas").getContext("2d")!;
  measure.font = `12px ${face}`;
  const words = ["你好", "妳好", "你好呀", "擬好"];
  const cells = words.map((w, i) => ({ number: measure.measureText(String(i + 1)).width, text: measure.measureText(w).width, paw: 0 }));
  const here = await invoke<{ place: number }>("glyph_here").catch(() => ({ place: 0 }));
  const ask = { dark, place: here.place, k, still: reducedMotion(), cells, selected: 0 };
  const [layout, pic] = await Promise.all([invoke<StripLayout>("strip_layout", { ask }), picture("strip_picture", { ask })]);
  if (!host.isConnected) return;
  const cv = canvasOf(pic);
  const ctx = cv.getContext("2d")!;
  ctx.font = `${12 * k}px ${face}`;
  ctx.textBaseline = "alphabetic";
  const put = (text: string, x: number, baseline: number, ink: number, halo: number | null) => {
    if (halo !== null) {
      ctx.fillStyle = argb(halo);
      for (let a = -3; a <= 3; a++)
        for (let b = -3; b <= 3; b++) if (a * a + b * b > 0 && a * a + b * b <= 10) ctx.fillText(text, x * k + a * k, baseline * k + b * k);
    }
    ctx.fillStyle = argb(ink);
    ctx.fillText(text, x * k, baseline * k);
  };
  layout.cells.forEach((c, i) => {
    put(String(i + 1), c.numberX, c.baseline, c.numberColor, c.halo);
    put(words[i], c.textX, c.baseline, c.textColor, c.halo);
  });
  host.style.width = `${pic.w / dpr()}px`;
  host.replaceChildren(cv);
  void sp;
}
