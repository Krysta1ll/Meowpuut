// What the pages read: loaded once when the window opens and again after each change. Every number comes from the
// backend; the pages compute nothing that is stored anywhere.

import { api, type AppInfo, type CompanionState, type MobileSyncStatus, type SettingsView, type ShareStatus, type SteamView, type Summary } from "./api";
import { todayIndex } from "./dates";
import { invoke } from "@tauri-apps/api/core";
import { hasEngine } from "./glyph";
import { adoptGlyphTokens, applySkin } from "./skins";

export const store = {
  info: null as unknown as AppInfo,
  summary: null as unknown as Summary,
  settings: null as unknown as SettingsView,
  companion: null as unknown as CompanionState,
  steam: null as unknown as SteamView,
  mobileSync: null as MobileSyncStatus | null,
  mobileSyncNote: "" as string,
  shareStatus: null as ShareStatus | null,
  error: "" as string,
};

export async function loadAll(): Promise<void> {
  store.info = await api.appInfo();
  if (hasEngine) adoptGlyphTokens(await invoke<Parameters<typeof adoptGlyphTokens>[0]>("glyph_tokens").catch(() => []));
  const [summary, settings, companion, steam, mobileSync, shareStatus] = await Promise.all([
    api.ledger().catch((e) => fail(e, emptySummary())),
    api.settings().catch((e) => fail(e, null as unknown as SettingsView)),
    api.companion(),
    api.steam(),
    api.mobileSyncStatus().catch(() => null),
    api.shareStatus().catch(() => null),
  ]);
  Object.assign(store, { summary, settings, companion, steam, mobileSync, shareStatus });
}

export async function reloadSteam(): Promise<void> {
  const [steam, mobileSync] = await Promise.all([api.steam(), api.mobileSyncStatus().catch(() => null)]);
  Object.assign(store, { steam, mobileSync });
}

/** After a sharing switch changed (or the loop stopped by itself). */
export async function reloadShare(): Promise<void> {
  const [companion, shareStatus] = await Promise.all([api.companion(), api.shareStatus().catch(() => null)]);
  Object.assign(store, { companion, shareStatus });
}

export async function reloadLedger(): Promise<void> {
  store.summary = await api.ledger().catch((e) => fail(e, emptySummary()));
}

function fail<T>(error: unknown, fallback: T): T {
  store.error = String(error);
  return fallback;
}

function emptySummary(): Summary {
  return {
    characters: 0, days: 0, epoch: 0, dailyDay: 0, dailyCharacters: 0, mimimo: { pets: 0, falls: 0, plays: 0 }, momo: { pets: 0, falls: 0, plays: 0 },
    marks: [], status: { kind: "missing" }, file: "", fileBytes: 0, stateBytes: 2884, headerHex: "",
  };
}

/** Which legends the home keyboard wears: the Mac's input mode, or on Windows the companion's own choice. */
export function heroLayout(): "pinyin" | "zhuyin" {
  if (store.info.platform === "windows") return store.companion.heroLayout;
  return store.settings?.settings.inputMode === "pinyin" ? "pinyin" : "zhuyin";
}

export const today = (): number => todayIndex();

export function dress(): void {
  applySkin(document.body, store.companion.skin, store.settings?.settings.palette ?? null);
}
