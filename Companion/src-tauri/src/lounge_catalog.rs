#![forbid(unsafe_code)]
//! Static catalogs for the Steam companion's cat lounge: 12 landmarks, 24 curios, 12 furniture pieces, 10 titles, 6 toys.
//!
//! All entries live in static read-only arrays so lookups allocate zero heap memory.
//!
//! ```
//! use meowpuut_companion_lib::lounge_catalog::{
//!     CURIOS, FURNITURE, PLACES, STAR_STORIES, STORIES, TITLES, TOYS,
//! };
//! assert_eq!(PLACES.len(), 12);
//! assert_eq!(PLACES[6].name[0], "富士");
//! assert_eq!(PLACES[7].name[0], "香港");
//! assert_eq!(CURIOS.len(), 24);
//! assert_eq!(FURNITURE.len(), 12);
//! assert_eq!(TITLES.len(), 10);
//! assert_eq!(TOYS.len(), 6);
//! assert_eq!(STORIES.len(), 10);
//! assert_eq!(STAR_STORIES.len(), 5);
//! ```

pub use crate::lounge_stories::{
    bond_stories, reaction_line, star_stories, StarStoryDef, StarStoryView, StoryDef, StoryView,
    STAR_STORIES, STORIES,
};
use meowpuut_pets::achievements::TABLE;
use serde::Serialize;

/// Twelve journey landmarks aligned with `meowpuut_pets::achievements::place` bit indices `0..12`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaceDef {
    pub id: &'static str,
    pub bit: u8,
    pub name: [&'static str; 2],
    pub vibe: [&'static str; 2],
    pub always_open: bool,
}

/// One of 24 collectible curios (小貓集市 treasures, bottle caps, and 12 landmark exclusives).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CurioDef {
    pub id: &'static str,
    pub name: [&'static str; 2],
    pub flavor: [&'static str; 2],
    pub rarity: &'static str,
    pub icon: &'static str,
    pub place_bit: Option<u8>,
}

/// One of 12 decorative furniture pieces placed across the 5 lounge room slots.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FurnitureDef {
    pub id: &'static str,
    pub bit: u8,
    pub slot: &'static str,
    pub price: u32,
    pub p0_shop: bool,
    pub unlock_curios: u8,
    pub unlock_postcards: u16,
    pub name: [&'static str; 2],
    pub desc: [&'static str; 2],
    pub icon: &'static str,
}

/// One of 10 equippable titles.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TitleDef {
    pub id: &'static str,
    pub name: [&'static str; 2],
    pub hint: [&'static str; 2],
    pub badge: &'static str,
}

/// One of 6 interactive toys in the cat lounge.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ToyDef {
    pub id: &'static str,
    pub name: [&'static str; 2],
    pub desc: [&'static str; 2],
    pub icon: &'static str,
}

pub const ROOM_SLOTS: [&str; 5] = ["window", "left", "floor", "right", "wall"];
pub const STARTER_FURNITURE: [&str; 3] = ["kibble_rug", "noodle_bed", "corn_pot"];

pub const PLACES: [PlaceDef; 12] = [
    PlaceDef { id: "field", bit: 0, name: ["山野", "山野"], vibe: ["草叢沙沙響，莫莫撲蝶去", "草丛沙沙响，莫莫扑蝶去"], always_open: false },
    PlaceDef { id: "sea", bit: 1, name: ["海", "海"], vibe: ["浪花白白的，米米莫捂鼻躲魚味", "浪花白白的，米米莫捂鼻躲鱼味"], always_open: false },
    PlaceDef { id: "street", bit: 2, name: ["小貓集市", "小猫集市"], vibe: ["永遠開張的巷口集市，什麼瓶蓋都有", "永远开张的巷口集市，什么瓶盖都有"], always_open: true },
    PlaceDef { id: "bamboo", bit: 3, name: ["竹林", "竹林"], vibe: ["風吹竹葉響，跟紙箱一樣安心", "风吹竹叶响，跟纸箱一样安心"], always_open: false },
    PlaceDef { id: "snow", bit: 4, name: ["雪山", "雪山"], vibe: ["小爪爪踩出咯吱小腳印", "小爪爪踩出咯吱小脚印"], always_open: false },
    PlaceDef { id: "moon", bit: 5, name: ["月", "月"], vibe: ["圓眼睛在月光裡偷偷咪到", "圆眼睛在月光里偷偷咪到"], always_open: false },
    PlaceDef { id: "fuji", bit: 6, name: ["富士", "富士"], vibe: ["兩隻貓並排搭爪看超大台電視", "两只猫并排搭爪看超大台电视"], always_open: false },
    PlaceDef { id: "harbor", bit: 7, name: ["香港", "香港"], vibe: ["街燈亮晶晶，奧哇好亮", "街灯亮晶晶，奥哇好亮"], always_open: false },
    PlaceDef { id: "volcano", bit: 8, name: ["火山", "火山"], vibe: ["肚皮暖暖的，像剛出鍋甜玉米", "肚皮暖暖的，像刚出锅甜玉米"], always_open: false },
    PlaceDef { id: "desert", bit: 9, name: ["沙漠", "沙漠"], vibe: ["米米莫看傻眼：這也太大一盆", "米米莫看傻眼：这也太大一盆"], always_open: false },
    PlaceDef { id: "maple", bit: 10, name: ["紅葉", "红叶"], vibe: ["飄下來一片就被小爪拍兩下", "飘下来一片就被小爪拍两下"], always_open: false },
    PlaceDef { id: "lunar", bit: 11, name: ["月球", "月球"], vibe: ["米米莫飄在半空，登月嘎嘎固", "米米莫飘在半空，登月嘎嘎固"], always_open: false },
];

pub const CURIOS: [CurioDef; 24] = [
    CurioDef { id: "cap_amber", name: ["琥珀氣泡瓶蓋", "琥珀气泡瓶盖"], flavor: ["米米莫沙發底下的頭號收藏，敲起來叮噹脆。", "米米莫沙发底下的头号收藏，敲起来叮当脆。"], rarity: "common", icon: "🟠", place_bit: None },
    CurioDef { id: "cap_cobalt", name: ["鈷藍星光瓶蓋", "钴蓝星光瓶盖"], flavor: ["照到燈光會閃一下，莫莫說像小貓眼睛。", "照到灯光会闪一下，莫莫说像小猫眼睛。"], rarity: "common", icon: "🔵", place_bit: None },
    CurioDef { id: "cap_brass", name: ["銅敲敲復古瓶蓋", "铜敲敲复古瓶盖"], flavor: ["邊緣磨得滑溜溜，被拍飛過至少一百次。", "边缘磨得滑溜溜，被拍飞过至少一百次。"], rarity: "common", icon: "🪙", place_bit: None },
    CurioDef { id: "cap_mint", name: ["薄荷脆脆瓶蓋", "薄荷脆脆瓶盖"], flavor: ["聞起來涼涼的，米米莫確認過絕沒魚味。", "闻起来凉凉的，米米莫确认过绝没鱼味。"], rarity: "common", icon: "🟢", place_bit: None },
    CurioDef { id: "faucet_drop", name: ["水龍頭水晶滴珠", "水龙头水晶滴珠"], flavor: ["米米莫蹲守半小時才等到的完美一滴水。", "米米莫蹲守半小时才等到的完美一滴水。"], rarity: "uncommon", icon: "💧", place_bit: None },
    CurioDef { id: "noodle_ticket", name: ["泡麵盒專車車票", "泡面盒专车车票"], flavor: ["莫莫第一次搭泡麵盒回家時蓋的肉球章。", "莫莫第一次搭泡面盒回家时盖的肉球章。"], rarity: "uncommon", icon: "🎫", place_bit: None },
    CurioDef { id: "corn_kernel", name: ["黃金甜玉米粒", "黄金甜玉米粒"], flavor: ["飽滿發亮，米米莫看了瞳孔瞬間放大。", "饱满发亮，米米莫看了瞳孔瞬间放大。"], rarity: "uncommon", icon: "🌽", place_bit: None },
    CurioDef { id: "cardboard_stamp", name: ["紙箱大王戳章", "纸箱大王戳章"], flavor: ["蓋在任何快遞箱上就自動變成米米莫領地。", "盖在任何快递箱上就自动变成米米莫领地。"], rarity: "uncommon", icon: "📦", place_bit: None },
    CurioDef { id: "momo_bell", name: ["莫莫修修小鈴", "莫莫修修小铃"], flavor: ["走一步響一下，提醒大家莫莫來了奧。", "走一步响一下，提醒大家莫莫来了奥。"], rarity: "rare", icon: "🔔", place_bit: None },
    CurioDef { id: "mimimo_whisker", name: ["米米莫幸運黑鬍鬚", "米米莫幸运黑胡须"], flavor: ["自然掉落的珍貴長鬚，據說能讓陪伴嘎嘎順。", "自然掉落的珍贵长须，据说能让陪伴嘎嘎顺。"], rarity: "rare", icon: "✨", place_bit: None },
    CurioDef { id: "market_lantern", name: ["小貓集市暖黃燈籠", "小猫集市暖黄灯笼"], flavor: ["巷口賣貓豆豆老攤送的紀念小燈，暖呼呼。", "巷口卖猫豆豆老摊送的纪念小灯，暖呼呼。"], rarity: "rare", icon: "🏮", place_bit: None },
    CurioDef { id: "cap_rainbow", name: ["七彩大咪傳說瓶蓋", "七彩大咪传说瓶盖"], flavor: ["小貓集市最稀有的壓箱寶，兩隻貓搶著抱。", "小猫集市最稀有的压箱宝，两只猫抢着抱。"], rarity: "legend", icon: "🌈", place_bit: None },
    CurioDef { id: "curio_field", name: ["蒲公英毛球書籤", "蒲公英毛球书签"], flavor: ["山野風裡撿到的白絨球，莫莫拍了三下沒飛走。", "山野风里捡到的白绒球，莫莫拍了三下没飞走。"], rarity: "uncommon", icon: "🌼", place_bit: Some(0) },
    CurioDef { id: "curio_sea", name: ["絕無魚味白貝殼", "绝无鱼味白贝壳"], flavor: ["米米莫鼓起勇氣聞了十秒才肯帶回家的海邊寶貝。", "米米莫鼓起勇气闻了十秒才肯带回家的海边宝贝。"], rarity: "uncommon", icon: "🐚", place_bit: Some(1) },
    CurioDef { id: "curio_street", name: ["集市限量貓豆豆罐", "集市限量猫豆豆罐"], flavor: ["印著兩隻貓大頭貼的鐵罐，搖起來沙沙超米到。", "印着两只猫大头贴的铁罐，摇起来沙沙超米到。"], rarity: "rare", icon: "🥫", place_bit: Some(2) },
    CurioDef { id: "curio_bamboo", name: ["沙沙竹葉小風鈴", "沙沙竹叶小风铃"], flavor: ["掛在客廳風一吹就像回到安靜竹林。", "挂在客厅风一吹就像回到安静竹林。"], rarity: "uncommon", icon: "🎋", place_bit: Some(3) },
    CurioDef { id: "curio_snow", name: ["冰晶肉球雪花球", "冰晶肉球雪花球"], flavor: ["搖一搖會飄起細雪，裡頭有兩排小小貓腳印。", "摇一摇会飘起细雪，里头有两排小小猫脚印。"], rarity: "rare", icon: "❄️", place_bit: Some(4) },
    CurioDef { id: "curio_moon", name: ["偷偷咪到弦月別針", "偷偷咪到弦月别针"], flavor: ["夜月屋簷下撿到的小銀月，在暗處微微發光。", "夜月屋檐下捡到的小银月，在暗处微微发光。"], rarity: "rare", icon: "🌙", place_bit: Some(5) },
    CurioDef { id: "curio_fuji", name: ["富士搭爪明信片", "富士搭爪明信片"], flavor: ["富士山下兩隻貓背影的紀念卡，超級咪到。", "富士山下两只猫背影的纪念卡，超级咪到。"], rarity: "rare", icon: "🏔️", place_bit: Some(6) },
    CurioDef { id: "curio_harbor", name: ["香港霓虹小招牌", "香港霓虹小招牌"], flavor: ["香港街頭縮小的迷你招牌，寫著「超米到」。", "香港街头缩小的迷你招牌，写着「超米到」。"], rarity: "rare", icon: "🪧", place_bit: Some(7) },
    CurioDef { id: "curio_volcano", name: ["篝火烤玉米徽章", "篝火烤玉米徽章"], flavor: ["火山篝火烤得金黃微焦，米米莫嘴角都亮了。", "火山篝火烤得金黄微焦，米米莫嘴角都亮了。"], rarity: "rare", icon: "🔥", place_bit: Some(8) },
    CurioDef { id: "curio_desert", name: ["巨型貓砂鏟吊飾", "巨型猫砂铲吊饰"], flavor: ["沙漠冒險紀念品，米米莫堅稱這是聖物。", "沙漠冒险纪念品，米米莫坚称这是圣物。"], rarity: "rare", icon: "🏜️", place_bit: Some(9) },
    CurioDef { id: "curio_maple", name: ["雙爪拍拍紅葉標本", "双爪拍拍红叶标本"], flavor: ["壓在書頁裡最紅的那一片，邊角有小牙印。", "压在书页里最红的那一片，边角有小牙印。"], rarity: "uncommon", icon: "🍁", place_bit: Some(10) },
    CurioDef { id: "curio_lunar", name: ["2077 暖金膠片", "2077 暖金胶片"], flavor: ["無重力月球帶回的暖金色底片，映著飄浮大黑貓。", "无重力月球带回的暖金色底片，映着漂浮大黑猫。"], rarity: "legend", icon: "🎞️", place_bit: Some(11) },
];

pub const FURNITURE: [FurnitureDef; 12] = [
    FurnitureDef { id: "win_snow_peak", bit: 0, slot: "window", price: 800, p0_shop: false, unlock_curios: 0, unlock_postcards: 0, name: ["雪峰窗景", "雪峰窗景"], desc: ["窗外是安靜的雪山稜線，適合搭爪發呆。", "窗外是安静的雪山棱线，适合搭爪发呆。"], icon: "🏔️" },
    FurnitureDef { id: "win_starry_moon", bit: 1, slot: "window", price: 1_200, p0_shop: false, unlock_curios: 0, unlock_postcards: 0, name: ["夜月星海窗景", "夜月星海窗景"], desc: ["一輪大月亮掛在窗框裡，照亮兩雙圓眼睛。", "一轮大月亮挂在窗框里，照亮两双圆眼睛。"], icon: "🌙" },
    FurnitureDef { id: "box_villa", bit: 2, slot: "left", price: 600, p0_shop: true, unlock_curios: 0, unlock_postcards: 0, name: ["雙層紙箱別墅", "双层纸箱别墅"], desc: ["米米莫御用豪宅，上下兩層都能藏瓶蓋。", "米米莫御用豪宅，上下两层都能藏瓶盖。"], icon: "📦" },
    FurnitureDef { id: "faucet_fountain", bit: 3, slot: "left", price: 900, p0_shop: true, unlock_curios: 0, unlock_postcards: 0, name: ["水龍頭飲水機", "水龙头饮水机"], desc: ["永遠慢慢滴答的活水，米米莫會蹲在旁邊盯著水滴。", "永远慢慢滴答的活水，米米莫会蹲在旁边盯着水滴。"], icon: "🚰" },
    FurnitureDef { id: "campfire_hearth", bit: 4, slot: "left", price: 1_600, p0_shop: false, unlock_curios: 0, unlock_postcards: 0, name: ["篝火暖爐", "篝火暖炉"], desc: ["木柴劈啪暖呼呼，兩隻貓會一起烤暖暖肚皮。", "木柴劈啪暖呼呼，两只猫会一起烤暖暖肚皮。"], icon: "🔥" },
    FurnitureDef { id: "corn_pot", bit: 5, slot: "floor", price: 1_100, p0_shop: true, unlock_curios: 0, unlock_postcards: 0, name: ["黃金甜玉米鍋", "黄金甜玉米锅"], desc: ["冒著甜香熱氣的小鍋，米米莫最沒抵抗力。", "冒着甜香热气的小锅，米米莫最没抵抗力。"], icon: "🌽" },
    FurnitureDef { id: "noodle_bed", bit: 6, slot: "floor", price: 700, p0_shop: false, unlock_curios: 0, unlock_postcards: 0, name: ["泡麵盒專車窩", "泡面盒专车窝"], desc: ["莫莫認證最舒服的軟墊泡麵盒，隨時發車。", "莫莫认证最舒服的软垫泡面盒，随时发车。"], icon: "🍜" },
    FurnitureDef { id: "kibble_rug", bit: 7, slot: "floor", price: 500, p0_shop: true, unlock_curios: 0, unlock_postcards: 0, name: ["貓豆豆編織毯", "猫豆豆编织毯"], desc: ["軟綿綿的暖色圓毯，翻肚皮曬太陽剛剛好。", "软绵绵的暖色圆毯，翻肚皮晒太阳刚刚好。"], icon: "🧶" },
    FurnitureDef { id: "crt_tv", bit: 8, slot: "right", price: 1_400, p0_shop: true, unlock_curios: 0, unlock_postcards: 0, name: ["復古 CRT 小電視", "复古 CRT 小电视"], desc: ["畫面暖呼呼微閃，兩隻貓最愛趴在上面追劇。", "画面暖呼呼微闪，两只猫最爱趴在上面追剧。"], icon: "📺" },
    FurnitureDef { id: "cap_tower", bit: 9, slot: "right", price: 1_000, p0_shop: true, unlock_curios: 6, unlock_postcards: 0, name: ["瓶蓋收藏展示架", "瓶盖收藏展示架"], desc: ["需收集 6 件奇珍解鎖：整排亮晶晶的瓶蓋展示架。", "需收集 6 件奇珍解锁：整排亮晶晶的瓶盖展示架。"], icon: "🏆" },
    FurnitureDef { id: "neon_sign", bit: 10, slot: "wall", price: 1_800, p0_shop: false, unlock_curios: 0, unlock_postcards: 0, name: ["小貓集市霓虹燈牌", "小猫集市霓虹灯牌"], desc: ["微微發亮的暖粉霓虹，寫著「今日超咪到」。", "微微发亮的暖粉霓虹，写着「今日超咪到」。"], icon: "✨" },
    FurnitureDef { id: "paw_garland", bit: 11, slot: "wall", price: 900, p0_shop: false, unlock_curios: 0, unlock_postcards: 3, name: ["雙貓照片掛繩", "双猫照片挂绳"], desc: ["需收集 3 張明信片解鎖：夾滿旅行回憶的小木夾掛繩。", "需收集 3 张明信片解锁：夹满旅行回忆的小木夹挂绳。"], icon: "🖼️" },
];

pub const TITLES: [TitleDef; 10] = [
    TitleDef { id: "kibble_taster", name: ["貓豆豆鑑賞家", "猫豆豆鉴赏家"], hint: ["踏入小貓客廳即可配戴", "踏入小猫客厅即可佩戴"], badge: "🥣" },
    TitleDef { id: "balance_master", name: ["端水大師", "端水大师"], hint: ["各摸米米莫與莫莫至少 5 次，或達成雙貓百摸", "各摸米米莫与莫莫至少 5 次，或达成双猫百摸"], badge: "⚖️" },
    TitleDef { id: "cap_tycoon", name: ["瓶蓋富豪", "瓶盖富豪"], hint: ["收集至少 6 件小貓集市與地標奇珍", "收集至少 6 件小猫集市与地标奇珍"], badge: "🪙" },
    TitleDef { id: "cardboard_lord", name: ["紙箱薪王", "纸箱薪王"], hint: ["玩紙箱躲貓貓 3 次或解鎖抓紙箱怪成就", "玩纸箱躲猫猫 3 次或解锁抓纸箱怪成就"], badge: "📦" },
    TitleDef { id: "corn_alchemist", name: ["甜玉米煉金術士", "甜玉米炼金术士"], hint: ["完成 5 次遠征或累積五萬喵字", "完成 5 次远征或累积五万喵字"], badge: "🌽" },
    TitleDef { id: "focus_sage", name: ["陪讀賢者", "陪读贤者"], hint: ["完成 1 次零壓力極簡陪讀", "完成 1 次零压力极简陪读"], badge: "🍵" },
    TitleDef { id: "lunar_gagagu", name: ["登月嘎嘎固", "登月嘎嘎固"], hint: ["抵達月球或撿到 2077 暖金膠片", "抵达月球或捡到 2077 暖金胶片"], badge: "🌕" },
    TitleDef { id: "market_collector", name: ["集市傳奇藏家", "集市传奇藏家"], hint: ["收集至少 18 件奇珍與稀有瓶蓋", "收集至少 18 件奇珍与稀有瓶盖"], badge: "👑" },
    TitleDef { id: "word_sea_god", name: ["字海貓神", "字海猫神"], hint: ["累積達一百萬喵字", "累积达一百万喵字"], badge: "🌊" },
    TitleDef { id: "ultimate_damidao", name: ["終極大咪到", "终极大咪到"], hint: ["踏入星階 ★25 或解鎖 70 個成就", "踏入星阶 ★25 或解锁 70 个成就"], badge: "⭐" },
];

pub const TOYS: [ToyDef; 6] = [
    ToyDef { id: "kibble", name: ["撒貓豆豆", "撒猫豆豆"], desc: ["咔滋咔滋！兩隻貓立刻小碎步衝過來", "咔滋咔滋！两只猫立刻小碎步冲过来"], icon: "🥣" },
    ToyDef { id: "sweet_corn", name: ["熱甜玉米", "热甜玉米"], desc: ["剛煮好的甜香，米米莫瞳孔瞬間放大", "刚煮好的甜香，米米莫瞳孔瞬间放大"], icon: "🌽" },
    ToyDef { id: "bottle_cap", name: ["丟瓶蓋", "丢瓶盖"], desc: ["叮噹一滾，兩隻貓空中神手接住", "叮当一滚，两只猫空中神手接住"], icon: "🪙" },
    ToyDef { id: "cardboard_box", name: ["紙箱躲貓貓", "纸箱躲猫猫"], desc: ["鑽進紙箱只露兩隻耳朵，抓到你啦", "钻进纸箱只露两只耳朵，抓到你啦"], icon: "📦" },
    ToyDef { id: "laser_dot", name: ["雷射紅點", "激光红点"], desc: ["15 秒自動煞車！雙貓蓄力追著紅點跑", "15 秒自动刹车！双猫蓄力追着红点跑"], icon: "🔴" },
    ToyDef { id: "noodle_box", name: ["泡麵盒專車", "泡面盒专车"], desc: ["嘟嘟發車！莫莫最愛的搖晃順風車", "嘟嘟发车！莫莫最爱的摇晃顺风车"], icon: "🍜" },
];

#[must_use]
pub fn find_place(id: &str) -> Option<&'static PlaceDef> { PLACES.iter().find(|p| p.id == id) }
#[must_use]
pub fn find_curio(id: &str) -> Option<&'static CurioDef> { CURIOS.iter().find(|c| c.id == id) }
#[must_use]
pub fn find_furniture(id: &str) -> Option<&'static FurnitureDef> { FURNITURE.iter().find(|f| f.id == id) }
#[must_use]
pub fn find_title(id: &str) -> Option<&'static TitleDef> { TITLES.iter().find(|t| t.id == id) }
#[must_use]
pub fn find_toy(id: &str) -> Option<&'static ToyDef> { TOYS.iter().find(|t| t.id == id) }

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AchievementCatalogItem {
    pub api: &'static str,
    pub name: [&'static str; 2],
    pub desc: [&'static str; 2],
    pub hidden: bool,
    pub steam: bool,
    pub enabled: bool,
    pub unlocked: bool,
    pub tier: &'static str,
    pub index: u16,
}

#[must_use]
pub fn achievements_catalog(unlocked: &[String]) -> Vec<AchievementCatalogItem> {
    TABLE
        .iter()
        .enumerate()
        .map(|(i, d)| {
            let tier = if !d.steam {
                "local"
            } else if d.desc_hant.contains("【傳說】") {
                "legend"
            } else if d.desc_hant.contains("【極難】") {
                "very_hard"
            } else if d.desc_hant.contains("【難】") || d.desc_hant.contains("【隱藏・難】") {
                "hard"
            } else {
                "normal"
            };
            AchievementCatalogItem {
                api: d.api,
                name: [d.zh_hant, d.zh_hans],
                desc: [d.desc_hant, d.desc_hans],
                hidden: d.hidden,
                steam: d.steam,
                enabled: d.enabled,
                unlocked: unlocked.iter().any(|u| u == d.api),
                tier,
                index: (i + 1) as u16,
            }
        })
        .collect()
}
