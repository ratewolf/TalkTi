package kr.ac.kopo.talkti.data.parser

import kotlinx.serialization.Serializable
import kr.ac.kopo.talkti.models.RectDto

/**
 * 데이터 파트: UI 트리 노드 텍스트 정규화 및 파싱 담당
 */
class UiNodeParser {
    
    @Serializable
    data class SimplifiedElement(
        val candidateId: String,
        val interactionText: String, // 텍스트나 설명 중 더 중요한 것
        val type: String,
        val bounds: RectDto
    )

    /**
     * LLM이 읽기 편하도록 노드 정보를 요약합니다.
     */
    fun simplifyForLlm(
        candidateId: String,
        text: String,
        contentDesc: String,
        className: String,
        bounds: RectDto
    ): SimplifiedElement {
        val cleanText = text.ifBlank { contentDesc }.trim()
        val simpleType = className.substringAfterLast(".")
        
        return SimplifiedElement(
            candidateId = candidateId,
            interactionText = cleanText,
            type = simpleType,
            bounds = bounds
        )
    }

    fun normalizeText(text: String): String {
        return text.trim().lowercase()
    }
}
