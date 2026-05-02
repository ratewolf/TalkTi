package kr.ac.kopo.talkti

import androidx.compose.runtime.Composable

expect class PlatformSettings {
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
}

@Composable
expect fun rememberSettings(): PlatformSettings
