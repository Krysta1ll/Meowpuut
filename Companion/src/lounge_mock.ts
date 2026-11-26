// Browser preview mock (`npm run dev` outside Tauri) for the pure-game cat lounge, writing pad, sync inspector, and 82 achievements.

import type {
  AchievementCatalogItem,
  CurioDef,
  ExpeditionTier,
  FocusResponse,
  FurnitureDef,
  InteractResponse,
  LoungeState,
  LoungeView,
  PadCommitOutcome,
  PlaceDef,
  SettleLoungeResponse,
  StarStoryView,
  StoryView,
  SyncInspection,
  TitleDef,
  ToyDef,
} from "./lounge_types";

const PLACES: PlaceDef[] = [
  { id: "field", bit: 0, name: ["山野", "山野"], vibe: ["草叢沙沙響，莫莫撲蝶去", "草丛沙沙响，莫莫扑蝶去"], alwaysOpen: false },
  { id: "sea", bit: 1, name: ["海", "海"], vibe: ["浪花白白的，米米莫捂鼻躲魚味", "浪花白白的，米米莫捂鼻躲鱼味"], alwaysOpen: false },
  { id: "street", bit: 2, name: ["小貓集市", "小猫集市"], vibe: ["永遠開張的巷口集市，什麼瓶蓋都有", "永远开张的巷口集市，什么瓶盖都有"], alwaysOpen: true },
  { id: "bamboo", bit: 3, name: ["竹林", "竹林"], vibe: ["風吹竹葉響，跟紙箱一樣安心", "风吹竹叶响，跟纸箱一样安心"], alwaysOpen: false },
  { id: "snow", bit: 4, name: ["雪山", "雪山"], vibe: ["小爪爪踩出咯吱小腳印", "小爪爪踩出咯吱小脚印"], alwaysOpen: false },
  { id: "moon", bit: 5, name: ["月", "月"], vibe: ["圓眼睛在月光裡偷偷咪到", "圆眼睛在月光里偷偷咪到"], alwaysOpen: false },
  { id: "fuji", bit: 6, name: ["富士", "富士"], vibe: ["兩隻貓並排搭爪看超大台電視", "两只猫并排搭爪看超大台电视"], alwaysOpen: false },
  { id: "harbor", bit: 7, name: ["香港", "香港"], vibe: ["街燈亮晶晶，奧哇好亮", "街灯亮晶晶，奥哇好亮"], alwaysOpen: false },
  { id: "volcano", bit: 8, name: ["火山", "火山"], vibe: ["肚皮暖暖的，像剛出鍋甜玉米", "肚皮暖暖的，像刚出锅甜玉米"], alwaysOpen: false },
  { id: "desert", bit: 9, name: ["沙漠", "沙漠"], vibe: ["米米莫看傻眼：這也太大一盆", "米米莫看傻眼：这也太大一盆"], alwaysOpen: false },
  { id: "maple", bit: 10, name: ["紅葉", "红叶"], vibe: ["飄下來一片就被小爪拍兩下", "飘下来一片就被小爪拍两下"], alwaysOpen: false },
  { id: "lunar", bit: 11, name: ["月球", "月球"], vibe: ["米米莫飄在半空，登月嘎嘎固", "米米莫飘在半空，登月嘎嘎固"], alwaysOpen: false },
];

const CURIOS: CurioDef[] = [
  { id: "cap_amber", name: ["琥珀氣泡瓶蓋", "琥珀气泡瓶盖"], flavor: ["米米莫沙發底下的頭號收藏。", "米米莫沙发底下的头号收藏。"], rarity: "common", icon: "🟠", placeBit: null },
  { id: "cap_cobalt", name: ["鈷藍星光瓶蓋", "钴蓝星光瓶盖"], flavor: ["照到燈光會閃一下，像小貓眼睛。", "照到灯光会闪一下，像小猫眼睛。"], rarity: "common", icon: "🔵", placeBit: null },
  { id: "cap_brass", name: ["銅敲敲復古瓶蓋", "铜敲敲复古瓶盖"], flavor: ["被拍飛過至少一百次。", "被拍飞过至少一百次。"], rarity: "common", icon: "🪙", placeBit: null },
  { id: "cap_mint", name: ["薄荷脆脆瓶蓋", "薄荷脆脆瓶盖"], flavor: ["聞起來涼涼的，絕沒魚味。", "闻起来凉凉的，绝没鱼味。"], rarity: "common", icon: "🟢", placeBit: null },
  { id: "faucet_drop", name: ["水龍頭水晶滴珠", "水龙头水晶滴珠"], flavor: ["米米莫蹲守半小時才等到的一滴。", "米米莫蹲守半小时才等到的一滴。"], rarity: "uncommon", icon: "💧", placeBit: null },
  { id: "noodle_ticket", name: ["泡麵盒專車車票", "泡面盒专车车票"], flavor: ["莫莫第一次坐泡麵盒回家蓋的章。", "莫莫第一次坐泡面盒回家盖的章。"], rarity: "uncommon", icon: "🎫", placeBit: null },
  { id: "corn_kernel", name: ["黃金甜玉米粒", "黄金甜玉米粒"], flavor: ["米米莫看了瞳孔瞬間放大。", "米米莫看了瞳孔瞬间放大。"], rarity: "uncommon", icon: "🌽", placeBit: null },
  { id: "cardboard_stamp", name: ["紙箱大王戳章", "纸箱大王戳章"], flavor: ["蓋上去就自動變成米米莫領地。", "盖上去就自动变成米米莫领地。"], rarity: "uncommon", icon: "📦", placeBit: null },
  { id: "momo_bell", name: ["莫莫修修小鈴", "莫莫修修小铃"], flavor: ["提醒大家莫莫來了奧。", "提醒大家莫莫来了奥。"], rarity: "rare", icon: "🔔", placeBit: null },
  { id: "mimimo_whisker", name: ["米米莫幸運黑鬍鬚", "米米莫幸运黑胡须"], flavor: ["讓陪伴變得嘎嘎順。", "让陪伴变得嘎嘎顺。"], rarity: "rare", icon: "✨", placeBit: null },
  { id: "market_lantern", name: ["小貓集市暖黃燈籠", "小猫集市暖黄灯笼"], flavor: ["賣貓豆豆老攤送的紀念小燈。", "卖猫豆豆老摊送的纪念小灯。"], rarity: "rare", icon: "🏮", placeBit: null },
  { id: "cap_rainbow", name: ["七彩大咪傳說瓶蓋", "七彩大咪传说瓶盖"], flavor: ["小貓集市壓箱寶，兩隻貓搶著抱。", "小猫集市压箱宝，两只猫抢着抱。"], rarity: "legend", icon: "🌈", placeBit: null },
  { id: "curio_field", name: ["蒲公英毛球書籤", "蒲公英毛球书签"], flavor: ["山野風裡撿到的白絨球。", "山野风里捡到的白绒球。"], rarity: "uncommon", icon: "🌼", placeBit: 0 },
  { id: "curio_sea", name: ["絕無魚味白貝殼", "绝无鱼味白贝壳"], flavor: ["米米莫鼓起勇氣聞了十秒。", "米米莫鼓起勇气闻了十秒。"], rarity: "uncommon", icon: "🐚", placeBit: 1 },
  { id: "curio_street", name: ["集市限量貓豆豆罐", "集市限量猫豆豆罐"], flavor: ["搖起來沙沙超米到。", "摇起来沙沙超米到。"], rarity: "rare", icon: "🥫", placeBit: 2 },
  { id: "curio_bamboo", name: ["沙沙竹葉小風鈴", "沙沙竹叶小风铃"], flavor: ["掛在客廳像回到安靜竹林。", "挂在客厅像回到安静竹林。"], rarity: "uncommon", icon: "🎋", placeBit: 3 },
  { id: "curio_snow", name: ["冰晶肉球雪花球", "冰晶肉球雪花球"], flavor: ["裡頭有兩排小小貓腳印。", "里头有两排小小猫脚印。"], rarity: "rare", icon: "❄️", placeBit: 4 },
  { id: "curio_moon", name: ["偷偷咪到弦月別針", "偷偷咪到弦月别针"], flavor: ["暗處微微發光的小銀月。", "暗处微微发光的小银月。"], rarity: "rare", icon: "🌙", placeBit: 5 },
  { id: "curio_fuji", name: ["富士搭爪明信片", "富士搭爪明信片"], flavor: ["富士山下兩隻貓背影的紀念卡。", "富士山下两只猫背影的纪念卡。"], rarity: "rare", icon: "🏔️", placeBit: 6 },
  { id: "curio_harbor", name: ["香港霓虹小招牌", "香港霓虹小招牌"], flavor: ["香港街頭縮小的迷你招牌。", "香港街头缩小的迷你招牌。"], rarity: "rare", icon: "🪧", placeBit: 7 },
  { id: "curio_volcano", name: ["篝火烤玉米徽章", "篝火烤玉米徽章"], flavor: ["火山篝火烤得金黃微焦。", "火山篝火烤得金黄微焦。"], rarity: "rare", icon: "🔥", placeBit: 8 },
  { id: "curio_desert", name: ["巨型貓砂鏟吊飾", "巨型猫砂铲吊饰"], flavor: ["米米莫堅稱這是聖物。", "米米莫坚称这是圣物。"], rarity: "rare", icon: "🏜️", placeBit: 9 },
  { id: "curio_maple", name: ["雙爪拍拍紅葉標本", "双爪拍拍红叶标本"], flavor: ["邊角還有小牙印。", "边角还有小牙印。"], rarity: "uncommon", icon: "🍁", placeBit: 10 },
  { id: "curio_lunar", name: ["2077 暖金膠片", "2077 暖金胶片"], flavor: ["無重力月球帶回的暖金色底片。", "无重力月球带回的暖金色底片。"], rarity: "legend", icon: "🎞️", placeBit: 11 },
];

const FURNITURE: FurnitureDef[] = [
  { id: "win_snow_peak", bit: 0, slot: "window", price: 800, p0Shop: false, unlockCurios: 0, unlockPostcards: 0, name: ["雪峰窗景", "雪峰窗景"], desc: ["窗外是安靜的雪山稜線。", "窗外是安静的雪山棱线。"], icon: "🏔️" },
  { id: "win_starry_moon", bit: 1, slot: "window", price: 1200, p0Shop: false, unlockCurios: 0, unlockPostcards: 0, name: ["夜月星海窗景", "夜月星海窗景"], desc: ["大月亮掛在窗框裡。", "大月亮挂在窗框里。"], icon: "🌙" },
  { id: "box_villa", bit: 2, slot: "left", price: 600, p0Shop: true, unlockCurios: 0, unlockPostcards: 0, name: ["雙層紙箱別墅", "双层纸箱别墅"], desc: ["米米莫御用豪宅，上下都能藏瓶蓋。", "米米莫御用豪宅，上下都能藏瓶盖。"], icon: "📦" },
  { id: "faucet_fountain", bit: 3, slot: "left", price: 900, p0Shop: true, unlockCurios: 0, unlockPostcards: 0, name: ["水龍頭飲水機", "水龙头饮水机"], desc: ["永遠慢慢滴答的活水，米米莫最愛盯著看。", "永远慢慢滴答的活水，米米莫最爱盯着看。"], icon: "🚰" },
  { id: "campfire_hearth", bit: 4, slot: "left", price: 1600, p0Shop: false, unlockCurios: 0, unlockPostcards: 0, name: ["篝火暖爐", "篝火暖炉"], desc: ["木柴劈啪暖呼呼。", "木柴劈啪暖呼呼。"], icon: "🔥" },
  { id: "corn_pot", bit: 5, slot: "floor", price: 1100, p0Shop: true, unlockCurios: 0, unlockPostcards: 0, name: ["黃金甜玉米鍋", "黄金甜玉米锅"], desc: ["冒著甜香熱氣的小鍋。", "冒着甜香热气的小锅。"], icon: "🌽" },
  { id: "noodle_bed", bit: 6, slot: "floor", price: 700, p0Shop: false, unlockCurios: 0, unlockPostcards: 0, name: ["泡麵盒專車窩", "泡面盒专车窝"], desc: ["莫莫認證最舒服軟墊。", "莫莫认证最舒服软垫。"], icon: "🍜" },
  { id: "kibble_rug", bit: 7, slot: "floor", price: 500, p0Shop: true, unlockCurios: 0, unlockPostcards: 0, name: ["貓豆豆編織毯", "猫豆豆编织毯"], desc: ["翻肚皮曬太陽剛剛好。", "翻肚皮晒太阳刚刚好。"], icon: "🧶" },
  { id: "crt_tv", bit: 8, slot: "right", price: 1400, p0Shop: true, unlockCurios: 0, unlockPostcards: 0, name: ["復古 CRT 小電視", "复古 CRT 小电视"], desc: ["兩隻貓最愛趴著追劇。", "两只猫最爱趴着追剧。"], icon: "📺" },
  { id: "cap_tower", bit: 9, slot: "right", price: 1000, p0Shop: true, unlockCurios: 6, unlockPostcards: 0, name: ["瓶蓋收藏展示架", "瓶盖收藏展示架"], desc: ["需 6 件奇珍解鎖：整排亮晶晶瓶蓋架。", "需 6 件奇珍解锁：整排亮晶晶瓶盖架。"], icon: "🏆" },
  { id: "neon_sign", bit: 10, slot: "wall", price: 1800, p0Shop: false, unlockCurios: 0, unlockPostcards: 0, name: ["小貓集市霓虹燈牌", "小猫集市霓虹灯牌"], desc: ["寫著「今日超咪到」。", "写着「今日超咪到」。"], icon: "✨" },
  { id: "paw_garland", bit: 11, slot: "wall", price: 900, p0Shop: false, unlockCurios: 0, unlockPostcards: 3, name: ["雙貓照片掛繩", "双猫照片挂绳"], desc: ["需 3 張明信片解鎖。", "需 3 张明信片解锁。"], icon: "🖼️" },
];

const TITLES: TitleDef[] = [
  { id: "kibble_taster", name: ["貓豆豆鑑賞家", "猫豆豆鉴赏家"], hint: ["踏入小貓客廳即可配戴", "踏入小猫客厅即可佩戴"], badge: "🥣" },
  { id: "balance_master", name: ["端水大師", "端水大师"], hint: ["各摸米米莫與莫莫至少 5 次", "各摸米米莫与莫莫至少 5 次"], badge: "⚖️" },
  { id: "cap_tycoon", name: ["瓶蓋富豪", "瓶盖富豪"], hint: ["收集至少 6 件奇珍", "收集至少 6 件奇珍"], badge: "🪙" },
  { id: "cardboard_lord", name: ["紙箱薪王", "纸箱薪王"], hint: ["玩紙箱躲貓貓 3 次", "玩纸箱躲猫猫 3 次"], badge: "📦" },
  { id: "corn_alchemist", name: ["甜玉米煉金術士", "甜玉米炼金术士"], hint: ["完成 5 次遠征或累積五萬喵字", "完成 5 次远征或累积五万喵字"], badge: "🌽" },
  { id: "focus_sage", name: ["陪讀賢者", "陪读贤者"], hint: ["完成 1 次極簡陪讀", "完成 1 次极简陪读"], badge: "🍵" },
  { id: "lunar_gagagu", name: ["登月嘎嘎固", "登月嘎嘎固"], hint: ["抵達月球或撿到暖金膠片", "抵达月球或捡到暖金胶片"], badge: "🌕" },
  { id: "market_collector", name: ["集市傳奇藏家", "集市传奇藏家"], hint: ["收集至少 18 件奇珍", "收集至少 18 件奇珍"], badge: "👑" },
  { id: "word_sea_god", name: ["字海貓神", "字海猫神"], hint: ["累積達一百萬喵字", "累积达一百万喵字"], badge: "🌊" },
  { id: "ultimate_damidao", name: ["終極大咪到", "终极大咪到"], hint: ["踏入星階 ★25 或解鎖 70 個成就", "踏入星阶 ★25 或解锁 70 个成就"], badge: "⭐" },
];

const TOYS: ToyDef[] = [
  { id: "kibble", name: ["撒貓豆豆", "撒猫豆豆"], desc: ["咔滋咔滋衝過來", "咔滋咔滋冲过来"], icon: "🥣" },
  { id: "sweet_corn", name: ["熱甜玉米", "热甜玉米"], desc: ["米米莫珍奶圓瞳反轉", "米米莫珍奶圆瞳反转"], icon: "🌽" },
  { id: "bottle_cap", name: ["丟瓶蓋", "丢瓶盖"], desc: ["莫莫偷藏奇珍進紙箱", "莫莫偷藏奇珍进纸箱"], icon: "🪙" },
  { id: "cardboard_box", name: ["紙箱躲貓貓", "纸箱躲猫猫"], desc: ["抓到小紙箱怪", "抓到小纸箱怪"], icon: "📦" },
  { id: "laser_dot", name: ["雷射紅點", "激光红点"], desc: ["15 秒自動煞車追擊", "15 秒自动刹车追击"], icon: "🔴" },
  { id: "noodle_box", name: ["泡麵盒專車", "泡面盒专车"], desc: ["視窗順風車微戲劇", "窗口顺风车微戏剧"], icon: "🍜" },
];

const STORIES: StoryView[] = [
  { id: "mimimo_10", cat: "mimimo", level: 10, title: ["水龍頭滴答等水記", "水龙头滴答等水记"], unlocked: true, body: ["碗裡明明有滿盆乾淨水，米米莫卻偏要等水龍頭慢悠悠滴下來才舔一口。", "碗里明明有满盆干净水，米米莫却偏要等水龙头慢悠悠滴下来才舔一口。"] },
  { id: "mimimo_25", cat: "mimimo", level: 25, title: ["沙發底下的瓶蓋銀行", "沙发底下的瓶盖银行"], unlocked: true, body: ["沙發底下一伸手嘩啦滾出十七個亮晶晶瓶蓋，米米莫急忙用肚皮蓋住。", "沙发底下一伸手哗啦滚出十七个亮晶晶瓶盖，米米莫急忙用肚皮盖住。"] },
  { id: "momo_10", cat: "momo", level: 10, title: ["坐著泡麵盒專車回家的夜晚", "坐着泡面盒专车回家的夜晚"], unlocked: true, body: ["縮在軟綿綿的泡麵盒裡一路被抱回家，從此看到空箱都要鑽進去。", "缩在软绵绵的泡面盒里一路被抱回家，从此看到空箱都要钻进去。"] },
];

const STAR_STORIES: StarStoryView[] = [
  { id: "star_1", star: 1, title: ["★1・窗邊的第一顆星屑", "★1・窗边的第一颗星屑"], unlocked: true, body: ["好感滿級之後路沒有結束，這條一起走過的路才剛剛開始奧。", "好感满级之后路没有结束，这条一起走过的路才刚刚开始奥。"] },
  { id: "star_5", star: 5, title: ["★5・紙鶴信差的三條小徑", "★5・纸鹤信差的三条小径"], unlocked: true, body: ["五顆星亮成小貓耳朵的形狀，暖紙小屋永遠留著一盞不催人的燈。", "五颗星亮成小猫耳朵的形状，暖纸小屋永远留着一盏不催人的灯。"] },
  { id: "star_10", star: 10, title: ["★10・暖被窩裡的舊瓶蓋", "★10・暖被窝里的旧瓶盖"], unlocked: false, body: null },
  { id: "star_25", star: 25, title: ["★25・屋頂並肩看星海", "★25・屋顶并肩看星海"], unlocked: false, body: null },
  { id: "star_50", star: 50, title: ["★50・不急不慌的長路", "★50・不急不慌的长路"], unlocked: false, body: null },
];

let totalChars = 185_000;
let focusStartMs: number | null = null;
const state: LoungeState = {
  walletEpoch: 1,
  seenMeowChars: totalChars,
  kibbleRemainder: 0,
  kibbleBalance: 2_600,
  ownedFurniture: (1 << 7) | (1 << 2),
  starterGiftClaimed: true,
  petForm: "3d",
  curios: ["cap_cobalt", "curio_sea", "corn_kernel"],
  curioDupes: 1,
  roomSlots: { left: "box_villa", floor: "kibble_rug" },
  equippedTitle: "corn_alchemist",
  expeditionCount: 3,
  expeditionPity: 2,
  onboardingExpeditionDone: false,
  restedBonusChars: 2_000,
  expeditionSlots: [null, null, null],
  focusCompleted: 1,
  snackVouchers: 1,
  toyPlays: { kibble: 2, cardboard_box: 1 },
  pattedMimimoLocal: 3,
  pattedMomoLocal: 4,
  petBucket: 58,
  petBucketMs: Date.now(),
  seed: 42,
};

function buildMockView(): LoungeView {
  return {
    state: { ...state, curios: [...state.curios], roomSlots: { ...state.roomSlots }, expeditionSlots: [...state.expeditionSlots], toyPlays: { ...state.toyPlays } },
    availableKibble: state.kibbleBalance,
    totalChars,
    placesMask: 0b0000_1100_0111,
    unlockedSlots: 3,
    paragon: { bondMimimo: 100, bondMomo: 100, maxBondReached: true, paragonLevel: 6, stardustPips: 4, nextStarPct: 42, nextStarNeed: 33_000 },
    places: PLACES,
    curios: CURIOS,
    furniture: FURNITURE,
    titles: TITLES,
    toys: TOYS,
    unlockedTitles: ["kibble_taster", "corn_alchemist", "focus_sage"],
    stories: STORIES,
    starStories: STAR_STORIES,
  };
}

export function mockLounge(command: string, args?: Record<string, unknown>): unknown {
  if (command === "lounge_view" || command === "record_active_minute") return buildMockView();
  if (command === "lounge_set_form") {
    state.petForm = args?.form === "pixel" ? "pixel" : "3d";
    return buildMockView();
  }
  if (command === "lounge_claim_starter") {
    const id = String(args?.itemId ?? "kibble_rug");
    const f = FURNITURE.find((x) => x.id === id) ?? FURNITURE[7];
    state.ownedFurniture |= 1 << f.bit;
    state.starterGiftClaimed = true;
    state.roomSlots[f.slot] = f.id;
    return buildMockView();
  }
  if (command === "lounge_buy_furniture") {
    const f = FURNITURE.find((x) => x.id === args?.itemId);
    if (f && f.p0Shop && state.kibbleBalance >= f.price) {
      state.kibbleBalance -= f.price;
      state.ownedFurniture |= 1 << f.bit;
    }
    return buildMockView();
  }
  if (command === "lounge_interact") {
    const toyId = String(args?.toyId ?? "kibble");
    const cat = (args?.cat as "mimimo" | "momo" | undefined) ?? "both";
    if (toyId === "pat" && cat === "mimimo") state.pattedMimimoLocal++;
    else if (toyId === "pat" && cat === "momo") state.pattedMomoLocal++;
    else state.toyPlays[toyId] = (state.toyPlays[toyId] ?? 0) + 1;
    const mood = toyId === "cardboard_box" ? "peek" : toyId === "sweet_corn" ? "sparkle" : toyId === "laser_dot" || toyId === "bottle_cap" ? "dilated" : cat === "mimimo" ? "purr" : "happy";
    return {
      reaction: { toyId, cat, mood, drama: toyId === "laser_dot" ? "DRAMA_LASER_CHASE" : "DRAMA_FISH_VS_CORN", dialogue: ["哇啊！米米莫與莫莫在客廳玩得超級咪到！", "哇啊！米米莫与莫莫在客厅玩得超级咪到！"], throttled: false, kibbleGained: toyId === "pat" ? 1 : 0 },
      completed: [],
      view: buildMockView(),
    } satisfies InteractResponse;
  }
  if (command === "lounge_dispatch_expedition") {
    const slot = Number(args?.slot ?? 0);
    const place = PLACES.find((p) => p.id === args?.placeId) ?? PLACES[2];
    const tier = (args?.tier as ExpeditionTier) ?? "stroll";
    const need = tier === "onboarding" ? 300 : tier === "adventure" ? 15_000 : 2_000;
    state.expeditionSlots[slot] = { placeId: place.id, placeBit: place.bit, tier, walkedChars: 0, needChars: need };
    return { completed: [], view: buildMockView() } satisfies SettleLoungeResponse;
  }
  if (command === "lounge_equip_room") {
    const slot = String(args?.slot ?? "");
    const item = args?.itemId as string | null | undefined;
    if (item) state.roomSlots[slot] = item;
    else delete state.roomSlots[slot];
    return buildMockView();
  }
  if (command === "lounge_equip_title") {
    state.equippedTitle = String(args?.titleId ?? "");
    return buildMockView();
  }
  if (command === "lounge_focus_start") {
    focusStartMs = Date.now();
    return focusStartMs;
  }
  if (command === "lounge_complete_focus") {
    const mins = focusStartMs ? Math.max(0, Math.floor((Date.now() - focusStartMs) / 60_000)) : 0;
    focusStartMs = null;
    state.snackVouchers++;
    if (mins >= 1) state.focusCompleted++;
    return {
      outcome: { minutes: mins, naturalMeowChars: mins * 6, snackVouchers: state.snackVouchers, focusCompleted: state.focusCompleted, dialogue: ["收工蓋上紙本陪讀小票！附贈 1 張零壓力零食反應券。", "收工盖上纸本陪读小票！附赠 1 张零压力零食反应券。"] },
      view: buildMockView(),
    } satisfies FocusResponse;
  }
  if (command === "pad_commit") {
    const g = Math.min(240, Number(args?.graphemes ?? 0));
    totalChars += g;
    return { accepted: g, dailyTotal: g, totalChars } satisfies PadCommitOutcome;
  }
  if (command === "inspect_sync_envelope") {
    return {
      formatVersion: 1, formatBytes: 3072, epoch: 1, totalGraphemes: totalChars, replicaCount: 0,
      headerHex: "4d 50 53 59 01 00 00 00",
      privacyNote: ["僅包含固定長度的無符號數字與位元，零輸入內容、零帳號個資。", "仅包含固定长度的无符号数字与位，零输入内容、零账号个资。"],
      fields: [
        { key: "magic", label: ["檔案標記與版本", "文件标记与版本"], value: "MPSY v1 (3072 B)", bytes: 8 },
        { key: "graphemes", label: ["累積字數計數", "累积字数计数"], value: String(totalChars), bytes: 8 },
      ],
    } satisfies SyncInspection;
  }
  if (command === "achievements_catalog") return mockAchievementsCatalog();
  return null;
}

function mockAchievementsCatalog(): AchievementCatalogItem[] {
  const sample: [string, string, string, AchievementCatalogItem["tier"], boolean, boolean][] = [
    ["CHARS_1", "哇啊，第一行字", "你的第一份陪伴落在這裡了，莫莫和米米莫都咪到。", "normal", false, true],
    ["CHARS_2M", "兩百萬字・硬核貓豆豆山", "【難】兩百萬喵字！小貓集市擺一整街都裝不完你們的足跡。", "hard", false, false],
    ["CHARS_3M", "三百萬字・傳說級大咪到", "【極難】三百萬喵字！兩隻貓仰頭看到脖子酸，佩服得嘎嘎固。", "very_hard", false, false],
    ["CHARS_4M", "四百萬字・字海貓神", "【傳說】四百萬喵字！米米莫與莫莫直接封你為終極大咪到。", "legend", false, false],
    ["MASTER_MEOWPUUT", "喵撲大滿貫・終極咪到", "【傳說】一百萬喵字、一百座山、十二個地方加上雙貓好感雙滿級！", "legend", true, false],
    ["LOCAL_DAWN", "清晨的字・嘛啊早安", "清晨就陪伴在一起，莫莫剛睡醒喊哇啊。", "local", false, true],
  ];
  return Array.from({ length: 82 }, (_, i) => {
    const s = sample[i % sample.length];
    const steam = i < 80;
    return {
      api: i < sample.length ? s[0] : steam ? `MOCK_ACH_${i + 1}` : i === 80 ? "LOCAL_DAWN" : "LOCAL_LATE_NIGHT",
      name: [s[1], s[1]], desc: [s[2], s[2]], hidden: s[4], steam, enabled: true, unlocked: s[5],
      tier: steam ? (s[3] === "local" ? "normal" : s[3]) : "local", index: i + 1,
    };
  });
}
