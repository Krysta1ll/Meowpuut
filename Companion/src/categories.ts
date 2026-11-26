// The ten categories of 時間花在哪裡 (FINAL.md §11.3), in the ledger's order (meowpuut-activity `Category`). Each wears
// a fixed colour (`--cat-0` … `--cat-9` in styles.css), assigned by category, never by rank. The nine hues are the
// dataviz reference palette plus one teal, ordered so that neighbours in a stack stay apart for colour-blind readers
// (validated with the dataviz skill's validate_palette.js on the 日常玻璃 paper, light and dark: worst adjacent CVD
// ΔE 11.0 / 9.9, normal vision 19.3 / 19.3); 其他 is a neutral grey on purpose.

import { t } from "./i18n";

export const CATEGORIES: { code: string; name: [string, string] }[] = [
  { code: "write", name: ["寫作", "写作"] },
  { code: "code", name: ["程式", "编程"] },
  { code: "design", name: ["設計", "设计"] },
  { code: "web", name: ["瀏覽", "浏览"] },
  { code: "chat", name: ["通訊", "通讯"] },
  { code: "media", name: ["影音", "影音"] },
  { code: "game", name: ["遊戲", "游戏"] },
  { code: "learn", name: ["學習", "学习"] },
  { code: "office", name: ["辦公", "办公"] },
  { code: "other", name: ["其他", "其他"] },
];

export const catName = (i: number): string => t(...CATEGORIES[i].name);

/** Minutes as calm words: 「40 分」「3 小時 20 分」. */
export function dur(minutes: number): string {
  if (minutes <= 0) return t("0 分", "0 分");
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return t(`${m} 分`, `${m} 分`);
  return m === 0 ? t(`${h} 小時`, `${h} 小时`) : t(`${h} 小時 ${m} 分`, `${h} 小时 ${m} 分`);
}

/** Short form for the top of a bar: 「3:20」 reads as a clock, so hours with one decimal: 「3.3 時」, or 「40 分」. */
export function durShort(minutes: number): string {
  if (minutes <= 0) return "";
  if (minutes < 60) return t(`${minutes} 分`, `${minutes} 分`);
  const h = Math.round(minutes / 6) / 10;
  return t(`${h} 時`, `${h} 时`);
}
