// 貓貓陪寫小書桌: pure in-game writing pad drawer (zero global hooks, zero disk text persistence).
// Strictly listens only to `isTrusted` `beforeinput` (`insertText` / `insertCompositionText`), blocks paste/drop,
// clears memory on close, and batches validated grapheme counts + cadence intervals to Rust `pad_commit`.

import { api, type PadCommitOutcome } from "../api";
import { esc, t } from "../i18n";

export interface PadMount {
  dispose: () => void;
}

const SEG = typeof Intl !== "undefined" && "Segmenter" in Intl
  ? new Intl.Segmenter("zh", { granularity: "grapheme" })
  : null;

function countGraphemes(text: string): { count: number; unique: Set<string> } {
  const unique = new Set<string>();
  let count = 0;
  if (SEG) {
    for (const seg of SEG.segment(text)) {
      if (seg.segment.trim()) {
        count++;
        unique.add(seg.segment);
      }
    }
  } else {
    for (const ch of text) {
      if (ch.trim()) {
        count++;
        unique.add(ch);
      }
    }
  }
  return { count, unique };
}

export function renderPadDrawerHTML(open: boolean, sessionCount: number, statusLine: string): string {
  if (!open) return "";
  return `
    <section class="l-pad" data-pad-drawer>
      <div class="l-pad-hd">
        <div>
          <b>${t("✏️ 貓貓陪寫小書桌", "✏️ 猫猫陪写小书桌")}</b>
          <small class="mut">${t("只收你在這扇窗內親手敲下的字素，關窗即清空記憶體、絕不存檔。", "只收你在这扇窗内亲手敲下的字素，关窗即清空内存、绝不存档。")}</small>
        </div>
        <div class="l-pad-meta">
          <span class="l-pill" data-pad-count>${t(`本次 ${sessionCount} 字`, `本次 ${sessionCount} 字`)}</span>
          <button class="btn" data-pad-copy>${t("複製草稿", "复制草稿")}</button>
          <button class="btn" data-pad-close>${t("收起並清空", "收起并清空")}</button>
        </div>
      </div>
      <textarea class="l-pad-input" data-pad-input rows="4" spellcheck="false" autocomplete="off"
        placeholder="${t("隨便寫幾句日記、靈感或備忘——米米莫和莫莫會跟著拍紙長出小墨山（不支援貼上或拖放）…", "随便写几句日记、灵感或备忘——米米莫和莫莫会跟着拍纸长出小墨山（不支持粘贴或拖放）…")}"></textarea>
      <div class="l-pad-ft">
        <canvas class="l-pad-hills" data-pad-hills width="320" height="36" aria-hidden="true"></canvas>
        <small class="mut" data-pad-status>${esc(statusLine)}</small>
      </div>
    </section>`;
}

export function mountPadDrawer(
  root: HTMLElement,
  onCommitted: (out: PadCommitOutcome) => void,
  onBlocked: (msg: string) => void,
  onClose: () => void,
): PadMount {
  const textarea = root.querySelector<HTMLTextAreaElement>("[data-pad-input]");
  const countEl = root.querySelector<HTMLElement>("[data-pad-count]");
  const statusEl = root.querySelector<HTMLElement>("[data-pad-status]");
  const hills = root.querySelector<HTMLCanvasElement>("[data-pad-hills]");
  let sessionCount = 0;
  let pendingCount = 0;
  let lastInputAt = 0;
  const intervals: number[] = [];
  const uniqueChars = new Set<string>();
  let flushTimer: number | null = null;

  const drawHills = () => {
    if (!hills) return;
    const ctx = hills.getContext("2d");
    if (!ctx) return;
    ctx.clearRect(0, 0, hills.width, hills.height);
    const peaks = Math.min(18, Math.max(1, Math.ceil(sessionCount / 8)));
    ctx.fillStyle = "rgba(44, 42, 50, 0.22)";
    ctx.beginPath();
    ctx.moveTo(0, hills.height);
    for (let i = 0; i <= peaks; i++) {
      const x = (i / peaks) * hills.width;
      const h = 8 + ((i * 17 + sessionCount) % 22);
      ctx.lineTo(x, hills.height - h);
    }
    ctx.lineTo(hills.width, hills.height);
    ctx.closePath();
    ctx.fill();
  };

  const flush = async () => {
    if (flushTimer !== null) {
      window.clearTimeout(flushTimer);
      flushTimer = null;
    }
    if (pendingCount <= 0) return;
    const batch = pendingCount;
    const iv = intervals.splice(0, 32);
    const uniq = uniqueChars.size;
    pendingCount = 0;
    uniqueChars.clear();
    try {
      const out = await api.padCommit(batch, iv, uniq);
      if (statusEl && out.accepted > 0) {
        statusEl.textContent = t(`已安全寫入帳本 +${out.accepted} 字`, `已安全写入账本 +${out.accepted} 字`);
      }
      onCommitted(out);
    } catch {
      if (statusEl) {
        statusEl.textContent = t("敲字太快或重複字元過多，雙貓先歇一口氣再記奧。", "敲字太快或重复字符过多，双猫先歇一口气再记奥。");
      }
    }
  };

  const onBeforeInput = (e: InputEvent) => {
    if (!e.isTrusted) {
      e.preventDefault();
      return;
    }
    if (e.inputType === "insertFromPaste" || e.inputType === "insertFromDrop") {
      e.preventDefault();
      onBlocked(t("米米莫皺眉 wer：小書桌只能親手敲字，不收貼上或拖放奧！", "米米莫皱眉 wer：小书桌只能亲手敲字，不收粘贴或拖放奥！"));
      return;
    }
    if (e.inputType !== "insertText" && e.inputType !== "insertCompositionText") return;
    const data = e.data ?? "";
    if (!data) return;
    const { count, unique } = countGraphemes(data);
    if (count <= 0) return;
    const now = performance.now();
    if (lastInputAt > 0) intervals.push(Math.min(5000, Math.round(now - lastInputAt)));
    lastInputAt = now;
    sessionCount += count;
    pendingCount += count;
    for (const ch of unique) uniqueChars.add(ch);
    if (countEl) countEl.textContent = t(`本次 ${sessionCount} 字`, `本次 ${sessionCount} 字`);
    drawHills();
    if (pendingCount >= 16) void flush();
    else if (flushTimer === null) flushTimer = window.setTimeout(() => void flush(), 1800);
  };

  const blockPasteDrop = (e: Event) => {
    e.preventDefault();
    onBlocked(t("米米莫蓋住紙面：小書桌謝絕貼上與拖放，親手寫才算數奧！", "米米莫盖住纸面：小书桌谢绝粘贴与拖放，亲手写才算数奥！"));
  };

  textarea?.addEventListener("beforeinput", onBeforeInput);
  textarea?.addEventListener("paste", blockPasteDrop);
  textarea?.addEventListener("drop", blockPasteDrop);
  root.querySelector<HTMLButtonElement>("[data-pad-copy]")?.addEventListener("click", () => {
    if (textarea?.value) void navigator.clipboard?.writeText(textarea.value).catch(() => {});
  });
  root.querySelector<HTMLButtonElement>("[data-pad-close]")?.addEventListener("click", () => {
    void flush().finally(() => {
      if (textarea) textarea.value = "";
      onClose();
    });
  });

  drawHills();
  return {
    dispose: () => {
      if (flushTimer !== null) window.clearTimeout(flushTimer);
      if (textarea) textarea.value = "";
    },
  };
}
