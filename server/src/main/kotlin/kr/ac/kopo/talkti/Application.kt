package kr.ac.kopo.talkti

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kr.ac.kopo.talkti.models.ScreenStateRequest
import kr.ac.kopo.talkti.models.GuideActionResponse
import kr.ac.kopo.talkti.models.RectDto

// ⭐️ 파일 입출력 및 Base64 디코딩을 위한 패키지 추가
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

    routing {
        post("/analyze") {
            val request = call.receive<ScreenStateRequest>()
            println("서버 데이터 수신 성공! 명령: ${request.userVoiceCommand}")

            // ==========================================
            // 📁 1. 저장할 폴더(디렉토리) 생성
            // ==========================================
            val uploadDir = File("uploads")
            if (!uploadDir.exists()) {
                uploadDir.mkdirs() // 폴더가 없으면 새로 만듭니다
            }

            // 파일명 중복을 막기 위한 고유 타임스탬프
            val timestamp = System.currentTimeMillis()

            // ==========================================
            // 🖼️ 2. 스크린샷 이미지 (Base64 -> JPG 파일) 저장
            // ==========================================
            request.screenshotBase64?.let { base64String ->
                try {
                    // 안드로이드에서 보낸 Base64 문자열을 다시 바이트 배열로 해독
                    val imageBytes = Base64.getDecoder().decode(base64String)
                    val imageFile = File(uploadDir, "screenshot_$timestamp.jpg")
                    imageFile.writeBytes(imageBytes)
                    println("✅ 이미지 저장 완료: ${imageFile.absolutePath}")
                } catch (e: Exception) {
                    println("❌ 이미지 저장 실패: ${e.message}")
                }
            }

            // ==========================================
            // 🌳 3. UI 노드 정보 (String -> JSON 파일) 저장
            // ==========================================
            val uiTreeFile = File(uploadDir, "uitree_$timestamp.json")
            uiTreeFile.writeText(request.uiTreeJson)
            println("✅ UI 트리 저장 완료: ${uiTreeFile.absolutePath}")


            // 클라이언트에게 돌려줄 모의 응답
            val mockResponse = GuideActionResponse(
                actionType = "CLICK",
                targetBounds = RectDto(500, 1200, 800, 1400),
                ttsMessage = "화면 하단의 노란색 호출 버튼을 눌러주세요."
            )

            call.respond(mockResponse)
        }
    }
}