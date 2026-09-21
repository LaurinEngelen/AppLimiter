package com.example.instaguard

import android.content.SharedPreferences

object BudgetManager {
    const val ONE_HOUR_MS = 60 * 60 * 1000L

    fun getLimitMs(prefs: SharedPreferences): Long {
        val limitMinutes = prefs.getFloat("limit_minutes", 5.0f)
        return (limitMinutes * 60 * 1000).toLong()
    }

    fun getMaxCooldownMs(prefs: SharedPreferences): Long {
        val cooldownMinutes = prefs.getFloat("cooldown_minutes", 10.0f)
        return (cooldownMinutes * 60 * 1000).toLong() + 5000L
    }

    /**
     * Cleans up invalid, expired or reboot-corrupted cooldown timestamps.
     * Returns remaining cooldown in ms (0 if not in cooldown).
     */
    fun getActiveCooldownRemainingMs(
        prefs: SharedPreferences,
        pkg: String,
        nowWall: Long = System.currentTimeMillis()
    ): Long {
        val cooldownEnd = prefs.getLong("cooldown_end_time_$pkg", 0L)
        if (cooldownEnd <= 0L) return 0L

        val maxCooldownMs = getMaxCooldownMs(prefs)
        val diff = cooldownEnd - nowWall

        // If cooldown already expired, or corrupted (e.g. from old elapsedRealtime in SharedPreferences,
        // diff > maxCooldownMs or diff < 0), self-heal by clearing it.
        if (diff <= 0L || diff > maxCooldownMs) {
            prefs.edit().putLong("cooldown_end_time_$pkg", 0L).apply()
            return 0L
        }

        return diff
    }

    /**
     * Checks and applies reset rules:
     * 1. 1 hour of inactivity since last exit.
     * 2. Less than 1/4 budget left and 1 full hour (60 min) passed since dropping below 1/4.
     * 3. Budget completely exhausted and cooldown has ended.
     *
     * Returns current effective remaining budget in ms.
     */
    fun getOrResetBudgetMs(
        prefs: SharedPreferences,
        pkg: String,
        nowWall: Long = System.currentTimeMillis()
    ): Long {
        val limitMs = getLimitMs(prefs)
        val quarterLimitMs = limitMs / 4

        // Ensure cooldown state is cleaned up if expired
        val cooldownRemaining = getActiveCooldownRemainingMs(prefs, pkg, nowWall)

        val storedBudget = prefs.getLong("remaining_budget_ms_$pkg", limitMs)
        val lastExit = prefs.getLong("last_exit_time_$pkg", 0L)
        var quarterDepletedTime = prefs.getLong("quarter_depleted_time_$pkg", 0L)

        // If budget is below 1/4 but quarterDepletedTime hasn't been set yet, initialize it
        if (storedBudget in 1 until quarterLimitMs && quarterDepletedTime == 0L) {
            quarterDepletedTime = nowWall
            prefs.edit().putLong("quarter_depleted_time_$pkg", quarterDepletedTime).apply()
        }

        val isInactiveOneHour = (lastExit > 0L && (nowWall - lastExit) >= ONE_HOUR_MS)
        val isQuarterDepletedOneHour = (storedBudget < quarterLimitMs && quarterDepletedTime > 0L && (nowWall - quarterDepletedTime) >= ONE_HOUR_MS)
        val isBudgetZeroAndCooldownEnded = (storedBudget <= 0L && cooldownRemaining == 0L)

        if (isInactiveOneHour || isQuarterDepletedOneHour || isBudgetZeroAndCooldownEnded) {
            prefs.edit()
                .putLong("remaining_budget_ms_$pkg", limitMs)
                .putLong("quarter_depleted_time_$pkg", 0L)
                .apply()
            return limitMs
        }

        return storedBudget
    }

    /**
     * Called when an app session ends or periodically during ticking to update stored budget.
     */
    fun recordBudgetProgress(
        prefs: SharedPreferences,
        pkg: String,
        remainingMs: Long,
        nowWall: Long = System.currentTimeMillis()
    ) {
        val limitMs = getLimitMs(prefs)
        val quarterLimitMs = limitMs / 4
        val editor = prefs.edit()
            .putLong("remaining_budget_ms_$pkg", remainingMs)
            .putLong("last_exit_time_$pkg", nowWall)

        if (remainingMs in 1 until quarterLimitMs) {
            val existingQuarterTime = prefs.getLong("quarter_depleted_time_$pkg", 0L)
            if (existingQuarterTime == 0L) {
                editor.putLong("quarter_depleted_time_$pkg", nowWall)
            }
        } else if (remainingMs >= quarterLimitMs || remainingMs <= 0L) {
            editor.putLong("quarter_depleted_time_$pkg", 0L)
        }
        editor.apply()
    }

    /**
     * Starts a cooldown for an app.
     */
    fun startCooldown(
        prefs: SharedPreferences,
        pkg: String,
        cooldownMinutes: Float,
        nowWall: Long = System.currentTimeMillis()
    ): Long {
        val cooldownMs = (cooldownMinutes * 60 * 1000).toLong()
        val cooldownEnd = nowWall + cooldownMs
        prefs.edit()
            .putLong("cooldown_end_time_$pkg", cooldownEnd)
            .putLong("remaining_budget_ms_$pkg", 0L)
            .putLong("last_exit_time_$pkg", nowWall)
            .putLong("quarter_depleted_time_$pkg", 0L)
            .apply()
        return cooldownMs
    }

    /**
     * Resets an app immediately (e.g. via Reset button in UI).
     */
    fun resetApp(
        prefs: SharedPreferences,
        pkg: String,
        nowWall: Long = System.currentTimeMillis()
    ) {
        val limitMs = getLimitMs(prefs)
        prefs.edit()
            .putLong("remaining_budget_ms_$pkg", limitMs)
            .putLong("cooldown_end_time_$pkg", 0L)
            .putLong("last_exit_time_$pkg", nowWall)
            .putLong("quarter_depleted_time_$pkg", 0L)
            .apply()
    }
}
