package com.example.instaguard

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

class AppMonitorService : AccessibilityService() {

    private var blockedAppStartTime: Long = 0
    private var isBlockedAppInForeground = false
    private var activeBlockedAppPackage: String? = null
    private var remainingTimeMs: Long = -1

    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    private var windowManager: WindowManager? = null
    
    // Warning overlay components
    private var warningOverlayView: View? = null
    private var warningTextView: TextView? = null
    private var isWarningOverlayTriggered = false
    private var warningSecondsRemaining = 0

    // Cooldown overlay
    private var cooldownOverlayView: View? = null

    private val NotificationId = 1001
    private val ChannelId = "instaguard_timer_channel"

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                saveSessionProgressAndReset()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)
        createNotificationChannel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val prefs = getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE)
        val lockedPackages = prefs.getStringSet("locked_packages", emptySet()) ?: emptySet()

        // Map package name to virtual package name if inside Chrome viewing Instagram or YouTube
        var targetPackage = packageName
        if (packageName == "com.android.chrome") {
            val rootNode = rootInActiveWindow
            val url = getChromeUrl(rootNode)
            rootNode?.recycle()
            
            if (url != null) {
                if (url.contains("instagram.com")) {
                    targetPackage = "web:instagram.com"
                } else if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    targetPackage = "web:youtube.com"
                }
            }
        }

        // 1. Cooldown enforcement for any locked app or website
        if (targetPackage in lockedPackages) {
            val now = SystemClock.elapsedRealtime()
            val cooldownEnd = prefs.getLong("cooldown_end_time_$targetPackage", 0L)
            if (now < cooldownEnd) {
                // Kick out immediately
                performGlobalAction(GLOBAL_ACTION_HOME)
                showCooldownBlockerOverlay(cooldownEnd - now, targetPackage)
                return
            }

            // 2. Locked app opened or returned to focus
            if (!isBlockedAppInForeground) {
                isBlockedAppInForeground = true
                blockedAppStartTime = now
                activeBlockedAppPackage = targetPackage

                val limitMinutes = prefs.getFloat("limit_minutes", 5.0f)
                val limitMs = (limitMinutes * 60 * 1000).toLong()

                // Check 1-hour inactivity reset
                val lastExit = prefs.getLong("last_exit_time_$targetPackage", 0L)
                val timeAway = now - lastExit
                
                if (lastExit > 0 && timeAway >= 1 * 60 * 60 * 1000) {
                    remainingTimeMs = limitMs
                } else {
                    remainingTimeMs = prefs.getLong("remaining_budget_ms_$targetPackage", limitMs)
                }

                if (remainingTimeMs <= 0) {
                    remainingTimeMs = limitMs
                }

                startTicking()
            } else if (activeBlockedAppPackage != targetPackage) {
                // Switched directly between two different locked apps (e.g. Instagram Web -> YouTube Web)
                val prevApp = activeBlockedAppPackage
                if (prevApp != null) {
                    val sessionTime = now - blockedAppStartTime
                    val prevRemaining = (remainingTimeMs - sessionTime).coerceAtLeast(0)
                    prefs.edit()
                        .putLong("remaining_budget_ms_$prevApp", prevRemaining)
                        .putLong("last_exit_time_$prevApp", now)
                        .apply()
                }

                // Start tracking the new locked app
                blockedAppStartTime = now
                activeBlockedAppPackage = targetPackage

                val limitMinutes = prefs.getFloat("limit_minutes", 5.0f)
                val limitMs = (limitMinutes * 60 * 1000).toLong()

                // Check 1-hour inactivity reset for the new app
                val lastExit = prefs.getLong("last_exit_time_$targetPackage", 0L)
                val timeAway = now - lastExit
                
                if (lastExit > 0 && timeAway >= 1 * 60 * 60 * 1000) {
                    remainingTimeMs = limitMs
                } else {
                    remainingTimeMs = prefs.getLong("remaining_budget_ms_$targetPackage", limitMs)
                }

                if (remainingTimeMs <= 0) {
                    remainingTimeMs = limitMs
                }

                startTicking()
            } else {
                // Still in same locked app: ensure warning overlays are visible
                if (isBlockedAppInForeground) {
                    if (isWarningOverlayTriggered) {
                        addWarningOverlayView()
                    }
                }
            }
        } else {
            // 3. User switched to another app or system overlay
            val ignoredPackages = listOf(
                "android",
                "com.android.systemui",
                "com.google.android.inputmethod.latin",
                this.packageName
            )

            if (packageName != this.packageName) {
                if (packageName in ignoredPackages) {
                    // Temporarily hide overlays to not block keyboard or system panels
                    removeWarningOverlayView()
                } else {
                    // Left blocked apps completely: save progress and stop overlays/timers
                    saveSessionProgressAndReset()
                }
            }
        }
    }

    override fun onInterrupt() {
        saveSessionProgressAndReset()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenOffReceiver)
        saveSessionProgressAndReset()
    }

    private fun saveSessionProgressAndReset() {
        val app = activeBlockedAppPackage
        if (isBlockedAppInForeground && app != null) {
            val now = SystemClock.elapsedRealtime()
            val sessionTime = now - blockedAppStartTime
            remainingTimeMs = (remainingTimeMs - sessionTime).coerceAtLeast(0)

            val prefs = getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("remaining_budget_ms_$app", remainingTimeMs)
                .putLong("last_exit_time_$app", now)
                .apply()
        }
        
        isBlockedAppInForeground = false
        activeBlockedAppPackage = null
        blockedAppStartTime = 0
        stopTicking()
    }

    private fun startTicking() {
        stopTicking()
        
        tickRunnable = object : Runnable {
            override fun run() {
                if (!isBlockedAppInForeground) return
                
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - blockedAppStartTime
                blockedAppStartTime = now // reset start time to current tick
                
                remainingTimeMs = (remainingTimeMs - elapsed).coerceAtLeast(0)
                
                // Save progress to prefs (asynchronously)
                val app = activeBlockedAppPackage
                if (app != null) {
                    val prefs = getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putLong("remaining_budget_ms_$app", remainingTimeMs).apply()
                    
                    // Show minimal countdown notification in status bar
                    val appLabel = getAppLabel(this@AppMonitorService, app)
                    showNotification(appLabel, remainingTimeMs)
                }
                
                val prefs = getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE)
                val warningSeconds = prefs.getInt("warning_seconds", 30)
                val warningMs = (warningSeconds * 1000).toLong()
                
                if (remainingTimeMs <= warningMs) {
                    val remainingSecs = (remainingTimeMs / 1000).toInt().coerceAtLeast(1)
                    showWarningOverlay(remainingSecs)
                }
                
                if (remainingTimeMs <= 0) {
                    val cooldownMinutes = prefs.getFloat("cooldown_minutes", 10.0f)
                    closeAppAndStartCooldown(cooldownMinutes)
                } else {
                    tickHandler.postDelayed(this, 1000)
                }
            }
        }
        tickHandler.post(tickRunnable!!)
    }

    private fun stopTicking() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        tickRunnable = null
        cancelNotification()
        removeWarningOverlay()
    }

    private fun closeAppAndStartCooldown(cooldownMinutes: Float) {
        val packageToBlock = activeBlockedAppPackage ?: return
        
        // Go home to close active blocked app
        performGlobalAction(GLOBAL_ACTION_HOME)
        
        // Save cooldown timestamp specifically for this app
        val cooldownMs = (cooldownMinutes * 60 * 1000).toLong()
        val cooldownEnd = SystemClock.elapsedRealtime() + cooldownMs

        val prefs = getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE)
        
        // Reset budget completely for this app
        remainingTimeMs = 0
        prefs.edit()
            .putLong("cooldown_end_time_$packageToBlock", cooldownEnd)
            .putLong("remaining_budget_ms_$packageToBlock", 0L)
            .putLong("last_exit_time_$packageToBlock", SystemClock.elapsedRealtime())
            .apply()

        saveSessionProgressAndReset()
        
        // Display a brief cooldown notice for this app
        showCooldownBlockerOverlay(cooldownMs, packageToBlock)
    }

    // --- Warning Overlay ---

    private fun showWarningOverlay(seconds: Int) {
        isWarningOverlayTriggered = true
        warningSecondsRemaining = seconds

        if (isBlockedAppInForeground) {
            addWarningOverlayView()
        }
    }

    private fun addWarningOverlayView() {
        if (warningOverlayView == null) {
            val context = this
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                
                // Glassmorphism-style clean warm white background (slightly transparent)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#BFFAFAFA")) // Semi-transparent greyish-white (75% opacity)
                    cornerRadius = 24 * resources.displayMetrics.density
                    setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.parseColor("#E0E0E0")) // Subtle grey border
                }
                elevation = 10f * resources.displayMetrics.density
            }

            val textView = TextView(context).apply {
                setTextColor(Color.parseColor("#1E1E24")) // Deep carbon text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
            }
            layout.addView(textView)
            warningTextView = textView

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (80 * resources.displayMetrics.density).toInt() // Position lower than status bar
                width = (resources.displayMetrics.widthPixels * 0.9).toInt()
            }

            try {
                windowManager?.addView(layout, params)
                warningOverlayView = layout
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        updateWarningOverlayText()
    }

    private fun updateWarningOverlayText() {
        val appLabel = activeBlockedAppPackage?.let { getAppLabel(this, it) } ?: "App"
        warningTextView?.text = Html.fromHtml(
            "Zeitwächter: <b>$appLabel</b> schließt in <font color='#E53935'><b>$warningSecondsRemaining</b></font> Sekunden!",
            Html.FROM_HTML_MODE_LEGACY
        )
    }

    private fun removeWarningOverlayView() {
        warningOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            warningOverlayView = null
            warningTextView = null
        }
    }

    private fun removeWarningOverlay() {
        removeWarningOverlayView()
        isWarningOverlayTriggered = false
        warningSecondsRemaining = 0
    }

    // --- Status Bar Low-Priority Notification ---

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ChannelId,
                "AppLimiter Zeitlimits",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt verbleibende App-Zeitlimits in der Statusleiste."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(appLabel: String, remainingMs: Long) {
        val totalSecs = (remainingMs / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        val timeString = String.format("%02d:%02d verbleibend", mins, secs)

        val notification = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(this, ChannelId)
        } else {
            Notification.Builder(this)
        }.apply {
            setSmallIcon(R.drawable.ic_notification)
            // Title contains app label and countdown to keep it as compact and minimal as possible
            setContentTitle("$appLabel: $timeString")
            setOngoing(true)
            setOnlyAlertOnce(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setVisibility(Notification.VISIBILITY_PUBLIC)
                setCategory(Notification.CATEGORY_PROGRESS)
            }
        }.build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NotificationId, notification)
    }

    private fun cancelNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NotificationId)
    }

    // --- Cooldown Overlay ---

    private fun showCooldownBlockerOverlay(remainingMs: Long, packageName: String) {
        cooldownOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
        }

        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            
            // Clean glassmorphism white without border
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F9F9FCFF")) // Soft light blue-white
                cornerRadius = 28 * resources.displayMetrics.density
            }
            elevation = 16f * resources.displayMetrics.density
        }

        val appLabel = getAppLabel(context, packageName)
        val titleView = TextView(context).apply {
            text = "$appLabel ist gesperrt"
            setTextColor(Color.parseColor("#C62828")) // Warm Crimson Red
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            gravity = Gravity.CENTER
        }
        layout.addView(titleView)

        val remainingMinutes = remainingMs / 1000 / 60
        val remainingSeconds = (remainingMs / 1000) % 60
        
        val descView = TextView(context).apply {
            text = Html.fromHtml(
                "Cooldown aktiv!<br/>Noch <font color='#C62828'><b>$remainingMinutes Min $remainingSeconds Sek</b></font> verbleibend.",
                Html.FROM_HTML_MODE_LEGACY
            )
            setTextColor(Color.parseColor("#37474F")) // Slate grey text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        layout.addView(descView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager?.addView(layout, params)
            cooldownOverlayView = layout

            // Auto-dismiss after 3 seconds
            tickHandler.postDelayed({
                cooldownOverlayView?.let {
                    try {
                        windowManager?.removeView(it)
                    } catch (e: Exception) {
                        // Ignore
                    }
                    cooldownOverlayView = null
                }
            }, 3000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getChromeUrl(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        
        val viewId = node.viewIdResourceName
        if (viewId != null && (viewId == "com.android.chrome:id/url_bar" || viewId == "com.android.chrome:id/search_box_text")) {
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                return text
            }
        }
        
        val text = node.text?.toString()
        if (text != null && (text.contains("instagram.com") || text.contains("youtube.com") || text.contains("youtu.be"))) {
            return text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val url = getChromeUrl(child)
            child.recycle()
            if (url != null) {
                return url
            }
        }
        return null
    }

    private fun getAppLabel(context: Context, packageName: String): String {
        if (packageName == "web:instagram.com") return "Instagram Web"
        if (packageName == "web:youtube.com") return "YouTube Web"
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "App"
        }
    }
}
