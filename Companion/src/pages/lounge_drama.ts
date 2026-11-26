// P0 curated 6 lounge micro-dramas + minimal zero-pressure teacup focus theater (<= 150 lines).
// Reuses existing 3D clay poses + CSS PupilDilate overshoot; zero ticking countdown digits; Rust monotonic clock only.

import type { CatMood, LoungeView } from "../api";
import { t, tt } from "../i18n";

export type DramaId =
  | "DRAMA_FAUCET_STARE"
  | "DRAMA_CAP_STASH"
  | "DRAMA_LASER_CHASE"
  | "DRAMA_CRT_HYPNOSIS"
  | "DRAMA_FISH_VS_CORN"
  | "DRAMA_WINDOW_HITCHHIKE";

export interface DramaPlayback {
  id: DramaId;
  cat: "mimimo" | "momo" | "both";
  mood: CatMood;
  line: string;
  durationMs: number;
}

const GLOBAL_DRAMA_CD_MS = 45_000;
let lastAutoDramaMs = 0;

/** Builds one of the 6 P0 micro-dramas, dynamically referencing the player's latest owned curio for CAP_STASH. */
export function buildDrama(id: DramaId, view: LoungeView): DramaPlayback {
  const latestCurioId = view.state.curios[view.state.curios.length - 1];
  const latestCurio = view.curios.find((c) => c.id === latestCurioId);
  const curioName = latestCurio ? tt(latestCurio.name) : t("鈷藍星光瓶蓋", "钴蓝星光瓶盖");

  switch (id) {
    case "DRAMA_FAUCET_STARE":
      return {
        id,
        cat: "mimimo",
        mood: "dilated",
        durationMs: 3200,
        line: t(
          "🚰 米米莫蹲在水龍頭前盯著水滴，瞳孔瞬間放大成珍奶圓球：奧哇，這滴最新鮮！",
          "🚰 米米莫蹲在水龙头前盯着水滴，瞳孔瞬间放大成珍奶圆球：奥哇，这滴最新鲜！",
        ),
      };
    case "DRAMA_CAP_STASH":
      return {
        id,
        cat: "momo",
        mood: "peek",
        durationMs: 3200,
        line: t(
          `📦 莫莫偷偷把你剛撿到的「${curioName}」塞進紙箱，被游標撞見立刻僵住裝傻！`,
          `📦 莫莫偷偷把你刚捡到的「${curioName}」塞进纸箱，被光标撞见立刻僵住装傻！`,
        ),
      };
    case "DRAMA_LASER_CHASE":
      return {
        id,
        cat: "both",
        mood: "dilated",
        durationMs: 15_000,
        line: t(
          "🔴 雷射紅點亮起！米米莫 0.08 秒爆出珍奶圓瞳蓄力追擊，15 秒自動急煞滑步收工。",
          "🔴 激光红点亮起！米米莫 0.08 秒爆出珍奶圆瞳蓄力追击，15 秒自动急刹滑步收工。",
        ),
      };
    case "DRAMA_CRT_HYPNOSIS":
      return {
        id,
        cat: "both",
        mood: "purr",
        durationMs: 3600,
        line: t(
          "📺 復古小電視切換著三格微頻道，兩隻貓眼皮開始打架，暖呼呼縮成一團呼嚕。",
          "📺 复古小电视切换着三格微频道，两只猫眼皮开始打架，暖呼呼缩成一团呼噜。",
        ),
      };
    case "DRAMA_FISH_VS_CORN":
      return {
        id,
        cat: "mimimo",
        mood: "sparkle",
        durationMs: 3200,
        line: t(
          "🌽 聞到小魚乾皺眉 wer 退後兩步——下一秒瞄到熱甜玉米，米米莫瞬間爆出星芒珍奶瞳！",
          "🌽 闻到小鱼干皱眉 wer 退后两步——下一秒瞄到热甜玉米，米米莫瞬间爆出星芒珍奶瞳！",
        ),
      };
    case "DRAMA_WINDOW_HITCHHIKE":
      return {
        id,
        cat: "momo",
        mood: "happy",
        durationMs: 3200,
        line: t(
          "🍜 莫莫搭上視窗邊緣順風車衝浪，米米莫在窗框角落偷偷探頭躲貓貓！",
          "🍜 莫莫搭上窗口边缘顺风车冲浪，米米莫在窗框角落偷偷探头躲猫猫！",
        ),
      };
  }
}

/** Maps an equipped furniture item click to its unlocked signature micro-drama. */
export function dramaForFurniture(itemId: string): DramaId | null {
  switch (itemId) {
    case "faucet_fountain": return "DRAMA_FAUCET_STARE";
    case "box_villa":
    case "cap_tower": return "DRAMA_CAP_STASH";
    case "crt_tv": return "DRAMA_CRT_HYPNOSIS";
    case "corn_pot": return "DRAMA_FISH_VS_CORN";
    case "noodle_bed":
    case "kibble_rug": return "DRAMA_WINDOW_HITCHHIKE";
    default: return null;
  }
}

/** Picks an automatic ambient furniture drama if the 45s global cooldown has passed. */
export function maybeAutoDrama(view: LoungeView, nowMs: number): DramaPlayback | null {
  if (nowMs - lastAutoDramaMs < GLOBAL_DRAMA_CD_MS) return null;
  const equipped = Object.values(view.state.roomSlots);
  const candidates = equipped.map(dramaForFurniture).filter((d): d is DramaId => d !== null);
  if (!candidates.length) return null;
  lastAutoDramaMs = nowMs;
  const pick = candidates[Math.floor((nowMs / 1000) % candidates.length)];
  return buildDrama(pick, view);
}

export function dramaForToy(toyId: string): DramaId | null {
  if (toyId === "laser_dot") return "DRAMA_LASER_CHASE";
  if (toyId === "sweet_corn") return "DRAMA_FISH_VS_CORN";
  if (toyId === "cardboard_box" || toyId === "bottle_cap") return "DRAMA_CAP_STASH";
  if (toyId === "noodle_box") return "DRAMA_WINDOW_HITCHHIKE";
  return null;
}

export function bindLaserChase(stage: HTMLElement | null, dot: HTMLElement | null, active: boolean): void {
  if (!stage || !dot || !active) return;
  stage.addEventListener("pointermove", (e) => {
    const r = stage.getBoundingClientRect();
    const x = Math.max(12, Math.min(r.width - 12, e.clientX - r.left));
    const y = Math.max(12, Math.min(r.height - 12, e.clientY - r.top));
    dot.hidden = false;
    dot.style.left = `${x}px`;
    dot.style.top = `${y}px`;
    const dx = (x / r.width - 0.5) * 140;
    const dy = (y / r.height - 0.65) * 28;
    const mEl = stage.querySelector<HTMLElement>(".l-cat.mimimo");
    const oEl = stage.querySelector<HTMLElement>(".l-cat.momo");
    if (mEl) mEl.style.transform = `translate(${dx * 0.72}px, ${dy * 0.6}px)`;
    if (oEl) oEl.style.transform = `translate(${dx * 0.95}px, ${dy * 0.85}px)`;
  });
  stage.addEventListener("pointerleave", () => {
    dot.hidden = true;
  });
}

/** Renders the minimal zero-pressure Focus Theater strip (no ticking countdown numbers, teacup progress only). */
export function renderTeacupFocusHTML(active: boolean, fillPct: number, completed: number, vouchers: number): string {
  return `
    <div class="l-focus">
      <span class="lf-label">${t("🍵 極簡陪讀劇場", "🍵 极简陪读剧场")}</span>
      <div class="lf-tea" title="${t("茶杯靜靜溫著，無倒數不催人", "茶杯静静温着，无倒数不催人")}">
        <i style="width:${active ? Math.min(100, Math.max(8, fillPct)) : 0}%"></i>
      </div>
      ${
        active
          ? `<button class="btn primary" data-focus-finish>${t("收工蓋小票（零懲罰）", "收工盖小票（零惩罚）")}</button>`
          : `<button class="btn" data-focus-begin>${t("倒杯熱茶陪讀", "倒杯热茶陪读")}</button>`
      }
      <small class="mut">${t(`已陪讀 ${completed} 次 · 零食券 ${vouchers}`, `已陪读 ${completed} 次 · 零食券 ${vouchers}`)}</small>
    </div>`;
}
