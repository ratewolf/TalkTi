package kr.ac.kopo.talkti.models

import kotlinx.serialization.Serializable

/**
 * 하나의 "음성 선택 대화 단위"를 표현한다.
 *
 * 특정 도메인(길찾기, 택시, 카카오톡 등)에 종속되지 않는 범용 구조.
 *
 * @param sessionId  세션을 고유하게 식별하는 ID
 * @param question   사용자에게 제시할 질문 문자열 (TTS로 읽힘)
 * @param candidate    사용자가 선택할 후보 목록
 */
@Serializable
data class SelectionSession(
    val sessionId: String,
    val question: String,
    val candidates: List<Candidate>
)
