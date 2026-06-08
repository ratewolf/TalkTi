package kr.ac.kopo.talkti.models

import kotlinx.serialization.Serializable

/**
 * /guide 엔드포인트의 LLM 응답 모델.
 *
 * 서버가 UI Tree를 분석한 뒤 클라이언트에게
 * "지금 사용자가 해야 할 행동"을 알려준다.
 */
@Serializable
data class GuideScreenResponse(
    /** 행동 중심 상태 (SELECT_TARGET, PRESS_ACTION, SELECT_OPTION, CONFIRM, COMPLETE) */
    val state: String,

    /** 오버레이를 표시할 대상 목록 */
    val targets: List<GuideTarget> = emptyList(),

    /** TTS로 안내할 메시지 */
    val tts: String,

    /** 상태가 바뀌지 않은 경우 true — 오버레이/TTS를 재실행하지 않기 위한 플래그 */
    val unchanged: Boolean = false
)

/**
 * 오버레이 대상 단일 항목.
 */
@Serializable
data class GuideTarget(
    val candidateId: String,
    val text: String,
    val bounds: RectDto
)

/**
 * /guide 엔드포인트 요청 모델.
 *
 * 스크린샷을 사용하지 않고 UI Tree만 전송한다.
 */
@Serializable
data class GuideScreenRequest(
    /** 사용자의 원본 음성/텍스트 명령 */
    val userCommand: String,

    /** 현재 화면의 UI Tree JSON (UiElement 리스트) */
    val uiTreeJson: String,

    /** 현재 포그라운드 앱 패키지명 */
    val packageName: String,

    /** 이전 가이드 상태 (서버가 상태 변화를 판단하는 데 사용) */
    val previousState: String = "IDLE"
)
