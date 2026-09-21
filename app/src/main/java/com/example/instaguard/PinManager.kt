package com.example.instaguard

import android.content.SharedPreferences
import java.security.MessageDigest

object PinManager {
    private const val PREF_PIN_HASH = "app_pin_hash"
    private const val PREF_PIN_ENABLED = "app_pin_enabled"

    fun isPinEnabled(prefs: SharedPreferences): Boolean {
        val enabled = prefs.getBoolean(PREF_PIN_ENABLED, false)
        val hash = prefs.getString(PREF_PIN_HASH, null)
        return enabled && !hash.isNullOrBlank()
    }

    fun setPin(prefs: SharedPreferences, pin: String): Boolean {
        if (!isValidPin(pin)) return false
        val hash = hashPin(pin)
        prefs.edit()
            .putString(PREF_PIN_HASH, hash)
            .putBoolean(PREF_PIN_ENABLED, true)
            .apply()
        return true
    }

    fun verifyPin(prefs: SharedPreferences, inputPin: String): Boolean {
        if (!isValidPin(inputPin)) return false
        val storedHash = prefs.getString(PREF_PIN_HASH, null) ?: return false
        val inputHash = hashPin(inputPin)
        return storedHash == inputHash
    }

    fun disablePin(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(PREF_PIN_ENABLED, false)
            .remove(PREF_PIN_HASH)
            .apply()
    }

    fun isValidPin(pin: String): Boolean {
        return pin.length == 4 && pin.all { it.isDigit() }
    }

    internal fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
