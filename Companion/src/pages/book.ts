// 爪印本: an open paper calendar of days together. Right leaf renders today's real cat photo + working postcard PNG export.

import { api, type FestivalDay, type Mark } from "../api";
import { dayOf, daysInMonth, ymd } from "../dates";
import { esc, monthName, t } from "../i18n";
import { figure, fill, todaysPhotos } from "../photos";
import { store, today } from "../store";

const WEEK: [string, string][] = [["日", "日"], ["一", "一"], ["二", "二"], ["三", "三"], ["四", "四"], ["五", "五"], ["六", "六"]];
const PART_DAWN = 1 << 0;
const PART_LATE = 1 << 4;

export function pawClass(mark: Mark): string {
  return `paw s${mark.size}${mark.who === 2 ? " momo" : ""}`;
}

function downloadPostcard(y: number, m: number, lines: string[]): void {
  const cv = document.createElement("canvas");
  cv.width = 960;
  cv.height = 540;
  const ctx = cv.getContext("2d");
  if (!ctx) return;
  ctx.fillStyle = "#F7EFE3";
  ctx.fillRect(0, 0, cv.width, cv.height);
  ctx.strokeStyle = "#2C2A32";
  ctx.lineWidth = 4;
  ctx.strokeRect(24, 24, cv.width - 48, cv.height - 48);
  ctx.fillStyle = "#B82E1F";
  ctx.font = "bold 40px serif";
  ctx.fillText(`喵撲 Meowpuut · ${y} ${monthName(m)}`, 64, 110);
  ctx.fillStyle = "#221C22";
  ctx.font = "28px sans-serif";
  lines.forEach((line, idx) => ctx.fillText(line, 64, 195 + idx * 52));
  ctx.fillStyle = "#7A6A5B";
  ctx.font = "20px sans-serif";
  ctx.fillText("米米莫 & 莫莫 爪印本回憶明信片", 64, 470);
  const a = document.createElement("a");
  a.href = cv.toDataURL("image/png");
  a.download = `meowpuut-book-${y}-${String(m).padStart(2, "0")}.png`;
  a.click();
}

export function renderBook(app: HTMLElement, params: URLSearchParams): () => void {
  const now = today();
  const marks = new Map(store.summary.marks.map((mk) => [mk.day, mk]));
  const first = store.summary.marks[0]?.day ?? now;
  const lo = ymd(first);
  const hi = ymd(now);
  let { y, m } = ymd(now);
  const asked = /^(\d{4})-(\d{1,2})$/.exec(params.get("month") ?? "");
  if (asked) [y, m] = [Number(asked[1]), Number(asked[2])];

  let renderSeq = 0;
  let stopFill = () => {};

  const draw = () => {
    const seq = ++renderSeq;
    const start = dayOf(y, m, 1);
    const count = daysInMonth(y, m);
    const renderMonth = (fests: Map<number, FestivalDay>) => {
      if (seq !== renderSeq) return;
      stopFill();
      const lead = ymd(start).w;
      const inMonth = [...Array(count)].map((_, i) => marks.get(start + i)).filter((mk): mk is Mark => !!mk);
      const late = inMonth.filter((mk) => mk.part & PART_LATE).length;
      const dawn = inMonth.filter((mk) => mk.part & PART_DAWN).length;
      const festMet = inMonth.filter((mk) => fests.has(mk.day)).length;
      const canBack = y > lo.y || (y === lo.y && m > lo.m);
      const canOn = y < hi.y || (y === hi.y && m < hi.m);
      const cells: string[] = [];
      for (let i = 0; i < lead; i++) cells.push(`<div class="dd blank"></div>`);
      for (let d = 1; d <= count; d++) {
        const day = start + d - 1;
        const mk = marks.get(day);
        const fest = fests.get(day);
        const festTitle = fest ? t(...fest.name) : "";
        cells.push(
          `<div class="dd${day === now ? " today" : ""}${day > now ? " later" : ""}${fest ? " fest" : ""}"${festTitle ? ` title="${esc(festTitle)}"` : ""}><span class="dn">${d}</span>` +
            (day === now ? `<span class="tag">${t("今天", "今天")}</span>` : fest ? `<span class="ftag">${esc(festTitle)}</span>` : "") +
            (mk ? `<i class="${pawClass(mk)}" title="${t("那天的山勢", "那天的山势")}: ${["小丘", "山坡", "山", "高山", "雪峰"][mk.size]}"></i>` : "") +
            `</div>`,
        );
      }
      const lines = [t(`${inMonth.length} 個日子有爪印。`, `${inMonth.length} 个日子有爪印。`)];
      if (dawn) lines.push(t(`${dawn} 天在清晨就出門了。`, `${dawn} 天在清晨就出门了。`));
      if (late) lines.push(t(`${late} 天走到了深夜。`, `${late} 天走到了深夜。`));
      if (festMet) lines.push(t(`遇見了 ${festMet} 個節日。`, `遇见了 ${festMet} 个节日。`));
      if (!inMonth.length) lines.splice(0, 1, t("這個月還是白紙，隨時歡迎來坐坐。", "这个月还是白纸，随时欢迎来坐坐。"));

      app.innerHTML = `
      <header class="page-hd">
        <h1>${t("爪印本", "爪印本")}</h1>
        <span class="mut">${t("米米莫和莫莫走過的日子。沒有空格要補，也沒有人在數。", "米米莫和莫莫走过的日子。没有空格要补，也没有人在数。")}</span>
      </header>
      <div class="book">
        <section class="leaf left">
          <div class="month">
            <button class="turn" data-by="-1" ${canBack ? "" : "disabled"} aria-label="${t("上個月", "上个月")}">‹</button>
            <h2>${monthName(m)}<small>${y}</small></h2>
            <button class="turn" data-by="1" ${canOn ? "" : "disabled"} aria-label="${t("下個月", "下个月")}">›</button>
          </div>
          <div class="cal">
            ${WEEK.map((w) => `<div class="dw">${t(...w)}</div>`).join("")}
            ${cells.join("")}
          </div>
          <p class="legend mut">${t("爪印越大，那天山勢越高；棕色的是莫莫陪走的日子；朱紅日期是節日。", "爪印越大，那天山势越高；棕色的是莫莫陪走的日子；朱红日期是节日。")}</p>
        </section>
        <section class="leaf right">
          <h2>${t("這個月", "这个月")}</h2>
          <div class="photo-slot tilt" data-book-photo><span>${t("載入本月生活照…", "加载本月生活照…")}</span></div>
          <p class="month-note">${lines.join("<br>")}</p>
          <div class="book-btns">
            <button class="btn primary" data-export-postcard>${t("做成明信片", "做成明信片")}</button>
          </div>
        </section>
      </div>`;

      for (const b of app.querySelectorAll<HTMLButtonElement>(".turn")) {
        b.addEventListener("click", () => {
          m += Number(b.dataset.by);
          if (m < 1) [y, m] = [y - 1, 12];
          if (m > 12) [y, m] = [y + 1, 1];
          draw();
        });
      }
      app.querySelector<HTMLButtonElement>("[data-export-postcard]")?.addEventListener("click", () => {
        downloadPostcard(y, m, lines);
      });

      const slot = app.querySelector<HTMLElement>("[data-book-photo]");
      if (slot) {
        void todaysPhotos().then((pv) => {
          if (seq !== renderSeq || !slot.isConnected) return;
          if (pv.photos.length) {
            slot.innerHTML = figure(pv.photos[0], 180);
            stopFill = fill(slot);
          } else {
            slot.innerHTML = `<span>${t("米米莫與莫莫正在整理相簿", "米米莫与莫莫正在整理相簿")}</span>`;
          }
        }).catch(() => {});
      }
    };
    renderMonth(new Map());
    void api
      .bookFestivals(start, start + count - 1)
      .then((list) => {
        if (list.length) renderMonth(new Map(list.map((f) => [f.day, f])));
      })
      .catch(() => {});
  };
  draw();
  return () => stopFill();
}
