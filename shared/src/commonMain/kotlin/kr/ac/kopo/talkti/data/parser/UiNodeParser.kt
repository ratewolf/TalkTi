package kr.ac.kopo.talkti.data.parser

/**
 * 데이터 파트: UI 트리 노드 텍스트 정규화 및 파싱 담당
 */
class UiNodeParser {
    fun normalizeText(text: String): String {
        return text.trim().lowercase()
    }
}
