package com.example.instaguard

import android.accessibilityservice.AccessibilityService
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
import android.widget.LinearLayout
import android.widget.TextView

class AppMonitorService : AccessibilityService() {

    private var blockedAppStartTime: Long = 0
    private var isBlockedAppInForeground = false
    private var activeBlockedAppPackage: String? = null
    private var remainingTimeMs: Long = -1

    private val handler = Handler(Looper.getMainLooper())
    private var warningRunnable: Runnable? = null
    private var closeRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    private var windowManager: WindowManager? = null
    private var warningOverlayView: View? = null
    private var warningTextView: TextView? = null
    private var cooldownOverlayView: View? = null

    private var isWarningOverlayTriggered = false
    private var warningSecondsRemaining = 0

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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val prefs = getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE)
        val lockedPackages = prefs.getStringSet("locked_packages", emptySet()) ?: emptySet()

        // 1. Cooldown enforcement for any locked app (app-specific check)
        if (packageName in lockedPackages) {
            val now = SystemClock.elapsedRealtime()
            val cooldownEnd = prefs.getLong("cooldown_end_time_$packageName", 0L)
            if (now < cooldownEnd) {
                // Kick out immediately
                performGlobalAction(GLOBAL_ACTION_HOME)
                showCooldownBlockerOverlay(cooldownEnd - now, packageName)
                return
            }

            // 2. Locked app opened or returned to focus
            if (!isBlockedAppInForeground) {
                isBlockedAppInForeground = true
                blockedAppStartTime = now
                activeBlockedAppPackage = packageName

                val limitMinutes = prefs.getFloat("limit_minutes", 5.0f)
                val limitMs = (limitMinutes * 60 * 1000).toLong()

                // Check 1-hour inactivity reset (app-specific check)
                val lastExit = prefs.getLong("last_exit_time_$packageName", 0L)
                val timeAway = now - lastExit
                
                if (lastExit > 0 && timeAway >= 1 * 60 * 60 * 1000) {
                    remainingTimeMs = limitMs
                } else {
                    remainingTimeMs = prefs.getLong("remaining_budget_ms_$packageName", limitMs)
                }

                if (remainingTimeMs <= 0) {
                    remainingTimeMs = limitMs
                }

                scheduleTimers()
            } else if (activeBlockedAppPackage != packageName) {
                // Switched directly between two different locked apps
                // Save progress for the previous app
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
                activeBlockedAppPackage = packageName

                val limitMinutes = prefs.getFloat("limit_minutes", 5.0f)
                val limitMs = (limitMinutes * 60 * 1000).toLong()

                // Check 1-hour inactivity reset for the new app
                val lastExit = prefs.getLong("last_exit_time_$packageName", 0L)
                val timeAway = now - lastExit
                
                if (lastExit > 0 && timeAway >= 1 * 60 * 60 * 1000) {
                    remainingTimeMs = limitMs
                } else {
                    remainingTimeMs = prefs.getLong("remaining_budget_ms_$packageName", limitMs)
                }

                if (remainingTimeMs <= 0) {
                    remainingTimeMs = limitMs
                }

                scheduleTimers()
            } else {
                // Still in same locked app: if warning is triggered, make sure warning overlay is visible
                if (isWarningOverlayTriggered) {
                    addWarningOverlayView()
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
                    // Temporarily hide the overlay view, but keep the session active
                    removeWarningOverlayView()
                } else {
                    // Left blocked apps completely: save progress and reset timers
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
        cancelTimers()
        removeWarningOverlay()
    }

    private fun scheduleTimers() {
        cancelTimers()

        val app = activeBlockedAppPackage ?: return
        val prefs = getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE)
        val limitMinutes = prefs.getFloat("limit_minutes", 5.0f)
        val warningSeconds = prefs.getInt("warning_seconds", 30)
        val cooldownMinutes = prefs.getFloat("cooldown_minutes", 10.0f)

        val limitMs = (limitMinutes * 60 * 1000).toLong()
        val warningMs = (warningSeconds * 1000).toLong()

        // Cap remaining budget if limit was decreased in settings
        if (remainingTimeMs > limitMs || remainingTimeMs <= 0) {
            remainingTimeMs = limitMs
        }

        // Schedule warning overlay
        if (remainingTimeMs > warningMs) {
            val warningTriggerMs = remainingTimeMs - warningMs
            warningRunnable = Runnable {
                showWarningOverlay(warningSeconds)
            }
            handler.postDelayed(warningRunnable!!, warningTriggerMs)
        } else {
            // Already in warning zone: show immediately with remaining seconds
            val remainingSecs = (remainingTimeMs / 1000).toInt().coerceAtLeast(1)
            showWarningOverlay(remainingSecs)
        }

        // Schedule closing action
        closeRunnable = Runnable {
            closeAppAndStartCooldown(cooldownMinutes)
        }
        handler.postDelayed(closeRunnable!!, remainingTimeMs)
    }

    private fun cancelTimers() {
        warningRunnable?.let { handler.removeCallbacks(it) }
        closeRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable?.let { handler.removeCallbacks(it) }
        warningRunnable = null
        closeRunnable = null
        countdownRunnable = null
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

    private fun showWarningOverlay(initialSeconds: Int) {
        removeWarningOverlay()
        
        isWarningOverlayTriggered = true
        warningSecondsRemaining = initialSeconds

        // Start countdown timer loop
        countdownRunnable = object : Runnable {
            override fun run() {
                if (warningSecondsRemaining > 0) {
                    warningSecondsRemaining--
                    updateWarningOverlayText()
                    
                    // Periodically update budget in SharedPreferences for this app
                    val app = activeBlockedAppPackage
                    if (app != null) {
                        val prefs = getSharedPreferences("instaguard_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putLong("remaining_budget_ms_$app", (warningSecondsRemaining * 1000).toLong()).apply()
                    }
                    
                    handler.postDelayed(this, 1000)
                } else {
                    isWarningOverlayTriggered = false
                    removeWarningOverlayView()
                }
            }
        }
        handler.post(countdownRunnable!!)

        // Show the overlay view if we are currently looking at a blocked app
        if (isBlockedAppInForeground) {
            addWarningOverlayView()
        }
    }

    private fun addWarningOverlayView() {
        if (warningOverlayView != null) return // Already visible

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
            
            // Soft elevation shadow
            elevation = 10f * resources.displayMetrics.density
        }

        val appLabel = activeBlockedAppPackage?.let { getAppLabel(context, it) } ?: "App"
        val textView = TextView(context).apply {
            // Stylized HTML text: bold app label, bold red remaining seconds
            text = Html.fromHtml(
                "Zeitwächter: <b>$appLabel</b> schließt in <font color='#E53935'><b>$warningSecondsRemaining</b></font> Sekunden!",
                Html.FROM_HTML_MODE_LEGACY
            )
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
            y = (80 * resources.displayMetrics.density).toInt() // Position in upper part of screen
            width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        }

        try {
            windowManager?.addView(layout, params)
            warningOverlayView = layout
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        isWarningOverlayTriggered = false
        warningSecondsRemaining = 0
    }

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
            handler.postDelayed({
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

    private fun getAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "App"
        }
    }
}
