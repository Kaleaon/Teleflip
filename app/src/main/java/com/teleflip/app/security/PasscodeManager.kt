package com.teleflip.app.security

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

object PasscodeManager {
    private const val PREFS_NAME = "teleflip_prefs"
    private const val PREF_PASSCODE_ENABLED = "pref_passcode_enabled"
    private const val PREF_PASSCODE_HASH = "pref_passcode_hash"
    private const val PREF_AUTO_LOCK_TIMEOUT = "pref_passcode_timeout_sec"

    private fun getPrefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    const val TIMEOUT_IMMEDIATE = 0
    const val TIMEOUT_1_MIN = 60
    const val TIMEOUT_5_MIN = 300
    private const val MILLIS_PER_SEC = 1000L

    private var isUnlockedSession = false
    private var lastBackgroundTimestamp = 0L

    fun isEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(PREF_PASSCODE_ENABLED, false) && prefs.getString(PREF_PASSCODE_HASH, null) != null
    }

    fun isLocked(context: Context): Boolean {
        if (!isEnabled(context)) return false
        return !isUnlockedSession
    }

    fun setPasscode(
        context: Context,
        pin: String,
    ) {
        val hash = hashPin(pin)
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(PREF_PASSCODE_ENABLED, true)
            .putString(PREF_PASSCODE_HASH, hash)
            .apply()
        isUnlockedSession = true
    }

    fun disablePasscode(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(PREF_PASSCODE_ENABLED, false)
            .remove(PREF_PASSCODE_HASH)
            .apply()
        isUnlockedSession = true
    }

    fun verifyPasscode(
        context: Context,
        pin: String,
    ): Boolean {
        val prefs = getPrefs(context)
        val savedHash = prefs.getString(PREF_PASSCODE_HASH, null) ?: return false
        return hashPin(pin) == savedHash
    }

    fun unlock(
        context: Context,
        pin: String,
    ): Boolean {
        if (verifyPasscode(context, pin)) {
            isUnlockedSession = true
            return true
        }
        return false
    }

    fun lock() {
        isUnlockedSession = false
    }

    fun getAutoLockTimeoutSeconds(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(PREF_AUTO_LOCK_TIMEOUT, TIMEOUT_IMMEDIATE)
    }

    fun setAutoLockTimeoutSeconds(
        context: Context,
        seconds: Int,
    ) {
        val prefs = getPrefs(context)
        prefs.edit().putInt(PREF_AUTO_LOCK_TIMEOUT, seconds).apply()
    }

    fun getAutoLockLabel(context: Context): String {
        return when (getAutoLockTimeoutSeconds(context)) {
            TIMEOUT_1_MIN -> "1 Minute"
            TIMEOUT_5_MIN -> "5 Minutes"
            else -> "Immediately"
        }
    }

    fun onAppBackgrounded() {
        lastBackgroundTimestamp = System.currentTimeMillis()
    }

    fun onAppForegrounded(context: Context) {
        if (!isEnabled(context)) return
        val timeoutSec = getAutoLockTimeoutSeconds(context)
        val elapsedSec = (System.currentTimeMillis() - lastBackgroundTimestamp) / MILLIS_PER_SEC
        if (lastBackgroundTimestamp > 0L && elapsedSec >= timeoutSec) {
            isUnlockedSession = false
        }
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
