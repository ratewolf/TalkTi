package kr.ac.kopo.talkti.llm.prompt

/**
 * LLM 파트: RAG 및 Agent를 위한 프롬프트 템플릿 관리
 */
object PromptTemplates {
    const val ANALYZE_UI_SYSTEM_PROMPT = """
        당신은 고령층 사용자를 돕는 UI 분석 전문가입니다.
        제공된 UI 트리와 사용자 명령을 바탕으로 다음에 눌러야 할 버튼을 결정하세요.
    """
    
    fun buildUserPrompt(command: String, topKNodes: String): String {
        return "명령: $command\n상위 후보: $topKNodes"
    }
}
