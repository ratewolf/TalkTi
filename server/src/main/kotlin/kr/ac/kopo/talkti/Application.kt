package kr.ac.kopo.talkti

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kr.ac.kopo.talkti.models.ScreenStateRequest
import java.io.File
import java.util.Base64

val SERVER_PORT = 8080

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        })
    }

    val analyzeService = AnalyzeService()

    routing {
        post("/analyze") {
            val request = call.receive<ScreenStateRequest>()
            val fallbackId = System.currentTimeMillis().toString()
            val sessionId = request.screenSessionId ?: fallbackId

            println("서버 데이터 수신 성공! 명령: ${request.userVoiceCommand}, sessionId: $sessionId")

            val uploadDir = File("uploads")
            if (!uploadDir.exists()) {
                uploadDir.mkdirs()
            }

            // ==========================================
            // 🖼️ 2. 스크린샷 이미지 (Base64 -> JPG 파일) 저장
            // ==========================================
            request.screenshotBase64?.let { base64String ->
                try {
                    val imageBytes = Base64.getDecoder().decode(base64String)
                    val imageFile = File(uploadDir, "screenshot_${sessionId}.jpg")
                    imageFile.writeBytes(imageBytes)
                    println("✅ 이미지 저장 완료: ${imageFile.absolutePath}")
                } catch (e: Exception) {
                    println("❌ 이미지 저장 실패: ${e.message}")
                }
            }

            // ==========================================
            // 🌳 3. UI 노드 정보 (String -> JSON 파일) 저장
            // ==========================================
            val uiTreeFile = File(uploadDir, "uitree_${sessionId}.json")
            uiTreeFile.writeText(request.uiTreeJson)
            println("✅ UI 트리 저장 완료: ${uiTreeFile.absolutePath}")

            val response = analyzeService.analyze(request)
            call.respond(response)

            // 클라이언트에게 돌려줄 모의 응답
            val mockResponse = when {
                request.userVoiceCommand.contains("택시") -> GuideActionResponse(
                    actionType = "CLICK",
                    targetBounds = RectDto(120, 780, 960, 920),
                    ttsMessage = "택시 호출 버튼을 눌러주세요.",
                    targetCandidateId = "candidate_0",
                    confidence = 0.92,
                    screenSessionId = request.screenSessionId
                )

                request.userVoiceCommand.contains("지도") ||
                        request.userVoiceCommand.contains("길") -> GuideActionResponse(
                    actionType = "CLICK",
                    targetBounds = RectDto(80, 180, 1000, 300),
                    ttsMessage = "상단 검색창을 눌러 목적지를 입력해주세요.",
                    targetCandidateId = "candidate_1",
                    confidence = 0.88,
                    screenSessionId = request.screenSessionId
                )

                else -> GuideActionResponse(
                    actionType = "SPEAK",
                    targetBounds = null,
                    ttsMessage = "원하는 작업을 다시 한 번 말씀해주세요.",
                    targetCandidateId = null,
                    confidence = 0.55,
                    screenSessionId = request.screenSessionId
                )
            }

            call.respond(mockResponse)
        }
    }
}
