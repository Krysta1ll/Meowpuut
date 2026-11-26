// 成就牆: all 82 achievements (80 Steam + 2 local) with progress bar, category filters, rarity frames,
// and an automatic Vite static-asset hook for `Companion/src/assets/achievements/*.png` (with pixel-emblem fallback).

import { api, type AchievementCatalogItem, type AchievementTier } from "../api";
import { esc, t, tt } from "../i18n";
import { nav } from "../nav";

type Filter = "all" | "unlocked" | "locked" | "hard" | "hidden";

// Static asset hook: once the 160 achievement PNGs are copied into `Companion/src/assets/achievements/`,
// Vite bundles them automatically and they light up here without touching any code.
const ICON_ASSETS = import.meta.glob<string>("../assets/achievements/*.png", {
  eager: true,
  import: "default",
});

function resolveIcon(item: AchievementCatalogItem): string | null {
  const num = String(item.index).padStart(2, "0");
  const stateA = item.unlocked ? "unlocked" : "locked";
  const stateB = item.unlocked ? "on" : "off";
  const candidates = [
    `../assets/achievements/${item.api}_${stateA}.png`,
    `../assets/achievements/${item.api}_${stateB}.png`,
    `../assets/achievements/${num}_${item.api}_${stateA}.png`,
    `../assets/achievements/${num}_${item.api}_${stateB}.png`,
    `../assets/achievements/${item.api}.png`,
  ];
  for (const key of candidates) {
    if (ICON_ASSETS[key]) return ICON_ASSETS[key];
  }
  for (const key of Object.keys(ICON_ASSETS)) {
    const filename = key.split("/").pop() || "";
    if (filename.startsWith(`${num}-`)) {
      if (item.unlocked && !filename.includes("_locked")) return ICON_ASSETS[key];
      if (!item.unlocked && filename.includes("_locked")) return ICON_ASSETS[key];
    }
  }
  return null;
}

function fallbackGlyph(item: AchievementCatalogItem): string {
  const api = item.api;
  if (api.startsWith("CHARS_")) return "⌨️";
  if (api.startsWith("DAYS_") || api.startsWith("PEAK_") || api.startsWith("SNOW_")) return "⛰️";
  if (api.startsWith("PLACE_") || api === "PLACES_12") return "🧭";
  if (api.startsWith("FEST_")) return "🏮";
  if (api.startsWith("PET_") || api.startsWith("BOND_")) return "🐾";
  if (api.startsWith("PLAYS_") || api.startsWith("FALLS_")) return "🧶";
  if (api.startsWith("CAT_")) return "🐱";
  if (api.startsWith("SCROLL_")) return "📜";
  if (api === "MASTER_MEOWPUUT") return "👑";
  if (api === "LOCAL_DAWN") return "🌅";
  if (api === "LOCAL_LATE_NIGHT") return "🦉";
  return "⭐";
}

const TIER_LABEL: Record<AchievementTier, [string, string]> = {
  normal: ["普通", "普通"],
  hard: ["難", "难"],
  very_hard: ["極難", "极难"],
  legend: ["傳說", "传说"],
  local: ["本機限定", "本机限定"],
};

function matches(item: AchievementCatalogItem, filter: Filter): boolean {
  if (filter === "unlocked") return item.unlocked;
  if (filter === "locked") return !item.unlocked;
  if (filter === "hard") return item.tier === "hard" || item.tier === "very_hard" || item.tier === "legend";
  if (filter === "hidden") return item.hidden;
  return true;
}

export function renderTrophies(app: HTMLElement): void {
  let items: AchievementCatalogItem[] = [];
  let loaded = false;
  let filter: Filter = "all";

  const draw = () => {
    if (!loaded) {
      app.innerHTML = `<header class="page-hd"><h1>${t("成就牆", "成就墙")}</h1><span class="mut">${t("正在整理獎盃架…", "正在整理奖杯架…")}</span></header>`;
      return;
    }

    const total = items.length;
    const unlockedCount = items.filter((i) => i.unlocked).length;
    const steamTotal = items.filter((i) => i.steam).length;
    const steamUnlocked = items.filter((i) => i.steam && i.unlocked).length;
    const localUnlocked = items.filter((i) => !i.steam && i.unlocked).length;
    const hardTotal = items.filter((i) => i.tier === "hard" || i.tier === "very_hard" || i.tier === "legend").length;
    const hardUnlocked = items.filter((i) => i.unlocked && (i.tier === "hard" || i.tier === "very_hard" || i.tier === "legend")).length;
    const hiddenTotal = items.filter((i) => i.hidden).length;
    const pct = total ? Math.round((unlockedCount / total) * 100) : 0;

    const shown = items.filter((i) => matches(i, filter));

    const filters: [Filter, [string, string], number][] = [
      ["all", ["全部", "全部"], total],
      ["unlocked", ["已解鎖", "已解锁"], unlockedCount],
      ["locked", ["未解鎖", "未解锁"], total - unlockedCount],
      ["hard", ["高難與傳說", "高难与传说"], hardTotal],
      ["hidden", ["隱藏彩蛋", "隐藏彩蛋"], hiddenTotal],
    ];

    const cards = shown
      .map((item) => {
        const masked = item.hidden && !item.unlocked;
        const iconUrl = resolveIcon(item);
        const badgeHtml = iconUrl
          ? `<img class="tr-png tr-sticker" src="${iconUrl}" alt="${esc(tt(item.name))}">`
          : `<div class="tr-px tr-sticker" aria-hidden="true"><span>${masked ? "？" : fallbackGlyph(item)}</span><small>#${String(item.index).padStart(2, "0")}</small></div>`;
        const tags: string[] = [t(...TIER_LABEL[item.tier])];
        if (item.hidden) tags.push(t("隱藏", "隐藏"));
        if (!item.enabled) tags.push(t("敬請期待", "敬请期待"));

        return `<article class="tr-card tier-${item.tier}${item.unlocked ? " unlocked" : " locked"}">
          <div class="tr-ico">${badgeHtml}</div>
          <div class="tr-body">
            <div class="tr-top">
              <b>${masked ? t("？？？隱藏彩蛋", "？？？隐藏彩蛋") : esc(tt(item.name))}</b>
              <span class="tr-idx">${item.steam ? `#${String(item.index).padStart(2, "0")}` : t("本機", "本机")}</span>
            </div>
            <p>${masked ? t("某個偷偷藏起來的小貓彩蛋，達成後自動點亮揭曉！", "某个偷偷藏起来的小猫彩蛋，达成后自动点亮揭晓！") : esc(tt(item.desc))}</p>
            <div class="tr-meta">
              ${tags.map((tag) => `<span class="tr-tag">${esc(tag)}</span>`).join("")}
              <span class="tr-st">${item.unlocked ? t("✓ 已咪到", "✓ 已咪到") : t("未解鎖", "未解锁")}</span>
            </div>
          </div>
        </article>`;
      })
      .join("");

    app.innerHTML = `
      <header class="page-hd tr-hd">
        <div>
          <h1>${t("成就牆", "成就墙")}</h1>
          <span class="mut">${t("80 枚 Steam 像素貼紙成就 + 2 枚本機限定成就。不催你、不過期，純遊戲也能 100% 全部解鎖。", "80 枚 Steam 像素贴纸成就 + 2 枚本机限定成就。不催你、不过期，纯游戏也能 100% 全部解锁。")}</span>
        </div>
        <button class="btn" data-go-lounge>${t("🐱 回小貓客廳", "🐱 回小猫客厅")}</button>
      </header>

      <section class="tr-overview">
        <div class="tr-ov-top">
          <div>
            <b>${unlockedCount} / ${total}</b>
            <span class="mut">（${pct}%）</span>
          </div>
          <div class="tr-ov-pills">
            <span>Steam <b>${steamUnlocked}/${steamTotal}</b></span>
            <span>${t("高難／傳說", "高难／传说")} <b>${hardUnlocked}/${hardTotal}</b></span>
            <span>${t("本機彩蛋", "本机彩蛋")} <b>${localUnlocked}/2</b></span>
          </div>
        </div>
        <div class="tr-bar"><i style="width:${pct}%"></i></div>
      </section>

      <div class="tr-filters seg" role="tablist">
        ${filters
          .map(
            ([id, label, count]) =>
              `<button class="${filter === id ? "on" : ""}" data-filter="${id}">${t(...label)}<small>${count}</small></button>`,
          )
          .join("")}
      </div>

      <section class="tr-grid">
        ${cards || `<p class="mut tr-empty">${t("這個分類裡還沒有成就，去小貓客廳嚕嚕貓吧！", "这个分类里还没有成就，去小猫客厅噜噜猫吧！")}</p>`}
      </section>
    `;

    app.querySelector<HTMLElement>("[data-go-lounge]")?.addEventListener("click", () => nav.go("lounge"));
    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-filter]")) {
      btn.addEventListener("click", () => {
        filter = btn.dataset.filter as Filter;
        draw();
      });
    }
  };

  draw();
  void api
    .achievementsCatalog()
    .then((list) => {
      items = list;
      loaded = true;
      draw();
    })
    .catch(() => {
      loaded = true;
      draw();
    });
}
