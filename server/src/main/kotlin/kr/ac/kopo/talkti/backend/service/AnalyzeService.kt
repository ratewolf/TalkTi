package kr.ac.kopo.talkti.backend.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kr.ac.kopo.talkti.backend.service.llm.ClaudeClient
import kr.ac.kopo.talkti.backend.service.llm.OllamaClient
import kr.ac.kopo.talkti.data.parser.UiNodeParser
import kr.ac.kopo.talkti.llm.prompt.PromptTemplates
import kr.ac.kopo.talkti.models.AppInfo
import kr.ac.kopo.talkti.models.GuideActionResponse
import kr.ac.kopo.talkti.models.RectDto
import kr.ac.kopo.talkti.models.ScreenStateRequest

/**
 * 백엔드 파트: 분석 오케스트레이션 담당
 */
class AnalyzeService(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nodeParser: UiNodeParser = UiNodeParser(),
    private val claudeClient: ClaudeClient = ClaudeClient() // OllamaClient 대신 ClaudeClient 사용
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
        val arguments: String? = null,
        val ttsMessage: String,
        val confidence: Double = 0.0,
        val thought: String = ""
    )

    private val sessionHistories = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<String, String>>>()

    private fun getSessionHistoryText(sessionId: String): String {
        val history = sessionHistories[sessionId] ?: return "이전 대화 없음"
        if (history.isEmpty()) return "이전 대화 없음"
        return history.joinToString("\n") { (user, assistant) ->
            "사용자: $user\n똑띠: $assistant"
        }
    }

    private fun addMessageToHistory(sessionId: String, user: String, assistant: String) {
        val list = sessionHistories.computeIfAbsent(sessionId) { java.util.concurrent.CopyOnWriteArrayList() }
        list.add(Pair(user, assistant))
        if (list.size > 5) {
            list.removeAt(0)
        }
    }

    fun analyze(request: ScreenStateRequest): GuideActionResponse {
        val candidates = extractCandidates(request.uiTreeJson)
        
        // 1. LLM을 위한 데이터 단순화
        val simplifiedElements = candidates.map { 
            nodeParser.simplifyForLlm(it.candidateId, it.text, it.contentDesc, it.className, it.bounds)
        }
        val simplifiedJson = Json.encodeToString(simplifiedElements)
        val installedAppsJson = Json.encodeToString(request.installedApps ?: emptyList<AppInfo>())

        val sessionId = request.screenSessionId ?: "default_session"
        val historyText = getSessionHistoryText(sessionId)

        // 2. 프롬프트 생성 및 LLM 호출
        val combinedPrompt = """
            ${PromptTemplates.SCREEN_ANALYZE_SYSTEM_PROMPT}
            
            [대화 이력]
            $historyText
            
            ${PromptTemplates.buildScreenAnalyzePrompt(request.userVoiceCommand, simplifiedJson, installedAppsJson)}
        """.trimIndent()

        println("--- Claude 호출 시작 ---")
        val rawLlmRes = claudeClient.generate(combinedPrompt, request.screenshotBase64)
        
        return if (rawLlmRes != null) {
            try {
                // LLM이 덧붙인 불필요한 텍스트나 마크다운을 제거하고 순수 JSON 객체 부분만 추출
                val startIndex = rawLlmRes.indexOf('{')
                val endIndex = rawLlmRes.lastIndexOf('}')
                val cleanJson = if (startIndex != -1 && endIndex != -1 && startIndex <= endIndex) {
                    rawLlmRes.substring(startIndex, endIndex + 1)
                } else {
                    rawLlmRes
                }

                val llmRes = json.decodeFromString(LlmResponse.serializer(), cleanJson)
                println("🧠 LLM Thought (Sub-goal): ${llmRes.thought}")
                println("✅ LLM 분석 성공: ${llmRes.ttsMessage} (Target: ${llmRes.candidateId})")

                // 대화 이력 기록
                addMessageToHistory(sessionId, request.userVoiceCommand, llmRes.ttsMessage)

                // LLM이 선택한 candidateId에 해당하는 좌표 찾기
                val targetCandidate = candidates.find { it.candidateId == llmRes.candidateId }
                
                GuideActionResponse(
                    actionType = llmRes.actionType,
                    targetBounds = targetCandidate?.bounds,
                    ttsMessage = llmRes.ttsMessage,
                    targetCandidateId = llmRes.candidateId,
                    actionArguments = llmRes.arguments,
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

        val allParsed = elements.mapNotNull { element -> parseCandidate(element) }

        val initialCandidates = allParsed
            .filter { 
                val isListContainer = it.className.endsWith("RecyclerView") || 
                                     it.className.endsWith("ListView") || 
                                     it.className.endsWith("ScrollView")
                (it.clickable || it.text.isNotBlank() || it.contentDesc.isNotBlank() || isListContainer) 
                && it.enabled && it.visibleToUser 
            }

        val containers = initialCandidates.filter { 
            it.className.endsWith("RecyclerView") || 
            it.className.endsWith("ListView") || 
            it.className.endsWith("ScrollView")
        }

        // 컨테이너 노드들의 bounds를 내부에 속한 실제 자식 뷰들의 합산 영역으로 축소 보정
        val adjustedContainers = containers.map { container ->
            val childrenInContainer = allParsed.filter { child ->
                child.candidateId != container.candidateId &&
                child.visibleToUser &&
                child.bounds.left >= container.bounds.left - 10 &&
                child.bounds.right <= container.bounds.right + 10 &&
                child.bounds.top >= container.bounds.top - 10 &&
                child.bounds.bottom <= container.bounds.bottom + 10
            }
            if (childrenInContainer.isNotEmpty()) {
                val minLeft = childrenInContainer.minOf { it.bounds.left }
                val minTop = childrenInContainer.minOf { it.bounds.top }
                val maxRight = childrenInContainer.maxOf { it.bounds.right }
                val maxBottom = childrenInContainer.maxOf { it.bounds.bottom }
                container.copy(
                    bounds = RectDto(
                        left = Math.max(container.bounds.left, minLeft),
                        top = Math.max(container.bounds.top, minTop),
                        right = Math.min(container.bounds.right, maxRight),
                        bottom = Math.min(container.bounds.bottom, maxBottom)
                    )
                )
            } else {
                container
            }
        }

        // 컨테이너 내부에 쏙 포함되는 자식 카드 노드들을 후보 목록에서 제외
        val filteredCandidates = initialCandidates.filter { candidate ->
            val isContainer = candidate.className.endsWith("RecyclerView") || 
                              candidate.className.endsWith("ListView") || 
                              candidate.className.endsWith("ScrollView")
            
            if (isContainer) {
                true
            } else {
                val isInsideContainer = containers.any { container ->
                    candidate.candidateId != container.candidateId &&
                    candidate.bounds.left >= container.bounds.left - 10 &&
                    candidate.bounds.right <= container.bounds.right + 10 &&
                    candidate.bounds.top >= container.bounds.top - 10 &&
                    candidate.bounds.bottom <= container.bounds.bottom + 10
                }
                !isInsideContainer
            }
        }.map { candidate ->
            val matchedAdjusted = adjustedContainers.find { it.candidateId == candidate.candidateId }
            matchedAdjusted ?: candidate
        }

        return filteredCandidates.map { candidate ->
            val isListContainer = candidate.className.endsWith("RecyclerView") || 
                                 candidate.className.endsWith("ListView") || 
                                 candidate.className.endsWith("ScrollView")
            
            if ((candidate.clickable || isListContainer) && candidate.text.isBlank() && candidate.contentDesc.isBlank()) {
                val childTexts = allParsed
                    .filter { child ->
                        child.candidateId != candidate.candidateId &&
                        child.text.isNotBlank() &&
                        child.visibleToUser &&
                        child.bounds.left >= candidate.bounds.left - 10 &&
                        child.bounds.right <= candidate.bounds.right + 10 &&
                        child.bounds.top >= candidate.bounds.top - 10 &&
                        child.bounds.bottom <= candidate.bounds.bottom + 10
                    }
                    .sortedBy { it.bounds.top }
                    .map { it.text.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(5)
                    .joinToString(" | ")

                if (childTexts.isNotBlank()) candidate.copy(text = childTexts) else candidate
            } else {
                candidate
            }
        }
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
