// 首頁: interactive dual-cat paper stage, entrances to lounge/trophies/journey/book/closet/settings, and 生活照.

import { api, type PhotoInfo } from "../api";
import { ymd } from "../dates";
import { esc, t, tt } from "../i18n";
import { heroHTML, layoutName, mountHero } from "../keyboard";
import { nav } from "../nav";
import { heroLayout, store, today } from "../store";
import { skinName } from "../skins";
import { figure, fill, onPhotosUpdated, todaysPhotos, waiting } from "../photos";
import { invoke } from "@tauri-apps/api/core";
import { hasEngine } from "../glyph";

const UNIT = 44;

export const modKey = (n: number | string): string => (store.info.platform === "mac" ? `⌘${n}` : `Ctrl ${n}`);

export function entranceValues(): Record<string, string> {
  const s = store.summary;
  const now = today();
  const { y, m } = ymd(now);
  const thisMonth = s.marks.filter((mk) => {
    const d = ymd(mk.day);
    return d.y === y && d.m === m;
  }).length;
  const marked = s.marks.some((mk) => mk.day === now);
  const unlocked = store.companion.progress?.unlocked?.length ?? 0;
  return {
    settings: t("偏好與隱私", "偏好与隐私"),
    closet: skinName(store.companion.skin),
    journey: t(`${s.days} 座山`, `${s.days} 座山`),
    book: marked ? t("今天有一枚", "今天有一枚") : t(`這個月 ${thisMonth} 枚`, `这个月 ${thisMonth} 枚`),
    lounge: t("互動・遠征・佈置", "互动・远征・布置"),
    trophies: `${unlocked}/82`,
  };
}

function steamWord(): string {
  const s = store.steam;
  if (!s?.built) return t("Steam 還沒接上", "Steam 还没接上");
  return s.connected ? t("Steam 已連上", "Steam 已连上") : t("Steam 沒有開著", "Steam 没有开着");
}

export function showSteamBits(app: HTMLElement): void {
  const word = app.querySelector<HTMLElement>("[data-steam]");
  if (word) word.textContent = steamWord();
  const settled = app.querySelector<HTMLElement>("[data-settled]");
  if (settled) settled.hidden = !store.steam?.settlement?.sent.length;
}

async function showHere(app: HTMLElement): Promise<void> {
  if (!hasEngine) return;
  const here = await invoke<{ name: string; stop: number }>("glyph_here").catch(() => null);
  const place = app.querySelector<HTMLElement>(".where .place");
  if (!here || !place) return;
  place.textContent = `— ${here.name} —`;
  place.removeAttribute("title");
  const days = app.querySelector<HTMLElement>(".where span:nth-child(2)");
  if (days) days.textContent = t(`第 ${here.stop} 站 · ${store.summary.days} 座山`, `第 ${here.stop} 站 · ${store.summary.days} 座山`);
}

function showPhotos(app: HTMLElement): () => void {
  let stop = () => {};
  let list: PhotoInfo[] = [];
  const draw = async () => {
    const view = await todaysPhotos();
    const row = app.querySelector<HTMLElement>("[data-photos]");
    if (!row) return;
    list = view.photos.slice(0, 3);
    stop();
    row.innerHTML = list.length ? list.map((p) => figure(p, 150)).join("") : `<p class="mut photos-wait">${esc(waiting(view.haveManifest))}</p>`;
    stop = fill(row);
  };
  app.querySelector<HTMLElement>("[data-photos]")?.addEventListener("click", (e) => {
    if ((e.target as HTMLElement).closest("figure.ph")) nav.go("photos");
  });
  const off = onPhotosUpdated(() => void draw());
  void draw();
  return () => {
    stop();
    off();
  };
}

export function renderHome(app: HTMLElement, params: URLSearchParams): () => void {
  const os = store.info.platform;
  const mod = os === "mac" ? "⌘" : "Ctrl";
  const v = entranceValues();
  const ways: [string, [string, string], string, number][] = [
    ["lounge", ["小貓客廳", "小猫客厅"], v.lounge, 5],
    ["trophies", ["成就牆", "成就墙"], v.trophies, 6],
    ["closet", ["貓貓衣櫥", "猫猫衣橱"], v.closet, 2],
    ["journey", ["行旅長卷", "行旅长卷"], v.journey, 3],
    ["book", ["爪印本", "爪印本"], v.book, 4],
    ["settings", ["設定與關於", "设置与关于"], v.settings, 1],
  ];
  app.innerHTML = `
    <header class="hd">
      <div class="wm"><b>${t("喵撲", "喵扑")}</b><small>Meowpuut</small></div>
      <div class="where">
        <span class="place">— ？ —</span>
        <span>${t(`${store.summary.days} 座山`, `${store.summary.days} 座山`)}</span>
      </div>
    </header>
    <div class="meta">
      <span>${esc(layoutName(os, heroLayout()))}</span>
      <span>${t("直接點貓咪即可互動", "直接点猫咪即可互动")}　<kbd>${mod}</kbd><kbd>K</kbd> ${t("路牌", "路牌")}</span>
    </div>
    <div class="hero-wrap">${heroHTML({ os, layout: heroLayout(), skin: store.companion.skin, u: UNIT })}</div>
    <p class="home-cat-say mut" data-home-say>${t("點一下窗台上的米米莫或莫莫摸摸牠們，或進「小貓客廳」打開陪寫小書桌與遠征！", "点一下窗台上的米米莫或莫莫摸摸它们，或进「小猫客厅」打开陪写小书桌与远征！")}</p>
    <div class="two">
      <section class="try" id="try">
        <h2>${t("走進小貓客廳", "走进小猫客厅")}</h2>
        <p class="sub">${t("無需系統輸入法或權限，即可逗貓、佈置家具、派紙鶴遠征，並用陪寫小書桌長出山水長卷。", "无需系统输入法或权限，即可逗猫、布置家具、派纸鹤远征，并用陪写小书桌长出山水长卷。")}</p>
        <div class="btns" style="margin-top:10px">
          <button class="btn primary" data-route="lounge">${t("🐱 打開小貓客廳", "🐱 打开小猫客厅")}</button>
          <button class="btn" data-route="trophies">${t("🏆 82 顆像素成就牆", "🏆 82 颗像素成就墙")}</button>
        </div>
      </section>
      <nav class="ways">
        ${ways
          .map(
            ([route, name, value, n]) =>
              `<button class="way" data-route="${route}"><span class="t">${t(...name)}</span><span class="v">${esc(value)}</span><span class="k">${modKey(n)}</span></button>`,
          )
          .join("")}
      </nav>
    </div>
    <section class="photos-sec" id="photos">
      <h2>${t("生活照", "生活照")} <button class="more" data-route="photos">${t("全部 ›", "全部 ›")}</button></h2>
      <p class="note">${t("米米莫和莫莫的生活照，每次打開換一個順序。", "米米莫和莫莫的生活照，每次打开换一个顺序。")}</p>
      <div class="photos" data-photos></div>
    </section>
    <footer class="ft">
      <span>${t("零全域監聽、零帳號個資。所有數字與陪伴紀錄只留在本機。", "零全局监听、零账号个资。所有数字与陪伴记录只留在本机。")}</span>
      <span>${t("喵撲", "喵扑")} ${esc(store.info.version)}${store.info.dataDirOverridden ? ` · <b class="dev">${t("試用資料夾", "试用文件夹")}</b>` : ""} · <span data-steam>${steamWord()}</span></span>
    </footer>
    <p class="settled" data-settled hidden>${t("牠們出門時做到的事，現在補上了。", "它们出门时做到的事，现在补上了。")}</p>`;
  showSteamBits(app);
  void showHere(app);

  for (const button of app.querySelectorAll<HTMLElement>("[data-route]")) {
    button.addEventListener("click", () => nav.go(button.dataset.route!));
  }
  const catImgs = app.querySelectorAll<HTMLImageElement>(".hero-cats img");
  catImgs.forEach((img, idx) => {
    const who: "mimimo" | "momo" = idx === 0 ? "mimimo" : "momo";
    img.title = who === "mimimo" ? t("摸摸米米莫", "摸摸米米莫") : t("摸摸莫莫", "摸摸莫莫");
    img.addEventListener("click", (e) => {
      e.stopPropagation();
      img.classList.remove("boop");
      void img.offsetWidth;
      img.classList.add("boop");
      void api.loungeInteract("pat", who).then((res) => {
        const say = app.querySelector<HTMLElement>("[data-home-say]");
        if (say) say.textContent = tt(res.reaction.dialogue);
      }).catch(() => {});
    });
  });

  const at = params.get("at");
  if (at === "photos") requestAnimationFrame(() => app.querySelector("#photos")?.scrollIntoView({ block: "start" }));
  const stopFill = showPhotos(app);
  const glyph = app.querySelector<HTMLElement>(".hero[data-glyph]");
  const hero = glyph ? mountHero(glyph) : null;
  return () => {
    hero?.dispose();
    stopFill();
  };
}
