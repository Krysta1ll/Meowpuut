#![forbid(unsafe_code)]
//! Dual-cat bond stories, 5 P0 Paragon star stories (never mentioning word counts), and lounge toy reaction lines.
//!
//! ```
//! use meowpuut_companion_lib::lounge_stories::{bond_stories, star_stories, STAR_STORIES, STORIES};
//! assert_eq!(STORIES.len(), 10);
//! assert_eq!(STAR_STORIES.len(), 5);
//! assert_eq!(bond_stories(50, 25).iter().filter(|s| s.unlocked).count(), 5);
//! assert_eq!(star_stories(10).iter().filter(|s| s.unlocked).count(), 3);
//! ```

use serde::Serialize;

/// One of 10 bond stories unlocked at Lv.10 / 25 / 50 / 75 / 100 for each cat.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StoryDef {
    pub id: &'static str,
    pub cat: &'static str,
    pub level: u8,
    pub title: [&'static str; 2],
    pub body: [&'static str; 2],
}

/// One of 5 P0 Paragon star-tier text stories (`★1 / 5 / 10 / 25 / 50`), never mentioning word counts.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StarStoryDef {
    pub id: &'static str,
    pub star: u32,
    pub title: [&'static str; 2],
    pub body: [&'static str; 2],
}

pub const STORIES: [StoryDef; 10] = [
    StoryDef { id: "mimimo_10", cat: "mimimo", level: 10, title: ["水龍頭滴答等水記", "水龙头滴答等水记"], body: ["碗裡明明有滿盆乾淨水，米米莫卻偏要坐在洗手台邊盯著水龍頭，等那一滴慢悠悠掉下來，才滿意舔一口：奧哇，這滴最新鮮。", "碗里明明有满盆干净水，米米莫却偏要坐在洗手台边盯着水龙头，等那一滴慢悠悠掉下来，才满意舔一口：奥哇，这滴最新鲜。"] },
    StoryDef { id: "mimimo_25", cat: "mimimo", level: 25, title: ["沙發底下的瓶蓋銀行", "沙发底下的瓶盖银行"], body: ["打掃客廳時手往沙發底下一伸，嘩啦滾出十七個亮晶晶瓶蓋。米米莫火速衝過來用肚皮蓋住：這些都是我的退休金，不許動奧。", "打扫客厅时手往沙发底下一伸，哗啦滚出十七个亮晶晶瓶盖。米米莫火速冲过来用肚皮盖住：这些都是我的退休金，不许动奥。"] },
    StoryDef { id: "mimimo_50", cat: "mimimo", level: 50, title: ["聞到魚味立刻 wer 的大黑貓", "闻到鱼味立刻 wer 的大黑猫"], body: ["別家小貓看到小魚乾就撲上去，米米莫卻只遠遠嗅了一下就皺起臉「wer」一聲倒退三步——還是熱呼呼的甜玉米最對味嘛。", "别家小猫看到小鱼干就扑上去，米米莫却只远远嗅了一下就皱起脸「wer」一声倒退三步——还是热呼呼的甜玉米最对味嘛。"] },
    StoryDef { id: "mimimo_75", cat: "mimimo", level: 75, title: ["黃金甜玉米鍋的香氣陷阱", "黄金甜玉米锅的香气陷阱"], body: ["只要廚房傳來煮玉米的甜味，平常最愛躲紙箱的胖黑貓就會像被磁鐵吸住一樣出現在腳邊，雙眼放大成圓球：嘛啊，分一粒好不好？", "只要厨房传来煮玉米的甜味，平常最爱躲纸箱的胖黑猫就会像被磁铁吸住一样出现在脚边，双眼放大成圆球：嘛啊，分一粒好不好？"] },
    StoryDef { id: "mimimo_100", cat: "mimimo", level: 100, title: ["滿級認證：黑嚕嚕肚皮給你靠", "满级认证：黑噜噜肚皮给你靠"], body: ["從一開始害羞躲箱子，到如今主動把整顆圓滾滾黑肚皮翻過來讓你嚕。米米莫小聲呼嚕著說：只有你摸的時候，本貓超級無敵咪到。", "从一开始害羞躲箱子，到如今主动把整颗圆滚滚黑肚皮翻过来让你噜。米米莫小声呼噜着说：只有你摸的时候，本猫超级无敌咪到。"] },
    StoryDef { id: "momo_10", cat: "momo", level: 10, title: ["坐著泡麵盒專車回家的夜晚", "坐着泡面盒专车回家的夜晚"], body: ["那天小小的莫莫縮在軟綿綿的泡麵盒裡被一路抱回家，紙箱一晃一晃像坐專車。從此只要看到空盒子，莫莫都要鑽進去喊一聲哇啊。", "那天小小的莫莫缩在软绵绵的泡面盒里被一路抱回家，纸箱一晃一晃像坐专车。从此只要看到空盒子，莫莫都要钻进去喊一声哇啊。"] },
    StoryDef { id: "momo_25", cat: "momo", level: 25, title: ["小貓集市吃醋有點修", "小猫集市吃醋有点修"], body: ["聽說你剛才多摸了米米莫兩下，莫莫立刻背對著你坐成一團小山竹，尾巴尖卻偷偷甩呀甩：人家有點修，快拿貓豆豆哄我回來奧。", "听说你刚才多摸了米米莫两下，莫莫立刻背对着你坐成一团小山竹，尾巴尖却偷偷甩呀甩：人家有点修，快拿猫豆豆哄我回来奥。"] },
    StoryDef { id: "momo_50", cat: "momo", level: 50, title: ["窗台搭爪追劇小搭檔", "窗台搭爪追剧小搭档"], body: ["每當你忙碌累了抬起頭，就會看見莫莫把小小前爪搭在螢幕邊，認真盯著畫面上晃動的光點，像在陪你一起看超大台電視。", "每当你忙碌累了抬起头，就会看见莫莫把小小前爪搭在屏幕边，认真盯着画面上晃动的光点，像在陪你一起看超大台电视。"] },
    StoryDef { id: "momo_75", cat: "momo", level: 75, title: ["修修的塞給你最亮瓶蓋", "修修的塞给你最亮瓶盖"], body: ["莫莫嘴裡叼著一顆從小貓集市挑了半天的星光瓶蓋，修修的蹭了蹭你手背放下，抬起圓眼睛輕輕喵了一聲：最可愛的寶貝留給你。", "莫莫嘴里叼着一颗从小猫集市挑了半天的星光瓶盖，修修的蹭了蹭你手背放下，抬起圆眼睛轻轻喵了一声：最可爱的宝贝留给你。"] },
    StoryDef { id: "momo_100", cat: "momo", level: 100, title: ["滿級認證：永遠衝第一的小莫莫", "满级认证：永远冲第一的小莫莫"], body: ["無論你多久沒打開電腦，只要窗台亮起來，最嬌小也最勇敢的莫莫永遠第一個衝出門迎接你：哇啊，你回來啦，今天也一起超咪到！", "无论你多久没打开电脑，只要窗台亮起来，最娇小也最勇敢的莫莫永远第一个冲出门迎接你：哇啊，你回来啦，今天也一起超咪到！"] },
];

pub const STAR_STORIES: [StarStoryDef; 5] = [
    StarStoryDef { id: "star_1", star: 1, title: ["★1・窗邊的第一顆星屑", "★1・窗边的第一颗星屑"], body: ["好感滿級之後路沒有結束。米米莫把紙箱挪近窗台半吋，莫莫歪頭數著窗外第一顆亮起來的星屑：這條一起走過的路，才剛剛開始奧。", "好感满级之后路没有结束。米米莫把纸箱挪近窗台半寸，莫莫歪头数着窗外第一颗亮起来的星屑：这条一起走过的路，才刚刚开始奥。"] },
    StarStoryDef { id: "star_5", star: 5, title: ["★5・紙鶴信差的三條小徑", "★5・纸鹤信差的三条小径"], body: ["五顆星亮成一隻小貓耳朵的形狀，莫莫疊了第三隻紙鶴放上窗沿。無論外面晴天雨天，這間暖紙小屋永遠留著一盞不催人的燈。", "五颗星亮成一只小猫耳朵的形状，莫莫叠了第三只纸鹤放上窗沿。无论外面晴天雨天，这间暖纸小屋永远留着一盏不催人的灯。"] },
    StarStoryDef { id: "star_10", star: 10, title: ["★10・暖被窩裡的舊瓶蓋", "★10・暖被窝里的旧瓶盖"], body: ["一起走過這麼長一段路，沙發底下的瓶蓋已經堆成一座發光的小丘。米米莫懶洋洋打個哈欠，把最亮的那顆推到你手邊。", "一起走过这么长一段路，沙发底下的瓶盖已经堆成一座发光的小丘。米米莫懒洋洋打个哈欠，把最亮的那颗推到你手边。"] },
    StarStoryDef { id: "star_25", star: 25, title: ["★25・屋頂並肩看星海", "★25・屋顶并肩看星海"], body: ["兩隻貓不再只是偶爾探頭的訪客，而是這張桌子的老街坊。二十五顆星連成銀色河岸，莫莫輕輕蹭過你的掌心說：有你在就超米到。", "两只猫不再只是偶尔探头的访客，而是这张桌子的老街坊。二十五颗星连成银色河岸，莫莫轻轻蹭过你的掌心说：有你在就超米到。"] },
    StarStoryDef { id: "star_50", star: 50, title: ["★50・不急不慌的長路", "★50・不急不慌的长路"], body: ["五十顆星靜靜照亮整本暖紙手帳。不需要計時，也不必趕路——只要你回頭，米米莫的珍奶圓瞳和莫莫的探頭永遠都在原處。", "五十颗星静静照亮整本暖纸手账。不需要计时，也不必赶路——只要你回头，米米莫的珍奶圆瞳和莫莫的探头永远都在原处。"] },
];

/// Returns dialogue pair for petting or playing with a toy (`pick` in `0..2`).
#[must_use]
pub const fn reaction_line(key: &str, pick: usize) -> [&'static str; 2] {
    let idx = pick & 1;
    let pair: [[&'static str; 2]; 2] = match key.as_bytes() {
        b"pat_mimimo" => [
            ["米米莫假裝淡定，肚皮卻已經呼嚕到微微震動：還、還算米到奧。", "米米莫假装淡定，肚皮却已经呼噜到微微震动：还、还算米到奥。"],
            ["摸到圓滾滾大黑貓！米米莫偷偷把瓶蓋往你手邊推了一公分。", "摸到圆滚滚大黑猫！米米莫偷偷把瓶盖往你手边推了一公分。"],
        ],
        b"pat_momo" => [
            ["莫莫立刻把頭頂塞進你掌心，修修的蹭來蹭去：超級咪到！", "莫莫立刻把头顶塞进你掌心，修修的蹭来蹭去：超级咪到！"],
            ["莫莫原地翻出軟呼呼肚皮：哇啊，左邊也要摸一下才不吃醋奧！", "莫莫原地翻出软呼呼肚皮：哇啊，左边也要摸一下才不吃醋奥！"],
        ],
        b"kibble" => [
            ["咔滋咔滋！兩隻貓頭碰頭搶吃脆貓豆豆，連碎屑都舔得嘎嘎固。", "咔滋咔滋！两只猫头碰头抢吃脆猫豆豆，连碎屑都舔得嘎嘎固。"],
            ["撒下一把貓豆豆！莫莫衝第一，米米莫嘴裡含三顆：真香奧。", "撒下一把猫豆豆！莫莫冲第一，米米莫嘴里含三颗：真香奥。"],
        ],
        b"sweet_corn" => [
            ["熱甜玉米剛端出來，米米莫瞳孔瞬間放大成珍奶圓球：嘛啊，比魚香一萬倍！", "热甜玉米刚端出来，米米莫瞳孔瞬间放大成珍奶圆球：嘛啊，比鱼香一万倍！"],
            ["奧哇！黃金甜玉米粒甜呼呼，米米莫抱著玉米棒直接開起呼嚕馬達。", "奥哇！黄金甜玉米粒甜呼呼，米米莫抱着玉米棒直接开起呼噜马达。"],
        ],
        b"bottle_cap" => [
            ["瓶蓋叮噹一彈！米米莫空中神手接住，火速叼去沙發底下存起來。", "瓶盖叮当一弹！米米莫空中神手接住，火速叼去沙发底下存起来。"],
            ["亮晶晶瓶蓋滾過地板，兩隻貓同步起跳撲空又互相看傻眼：wer！", "亮晶晶瓶盖滚过地板，两只猫同步起跳扑空又互相看傻眼：wer！"],
        ],
        b"cardboard_box" => [
            ["沙沙——紙箱裡探出兩隻黑耳朵，抓到你啦小紙箱怪！米米莫一臉不米到。", "沙沙——纸箱里探出两只黑耳朵，抓到你啦小纸箱怪！米米莫一脸不米到。"],
            ["莫莫跳進紙箱假裝沒人，尾巴卻露在外面晃呀晃：哇啊被你米到了！", "莫莫跳进纸箱假装没人，尾巴却露在外面晃呀晃：哇啊被你米到了！"],
        ],
        b"laser_dot" => [
            ["紅點一閃！米米莫珍奶瞳蓄力起跑，15 秒自動急煞滑步停在牆邊。", "红点一闪！米米莫珍奶瞳蓄力起跑，15 秒自动急刹滑步停在墙边。"],
            ["紅點滑到牆角收回啦，米米莫撲空低頭看肉球：wer，這東西不講武德。", "红点滑到墙角收回啦，米米莫扑空低头看肉球：wer，这东西不讲武德。"],
        ],
        _ => [
            ["嘟嘟發車！莫莫穩穩坐進泡麵盒專車，探頭向你招小爪：超米到！", "嘟嘟发车！莫莫稳稳坐进泡面盒专车，探头向你招小爪：超米到！"],
            ["米米莫硬塞進泡麵盒把紙邊撐圓了一圈，莫莫在旁邊笑得嘎嘎固。", "米米莫硬塞进泡面盒把纸边撑圆了一圈，莫莫在旁边笑得嘎嘎固。"],
        ],
    };
    pair[idx]
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StoryView {
    pub id: &'static str,
    pub cat: &'static str,
    pub level: u8,
    pub title: [&'static str; 2],
    pub unlocked: bool,
    pub body: Option<[&'static str; 2]>,
}

#[must_use]
pub fn bond_stories(bond_mimimo: u8, bond_momo: u8) -> Vec<StoryView> {
    STORIES
        .iter()
        .map(|s| {
            let level = if s.cat == "mimimo" { bond_mimimo } else { bond_momo };
            let unlocked = level >= s.level;
            StoryView { id: s.id, cat: s.cat, level: s.level, title: s.title, unlocked, body: unlocked.then_some(s.body) }
        })
        .collect()
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StarStoryView {
    pub id: &'static str,
    pub star: u32,
    pub title: [&'static str; 2],
    pub unlocked: bool,
    pub body: Option<[&'static str; 2]>,
}

#[must_use]
pub fn star_stories(paragon_level: u32) -> Vec<StarStoryView> {
    STAR_STORIES
        .iter()
        .map(|s| {
            let unlocked = paragon_level >= s.star;
            StarStoryView { id: s.id, star: s.star, title: s.title, unlocked, body: unlocked.then_some(s.body) }
        })
        .collect()
}
