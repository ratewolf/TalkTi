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
        val currentDir = File(".").absolutePath
        println("🔍 현재 작업 디렉토리: $currentDir")

        // 1. 현재 디렉토리에서 찾기
        var envFile = File(".env")
        
        // 2. 만약 없으면 상위 디렉토리에서 찾기 (멀티 모듈 프로젝트 대응)
        if (!envFile.exists()) {
            envFile = File("../.env")
        }

        if (envFile.exists()) {
            println("📂 .env 파일 발견: ${envFile.absolutePath}")
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
            println("⚠️ .env 파일을 찾을 수 없습니다. (검색 경로: ./.env 또는 ../.env)")
            println("💡 시스템 환경 변수를 사용합니다.")
        }
    }

    fun get(key: String, defaultValue: String = ""): String {
        return properties.getProperty(key) ?: System.getenv(key) ?: defaultValue
    }
}
