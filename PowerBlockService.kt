package com.example.powerlocks

import android.view.accessibility.AccessibilityWindowInfo
import android.os.Build
import android.content.IntentFilter
import android.content.Context
import android.content.BroadcastReceiver
import android.widget.TextView
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Power menu protection engine (event driven, no polling).
 *
 * While a session is active:
 *  1. the power menu is recognised from the window event itself (class / text), with no extra IPC,
 *  2. if needed, the window content is scanned (throttled, only for system UI),
 *  3. Back is pressed immediately, then Back again and Home shortly after, so the user ends on the Home screen,
 *  4. a short "PowerLock active" shield blocks touches on the slider while the menu is closing.
 * The protection state is cached from SharedPreferences, so events do not read storage.
 * Settings / uninstall pages that show "PowerLock" are sent Home when "protect app settings" is on.
 */
// লেখা ব্লকের পর্দা: Back চাপলে এই ভিউ থেকে সংকেত যায় (ScreenGuard-এর Cover-এর মতো)।
private class TextCover(c: Context, val onBack: () -> Unit) : FrameLayout(c) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

class PowerBlockService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastAction = 0L
    private var lastMenuScan = 0L
    private var lastGuardScan = 0L

    @Volatile
    private var provisionalCheckRunning = false // a location check is running for the power menu

    private var overlay: View? = null
    private var overlaySub: TextView? = null
    private var overlayAttached = false

    @Volatile
    private var active = false

    @Volatile
    private var endAt = 0L

    @Volatile
    private var locProtecting = false // location lock: blocked while at a saved place, no session

    @Volatile
    private var guardOn = true

    @Volatile
    private var focusOn = false

    @Volatile
    private var focusApps: Set<String> = emptySet()

    private var lastFocusAction = 0L

    private var lastKeepAliveCheck = 0L // throttles the revive check (once per 30s)

    // লেখা ব্লক (ScreenGuard থেকে): টাইমার/লোকেশন লক ছাড়াও চলে, শুধু নিজের সুইচের উপর নির্ভর করে।
    @Volatile
    private var textBlockOn = false
    private var lastTextScan = 0L
    private var contentOverlay: View? = null
    private var contentAttached = false

    // English + Bengali: the phone's system language decides the menu text.
    private val triggers = listOf(
        "Swipe to power off", "Swipe to restart", "Swipe for SOS",
        "Power off", "Restart",
        "পাওয়ার বন্ধ", "পাওয়ার অফ", "রিস্টার্ট", "বন্ধ করতে সোয়াইপ"
    )
    private val triggersLower = triggers.map { it.lowercase() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> readState() }
    private val hideTask = Runnable { hideOverlay() }
    private val backAgain = Runnable { if (protecting()) performGlobalAction(GLOBAL_ACTION_BACK) }
    private val backAgain2 = Runnable { if (protecting()) performGlobalAction(GLOBAL_ACTION_BACK) }
    private var lastWinScan = 0L
    private var receiverOn = false

    // The system announces "globalactions" the instant the power button is long-pressed,
    // before the menu is even drawn - the earliest possible trigger.
    private val closeDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            try {
                if (i?.getStringExtra("reason") == "globalactions" && protecting()) block()
            } catch (ex: Exception) {
                Log.e(TAG, "close-dialogs receiver failed", ex)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            L.load(this)
            readState()
            Store.lock(this).registerOnSharedPreferenceChangeListener(prefsListener)
            Store.settings(this).registerOnSharedPreferenceChangeListener(prefsListener)
            ensureOverlay() // pre-build the shield so it can appear instantly
            registerCloseDialogs()
            Store.kickService(this)
            Scheduler.onAlarm(this)
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerCloseDialogs() {
        try {
            val f = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(closeDialogsReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(closeDialogsReceiver, f)
            }
            receiverOn = true
        } catch (ex: Exception) {
            Log.e(TAG, "register close-dialogs failed", ex)
        }
    }

    private fun readState() {
        val p = Store.lock(this)
        active = p.getBoolean("active", false)
        endAt = p.getLong("end", 0)
        locProtecting = p.getBoolean("loc_protecting", false)
        // the guard follows the session while one runs (locked at its start); otherwise it
        // follows the live "App settings protection" switch in Settings
        val sessionOn = active && System.currentTimeMillis() < endAt
        guardOn = if (sessionOn) p.getBoolean("s_guard", true)
        else Store.settings(this).getBoolean("guard", true)
        val st = Store.settings(this)
        focusOn = st.getBoolean("focus_on", false)
        focusApps = (st.getString("focus_apps", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()
        textBlockOn = st.getBoolean("text_block", false)
    }

    private fun protecting(): Boolean = (active && System.currentTimeMillis() < endAt) || locProtecting

    /**
     * The system re-binds this service after the process dies, so it is the one
     * watchdog the system itself brings back: if the keep-alive service has not
     * written a heartbeat for 90s while protection should be on, bring it back.
     */
    private fun maybeReviveKeepAlive() {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastKeepAliveCheck < 30_000) return
            lastKeepAliveCheck = now
            if (!protecting()) return
            val hb = Store.lock(this).getLong("kas_heartbeat", 0)
            if (System.currentTimeMillis() - hb < 90_000) return
            Store.kickService(this)
            KeepAliveService.restartSoon(this, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "revive keep-alive failed", e)
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val pkg = e.packageName?.toString() ?: return
        try {
            maybeReviveKeepAlive() // the system re-binds this service, so it revives the keep-alive service
            // লেখা ব্লক: নিজের অ্যাপ আর সিস্টেম UI ছাড়া সব অ্যাপে, টাইমার চলুক বা না চলুক।
            if (textBlockOn && pkg != packageName && contentOverlay == null && !pkg.contains("systemui")) {
                if (onTextBlockEvent(e)) return
            }
            if (pkg.contains("systemui")) {
                // the power menu: even when the flag is not set yet, the location lock may apply
                onSystemUi(e)
            } else if (!protecting()) {
                return
            } else if (focusOn && focusApps.contains(pkg)) {
                onFocusApp(e)
            } else if (guardOn && isGuarded(pkg)) {
                onGuarded(e)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "onAccessibilityEvent failed", ex)
        }
    }

    // ---------- detection ----------

    private fun onSystemUi(e: AccessibilityEvent) {
        // 1) instant check from the event itself (no IPC at all)
        val menu = looksLikePowerMenu(e) || menuFromNode(e)
        if (!menu) return
        if (protecting()) {
            block()
            return
        }
        // location lock on but the flag has not caught up yet (updates are slow): shield now,
        // verify presence, then keep blocking or release the menu
        if (!Store.settings(this).getBoolean("loc_on", false)) return
        if (!Perm.locationOn(this)) return
        if (provisionalCheckRunning) {
            showOverlay() // keep the shield up while the check runs
            return
        }
        showOverlay()
        provisionalCheckRunning = true
        PlaceWatcher.isAnyPlaceActiveNow(this) { active ->
            provisionalCheckRunning = false
            if (active == true) {
                Store.setLocProtecting(this, true)
                block()
            } else {
                hideOverlay()
            }
        }
    }

    /** Node / window scan for the power menu (throttled like before). */
    private fun menuFromNode(e: AccessibilityEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastMenuScan < 60) return false
            lastMenuScan = now
            if (hasPowerMenu(e.source)) return true
            if (now - lastWinScan < 150) return false
            lastWinScan = now
            return scanWindows(false)
        }
        return hasPowerMenu(e.source) || scanWindows(true)
    }

    private fun onGuarded(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastGuardScan < 120) return
            lastGuardScan = now
        }
        val root = rootInActiveWindow ?: return
        if (root.findAccessibilityNodeInfosByText("PowerLock").isNotEmpty() && now - lastAction >= 300) {
            lastAction = now
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun onFocusApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFocusAction < 300) return
        lastFocusAction = now
        Extra.logAttempt(this, "focus")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ---------- লেখা ব্লক (ScreenGuard থেকে) ----------

    /** স্ক্রিনের লেখায় কীওয়ার্ড বা ব্লক করা সাইটের ঠিকানা থাকলে ব্লক পর্দা দেখায়। */
    private fun onTextBlockEvent(e: AccessibilityEvent): Boolean {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastTextScan < 600) return false
        lastTextScan = now
        val root = rootInActiveWindow ?: return false
        val sb = StringBuilder()
        collectText(root, sb)
        if (sb.isEmpty()) return false
        val rawLower = sb.toString().lowercase()
        // সাইট: ঠিকানায় ডট থাকে, তাই কাঁচা লেখাতেই মেলানো হয় (যেমন ব্রাউজারের অ্যাড্রেস বার)।
        val sites = Store.textSites(this).map { it.lowercase() }.filter { it.isNotEmpty() }
        if (sites.any { rawLower.contains(it) }) {
            triggerTextBlock()
            return true
        }
        var text = normText(rawLower)
        for (w in Store.textWhite(this).map { normText(it) }.filter { it.isNotEmpty() }) {
            text = text.replace(w, " ")
        }
        val words = Store.textWords(this).map { normText(it) }.filter { it.isNotEmpty() }
        if (words.any { text.contains(it) }) {
            triggerTextBlock()
            return true
        }
        return false
    }

    private fun collectText(n: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (n == null) return
        n.text?.let { sb.append(it).append(' ') }
        n.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until n.childCount) collectText(n.getChild(i), sb)
    }

    // ScreenGuard-এর মতোই: p0rn / s3x ধরতে 0->o, 1->i, 3->e, @->a, $->s বদলে নেওয়া হয়।
    private fun normText(s: String) = s.lowercase()
        .replace('0', 'o').replace('1', 'i').replace('3', 'e')
        .replace('@', 'a').replace('$', 's')
        .replace(Regex("[^\\p{L}\\p{M}\\s]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun triggerTextBlock() {
        handler.post {
            if (contentOverlay != null || !textBlockOn) return@post
            showContentOverlay()
        }
    }

    private fun showContentOverlay() {
        if (contentAttached) return
        try {
            val cover = TextCover(this) { dismissContentOverlay() }
            cover.setBackgroundColor(Color.parseColor("#0B1020"))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            col.setPadding(dp(32), dp(32), dp(32), dp(32))
            col.addView(IconView(this, Ic.SHIELD, C.primary), lp(dp(104), dp(104)))
            val title = tv(L.t("This is now blocked", "এটা এখন ব্লক করা হয়েছে"), 26f, C.text, 1)
            title.gravity = Gravity.CENTER
            col.addView(title, lp(WRAP, WRAP, t = dp(28)))
            val sub = tv(
                L.t("A blocked word or site was found on the screen.", "স্ক্রিনে ব্লক করা শব্দ বা সাইট পাওয়া গেছে।"),
                16f, C.muted
            )
            sub.gravity = Gravity.CENTER
            col.addView(sub, lp(WRAP, WRAP, t = dp(12)))
            val back = tv(L.t("← Go back", "← ফিরে যান"), 17f, Color.WHITE, 1)
            back.gravity = Gravity.CENTER
            back.background = rounded(C.primary, dp(16).toFloat())
            back.setPadding(dp(40), dp(14), dp(40), dp(14))
            back.setOnClickListener { dismissContentOverlay() }
            col.addView(back, lp(WRAP, WRAP, t = dp(36)))
            val hint = tv(L.t("Or press the Back button", "অথবা Back বাটন চাপুন"), 13f, C.muted)
            hint.gravity = Gravity.CENTER
            col.addView(hint, lp(WRAP, WRAP, t = dp(14)))
            cover.addView(
                col,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            )
            wm.addView(cover, params)
            contentOverlay = cover
            contentAttached = true
            cover.isFocusable = true
            cover.isFocusableInTouchMode = true
            cover.requestFocus()
        } catch (ex: Exception) {
            Log.e(TAG, "showContentOverlay failed", ex)
            contentAttached = false
        }
    }

    // Back চাপলে ব্লক পর্দা সরে যায় এবং আগের পেজে ফেরা হয়।
    private fun dismissContentOverlay() {
        val o = contentOverlay ?: return
        contentOverlay = null
        contentAttached = false
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(o)
        } catch (ex: Exception) {
            Log.e(TAG, "dismissContentOverlay failed", ex)
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun removeContentOverlay() {
        val o = contentOverlay ?: return
        contentOverlay = null
        contentAttached = false
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(o)
        } catch (ex: Exception) {
            Log.e(TAG, "removeContentOverlay failed", ex)
        }
    }

    // ব্লক পর্দা থাকাকালীন ফোনের Back বাটনের চাপ ধরা।
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && contentOverlay != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) handler.post { dismissContentOverlay() }
            return true
        }
        return false
    }

    private fun looksLikePowerMenu(e: AccessibilityEvent): Boolean {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = (e.className?.toString() ?: "").lowercase()
            if (cls.contains("globalaction") || cls.contains("powermenu") || cls.contains("poweroff") || cls.contains("shutdown")) {
                return true
            }
        }
        val sb = StringBuilder()
        for (t in e.text) sb.append(t).append(' ')
        val cd = e.contentDescription
        if (cd != null) sb.append(cd)
        if (sb.isEmpty()) return false
        val s = sb.toString().lowercase()
        for (t in triggersLower) {
            if (s.contains(t)) return true
        }
        return false
    }

    /** The power menu always has the "power off" slider (English or Bengali). */
    private fun hasPowerMenu(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        return n.findAccessibilityNodeInfosByText("to power off").isNotEmpty() ||
                n.findAccessibilityNodeInfosByText("পাওয়ার বন্ধ").isNotEmpty() ||
                n.findAccessibilityNodeInfosByText("রিস্টার্ট").isNotEmpty()
    }

    /** System windows first (where the power menu lives); other windows only when [all] is true. */
    private fun scanWindows(all: Boolean): Boolean {
        return try {
            val list = windows
            var found = false
            for (w in list) {
                if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && hasPowerMenu(w.root)) {
                    found = true
                    break
                }
            }
            if (!found && all) {
                for (w in list) {
                    if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM && hasPowerMenu(w.root)) {
                        found = true
                        break
                    }
                }
            }
            found
        } catch (ex: Exception) {
            false
        }
    }

    private fun isGuarded(p: String): Boolean =
        p.contains("settings") || p.contains("packageinstaller") ||
                p.contains("securitycenter") || p.contains("permissioncontroller")

    // ---------- blocking ----------

    private fun block() {
        showOverlay() // shield first: from this moment the slider cannot be touched
        val now = SystemClock.uptimeMillis()
        if (now - lastAction >= 100) {
            lastAction = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.removeCallbacks(backAgain)
            handler.removeCallbacks(backAgain2)
            handler.postDelayed(backAgain, 60)
            handler.postDelayed(backAgain2, 150)
            // no HOME: the app the user is in must stay open
        }
    }

    private fun ensureOverlay(): View {
        val existing = overlay
        if (existing != null) return existing
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(245, 8, 16, 12))
        root.isClickable = true
        root.addView(IconView(this, Ic.SHIELD, C.primary), lp(dp(84), dp(84)))
        val title = tv(L.t("PowerLock is active", "PowerLock সক্রিয়"), 24f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val sub = tv("", 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        overlay = root
        overlaySub = sub
        return root
    }

    private fun showOverlay() {
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 1400)
        if (overlayAttached) return // never more than one shield
        try {
            val v = ensureOverlay()
            val sessionOn = active && System.currentTimeMillis() < endAt
            overlaySub?.text = if (locProtecting && !sessionOn) {
                L.t("Location lock is active", "লোকেশন লক সক্রিয়")
            } else {
                val at = fmtClock(endAt)
                L.t("Power menu is blocked until $at", "$at পর্যন্ত পাওয়ার মেনু ব্লক করা আছে")
            }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, params)
            overlayAttached = true
        } catch (ex: Exception) {
            Log.e(TAG, "showOverlay failed", ex)
            overlayAttached = false
        }
    }

    private fun hideOverlay() {
        if (!overlayAttached) return
        overlayAttached = false
        try {
            val v = overlay ?: return
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        } catch (ex: Exception) {
            Log.e(TAG, "hideOverlay failed", ex)
        }
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        removeContentOverlay()
        if (receiverOn) {
            receiverOn = false
            try {
                unregisterReceiver(closeDialogsReceiver)
            } catch (ex: Exception) {
                Log.e(TAG, "unregister close-dialogs failed", ex)
            }
        }
        try {
            Store.lock(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (ex: Exception) {
            Log.e(TAG, "unregister failed", ex)
        }
        try {
            Store.settings(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (ex: Exception) {
            Log.e(TAG, "unregister settings failed", ex)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
