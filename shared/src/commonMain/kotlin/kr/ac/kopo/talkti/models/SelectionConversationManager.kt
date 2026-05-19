package kr.ac.kopo.talkti.models

class SelectionConversationManager {

    var currentFlow: SelectionFlow = SelectionFlow.Idle
        private set

    var currentCandidateIndex: Int = -1
        private set

    /**
     * 새로운 선택 세션 시작
     */
    fun startSession(session: SelectionSession) {

        if (session.candidates.isEmpty()) {
            currentFlow = SelectionFlow.Cancelled(session)
            return
        }

        currentCandidateIndex = 0

        // TODO:
        // 이후 실제 TTS 질문 출력 단계에서
        // Presenting -> AwaitingVoice 흐름 연결 예정
        currentFlow = SelectionFlow.Presenting(session)
    }

    /**
     * 사용자 응답 처리
     */
    fun handleResponse(response: UserResponse) {

        val session = getSessionFromFlow() ?: return
        when (response) {
            UserResponse.YES -> {
                val candidate = getCurrentCandidate()
                if (candidate != null) {
                    currentFlow = SelectionFlow.Resolved(
                        session = session,
                        selected = candidate
                    )
                } else {
                    currentFlow = SelectionFlow.Cancelled(session)
                }
            }
            UserResponse.NO -> {
                moveToNextCandidate()
            }
            UserResponse.CANCEL -> {
                currentFlow = SelectionFlow.Cancelled(session)
            }
            UserResponse.UNKNOWN -> {
                // 알 수 없는 응답은 현재 상태 유지
            }
        }
    }

    /**
     * 다음 후보 이동
     */
    fun moveToNextCandidate() {

        val session = getSessionFromFlow() ?: return

        if (currentCandidateIndex < session.candidates.size - 1) {
            currentCandidateIndex++
            currentFlow = SelectionFlow.AwaitingVoice(session)
        } else {
            currentFlow = SelectionFlow.CandidatesExhausted(session)
        }
    }

    /**
     * 현재 질문 중인 Candidate 반환
     */
    fun getCurrentCandidate(): Candidate? {

        val session = getSessionFromFlow() ?: return null
        return session.candidates.getOrNull(currentCandidateIndex)
    }

    /**
     * 현재 Flow에서 Session 추출
     */
    private fun getSessionFromFlow(): SelectionSession? {

        return when (val flow = currentFlow) {
            is SelectionFlow.Presenting -> flow.session
            is SelectionFlow.AwaitingVoice -> flow.session
            is SelectionFlow.Resolved -> flow.session
            is SelectionFlow.Cancelled -> flow.session
            is SelectionFlow.CandidatesExhausted -> flow.session
            SelectionFlow.Idle -> null
        }
    }
}