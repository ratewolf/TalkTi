package kr.ac.kopo.talkti

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.ac.kopo.talkti.models.ScreenStateRequest

fun main() = application {
    val screenInspector = remember { ScreenInspector() }
    val scope = rememberCoroutineScope()

    // Desktop용 Ktor 클라이언트
    val client = remember {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "TalkTi Desktop",
    ) {
        App(
            onStartSetupClick = {
                println("리눅스 UI 트리 추출 및 서버 전송 시작...")
                val tree = screenInspector.getUiTree()

                if (tree.isEmpty()) {
                    println("UI 요소를 찾지 못했습니다.")
                } else {
                    val uiTreeJson = Json.encodeToString(tree)
                    val sessionId = "desktop_${System.currentTimeMillis()}"

                    scope.launch {
                        try {
                            // 안드로이드와 동일한 형식으로 서버에 전송
                            // 서버 주소는 환경에 맞게 수정 필요 (예: http://localhost:8080/analyze)
                            val response = client.post("http://localhost:8080/analyze") {
                                contentType(ContentType.Application.Json)
                                setBody(ScreenStateRequest(
                                    userVoiceCommand = "리눅스 화면 분석",
                                    uiTreeJson = uiTreeJson,
                                    screenshotBase64 = null, // 리눅스 스크린샷 기능은 추후 구현 가능
                                    screenSessionId = sessionId
                                ))
                            }
                            println("서버 응답 상태: ${response.status}")
                        } catch (e: Exception) {
                            println("서버 전송 실패: ${e.message}")
                        }
                    }
                }
            }
        )
    }
}