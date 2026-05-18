package kr.ac.kopo.talkti.selection

import kr.ac.kopo.talkti.models.Option
import kr.ac.kopo.talkti.models.SelectionFlow
import kr.ac.kopo.talkti.models.SelectionSession

/**
 * 음성 입력을 Option과 매칭하고 SelectionFlow 상태를 전이시키는 범용 실행 엔진.
 *
 * 특정 도메인(길찾기, 택시, 카카오톡 등)에 종속되지 않는다.
 * SelectionSession을 주입받아 동작하므로 외부에서 세션을 구성하여 전달하면 된다.
 *
 * 상태 전이:
 *   start()       → Presenting
 *   readyToListen() → AwaitingVoice
 *   processVoice()  → Resolved | AwaitingVoice(재시도) | Cancelled
 *   cancel()      → Cancelled
 */
object VoiceSelectionEngine {

    // ──────────────────────────────────────────────
    // 순서 기반 매칭 테이블 (한국어 자연어 표현 → 0-based index)
    // ──────────────────────────────────────────────
    private val ORDINAL_MAP: Map<String, Int> = mapOf(
        // 1번째
        "첫번째" to 0, "첫 번째" to 0, "하나" to 0, "일번" to 0,
        "1번" to 0, "1" to 0, "첫" to 0,
        // 2번째
        "두번째" to 1, "두 번째" to 1, "둘" to 1, "이번" to 1,
        "2번" to 1, "2" to 1,
        // 3번째
        "세번째" to 2, "세 번째" to 2, "셋" to 2, "삼번" to 2,
        "3번" to 2, "3" to 2,
        // 4번째
        "네번째" to 3, "네 번째" to 3, "넷" to 3, "사번" to 3,
        "4번" to 3, "4" to 3,
        // 5번째
        "다섯번째" to 4, "다섯 번째" to 4, "다섯" to 4, "오번" to 4,
        "5번" to 4, "5" to 4
    )

    // 취소 키워드
    private val CANCEL_KEYWORDS = setOf("취소", "아니요", "아니", "없어", "없어요", "그만")

    // ──────────────────────────────────────────────
    // 상태 전이 함수
    // ──────────────────────────────────────────────

    /** 새 세션을 시작한다 → Presenting */
    fun start(session: SelectionSession): SelectionFlow =
        SelectionFlow.Presenting(session)

    /** 옵션 안내가 끝나고 음성 입력을 기다리는 단계로 전이 → AwaitingVoice */
    fun readyToListen(flow: SelectionFlow.Presenting): SelectionFlow =
        SelectionFlow.AwaitingVoice(flow.session)

    /**
     * 사용자 음성 입력을 처리하여 상태를 전이한다.
     *
     * @return Resolved  — 매칭 성공
     *         AwaitingVoice — 매칭 실패 (재시도 허용)
     *         Cancelled — 취소 키워드 감지
     */
    fun processVoice(
        flow: SelectionFlow.AwaitingVoice,
        voiceInput: String
    ): SelectionFlow {
        val trimmed = voiceInput.trim()

        if (isCancelIntent(trimmed)) {
            return SelectionFlow.Cancelled(flow.session)
        }

        val matched = matchOption(flow.session, trimmed)
        return if (matched != null) {
            SelectionFlow.Resolved(flow.session, matched)
        } else {
            // 매칭 실패 → 동일 세션으로 재대기 (호출자가 재시도 TTS를 출력)
            SelectionFlow.AwaitingVoice(flow.session)
        }
    }

    /** 외부에서 명시적으로 세션을 취소한다 → Cancelled */
    fun cancel(session: SelectionSession): SelectionFlow =
        SelectionFlow.Cancelled(session)

    // ──────────────────────────────────────────────
    // 매칭 내부 로직
    // ──────────────────────────────────────────────

    private fun isCancelIntent(input: String): Boolean =
        CANCEL_KEYWORDS.any { input.contains(it) }

    private fun matchOption(session: SelectionSession, input: String): Option? {
        val normalized = input.replace(" ", "")
        // 1순위: 순서 기반 매칭 ("첫 번째", "2번" 등)
        matchByOrdinal(normalized, session.options)?.let { return it }
        // 2순위: 텍스트 완전 일치
        matchByExactText(input, session.options)?.let { return it }
        // 3순위: 텍스트 포함 일치 (부분 매칭)
        return matchByContains(input, session.options)
    }

    private fun matchByOrdinal(normalizedInput: String, options: List<Option>): Option? {
        val index = ORDINAL_MAP[normalizedInput] ?: return null
        return options.getOrNull(index)
    }

    private fun matchByExactText(input: String, options: List<Option>): Option? {
        val lower = input.lowercase()
        return options.firstOrNull { it.text.lowercase() == lower }
    }

    private fun matchByContains(input: String, options: List<Option>): Option? {
        val lower = input.lowercase()
        // option.text가 입력에 포함되거나, 입력이 option.text에 포함
        return options.firstOrNull { option ->
            val optLower = option.text.lowercase()
            lower.contains(optLower) || optLower.contains(lower)
        }
    }
}
