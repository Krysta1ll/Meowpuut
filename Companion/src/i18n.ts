// Traditional or Simplified, chosen once from the system language by the backend (locale.rs), the way the Mac input
// method's own windows choose. Every string on the pages is written in both: t("繁體", "简体").

let simplified = false;

export function setScript(script: "hant" | "hans"): void {
  simplified = script === "hans";
  document.documentElement.lang = simplified ? "zh-Hans" : "zh-Hant";
}

export const t = (hant: string, hans: string): string => (simplified ? hans : hant);

/** t() for a [traditional, simplified] pair. */
export const tt = (pair: readonly [string, string]): string => t(pair[0], pair[1]);

export const isSimplified = (): boolean => simplified;

const DIGITS = ["零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"];
export const monthName = (m: number): string => `${DIGITS[m]}${t("月", "月")}`;
export const fmt = (n: number): string => n.toLocaleString("en-US");

/** Escapes text for innerHTML. */
export function esc(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}
