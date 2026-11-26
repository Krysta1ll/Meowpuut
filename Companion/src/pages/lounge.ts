// 小貓客廳: pure-game cat lounge with 6 micro-dramas, 15s auto-brake laser, writing pad drawer,
// minimal teacup focus theater (Rust monotonic clock), 2-tier clockless expedition, and P0 furniture shop.

import { api, type CatMood, type ExpeditionOutcome, type ExpeditionTier, type LoungeView, type PetForm } from "../api";
import { CAT } from "../art";
import { esc, fmt, t, tt } from "../i18n";
import { nav } from "../nav";
import { bindLaserChase, buildDrama, dramaForFurniture, dramaForToy, maybeAutoDrama, renderTeacupFocusHTML } from "./lounge_drama";
import { mountPadDrawer, renderPadDrawerHTML, type PadMount } from "./lounge_pad";
import { renderExpeditionPanel, renderParagonPanel, renderRoomPanel, type LoungeTab } from "./lounge_panels";

const SLOT_ORDER = ["window", "wall", "left", "floor", "right"] as const;
const LASER_MAX_MS = 15_000;

function catSprite(cat: "mimimo" | "momo", form: PetForm, mood: CatMood | "idle"): string {
  if (form === "pixel") return cat === "mimimo" ? CAT.mimimoPixel : CAT.momoPixel;
  if (cat === "mimimo") {
    if (mood === "peek") return CAT.mimimoPeek;
    if (mood === "dilated" || mood === "sparkle") return CAT.mimimoLookUp;
    if (mood === "happy" || mood === "purr") return CAT.mimimoHappy;
    return CAT.mimimoIdle;
  }
  if (mood === "peek") return CAT.momoPeek;
  if (mood === "dilated" || mood === "sparkle") return CAT.momoLookUp;
  if (mood === "happy") return CAT.momoJump;
  if (mood === "purr") return CAT.momoHappy;
  return CAT.momoIdle;
}

export function renderLounge(app: HTMLElement): () => void {
  let view: LoungeView | null = null;
  let tab: LoungeTab = "expedition";
  let selectedPlace = "street";
  let lastDrop: ExpeditionOutcome | null = null;
  let bubble = t("歡迎回到小貓客廳！點點米米莫或莫莫，擺件家具看微戲劇，或打開陪寫小書桌吧。", "欢迎回到小猫客厅！点点米米莫或莫莫，摆件家具看微戏剧，或打开陪写小书桌吧。");
  let moodMimimo: CatMood | "idle" = "idle";
  let moodMomo: CatMood | "idle" = "idle";
  let laserOn = false;
  let padOpen = false;
  let padSessionCount = 0;
  let padStatus = t("敲下的每個字素都會化作喵字與小墨山。", "敲下的每个字素都会化作喵字与小墨山。");
  let padMount: PadMount | null = null;
  let moodTimer: number | null = null;
  let laserTimer: number | null = null;
  let focusActive = false;
  let focusStartedWall = 0;
  let focusTeaTimer: number | null = null;
  let lastUserActivityMs = Date.now();

  const markActivity = () => {
    lastUserActivityMs = Date.now();
  };
  window.addEventListener("pointerdown", markActivity, { passive: true });
  window.addEventListener("keydown", markActivity, { passive: true });

  const activeMinTimer = window.setInterval(() => {
    const idleSec = Math.floor((Date.now() - lastUserActivityMs) / 1000);
    if (idleSec < 60) {
      void api
        .recordActiveMinute(idleSec)
        .then((res) => {
          view = res;
          const auto = maybeAutoDrama(res, Date.now());
          if (auto) {
            bubble = auto.line;
            flashMood(auto.cat, auto.mood, auto.durationMs);
          }
          draw();
        })
        .catch(() => {});
    }
  }, 60_000);

  const flashMood = (who: "mimimo" | "momo" | "both", mood: CatMood, ms = 2400) => {
    if (moodTimer !== null) window.clearTimeout(moodTimer);
    moodMimimo = who === "momo" ? "idle" : mood;
    moodMomo = who === "mimimo" ? "idle" : mood;
    moodTimer = window.setTimeout(() => {
      moodMimimo = "idle";
      moodMomo = "idle";
      moodTimer = null;
      syncStageCats();
    }, ms);
  };

  const stopLaser = (announce = false) => {
    laserOn = false;
    if (laserTimer !== null) {
      window.clearTimeout(laserTimer);
      laserTimer = null;
    }
    if (announce) {
      bubble = t("🔴 15 秒煞車收光！米米莫急煞滑步停在紙箱旁，喘口氣舔舔爪子。", "🔴 15 秒刹车收光！米米莫急刹滑步停在纸箱旁，喘口气舔舔爪子。");
      flashMood("mimimo", "happy");
      draw();
    }
  };

  const syncStageCats = () => {
    if (!view) return;
    const form = view.state.petForm;
    const mImg = app.querySelector<HTMLImageElement>("[data-cat-img='mimimo']");
    const oImg = app.querySelector<HTMLImageElement>("[data-cat-img='momo']");
    if (mImg) mImg.src = catSprite("mimimo", form, moodMimimo);
    if (oImg) oImg.src = catSprite("momo", form, moodMomo);
    app.querySelector<HTMLElement>("[data-pat='mimimo']")?.setAttribute("data-mood", moodMimimo);
    app.querySelector<HTMLElement>("[data-pat='momo']")?.setAttribute("data-mood", moodMomo);
  };

  const draw = () => {
    padMount?.dispose();
    padMount = null;
    if (!view) {
      app.innerHTML = `<header class="page-hd"><h1>${t("小貓客廳", "小猫客厅")}</h1><span class="mut">${t("正在鋪開毛毯…", "正在铺开毛毯…")}</span></header>`;
      return;
    }
    const v = view;
    const form = v.state.petForm;
    const titleObj = v.titles.find((ti) => ti.id === v.state.equippedTitle);
    const furnById = new Map(v.furniture.map((f) => [f.id, f]));

    const propsHtml = SLOT_ORDER.map((slot) => {
      const id = v.state.roomSlots[slot];
      const f = id ? furnById.get(id) : null;
      if (!f) return "";
      return `<button class="l-prop slot-${slot}" data-drama-furn="${f.id}" title="${esc(tt(f.name))}"><span>${f.icon}</span><small>${esc(tt(f.name))}</small></button>`;
    }).join("");

    const toysHtml = v.toys
      .map((toy) => {
        const count = v.state.toyPlays[toy.id] ?? 0;
        const active = toy.id === "laser_dot" && laserOn;
        return `<button class="l-toy${active ? " on" : ""}" data-toy="${toy.id}" title="${esc(tt(toy.desc))}">
          <span class="lt-ico">${toy.icon}</span>
          <span class="lt-name">${esc(tt(toy.name))}</span>
          ${count > 0 ? `<small>${count}</small>` : ""}
        </button>`;
      })
      .join("");

    const teaPct = focusActive ? Math.min(100, Math.round(((Date.now() - focusStartedWall) / (25 * 60_000)) * 100)) : 0;
    const panelHtml =
      tab === "expedition"
        ? renderExpeditionPanel(v, selectedPlace, lastDrop)
        : tab === "room"
          ? renderRoomPanel(v)
          : renderParagonPanel(v);

    app.innerHTML = `
      <header class="page-hd l-hd">
        <div class="l-hd-l">
          <h1>${t("小貓客廳", "小猫客厅")}</h1>
          ${titleObj ? `<span class="l-title-pill">${titleObj.badge} ${esc(tt(titleObj.name))}</span>` : ""}
        </div>
        <div class="l-hd-r">
          <span class="l-stat" title="${t("每 20 喵字自動結算 +1 餅乾，已入帳永不倒扣", "每 20 喵字自动结算 +1 饼干，已入账永不倒扣")}">🍪 <b>${fmt(v.availableKibble)}</b> ${t("貓餅乾", "猫饼干")}</span>
          <span class="l-stat">⭐ <b>★${fmt(v.paragon.paragonLevel)}</b></span>
          <span class="l-stat">🎁 <b>${v.state.curios.length}/24</b></span>
          <button class="btn${padOpen ? " primary" : ""}" data-toggle-pad>${t("✏️ 陪寫小書桌", "✏️ 陪写小书桌")}</button>
          <div class="seg small" role="group" aria-label="${t("雙形態切換", "双形态切换")}">
            <button class="${form === "3d" ? "on" : ""}" data-form="3d">${t("3D 黏土", "3D 粘土")}</button>
            <button class="${form === "pixel" ? "on" : ""}" data-form="pixel">${t("像素雙貓", "像素双猫")}</button>
          </div>
          <button class="btn" data-go-trophies>${t("🏆 成就牆", "🏆 成就墙")}</button>
        </div>
      </header>

      <section class="l-stage form-${form}${laserOn ? " laser" : ""}" data-stage>
        <div class="l-room-bg">${propsHtml}</div>
        <div class="l-bubble" role="status">${esc(bubble)}</div>
        <div class="l-cats">
          <button class="l-cat mimimo" data-pat="mimimo" data-mood="${moodMimimo}" title="${t("搔米米莫下巴（觸發珍奶圓瞳）", "搔米米莫下巴（触发珍奶圆瞳）")}">
            <img data-cat-img="mimimo" src="${catSprite("mimimo", form, moodMimimo)}" alt="米米莫">
            <span class="l-cat-tag">${t("米米莫", "米米莫")} · Lv.${v.paragon.bondMimimo}</span>
          </button>
          <button class="l-cat momo" data-pat="momo" data-mood="${moodMomo}" title="${t("輕摸莫莫頭頂", "轻摸莫莫头顶")}">
            <img data-cat-img="momo" src="${catSprite("momo", form, moodMomo)}" alt="莫莫">
            <span class="l-cat-tag">${t("莫莫", "莫莫")} · Lv.${v.paragon.bondMomo}</span>
          </button>
        </div>
        <i class="l-laser-dot" data-laser-dot hidden></i>
      </section>

      ${renderPadDrawerHTML(padOpen, padSessionCount, padStatus)}

      <section class="l-controls">
        <div class="l-toys" role="group" aria-label="${t("互動玩具", "互动玩具")}">${toysHtml}</div>
        ${renderTeacupFocusHTML(focusActive, teaPct, v.state.focusCompleted, v.state.snackVouchers)}
      </section>

      <div class="l-tabs seg" role="tablist">
        <button class="${tab === "expedition" ? "on" : ""}" data-tab="expedition">${t("🕊️ 紙鶴遠征 & 24 奇珍", "🕊️ 纸鹤远征 & 24 奇珍")}</button>
        <button class="${tab === "room" ? "on" : ""}" data-tab="room">${t("🛋️ 家具鋪 & 小屋佈置", "🛋️ 家具铺 & 小屋布置")}</button>
        <button class="${tab === "paragon" ? "on" : ""}" data-tab="paragon">${t("⭐ 無限星階 · 5 星階故事 · 稱號", "⭐ 无限星阶 · 5 星阶故事 · 称号")}</button>
      </div>
      ${panelHtml}
    `;

    bindEvents();
  };

  const bindEvents = () => {
    app.querySelector<HTMLElement>("[data-go-trophies]")?.addEventListener("click", () => nav.go("trophies"));
    app.querySelector<HTMLButtonElement>("[data-toggle-pad]")?.addEventListener("click", () => {
      padOpen = !padOpen;
      if (!padOpen) padSessionCount = 0;
      draw();
    });

    if (padOpen) {
      padMount = mountPadDrawer(
        app,
        (out) => {
          padSessionCount += out.accepted;
          padStatus = t(`已寫入帳本 +${out.accepted} 字（今日累計 ${fmt(out.dailyTotal)} 字）`, `已写入账本 +${out.accepted} 字（今日累计 ${fmt(out.dailyTotal)} 字）`);
          void api.loungeView().then((res) => {
            view = res;
          });
        },
        (msg) => {
          bubble = msg;
          flashMood("mimimo", "peek");
          const b = app.querySelector<HTMLElement>(".l-bubble");
          if (b) b.textContent = msg;
        },
        () => {
          padOpen = false;
          padSessionCount = 0;
          draw();
        },
      );
    }

    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-drama-furn]")) {
      btn.addEventListener("click", () => {
        if (!view) return;
        const did = dramaForFurniture(btn.dataset.dramaFurn!);
        if (!did) return;
        const d = buildDrama(did, view);
        bubble = d.line;
        flashMood(d.cat, d.mood, d.durationMs);
        draw();
      });
    }

    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-form]")) {
      btn.addEventListener("click", () => {
        const next = btn.dataset.form as PetForm;
        void api.loungeSetForm(next).then((res) => {
          view = res;
          bubble = next === "pixel"
            ? t("切換成像素雙貓啦！整數倍清晰點陣貼紙感。", "切换成像素双猫啦！整数倍清晰点阵贴纸感。")
            : t("回到 3D 軟陶黏土雙貓！米米莫與莫莫都在原處。", "回到 3D 软陶粘土双猫！米米莫与莫莫都在原处。");
          draw();
        });
      });
    }

    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-pat]")) {
      btn.addEventListener("click", () => {
        const cat = btn.dataset.pat as "mimimo" | "momo";
        void api.loungeInteract("pat", cat).then((res) => {
          view = res.view;
          if (res.completed.length) lastDrop = res.completed[res.completed.length - 1];
          bubble = tt(res.reaction.dialogue);
          flashMood(res.reaction.cat, cat === "mimimo" ? "dilated" : res.reaction.mood);
          draw();
        });
      });
    }

    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-toy]")) {
      btn.addEventListener("click", () => {
        const toyId = btn.dataset.toy!;
        if (toyId === "laser_dot") {
          if (laserOn) stopLaser(false);
          else {
            laserOn = true;
            laserTimer = window.setTimeout(() => stopLaser(true), LASER_MAX_MS);
          }
        }
        void api.loungeInteract(toyId).then((res) => {
          view = res.view;
          const did = dramaForToy(toyId);
          const drama = did ? buildDrama(did, res.view) : null;
          bubble = drama ? drama.line : tt(res.reaction.dialogue);
          flashMood(res.reaction.cat, res.reaction.mood, drama?.durationMs ?? 2400);
          draw();
        });
      });
    }

    bindLaserChase(app.querySelector<HTMLElement>("[data-stage]"), app.querySelector<HTMLElement>("[data-laser-dot]"), laserOn);

    app.querySelector<HTMLButtonElement>("[data-focus-begin]")?.addEventListener("click", () => {
      void api.loungeFocusStart().then(() => {
        focusActive = true;
        focusStartedWall = Date.now();
        if (focusTeaTimer !== null) window.clearInterval(focusTeaTimer);
        focusTeaTimer = window.setInterval(() => {
          const bar = app.querySelector<HTMLElement>(".lf-tea i");
          if (bar) bar.style.width = `${Math.min(100, Math.max(8, Math.round(((Date.now() - focusStartedWall) / (25 * 60_000)) * 100)))}%`;
        }, 15_000);
        bubble = t("🍵 泡好熱茶啦！不放跳秒倒數，隨時收工都能蓋陪讀小票。", "🍵 泡好热茶啦！不放跳秒倒数，随时收工都能盖陪读小票。");
        draw();
      });
    });

    app.querySelector<HTMLButtonElement>("[data-focus-finish]")?.addEventListener("click", () => {
      focusActive = false;
      if (focusTeaTimer !== null) {
        window.clearInterval(focusTeaTimer);
        focusTeaTimer = null;
      }
      void api.loungeCompleteFocus().then((res) => {
        view = res.view;
        bubble = tt(res.outcome.dialogue);
        flashMood("both", "happy");
        draw();
      });
    });

    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-tab]")) {
      btn.addEventListener("click", () => { tab = btn.dataset.tab as LoungeTab; draw(); });
    }
    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-place]")) {
      btn.addEventListener("click", () => { selectedPlace = btn.dataset.place!; draw(); });
    }
    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-dispatch-tier]")) {
      btn.addEventListener("click", () => {
        const tier = btn.dataset.dispatchTier as ExpeditionTier;
        void api.loungeDispatchExpedition(Number(btn.dataset.slot ?? 0), selectedPlace, tier).then((res) => {
          view = res.view;
          if (res.completed.length) lastDrop = res.completed[res.completed.length - 1];
          bubble = res.completed.length ? tt(res.completed[0].dialogue) : t("🕊️ 紙鶴信差出發囉！兩隻貓照常陪在桌上與小屋裡等你。", "🕊️ 纸鹤信差出发啰！两只猫照常陪在桌上与小屋里等你。");
          draw();
        });
      });
    }
    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-claim-starter]")) {
      btn.addEventListener("click", () => {
        void api.loungeClaimStarter(btn.dataset.claimStarter!).then((res) => {
          view = res;
          bubble = t("🎁 首件地板家具擺下啦！米米莫馬上走過去窩起來，莫莫還叼出了鈷藍星光瓶蓋！", "🎁 首件地板家具摆下啦！米米莫马上走过去窝起来，莫莫还叼出了钴蓝星光瓶盖！");
          flashMood("both", "happy"); draw();
        });
      });
    }
    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-buy-furn]")) {
      btn.addEventListener("click", () => {
        const id = btn.dataset.buyFurn!;
        void api.loungeBuyFurniture(id).then((res) => {
          view = res;
          const did = dramaForFurniture(id); const d = did ? buildDrama(did, res) : null;
          bubble = d ? d.line : t("🛍️ 新家具入手！去下方點擊就能放進客廳囉。", "🛍️ 新家具入手！去下方点击就能放进客厅啰。");
          flashMood(d?.cat ?? "both", d?.mood ?? "happy"); draw();
        });
      });
    }
    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-equip-room]")) {
      btn.addEventListener("click", () => {
        const slot = btn.dataset.equipRoom!; const item = btn.dataset.item || null;
        void api.loungeEquipRoom(slot, item).then((res) => {
          view = res;
          const did = item ? dramaForFurniture(item) : null; const d = did ? buildDrama(did, res) : null;
          bubble = d ? d.line : item ? t("家具擺好啦！兩隻貓湊過去聞了三圈：超米到。", "家具摆好啦！两只猫凑过去闻了三圈：超米到。") : t("把這一角騰出來透透氣。", "把这一角腾出来透透气。");
          draw();
        });
      });
    }
    for (const btn of app.querySelectorAll<HTMLButtonElement>("[data-equip-title]")) {
      btn.addEventListener("click", () => {
        void api.loungeEquipTitle(btn.dataset.equipTitle!).then((res) => {
          view = res; bubble = t("新稱號掛上客廳門牌啦！兩隻貓一起拍小爪。", "新称号挂上客厅门牌啦！两只猫一起拍小爪。"); draw();
        });
      });
    }
  };

  draw();
  void api.loungeView().then((res) => { view = res; draw(); }).catch((e) => { bubble = String(e); draw(); });

  return () => {
    padMount?.dispose();
    if (moodTimer !== null) window.clearTimeout(moodTimer);
    if (laserTimer !== null) window.clearTimeout(laserTimer);
    if (focusTeaTimer !== null) window.clearInterval(focusTeaTimer);
    window.clearInterval(activeMinTimer);
    window.removeEventListener("pointerdown", markActivity);
    window.removeEventListener("keydown", markActivity);
  };
}
