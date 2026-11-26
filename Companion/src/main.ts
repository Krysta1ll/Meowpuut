// The companion app's page. One window; pages are swapped in place (#/home, #/book, #/journey, #/settings,
// #/closet, #/setup). Keys are this window's own: shortcuts work only while it has focus.

import "./styles.css";
import "./lounge.css";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { setScript, t } from "./i18n";
import { mountHero, type Hero } from "./keyboard";
import { nav } from "./nav";
import { Signpost } from "./signpost";
import { api, onBackend } from "./api";
import { dress, loadAll, reloadLedger, reloadShare, reloadSteam, store } from "./store";
import { renderHome } from "./pages/home";
import { renderBook } from "./pages/book";
import { renderJourney } from "./pages/journey";
import { renderSettings } from "./pages/settings";
import { renderCloset } from "./pages/closet";
import { renderSetup } from "./pages/setup";
import { renderTime } from "./pages/time";
import { renderPhotos } from "./pages/photos";
import { renderLounge } from "./pages/lounge";
import { renderTrophies } from "./pages/trophies";
import { PHOTOS_EVENT } from "./photos";
import { showSteamBits } from "./pages/home";

export type Cleanup = (() => void) | void;
type Page = (app: HTMLElement, params: URLSearchParams) => Cleanup;

const PAGES: Record<string, Page> = {
  home: renderHome,
  book: renderBook,
  journey: renderJourney,
  settings: renderSettings,
  closet: renderCloset,
  setup: renderSetup,
  time: renderTime,
  photos: renderPhotos,
  lounge: renderLounge,
  trophies: renderTrophies,
};

let app = document.getElementById("app")!;
const layer = document.getElementById("layer")!;
let cleanup: Cleanup;
let current = "home";
let signpost: Signpost;
const held = new Set<string>();

function go(route: string): void {
  const next = `#/${route}`;
  if (location.hash === next) show();
  else location.hash = next;
}

function show(): void {
  const [name, query] = location.hash.replace(/^#\/?/, "").split("?");
  const page = PAGES[name] ? name : "home";
  if (typeof cleanup === "function") cleanup();
  current = page;
  held.clear();
  // A fresh element per page: the previous page's listeners go with the old one.
  const fresh = app.cloneNode(false) as HTMLElement;
  app.replaceWith(fresh);
  app = fresh;
  app.className = `page page-${page}`;
  app.scrollTop = 0;
  cleanup = PAGES[page](app, new URLSearchParams(query ?? ""));
  const params = new URLSearchParams(query ?? "");
  if (page === "home" && params.has("post")) signpost.toggle(params.get("post") ?? "");
  if (page === "home" && params.has("hold")) hero()?.setMap(true);
  // MEOWPUUT_START=home?key=KeyM,KeyE: those keys held down (previews and screenshots).
  if (page === "home" && params.get("mouse")) void hero()?.mouse(params.get("mouse")!);
  if (page === "home" && params.get("key")) for (const code of params.get("key")!.split(",")) hero()?.press(code);
}

/** Re-renders the current page (after a change of skin, settings or the ledger). */
function refresh(): void {
  dress();
  show();
}

const hero = (): Hero | null => {
  const el = app.querySelector<HTMLElement>(".hero");
  return el ? mountHero(el) : null;
};

const modOf = (e: KeyboardEvent) => (store.info.platform === "mac" ? e.metaKey : e.ctrlKey);
const isMod = (code: string) => (store.info.platform === "mac" ? code.startsWith("Meta") : code.startsWith("Control"));

function onKeyDown(e: KeyboardEvent): void {
  if (current === "setup" || document.querySelector(".modal:not([hidden]), .explain:not([hidden])")) return;
  held.add(e.code);
  hero()?.press(e.code);
  if (isMod(e.code)) {
    hero()?.setMap(true);
    return;
  }
  if (modOf(e) && !e.altKey && !e.shiftKey) {
    const digit = /^Digit([0-7])$/.exec(e.code)?.[1];
    const routes = ["home", "settings", "closet", "journey", "book", "lounge", "trophies", "photos"];
    if (digit !== undefined) {
      e.preventDefault();
      signpost.close();
      go(routes[Number(digit)]);
    } else if (e.code === "KeyK") {
      e.preventDefault();
      hero()?.setMap(false);
      signpost.toggle();
    } else if (e.code === "KeyT") {
      e.preventDefault();
      go("home?at=try");
    } else if (e.code === "KeyW" && store.info.platform === "windows") {
      e.preventDefault();
      void getCurrentWindow().close();
    }
    return;
  }
  if (signpost.open) return;
  // A selection (截一段) or a photo being looked at takes the first Esc itself; the next one goes home.
  if (e.key === "Escape" && document.querySelector(".jview.picking, .lightbox")) return;
  if (e.key === "Escape" && !e.isComposing && e.keyCode !== 229 && current !== "home") {
    e.preventDefault();
    go("home");
  }
}

function onKeyUp(e: KeyboardEvent): void {
  held.delete(e.code);
  hero()?.release(e.code);
  if (isMod(e.code)) {
    // A Mac sends no key-up for keys released while ⌘ was down: let them all go with ⌘.
    hero()?.clear();
    held.clear();
  }
}

nav.go = go;
nav.refresh = refresh;

async function start(): Promise<void> {
  try {
    await loadAll();
  } catch (error) {
    app.innerHTML = `<div class="fatal">${String(error)}</div>`;
    return;
  }
  setScript(store.info.script);
  document.title = t("喵撲", "喵扑");
  document.body.classList.add(`os-${store.info.platform}`);
  dress();
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => refresh());
  signpost = new Signpost(layer, store.info.platform, go, (action) => {
    if (action === "quit") {
      void api.quitApp().catch(() => {});
      return;
    }
    const out = action === "desk-out";
    void api
      .deskSetOut(out)
      .then(async () => {
        store.settings = await api.settings().catch(() => store.settings);
        store.companion = await api.companion().catch(() => store.companion);
        refresh();
      })
      .catch(() => {});
  });
  addEventListener("hashchange", show);
  addEventListener("keydown", onKeyDown, true);
  addEventListener("keyup", onKeyUp, true);
  addEventListener("blur", () => {
    held.clear();
    hero()?.clear();
  });
  // The settlement on open finishes after the first paint; sharing can stop by itself (its time limit).
  void onBackend("steam-settled", async () => {
    const before = JSON.stringify([store.summary.characters, store.summary.epoch, store.summary.marks]);
    await reloadSteam();
    // Steam Cloud merges the other computers' ledgers in on open (after the first paint): show the merged totals.
    await reloadLedger();
    const merged = before !== JSON.stringify([store.summary.characters, store.summary.epoch, store.summary.marks]);
    if (current === "settings" || current === "trophies" || (merged && current !== "setup")) refresh();
    else showSteamBits(app);
  });
  // The photo manifest is fetched in the background (the page never waits); a new one is cached: the photo spots refill.
  void onBackend("photos-updated", () => dispatchEvent(new Event(PHOTOS_EVENT)));
  void api.photosRefresh().catch(() => {});
  void onBackend("share-changed", async () => {
    await reloadShare();
    if (current === "settings") refresh();
  });
  const first = store.info.start ?? (store.companion.firstRunDone ? null : "setup");
  if (first) go(first);
  else if (!location.hash) go("home");
  else show();
}

void start();
