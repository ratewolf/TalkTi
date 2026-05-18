package kr.ac.kopo.talkti

import androidx.compose.runtime.Composable

expect class PlatformSettings {
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

@Composable
expect fun rememberSettings(): PlatformSettings
