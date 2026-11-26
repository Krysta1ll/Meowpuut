// ⌘K / Ctrl+K 路牌: where to go, typed with 喵撲 itself. `xl` (the pinyin initials) finds 行旅; so do ㄒㄌ (Zhuyin
// initials, while composing) and 行旅 itself once committed. The field is a real text field, so whatever input
// method is active works in it; Enter while composing belongs to the input method, not to the signpost.

import type { Platform } from "./api";
import { esc, t } from "./i18n";

export interface Post {
  label: [string, string];
  /** Pinyin initials of the label, in order. */
  initials: string;
  /** Zhuyin initials (the first symbol of each syllable). */
  zhuyin: string;
  route?: string;
  /** Built-in action instead of navigation. */
  action?: "desk-home" | "desk-out" | "quit";
  /** ⌘ / Ctrl + this digit goes there too. */
  digit?: number;
  /** Not available yet: shown, never taken. */
  later?: [string, string];
}

export const POSTS: Post[] = [
  { label: ["小貓客廳", "小猫客厅"], initials: "xmkt", zhuyin: "ㄒㄇㄎㄊ", route: "lounge", digit: 5 },
  { label: ["成就牆", "成就墙"], initials: "cjq", zhuyin: "ㄔㄐㄑ", route: "trophies", digit: 6 },
  { label: ["行旅", "行旅"], initials: "xl", zhuyin: "ㄒㄌ", route: "journey", digit: 3 },
  { label: ["行旅 › 一年", "行旅 › 一年"], initials: "xlyn", zhuyin: "ㄒㄌㄧㄋ", route: "journey?lens=year", digit: 3 },
  { label: ["行旅 › 這個月", "行旅 › 这个月"], initials: "xlzgy", zhuyin: "ㄒㄌㄓㄍㄩ", route: "journey?lens=month", digit: 3 },
  { label: ["行旅 › 時間花在哪裡", "行旅 › 时间花在哪里"], initials: "xlsjhznl", zhuyin: "ㄒㄌㄕㄐㄏㄗㄋㄌ", route: "time", digit: 3 },
  { label: ["爪印本", "爪印本"], initials: "zyb", zhuyin: "ㄓㄧㄅ", route: "book", digit: 4 },
  { label: ["鍵盤衣櫥", "键盘衣橱"], initials: "jpyc", zhuyin: "ㄐㄆㄧㄔ", route: "closet", digit: 2 },
  { label: ["鍵盤設定", "键盘设置"], initials: "jpsd", zhuyin: "ㄐㄆㄕㄉ", route: "settings", digit: 1 },
  { label: ["鍵盤設定 › 桌面貓", "键盘设置 › 桌面猫"], initials: "jpsdzmm", zhuyin: "ㄐㄆㄕㄉㄓㄇㄇ", route: "settings?at=desk", digit: 1 },
  { label: ["鍵盤設定 › 時間花在哪裡與好友狀態", "键盘设置 › 时间花在哪里与好友状态"], initials: "jpsdsjhznlyhyzt", zhuyin: "ㄐㄆㄕㄉㄕㄐㄏㄗㄋㄌㄩㄏㄧㄓㄊ", route: "settings?at=activity", digit: 1 },
  { label: ["鍵盤設定 › 隱私與帳本", "键盘设置 › 隐私与账本"], initials: "jpsdysyzb", zhuyin: "ㄐㄆㄕㄉㄧㄙㄩㄓㄅ", route: "settings?at=privacy", digit: 1 },
  { label: ["首頁", "首页"], initials: "sy", zhuyin: "ㄕㄧ", route: "home", digit: 0 },
  { label: ["生活照", "生活照"], initials: "shz", zhuyin: "ㄕㄏㄓ", route: "photos", digit: 7 },
  { label: ["試打", "试打"], initials: "sd", zhuyin: "ㄕㄉ", route: "home?at=try" },
  { label: ["第一次開啟", "第一次打开"], initials: "dycks", zhuyin: "ㄉㄧㄘㄎㄑ", route: "setup" },
  { label: ["叫牠們回家", "叫它们回家"], initials: "jtmhj", zhuyin: "ㄐㄊㄇㄏㄐ", action: "desk-home" },
  { label: ["讓牠們出門", "让它们出门"], initials: "rtmcm", zhuyin: "ㄖㄊㄇㄔㄇ", action: "desk-out" },
  { label: ["離開喵撲", "退出喵扑"], initials: "lkmp", zhuyin: "ㄌㄎㄇㄆ", action: "quit" },
];

const subsequence = (needle: string, hay: string): boolean => {
  let i = 0;
  for (const ch of hay) if (ch === needle[i]) i++;
  return i >= needle.length;
};

/** Every post the query finds, in the list's order. */
export function find(query: string): Post[] {
  const q = query.replace(/\s+/g, "").toLowerCase();
  if (!q) return POSTS;
  return POSTS.filter((p) => {
    if (/^[a-z]+$/.test(q)) return subsequence(q, p.initials);
    if (/^[ㄅ-ㄩˇˊˋ˙]+$/.test(q)) return subsequence(q.replace(/[ˇˊˋ˙]/g, ""), p.zhuyin);
    const plain = p.label.map((l) => l.replace(/ › /g, ""));
    return plain.some((l) => l.includes(q) || subsequence(q, l));
  });
}

export class Signpost {
  private el: HTMLElement;
  private input!: HTMLInputElement;
  private list!: HTMLElement;
  private sel = 0;
  private shown: Post[] = POSTS;

  constructor(
    layer: HTMLElement,
    private os: Platform,
    private go: (route: string) => void,
    private runAction?: (action: NonNullable<Post["action"]>) => void,
  ) {
    this.el = document.createElement("div");
    this.el.className = "signpost";
    this.el.hidden = true;
    this.el.innerHTML = `
      <div class="post-card" role="dialog" aria-label="${t("路牌", "路牌")}">
        <label class="post-q"><span class="mut">${t("往哪裡走？", "往哪里走？")}</span><input type="text" autocomplete="off" spellcheck="false"></label>
        <div class="post-list" role="listbox"></div>
        <div class="post-foot">${t("打拼音的頭一個字母就找得到：xl 是行旅", "打拼音的头一个字母就找得到：xl 是行旅")}</div>
      </div>`;
    layer.append(this.el);
    this.input = this.el.querySelector("input")!;
    this.list = this.el.querySelector(".post-list")!;
    this.input.addEventListener("input", () => {
      this.sel = 0;
      this.render();
    });
    this.input.addEventListener("keydown", (e) => this.key(e));
    this.el.addEventListener("mousedown", (e) => {
      if (e.target === this.el) this.close();
    });
    this.list.addEventListener("click", (e) => {
      const row = (e.target as HTMLElement).closest<HTMLElement>("[data-i]");
      if (row) this.take(Number(row.dataset.i));
    });
  }

  get open(): boolean {
    return !this.el.hidden;
  }

  toggle(query = ""): void {
    if (this.open) return this.close();
    this.el.hidden = false;
    this.input.value = query;
    this.sel = 0;
    this.render();
    this.input.focus();
  }

  close(): void {
    this.el.hidden = true;
    this.input.blur();
  }

  private key(e: KeyboardEvent): void {
    if (e.isComposing || e.keyCode === 229) return; // the input method's keys
    if (e.key === "Escape") {
      e.preventDefault();
      this.close();
    } else if (e.key === "ArrowDown" || (e.key === "Tab" && !e.shiftKey)) {
      e.preventDefault();
      this.move(1);
    } else if (e.key === "ArrowUp" || (e.key === "Tab" && e.shiftKey)) {
      e.preventDefault();
      this.move(-1);
    } else if (e.key === "Enter") {
      e.preventDefault();
      this.take(this.sel);
    }
  }

  private move(by: number): void {
    const n = this.shown.length;
    if (!n) return;
    this.sel = (this.sel + by + n) % n;
    this.render();
  }

  private take(i: number): void {
    const post = this.shown[i];
    if (!post || post.later) return;
    if (post.action) {
      this.close();
      this.runAction?.(post.action);
      return;
    }
    if (!post.route) return;
    this.close();
    this.go(post.route);
  }

  private render(): void {
    // While composing, the field already holds the reading (xl, ㄒㄌ), which is what the initials are matched with.
    this.shown = find(this.input.value);
    this.sel = Math.min(this.sel, Math.max(0, this.shown.length - 1));
    const mod = this.os === "mac" ? "⌘" : "Ctrl ";
    this.list.innerHTML = this.shown.length
      ? this.shown
          .map((p, i) => {
            const [head, ...rest] = t(...p.label).split(" › ");
            const tail = rest.length ? `<b> › </b>${esc(rest.join(" › "))}` : "";
            const side = p.later ? `<small class="later">${t(...p.later)}</small>` : p.digit !== undefined ? `<small>${mod}${p.digit}</small>` : "";
            return `<div class="post-it${i === this.sel ? " on" : ""}${p.later ? " off" : ""}" data-i="${i}" role="option"><span>${esc(head)}${tail}</span>${side}</div>`;
          })
          .join("")
      : `<div class="post-it off"><span class="mut">${t("這條路上沒有。按 Esc 回去。", "这条路上没有。按 Esc 回去。")}</span></div>`;
  }
}
