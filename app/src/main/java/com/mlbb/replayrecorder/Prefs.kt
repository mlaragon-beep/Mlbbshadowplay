package com.mlbb.replayrecorder

import android.content.Context

object Prefs {
    const val HEIGHT = "height"
    const val FPS = "fps"
    const val BITRATE = "bitrate"
    const val BUFFER = "buffer"
    const val AUDIO = "audio"
    const val BUBBLE = "bubble"

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun getInt(c: Context, key: String, def: Int): Int = sp(c).getInt(key, def)
    fun setInt(c: Context, key: String, v: Int) = sp(c).edit().putInt(key, v).apply()
    fun getBool(c: Context, key: String, def: Boolean): Boolean = sp(c).getBoolean(key, def)
    fun setBool(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()
}
