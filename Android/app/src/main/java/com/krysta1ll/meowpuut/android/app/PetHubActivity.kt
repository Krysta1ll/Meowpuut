package com.krysta1ll.meowpuut.android.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.app.stats.BundledFestivals
import com.krysta1ll.meowpuut.android.app.stats.PetAchievementStatus
import com.krysta1ll.meowpuut.android.app.stats.PetAchievementsCatalog
import com.krysta1ll.meowpuut.android.app.stats.PetAchievementsEvaluator
import com.krysta1ll.meowpuut.android.app.stats.PetMobileEvent
import com.krysta1ll.meowpuut.android.app.stats.PetPassCode
import com.krysta1ll.meowpuut.android.app.stats.PetSyncDecodeResult
import com.krysta1ll.meowpuut.android.app.stats.PetSyncEnvelope
import com.krysta1ll.meowpuut.android.app.stats.PetSyncStore
import com.krysta1ll.meowpuut.android.app.stats.StatsStore
import com.krysta1ll.meowpuut.android.ime.Prefs
import kotlin.math.roundToInt

/**
 * Pet achievements (all 82 咪咪貓貓話 achievements) and Route A cross-platform `.mpsync`
 * sync hub (3,072-byte CRDT envelope + Crockford Base32 anonymous pass code `MEOW-...`).
 */
class PetHubActivity : Activity() {
    private enum class Filter { ALL, UNLOCKED, LOCKED, HARD }

    private lateinit var prefs: Prefs
    private lateinit var ui: PaperUi
    private lateinit var syncStore: PetSyncStore
    private lateinit var statsStore: StatsStore
    private lateinit var column: LinearLayout
    private var envelope = PetSyncEnvelope()
    private var passCode = ""
    private var filter = Filter.ALL
    private var notice: String? = null

    private val density get() = resources.displayMetrics.density
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val simplified: Boolean
        get() {
            val locale = resources.configuration.locales[0]
            if (locale.language != "zh") return false
            if (locale.script == "Hans") return true
            return locale.script != "Hant" && locale.country !in setOf("TW", "HK", "MO")
        }

    private fun t(hant: String, hans: String) = if (simplified) hans else hant
    private fun dp(v: Int) = (v * density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = PaperUi(this, AppSkins.worn(prefs, dark), dark)
        syncStore = PetSyncStore(filesDir)
        statsStore = StatsStore(this, BundledFestivals.of(this))
        column = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(MeasureSpec.getSize(widthMeasureSpec), dp(680))
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(32))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = ui.background()
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        }
        setContentView(scroll)
        title = getString(R.string.pet_hub_title)
        reload()
    }

    override fun onDestroy() {
        statsStore.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> {
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use { it.write(envelope.toBytes()) } != null
                } catch (_: Exception) { false }
                notice = if (ok) t("已匯出 3,072 位元組 .mpsync 同步檔。", "已导出 3,072 字节 .mpsync 同步文件。")
                else t("匯出失敗，請再試一次。", "导出失败，请再试一次。")
                render()
            }
            REQ_IMPORT -> {
                val bytes = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
                if (bytes == null) {
                    notice = t("無法讀取所選檔案。", "无法读取所选文件。")
                    render()
                    return
                }
                when (val res = syncStore.importBytes(bytes)) {
                    is PetSyncDecodeResult.Ok -> {
                        envelope = res.envelope
                        notice = t("CRDT 合併成功！目前已解鎖 ${envelope.unlockedApis().size} 個成就。",
                            "CRDT 合并成功！目前已解锁 ${envelope.unlockedApis().size} 个成就。")
                    }
                    else -> notice = t("匯入失敗：檔案格式或校驗碼無效。", "导入失败：文件格式或校验码无效。")
                }
                render()
            }
        }
    }

    private fun reload() {
        val festivalsCount = try { statsStore.refresh().festivals.size } catch (_: Exception) { 0 }
        passCode = syncStore.loadOrCreatePassCode()
        envelope = syncStore.snapshot(festivalsCount)
        render()
    }

    private fun render() {
        column.removeAllViews()
        val statuses = PetAchievementsEvaluator.statuses(envelope.state, envelope.progress, envelope.unlockedBits)
        val unlockedCount = statuses.count { it.unlocked }

        // Header
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(actionButton("‹ " + t("返回", "返回")) { finish() })
        head.addView(label(t("寵物成就與跨平台同步", "宠物成就与跨平台同步"), 20f, bold = true).apply { setPadding(dp(10), 0, 0, 0) })
        column.addView(head)

        notice?.let { msg ->
            column.addView(card().apply { addView(label(msg, 13f, secondary = true)) }, topMargin(10))
        }

        // Cats growth & quick pet card
        val snap = envelope.state.snapshot
        column.addView(card().apply {
            addView(label(t("兩隻貓的成長", "两只猫的成长"), 16f, bold = true))
            addView(label(
                t("米米莫 Lv.${snap.bondLevel(true)}（摸摸 ${snap.mimimo.pets} · 互動 ${snap.mimimo.plays}）\n" +
                    "莫莫 Lv.${snap.bondLevel(false)}（摸摸 ${snap.momo.pets} · 互動 ${snap.momo.plays}）\n" +
                    "累積字數：${snap.committedGraphemes} · 已解鎖：$unlockedCount / ${statuses.size}",
                    "米米莫 Lv.${snap.bondLevel(true)}（摸摸 ${snap.mimimo.pets} · 互动 ${snap.mimimo.plays}）\n" +
                        "莫莫 Lv.${snap.bondLevel(false)}（摸摸 ${snap.momo.pets} · 互动 ${snap.momo.plays}）\n" +
                        "累积字数：${snap.committedGraphemes} · 已解锁：$unlockedCount / ${statuses.size}"),
                14f, secondary = true,
            ).apply { setPadding(0, dp(6), 0, dp(8)) })
            val row = LinearLayout(this@PetHubActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(actionButton(t("摸摸米米莫", "摸摸米米莫")) {
                envelope = syncStore.record(PetMobileEvent.Pet(true))
                render()
            }, weighted())
            row.addView(actionButton(t("摸摸莫莫", "摸摸莫莫")) {
                envelope = syncStore.record(PetMobileEvent.Pet(false))
                render()
            }, weighted())
            addView(row)
        }, topMargin(12))

        // Route A sync card
        column.addView(card().apply {
            addView(label(t("Route A 跨平台同步（MPSY v1 · 3,072 B）", "Route A 跨平台同步（MPSY v1 · 3,072 B）"), 16f, bold = true))
            addView(label(passCode, 12f, mono = true).apply { setPadding(0, dp(6), 0, dp(4)) })
            addView(label(t("紀元 #${envelope.state.epoch} · 零帳號、零個人資料，與 PC Steam / iOS 位元組級相容。",
                "纪元 #${envelope.state.epoch} · 零账号、零个人资料，与 PC Steam / iOS 字节级兼容。"), 12f, secondary = true))
            val passRow = LinearLayout(this@PetHubActivity).apply { orientation = LinearLayout.HORIZONTAL }
            passRow.addView(actionButton(t("複製通行碼", "复制通行码")) {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Meowpuut Pass", passCode))
                notice = t("已複製匿名通行碼。", "已复制匿名通行码。")
                render()
            }, weighted())
            passRow.addView(actionButton(t("輸入通行碼", "输入通行码")) { promptPassCode() }, weighted())
            addView(passRow, topMargin(8))

            val fileRow = LinearLayout(this@PetHubActivity).apply { orientation = LinearLayout.HORIZONTAL }
            fileRow.addView(actionButton(t("匯出 .mpsync", "导出 .mpsync")) { launchExport() }, weighted())
            fileRow.addView(actionButton(t("匯入 .mpsync", "导入 .mpsync")) { launchImport() }, weighted())
            addView(fileRow, topMargin(6))

            addView(actionButton(t("清空並重設紀元", "清空并重设纪元")) { confirmReset() }, topMargin(6))
        }, topMargin(12))

        // Filter row
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val filters = listOf(
            Filter.ALL to t("全部", "全部"),
            Filter.UNLOCKED to t("已解鎖", "已解锁"),
            Filter.LOCKED to t("未解鎖", "未解锁"),
            Filter.HARD to t("挑戰", "挑战"),
        )
        for ((f, text) in filters) {
            filterRow.addView(actionButton(if (filter == f) "● $text" else text) { filter = f; render() }, weighted())
        }
        column.addView(filterRow, topMargin(14))

        // Achievement list
        val shown = when (filter) {
            Filter.ALL -> statuses
            Filter.UNLOCKED -> statuses.filter { it.unlocked }
            Filter.LOCKED -> statuses.filter { !it.unlocked }
            Filter.HARD -> statuses.filter { it.def.isHard }
        }
        for (item in shown) {
            column.addView(achievementCard(item), topMargin(8))
        }
    }

    private fun achievementCard(item: PetAchievementStatus): View {
        val masked = item.def.hidden && !item.unlocked
        val heading = (if (item.unlocked) "✓ " else "○ ") +
            (if (masked) t("隱藏成就", "隐藏成就") else item.def.title(simplified)) +
            (if (item.def.isHard) t(" [挑戰]", " [挑战]") else "") +
            (if (!item.def.steam) t(" [本機]", " [本机]") else "")
        val body = if (masked) t("繼續陪伴米米莫與莫莫，就會悄悄咪到。", "继续陪伴米米莫与莫莫，就会悄悄咪到。")
        else item.def.desc(simplified)
        return card().apply {
            addView(label(heading, 14f, bold = true))
            addView(label(body, 12f, secondary = true).apply { setPadding(0, dp(3), 0, dp(3)) })
            if (!masked && item.def.enabled) {
                addView(label("${item.current} / ${item.target}", 11f, secondary = true, mono = true))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun launchExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "MeowpuutPets.${PetSyncEnvelope.FILE_EXTENSION}")
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    @Suppress("DEPRECATION")
    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    private fun promptPassCode() {
        val input = EditText(this).apply {
            hint = "MEOW-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX"
            typeface = Typeface.MONOSPACE
            setText(passCode)
        }
        AlertDialog.Builder(this)
            .setTitle(t("套用匿名通行碼", "应用匿名通行码"))
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString().orEmpty()
                val key = PetPassCode.decode(raw)
                if (key != null && syncStore.savePassCode(PetPassCode.encode(key))) {
                    notice = t("通行碼校驗通過並已套用。", "通行码校验通过并已应用。")
                    reload()
                } else {
                    notice = t("通行碼格式或校驗碼錯誤。", "通行码格式或校验码错误。")
                    render()
                }
            }
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(t("重設同步紀元？", "重设同步纪元？"))
            .setMessage(t("將清空本機紀錄並提升同步紀元編號，與其他裝置合併時會同步清空舊紀元紀錄。",
                "将清空本机记录并提升同步纪元编号，与其他设备合并时会同步清空旧纪元记录。"))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_pets) { _, _ ->
                envelope = syncStore.resetEpoch()
                statsStore.forget()
                prefs.store.edit().putLong(Prefs.CLEAR_PETS, System.nanoTime()).apply()
                notice = t("已清空並將紀元升至 #${envelope.state.epoch}。", "已清空并将纪元升至 #${envelope.state.epoch}。")
                render()
            }
            .show()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(ui.p.paper2)
            setStroke(dp(1), ui.line)
        }
    }

    private fun label(text: String, sp: Float, bold: Boolean = false, secondary: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(if (secondary) ui.p.inkSecondary else ui.p.ink)
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
        }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setOnClickListener { onClick() }
    }

    private fun topMargin(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(v) }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    companion object {
        private const val REQ_EXPORT = 4101
        private const val REQ_IMPORT = 4102
    }
}
