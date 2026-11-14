package com.sahil.volumebutton

import android.content.Context
import android.content.SharedPreferences

/** Private (app-only) storage: overlay on/off aur button ki position. */
object Prefs {
    private const val FILE = "volume_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_ENABLED, false)

    fun setEnabled(c: Context, value: Boolean) {
        sp(c).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun getX(c: Context): Int = sp(c).getInt(KEY_X, 0)
    fun getY(c: Context): Int = sp(c).getInt(KEY_Y, 300)

    fun setPosition(c: Context, x: Int, y: Int) {
        sp(c).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }
}
