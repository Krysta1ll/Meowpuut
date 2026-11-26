package com.krysta1ll.meowpuut.android.app.stats

/**
 * Exact 82-achievement catalog (80 Steam cross-platform + 2 local-only) aligned 1:1 with
 * `Engine/crates/meowpuut-pets/src/achievements_table.rs`.
 * Row order and bit indices (`0..81`) are frozen for `MPSY` sync compatibility.
 * Copy uses canonical names 「米米莫」 and 「莫莫」 with zero forbidden terms.
 */
object PetAchievementPlace {
    const val FIELD = 0
    const val SEA = 1
    const val STREET = 2
    const val BAMBOO = 3
    const val SNOW = 4
    const val MOON = 5
    const val FUJI = 6
    const val HONG_KONG = 7
    const val VOLCANO = 8
    const val DESERT = 9
    const val MAPLE = 10
    const val LUNAR = 11
    const val COUNT = 12
    const val ALL_MASK = (1 shl COUNT) - 1
}

sealed interface PetAchievementCondition {
    data class Chars(val count: ULong) : PetAchievementCondition
    data class Days(val count: UInt) : PetAchievementCondition
    data class PeakDay(val count: UInt) : PetAchievementCondition
    data class PeakDays1500(val count: UInt) : PetAchievementCondition
    data class SnowPeaks4000(val count: UInt) : PetAchievementCondition
    data class Place(val index: Int) : PetAchievementCondition
    data object AllPlaces : PetAchievementCondition
    data class Festivals(val count: UInt) : PetAchievementCondition
    data class MimimoPets(val count: ULong) : PetAchievementCondition
    data class MomoPets(val count: ULong) : PetAchievementCondition
    data class BothPets(val count: ULong) : PetAchievementCondition
    /** [who]: 0 = Mimimo, 1 = Momo, 2 = both. */
    data class BondLevel(val who: Int, val level: Int) : PetAchievementCondition
    data class Plays(val count: ULong) : PetAchievementCondition
    data class Falls(val count: ULong) : PetAchievementCondition
    data class Outings(val count: UInt) : PetAchievementCondition
    data class Purr(val count: UInt) : PetAchievementCondition
    data class Ride(val count: UInt) : PetAchievementCondition
    data class Caught(val count: UInt) : PetAchievementCondition
    data class Found(val count: UInt) : PetAchievementCondition
    data class Roof(val count: UInt) : PetAchievementCondition
    data class Steps(val count: ULong) : PetAchievementCondition
    data class Nose(val count: UInt) : PetAchievementCondition
    data object Moved : PetAchievementCondition
    data object Cuddle : PetAchievementCondition
    data object Outfit : PetAchievementCondition
    data class Scroll(val count: UInt) : PetAchievementCondition
    data object Master : PetAchievementCondition
    data object DawnDay : PetAchievementCondition
    data object LateNightDay : PetAchievementCondition
}

data class PetAchievementDef(
    val api: String,
    val zhHant: String,
    val zhHans: String,
    val descHant: String,
    val descHans: String,
    val condition: PetAchievementCondition,
    val hidden: Boolean = false,
    val steam: Boolean = true,
    val enabled: Boolean = true,
) {
    fun title(simplified: Boolean): String = if (simplified) zhHans else zhHant
    fun desc(simplified: Boolean): String = if (simplified) descHans else descHant
    val isHard: Boolean
        get() = descHant.contains("【難】") || descHant.contains("【極難】") ||
            descHant.contains("【傳說】") || descHant.contains("【隱藏・難】")
}

object PetAchievementsCatalog {
    /** Order MUST match `achievements_table.rs::TABLE` verbatim (bit `i` in `unlocked_bits` = `TABLE[i]`). */
    val TABLE: List<PetAchievementDef> = listOf(
        // Characters / Meow-Units (11)
        PetAchievementDef("CHARS_1", "哇啊，第一行字", "哇啊，第一行字", "哇啊！你的第一份陪伴落在這裡了，莫莫和米米莫都咪到。", "哇啊！你的第一份陪伴落在这里了，莫莫和米米莫都咪到。", PetAchievementCondition.Chars(1UL)),
        PetAchievementDef("CHARS_100", "一百字・試試貓豆豆", "一百字・试试猫豆豆", "累積一百喵字落地，米米莫湊過來聞了聞：脆的，可以。", "累积一百喵字落地，米米莫凑过来闻了闻：脆的，可以。", PetAchievementCondition.Chars(100UL)),
        PetAchievementDef("CHARS_1K", "攢夠一千顆貓豆豆", "攒够一千颗猫豆豆", "牠們陪你累積了一千喵字，米米莫說這叫一千顆脆貓豆豆。", "它们陪你累积了一千喵字，米米莫说这叫一千颗脆猫豆豆。", PetAchievementCondition.Chars(1_000UL)),
        PetAchievementDef("CHARS_10K", "一萬字，超米到", "一万字，超米到", "一萬喵字慢慢攢起來了，小爪爪都拍得超米到。", "一万喵字慢慢攒起来了，小爪爪都拍得超米到。", PetAchievementCondition.Chars(10_000UL)),
        PetAchievementDef("CHARS_50K", "五萬字・甜玉米滿碗", "五万字・甜玉米满碗", "五萬喵字了！米米莫說這份量抵得上一整鍋甜玉米。", "五万喵字了！米米莫说这份量抵得上一整锅甜玉米。", PetAchievementCondition.Chars(50_000UL)),
        PetAchievementDef("CHARS_100K", "十萬字，奧哇好長", "十万字，奥哇好长", "十萬喵字是一小段長路，夠米米莫藏一整排瓶蓋了。", "十万喵字是一小段长路，够米米莫藏一整排瓶盖了。", PetAchievementCondition.Chars(100_000UL)),
        PetAchievementDef("CHARS_500K", "五十萬字・米米莫傻眼", "五十万字・米米莫傻眼", "五十萬喵字了！連紙箱裡的米米莫都探出頭來：奧哇。", "五十万喵字了！连纸箱里的米米莫都探出头来：奥哇。", PetAchievementCondition.Chars(500_000UL)),
        PetAchievementDef("CHARS_1M", "一百萬字，超級咪到", "一百万字，超级咪到", "一百萬喵字一路都有牠們，雙爪搭著一起走，超級無敵咪到。", "一百万喵字一路都有它们，双爪搭着一起走，超级无敌咪到。", PetAchievementCondition.Chars(1_000_000UL)),
        PetAchievementDef("CHARS_2M", "兩百萬字・硬核貓豆豆山", "两百万字・硬核猫豆豆山", "【難】兩百萬喵字！小貓集市擺一整街都裝不完你們的足跡。", "【难】两百万喵字！小猫集市摆一整街都装不完你们的足迹。", PetAchievementCondition.Chars(2_000_000UL)),
        PetAchievementDef("CHARS_3M", "三百萬字・傳說級大咪到", "三百万字・传说级大咪到", "【極難】三百萬喵字！兩隻貓仰頭看到脖子酸，佩服得嘎嘎固。", "【极难】三百万喵字！两只猫仰头看到脖子酸，佩服得嘎嘎固。", PetAchievementCondition.Chars(3_000_000UL)),
        PetAchievementDef("CHARS_4M", "四百萬字・字海貓神", "四百万字・字海猫神", "【傳說】四百萬喵字！米米莫與莫莫直接封你為終極大咪到。", "【传说】四百万喵字！米米莫与莫莫直接封你为终极大咪到。", PetAchievementCondition.Chars(4_000_000UL)),
        // Days & Peaks (13)
        PetAchievementDef("DAYS_1", "第一座咪咪山", "第一座咪咪山", "有一天你們在一起，地上長出第一座小山，莫莫探頭喊哇啊。", "有一天你们在一起，地上长出第一座小山，莫莫探头喊哇啊。", PetAchievementCondition.Days(1u)),
        PetAchievementDef("DAYS_7", "七座小山・慢慢走", "七座小山・慢慢走", "七個在一起的日子，不必連著，小貓在七座山頭輪流曬肚皮。", "七个在一起的日子，不必连着，小猫在七座山头轮流晒肚皮。", PetAchievementCondition.Days(7u)),
        PetAchievementDef("DAYS_30", "三十座山・住熟了奧", "三十座山・住熟了奥", "三十個在一起的日子，不必連著，米米莫終於敢從紙箱裡走出來了。", "三十个在一起的日子，不必连着，米米莫终于敢从纸箱里走出来了。", PetAchievementCondition.Days(30u)),
        PetAchievementDef("DAYS_100", "一百座山，不急嘛", "一百座山，不急嘛", "一百個在一起的日子，不必連著，隔多久牠們都在，修修的也不催你。", "一百个在一起的日子，不必连着，隔多久它们都在，修修的也不催你。", PetAchievementCondition.Days(100u)),
        PetAchievementDef("DAYS_365", "三百六十五座山・四季咪到", "三百六十五座山・四季咪到", "【難】三百六十五個在一起的日子，不必連著，足夠陪牠們看遍春夏秋冬。", "【难】三百六十五个在一起的日子，不必连着，足够陪它们看遍春夏秋冬。", PetAchievementCondition.Days(365u)),
        PetAchievementDef("DAYS_1000", "一千座山・長長久久咪", "一千座山・长长久久咪", "【傳說】一千個在一起的日子！不必連著，一千座山連起來全是貓腳印。", "【传说】一千个在一起的日子！不必连着，一千座山连起来全是猫脚印。", PetAchievementCondition.Days(1_000u)),
        PetAchievementDef("DAYS_PEAK_1500", "哇，高山長出來了", "哇，高山长出来了", "某一天寫了一千五百字以上，莫莫爬上去看電視看呆了。", "某一天写了一千五百字以上，莫莫爬上去看电视看呆了。", PetAchievementCondition.PeakDay(1_500u)),
        PetAchievementDef("DAYS_PEAK_4000", "雪峰，wer 到最高點", "雪峰，wer 到最高点", "某一天寫了四千字以上，連米米莫都 wer 了一聲：這座好高。", "某一天写了四千字以上，连米米莫都 wer 了一声：这座好高。", PetAchievementCondition.PeakDay(4_000u)),
        PetAchievementDef("DAYS_PEAK_8000", "八千字巨峰・小爪冒煙", "八千字巨峰・小爪冒烟", "【難】某一天寫了八千字以上！莫莫問你的小爪是不是著火了。", "【难】某一天写了八千字以上！莫莫问你的小爪是不是着火了。", PetAchievementCondition.PeakDay(8_000u)),
        PetAchievementDef("DAYS_PEAK_15000", "一萬五千字・珠穆朗瑪貓峰", "一万五千字・珠穆朗玛猫峰", "【極難】某一天寫了一萬五千字以上！兩隻貓直接 wer 到說不出話。", "【极难】某一天写了一万五千字以上！两只猫直接 wer 到说不出话。", PetAchievementCondition.PeakDay(15_000u)),
        PetAchievementDef("PEAK_DAYS_10", "十座高山・常客咪", "十座高山・常客咪", "累積十天寫滿一千五百字以上，群山起伏超米到。", "累积十天写满一千五百字以上，群山起伏超米到。", PetAchievementCondition.PeakDays1500(10u)),
        PetAchievementDef("SNOW_PEAKS_10", "十座雪峰・冰原行者", "十座雪峰・冰原行者", "【難】累積十天寫滿四千字以上，雪峰排成一整條山脈。", "【难】累积十天写满四千字以上，雪峰排成一整条山脉。", PetAchievementCondition.SnowPeaks4000(10u)),
        PetAchievementDef("SNOW_PEAKS_50", "五十座雪峰・極寒貓王", "五十座雪峰・极寒猫王", "【極難】累積五十天寫滿四千字以上！小爪爪全凍成雪球也陪你。", "【极难】累积五十天写满四千字以上！小爪爪全冻成雪球也陪你。", PetAchievementCondition.SnowPeaks4000(50u)),
        // Places (13)
        PetAchievementDef("PLACE_FIELD", "遇見山野・踏草咪", "遇见山野・踏草咪", "走到了山野，草叢沙沙響，莫莫已經衝出去探險了。", "走到了山野，草丛沙沙响，莫莫已经冲出去探险了。", PetAchievementCondition.Place(PetAchievementPlace.FIELD)),
        PetAchievementDef("PLACE_SEA", "遇見海・莫莫咪到", "遇见海・莫莫咪到", "走到了海邊，莫莫盯著海問嘛啊，米米莫聞到魚味立刻 wer。", "走到了海边，莫莫盯着海问嘛啊，米米莫闻到鱼味立刻 wer。", PetAchievementCondition.Place(PetAchievementPlace.SEA)),
        PetAchievementDef("PLACE_STREET", "遇見街・逛小貓集市", "遇见街・逛小猫集市", "走到了街上，莫莫眼睛一亮：這就是傳說中的小貓集市奧？", "走到了街上，莫莫眼睛一亮：这就是传说中的小猫集市奥？", PetAchievementCondition.Place(PetAchievementPlace.STREET)),
        PetAchievementDef("PLACE_BAMBOO", "遇見竹林・沙沙咪", "遇见竹林・沙沙咪", "走進了竹林，風吹葉響，米米莫覺得跟躲紙箱一樣安心。", "走进了竹林，风吹叶响，米米莫觉得跟躲纸箱一样安心。", PetAchievementCondition.Place(PetAchievementPlace.BAMBOO)),
        PetAchievementDef("PLACE_SNOW", "遇見雪山・小爪冰冰", "遇见雪山・小爪冰冰", "走到了雪山，踩起來咯吱咯吱，小爪爪都有點修修的。", "走到了雪山，踩起来咯吱咯吱，小爪爪都有点修修的。", PetAchievementCondition.Place(PetAchievementPlace.SNOW)),
        PetAchievementDef("PLACE_FUJI", "遇見富士・看電視", "遇见富士・看电视", "走到了富士山下，兩隻貓並排搭爪看山，當成超大台電視。", "走到了富士山下，两只猫并排搭爪看山，当成超大台电视。", PetAchievementCondition.Place(PetAchievementPlace.FUJI)),
        PetAchievementDef("PLACE_HONG_KONG", "遇見香港・奧哇好亮", "遇见香港・奥哇好亮", "走到了香港，街燈亮晶晶，莫莫好奇得一直探頭看。", "走到了香港，街灯亮晶晶，莫莫好奇得一直探头看。", PetAchievementCondition.Place(PetAchievementPlace.HONG_KONG)),
        PetAchievementDef("PLACE_VOLCANO", "遇見火山・肚皮暖暖", "遇见火山・肚皮暖暖", "走到了火山，米米莫說這兒暖呼呼的，像剛煮好的甜玉米。", "走到了火山，米米莫说这儿暖呼呼的，像刚煮好的甜玉米。", PetAchievementCondition.Place(PetAchievementPlace.VOLCANO)),
        PetAchievementDef("PLACE_DESERT", "遇見沙漠・超大貓砂盆", "遇见沙漠・超大猫砂盆", "走進了沙漠，米米莫看傻了眼：這、這也太大一盆了吧，wer。", "走进了沙漠，米米莫看傻了眼：这、这也太大一盆了吧，wer。", PetAchievementCondition.Place(PetAchievementPlace.DESERT)),
        PetAchievementDef("PLACE_MAPLE", "遇見紅葉・撲葉咪", "遇见红叶・扑叶咪", "走進了紅葉裡，飄下來的每一片都要被小爪拍兩下。", "走进了红叶里，飘下来的每一片都要被小爪拍两下。", PetAchievementCondition.Place(PetAchievementPlace.MAPLE)),
        PetAchievementDef("PLACE_MOON", "遇見月・偷偷咪到", "遇见月・偷偷咪到", "夜裡抬頭遇見了月，兩隻貓圓滾滾的眼睛亮了一下。", "夜里抬头遇见了月，两只猫圆滚滚的眼睛亮了一下。", PetAchievementCondition.Place(PetAchievementPlace.MOON), hidden = true),
        PetAchievementDef("PLACE_LUNAR", "遇見月球・登月嘎嘎固", "遇见月球・登月嘎嘎固", "走到了月球上！米米莫在無重力裡漂起來，一臉不米到。", "走到了月球上！米米莫在无重力里漂起来，一脸不米到。", PetAchievementCondition.Place(PetAchievementPlace.LUNAR), hidden = true),
        PetAchievementDef("PLACES_12", "十二個地方・爪印全咪到", "十二个地方・爪印全咪到", "十二個地方都留下了雙貓的小腳印，兩隻貓走遍大世界。", "十二个地方都留下了双猫的小脚印，两只猫走遍大世界。", PetAchievementCondition.AllPlaces),
        // Festivals (4), never named
        PetAchievementDef("FEST_1", "第一個節日・嘛啊過節", "第一个节日・嘛啊过节", "遇見了第一個節日，莫莫開心得轉圈圈，問今天吃咪了沒。", "遇见了第一个节日，莫莫开心得转圈圈，问今天吃咪了没。", PetAchievementCondition.Festivals(1u)),
        PetAchievementDef("FEST_12", "十二個節日・年年咪到", "十二个节日・年年咪到", "遇見了十二個節日，哪一個、哪一年都算，年年都想跟你過。", "遇见了十二个节日，哪一个、哪一年都算，年年都想跟你过。", PetAchievementCondition.Festivals(12u)),
        PetAchievementDef("FEST_36", "三十六個節日・過節達人咪", "三十六个节日・过节达人咪", "【難】遇見了三十六個不同的節日！莫莫把每一個都記進小本本。", "【难】遇见了三十六个不同的节日！莫莫把每一个都记进小本本。", PetAchievementCondition.Festivals(36u)),
        PetAchievementDef("FEST_100", "一百個節日・世界大巡遊", "一百个节日・世界大巡游", "【極難】遇見了一百個不同的節日！哪一個哪一年都算，超級咪到。", "【极难】遇见了一百个不同的节日！哪一个哪一年都算，超级咪到。", PetAchievementCondition.Festivals(100u)),
        // Two cats (15)
        PetAchievementDef("PET_MIMIMO_10", "輕輕摸摸米米莫", "轻轻摸摸米米莫", "摸了米米莫十下，他還沒逃回紙箱，算你過關。", "摸了米米莫十下，他还没逃回纸箱，算你过关。", PetAchievementCondition.MimimoPets(10UL)),
        PetAchievementDef("PET_MOMO_10", "輕輕摸摸莫莫", "轻轻摸摸莫莫", "摸了莫莫十下，她馬上修修的把頭頂湊進你手心。", "摸了莫莫十下，她马上修修的把头顶凑进你手心。", PetAchievementCondition.MomoPets(10UL)),
        PetAchievementDef("PET_MIMIMO_100", "摸摸米米莫一百下", "摸摸米米莫一百下", "摸了米米莫一百下！嘴上說別盯著我看，肚皮其實超米到。", "摸了米米莫一百下！嘴上说别盯着我看，肚皮其实超米到。", PetAchievementCondition.MimimoPets(100UL)),
        PetAchievementDef("PET_MOMO_100", "摸摸莫莫一百下", "摸摸莫莫一百下", "摸了莫莫一百下！修修的靠過來把肚皮翻給你，咪到不行。", "摸了莫莫一百下！修修的靠过来把肚皮翻给你，咪到不行。", PetAchievementCondition.MomoPets(100UL)),
        PetAchievementDef("PET_BOTH_100", "兩隻貓都摸到咪到", "两只猫都摸到咪到", "兩隻貓各摸了一百下，莫莫不再吃醋有點修，兩邊都公平奧。", "两只猫各摸了一百下，莫莫不再吃醋有点修，两边都公平奥。", PetAchievementCondition.BothPets(100UL)),
        PetAchievementDef("PET_MIMIMO_1K", "狂嚕米米莫一千下", "狂噜米米莫一千下", "【難】摸了米米莫一千下！黑毛被你摸得反光發亮，他心裡超米到。", "【难】摸了米米莫一千下！黑毛被你摸得反光发亮，他心里超米到。", PetAchievementCondition.MimimoPets(1_000UL)),
        PetAchievementDef("PET_MOMO_1K", "狂嚕莫莫一千下", "狂噜莫莫一千下", "【難】摸了莫莫一千下！莫莫已經徹底黏在你手心上不下來了。", "【难】摸了莫莫一千下！莫莫已经彻底黏在你手心上不下来了。", PetAchievementCondition.MomoPets(1_000UL)),
        PetAchievementDef("PET_BOTH_1K", "左右開弓各一千下", "左右开弓各一千下", "【難】兩隻貓各摸了一千下！左邊摸米米莫肚皮、右邊摸莫莫肚皮，端水大師。", "【难】两只猫各摸了一千下！左边摸米米莫肚皮、右边摸莫莫肚皮，端水大师。", PetAchievementCondition.BothPets(1_000UL)),
        PetAchievementDef("PET_BOTH_5K", "嚕貓宗師・雙五千", "噜猫宗师・双五千", "【傳說】兩隻貓各摸了五千下！兩坨黏土貓快被你嚕成圓球了。", "【传说】两只猫各摸了五千下！两坨黏土猫快被你噜成圆球了。", PetAchievementCondition.BothPets(5_000UL)),
        PetAchievementDef("BOND_MIMIMO_MAX", "米米莫滿級・只跟你好", "米米莫满级・只跟你好", "米米莫好感度達到 100 滿級！連藏起來的瓶蓋都分你一半。", "米米莫好感度达到 100 满级！连藏起来的瓶盖都分你一半。", PetAchievementCondition.BondLevel(0, 100)),
        PetAchievementDef("BOND_MOMO_MAX", "莫莫滿級・黏糊糊", "莫莫满级・黏糊糊", "莫莫好感度達到 100 滿級！小貓集市最可愛的寶貝全留給你。", "莫莫好感度达到 100 满级！小猫集市最可爱的宝贝全留给你。", PetAchievementCondition.BondLevel(1, 100)),
        PetAchievementDef("BOND_BOTH_MAX", "雙貓滿級・帽帽認證", "双猫满级・帽帽认证", "米米莫與莫莫雙雙好感滿級 100！一家貓全體超級咪到。", "米米莫与莫莫双双好感满级 100！一家猫全体超级咪到。", PetAchievementCondition.BondLevel(2, 100)),
        PetAchievementDef("PLAYS_100", "陪貓玩耍一百回", "陪猫玩耍一百回", "跟兩隻貓互動玩耍累積一百次，家裡永遠熱熱鬧鬧的。", "跟两只猫互动玩耍累积一百次，家里永远热热闹闹的。", PetAchievementCondition.Plays(100UL)),
        PetAchievementDef("PLAYS_1K", "超級鏟屎官・陪玩一千回", "超级铲屎官・陪玩一千回", "【難】跟兩隻貓互動玩耍累積一千次！莫莫說你比小魚乾還好玩。", "【难】跟两只猫互动玩耍累积一千次！莫莫说你比小鱼干还好玩。", PetAchievementCondition.Plays(1_000UL)),
        PetAchievementDef("FALLS_50", "哎呀又掉下去・五十連摔", "哎呀又掉下去・五十连摔", "【隱藏・難】看著兩隻貓摔下或跳下五十次，米米莫一臉 wer 到不行。", "【隐藏・难】看着两只猫摔下或跳下五十次，米米莫一脸 wer 到不行。", PetAchievementCondition.Falls(50UL), hidden = true),
        // Desktop & interactive cats (21)
        PetAchievementDef("CAT_OUT", "出門啦，哇啊", "出门啦，哇啊", "牠們第一次走出門來到桌面上，莫莫衝第一，米米莫在後面偷看。", "它们第一次走出门来到桌面上，莫莫冲第一，米米莫在后面偷看。", PetAchievementCondition.Outings(1u)),
        PetAchievementDef("CAT_OUT_100", "百次出門・桌上老街坊", "百次出门・桌上老街坊", "牠們走出門來到桌面一百次，整個螢幕都是牠們的巡邏領地。", "它们走出门来到桌面一百次，整个屏幕都是它们的巡逻领地。", PetAchievementCondition.Outings(100u)),
        PetAchievementDef("CAT_PURR", "呼嚕呼嚕・超咪到", "呼噜呼噜・超咪到", "聽見了牠的呼嚕聲，馬達開起來，舒服到整個貓都化掉了。", "听见了它的呼噜声，马达开起来，舒服到整个猫都化掉了。", PetAchievementCondition.Purr(1u)),
        PetAchievementDef("CAT_PURR_100", "呼嚕交響樂・一百回", "呼噜交响乐・一百回", "【難】聽見牠們呼嚕了一百次，整張桌子都在微微震動。", "【难】听见它们呼噜了一百次，整张桌子都在微微震动。", PetAchievementCondition.Purr(100u)),
        PetAchievementDef("CAT_RIDE", "坐順風車・像坐泡麵盒", "坐顺风车・像坐泡面盒", "牠搭著視窗邊緣一起滑去別處，莫莫說這跟坐泡麵盒一樣晃。", "它搭着窗口边缘一起滑去别处，莫莫说这跟坐泡面盒一样晃。", PetAchievementCondition.Ride(1u)),
        PetAchievementDef("CAT_RIDE_50", "老司機順風車・五十趟", "老司机顺风车・五十趟", "【難】載著貓搭視窗順風車五十趟！莫莫坐得比泡麵盒還穩。", "【难】载着猫搭窗口顺风车五十趟！莫莫坐得比泡面盒还稳。", PetAchievementCondition.Ride(50u)),
        PetAchievementDef("CAT_CAUGHT", "接住啦，沒吃修", "接住啦，没吃修", "牠落下來的時候被你穩穩接住了，米米莫鬆了口氣：好險沒吃修。", "它落下来的时候被你稳稳接住了，米米莫松了口气：好险没吃修。", PetAchievementCondition.Caught(1u)),
        PetAchievementDef("CAT_CAUGHT_25", "神手接貓・二十五連救", "神手接猫・二十五连救", "【難】空中穩穩接住落下的貓二十五次！米米莫：這反應太米到。", "【难】空中稳稳接住落下的猫二十五次！米米莫：这反应太米到。", PetAchievementCondition.Caught(25u)),
        PetAchievementDef("CAT_FOUND", "抓到你了，小紙箱怪", "抓到你了，小纸箱怪", "牠躲起來偷藏瓶蓋，還是被你一眼找到啦。", "它躲起来偷藏瓶盖，还是被你一眼找到啦。", PetAchievementCondition.Found(1u), hidden = true),
        PetAchievementDef("CAT_FOUND_25", "躲貓貓冠軍・抓二十五次", "躲猫猫冠军・抓二十五次", "【隱藏・難】把躲起來的小貓抓出來二十五次，藏在哪都被你米到。", "【隐藏・难】把躲起来的小猫抓出来二十五次，藏在哪都被你米到。", PetAchievementCondition.Found(25u), hidden = true),
        PetAchievementDef("CAT_ROOF", "走上屋頂・搭爪看電視", "走上屋顶・搭爪看电视", "牠第一次站上視窗上緣，搭著小爪居高臨下看電視。", "它第一次站上窗口上缘，搭着小爪居高临下看电视。", PetAchievementCondition.Roof(1u)),
        PetAchievementDef("CAT_ROOF_100", "屋頂追劇王・一百回", "屋顶追剧王・一百回", "【難】牠們站上視窗上緣看電視一百次，每一扇窗都是 VIP 座位。", "【难】它们站上窗口上缘看电视一百次，每一扇窗都是 VIP 座位。", PetAchievementCondition.Roof(100u)),
        PetAchievementDef("CAT_STEPS_1K", "踏踏陪走一千步", "踏踏陪走一千步", "牠沿著螢幕邊緣陪你走了一千步，小碎步踏得嘎嘎固。", "它沿着屏幕边缘陪你走了一千步，小碎步踏得嘎嘎固。", PetAchievementCondition.Steps(1_000UL)),
        PetAchievementDef("CAT_STEPS_10K", "散步一萬步・不累奧", "散步一万步・不累奥", "牠陪你走了一萬步，小爪爪踩出了一條看不見的小路。", "它陪你走了一万步，小爪爪踩出了一条看不见的小路。", PetAchievementCondition.Steps(10_000UL)),
        PetAchievementDef("CAT_STEPS_100K", "十萬步長征・鐵腿咪", "十万步长征・铁腿咪", "【難】牠陪你走了十萬步！米米莫說這距離夠走去小貓集市十趟。", "【难】它陪你走了十万步！米米莫说这距离够走去小猫集市十趟。", PetAchievementCondition.Steps(100_000UL)),
        PetAchievementDef("CAT_STEPS_1M", "百萬步環遊・傳說肉球", "百万步环游・传说肉球", "【傳說】牠陪你走了一百萬步！小小肉球踏遍整個螢幕宇宙。", "【传说】它陪你走了一百万步！小小肉球踏遍整个屏幕宇宙。", PetAchievementCondition.Steps(1_000_000UL)),
        PetAchievementDef("CAT_NOSE", "碰碰鼻子・修修的", "碰碰鼻子・修修的", "和牠輕輕碰了碰鼻子，莫莫修修的蹭了蹭，米米莫假裝沒害羞。", "和它轻轻碰了碰鼻子，莫莫修修的蹭了蹭，米米莫假装没害羞。", PetAchievementCondition.Nose(1u)),
        PetAchievementDef("CAT_NOSE_50", "暖暖貼鼻鼻・五十下", "暖暖贴鼻鼻・五十下", "【難】和牠們輕輕碰鼻子五十次，莫莫蹭得鼻頭都粉呼呼的。", "【难】和它们轻轻碰鼻子五十次，莫莫蹭得鼻头都粉呼呼的。", PetAchievementCondition.Nose(50u)),
        PetAchievementDef("CAT_MOVED", "搬新家・換紙箱住", "搬新家・换纸箱住", "牠換了一個更舒服的地方窩下來，米米莫表示這紙箱我米到。", "它换了一个更舒服的地方窝下来，米米莫表示这纸箱我米到。", PetAchievementCondition.Moved, hidden = true),
        PetAchievementDef("CAT_CUDDLE", "貼貼一起睡・暖呼呼", "贴贴一起睡・暖呼呼", "兩隻貓挨著捲成一團睡著了，難得沒打架，超級咪到。", "两只猫挨着卷成一团睡着了，难得没打架，超级咪到。", PetAchievementCondition.Cuddle, hidden = false, steam = true, enabled = false),
        PetAchievementDef("CAT_OUTFIT", "穿上過節新衣服・奧哇", "穿上过节新衣服・奥哇", "第一次讓牠穿上節日的衣服，莫莫馬上轉身給大家看，修修的。", "第一次让它穿上节日的衣服，莫莫马上转身给大家看，修修的。", PetAchievementCondition.Outfit),
        // Scroll & Grandmaster (3)
        PetAchievementDef("SCROLL_SHARE", "截一段・留給貓看", "截一段・留给猫看", "把長卷的一段裁下來珍藏，兩隻貓湊過來一起點點頭：米到。", "把长卷的一段裁下来珍藏，两只猫凑过来一起点点头：米到。", PetAchievementCondition.Scroll(1u)),
        PetAchievementDef("SCROLL_SHARE_10", "長卷收藏家・截十段", "长卷收藏家・截十段", "把長卷截了十段珍藏起來，拚在一起就是一本小貓旅行相簿。", "把长卷截了十段珍藏起来，拼在一起就是一本小猫旅行相簿。", PetAchievementCondition.Scroll(10u)),
        PetAchievementDef("MASTER_MEOWPUUT", "喵撲大滿貫・終極咪到", "喵扑大满贯・终极咪到", "【傳說】一百萬喵字、一百座山、十二個地方加上雙貓好感雙滿級，你是無敵大咪到！", "【传说】一百万喵字、一百座山、十二个地方加上双猫好感双满级，你是无敌大咪到！", PetAchievementCondition.Master, hidden = true),
        // Local only (2)
        PetAchievementDef("LOCAL_DAWN", "清晨的字・嘛啊早安", "清晨的字・嘛啊早安", "清晨就陪伴在一起，莫莫剛睡醒喊哇啊，米米莫還在等水龍頭滴水。", "清晨就陪伴在一起，莫莫刚睡醒喊哇啊，米米莫还在等水龙头滴水。", PetAchievementCondition.DawnDay, hidden = false, steam = false, enabled = true),
        PetAchievementDef("LOCAL_LATE_NIGHT", "深夜的字・貓頭鷹咪", "深夜的字・猫头鹰咪", "深夜還在陪伴在一起，黑嚕嚕的米米莫只剩兩顆圓眼睛陪著你。", "深夜还在陪伴在一起，黑噜噜的米米莫只剩两颗圆眼睛陪着你。", PetAchievementCondition.LateNightDay, hidden = false, steam = false, enabled = true),
    )

    val INDEX_BY_API: Map<String, Int> = TABLE.withIndex().associate { (i, d) -> d.api to i }
}
