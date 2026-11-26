// 時間花在哪裡 (FINAL.md §11.1, under 行旅): what the desk counted in Activity.bin, days × ten categories × minutes.
// This week as one stacked bar per day, this month's minutes per category, and the five day parts. Calm on purpose:
// no streaks, no rankings (categories always stand in their own fixed order), no comparisons with last week. With
// recording off the page explains what it would record and links to the switch.

import { api, type ActivityPage } from "../api";
import { CATEGORIES, catName, dur, durShort } from "../categories";
import { dayOf, daysInMonth, iso, ymd } from "../dates";
import { esc, fmt, monthName, t } from "../i18n";
import { nav } from "../nav";
import { today } from "../store";
import { PARTS } from "./journey";

const WEEKDAYS: [string, string][] = [["週日", "周日"], ["週一", "周一"], ["週二", "周二"], ["週三", "周三"], ["週四", "周四"], ["週五", "周五"], ["週六", "周六"]];
const PART_HOURS = ["05–08", "08–17", "17–20", "20–23", "23–05"];

function monthRange(offset: number): { y: number; m: number; first: number; last: number } {
  const now = ymd(today());
  const index = now.y * 12 + (now.m - 1) + offset;
  const y = Math.floor(index / 12);
  const m = (index % 12) + 1;
  return { y, m, first: dayOf(y, m, 1), last: dayOf(y, m, daysInMonth(y, m)) };
}

export function renderTime(app: HTMLElement, params: URLSearchParams): () => void {
  let week = Math.min(0, Number(params.get("week")) || 0);
  let month = Math.min(0, Number(params.get("month")) || 0);
  let alive = true;

  const draw = async () => {
    const now = today();
    const weekStart = now - ymd(now).w + 7 * week;
    const mr = monthRange(month);
    let page: ActivityPage;
    try {
      page = await api.activity(weekStart, mr.first, mr.last);
    } catch (error) {
      app.innerHTML = `<p class="warn">${esc(String(error))}</p>`;
      return;
    }
    if (!alive) return;
    const v = page.view;
    const head = `
      <header class="page-hd time-hd">
        <button class="crumb" data-go="journey">‹ ${t("行旅", "行旅")}</button>
        <h1>${t("時間花在哪裡", "时间花在哪里")}</h1>
        ${page.recording ? `<span class="chip${page.paused ? " paused" : ""}">${page.paused ? t("暫停中", "暂停中") : t("記錄中", "记录中")}</span>` : ""}
      </header>`;
    if (!page.recording) {
      app.innerHTML = head + empty(v.daysKept);
      wire();
      return;
    }
    const first = v.firstDay || now;
    const wEnd = weekStart + 6;
    const a = ymd(weekStart);
    const b = ymd(wEnd);
    const weekLabel = a.m === b.m
      ? t(`${monthName(a.m)} ${a.d} 日 – ${b.d} 日`, `${monthName(a.m)} ${a.d} 日 – ${b.d} 日`)
      : t(`${monthName(a.m)} ${a.d} 日 – ${monthName(b.m)} ${b.d} 日`, `${monthName(a.m)} ${a.d} 日 – ${monthName(b.m)} ${b.d} 日`);
    const monthLabel = mr.y === ymd(now).y ? monthName(mr.m) : `${mr.y} ${t("年", "年")} ${monthName(mr.m)}`;
    app.innerHTML = `
      ${head}
      <p class="lede mut">${t("喵撲貓窩在這台電腦上數的：每天各類 App 用了幾分鐘。只有類別和分鐘，沒有 App 名稱、標題或網址。", "喵扑猫窝在这台电脑上数的：每天各类 App 用了几分钟。只有类别和分钟，没有 App 名称、标题或网址。")}</p>
      <section class="tsec">
        <div class="tsec-hd">
          <h2>${week === 0 ? t("這一週", "这一周") : weekLabel}</h2>
          <div class="pager">
            <button class="turn" data-week="-1" ${weekStart <= first ? "disabled" : ""} aria-label="${t("上一週", "上一周")}">‹</button>
            <span class="mut">${week === 0 ? weekLabel : ""}</span>
            <button class="turn" data-week="1" ${week >= 0 ? "disabled" : ""} aria-label="${t("下一週", "下一周")}">›</button>
          </div>
          <span class="sum mut">${v.weekTotal ? t(`一共 ${dur(v.weekTotal)}`, `一共 ${dur(v.weekTotal)}`) : ""}</span>
        </div>
        ${weekChart(page, now)}
        <div class="legend">${CATEGORIES.map((_, i) => `<span class="${v.week.some((d) => d.minutes[i] > 0) ? "" : "absent"}"><i class="sw c${i}"></i>${catName(i)}</span>`).join("")}</div>
      </section>
      <div class="tgrid">
        <section class="tsec">
          <div class="tsec-hd">
            <h2>${monthLabel}</h2>
            <div class="pager">
              <button class="turn" data-month="-1" ${mr.first <= first ? "disabled" : ""} aria-label="${t("上個月", "上个月")}">‹</button>
              <button class="turn" data-month="1" ${month >= 0 ? "disabled" : ""} aria-label="${t("下個月", "下个月")}">›</button>
            </div>
            <span class="sum mut">${v.monthDays ? t(`${v.monthDays} 天在電腦前・一共 ${dur(v.monthTotal)}`, `${v.monthDays} 天在电脑前・一共 ${dur(v.monthTotal)}`) : t("這個月還沒有紀錄", "这个月还没有记录")}</span>
          </div>
          ${monthTable(page)}
        </section>
        <section class="tsec">
          <div class="tsec-hd"><h2>${t("一天裡的時刻", "一天里的时刻")}</h2></div>
          <p class="note">${t(`${monthLabel}，每個時段有幾天在電腦前。算的是天數，不是分鐘。`, `${monthLabel}，每个时段有几天在电脑前。算的是天数，不是分钟。`)}</p>
          ${partsTable(page)}
        </section>
      </div>
      <footer class="tfoot mut">
        <span>Activity.bin · ${t(`${v.daysKept} 天`, `${v.daysKept} 天`)} · ${fmt(v.fileBytes)} / ${fmt(v.stateBytes)} bytes${v.keepDays ? t(` · 只留近 ${v.keepDays} 天`, ` · 只留近 ${v.keepDays} 天`) : ""}</span>
        <button class="linkish" data-go="settings?at=activity">${t("暫停、不記錄的 App、清除 ›", "暂停、不记录的 App、清除 ›")}</button>
      </footer>`;
    wire();
  };

  const tip = () => app.querySelector<HTMLElement>(".wtip");
  const wire = () => {
    for (const b of app.querySelectorAll<HTMLElement>("[data-go]")) b.addEventListener("click", () => nav.go(b.dataset.go!));
    for (const b of app.querySelectorAll<HTMLButtonElement>("[data-week]"))
      b.addEventListener("click", () => {
        week = Math.min(0, week + Number(b.dataset.week));
        history.replaceState(null, "", `#/time?week=${week}&month=${month}`);
        void draw();
      });
    for (const b of app.querySelectorAll<HTMLButtonElement>("[data-month]"))
      b.addEventListener("click", () => {
        month = Math.min(0, month + Number(b.dataset.month));
        history.replaceState(null, "", `#/time?week=${week}&month=${month}`);
        void draw();
      });
    // Hover: one tooltip for the segment under the pointer (the hit target is the whole segment, at least 6 px tall).
    const chart = app.querySelector<HTMLElement>(".wchart");
    chart?.addEventListener("mousemove", (e) => {
      const seg = (e.target as HTMLElement).closest<HTMLElement>("[data-tip]");
      const box = tip();
      if (!box) return;
      if (!seg) {
        box.hidden = true;
        return;
      }
      box.innerHTML = seg.dataset.tip!;
      box.hidden = false;
      const r = chart.getBoundingClientRect();
      const x = Math.min(e.clientX - r.left + 12, r.width - box.offsetWidth - 4);
      box.style.left = `${Math.max(0, x)}px`;
      box.style.top = `${Math.max(0, e.clientY - r.top - box.offsetHeight - 10)}px`;
    });
    chart?.addEventListener("mouseleave", () => {
      const box = tip();
      if (box) box.hidden = true;
    });
  };

  void draw();
  return () => {
    alive = false;
  };
}

function empty(daysKept: number): string {
  const kept: [string, string][] = [["哪一天", "哪一天"], ["哪一類 App（十類之一）", "哪一类 App（十类之一）"], ["幾分鐘", "几分钟"], ["大約在一天裡的哪個時段", "大约在一天里的哪个时段"]];
  const not: [string, string][] = [["App 名稱", "App 名称"], ["視窗標題、網址", "窗口标题、网址"], ["你打了什麼", "你打了什么"], ["幾點幾分", "几点几分"]];
  return `
    <section class="tempty">
      <h2>${t("還沒有開始記錄", "还没有开始记录")}</h2>
      <p>${t("打開「記錄時間花在哪裡」，喵撲貓窩會在這台電腦上數每天各類 App 用了幾分鐘：", "打开「记录时间花在哪里」，喵扑猫窝会在这台电脑上数每天各类 App 用了几分钟：")}${CATEGORIES.map((_, i) => catName(i)).join(t("、", "、"))}${t("。", "。")}</p>
      <div class="knows">
        <div><h3>${t("記下的", "记下的")}</h3><ul>${kept.map((k) => `<li>${t(...k)}</li>`).join("")}</ul></div>
        <div><h3>${t("不記的", "不记的")}</h3><ul>${not.map((k) => `<li>${t(...k)}</li>`).join("")}</ul></div>
      </div>
      <p class="mut">${t("預設關著。這些數字只在這台電腦上；好友看不到，除非你另外打開「讓 Steam 好友看到我在做什麼」。", "默认关着。这些数字只在这台电脑上；好友看不到，除非你另外打开「让 Steam 好友看到我在做什么」。")}</p>
      <div class="btns"><button class="btn" data-go="settings?at=activity">${t("去打開", "去打开")}</button></div>
      ${daysKept > 0 ? `<p class="mut small">${t(`之前記下的 ${daysKept} 天還在；要清除，到鍵盤設定的「時間花在哪裡」。`, `之前记下的 ${daysKept} 天还在；要清除，到键盘设置的「时间花在哪里」。`)}</p>` : ""}
    </section>`;
}

/** Seven stacked bars, categories bottom-up in their fixed order, a 2 px paper gap between segments. */
function weekChart(page: ActivityPage, now: number): string {
  const v = page.view;
  const top = Math.max(60, ...v.week.map((d) => d.total));
  const hours = Math.ceil(top / 60);
  const step = hours <= 4 ? 1 : hours <= 8 ? 2 : hours <= 16 ? 4 : 6;
  const max = Math.ceil(hours / step) * step * 60;
  const grid = [...Array(max / 60 / step + 1)].map((_, i) => {
    const h = i * step;
    return `<div class="gl" style="bottom:${(h * 60 * 100) / max}%"><span>${h ? t(`${h} 時`, `${h} 时`) : ""}</span></div>`;
  });
  const cols = v.week.map((d) => {
    const date = ymd(d.day);
    const label = `${t(...WEEKDAYS[date.w])} ${date.m}/${date.d}`;
    const future = d.day > now;
    const segs = d.minutes
      .map((m, c) =>
        m > 0
          ? `<i class="seg c${c}" style="flex:${m} 1 0" data-tip="${esc(`<b>${label}</b> · ${catName(c)}<br>${dur(m)}`)}"></i>`
          : "",
      )
      .join("");
    return `
      <div class="wcol${d.day === now ? " now" : ""}${future ? " future" : ""}">
        <div class="wplot"><div class="wbar" style="height:${(d.total * 100) / max}%">${segs}</div><span class="wtot" style="bottom:${(d.total * 100) / max}%">${durShort(d.total)}</span></div>
        <div class="wday"><b>${t(...WEEKDAYS[date.w])}</b><span>${date.m}/${date.d}</span></div>
      </div>`;
  });
  const summary = v.week.map((d) => `${iso(d.day)} ${dur(d.total)}`).join("; ");
  return `
    <div class="wchart" role="img" aria-label="${esc(summary)}">
      <div class="wgrid">${grid.join("")}</div>
      <div class="wcols">${cols.join("")}</div>
      <div class="wtip" hidden></div>
    </div>`;
}

/** The month as a table: every category in its own order, its minutes as a thin bar and in words. */
function monthTable(page: ActivityPage): string {
  const v = page.view;
  const top = Math.max(1, ...v.month);
  return `
    <table class="mtable">
      <tbody>
        ${v.month
          .map(
            (m, c) => `
          <tr class="${m ? "" : "zero"}">
            <th scope="row"><i class="sw c${c}"></i>${catName(c)}</th>
            <td class="mbar"><i class="c${c}" style="width:${m ? Math.max(1.5, (m * 100) / top) : 0}%"></i></td>
            <td class="mval">${m ? dur(m) : "—"}</td>
          </tr>`,
          )
          .join("")}
      </tbody>
    </table>`;
}

/** The five day parts: on how many days of the month each had any activity, and a strip of which categories. */
function partsTable(page: ActivityPage): string {
  const v = page.view;
  const days = Math.max(1, ...v.parts.map((p) => p.days));
  return `
    <div class="ptable">
      ${v.parts
        .map((p, i) => {
          const sum = p.byCategory.reduce((a, b) => a + b, 0) || 1;
          const strip = p.byCategory
            .map((n, c) => (n ? `<i class="c${c}" style="flex:${n}" title="${esc(`${catName(c)} · ${t(`${n} 天`, `${n} 天`)}`)}"></i>` : ""))
            .join("");
          return `
          <div class="prow${p.days ? "" : " zero"}">
            <span class="pname">${t(...PARTS[i])}<small>${PART_HOURS[i]}</small></span>
            <span class="pstrip" style="width:${(p.days * 100) / days}%" data-sum="${sum}">${strip}</span>
            <span class="pval">${p.days ? t(`${p.days} 天`, `${p.days} 天`) : "—"}</span>
          </div>`;
        })
        .join("")}
    </div>`;
}
