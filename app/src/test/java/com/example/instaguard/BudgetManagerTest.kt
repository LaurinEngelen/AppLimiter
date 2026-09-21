package com.example.instaguard

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BudgetManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        // Default settings: 20 minutes limit, 10 minutes cooldown
        fakePrefs.edit()
            .putFloat("limit_minutes", 20.0f)
            .putFloat("cooldown_minutes", 10.0f)
            .apply()
    }

    @Test
    fun testRebootCorruptionAutoHealing() {
        val pkg = "com.test.app"
        val now = 1_000_000_000_000L // Wall clock time

        // Simulate old corrupt timestamp from elapsedRealtime before reboot (e.g. 500 hours = 1.8 billion ms)
        fakePrefs.edit()
            .putLong("cooldown_end_time_$pkg", 1_800_000_000L) // Way in the past compared to wall clock
            .apply()

        val remainingCooldown = BudgetManager.getActiveCooldownRemainingMs(fakePrefs, pkg, now)
        assertEquals(0L, remainingCooldown)
        // Corrupted cooldown should be cleared
        assertEquals(0L, fakePrefs.getLong("cooldown_end_time_$pkg", -1L))
    }

    @Test
    fun testNormalActiveCooldown() {
        val pkg = "com.test.app"
        val now = 1_000_000_000_000L
        val cooldownEnd = now + 5 * 60 * 1000L // 5 mins left

        fakePrefs.edit()
            .putLong("cooldown_end_time_$pkg", cooldownEnd)
            .apply()

        val remaining = BudgetManager.getActiveCooldownRemainingMs(fakePrefs, pkg, now)
        assertEquals(5 * 60 * 1000L, remaining)
    }

    @Test
    fun testResetWhenLessThanQuarterRemainingAfterOneHour() {
        val pkg = "com.test.app"
        val limitMs = 20 * 60 * 1000L // 20 mins = 1,200,000 ms
        val quarterLimitMs = limitMs / 4 // 5 mins = 300,000 ms

        var now = 1_000_000_000_000L

        // App used down to 3 minutes (< 5 min quarter limit)
        val remainingBudget = 3 * 60 * 1000L
        BudgetManager.recordBudgetProgress(fakePrefs, pkg, remainingBudget, now)

        // At 30 minutes later: should NOT reset yet
        now += 30 * 60 * 1000L
        val budgetAt30Min = BudgetManager.getOrResetBudgetMs(fakePrefs, pkg, now)
        assertEquals(remainingBudget, budgetAt30Min)

        // At 61 minutes later: should reset to full limit
        now += 31 * 60 * 1000L // Total 61 mins passed
        val budgetAt61Min = BudgetManager.getOrResetBudgetMs(fakePrefs, pkg, now)
        assertEquals(limitMs, budgetAt61Min)
    }

    @Test
    fun testNoResetWhenMoreThanQuarterRemainingAndRecentlyActive() {
        val pkg = "com.test.app"
        val limitMs = 20 * 60 * 1000L
        var now = 1_000_000_000_000L

        // App used down to 15 minutes (> quarter limit of 5 mins)
        val remainingBudget = 15 * 60 * 1000L
        BudgetManager.recordBudgetProgress(fakePrefs, pkg, remainingBudget, now)

        // Check 30 minutes later
        now += 30 * 60 * 1000L
        val budget = BudgetManager.getOrResetBudgetMs(fakePrefs, pkg, now)
        assertEquals(remainingBudget, budget)
    }

    @Test
    fun testResetAfterOneHourOfInactivityRegardlessOfBudget() {
        val pkg = "com.test.app"
        val limitMs = 20 * 60 * 1000L
        var now = 1_000_000_000_000L

        // App had 12 minutes left
        val remainingBudget = 12 * 60 * 1000L
        BudgetManager.recordBudgetProgress(fakePrefs, pkg, remainingBudget, now)

        // 65 minutes of inactivity pass
        now += 65 * 60 * 1000L
        val budget = BudgetManager.getOrResetBudgetMs(fakePrefs, pkg, now)
        assertEquals(limitMs, budget)
    }

    @Test
    fun testResetAfterCooldownFinishes() {
        val pkg = "com.test.app"
        val limitMs = 20 * 60 * 1000L
        var now = 1_000_000_000_000L

        // Cooldown starts for 10 minutes
        val cooldownMs = BudgetManager.startCooldown(fakePrefs, pkg, 10.0f, now)
        assertEquals(10 * 60 * 1000L, cooldownMs)

        // While in cooldown (5 mins in)
        now += 5 * 60 * 1000L
        val inCooldownBudget = BudgetManager.getOrResetBudgetMs(fakePrefs, pkg, now)
        assertEquals(0L, inCooldownBudget)

        // After cooldown finishes (11 mins in)
        now += 6 * 60 * 1000L
        val resetBudget = BudgetManager.getOrResetBudgetMs(fakePrefs, pkg, now)
        assertEquals(limitMs, resetBudget)
    }
}

/**
 * Minimal in-memory implementation of SharedPreferences for unit tests.
 */
class FakeSharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any>()

    override fun getAll(): Map<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        (map[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor(map)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class Editor(private val map: MutableMap<String, Any>) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            key?.let { temp[it] = values }
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { temp[it] = value }
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { temp[it] = null }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            temp.clear()
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            for ((k, v) in temp) {
                if (v == null) {
                    map.remove(k)
                } else {
                    map[k] = v
                }
            }
        }
    }
}
