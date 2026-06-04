package kr.ac.kopo.talkti.backend.service.llm

import kr.ac.kopo.talkti.backend.config.ConfigLoader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * LLM 파트: Anthropic Claude API 연동 클라이언트
 */
class ClaudeClient(
    private val apiKey: String = ConfigLoader.get("CLAUDE_API_KEY"),
    private val model: String = ConfigLoader.get("CLAUDE_MODEL", "claude-3-5-sonnet-20240620")
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true 
    }

    @Serializable
    private data class ClaudeRequest(
        val model: String,
        val max_tokens: Int = 1024,
        val messages: List<ClaudeMessage>
    )

    @Serializable
    private data class ClaudeMessage(
        val role: String,
        val content: List<ClaudeContent>
    )

    @Serializable
    private data class ClaudeContent(
        val type: String,
        val text: String? = null,
        val source: ClaudeImageSource? = null
    )

    @Serializable
    private data class ClaudeImageSource(
        val type: String = "base64",
        val media_type: String = "image/jpeg",
        val data: String
    )

    @Serializable
    private data class ClaudeResponse(
        val content: List<ClaudeResponseContent>
    )

    @Serializable
    private data class ClaudeResponseContent(
        val text: String? = null
    )

    fun generate(prompt: String, base64Image: String? = null): String? {
        val contents = mutableListOf<ClaudeContent>()
        
        // 이미지가 있으면 먼저 추가 (Claude 가이드 권장 사항)
        if (base64Image != null) {
            contents.add(ClaudeContent(
                type = "image",
                source = ClaudeImageSource(data = base64Image)
            ))
        }
        
        contents.add(ClaudeContent(type = "text", text = prompt))

        val requestBody = ClaudeRequest(
            model = model,
            messages = listOf(ClaudeMessage(role = "user", content = contents))
        )

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(ClaudeRequest.serializer(), requestBody)))
            .timeout(Duration.ofMinutes(2))
            .build()

        return try {
            val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val claudeRes = json.decodeFromString(ClaudeResponse.serializer(), response.body())
                claudeRes.content.firstOrNull()?.text
            } else {
                println("❌ Claude API 에러: 상태 코드 ${response.statusCode()} - ${response.body()}")
                null
            }
        } catch (e: Exception) {
            println("❌ Claude 통신 중 예외 발생: ${e.message}")
            null
        }
    }
}
