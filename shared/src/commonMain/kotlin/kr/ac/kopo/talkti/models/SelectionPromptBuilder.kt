package kr.ac.kopo.talkti.models

class SelectionPromptBuilder {

    /**
     * Candidate 정보를 기반으로 사용자에게 읽어줄 질문 문장을 생성합니다.
     * 현재는 길찾기 목적지 질문 중심으로 간단하게 구현되어 있습니다.
     * (추후 Session 도메인이나 설정에 따라 확장 가능)
     *
     * @param candidate 질문 대상 후보
     * @return TTS로 읽어줄 질문 문자열
     */
    fun buildCandidateQuestion(candidate: Candidate): String {
        val targetText = candidate.text.trim()
        
        if (targetText.isEmpty()) {
            return "여기로 안내할까요?"
        }

        // 목적지를 자연스럽게 묻기 위한 조사 처리 ('로'/'으로')
        val postposition = getDirectionPostposition(targetText)
        
        return "${targetText}${postposition} 가실까요?"
    }

    /**
     * 한글 단어의 마지막 글자 받침 유무에 따라 '로' 또는 '으로'를 반환합니다.
     */
    private fun getDirectionPostposition(text: String): String {
        val lastChar = text.last()
        
        // 한글 가~힣 범위 내인지 확인
        if (lastChar in '\uAC00'..'\uD7A3') {
            val jongseong = (lastChar - '\uAC00') % 28
            // 받침이 없거나(0), 'ㄹ' 받침(8)인 경우 '로'
            if (jongseong == 0 || jongseong == 8) {
                return "로"
            }
            // 그 외 받침이 있는 경우 '으로'
            return "으로"
        }
        
        // 한글이 아니거나 범위를 벗어난 경우 기본값
        return "으로"
    }
}
