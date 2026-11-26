// Endgame tab panels for 小貓客廳: 2-Tier Clockless Expeditions, P0 Furniture Shop & 5 Slots, Quadratic Paragon + 5 Star Stories.

import type { CurioDef, ExpeditionOutcome, LoungeView } from "../api";
import { esc, fmt, t, tt } from "../i18n";

export type LoungeTab = "expedition" | "room" | "paragon";

const SLOT_LABELS: Record<string, [string, string]> = {
  window: ["窗景", "窗景"],
  left: ["左側擺設", "左侧摆设"],
  floor: ["地板中央", "地板中央"],
  right: ["右側擺設", "右侧摆设"],
  wall: ["牆面裝飾", "墙面装饰"],
};

const RARITY_LABELS: Record<CurioDef["rarity"], [string, string]> = {
  common: ["常見", "常见"],
  uncommon: ["稀有", "稀有"],
  rare: ["珍貴", "珍贵"],
  legend: ["傳說", "传说"],
};

const STARTER_IDS = ["kibble_rug", "noodle_bed", "corn_pot"] as const;

export function renderExpeditionPanel(
  view: LoungeView,
  selectedPlace: string,
  lastDrop: ExpeditionOutcome | null,
): string {
  const owned = new Set(view.state.curios);
  const placeCurioMap = new Map<number, CurioDef>();
  for (const c of view.curios) {
    if (c.placeBit !== null) placeCurioMap.set(c.placeBit, c);
  }

  const freeSlot = view.state.expeditionSlots
    .slice(0, view.unlockedSlots)
    .findIndex((s) => s === null);
  const canDispatch = freeSlot !== -1;

  const activeSlotsHtml = view.state.expeditionSlots
    .slice(0, view.unlockedSlots)
    .map((slot, i) => {
      if (!slot) {
        return `<div class="l-exp-slot empty"><span>${t(`紙鶴欄位 #${i + 1}`, `纸鹤栏位 #${i + 1}`)}</span><small class="mut">${t("待命：選好地標即可出發（不吃牆上時鐘）", "待命：选好地标即可出发（不吃墙上时钟）")}</small></div>`;
      }
      const p = view.places.find((x) => x.id === slot.placeId);
      const pct = Math.min(100, Math.round((slot.walkedChars / Math.max(1, slot.needChars)) * 100));
      const tierName =
        slot.tier === "onboarding"
          ? t("新手散步", "新手散步")
          : slot.tier === "adventure"
            ? t("大冒險（10 骰）", "大冒险（10 骰）")
            : t("散步（1 骰）", "散步（1 骰）");
      return `<div class="l-exp-slot active">
        <div class="les-top"><b>🕊️ ${p ? esc(tt(p.name)) : esc(slot.placeId)} · ${tierName}</b><small>${fmt(slot.walkedChars)} / ${fmt(slot.needChars)} ${t("喵字", "喵字")}</small></div>
        <div class="bar"><i style="width:${pct}%"></i></div>
      </div>`;
    })
    .join("");

  const placeChips = view.places
    .map((p) => {
      const open = p.alwaysOpen || (view.placesMask & (1 << p.bit)) !== 0;
      const ex = placeCurioMap.get(p.bit);
      const exOwned = ex ? owned.has(ex.id) : false;
      const active = p.id === selectedPlace;
      return `<button class="l-place${active ? " on" : ""}${open ? "" : " locked"}" data-place="${p.id}" ${open ? "" : "disabled"} title="${esc(tt(p.vibe))}">
        <span class="lp-name">${esc(tt(p.name))}</span>
        <small>${open ? (exOwned ? t("限定已收", "限定已收") : t("有未收限定", "有未收限定")) : t("尚未抵達", "尚未抵达")}</small>
      </button>`;
    })
    .join("");

  const dropBanner = lastDrop
    ? `<div class="l-drop rar-${lastDrop.curios[0]?.rarity ?? "common"}">
        <span class="ld-ico">${lastDrop.curios[0]?.icon ?? "🍪"}</span>
        <div class="ld-body">
          <b>${esc(tt(lastDrop.placeName))} ${t("紙鶴信差返家！", "纸鹤信差返家！")}
            <em class="l-pill">+${lastDrop.rewardKibble} 🍪${lastDrop.newCount > 0 ? t(` · 新奇珍 ×${lastDrop.newCount}`, ` · 新奇珍 ×${lastDrop.newCount}`) : ""}</em>
          </b>
          <p>${esc(tt(lastDrop.dialogue))}</p>
        </div>
      </div>`
    : "";

  const curioCards = view.curios
    .map((c) => {
      const has = owned.has(c.id);
      const from = c.placeBit !== null ? view.places.find((p) => p.bit === c.placeBit) : null;
      return `<div class="l-curio rar-${c.rarity}${has ? " owned" : " missing"}">
        <div class="lc-top">
          <span class="lc-ico">${has ? c.icon : "？"}</span>
          <span class="lc-rar">${t(...RARITY_LABELS[c.rarity])}</span>
        </div>
        <b>${has ? esc(tt(c.name)) : t("尚未發現的奇珍", "尚未发现的奇珍")}</b>
        <p>${has ? esc(tt(c.flavor)) : from ? t(`前往「${tt(from.name)}」有機會寄回`, `前往「${tt(from.name)}」有机会寄回`) : t("逛「小貓集市」有機會寄回", "逛「小猫集市」有机会寄回")}</p>
      </div>`;
    })
    .join("");

  return `
    <div class="l-panel">
      <div class="l-exp-head">
        <div>
          <h3>${t("紙鶴遠征（無時鐘 2 階）& 24 奇珍圖鑑", "纸鹤远征（无时钟 2 阶）& 24 奇珍图鉴")}</h3>
          <p class="mut small">${t("兩隻貓永遠留在你身邊，派出紙鶴信差只吃累積「喵字」不看時鐘。大冒險越不管越有效率，每 10 次未出新品硬保底必出！", "两只猫永远留在你身边，派出纸鹤信差只吃累积「喵字」不看时钟。大冒险越不管越有效率，每 10 次未出新品硬保底必出！")}</p>
        </div>
        <div class="l-exp-go">
          ${view.state.restedBonusChars > 0 ? `<span class="l-pill warm">${t(`🛏️ 暖被窩加成 ×2（剩 ${fmt(view.state.restedBonusChars)} 喵字）`, `🛏️ 暖被窝加成 ×2（剩 ${fmt(view.state.restedBonusChars)} 喵字）`)}</span>` : ""}
          <span class="l-pill">${t(`硬保底進度 ${view.state.expeditionPity}/10`, `硬保底进度 ${view.state.expeditionPity}/10`)}</span>
        </div>
      </div>
      <div class="l-exp-slots">${activeSlotsHtml}</div>
      <div class="l-exp-actions">
        ${
          !view.state.onboardingExpeditionDone
            ? `<button class="btn primary" data-dispatch-tier="onboarding" data-slot="${freeSlot}" ${canDispatch ? "" : "disabled"}>
                ${t("🐚 新手散步（300 喵字或摸貓 10 下 · 必得白貝殼）", "🐚 新手散步（300 喵字或摸猫 10 下 · 必得白贝壳）")}
              </button>`
            : ""
        }
        <button class="btn" data-dispatch-tier="stroll" data-slot="${freeSlot}" ${canDispatch ? "" : "disabled"}>
          ${t("🕊️ 散步（2,000 喵字 · 1 骰 · +10 🍪）", "🕊️ 散步（2,000 喵字 · 1 骰 · +10 🍪）")}
        </button>
        <button class="btn primary" data-dispatch-tier="adventure" data-slot="${freeSlot}" ${canDispatch ? "" : "disabled"}>
          ${t("✨ 大冒險（15,000 喵字 · 10 骰 · +150 🍪）", "✨ 大冒险（15,000 喵字 · 10 骰 · +150 🍪）")}
        </button>
      </div>
      ${dropBanner}
      <div class="l-places">${placeChips}</div>
      <div class="l-sec-hd">
        <b>${t(`奇珍圖鑑（${view.state.curios.length} / ${view.curios.length}）`, `奇珍图鉴（${view.state.curios.length} / ${view.curios.length}）`)}</b>
        <span class="mut small">${t(`已解鎖 ${view.unlockedSlots}/3 紙鶴欄 · 已完成 ${fmt(view.state.expeditionCount)} 次遠征`, `已解锁 ${view.unlockedSlots}/3 纸鹤栏 · 已完成 ${fmt(view.state.expeditionCount)} 次远征`)}</span>
      </div>
      <div class="l-curios">${curioCards}</div>
    </div>`;
}

export function renderRoomPanel(view: LoungeView): string {
  const owns = (bit: number) => ((view.state.ownedFurniture >>> bit) & 1) === 1;
  const starterBanner = !view.state.starterGiftClaimed
    ? `<div class="l-starter-box">
        <b>${t("🎁 新手見面禮：免費挑選第一件地板家具（再送鈷藍星光瓶蓋）", "🎁 新手见面礼：免费挑选第一件地板家具（再送钴蓝星光瓶盖）")}</b>
        <div class="l-furn-row">
          ${STARTER_IDS.map((id) => {
            const f = view.furniture.find((x) => x.id === id);
            if (!f) return "";
            return `<button class="l-furn" data-claim-starter="${f.id}">
              <span class="lf-ico">${f.icon}</span>
              <span class="lf-txt"><b>${esc(tt(f.name))}</b><small>${t("免費領取並擺放", "免费领取并摆放")}</small></span>
            </button>`;
          }).join("")}
        </div>
      </div>`
    : "";

  const shopItems = view.furniture.filter((f) => f.p0Shop);
  const shopHtml = shopItems
    .map((f) => {
      const owned = owns(f.bit);
      const curioOk = view.state.curios.length >= f.unlockCurios;
      const canBuy = !owned && curioOk && view.availableKibble >= f.price;
      return `<div class="l-shop-card${owned ? " owned" : ""}">
        <span class="lf-ico">${f.icon}</span>
        <div class="lf-txt">
          <b>${esc(tt(f.name))}</b>
          <small>${esc(tt(f.desc))}</small>
        </div>
        ${
          owned
            ? `<span class="l-pill">${t("已擁有", "已拥有")}</span>`
            : `<button class="btn${canBuy ? " primary" : ""}" data-buy-furn="${f.id}" ${canBuy ? "" : "disabled"}>
                ${!curioOk ? t(`需 ${f.unlockCurios} 奇珍`, `需 ${f.unlockCurios} 奇珍`) : `${fmt(f.price)} 🍪`}
              </button>`
        }
      </div>`;
    })
    .join("");

  const slots = ["left", "floor", "right", "window", "wall"] as const;
  const groups = slots
    .map((slot) => {
      const current = view.state.roomSlots[slot] ?? "";
      const ownedInSlot = view.furniture.filter((f) => f.slot === slot && owns(f.bit));
      if (!ownedInSlot.length && !current) return "";
      return `<div class="l-slot-group">
        <div class="l-slot-hd">
          <b>${t(...SLOT_LABELS[slot])}</b>
          ${current ? `<button class="linkish" data-equip-room="${slot}" data-item="">${t("收起來", "收起来")}</button>` : `<span class="mut small">${t("空著透氣中", "空着透气中")}</span>`}
        </div>
        <div class="l-furn-row">
          ${ownedInSlot
            .map(
              (f) => `<button class="l-furn${current === f.id ? " on" : ""}" data-equip-room="${slot}" data-item="${f.id}">
                <span class="lf-ico">${f.icon}</span>
                <span class="lf-txt"><b>${esc(tt(f.name))}</b><small>${t("點擊擺放並觸發微戲劇", "点击摆放并触发微戏剧")}</small></span>
              </button>`,
            )
            .join("")}
        </div>
      </div>`;
    })
    .join("");

  return `<div class="l-panel">
    ${starterBanner}
    <h3>${t("🛋️ 小貓家具鋪（P0 精選 6 件 · 單一貨幣貓餅乾）", "🛋️ 小猫家具铺（P0 精选 6 件 · 单一货币猫饼干）")}</h3>
    <p class="mut small">${t("家具就是微戲劇的鑰匙：買下並擺進小屋，立刻解鎖對應貓咪演出！", "家具就是微戏剧的钥匙：买下并摆进小屋，立刻解锁对应猫咪演出！")}</p>
    <div class="l-shop-grid">${shopHtml}</div>
    <h4 class="l-subh">${t("已擁有的擺設（點擊立即換裝）", "已拥有的摆设（点击立即换装）")}</h4>
    <div class="l-slots">${groups || `<p class="mut small">${t("先領取上方免費新手家具或用貓餅乾挑一件吧！", "先领取上方免费新手家具或用猫饼干挑一件吧！")}</p>`}</div>
  </div>`;
}

export function renderParagonPanel(view: LoungeView): string {
  const p = view.paragon;
  const unlockedTitles = new Set(view.unlockedTitles);
  const pips = Array.from({ length: 10 }, (_, i) => `<i class="${i < p.stardustPips ? "on" : ""}"></i>`).join("");

  const titleCards = view.titles
    .map((ti) => {
      const ok = unlockedTitles.has(ti.id);
      const active = view.state.equippedTitle === ti.id;
      return `<button class="l-title${active ? " on" : ""}${ok ? "" : " locked"}" data-equip-title="${ti.id}" ${ok ? "" : "disabled"}>
        <span class="lt-badge">${ti.badge}</span>
        <span class="lt-txt"><b>${esc(tt(ti.name))}</b><small>${esc(tt(ti.hint))}</small></span>
        <em class="lt-st">${active ? t("配戴中", "佩戴中") : ok ? t("換上", "换上") : t("未解鎖", "未解锁")}</em>
      </button>`;
    })
    .join("");

  const starStoryCards = view.starStories
    .map(
      (s) => `<article class="l-story star-story${s.unlocked ? " open" : " locked"}">
        <div class="ls-hd"><span class="l-pill">★${s.star}</span><b>${esc(tt(s.title))}</b></div>
        <p>${s.body ? esc(tt(s.body)) : t(`一起走到星階 ★${s.star} 後解鎖這段星空小記。`, `一起走到星阶 ★${s.star} 后解锁这段星空小记。`)}</p>
      </article>`,
    )
    .join("");

  const storyCards = view.stories
    .map((s) => {
      const catName = s.cat === "mimimo" ? t("米米莫", "米米莫") : t("莫莫", "莫莫");
      return `<article class="l-story${s.unlocked ? " open" : " locked"}">
        <div class="ls-hd"><span class="l-pill">${catName} · Lv.${s.level}</span><b>${esc(tt(s.title))}</b></div>
        <p>${s.body ? esc(tt(s.body)) : t(`和${catName}的羈絆達到 Lv.${s.level} 後解鎖這段故事。`, `和${catName}的羁绊达到 Lv.${s.level} 后解锁这段故事。`)}</p>
      </article>`;
    })
    .join("");

  return `<div class="l-panel">
    <div class="l-paragon-card">
      <div class="lp-star">
        <span class="lp-badge">★ ${fmt(p.paragonLevel)}</span>
        <div>
          <h3>${p.maxBondReached ? t("無限星階 ★（一起走過的路）", "无限星阶 ★（一起走过的路）") : t("雙貓羈絆成長中", "双猫羁绊成长中")}</h3>
          <p class="mut small">${p.maxBondReached ? t(`每一顆星由 10 格星屑點亮（距 ★${p.paragonLevel + 1} 已點亮 ${p.stardustPips}/10 格星屑）`, `每一颗星由 10 格星屑点亮（距 ★${p.paragonLevel + 1} 已点亮 ${p.stardustPips}/10 格星屑）`) : t("米米莫與莫莫雙雙達到 Lv.100 後即開啟無限星階 ★", "米米莫与莫莫双双达到 Lv.100 后即开启无限星阶 ★")}</p>
        </div>
      </div>
      <div class="lp-pips" aria-label="${t("星屑進度", "星屑进度")}">${pips}</div>
      <div class="lp-bars">
        <div class="lp-bar"><span>${t("米米莫羈絆", "米米莫羁绊")}</span><div class="bar"><i style="width:${p.bondMimimo}%"></i></div><b>Lv.${p.bondMimimo}/100</b></div>
        <div class="lp-bar"><span>${t("莫莫羈絆", "莫莫羁绊")}</span><div class="bar"><i style="width:${p.bondMomo}%"></i></div><b>Lv.${p.bondMomo}/100</b></div>
      </div>
    </div>
    <h4 class="l-subh">${t("⭐ 星階故事（5 篇）", "⭐ 星阶故事（5 篇）")}</h4>
    <div class="l-stories">${starStoryCards}</div>
    <h4 class="l-subh">${t(`稱號收藏（${view.unlockedTitles.length} / ${view.titles.length}）`, `称号收藏（${view.unlockedTitles.length} / ${view.titles.length}）`)}</h4>
    <div class="l-titles">${titleCards}</div>
    <h4 class="l-subh">${t("雙貓羈絆小故事（10 篇）", "双猫羁绊小故事（10 篇）")}</h4>
    <div class="l-stories">${storyCards}</div>
  </div>`;
}
