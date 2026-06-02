package kr.ac.kopo.talkti

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.prefs.Preferences

actual class PlatformSettings {
    private val prefs = Preferences.userNodeForPackage(PlatformSettings::class.java)

    actual fun getString(key: String, defaultValue: String): String =
        prefs.get(key, defaultValue)

    actual fun putString(key: String, value: String) {
        prefs.put(key, value)
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    actual fun putBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
    }
}

@Composable
actual fun rememberSettings(): PlatformSettings = remember { PlatformSettings() }
