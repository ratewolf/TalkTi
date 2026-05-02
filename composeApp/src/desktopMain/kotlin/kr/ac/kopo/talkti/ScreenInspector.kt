package kr.ac.kopo.talkti

import kr.ac.kopo.talkti.models.UiElement
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

actual class ScreenInspector actual constructor() {
    actual fun getUiTree(): List<UiElement> {
        return try {
            val scriptPaths = listOf(
                "src/desktopMain/python/get_ui_tree.py",
                "composeApp/src/desktopMain/python/get_ui_tree.py"
            )
            
            val scriptFile = scriptPaths.map { File(it) }.firstOrNull { it.exists() }
            
            if (scriptFile == null) {
                println("Error: Python script not found.")
                return emptyList()
            }

            val pythonBinary = when {
                File("/usr/bin/python3").exists() -> "/usr/bin/python3"
                File("/usr/bin/python").exists() -> "/usr/bin/python"
                else -> "python3"
            }

            val process = ProcessBuilder(pythonBinary, scriptFile.absolutePath).start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                println("Python script error (Exit Code $exitCode): ${errorReader.readText()}")
                return emptyList()
            }

            if (output.isBlank() || output.trim() == "[]") return emptyList()

            val json = Json { 
                ignoreUnknownKeys = true 
                coerceInputValues = true
            }
            json.decodeFromString<List<UiElement>>(output)
        } catch (e: Exception) {
            println("Failed to get UI tree from Linux: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
}
