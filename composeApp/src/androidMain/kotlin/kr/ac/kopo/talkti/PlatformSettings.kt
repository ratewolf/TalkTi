package kr.ac.kopo.talkti

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

actual class PlatformSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("talkti_prefs", Context.MODE_PRIVATE)
    
    actual fun getString(key: String, defaultValue: String): String =
        prefs.getString(key, defaultValue) ?: defaultValue

    actual fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

@Composable
actual fun rememberSettings(): PlatformSettings {
    val context = LocalContext.current
    return remember { PlatformSettings(context) }
}
