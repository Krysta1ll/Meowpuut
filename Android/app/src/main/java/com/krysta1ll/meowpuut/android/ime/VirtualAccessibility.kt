package com.krysta1ll.meowpuut.android.ime

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * Exposes the items a custom view draws (keys, candidates) as accessibility
 * nodes, so TalkBack can find each one by touch and activate it.
 */
class VirtualAccessibility(
    private val host: View,
    private val items: () -> List<Item>,
    private val activate: (Int) -> Unit,
) : AccessibilityNodeProvider() {
    /** [bounds] are in the host view's pixels. */
    class Item(
        val id: Int,
        val bounds: Rect,
        val label: String,
        val selected: Boolean = false,
        val enabled: Boolean = true,
        val longClick: LongClick? = null,
    )

    class LongClick(val label: String, val perform: () -> Unit)

    private var focused = View.NO_ID
    private var hovered = View.NO_ID
    private val location = IntArray(2)
    private val manager = host.context.getSystemService(AccessibilityManager::class.java)

    // Sending an event while accessibility is off throws.
    private val listening get() = manager?.isEnabled == true

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == View.NO_ID) {
            val info = node(View.NO_ID)
            host.onInitializeAccessibilityNodeInfo(info)
            for (item in items()) info.addChild(host, item.id)
            return info
        }
        val item = items().firstOrNull { it.id == virtualViewId } ?: return null
        val info = node(virtualViewId)
        info.packageName = host.context.packageName
        info.className = KEY_CLASS
        info.setParent(host)
        info.contentDescription = item.label
        // Older TalkBack versions still read the bounds in the parent.
        @Suppress("DEPRECATION")
        info.setBoundsInParent(item.bounds)
        host.getLocationOnScreen(location)
        info.setBoundsInScreen(Rect(item.bounds).apply { offset(location[0], location[1]) })
        info.isEnabled = item.enabled
        info.isVisibleToUser = true
        info.isClickable = item.enabled
        info.isFocusable = true
        info.isSelected = item.selected
        info.isAccessibilityFocused = focused == virtualViewId
        info.addAction(if (focused == virtualViewId) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                       else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        if (item.enabled) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        if (item.enabled && item.longClick != null) {
            info.isLongClickable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, item.longClick.label))
        }
        return info
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == View.NO_ID) return host.performAccessibilityAction(action, arguments)
        when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focused == virtualViewId) return false
                focused = virtualViewId
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                host.invalidate()
                return true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focused != virtualViewId) return false
                focused = View.NO_ID
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                return true
            }
            AccessibilityNodeInfo.ACTION_CLICK -> {
                if (items().none { it.id == virtualViewId && it.enabled }) return false
                activate(virtualViewId)
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                return true
            }
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                val longClick = items().firstOrNull { it.id == virtualViewId && it.enabled }?.longClick ?: return false
                longClick.perform()
                return true
            }
        }
        return false
    }

    /** Feed hover events from touch exploration; returns true when handled. */
    fun onHover(event: MotionEvent): Boolean {
        val id = items().firstOrNull { it.bounds.contains(event.x.toInt(), event.y.toInt()) }?.id ?: View.NO_ID
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (id != hovered) {
                    if (hovered != View.NO_ID) send(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    hovered = id
                    if (id != View.NO_ID) send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hovered != View.NO_ID) send(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                hovered = View.NO_ID
            }
        }
        return true
    }

    /** Call when the items change so TalkBack refreshes them. */
    fun invalidateAll() {
        if (!listening || !host.isAttachedToWindow) return
        val event = event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        event.packageName = host.context.packageName
        event.setSource(host)
        host.parent?.requestSendAccessibilityEvent(host, event)
    }

    private fun send(id: Int, type: Int) {
        if (!listening) return
        val parent = host.parent ?: return
        val item = items().firstOrNull { it.id == id }
        val event = event(type)
        event.packageName = host.context.packageName
        event.className = KEY_CLASS
        event.setSource(host, id)
        item?.let { event.contentDescription = it.label }
        parent.requestSendAccessibilityEvent(host, event)
    }

    // The constructors arrived in API 30; obtain() is the only way before that.
    private fun event(type: Int): AccessibilityEvent =
        if (Build.VERSION.SDK_INT >= 30) AccessibilityEvent(type) else @Suppress("DEPRECATION") AccessibilityEvent.obtain(type)

    private fun node(id: Int): AccessibilityNodeInfo = when {
        Build.VERSION.SDK_INT >= 30 -> if (id == View.NO_ID) AccessibilityNodeInfo(host) else AccessibilityNodeInfo(host, id)
        id == View.NO_ID -> @Suppress("DEPRECATION") AccessibilityNodeInfo.obtain(host)
        else -> @Suppress("DEPRECATION") AccessibilityNodeInfo.obtain(host, id)
    }

    private companion object {
        // TalkBack treats nodes of this class as keyboard keys.
        const val KEY_CLASS = "android.inputmethodservice.Keyboard\$Key"
    }
}
