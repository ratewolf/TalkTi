package kr.ac.kopo.talkti.models

import kotlinx.serialization.Serializable

// 1. 안드로이드 앱 -> 서버 (요청 데이터)
@Serializable
data class ScreenStateRequest(
    val userVoiceCommand: String, // 사용자의 음성 명령 (예: "택시 호출해줘")
    val uiTreeJson: String,       // AccessibilityService로 추출한 화면 구조 데이터
    val screenshotBase64: String? = null, // 스크린샷 이미지 (선택적)
    val screenSessionId: String? = null
)

// 2. 서버(LLM) -> 안드로이드 앱 (응답 데이터)
@Serializable
data class GuideActionResponse(
    val actionType: String,       // 행동 유형 (예: "CLICK", "SCROLL", "SPEAK")
    val targetBounds: RectDto?,   // 클릭해야 할 버튼의 화면 좌표
    val ttsMessage: String,       // 사용자에게 읽어줄 안내 음성
    val targetCandidateId: String? = null,
    val confidence: Double? = null,
    val screenSessionId: String? = null
)

@Serializable
data class RectDto(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)