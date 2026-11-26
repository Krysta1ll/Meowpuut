// The backend's commands (src-tauri/src/lib.rs). Outside Tauri (plain `npm run dev` in a browser, for working on
// the pages) a small stand-in answers with made-up numbers; it is never used inside the app.

import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { todayIndex } from "./dates";
import { mockLounge } from "./lounge_mock";
import type {
  AchievementCatalogItem,
  ExpeditionTier,
  FocusResponse,
  InteractResponse,
  LoungeView,
  PadCommitOutcome,
  PetForm,
  SettleLoungeResponse,
  SyncInspection,
} from "./lounge_types";
export type * from "./lounge_types";

export type Platform = "mac" | "windows";
export type SkinKey = "daily" | "classic" | "pixel" | "centenary" | "glyph";
export type TextSize = "small" | "standard" | "large";
export type TypingSeat = "split" | "glass";
export type ShareLimit = "hour" | "today" | "untilOff";

export interface AppInfo {
  platform: Platform;
  nativePlatform: Platform;
  script: "hant" | "hans";
  version: string;
  dataDir: string;
  dataDirOverridden: boolean;
  ledgerFile: string;
  activityFile: string;
  settingsLocation: string;
  companionFile: string;
  start: string | null;
}

export interface Mark {
  day: number;
  size: number;
  who: number;
  part: number;
  /** The place the cats had walked to that day (0–11, the order of the engine's twelve places); null when the ledger did not know it yet. */
  place: number | null;
}

export interface PhotoInfo {
  file: string;
  width: number;
  height: number;
  cats: string[];
  month: string | null;
  caption: string | null;
  /** The file is in the cache already. */
  cached: boolean;
}

export interface PhotosView {
  photos: PhotoInfo[];
  /** A manifest is cached: an empty list then means no photos, not that none came yet. */
  haveManifest: boolean;
}

export interface CatCounts {
  pets: number;
  falls: number;
  plays: number;
}

export interface Summary {
  characters: number;
  days: number;
  epoch: number;
  dailyDay: number;
  dailyCharacters: number;
  mimimo: CatCounts;
  momo: CatCounts;
  marks: Mark[];
  status: { kind: "missing" | "ok" | "corrupt" | "unknownVersion"; version?: number };
  file: string;
  fileBytes: number;
  stateBytes: number;
  headerHex: string;
}

export interface Settings {
  inputMode: string | null;
  simplified: boolean;
  mixJapanese: boolean;
  candidateTextSize: TextSize;
  /** Which candidate window the input method draws; follows the worn skin ("glyph" = 字景, else "glass"). */
  candidateSkin: "glass" | "glyph";
  palette: string | null;
  glassShape: string | null;
  deskPets: boolean;
  catsOn: boolean;
  typingSeat: TypingSeat;
  activityStats: boolean;
  activityPaused: boolean;
  activityHidden: string[];
}

export interface SettingsView {
  settings: Settings;
  location: string;
  candidateSkins: SkinKey[];
}

export interface Share {
  on: boolean;
  showApp: boolean;
  showTrack: boolean;
  limit: ShareLimit;
  /** With "hour": when it ends (ms since 1970). */
  endsAtMs: number;
  /** With "today": the local day it was turned on. */
  day: number;
}

export type Switch = "record" | "share" | "app" | "track" | "cloud";

export interface CompanionState {
  firstRunDone: boolean;
  skin: SkinKey;
  heroLayout: "pinyin" | "zhuyin";
  cloudOptIn: boolean;
  catsOut: boolean;
  achievementsOn: boolean;
  progress: { epoch: number; lastDay: number; lifetimeDays: number; maxDayGraphemes: number; unlocked: string[] };
  share: Share;
  explained: Switch[];
}

export interface CompanionPatch {
  firstRunDone?: boolean;
  skin?: SkinKey;
  heroLayout?: "pinyin" | "zhuyin";
  cloudOptIn?: boolean;
  catsOut?: boolean;
  achievementsOn?: boolean;
  share?: boolean;
  shareApp?: boolean;
  shareTrack?: boolean;
  shareLimit?: ShareLimit;
  explained?: Switch;
}

export interface Settlement {
  connected: boolean;
  localNew: string[];
  sent: string[];
  refused: string[];
  held: string[];
  stored: boolean;
}

export interface AchievementView {
  api: string;
  name: [string, string];
  desc: [string, string];
  steam: boolean;
}

/** What the cloud step of this open did (or why it did nothing); see `cloud.rs`. */
export type CloudState =
  | { kind: "off" | "trial" | "notBuilt" | "noSteam" | "steamCloudOff" | "failed" }
  | { kind: "synced"; others: number; skipped: number; wrote: boolean };

export interface SteamView {
  built: boolean;
  connected: boolean;
  error: string | null;
  achievementsOn: boolean;
  cloudOn: boolean;
  cloud: CloudState;
  /** This computer's cloud file name, while the switch is on. */
  cloudFile: string | null;
  trial: boolean;
  settlement: Settlement | null;
  unlocked: AchievementView[];
}

export interface ShareStatus {
  active: boolean;
  connected: boolean;
  built: boolean;
  appNames: boolean;
}

export interface PreviewLine {
  case: "active" | "fullscreen" | "idle";
  text: string;
}

export interface InstallOutcome {
  kind: "enabled" | "registered" | "needsLogout" | "bundleMissing" | "failed";
  detail: string;
}

export interface FestivalDay {
  day: number;
  id: string;
  name: [string, string];
  main: boolean;
}

export interface MobileSyncStatus {
  passCode: string | null;
  hasMirror: boolean;
  mirrorBytes: number;
  envelopeBytes: number;
  steamUnlocked: number;
  steamTotal: number;
  localUnlocked: number;
}

export interface MobileImportOutcome {
  characters: number;
  days: number;
  unlockedTotal: number;
  newlyUnlocked: number;
}

export interface ActivityDay {
  day: number;
  minutes: number[];
  total: number;
}

export interface ActivityView {
  week: ActivityDay[];
  weekTotal: number;
  month: number[];
  monthTotal: number;
  monthDays: number;
  parts: { days: number; byCategory: number[] }[];
  firstDay: number;
  lastDay: number;
  daysKept: number;
  keepDays: number;
  status: Summary["status"];
  file: string;
  fileBytes: number;
  stateBytes: number;
  epoch: number;
}

export interface ActivityPage {
  view: ActivityView;
  recording: boolean;
  paused: boolean;
  categories: string[];
}

export type Change =
  | { key: "inputMode"; value: string }
  | { key: "simplified"; value: boolean }
  | { key: "mixJapanese"; value: boolean }
  | { key: "candidateTextSize"; value: TextSize }
  | { key: "candidateSkin"; value: "glass" | "glyph" }
  | { key: "palette"; value: string }
  | { key: "glassShape"; value: string }
  | { key: "deskPets"; value: boolean }
  | { key: "typingSeat"; value: TypingSeat }
  | { key: "activityStats"; value: boolean }
  | { key: "activityPaused"; value: boolean }
  | { key: "activityHidden"; value: string[] };

const inTauri = "__TAURI_INTERNALS__" in window;

function call<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  return inTauri ? invoke<T>(command, args) : (mock(command, args) as Promise<T>);
}

export const api = {
  appInfo: () => call<AppInfo>("app_info"),
  ledger: () => call<Summary>("ledger_summary"),
  clearLedger: () => call<Summary>("ledger_clear"),
  revealLedger: () => call<void>("reveal_ledger"),
  settings: () => call<SettingsView>("settings_read"),
  writeSetting: (change: Change) => call<SettingsView>("settings_write", { change }),
  companion: () => call<CompanionState>("companion_read"),
  updateCompanion: (patch: CompanionPatch) => call<CompanionState>("companion_update", { patch }),
  steam: () => call<SteamView>("steam_status"),
  openInputSettings: () => call<void>("open_input_settings"),
  installInputSource: () => call<InstallOutcome>("install_input_source"),
  checkInputSource: () => call<InstallOutcome>("check_input_source"),
  deskSetOut: (out: boolean) => call<boolean>("desk_set_out", { out }),
  uninstallSystemComponents: () => call<string>("uninstall_system_components"),
  bookFestivals: (firstDay: number, lastDay: number) => call<FestivalDay[]>("book_festivals", { firstDay, lastDay }),
  mobileSyncStatus: () => call<MobileSyncStatus>("mobile_sync_status"),
  mobileSyncCreatePass: () => call<MobileSyncStatus>("mobile_sync_create_pass"),
  mobileSyncSetPass: (code: string) => call<MobileSyncStatus>("mobile_sync_set_pass", { code }),
  mobileSyncExport: () => call<string | null>("mobile_sync_export"),
  mobileSyncImport: () => call<MobileImportOutcome | null>("mobile_sync_import"),
  mobileSyncClear: () => call<MobileSyncStatus>("mobile_sync_clear"),
  loungeView: () => call<LoungeView>("lounge_view"),
  loungeSetForm: (form: PetForm) => call<LoungeView>("lounge_set_form", { form }),
  loungeInteract: (toyId: string, cat?: "mimimo" | "momo") => call<InteractResponse>("lounge_interact", { toyId, cat: cat ?? null }),
  loungeClaimStarter: (itemId: string) => call<LoungeView>("lounge_claim_starter", { itemId }),
  loungeBuyFurniture: (itemId: string) => call<LoungeView>("lounge_buy_furniture", { itemId }),
  loungeDispatchExpedition: (slot: number, placeId: string, tier: ExpeditionTier) =>
    call<SettleLoungeResponse>("lounge_dispatch_expedition", { slot, placeId, tier }),
  loungeEquipRoom: (slot: string, itemId: string | null) => call<LoungeView>("lounge_equip_room", { slot, itemId }),
  loungeEquipTitle: (titleId: string) => call<LoungeView>("lounge_equip_title", { titleId }),
  loungeFocusStart: () => call<number>("lounge_focus_start"),
  loungeCompleteFocus: () => call<FocusResponse>("lounge_complete_focus"),
  padCommit: (graphemes: number, intervalsMs: number[], uniqueChars: number) =>
    call<PadCommitOutcome>("pad_commit", { graphemes, intervalsMs, uniqueChars }),
  recordActiveMinute: (idleSeconds: number) => call<LoungeView>("record_active_minute", { idleSeconds }),
  inspectSyncEnvelope: () => call<SyncInspection>("inspect_sync_envelope"),
  achievementsCatalog: () => call<AchievementCatalogItem[]>("achievements_catalog"),
  quitApp: () => call<void>("quit_app"),
  activity: (weekStart: number, monthFirst: number, monthLast: number) => call<ActivityPage>("activity_view", { weekStart, monthFirst, monthLast }),
  clearActivity: () => call<void>("activity_clear"),
  pickApp: () => call<string | null>("pick_app"),
  shareStatus: () => call<ShareStatus>("share_status"),
  shareStop: () => call<void>("share_stop"),
  photosToday: (day: number) => call<PhotosView>("photos_today", { day }),
  photosRefresh: () => call<boolean>("photos_refresh"),
  presencePreview: (ask: { share: boolean; showApp: boolean; showTrack: boolean }) => call<PreviewLine[]>("presence_preview", { ask }),
};

/** Backend events (src-tauri/src/lib.rs, sharing.rs); nothing outside Tauri. */
export async function onBackend(event: "steam-settled" | "share-changed" | "photos-updated", run: () => void): Promise<void> {
  if (!inTauri) return;
  await listen(event, run);
}

// ---- the browser stand-in ------------------------------------------------------------------------------------------

const params = new URLSearchParams(location.search);
const fake = {
  settings: {
    inputMode: "pinyin", simplified: false, mixJapanese: false, candidateTextSize: "standard", candidateSkin: "glass", palette: "rose", glassShape: "capsule",
    deskPets: true, catsOn: true, typingSeat: "split", activityStats: params.has("activity"), activityPaused: false, activityHidden: ["com.apple.Safari"],
  } as Settings,
  companion: {
    firstRunDone: true, skin: "daily", heroLayout: "pinyin", cloudOptIn: false, catsOut: true, achievementsOn: true,
    progress: { epoch: 1, lastDay: 0, lifetimeDays: 0, maxDayGraphemes: 0, unlocked: ["CHARS_1", "LOCAL_DAWN"] },
    share: { on: false, showApp: false, showTrack: false, limit: "hour", endsAtMs: 0, day: 0 }, explained: [],
  } as CompanionState,
  cleared: false,
};

function sampleActivity(weekStart: number, monthFirst: number, monthLast: number): ActivityPage {
  const today = todayIndex();
  const day = (d: number): number[] => {
    if (d > today || d < today - 120 || !fake.settings.activityStats) return Array(10).fill(0);
    let seed = d * 2654435761;
    const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
    return [40, 120, 20, 60, 25, 45, 15, 30, 35, 10].map((m) => (rnd() < 0.55 ? Math.round(m * rnd() * 2) : 0));
  };
  const week = [...Array(7)].map((_, i) => {
    const minutes = day(weekStart + i);
    return { day: weekStart + i, minutes, total: minutes.reduce((a, b) => a + b, 0) };
  });
  const month = Array(10).fill(0);
  let monthDays = 0;
  const parts = [...Array(5)].map(() => ({ days: 0, byCategory: Array(10).fill(0) }));
  for (let d = monthFirst; d <= monthLast; d++) {
    const m = day(d);
    if (m.some((x) => x > 0)) monthDays++;
    m.forEach((x, c) => (month[c] += x));
    m.forEach((x, c) => {
      if (x > 0) {
        const p = (c + d) % 5;
        parts[p].byCategory[c]++;
      }
    });
    parts.forEach((p, i) => (p.days += m.some((x, c) => x > 0 && (c + d) % 5 === i) ? 1 : 0));
  }
  const total = (xs: number[]) => xs.reduce((a, b) => a + b, 0);
  return {
    view: {
      week, weekTotal: total(week.map((w) => w.total)), month, monthTotal: total(month), monthDays, parts, firstDay: today - 120, lastDay: today,
      daysKept: 121, keepDays: 0, status: { kind: "ok" }, file: "(browser preview)/Activity.bin", fileBytes: 12824, stateBytes: 12824, epoch: 0,
    },
    recording: fake.settings.activityStats, paused: fake.settings.activityPaused,
    categories: ["write", "code", "design", "web", "chat", "media", "game", "learn", "office", "other"],
  };
}

function sampleMarks(): Mark[] {
  const today = todayIndex();
  let seed = 7;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
  const out: Mark[] = [];
  for (let day = today - 399; day <= today; day++) {
    if (day !== today && rnd() > 0.58) continue;
    const r = rnd();
    out.push({ day, size: r < 0.25 ? 0 : r < 0.6 ? 1 : r < 0.85 ? 2 : r < 0.95 ? 3 : 4, who: [1, 2, 3, 3][Math.floor(rnd() * 4)], part: 1 + Math.floor(rnd() * 31), place: day < today - 330 ? null : Math.floor(rnd() * 12) });
  }
  return out;
}

async function mock(command: string, args?: Record<string, unknown>): Promise<unknown> {
  const platform: Platform = params.get("os") === "win" ? "windows" : "mac";
  switch (command) {
    case "app_info":
      return {
        platform, nativePlatform: platform, script: params.get("script") === "hans" ? "hans" : "hant", version: "0.1.0",
        dataDir: "(browser preview)", dataDirOverridden: true, ledgerFile: "(browser preview)/PetJournal.bin", activityFile: "(browser preview)/Activity.bin",
        settingsLocation: "(browser preview)", companionFile: "(browser preview)/Companion.json", start: null,
      } satisfies AppInfo;
    case "ledger_summary":
    case "ledger_clear": {
      if (command === "ledger_clear") fake.cleared = true;
      const marks = fake.cleared ? [] : sampleMarks();
      const last = marks[marks.length - 1];
      return {
        characters: fake.cleared ? 0 : 248316, days: marks.length, epoch: fake.cleared ? 2 : 1, dailyDay: last?.day ?? 0, dailyCharacters: last ? 812 : 0,
        mimimo: { pets: fake.cleared ? 0 : 141, falls: 6, plays: 12 }, momo: { pets: fake.cleared ? 0 : 203, falls: 4, plays: 9 },
        marks, status: { kind: "ok" }, file: "(browser preview)/PetJournal.bin", fileBytes: 2884, stateBytes: 2884,
        headerHex: "9c a7 01 00 01 00 00 00",
      } satisfies Summary;
    }
    case "settings_read":
    case "settings_write": {
      if (command === "settings_write") {
        const change = args!.change as Change;
        (fake.settings as unknown as Record<string, unknown>)[change.key] = change.value;
        if (change.key === "activityStats" && !change.value) fake.companion.share.on = false;
      }
      const settings = platform === "windows" ? { ...fake.settings, inputMode: null, palette: null, glassShape: null } : fake.settings;
      return { settings, location: "(browser preview)", candidateSkins: ["daily", "glyph"] } satisfies SettingsView;
    }
    case "companion_read":
      return fake.companion;
    case "companion_update": {
      const { share, shareApp, shareTrack, shareLimit, explained, ...rest } = args!.patch as CompanionPatch;
      Object.assign(fake.companion, rest);
      // As the companion does: the worn skin decides the candidate window.
      if (rest.skin) fake.settings.candidateSkin = rest.skin === "glyph" ? "glyph" : "glass";
      const sh = fake.companion.share;
      if (share !== undefined) sh.on = share;
      if (shareApp !== undefined) sh.showApp = shareApp;
      if (shareTrack !== undefined) sh.showTrack = shareTrack;
      if (shareLimit !== undefined) sh.limit = shareLimit;
      if (share) sh.endsAtMs = Date.now() + 3_600_000;
      if (explained && !fake.companion.explained.includes(explained)) fake.companion.explained.push(explained);
      return fake.companion;
    }
    case "steam_status":
      return {
        built: false, connected: false, error: "built without the steam feature", achievementsOn: fake.companion.achievementsOn, cloudOn: fake.companion.cloudOptIn,
        cloud: fake.companion.cloudOptIn ? (params.has("cloud") ? { kind: "synced", others: 1, skipped: 0, wrote: true } : { kind: "trial" }) : { kind: "off" },
        cloudFile: fake.companion.cloudOptIn ? "ledger-0123456789abcdef0123456789abcdef.bin" : null, trial: true,
        settlement: null, unlocked: [{ api: "LOCAL_DAWN", name: ["清晨的字・嘛啊早安", "清晨的字・嘛啊早安"], desc: ["清晨就打了字，莫莫剛睡醒喊哇啊，米米莫還在等水龍頭滴水。", "清晨就打了字，莫莫刚睡醒喊哇啊，米米莫还在等水龙头滴水。"], steam: false }],
      } satisfies SteamView;
    case "activity_view":
      return sampleActivity(args!.weekStart as number, args!.monthFirst as number, args!.monthLast as number);
    case "share_status":
      return { active: false, connected: false, built: false, appNames: true } satisfies ShareStatus;
    case "presence_preview": {
      const ask = args!.ask as { share: boolean; showApp: boolean; showTrack: boolean };
      if (!ask.share) return [{ case: "active", text: "" }, { case: "fullscreen", text: "" }, { case: "idle", text: "" }];
      const app = ask.showApp && platform === "mac" ? "（VS Code）" : "";
      const song = ask.showTrack ? (app ? "♪ 月光" : " ♪ 月光") : "";
      return [
        { case: "active", text: `喵撲・在寫程式${app}${song}` },
        { case: "fullscreen", text: "喵撲・專心中" },
        { case: "idle", text: `喵撲・陪著發呆${ask.showTrack ? " ♪ 月光" : ""}` },
      ] satisfies PreviewLine[];
    }
    case "photos_today":
      return { photos: [], haveManifest: false } satisfies PhotosView;
    case "photos_refresh":
      return false;
    case "pick_app":
      return platform === "mac" ? "com.apple.Notes" : "notepad.exe";
    case "install_input_source":
    case "check_input_source":
      return { kind: "enabled", detail: "瀏覽器預覽模式" } satisfies InstallOutcome;
    case "desk_set_out":
      fake.settings.deskPets = !!(args?.out);
      fake.companion.catsOut = fake.settings.deskPets;
      return fake.settings.deskPets;
    case "uninstall_system_components":
      return "瀏覽器預覽模式：未改動系統元件。";
    case "book_festivals":
      return [] satisfies FestivalDay[];
    case "mobile_sync_status":
    case "mobile_sync_create_pass":
    case "mobile_sync_set_pass":
    case "mobile_sync_clear":
      return {
        passCode: command === "mobile_sync_clear" ? null : "MEOW-9K3M-7P2Q-4X8V-2N6W-5R1T-8C4H-0J",
        hasMirror: false,
        mirrorBytes: 0,
        envelopeBytes: 3072,
        steamUnlocked: 1,
        steamTotal: 80,
        localUnlocked: 1,
      } satisfies MobileSyncStatus;
    case "mobile_sync_export":
      return "(browser preview)/meowpuut-sync.mpsync";
    case "mobile_sync_import":
      return { characters: 248316, days: 120, unlockedTotal: 12, newlyUnlocked: 3 } satisfies MobileImportOutcome;
    case "quit_app":
      return null;
    default:
      if (
        command.startsWith("lounge_") ||
        command === "achievements_catalog" ||
        command === "pad_commit" ||
        command === "record_active_minute" ||
        command === "inspect_sync_envelope"
      ) {
        return mockLounge(command, args);
      }
      return null;
  }
}
