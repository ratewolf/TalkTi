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
        .connectTimeout(Duration.ofSeconds(30)) // 연결 대기 시간을 30초로 증가
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
            .timeout(Duration.ofMinutes(5)) // 분석 대기 시간을 5분으로 대폭 증가
            .build()

        return try {
            val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val ollamaRes = json.decodeFromString(OllamaResponse.serializer(), response.body())
                extractJson(ollamaRes.response)
            } else {
                println("❌ Ollama 에러: 상태 코드 ${response.statusCode()}")
                println("❌ 에러 내용: ${response.body()}")
                null
            }
        } catch (e: Exception) {
            println("❌ Ollama 통신 중 예외 발생: ${e.javaClass.simpleName} - ${e.message}")
            if (e is java.net.http.HttpTimeoutException) {
                println("⚠️ 타임아웃이 발생했습니다. 모델 로딩 중이거나 서버 사양이 부족할 수 있습니다.")
            }
            null
        }
    }

    private fun extractJson(text: String): String {
        val pattern = Pattern.compile("\\{.*\\}", Pattern.DOTALL)
        val matcher = pattern.matcher(text)
        return if (matcher.find()) {
            matcher.group(0)
        } else {
            text
        }
    }
}
