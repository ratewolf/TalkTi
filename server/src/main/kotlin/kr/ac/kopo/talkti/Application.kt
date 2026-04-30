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
import kr.ac.kopo.talkti.backend.service.AnalyzeService
import kr.ac.kopo.talkti.backend.storage.FileStorage
import kr.ac.kopo.talkti.backend.validator.RequestValidator
import kr.ac.kopo.talkti.models.ScreenStateRequest

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
    val fileStorage = FileStorage()
    val validator = RequestValidator()

    routing {
        post("/analyze") {
            val request = call.receive<ScreenStateRequest>()
            
            if (!validator.validate(request)) {
                println("⚠️ 유효하지 않은 요청 수신")
                return@post
            }

            val sessionId = request.screenSessionId ?: System.currentTimeMillis().toString()
            println("서버 데이터 수신! 명령: ${request.userVoiceCommand}, sessionId: $sessionId")

            // 파일 저장 (Backend Storage 역할)
            request.screenshotBase64?.let { 
                fileStorage.saveScreenshot(sessionId, it)
            }
            fileStorage.saveUiTree(sessionId, request.uiTreeJson)

            // 분석 및 응답 (Backend Service 역할)
            val response = analyzeService.analyze(request)
            call.respond(response)
        }
    }
}
