package kr.ac.kopo.talkti

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

// ⭐️ 추가된 Import 문 (JSON 파싱용)
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

import kr.ac.kopo.talkti.models.ScreenStateRequest
import kr.ac.kopo.talkti.models.GuideActionResponse
import kr.ac.kopo.talkti.models.RectDto

// (팁) SERVER_PORT 변수가 정의되지 않았다면 8080으로 직접 적어주셔도 됩니다.
val SERVER_PORT = 8080

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {

    // ⭐️ 핵심 추가: JSON 데이터를 객체(DTO)로 상호 변환해주는 플러그인 설치
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true // 클라이언트가 추가 데이터를 보내도 에러 없이 무시
            prettyPrint = true
        })
    }

    routing {
        post("/analyze") {
            // 이제 ContentNegotiation 덕분에 receive<ScreenStateRequest>()가 정상 작동합니다!
            val request = call.receive<ScreenStateRequest>()
            println("서버 데이터 수신 성공! 명령: ${request.userVoiceCommand}")

            val mockResponse = GuideActionResponse(
                actionType = "CLICK",
                targetBounds = RectDto(500, 1200, 800, 1400),
                ttsMessage = "화면 하단의 노란색 호출 버튼을 눌러주세요."
            )

            call.respond(mockResponse)
        }
    }
}