package com.krysta1ll.meowpuut.android.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.engine.SkinCore
import com.krysta1ll.meowpuut.android.engine.SkinNative
import com.krysta1ll.meowpuut.android.ime.Prefs
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The "Custom skins" page of the settings app (plans/custom-skins.md §5.1): the device's redemption ID to send to
 * the developer, a file picker (or "Open with Meowpuut" / Share) for the `.mpskin` that comes back, the installed
 * skins with apply and remove. Verifying, decrypting and installing happen in the Rust core ([SkinRedeemer]);
 * a failure shows a plain sentence and installs nothing.
 */
class RedeemActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var redeemer: SkinRedeemer
    private lateinit var column: LinearLayout
    private lateinit var requestCard: LinearLayout
    private lateinit var installedCard: LinearLayout
    private lateinit var progress: TextView
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "meowpuut-redeem").apply { isDaemon = true } }
    private var busy = false

    private val density get() = resources.displayMetrics.density
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = if (dark) 0xfff3ece9.toInt() else 0xff292124.toInt()
    private val quietInk get() = if (dark) 0xffc5b8bf.toInt() else 0xff705e66.toInt()
    private val accentInk get() = if (dark) 0xffebbac2.toInt() else 0xff85515d.toInt()
    private val cardFill get() = if (dark) 0xff28242c.toInt() else 0xfffffaf4.toInt()
    private val outline get() = if (dark) 0xff443b48.toInt() else 0xffe7dce5.toInt()
    private val errorInk get() = if (dark) 0xffffb4ab.toInt() else 0xff93000a.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SkinNative.hasTrustedRoots()) {
            finish()
            return
        }
        prefs = Prefs(this)
        redeemer = SkinRedeemer(this)
        SkinDebugHooks.handle(this, intent)
        column = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(680))
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }

        column.addView(text(26f).apply {
            setText(R.string.cs_title)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(dp(2), dp(4), dp(2), dp(8))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        column.addView(text(14f, quietInk).apply {
            setText(R.string.cs_intro)
            setPadding(dp(2), 0, dp(2), dp(4))
        })
        if (SkinNative.acceptsTestKeys()) {
            column.addView(text(13f, errorInk).apply {
                setText(R.string.cs_test_build)
                setPadding(dp(2), dp(4), dp(2), dp(4))
            })
        }
        if (!redeemer.available) {
            column.addView(text(14f, errorInk).apply {
                setText(R.string.cs_keystore_unavailable)
                setPadding(dp(2), dp(8), dp(2), dp(4))
            })
        }

        requestCard = section(R.string.cs_request_title)
        val redeem = section(R.string.cs_redeem_title)
        redeem.addView(button(R.string.cs_pick_file) { pickFile() })
        redeem.addView(text(13f, quietInk).apply {
            setText(R.string.cs_redeem_hint)
            setPadding(dp(14), 0, dp(14), dp(6))
        })
        redeem.addView(button(R.string.cs_import_revoke) { pickFile() })
        progress = text(14f, quietInk).apply {
            setText(R.string.cs_working)
            setPadding(dp(14), dp(4), dp(14), dp(10))
            visibility = View.GONE
        }
        redeem.addView(progress)
        installedCard = section(R.string.cs_installed_title)
        setContentView(scroll)
        renderRequest()
        renderInstalled()
        // Skins shipped in the app are installed at app start; this catches up if that is still running.
        BundledSkins.installAsync(this) { changed -> if (changed) runOnUiThread { if (!isDestroyed) renderInstalled() } }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SkinDebugHooks.handle(this, intent)
        handleIncoming(intent)
        renderRequest()
    }

    override fun onResume() {
        super.onResume()
        renderInstalled()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------- redemption ID

    private fun renderRequest() {
        requestCard.removeAllViews()
        val id = if (redeemer.available) redeemer.openRequestId() else null
        if (id == null) {
            requestCard.addView(text(14f, quietInk).apply {
                setText(R.string.cs_request_none)
                setPadding(dp(14), dp(12), dp(14), dp(4))
            })
            if (redeemer.available) requestCard.addView(button(R.string.cs_request_make) { makeRequest() })
            return
        }
        requestCard.addView(text(15f).apply {
            // "MPR2-" and groups of four, selectable: the ID can also be copied by hand.
            this.text = id
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(6))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(R.string.cs_copy) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.cs_request_title), id))
            Toast.makeText(this, R.string.cs_copied, Toast.LENGTH_SHORT).show()
        }, weighted())
        row.addView(button(R.string.cs_share) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.cs_share_subject))
                .putExtra(Intent.EXTRA_TEXT, id)
            startActivity(Intent.createChooser(send, getString(R.string.cs_share_chooser)))
        }, weighted())
        row.addView(button(R.string.cs_request_new) { makeRequest() }, weighted())
        requestCard.addView(row)
        requestCard.addView(text(13f, quietInk).apply {
            setText(R.string.cs_request_hint)
            setPadding(dp(14), 0, dp(14), dp(10))
        })
    }

    private fun makeRequest() {
        if (redeemer.newRequestId() == null) toast(getString(R.string.cs_error_general, SkinCore.E_INTERNAL))
        renderRequest()
    }

    // ------------------------------------------------------------- file in

    private fun pickFile() {
        if (busy) return
        // The picker cannot filter by extension; the size and the signature checks do the refusing.
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, PICK)
        } catch (_: android.content.ActivityNotFoundException) {
            toast(getString(R.string.cs_error_general, SkinCore.E_INTERNAL))
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK && resultCode == RESULT_OK) data?.data?.let(::redeemFrom)
    }

    /** "Open with Meowpuut" (VIEW) and Share (SEND) of a `.mpskin` or `.mprevoke`. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            // Handled once: a rotation must not redeem the same file again.
            intent?.action = null
            redeemFrom(uri)
        }
    }

    private fun redeemFrom(uri: Uri) {
        if (busy) return
        busy = true
        progress.visibility = View.VISIBLE
        worker.execute {
            val outcome = try {
                val bytes = redeemer.readBounded(contentResolver, uri)
                if (bytes == null) SkinRedeemer.Outcome.Failed(SkinCore.E_TOO_LARGE) else redeemer.redeem(bytes)
            } catch (_: Exception) {
                SkinRedeemer.Outcome.Failed(SkinCore.E_STORAGE)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                progress.visibility = View.GONE
                show(outcome)
            }
        }
    }

    private fun show(outcome: SkinRedeemer.Outcome) {
        when (outcome) {
            is SkinRedeemer.Outcome.Installed -> {
                // New or changed skins, state and bindings: the keyboard verifies again.
                prefs.touchCustomSkins()
                val name = outcome.names[if (prefs.simplified) "hans" else "hant"].orEmpty().ifEmpty { outcome.ref.id }
                toast(getString(R.string.cs_installed_ok, name))
            }
            is SkinRedeemer.Outcome.Revocations -> {
                prefs.touchCustomSkins()
                toast(getString(R.string.cs_revocation_ok, outcome.added))
            }
            is SkinRedeemer.Outcome.Failed -> AlertDialog.Builder(this)
                .setTitle(R.string.cs_title)
                .setMessage(errorText(outcome.code))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        renderRequest()
        renderInstalled()
    }

    private fun errorText(code: Int): String {
        val message = when (code) {
            SkinCore.E_TOO_LARGE -> R.string.cs_error_too_large
            2, 3, 9, 13, 17 -> R.string.cs_error_damaged
            4, 5, 8, 28 -> R.string.cs_error_signature
            SkinCore.E_TEST_KEY -> R.string.cs_error_test_key
            SkinCore.E_REVOKED -> R.string.cs_error_revoked
            SkinCore.E_NOT_FOR_THIS_DEVICE, 11, 12, SkinCore.E_BINDING -> R.string.cs_error_other_device
            14, 15, 16 -> R.string.cs_error_invalid
            SkinCore.E_DOWNGRADE -> R.string.cs_error_downgrade
            SkinCore.E_TOO_MANY -> R.string.cs_error_too_many
            SkinCore.E_CLIENT -> R.string.cs_error_client
            SkinCore.E_STORAGE -> R.string.cs_error_storage
            else -> return getString(R.string.cs_error_general, code)
            }
        return getString(message)
    }

    // ------------------------------------------------------------- installed skins

    private fun renderInstalled() {
        installedCard.removeAllViews()
        if (!redeemer.available) return
        val list = redeemer.list()
        if (list.isEmpty()) {
            installedCard.addView(text(14f, quietInk).apply {
                setText(R.string.cs_none_installed)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            })
            return
        }
        val applied = prefs.customSkin
        list.forEachIndexed { index, skin ->
            if (index > 0) installedCard.addView(View(this).apply { setBackgroundColor(outline) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(14); marginEnd = dp(14) })
            val ref = skin.ref
            val inUse = ref != null && ref.toString() == applied
            installedCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(4))
                addView(text(16f).apply {
                    text = if (skin.ok) skin.name(prefs.simplified) else ref?.toString().orEmpty()
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(text(12f, quietInk).apply {
                    text = listOfNotNull(ref?.toString(), if (skin.bundled) getString(R.string.cs_bundled) else null, if (inUse) getString(R.string.cs_in_use) else null).joinToString(" · ")
                })
                if (!skin.ok) addView(text(13f, errorInk).apply { setText(R.string.cs_status_failed) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    if (skin.ok && ref != null) {
                        if (inUse) addView(button(R.string.cs_use_builtin) { useBuiltin() })
                        else addView(button(R.string.cs_apply) { apply(ref, skin.name(prefs.simplified)) })
                    }
                    // A bundled skin ships with the app and comes back at the next start: only redeemed ones are removed here.
                    if (ref != null && !skin.bundled) addView(button(R.string.cs_remove) { confirmRemove(skin) })
                })
            })
        }
    }

    private fun apply(ref: com.krysta1ll.meowpuut.android.ime.SkinRef, name: String) {
        prefs.customSkin = ref.toString()
        toast(getString(R.string.cs_applied, name))
        renderInstalled()
    }

    private fun useBuiltin() {
        prefs.customSkin = null
        renderInstalled()
    }

    private fun confirmRemove(skin: InstalledSkin) {
        val ref = skin.ref ?: return
        val label = if (skin.ok) skin.name(prefs.simplified) else ref.toString()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cs_remove_title, label))
            .setMessage(R.string.cs_remove_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cs_remove) { _, _ ->
                if (prefs.customSkin == ref.toString()) prefs.customSkin = null
                val removed = redeemer.remove(ref)
                prefs.touchCustomSkins()
                if (removed) toast(getString(R.string.cs_removed))
                renderInstalled()
            }
            .show()
    }

    // ------------------------------------------------------------- views

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * density).roundToInt()

    private fun text(size: Float, color: Int = ink) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun button(title: Int, perform: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        setText(title)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(accentInk)
        isAllCaps = false
        minHeight = dp(48)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { perform() }
    }

    private fun section(title: Int): LinearLayout {
        column.addView(text(16f).apply {
            setText(title)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(2), dp(20), dp(2), dp(8))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(cardFill)
                setStroke(dp(1), outline)
            }
            clipToOutline = true
            column.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private companion object {
        const val PICK = 41
    }
}
