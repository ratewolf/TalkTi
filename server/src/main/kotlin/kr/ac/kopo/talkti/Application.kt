package kr.ac.kopo.talkti

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kr.ac.kopo.talkti.backend.service.AnalyzeService
import kr.ac.kopo.talkti.backend.storage.FileStorage
import kr.ac.kopo.talkti.backend.validator.RequestValidator
import kr.ac.kopo.talkti.models.ScreenStateRequest
import kr.ac.kopo.talkti.models.GuideScreenRequest
import kr.ac.kopo.talkti.backend.service.GuideService
import kr.ac.kopo.talkti.backend.experience.ExperienceDatabase
import kr.ac.kopo.talkti.backend.experience.ExperienceService
import kr.ac.kopo.talkti.backend.experience.RecordTransitionRequest
import kr.ac.kopo.talkti.backend.experience.CompleteSessionRequest

val SERVER_PORT = 8080

@kotlinx.serialization.Serializable
data class StartSessionRequest(val userCommand: String)

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
    val guideService = GuideService()
    val fileStorage = FileStorage()
    val validator = RequestValidator()
    
    val experienceService = ExperienceService()
    // DB 초기화 (앱 시작 시 1회)
    ExperienceDatabase.getConnection()

    routing {
        get("/") {
            call.respondText("TalkTi Server is Running!")
        }
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
        post("/guide") {
            val request = call.receive<GuideScreenRequest>()
            println("Guide 요청 수신: cmd='${request.userCommand}', pkg='${request.packageName}', prevState='${request.previousState}'")
            val response = guideService.analyze(request)
            call.respond(response)
        }
        // ── 경험 기반 학습 API ──
        post("/experience/session/start") {
            val body = call.receive<StartSessionRequest>()
            val response = experienceService.startSession(body.userCommand)
            call.respond(response)
        }
        post("/experience/transition") {
            val request = call.receive<RecordTransitionRequest>()
            experienceService.recordTransition(request)
            call.respond(mapOf("ok" to true))
        }
        post("/experience/session/complete") {
            val request = call.receive<CompleteSessionRequest>()
            experienceService.completeSession(request)
            call.respond(mapOf("ok" to true))
        }
    }
}
