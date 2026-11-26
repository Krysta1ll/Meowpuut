// 鍵盤設定 (FINAL.md §2.6): a plain form in the paper world. Input method, script, 中日混打 and the candidate size,
// written where the input method reads them (macOS settings.json, Windows HKCU\Software\Meowpuut); 桌面貓 (§4.2) and
// 時間花在哪裡與好友狀態 (§11.1), written where 喵撲貓窩 reads them; Steam (achievements on open, §6.2); and 隱私與帳本:
// what Meowpuut knows and does not know, the ledger itself, where it is, and clearing it.
//
// The first time each of the four 時間花在哪裡 switches is turned on, an explanation page says in plain words what it
// does, with a preview of exactly what Steam friends would see (the backend's presence builder makes the lines).

import { api, type Change, type CompanionPatch, type PreviewLine, type Switch, type TextSize, type TypingSeat } from "../api";
import { CAT } from "../art";
import { iso } from "../dates";
import { esc, fmt, t } from "../i18n";
import { nav } from "../nav";
import { reloadLedger, reloadShare, reloadSteam, store } from "../store";
import { BANDS, PARTS } from "./journey";

export const seg = (name: string, options: [string, string][], value: string, disabled = false) =>
  `<div class="seg" data-name="${name}">${options.map(([v, label]) => `<button data-v="${v}" class="${v === value ? "on" : ""}" ${disabled ? "disabled" : ""}>${label}</button>`).join("")}</div>`;

export const toggle = (name: string, on: boolean, disabled = false) =>
  `<button class="toggle${on ? " on" : ""}" data-name="${name}" role="switch" aria-checked="${on}" ${disabled ? "disabled" : ""}><i></i></button>`;

export function row(title: string, note: string, control: string, cls = ""): string {
  return `<div class="row ${cls}"><div class="row-t"><b>${title}</b>${note ? `<small>${note}</small>` : ""}</div><div class="row-c">${control}</div></div>`;
}

const hhmm = (ms: number) => {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
};

export function renderSettings(app: HTMLElement, params: URLSearchParams): void {
  const win = store.info.platform === "windows";
  const view = store.settings;
  const st = view?.settings;
  const s = store.summary;
  const share = store.companion.share;
  const status = store.shareStatus;
  const steam = store.steam;
  const record = !!st?.activityStats;

  const inputRow = win
    ? row(
        t("輸入方式", "输入方式"),
        t("Windows 的鍵盤清單裡有兩個：喵撲注音、喵撲拼音。按 ⊞＋空白鍵換。這裡只決定首頁那把鍵盤畫哪一種。", "Windows 的键盘列表里有两个：喵扑注音、喵扑拼音。按 ⊞＋空格键换。这里只决定首页那把键盘画哪一种。"),
        seg("heroLayout", [["zhuyin", t("注音（大千）", "注音（大千）")], ["pinyin", t("全鍵拼音", "全键拼音")]], store.companion.heroLayout),
      )
    : row(
        t("輸入方式", "输入方式"),
        st?.inputMode && !["zhuyin", "pinyin"].includes(st.inputMode) ? t(`現在是「${st.inputMode}」。`, `现在是「${st.inputMode}」。`) : t("桌面沒有九鍵：注音用大千排列。", "桌面没有九键：注音用大千排列。"),
        seg("inputMode", [["zhuyin", t("注音（大千）", "注音（大千）")], ["pinyin", t("全鍵拼音", "全键拼音")]], st?.inputMode ?? ""),
      );

  // ---- 桌面貓 ----
  const deskNote = [
    t("米米莫和莫莫在桌面上（喵撲貓窩）。關掉，牠們回家，貓窩不畫任何東西。", "米米莫和莫莫在桌面上（喵扑猫窝）。关掉，它们回家，猫窝不画任何东西。"),
    win ? t("Windows 上關掉時，貓窩不啟動。", "Windows 上关掉时，猫窝不启动。") : "",
    !win && st && !st.catsOn ? t("輸入法選單裡「米米莫與莫莫」關著，桌面上也不會有貓。", "输入法菜单里「米米莫与莫莫」关着，桌面上也不会有猫。") : "",
  ].filter(Boolean).join("");
  const seatNote =
    st?.typingSeat === "glass"
      ? t("兩隻都坐在候選玻璃上，停手後才出門。", "两只都坐在候选玻璃上，停手后才出门。")
      : t("莫莫坐在候選玻璃上，米米莫沿著你正在寫的視窗上緣陪你走。", "莫莫坐在候选玻璃上，米米莫沿着你正在写的窗口上缘陪你走。");

  // ---- 時間花在哪裡與好友狀態 ----
  const shareState = (() => {
    if (!share.on) return "";
    if (status?.active) {
      const until =
        share.limit === "hour" ? t(`到 ${hhmm(share.endsAtMs)}`, `到 ${hhmm(share.endsAtMs)}`) : share.limit === "today" ? t("到今天結束", "到今天结束") : t("直到你關掉", "直到你关掉");
      return `<span class="state on">${t("分享中", "分享中")}・${until}</span>`;
    }
    if (!record) return `<span class="state">${t("要先打開記錄", "要先打开记录")}</span>`;
    if (!status?.built) return `<span class="state">${t("這個版本沒有接 Steam", "这个版本没有接 Steam")}</span>`;
    return `<span class="state">${t("Steam 沒有開著，連上後才分享", "Steam 没有开着，连上后才分享")}</span>`;
  })();
  const trackNote = win
    ? t("從 Windows 的媒體控制讀正在播放的歌名。", "从 Windows 的媒体控制读正在播放的歌名。")
    : t("只支援「音樂」與 Spotify；第一次 macOS 會問你要不要讓喵撲讀它們。網易雲、QQ 音樂暫不支援。", "只支持「音乐」与 Spotify；第一次 macOS 会问你要不要让喵扑读它们。网易云、QQ 音乐暂不支持。");
  const appNote = t("例如「喵撲・在寫程式（VS Code）」。", "例如「喵扑・在写代码（VS Code）」。");
  const hidden = st?.activityHidden ?? [];
  const hiddenBlock = `
    <div class="hidden-apps">
      <div class="row-t"><b>${t("不記錄的 App", "不记录的 App")}</b>
        <small>${t("這些 App 不算時間，好友也看不到它們的名字（只看到「陪著發呆」）。", "这些 App 不算时间，好友也看不到它们的名字（只看到「陪着发呆」）。")}
        ${t("喵撲不記得你用過哪些 App，所以這裡沒有「最近用過」：", "喵扑不记得你用过哪些 App，所以这里没有「最近用过」：")}${
          win ? t("打上執行檔名稱（例如 chrome.exe），或挑一個。", "打上可执行文件名（例如 chrome.exe），或挑一个。") : t("打上 bundle id（例如 com.apple.Safari），或挑一個 App。", "打上 bundle id（例如 com.apple.Safari），或挑一个 App。")
        }</small></div>
      <div class="chips">${hidden.length ? hidden.map((id) => `<span class="app-chip">${esc(id)}<button data-unhide="${esc(id)}" aria-label="${t("移除", "移除")}">×</button></span>`).join("") : `<span class="none">${t("（沒有）", "（没有）")}</span>`}</div>
      <div class="addline">
        <input type="text" class="hide-id" spellcheck="false" autocomplete="off" placeholder="${win ? "chrome.exe" : "com.apple.Safari"}" aria-label="${t("App 識別碼", "App 标识符")}">
        <button class="btn" data-act="hide-add">${t("加入", "加入")}</button>
        <button class="btn" data-act="hide-pick">${win ? t("挑一個執行檔…", "挑一个可执行文件…") : t("挑一個 App…", "挑一个 App…")}</button>
      </div>
    </div>`;

  // ---- Steam ----
  const settlement = steam?.settlement;
  const steamLine = (() => {
    if (!steam) return "";
    if (steam.trial) return t("試用資料夾：成就不會送到 Steam。", "试用文件夹：成就不会送到 Steam。");
    if (!steam.built) return t("這個版本沒有接 Steam：做到的先記在這台電腦。", "这个版本没有接 Steam：做到的先记在这台电脑。");
    if (!steam.connected) return t("Steam 沒有開著：做到的會在下次連上時補上。", "Steam 没有开着：做到的会在下次连上时补上。");
    const parts: string[] = [];
    if (settlement?.sent.length) parts.push(t(`這次打開補上了 ${settlement.sent.length} 個。`, `这次打开补上了 ${settlement.sent.length} 个。`));
    if (settlement?.refused.length) parts.push(t(`Steam 後台還沒有其中 ${settlement.refused.length} 個，之後再補。`, `Steam 后台还没有其中 ${settlement.refused.length} 个，之后再补。`));
    return parts.join("") || t("Steam 已連上。", "Steam 已连上。");
  })();
  // 雲端: the state next to the switch. Without a Steam client the choice is kept and nothing is sent.
  const cloudState = (() => {
    if (!store.companion.cloudOptIn || !steam) return "";
    const c = steam.cloud;
    const note = (text: string, on = false) => `<span class="state${on ? " on" : ""}">${text}</span>`;
    switch (c.kind) {
      case "trial": return note(t("試用資料夾：不會上傳", "试用文件夹：不会上传"));
      case "notBuilt": return note(t("這個版本沒有接 Steam", "这个版本没有接 Steam"));
      case "noSteam": return note(t("Steam 沒有開著", "Steam 没有开着"));
      case "steamCloudOff": return note(t("Steam 裡的雲端關著", "Steam 里的云端关着"));
      case "failed": return note(t("這次沒寫成，下次打開再試", "这次没写成，下次打开再试"));
      case "synced": return note(c.others ? t(`已同步・另外 ${c.others} 台電腦`, `已同步・另外 ${c.others} 台电脑`) : t("已同步・目前只有這台", "已同步・目前只有这台"), true);
      default: return "";
    }
  })();
  const localOnly = (steam?.unlocked ?? []).filter((a) => !a.steam || !steam!.achievementsOn);
  const ms = store.mobileSync;
  const achSummary = ms
    ? t(`已解鎖 ${ms.steamUnlocked} / ${ms.steamTotal} 個 Steam 成就（另加 ${ms.localUnlocked} 個只留在本機）。`, `已解锁 ${ms.steamUnlocked} / ${ms.steamTotal} 个 Steam 成就（另加 ${ms.localUnlocked} 个只留在本机）。`)
    : "";
  const passCodeText = ms?.passCode ?? "";

  const known: [string, string][] = [
    ["今天打了幾個字（只有數字）", "今天打了几个字（只有数字）"],
    ["一共打了幾個字（只有數字）", "一共打了几个字（只有数字）"],
    ["哪幾天打過字、那天是哪一種山", "哪几天打过字、那天是哪一种山"],
    ["清晨、白天、傍晚、晚上、深夜", "清晨、白天、傍晚、晚上、深夜"],
    ["那天米米莫、莫莫誰在", "那天米米莫、莫莫谁在"],
    ["摸了米米莫和莫莫幾下", "摸了米米莫和莫莫几下"],
    ...(store.companion.cloudOptIn ? ([["這台電腦的帳本複本，放在你的 Steam 雲端（你打開了雲端；只有數字）", "这台电脑的账本副本，放在你的 Steam 云端（你打开了云端；只有数字）"]] as [string, string][]) : []),
    ...(record ? ([["單日各類 App 用了幾分鐘（你打開了記錄）", "单日各类 App 用了几分钟（你打开了记录）"]] as [string, string][]) : []),
  ];
  const unknown: [string, string][] = [
    ["你打了什麼字", "你打了什么字"],
    [record ? "你在哪個 App 裡打（只知道是哪一類）" : "你在哪個 App 裡打", record ? "你在哪个 App 里打（只知道是哪一类）" : "你在哪个 App 里打"],
    ["幾點幾分", "几点几分"],
    ["你是誰（沒有帳號）", "你是谁（没有账号）"],
    ["任何統計或分析", "任何统计或分析"],
  ];
  const recent = s.marks.slice(-6).reverse();
  const partsOf = (bits: number) => PARTS.filter((_, i) => bits & (1 << i)).map((p) => t(...p)).join(" ");
  const whoOf = (bits: number) => [bits & 1 ? t("米米莫", "米米莫") : "", bits & 2 ? t("莫莫", "莫莫") : ""].filter(Boolean).join(" ");
  const fileStatus = { missing: t("還沒有檔案", "还没有文件"), ok: t("完好", "完好"), corrupt: t("損壞（當作空白）", "损坏（当作空白）"), unknownVersion: t("較新的版本（當作空白）", "较新的版本（当作空白）") }[s.status.kind];
  const ledgerText = [
    `characters ${s.characters}    days ${s.days}    epoch ${s.epoch}`,
    `today      ${s.dailyDay ? iso(s.dailyDay) : "-"}  ${s.dailyCharacters}`,
    `mimimo     pets ${s.mimimo.pets}  falls ${s.mimimo.falls}  plays ${s.mimimo.plays}`,
    `momo       pets ${s.momo.pets}  falls ${s.momo.falls}  plays ${s.momo.plays}`,
    ...recent.map((mk) => `day ${iso(mk.day)}  band ${mk.size} ${t(...BANDS[mk.size].name)}  parts ${partsOf(mk.part)}  who ${whoOf(mk.who)}`),
    s.days > recent.length ? `… ${s.days - recent.length} ${t("天", "天")}` : "",
  ].filter(Boolean).join("\n");

  app.innerHTML = `
    <header class="page-hd">
      <h1>${t("設定與關於", "设置与关于")}</h1>
      <span class="mut">${t("Steam 版為獨立純桌面陪伴遊戲，零管理員權限、零全域鍵盤監聽。", "Steam 版为独立纯桌面陪伴游戏，零管理员权限、零全局键盘监听。")}</span>
    </header>
    ${store.error ? `<p class="warn">${esc(store.error)}</p>` : ""}
    <section class="form" id="addon-info">
      <h2>${t("站外桌面配件說明（選配）", "站外桌面配件说明（选配）")}</h2>
      <p class="note">${t("Steam 版喵撲本身 100% 自足，不需安裝任何系統輸入法即可體驗全部玩法與全成就。若你另外使用開源免費的桌面輸入法配件（macOS Sill / Windows Porch），它會與本遊戲共用同一份本機帳本（PetJournal.bin），自動讓米米莫和莫莫聽懂你的日常中文字數。", "Steam 版喵扑本身 100% 自足，不需安装任何系统输入法即可体验全部玩法与全成就。若你另外使用开源免费的桌面输入法配件（macOS Sill / Windows Porch），它会与本游戏共用同一份本机账本（PetJournal.bin），自动让米米莫和莫莫听懂你的日常中文字数。")}</p>
      ${inputRow}
      <p class="store-at mut">${t("本機偏好存在", "本机偏好存在")} <code>${esc(view?.location ?? "")}</code></p>
    </section>
    <section class="form" id="desk">
      <h2>${t("桌面貓", "桌面猫")}</h2>
      ${row(t("桌面貓", "桌面猫"), deskNote, toggle("deskPets", !!st?.deskPets, !view))}
      ${row(t("打字時", "打字时"), seatNote, seg("typingSeat", [["split", t("陪走", "陪走")], ["glass", t("都在玻璃上", "都在玻璃上")]], st?.typingSeat ?? "split", !view || !st?.deskPets), st?.deskPets ? "" : "off")}
    </section>
    <section class="form" id="activity">
      <h2>${t("時間花在哪裡與好友狀態", "时间花在哪里与好友状态")}</h2>
      <p class="note">${t("Steam 版才有。下面的開關預設都關著；打開以前都會先說清楚。", "Steam 版才有。下面的开关默认都关着；打开以前都会先说清楚。")}</p>
      ${row(
        t("記錄時間花在哪裡", "记录时间花在哪里"),
        t("喵撲貓窩在這台電腦上統計單日各類 App 用了幾分鐘。只存類別和分鐘，不存 App 名稱、標題或網址。", "喵扑猫窝在这台电脑上统计单日各类 App 用了几分钟。只存类别和分钟，不存 App 名称、标题或网址。") +
          (win && st && !st.deskPets ? t("桌面貓關著時貓窩不啟動，也就不記錄。", "桌面猫关着时猫窝不启动，也就不记录。") : ""),
        `${record ? `<button class="linkish" data-go="time">${t("看紀錄 ›", "看记录 ›")}</button>` : ""}${toggle("record", record, !view)}`,
      )}
      ${row(t("暫停記錄", "暂停记录"), t("暫停時不算時間；好友看到「陪著發呆」。", "暂停时不算时间；好友看到「陪着发呆」。"), toggle("paused", !!st?.activityPaused, !record), `sub${record ? "" : " off"}`)}
      ${hiddenBlock}
      ${row(
        t("讓 Steam 好友看到我在做什麼", "让 Steam 好友看到我在做什么"),
        t("好友清單裡你的名字下面多一行，例如「喵撲・在寫程式」；Steam 也會顯示你在玩「喵撲」。要先打開記錄。", "好友列表里你的名字下面多一行，例如「喵扑・在写代码」；Steam 也会显示你在玩「喵扑」。要先打开记录。"),
        `${shareState}${toggle("share", share.on, !record)}`,
        record ? "" : "off",
      )}
      ${row(t("顯示 App 名稱", "显示 App 名称"), appNote, toggle("app", share.showApp && !!status?.appNames, !share.on || !status?.appNames), `sub${share.on && status?.appNames ? "" : " off"}`)}
      ${row(t("顯示正在聽的歌", "显示正在听的歌"), trackNote, toggle("track", share.showTrack, !share.on), `sub${share.on ? "" : " off"}`)}
      ${row(
        t("分享多久", "分享多久"),
        win
          ? t("時間到就停，好友那一行也清掉。關掉視窗後，通知區域會留一個小圖示，可以停止分享。", "时间到就停，好友那一行也清掉。关掉窗口后，通知区域会留一个小图标，可以停止分享。")
          : t("時間到就停，好友那一行也清掉。關掉視窗後，選單列會留一個小圖示，可以停止分享。", "时间到就停，好友那一行也清掉。关掉窗口后，菜单栏会留一个小图标，可以停止分享。"),
        seg("shareLimit", [["hour", t("1 小時", "1 小时")], ["today", t("今天", "今天")], ["untilOff", t("直到我關掉", "直到我关掉")]], share.limit, !record),
        `sub${record ? "" : " off"}`,
      )}
      ${row(
        t("清除時間紀錄", "清除时间记录"),
        t("每一天、每一類的分鐘數都會清空，無法復原。", "每一天、每一类的分钟数都会清空，无法恢复。"),
        `<button class="btn danger" data-act="clear-time">${t("清除時間紀錄", "清除时间记录")}</button>`,
      )}
      <p class="store-at mut">${t("開關存在", "开关存在")} <code>${esc(view?.location ?? "")}</code>${t("（記錄、暫停、不記錄的 App）與", "（记录、暂停、不记录的 App）与")} <code>Companion.json</code>${t("（分享）；紀錄在", "（分享）；记录在")} <code>${esc(store.info.activityFile)}</code></p>
    </section>
    <section class="form" id="steam">
      <h2>Steam</h2>
      ${row(t("成就", "成就"), t("在你打開喵撲時一次補上；關掉就只留在這台電腦。", "在你打开喵扑时一次补上；关掉就只留在这台电脑。"), toggle("ach", !!steam?.achievementsOn))}
      <p class="later mut">${esc([achSummary, steamLine].filter(Boolean).join(" "))}</p>
      ${
        localOnly.length
          ? `<h3 class="mut small" style="margin:10px 0 0;font-weight:400">${t("只在這台電腦上的成就", "只在这台电脑上的成就")}</h3><ul class="ach-list">${localOnly.map((a) => `<li><b>${esc(t(...a.name))}</b><span class="mut">${esc(t(...a.desc))}</span></li>`).join("")}</ul>`
          : ""
      }
      ${row(
        t("雲端", "云端"),
        t("預設關。開了，這台電腦的字數與爪印（只有數字，一個 2.8 KB 的檔案）放進你的 Steam 雲端，另一台電腦打開喵撲時合起來看；關掉就刪掉。選詞記憶、時間花在哪裡、你打的字，永遠不上傳。", "默认关。开了，这台电脑的字数与爪印（只有数字，一个 2.8 KB 的文件）放进你的 Steam 云端，另一台电脑打开喵扑时合起来看；关掉就删掉。选词记忆、时间花在哪里、你打的字，永远不上传。"),
        `${cloudState}${toggle("cloudOptIn", store.companion.cloudOptIn)}`,
      )}
      ${store.companion.cloudOptIn && steam?.cloudFile ? `<p class="later mut">${t("這台電腦在雲端的檔案：", "这台电脑在云端的文件：")}<code>${esc(steam.cloudFile)}</code></p>` : ""}
    </section>
    <section class="form" id="mobile-sync">
      <h2>${t("跨平台同步（手機 ↔ 電腦）", "跨平台同步（手机 ↔ 电脑）")}</h2>
      <p class="note">${t("沒有帳號、沒有信箱、不需要登入。只同步一個固定 3 KB 的數字與成就快照（.mpsync），選詞記憶與打過的字永遠留在本機。", "没有账号、没有邮箱、不需要登录。只同步一个固定 3 KB 的数字与成就快照（.mpsync），选词记忆与打过的字永远留在本机。")}</p>
      ${row(
        t("匿名通行碼", "匿名通行码"),
        passCodeText
          ? t("手機與電腦輸入同一組 MEOW 通行碼即可配對同步；所有數字會自動取兩邊加總／聯集，不會覆蓋遺失。", "手机与电脑输入同一组 MEOW 通行码即可配对同步；所有数字会自动取两边汇总／并集，不会覆盖丢失。")
          : t("尚未建立通行碼。可以先建立一組，或直接貼上手機端的 MEOW 通行碼。", "尚未建立通行码。可以先建立一组，或直接贴上手机端的 MEOW 通行码。"),
        `<div class="btns">
          <button class="btn" data-act="ms-create">${passCodeText ? t("換一組", "换一组") : t("建立通行碼", "建立通行码")}</button>
          ${passCodeText ? `<button class="btn" data-act="ms-copy">${t("複製", "复制")}</button>` : ""}
          ${passCodeText || ms?.hasMirror ? `<button class="btn" data-act="ms-clear">${t("清除配對", "清除配对")}</button>` : ""}
        </div>`,
      )}
      <div class="addline" style="margin-top:8px">
        <input type="text" class="ms-code-input" spellcheck="false" autocomplete="off" value="${esc(passCodeText)}" placeholder="MEOW-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX" aria-label="${t("匿名通行碼", "匿名通行码")}">
        <button class="btn" data-act="ms-apply">${t("套用通行碼", "套用通行码")}</button>
      </div>
      ${row(
        t("同步快照（.mpsync）", "同步快照（.mpsync）"),
        ms?.hasMirror
          ? t(`已合併過手機快照（MobileSync.bin · ${fmt(ms.mirrorBytes)} bytes），手機拿到的成就也會在下次連上 Steam 時一起補上。`, `已合并过手机快照（MobileSync.bin · ${fmt(ms.mirrorBytes)} bytes），手机拿到的成就也会在下次连上 Steam 时一起补上。`)
          : t("匯出這台電腦的 3 KB 快照給 iOS / Android，或匯入手機快照合併字數與成就。", "导出这台电脑的 3 KB 快照给 iOS / Android，或导入手机快照合并字数与成就。"),
        `<div class="btns">
          <button class="btn" data-act="ms-export">${t("匯出 .mpsync", "导出 .mpsync")}</button>
          <button class="btn primary" data-act="ms-import">${t("匯入手機 .mpsync", "导入手机 .mpsync")}</button>
        </div>`,
      )}
      ${store.mobileSyncNote ? `<p class="later mut">${esc(store.mobileSyncNote)}</p>` : ""}
    </section>
    <section class="form privacy" id="privacy">
      <h2>${t("隱私與帳本", "隐私与账本")}</h2>
      <div class="knows">
        <div><h3>${t("喵撲知道的", "喵扑知道的")}</h3><ul>${known.map((k) => `<li>${t(...k)}</li>`).join("")}</ul></div>
        <div><h3>${t("喵撲不知道的", "喵扑不知道的")}</h3><ul>${unknown.map((k) => `<li>${t(...k)}</li>`).join("")}</ul></div>
        <div class="ledger">
          <h3>${t("帳本", "账本")} <span class="mut">${t("這就是全部。", "这就是全部。")}</span></h3>
          <pre>${esc(ledgerText)}</pre>
          <p class="mut small">PetJournal.bin · ${fmt(s.fileBytes)} / ${fmt(s.stateBytes)} bytes · ${fileStatus}</p>
          <p class="mut small mono">${esc(s.headerHex)}</p>
          <div class="btns">
            <button class="btn primary" data-act="inspect-sync">${t("查看我的 3 KB 快照", "查看我的 3 KB 快照")}</button>
            <button class="btn" data-act="reveal">${t("打開檔案位置", "打开文件位置")}</button>
            <button class="btn danger" data-act="clear">${t("清除貓咪紀錄", "清除猫咪记录")}</button>
            <button class="btn" data-act="quit">${t("離開喵撲", "退出喵扑")}</button>
          </div>
          <p class="mut small">${t("同一個資料夾裡還有：", "同一个文件夹里还有：")}${win ? "" : "settings.json、"}Companion.json${t("（陪伴 App 自己的幾個選擇）", "（陪伴 App 自己的几个选择）")}${t("、DeskCounters.json（桌面貓事件計數），打開記錄後還有 Activity.bin（時間花在哪裡）", "、DeskCounters.json（桌面猫事件计数），打开记录后还有 Activity.bin（时间花在哪里）")}</p>
        </div>
      </div>
    </section>
    <div class="modal" hidden role="alertdialog" aria-modal="true">
      <div class="modal-card">
        <h2></h2>
        <p></p>
        <div class="btns"><button class="btn" data-act="cancel">${t("取消", "取消")}</button><button class="btn danger" data-act="confirm">${t("清除", "清除")}</button></div>
      </div>
    </div>
    <div class="modal snap-modal" hidden role="dialog" aria-modal="true" data-snap-modal>
      <div class="modal-card snap-card">
        <h2>${t("我的 3 KB 同步快照（明文欄位解讀）", "我的 3 KB 同步快照（明文字段解读）")}</h2>
        <div data-snap-body></div>
        <div class="btns"><button class="btn primary" data-act="snap-close">${t("關閉", "关闭")}</button></div>
      </div>
    </div>
    ${EXPLAIN_SHEET}`;

  store.error = "";
  const refresh = async () => {
    await Promise.all([reloadShare(), reloadSteam()]).catch(() => {});
    nav.refresh();
  };
  const write = async (change: Change) => {
    try {
      store.settings = await api.writeSetting(change);
      if (change.key === "activityStats" && !change.value) store.companion = await api.companion();
    } catch (error) {
      store.error = String(error);
    }
    await refresh();
  };
  const patch = async (p: CompanionPatch) => {
    try {
      store.companion = await api.updateCompanion(p);
    } catch (error) {
      store.error = String(error);
    }
    await refresh();
  };
  /** Turns a 時間花在哪裡 switch on, after its explanation the first time. */
  const turnOn = async (which: Switch, apply: () => Promise<void>) => {
    if (!store.companion.explained.includes(which)) {
      const ok = await explain(app, which);
      if (!ok) return;
      store.companion = await api.updateCompanion({ explained: which });
    }
    await apply();
  };

  for (const group of app.querySelectorAll<HTMLElement>(".seg[data-name]"))
    group.addEventListener("click", async (e) => {
      const b = (e.target as HTMLElement).closest<HTMLButtonElement>("button[data-v]");
      if (!b || b.disabled) return;
      const v = b.dataset.v!;
      const name = group.dataset.name!;
      if (name === "heroLayout") {
        store.companion = await api.updateCompanion({ heroLayout: v as "pinyin" | "zhuyin" });
        nav.refresh();
      } else if (name === "inputMode") await write({ key: "inputMode", value: v });
      else if (name === "simplified") await write({ key: "simplified", value: v === "1" });
      else if (name === "candidateTextSize") await write({ key: "candidateTextSize", value: v as TextSize });
      else if (name === "typingSeat") await write({ key: "typingSeat", value: v as TypingSeat });
      else if (name === "shareLimit") await patch({ shareLimit: v as "hour" | "today" | "untilOff" });
    });

  const onToggle = (name: string, run: (on: boolean) => Promise<void>) =>
    app.querySelector<HTMLButtonElement>(`.toggle[data-name="${name}"]`)?.addEventListener("click", (e) => {
      const b = e.currentTarget as HTMLButtonElement;
      if (!b.disabled) void run(!b.classList.contains("on"));
    });
  onToggle("mixJapanese", (on) => write({ key: "mixJapanese", value: on }));
  onToggle("deskPets", async (on) => {
    try {
      await api.deskSetOut(on);
      store.settings = await api.settings();
      store.companion = await api.companion();
    } catch (error) {
      store.error = String(error);
    }
    await refresh();
  });
  onToggle("record", (on) => (on ? turnOn("record", () => write({ key: "activityStats", value: true })) : write({ key: "activityStats", value: false })));
  onToggle("paused", (on) => write({ key: "activityPaused", value: on }));
  onToggle("share", (on) => (on ? turnOn("share", () => patch({ share: true })) : patch({ share: false })));
  onToggle("app", (on) => (on ? turnOn("app", () => patch({ shareApp: true })) : patch({ shareApp: false })));
  onToggle("track", (on) => (on ? turnOn("track", () => patch({ shareTrack: true })) : patch({ shareTrack: false })));
  onToggle("ach", (on) => patch({ achievementsOn: on }));
  onToggle("cloudOptIn", (on) => (on ? turnOn("cloud", () => patch({ cloudOptIn: true })) : patch({ cloudOptIn: false })));

  // 不記錄的 App
  const input = app.querySelector<HTMLInputElement>(".hide-id")!;
  const addHidden = async (id: string) => {
    let clean = id.trim();
    if (!clean) return;
    if (win && !clean.includes(".")) clean += ".exe";
    if (!win && !clean.includes(".")) {
      store.error = t(`「${clean}」看起來不像 bundle id（例如 com.apple.Safari）。也可以按「挑一個 App…」。`, `「${clean}」看起来不像 bundle id（例如 com.apple.Safari）。也可以按「挑一个 App…」。`);
      nav.refresh();
      return;
    }
    await write({ key: "activityHidden", value: [...hidden, clean] });
  };
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.isComposing) void addHidden(input.value);
  });

  const modal = app.querySelector<HTMLElement>(".modal")!;
  let confirmAct = "";
  const ask = (act: string, title: string, body: string) => {
    confirmAct = act;
    modal.querySelector("h2")!.textContent = title;
    modal.querySelector("p")!.textContent = body;
    modal.hidden = false;
    modal.querySelector<HTMLButtonElement>('[data-act="cancel"]')?.focus();
  };
  app.addEventListener("click", async (e) => {
    const target = e.target as HTMLElement;
    const go = target.closest<HTMLElement>("[data-go]")?.dataset.go;
    if (go) nav.go(go);
    const unhide = target.closest<HTMLElement>("[data-unhide]")?.dataset.unhide;
    if (unhide !== undefined) await write({ key: "activityHidden", value: hidden.filter((h) => h !== unhide) });
    const act = target.closest<HTMLElement>("[data-act]")?.dataset.act;
    if (act === "reveal") void api.revealLedger();
    if (act === "inspect-sync") {
      const snapModal = app.querySelector<HTMLElement>("[data-snap-modal]");
      const snapBody = app.querySelector<HTMLElement>("[data-snap-body]");
      if (snapModal && snapBody) {
        try {
          const info = await api.inspectSyncEnvelope();
          snapBody.innerHTML = `
            <p class="mut small">${esc(t(...info.privacyNote))}</p>
            <ul class="snap-list">
              ${info.fields
                .map(
                  (f) => `<li><span>${esc(t(...f.label))}</span><b>${esc(f.value)}</b><small>${f.bytes} B</small></li>`,
                )
                .join("")}
            </ul>
            <p class="mut small mono">${esc(info.headerHex)}</p>`;
          snapModal.hidden = false;
        } catch (error) {
          store.error = String(error);
          nav.refresh();
        }
      }
    }
    if (act === "snap-close") {
      const snapModal = app.querySelector<HTMLElement>("[data-snap-modal]");
      if (snapModal) snapModal.hidden = true;
    }
    if (act === "hide-add") await addHidden(input.value);
    if (act === "hide-pick") {
      try {
        const id = await api.pickApp();
        if (id) await addHidden(id);
      } catch (error) {
        store.error = t(`讀不到這個 App 的識別碼：${error}`, `读不到这个 App 的标识符：${error}`);
        nav.refresh();
      }
    }
    if (act === "clear")
      ask(
        "clear",
        t("清除貓咪紀錄？", "清除猫咪记录？"),
        t("字數、爪印本和好感度會清空，時間花在哪裡的紀錄也一起清空，無法復原。正在打字的程式下次寫入時也會跟著清空。選詞記憶不受影響。", "字数、爪印本和好感度会清空，时间花在哪里的记录也一起清空，无法恢复。正在打字的程序下次写入时也会跟着清空。选词记忆不受影响。"),
      );
    if (act === "clear-time")
      ask("clear-time", t("清除時間紀錄？", "清除时间记录？"), t("每一天、每一類的分鐘數都會清空，無法復原。字數與貓咪紀錄不受影響。", "每一天、每一类的分钟数都会清空，无法恢复。字数与猫咪记录不受影响。"));
    if (act === "quit") void api.quitApp().catch(() => {});
    if (act === "ms-create") {
      try {
        store.mobileSync = await api.mobileSyncCreatePass();
        store.mobileSyncNote = t("已建立新的匿名通行碼，拿去手機端貼上即可。", "已建立新的匿名通行码，拿去手机端贴上即可。");
      } catch (error) {
        store.error = String(error);
      }
      nav.refresh();
    }
    if (act === "ms-copy" && passCodeText) {
      try {
        await navigator.clipboard.writeText(passCodeText);
        store.mobileSyncNote = t("已複製通行碼。", "已复制通行码。");
      } catch {
        store.mobileSyncNote = t("請直接從下方輸入框全選複製。", "请直接从下方输入框全选复制。");
      }
      nav.refresh();
    }
    if (act === "ms-apply") {
      const codeInput = app.querySelector<HTMLInputElement>(".ms-code-input")?.value ?? "";
      try {
        store.mobileSync = await api.mobileSyncSetPass(codeInput);
        store.mobileSyncNote = store.mobileSync.passCode ? t("已套用匿名通行碼。", "已套用匿名通行码。") : t("已清空通行碼。", "已清空通行码。");
      } catch (error) {
        store.error = String(error);
      }
      nav.refresh();
    }
    if (act === "ms-export") {
      try {
        const saved = await api.mobileSyncExport();
        if (saved) store.mobileSyncNote = t(`已匯出同步檔：${saved}`, `已导出同步档：${saved}`);
      } catch (error) {
        store.error = String(error);
      }
      nav.refresh();
    }
    if (act === "ms-import") {
      try {
        const out = await api.mobileSyncImport();
        if (out) {
          store.mobileSyncNote = t(
            `已合併手機快照：累計 ${fmt(out.characters)} 字・${fmt(out.days)} 天，新增解鎖 ${out.newlyUnlocked} 個成就（共 ${out.unlockedTotal} 個）。`,
            `已合并手机快照：累计 ${fmt(out.characters)} 字・${fmt(out.days)} 天，新增解锁 ${out.newlyUnlocked} 个成就（共 ${out.unlockedTotal} 个）。`,
          );
          await Promise.all([reloadLedger(), reloadSteam()]);
        }
      } catch (error) {
        store.error = String(error);
      }
      nav.refresh();
    }
    if (act === "ms-clear") {
      try {
        store.mobileSync = await api.mobileSyncClear();
        store.mobileSyncNote = t("已清除跨平台通行碼與手機同步鏡像（本機帳本不受影響）。", "已清除跨平台通行码与手机同步镜像（本机账本不受影响）。");
        await reloadLedger();
      } catch (error) {
        store.error = String(error);
      }
      nav.refresh();
    }
    if (act === "cancel") modal.hidden = true;
    if (act === "confirm") {
      try {
        if (confirmAct === "clear") store.summary = await api.clearLedger();
        if (confirmAct === "clear-time") await api.clearActivity();
      } catch (error) {
        store.error = String(error);
      }
      modal.hidden = true;
      nav.refresh();
    }
  });
  modal.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      e.stopPropagation();
      modal.hidden = true;
    }
  });
  const at = params.get("at");
  if (at) app.querySelector(`#${at}`)?.scrollIntoView({ block: "start" });
  const show = params.get("explain") as Switch | null;
  if (show && ["record", "share", "app", "track", "cloud"].includes(show)) void explain(app, show);
}

/** The sheet the explanation page is drawn in (the settings page and the first-run guide both carry one). */
export const EXPLAIN_SHEET = `<div class="explain" hidden role="dialog" aria-modal="true"><div class="explain-card"></div></div>`;

/** The explanation page for one switch. Resolves true when the user turns it on. `have`: the sub-switches already
 *  chosen, when they are not saved yet (the first-run guide keeps its answers until it ends). */
export async function explain(app: HTMLElement, which: Switch, have: { showApp: boolean; showTrack: boolean } = store.companion.share): Promise<boolean> {
  const sheet = app.querySelector<HTMLElement>(".explain")!;
  const card = sheet.querySelector<HTMLElement>(".explain-card")!;
  const win = store.info.platform === "windows";
  const appNames = !!store.shareStatus?.appNames;
  const ask = {
    share: which !== "record",
    showApp: appNames && (which === "app" || have.showApp),
    showTrack: which === "track" || have.showTrack,
  };
  let lines: PreviewLine[] = [];
  try {
    if (which !== "cloud") lines = await api.presencePreview(ask);
  } catch {
    lines = [];
  }
  const line = (c: PreviewLine["case"]) => lines.find((l) => l.case === c)?.text ?? "";
  const friends = `
    <div class="friend">
      <div class="friend-cap">${t("好友清單裡的你（示意；好友看到的是他們自己 Steam 的語言）", "好友列表里的你（示意；好友看到的是他们自己 Steam 的语言）")}</div>
      <div class="friend-row">
        <div class="avatar"><img src="${CAT.momoIdle}" alt=""></div>
        <div class="friend-text"><b>${t("你", "你")}</b><span class="playing">${t("正在玩 喵撲", "正在玩 喵扑")}</span><span class="rp">${esc(line("active"))}</span></div>
      </div>
    </div>
    <ul class="cases">
      <li><span>${t("在做事時", "在做事时")}</span><span>${esc(line("active"))}</span></li>
      <li><span>${t("全螢幕或玩遊戲時", "全屏或玩游戏时")}</span><span>${esc(line("fullscreen"))}</span></li>
      <li><span>${t("離開、暫停或不記錄的 App", "离开、暂停或不记录的 App")}</span><span>${esc(line("idle"))}</span></li>
    </ul>`;
  const body: Record<Switch, { title: string; html: string }> = {
    record: {
      title: t("記錄時間花在哪裡", "记录时间花在哪里"),
      html: `
        <p>${t("打開後，喵撲貓窩會在這台電腦上統計單日各類 App 用了幾分鐘。類別有十種：寫作、程式、設計、瀏覽、通訊、影音、遊戲、學習、辦公、其他。", "打开后，喵扑猫窝会在这台电脑上统计单日各类 App 用了几分钟。类别有十种：写作、编程、设计、浏览、通讯、影音、游戏、学习、办公、其他。")}</p>
        <p>${t("貓窩看一眼你正在用的 App 是哪一類，查完就忘了：檔案裡只有日期、類別、分鐘數和大約的時段。像這樣：", "猫窝看一眼你正在用的 App 是哪一类，查完就忘了：文件里只有日期、类别、分钟数和大约的时段。像这样：")}</p>
        <div class="sample">2026-11-23  ${t("程式", "编程")} 95 ${t("分", "分")} · ${t("瀏覽", "浏览")} 30 ${t("分", "分")} · ${t("白天、晚上", "白天、晚上")}</div>
        <div class="knows">
          <div><h3>${t("不記的", "不记的")}</h3><ul><li>${t("App 名稱", "App 名称")}</li><li>${t("視窗標題、網址", "窗口标题、网址")}</li><li>${t("你打了什麼", "你打了什么")}</li><li>${t("幾點幾分", "几点几分")}</li></ul></div>
          <div><h3>${t("好友看到的", "好友看到的")}</h3><ul><li>${t("什麼都沒有。", "什么都没有。")}</li><li class="mut">${t("要另外打開「讓 Steam 好友看到我在做什麼」。", "要另外打开「让 Steam 好友看到我在做什么」。")}</li></ul></div>
        </div>
        <p class="mut">${t("數字只在這台電腦上，不上傳。隨時可以暫停、把某個 App 設成不記錄，或清除時間紀錄。", "数字只在这台电脑上，不上传。随时可以暂停、把某个 App 设成不记录，或清除时间记录。")}</p>`,
    },
    share: {
      title: t("讓 Steam 好友看到我在做什麼", "让 Steam 好友看到我在做什么"),
      html: `
        <p>${t("打開後，你的 Steam 好友清單裡，名字下面會多一行，說你大概在做什麼。Steam 也會顯示你正在玩「喵撲」。好友看到的就是這樣：", "打开后，你的 Steam 好友列表里，名字下面会多一行，说你大概在做什么。Steam 也会显示你正在玩「喵扑」。好友看到的就是这样：")}</p>
        ${friends}
        <p>${t("只說類別，不說 App 名稱與歌名，除非你另外打開那兩項。全螢幕和玩遊戲時一律只說「專心中」。", "只说类别，不说 App 名称与歌名，除非你另外打开那两项。全屏和玩游戏时一律只说「专注中」。")}</p>
        <p class="mut">${
          win
            ? t("分享有時限：1 小時、今天，或直到你關掉。關掉視窗後，通知區域會留一個小圖示，點它可以停止分享；時間到就自動停。", "分享有时限：1 小时、今天，或直到你关掉。关掉窗口后，通知区域会留一个小图标，点它可以停止分享；时间到就自动停。")
            : t("分享有時限：1 小時、今天，或直到你關掉。關掉視窗後，選單列會留一個小圖示，點它可以停止分享；時間到就自動停。", "分享有时限：1 小时、今天，或直到你关掉。关掉窗口后，菜单栏会留一个小图标，点它可以停止分享；时间到就自动停。")
        }</p>`,
    },
    app: {
      title: t("顯示 App 名稱", "显示 App 名称"),
      html: `
        <p>${t("好友那一行會加上你正在用的 App 的名字。全螢幕、遊戲和不記錄的 App 不會顯示名稱。", "好友那一行会加上你正在用的 App 的名字。全屏、游戏和不记录的 App 不会显示名称。")}</p>
        ${friends}
        <p class="mut">${t("名稱只在記憶體裡經過，從貓窩到 Steam，不寫進任何檔案。太長會被截短，整行不超過二十個字。", "名称只在内存里经过，从猫窝到 Steam，不写进任何文件。太长会被截短，整行不超过二十个字。")}</p>`,
    },
    cloud: {
      title: t("Steam 雲端", "Steam 云端"),
      html: `
        <p>${t("打開後，喵撲把這台電腦的帳本複本放進你的 Steam 雲端；你在另一台電腦打開喵撲時，兩邊的數字會合起來看。預設是關的，不打開，什麼都不會上去。", "打开后，喵扑把这台电脑的账本副本放进你的 Steam 云端；你在另一台电脑打开喵扑时，两边的数字会合起来看。默认是关的，不打开，什么都不会上去。")}</p>
        <div class="knows">
          <div><h3>${t("放上去的", "放上去的")}</h3><ul><li>${t("一共打了幾個字、哪幾天打過字（只有數字）", "一共打了几个字、哪几天打过字（只有数字）")}</li><li>${t("米米莫、莫莫的好感數字", "米米莫、莫莫的好感数字")}</li><li>${t("一個固定大小（2.8 KB）的檔案，叫 ledger-隨機碼.bin；每台電腦各一個，只寫自己的", "一个固定大小（2.8 KB）的文件，叫 ledger-随机码.bin；每台电脑各一个，只写自己的")}</li></ul></div>
          <div><h3>${t("永遠不上去的", "永远不上去的")}</h3><ul><li>${t("選詞記憶", "选词记忆")}</li><li>${t("你打了什麼字、短語", "你打了什么字、短语")}</li><li>${t("時間花在哪裡", "时间花在哪里")}</li><li>${t("App 名稱、各項設定", "App 名称、各项设置")}</li></ul></div>
        </div>
        <p class="mut">${t("關掉時，這台電腦那一個檔案會從雲端刪掉；Steam 沒開著，就等下次開著再刪。隨機碼是第一次打開時隨機產生的，和你的電腦、帳號都對不上。", "关掉时，这台电脑那一个文件会从云端删掉；Steam 没开着，就等下次开着再删。随机码是第一次打开时随机生成的，和你的电脑、账号都对不上。")}</p>`,
    },
    track: {
      title: t("顯示正在聽的歌", "显示正在听的歌"),
      html: `
        <p>${t("好友那一行會加上「♪ 歌名」。", "好友那一行会加上「♪ 歌名」。")}</p>
        ${friends}
        <p>${
          win
            ? t("歌名從 Windows 的媒體控制讀：有支援的播放器正在播放時才讀得到。", "歌名从 Windows 的媒体控制读：有支持的播放器正在播放时才读得到。")
            : t("只支援「音樂」與 Spotify，而且只在它們開著時才問。第一次 macOS 會問你要不要讓喵撲控制它們：喵撲只讀正在播放的歌名，不會播放、暫停或改任何東西。網易雲、QQ 音樂暫不支援。", "只支持「音乐」与 Spotify，而且只在它们开着时才问。第一次 macOS 会问你要不要让喵扑控制它们：喵扑只读正在播放的歌名，不会播放、暂停或改任何东西。网易云、QQ 音乐暂不支持。")
        }</p>
        <p class="mut">${t("歌名只即時顯示，從不存檔。", "歌名只即时显示，从不存档。")}</p>`,
    },
  };
  card.innerHTML = `
    <h2>${body[which].title}</h2>
    ${body[which].html}
    <div class="btns"><button class="btn" data-x="no">${t("先不要", "先不要")}</button><button class="btn primary" data-x="yes">${t("打開", "打开")}</button></div>`;
  sheet.hidden = false;
  card.querySelector<HTMLButtonElement>('[data-x="no"]')?.focus();
  return new Promise((resolve) => {
    const done = (ok: boolean) => {
      sheet.hidden = true;
      resolve(ok);
    };
    card.querySelector('[data-x="yes"]')!.addEventListener("click", () => done(true));
    card.querySelector('[data-x="no"]')!.addEventListener("click", () => done(false));
    sheet.addEventListener("keydown", (e) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        done(false);
      }
    });
  });
}
