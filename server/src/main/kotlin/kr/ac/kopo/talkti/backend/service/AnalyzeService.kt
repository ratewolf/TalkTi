package kr.ac.kopo.talkti.backend.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kr.ac.kopo.talkti.data.parser.UiNodeParser
import kr.ac.kopo.talkti.llm.prompt.PromptTemplates
import kr.ac.kopo.talkti.models.GuideActionResponse
import kr.ac.kopo.talkti.models.RectDto
import kr.ac.kopo.talkti.models.ScreenStateRequest

/**
 * 백엔드 파트: 분석 오케스트레이션 담당
 */
class AnalyzeService(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nodeParser: UiNodeParser = UiNodeParser()
) {
    data class UiCandidate(
        val candidateId: String,
        val bounds: RectDto,
        val clickable: Boolean,
        val enabled: Boolean,
        val visibleToUser: Boolean,
        val text: String,
        val contentDesc: String,
        val className: String
    )

    fun analyze(request: ScreenStateRequest): GuideActionResponse {
        val candidates = extractCandidates(request.uiTreeJson)
        
        // 1. LLM을 위한 데이터 단순화
        val simplifiedElements = candidates.map { 
            nodeParser.simplifyForLlm(it.candidateId, it.text, it.contentDesc, it.className, it.bounds)
        }
        val simplifiedJson = Json.encodeToString(simplifiedElements)

        // 2. 프롬프트 생성 (실제 LLM 호출 전 단계)
        val systemPrompt = PromptTemplates.ANALYZE_UI_SYSTEM_PROMPT
        val userPrompt = PromptTemplates.buildUserPrompt(request.userVoiceCommand, simplifiedJson)
        
        println("--- LLM 호출 준비 ---")
        println("[System]: $systemPrompt")
        println("[User]: $userPrompt")

        // 3. 현재는 Mock 응답이지만, 정제된 후보 중 첫 번째를 사용하도록 개선
        val firstCandidate = candidates.firstOrNull()

        return if (firstCandidate != null) {
            GuideActionResponse(
                actionType = "CLICK",
                targetBounds = firstCandidate.bounds,
                ttsMessage = "${firstCandidate.text.ifBlank { "해당 버튼" }}을(를) 눌러주세요.",
                targetCandidateId = firstCandidate.candidateId,
                confidence = 0.8,
                screenSessionId = request.screenSessionId
            )
        } else {
            GuideActionResponse(
                actionType = "ASK_USER",
                targetBounds = null,
                ttsMessage = "화면에서 적절한 버튼을 찾지 못했어요. 다시 말씀해 주세요.",
                targetCandidateId = null,
                confidence = 0.0,
                screenSessionId = request.screenSessionId
            )
        }
    }

    private fun extractCandidates(uiTreeJson: String): List<UiCandidate> {
        val elements = runCatching { json.parseToJsonElement(uiTreeJson).jsonArray }
            .getOrElse { JsonArray(emptyList()) }

        return elements.mapNotNull { element -> parseCandidate(element) }
            .filter { it.clickable && it.enabled && it.visibleToUser }
    }

    private fun parseCandidate(element: JsonElement): UiCandidate? {
        val obj = element as? JsonObject ?: return null
        val candidateId = obj["candidateId"]?.jsonPrimitive?.contentOrNull ?: return null
        val boundsObj = obj["bounds"]?.jsonObject ?: return null

        val bounds = RectDto(
            left = boundsObj["left"]?.jsonPrimitive?.intOrNull ?: return null,
            top = boundsObj["top"]?.jsonPrimitive?.intOrNull ?: return null,
            right = boundsObj["right"]?.jsonPrimitive?.intOrNull ?: return null,
            bottom = boundsObj["bottom"]?.jsonPrimitive?.intOrNull ?: return null
        )

        return UiCandidate(
            candidateId = candidateId,
            bounds = bounds,
            clickable = obj["clickable"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            visibleToUser = obj["visibleToUser"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            text = obj["text"]?.jsonPrimitive?.contentOrNull ?: "",
            contentDesc = obj["contentDescription"]?.jsonPrimitive?.contentOrNull ?: "",
            className = obj["className"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }
}
