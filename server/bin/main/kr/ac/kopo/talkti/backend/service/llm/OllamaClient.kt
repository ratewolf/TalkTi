package kr.ac.kopo.talkti.backend.service.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.regex.Pattern

/**
 * LLM 파트: Ollama API 연동 클라이언트
 */
class OllamaClient(
    private val ollamaUrl: String = "http://ollama.aikopo.net:8080/api/generate",
    private val model: String = "gemma4:26b"
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true 
    }

    @Serializable
    private data class OllamaRequest(
        val model: String,
        val prompt: String,
        val stream: Boolean = false,
        val images: List<String>? = null
    )

    @Serializable
    private data class OllamaResponse(
        val response: String
    )

    fun generate(prompt: String, base64Image: String? = null): String? {
        val requestBody = OllamaRequest(
            model = model,
            prompt = prompt,
            images = base64Image?.let { listOf(it) }
        )

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(ollamaUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(OllamaRequest.serializer(), requestBody)))
            .timeout(Duration.ofMinutes(5)) // 다시 5분으로 복구
            .build()

        return try {
            val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val ollamaRes = json.decodeFromString(OllamaResponse.serializer(), response.body())
                val extracted = extractJson(ollamaRes.response)
                println("✅ 분석 결과 추출 성공: $extracted")
                extracted
            } else {
                println("❌ Ollama 에러: 상태 코드 ${response.statusCode()} - ${response.body()}")
                null
            }
        } catch (e: Exception) {
            println("❌ Ollama 통신 중 예외 발생: ${e.message}")
            null
        }
    }

    /**
     * LLM 응답에서 JSON 부분만 안전하게 추출합니다.
     * 마크다운 태그(```json ... ```)가 포함된 경우도 처리합니다.
     */
    private fun extractJson(text: String): String {
        // 1. 마크다운 코드 블록 제거 시도
        var cleaned = text.trim()
        if (cleaned.contains("```json")) {
            cleaned = cleaned.substringAfter("```json").substringBeforeLast("```")
        } else if (cleaned.contains("```")) {
            cleaned = cleaned.substringAfter("```").substringBeforeLast("```")
        }

        // 2. 가장 처음 나타나는 { 와 마지막 } 사이 추출
        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')

        return if (firstBrace != -1 && lastBrace != -1 && firstBrace < lastBrace) {
            cleaned.substring(firstBrace, lastBrace + 1)
        } else {
            cleaned
        }
    }
}
