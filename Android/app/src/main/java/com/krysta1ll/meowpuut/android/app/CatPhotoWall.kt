package com.krysta1ll.meowpuut.android.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.app.home.Brush
import com.krysta1ll.meowpuut.android.app.home.Mosaic
import com.krysta1ll.meowpuut.android.app.home.PictureWorker
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Paper-themed cat photo showcase for `SettingsActivity`: displays today's
 * hero photo first, followed by a scrollable strip of all daily-rotated
 * photos and a full-screen viewer dialog.
 */
@SuppressLint("ViewConstructor")
internal class CatPhotoWall(
    context: Context,
    private val ui: PaperUi,
    private val store: CatPhotoStore,
    private val brush: Brush,
) : LinearLayout(context) {
    private val typeFirst = ui.skin.slots.photos == PhotoStyle.TYPE_FIRST
    private val density = context.resources.displayMetrics.density
    private val subtitleView: TextView
    private val refreshButton: Button
    private val contentHost: FrameLayout
    private var photos: List<CatPhoto> = emptyList()
    private var lastDayKey: ULong = currentDayKey()
    private var lastFetchMs: Long = 0L

    init {
        orientation = VERTICAL
        addView(ui.sectionTitle(context.getString(R.string.photos_title)), LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) })

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = ui.surface(18)
            clipToOutline = true
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        subtitleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ui.p.inkSecondary)
        }
        header.addView(subtitleView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        refreshButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.photos_refresh)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ui.p.stamp)
            isAllCaps = false
            minHeight = dp(36)
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { refresh(force = true) }
        }
        header.addView(refreshButton)
        card.addView(header)

        contentHost = FrameLayout(context)
        card.addView(contentHost, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        photos = store.loadCachedDailyPhotos()
        render()
        refresh(force = true)
    }

    fun refreshIfDayChanged() {
        val nowKey = currentDayKey()
        if (nowKey != lastDayKey && photos.isNotEmpty()) {
            lastDayKey = nowKey
            photos = DailyPhotoOrder.shuffleToday(photos)
            render()
        }
        val elapsed = System.currentTimeMillis() - lastFetchMs
        if (photos.isEmpty() || elapsed >= 300_000L) refresh(force = false)
    }

    private fun refresh(force: Boolean) {
        if (!force && photos.isNotEmpty() && System.currentTimeMillis() - lastFetchMs < 300_000L) return
        lastFetchMs = System.currentTimeMillis()
        refreshButton.isEnabled = false
        store.refreshRemote { next, _ ->
            refreshButton.isEnabled = true
            lastDayKey = currentDayKey()
            if (next.isNotEmpty()) {
                photos = next
                render()
            } else if (photos.isEmpty()) {
                renderOfflineState()
            }
        }
    }

    private fun render() {
        contentHost.removeAllViews()
        if (photos.isEmpty()) {
            subtitleView.setText(R.string.photos_loading)
            renderOfflineState(loading = true)
            return
        }
        subtitleView.text = context.getString(if (typeFirst) R.string.home_photos_type_note else R.string.photos_daily_count, photos.size)
        val body = LinearLayout(context).apply { orientation = VERTICAL }

        // Lead today's hero card
        val hero = photos.first()
        val heroCard = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(ui.p.paper)
                cornerRadius = dp(ui.skin.corner(14)).toFloat()
            }
            clipToOutline = true
            isClickable = true
            isFocusable = true
            contentDescription = hero.subtitle()
            setOnClickListener { openViewer(0) }
        }
        val heroImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
        }
        val heroHeight = dp(220)
        heroCard.addView(heroImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heroHeight))
        val heroMosaic = if (typeFirst) mosaicOver(heroCard, 220, 6, hero.subtitle()) { openViewer(0) } else null
        store.loadBitmap(hero, 960) { bmp -> if (bmp != null) { heroImage.setImageBitmap(bmp); heroMosaic?.invoke(bmp) } }

        val badge = TextView(context).apply {
            text = context.getString(R.string.photos_hero_badge, hero.title())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(0x991C1917.toInt())
                cornerRadius = dp(20).toFloat()
            }
        }
        heroCard.addView(badge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        ).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        body.addView(heroCard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heroHeight))

        if (photos.size > 1) {
            val strip = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (index in 1 until photos.size) {
                val photo = photos[index]
                val thumbSize = dp(92)
                val tile = FrameLayout(context).apply {
                    background = GradientDrawable().apply {
                        setColor(ui.p.paper)
                        cornerRadius = dp(ui.skin.corner(10)).toFloat()
                    }
                    clipToOutline = true
                    isClickable = true
                    isFocusable = true
                    contentDescription = photo.subtitle()
                    setOnClickListener { openViewer(index) }
                }
                val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                tile.addView(iv, FrameLayout.LayoutParams(thumbSize, thumbSize))
                val mosaic = if (typeFirst) mosaicOver(tile, 92, 4, photo.subtitle()) { openViewer(index) } else null
                store.loadBitmap(photo, 360) { bmp -> if (bmp != null) { iv.setImageBitmap(bmp); mosaic?.invoke(bmp) } }
                row.addView(tile, LayoutParams(thumbSize, thumbSize).apply {
                    marginEnd = if (index < photos.lastIndex) dp(8) else 0
                })
            }
            strip.addView(row)
            body.addView(strip, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
        contentHost.addView(body)
    }

    /**
     * A photograph that first shows as type (see [Mosaic]): a sheet of the land's own glyphs over the photograph, the photograph set in them.
     * A first tap takes the sheet away (in six steps, or at once when animations are off); after that a tap opens it as before. Returns what
     * to call with the photograph once it has loaded.
     */
    private fun mosaicOver(card: FrameLayout, heightDp: Int, cell: Int, name: String, open: () -> Unit): (android.graphics.Bitmap) -> Unit {
        val sheet = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        card.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        var revealed = false
        card.contentDescription = context.getString(R.string.home_photo_type_desc, name)
        card.setOnClickListener {
            if (revealed) { open(); return@setOnClickListener }
            revealed = true
            card.contentDescription = context.getString(R.string.home_photo_real_desc, name)
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { sheet.visibility = View.GONE; return@setOnClickListener }
            var step = 0
            val fade = object : Runnable {
                override fun run() {
                    step++
                    sheet.alpha = 1f - step / 6f
                    if (step < 6) sheet.postDelayed(this, 80) else sheet.visibility = View.GONE
                }
            }
            sheet.post(fade)
        }
        return { photo ->
            val make = {
                val widthDp = card.width / density.toDouble()
                if (widthDp > 0) PictureWorker.run({ Mosaic.of(brush, photo, widthDp, heightDp.toDouble(), cell) }) { sheet.setImageBitmap(it) }
            }
            if (card.width > 0) make() else card.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int) {
                    if (r - l > 0) { card.removeOnLayoutChangeListener(this); make() }
                }
            })
        }
    }

    private fun renderOfflineState(loading: Boolean = false) {
        contentHost.removeAllViews()
        subtitleView.setText(if (loading) R.string.photos_loading else R.string.photos_offline)
        if (loading) return
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val msg = TextView(context).apply {
            setText(R.string.photos_offline_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ui.p.inkSecondary)
        }
        row.addView(msg, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val retry = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.photos_retry)
            setTextColor(ui.p.stamp)
            isAllCaps = false
            setOnClickListener { refresh(force = true) }
        }
        row.addView(retry)
        contentHost.addView(row)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun openViewer(startIndex: Int) {
        if (photos.isEmpty()) return
        var index = startIndex.coerceIn(0, photos.lastIndex)
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(context).apply { setBackgroundColor(0xFF0E0E10.toInt()) }
        val photoView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(photoView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val counter = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(0x88000000.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        val close = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.close)
            setTextColor(Color.WHITE)
            setOnClickListener { dialog.dismiss() }
        }
        val topBar = FrameLayout(context).apply {
            setPadding(dp(16), dp(24), dp(16), dp(12))
            addView(close, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
            addView(counter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val titleText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
        }
        val prevBtn = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "‹"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.WHITE)
        }
        val nextBtn = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "›"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.WHITE)
        }
        val bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(16), dp(10), dp(16), dp(24))
            addView(titleText, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(prevBtn)
            addView(nextBtn)
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        fun showIndex(target: Int) {
            index = ((target % photos.size) + photos.size) % photos.size
            val item = photos[index]
            counter.text = String.format(Locale.US, "%d / %d", index + 1, photos.size)
            titleText.text = item.subtitle()
            photoView.setImageDrawable(ColorDrawable(Color.TRANSPARENT))
            store.loadBitmap(item, 1920) { bmp ->
                if (dialog.isShowing && photos[index] == item && bmp != null) {
                    photoView.setImageBitmap(bmp)
                }
            }
        }
        prevBtn.setOnClickListener { showIndex(index - 1) }
        nextBtn.setOnClickListener { showIndex(index + 1) }

        var downX = 0f
        photoView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = ev.x; true }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    if (dx > 72 * density) showIndex(index - 1)
                    else if (dx < -72 * density) showIndex(index + 1)
                    true
                }
                else -> true
            }
        }
        showIndex(index)
        dialog.setContentView(root)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    private fun currentDayKey(): ULong {
        val c = Calendar.getInstance()
        return DailyPhotoOrder.dayKey(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun dp(value: Int) = (value * density).roundToInt()
}
