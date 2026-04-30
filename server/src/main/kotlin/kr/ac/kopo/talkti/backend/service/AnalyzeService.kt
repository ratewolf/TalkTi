package kr.ac.kopo.talkti.backend.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kr.ac.kopo.talkti.backend.service.llm.OllamaClient
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
    private val nodeParser: UiNodeParser = UiNodeParser(),
    private val ollamaClient: OllamaClient = OllamaClient()
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

    @Serializable
    private data class LlmResponse(
        val candidateId: String? = null,
        val actionType: String,
        val ttsMessage: String,
        val confidence: Double = 0.0
    )

    fun analyze(request: ScreenStateRequest): GuideActionResponse {
        val candidates = extractCandidates(request.uiTreeJson)
        
        // 1. LLM을 위한 데이터 단순화
        val simplifiedElements = candidates.map { 
            nodeParser.simplifyForLlm(it.candidateId, it.text, it.contentDesc, it.className, it.bounds)
        }
        val simplifiedJson = Json.encodeToString(simplifiedElements)

        // 2. 프롬프트 생성 및 LLM 호출
        val systemPrompt = PromptTemplates.ANALYZE_UI_SYSTEM_PROMPT
        val userPrompt = PromptTemplates.buildUserPrompt(request.userVoiceCommand, simplifiedJson)
        val combinedPrompt = "$systemPrompt\n\n$userPrompt"

        println("--- LLM 호출 시작 ---")
        val rawLlmRes = ollamaClient.generate(combinedPrompt, request.screenshotBase64)
        
        return if (rawLlmRes != null) {
            try {
                val llmRes = json.decodeFromString(LlmResponse.serializer(), rawLlmRes)
                println("✅ LLM 분석 성공: ${llmRes.ttsMessage} (Target: ${llmRes.candidateId})")

                // LLM이 선택한 candidateId에 해당하는 좌표 찾기
                val targetCandidate = candidates.find { it.candidateId == llmRes.candidateId }
                
                GuideActionResponse(
                    actionType = llmRes.actionType,
                    targetBounds = targetCandidate?.bounds,
                    ttsMessage = llmRes.ttsMessage,
                    targetCandidateId = llmRes.candidateId,
                    confidence = llmRes.confidence,
                    screenSessionId = request.screenSessionId
                )
            } catch (e: Exception) {
                println("❌ LLM 응답 파싱 실패: ${e.message}")
                fallbackResponse(candidates, request)
            }
        } else {
            println("⚠️ LLM 호출 실패 - Fallback 실행")
            fallbackResponse(candidates, request)
        }
    }

    private fun fallbackResponse(candidates: List<UiCandidate>, request: ScreenStateRequest): GuideActionResponse {
        val firstCandidate = candidates.firstOrNull()
        return if (firstCandidate != null) {
            GuideActionResponse(
                actionType = "CLICK",
                targetBounds = firstCandidate.bounds,
                ttsMessage = "${firstCandidate.text.ifBlank { "표시된 부분" }}을(를) 눌러주세요.",
                targetCandidateId = firstCandidate.candidateId,
                confidence = 0.5,
                screenSessionId = request.screenSessionId
            )
        } else {
            GuideActionResponse(
                actionType = "ASK_USER",
                targetBounds = null,
                ttsMessage = "화면 분석에 실패했어요. 다시 한 번 말씀해 주시겠어요?",
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
