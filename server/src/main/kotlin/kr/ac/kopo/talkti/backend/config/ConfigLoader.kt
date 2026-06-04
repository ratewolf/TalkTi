package kr.ac.kopo.talkti.backend.config

import java.io.File
import java.util.*

/**
 * .env 파일을 로드하여 설정을 관리하는 유틸리티
 */
object ConfigLoader {
    private val properties = Properties()

    init {
        loadDotEnv()
    }

    private fun loadDotEnv() {
        val envFile = File(".env")
        if (envFile.exists()) {
            envFile.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                        val key = trimmed.substringBefore("=").trim()
                        val value = trimmed.substringAfter("=").trim()
                        properties[key] = value
                    }
                }
            }
            println("✅ .env 설정 로드 완료")
        } else {
            println("⚠️ .env 파일을 찾을 수 없습니다. 시스템 환경 변수를 사용합니다.")
        }
    }

    fun get(key: String, defaultValue: String = ""): String {
        return properties.getProperty(key) ?: System.getenv(key) ?: defaultValue
    }
}
