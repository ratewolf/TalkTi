package kr.ac.kopo.talkti.models

class SelectionPromptBuilder {

    /**
     * Candidate 정보를 기반으로 사용자에게 읽어줄 질문 문장을 생성합니다.
     *
     * @param candidate 질문 대상 후보
     * @param index 현재 인덱스 (0부터 시작)
     * @return TTS로 읽어줄 질문 문자열
     */
    fun buildCandidateQuestion(candidate: Candidate, index: Int): String {
        val targetText = candidate.text.trim()
        
        val prefix = when (index) {
            0 -> "첫 번째 장소입니다. "
            1 -> "그 다음은 "
            2 -> "세 번째 장소는 "
            else -> "그 다음 장소는 "
        }

        if (targetText.isEmpty()) {
            return "${prefix}여기가 맞으신가요?"
        }

        val postposition = getDirectionPostposition(targetText)
        return "${prefix}${targetText}${postposition} 가실까요?"
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
