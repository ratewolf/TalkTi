package kr.ac.kopo.talkti.models

/**
 * 음성 기반 선택 흐름의 단계를 표현하는 상태 봉투.
 *
 * SelectionSession(데이터)은 변하지 않고, SelectionFlow(상태)만 전이된다.
 *
 * 전이 흐름:
 *   Idle
 *     └─► Presenting      (세션 시작 — 질문 + 옵션 목록을 TTS로 제시)
 *           └─► AwaitingVoice  (음성 입력 대기 중)
 *                 ├─► Resolved   (사용자가 유효한 옵션을 선택·확정)
 *                 └─► Cancelled  (사용자가 취소하거나 타임아웃)
 */
sealed class SelectionFlow {

    /** 활성 선택 세션 없음 */
    object Idle : SelectionFlow()

    /** 세션이 시작되어 사용자에게 옵션 목록을 제시하는 중 */
    data class Presenting(
        val session: SelectionSession
    ) : SelectionFlow()

    /** 사용자의 음성 응답을 기다리는 중 */
    data class AwaitingVoice(
        val session: SelectionSession
    ) : SelectionFlow()

    /** 사용자가 옵션을 선택하고 확정한 최종 상태 */
    data class Resolved(
        val session: SelectionSession,
        val selected: Option
    ) : SelectionFlow()

    /** 사용자가 취소했거나 타임아웃 등으로 흐름이 중단된 상태 */
    data class Cancelled(
        val session: SelectionSession
    ) : SelectionFlow()
}
