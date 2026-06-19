package kr.ac.kopo.talkti.backend.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kr.ac.kopo.talkti.backend.service.llm.ClaudeClient
import kr.ac.kopo.talkti.data.parser.UiNodeParser
import kr.ac.kopo.talkti.models.*

/**
 * /guide 엔드포인트 전용 서비스.
 *
 * UI Tree 만 분석하여 사용자가 다음에 해야 할 행동을 판단한다.
 * 스크린샷을 사용하지 않는다.
 */
class GuideService(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nodeParser: UiNodeParser = UiNodeParser(),
    private val claudeClient: ClaudeClient = ClaudeClient()
) {

    @Serializable
    private data class LlmGuideResponse(
        val state: String,
        val targets: List<LlmGuideTarget> = emptyList(),
        val tts: String,
        val unchanged: Boolean = false,
        val thought: String = ""
    )

    @Serializable
    private data class LlmGuideTarget(
        val candidateId: String,
        val text: String = ""
    )

    /**
     * UI Tree 를 분석하여 GuideScreenResponse 를 반환한다.
     */
    fun analyze(request: GuideScreenRequest): GuideScreenResponse {
        val candidates = extractCandidates(request.uiTreeJson)

        // LLM 용 간소화
        val simplifiedElements = candidates.map {
            nodeParser.simplifyForLlm(it.candidateId, it.text, it.contentDesc, it.className, it.bounds)
        }
        val simplifiedJson = Json.encodeToString(simplifiedElements)

        // 프롬프트 생성
        val prompt = buildGuidePrompt(
            userCommand = request.userCommand,
            uiTreeJson = simplifiedJson,
            packageName = request.packageName,
            previousState = request.previousState
        )

        println("--- Guide LLM 호출 시작 ---")
        val rawLlmRes = claudeClient.generate(prompt, null) // 스크린샷 없음

        return if (rawLlmRes != null) {
            try {
                val cleanJson = extractJsonBlock(rawLlmRes)
                val llmRes = json.decodeFromString(LlmGuideResponse.serializer(), cleanJson)
                println("🧠 Guide LLM Thought (Sub-goal): ${llmRes.thought}")
                println("✅ Guide LLM 분석 성공: state=${llmRes.state}, targets=${llmRes.targets.size}")

                // LLM 이 선택한 candidateId 에 해당하는 좌표 매핑
                val targets = llmRes.targets.mapNotNull { target ->
                    val matched = candidates.find { it.candidateId == target.candidateId }
                    if (matched != null) {
                        GuideTarget(
                            candidateId = matched.candidateId,
                            text = target.text.ifBlank { matched.text.ifBlank { matched.contentDesc } },
                            bounds = matched.bounds
                        )
                    } else {
                        null
                    }
                }

                GuideScreenResponse(
                    state = llmRes.state,
                    targets = targets,
                    tts = llmRes.tts,
                    unchanged = llmRes.unchanged
                )
            } catch (e: Exception) {
                println("❌ Guide LLM 응답 파싱 실패: ${e.message}")
                fallbackAnalyze(candidates, request)
            }
        } else {
            println("⚠️ Guide LLM 호출 실패 - Fallback 실행")
            fallbackAnalyze(candidates, request)
        }
    }

    /**
     * LLM 실패 시 Rule 기반 Fallback.
     */
    private fun fallbackAnalyze(
        candidates: List<UiCandidate>,
        request: GuideScreenRequest
    ): GuideScreenResponse {
        // 1. 액션 버튼 탐색 ("도착", "호출", "전송" 등)
        val actionKeywords = setOf("도착", "길찾기", "안내 시작", "출발", "결제", "결제하기", "전송", "확인", "예약", "다음", "계속", "호출")
        val actionCandidate = candidates.firstOrNull { c ->
            val text = c.text.ifBlank { c.contentDesc }.trim()
            text in actionKeywords && c.clickable && c.enabled
        }

        if (actionCandidate != null && request.previousState != "PRESS_ACTION") {
            return GuideScreenResponse(
                state = "PRESS_ACTION",
                targets = listOf(
                    GuideTarget(
                        candidateId = actionCandidate.candidateId,
                        text = actionCandidate.text.ifBlank { actionCandidate.contentDesc },
                        bounds = actionCandidate.bounds
                    )
                ),
                tts = "${actionCandidate.text.ifBlank { actionCandidate.contentDesc }} 버튼을 눌러주세요."
            )
        }

        // 2. 선택 가능 후보 탐색
        val excludedTexts = setOf("뒤로", "검색", "현재 위치", "내 위치", "공유", "메뉴", "설정", "닫기", "취소", "확인")
        val selectCandidates = candidates.filter { c ->
            val text = c.text.ifBlank { c.contentDesc }.trim()
            text.isNotBlank() && text !in excludedTexts && c.clickable && c.enabled
        }

        if (selectCandidates.isNotEmpty() && request.previousState != "SELECT_TARGET") {
            return GuideScreenResponse(
                state = "SELECT_TARGET",
                targets = selectCandidates.take(10).map { c ->
                    GuideTarget(
                        candidateId = c.candidateId,
                        text = c.text.ifBlank { c.contentDesc },
                        bounds = c.bounds
                    )
                },
                tts = "선택해주세요."
            )
        }

        // 3. 변경 없음
        return GuideScreenResponse(
            state = request.previousState,
            targets = emptyList(),
            tts = "",
            unchanged = true
        )
    }

    // ================================================================
    //  내부 유틸리티
    // ================================================================

    private data class UiCandidate(
        val candidateId: String,
        val bounds: RectDto,
        val clickable: Boolean,
        val enabled: Boolean,
        val visibleToUser: Boolean,
        val text: String,
        val contentDesc: String,
        val className: String
    )

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

    private fun extractJsonBlock(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.contains("```json")) {
            cleaned = cleaned.substringAfter("```json").substringBeforeLast("```")
        } else if (cleaned.contains("```")) {
            cleaned = cleaned.substringAfter("```").substringBeforeLast("```")
        }
        val first = cleaned.indexOf('{')
        val last = cleaned.lastIndexOf('}')
        return if (first != -1 && last != -1 && first <= last) {
            cleaned.substring(first, last + 1)
        } else {
            cleaned
        }
    }

    // ================================================================
    //  Guide 전용 프롬프트
    // ================================================================

    private fun buildGuidePrompt(
        userCommand: String,
        uiTreeJson: String,
        packageName: String,
        previousState: String
    ): String {
        return """
당신은 어르신의 스마트폰 조작을 돕는 AI 에이전트 '똑띠'입니다.

현재 화면의 UI 요소 리스트(JSON)를 보고, 사용자가 **지금 해야 할 행동**을 판단하세요.

[절대 규칙]
1. 응답은 반드시 순수 JSON 객체 하나만 출력하세요. 마크다운(```) 금지, 부연 설명 금지.
2. 앱 이름, 화면 이름은 응답에 포함하지 마세요. 행동만 판단하세요.
3. 이전 상태(previousState)와 동일한 행동이면 unchanged=true 로 응답하세요.

[상태 종류]
- SELECT_TARGET: 사용자가 여러 후보 중 하나를 선택해야 함 (장소 목록, 채팅방 목록, 택시 종류 등)
- PRESS_ACTION: 사용자가 특정 버튼을 눌러야 함 (도착, 호출, 전송, 안내 시작, 결제 등)
- SELECT_OPTION: 사용자가 옵션 중 하나를 선택해야 함 (경로 추천 등)
- CONFIRM: 사용자가 최종 확인을 해야 함
- COMPLETE: 가이드 완료
- IDLE: 사용자가 해야 할 행동 없음

[targets 규칙]
- SELECT_TARGET / SELECT_OPTION: 선택 가능한 후보의 candidateId 를 최대 10개까지 배열로 반환
- PRESS_ACTION / CONFIRM: 눌러야 할 버튼 1개의 candidateId 만 반환
- IDLE / COMPLETE: 빈 배열

[tts 규칙]
- 어르신(60대 이상)이 이해할 수 있는 쉽고 짧은 문장
- "~해주세요" 존댓말 사용
- 20자 이내 권장

[unchanged 규칙]
- 이전 상태와 동일한 행동이고, 화면 구성도 비슷하면 unchanged=true
- 광고 로딩, 미세한 레이아웃 변경 등은 unchanged=true

[범용 목록/리스트 선택 화면에서의 오판 방지 규칙 (필수 준수)]
- 화면 전체가 여러 선택지를 제공하는 목록(경로 목록, 장소 검색 결과 목록, 상품 목록, 메뉴 목록 등) 형태라면, 첫 번째 항목이 기본적으로 펼쳐져서(Expanded) 그 아래에 액션 버튼(예: '안내시작', '구매하기', '선택')이 크게 노출되어 보이고 다른 항목들은 접히거나 하단 광고 등으로 일부 잘려 보일지라도, 사용자가 이미 하나의 항목을 결정한 최종 확인 화면으로 절대 오판하지 마십시오.
- 화면에 여러 항목이 나열되어 있다면 첫 번째 항목이 펼쳐져 있더라도 이는 여전히 선택 단계(SELECT_TARGET 또는 SELECT_OPTION)입니다. 따라서 성급하게 특정 항목의 버튼 하나만 PRESS_ACTION으로 유도하지 말고, 펼쳐진 항목을 포함해 화면에 노출된 다른 선택 가능한 항목들의 candidateId들을 모두 타겟으로 지정하여 사용자가 직접 고를 수 있도록 가이드하십시오.
- 하단 광고 배너나 화면 뷰포트 영역의 경계에 의해 일부 선택 항목들이 반쯤 잘려서 표시되더라도, 선택지의 일부분이 화면에 노출되어 있다면 해당 항목의 candidateId도 반드시 타겟에 함께 포함하십시오.

[앱별 행동 가이드 규칙 (필수 준수)]
- 지도 앱 (카카오맵, 네이버지도 등):
  * 장소 검색 결과 목록이 나타나면 상태는 SELECT_TARGET 이고 검색된 장소 목록들의 candidateId들을 선택하세요.
  * 연관 검색어(추천 검색어)보다 실제 장소를 우선 선택하세요.
  * 최근 검색어(히스토리)는 절대 선택하지 마세요.
  * 여러 추천 경로가 나열되는 [경로 목록 화면]에서는, 첫 번째 경로에 '안내 시작' 또는 '안내' 버튼이 펼쳐져서 보일지라도 즉시 PRESS_ACTION 상태로 해당 버튼 1개만 유도해서는 절대 안 됩니다. 반드시 상태를 SELECT_OPTION으로 지정하고 경로 목록 항목들의 candidateId들을 모두 선택하여 어르신이 경로를 직접 고르도록 유도하세요.
  * 사용자가 한 경로를 최종적으로 클릭/선택하여, 해당 경로에 대한 단독 '안내 시작' 혹은 '안내' 버튼만 크게 화면에 노출되는 최종 화면에 진입했을 때에만 비로소 상태를 PRESS_ACTION으로 지정하고 해당 버튼을 선택하세요.
  * '도착' 관련 버튼이 보이면 상태는 PRESS_ACTION 이고 해당 버튼을 선택하세요.
- 택시 앱 (카카오T 등):
  * 장소 선택 후 택시 종류 선택은 SELECT_OPTION 이고 택시 종류 목록들을 선택하세요.
  * '호출' 관련 버튼은 PRESS_ACTION 이고 해당 버튼을 선택하세요.
- 채팅 앱 (카카오톡 등):
  * 채팅방 목록은 SELECT_TARGET 이고 채팅방 목록들을 선택하세요.
  * '전송' 또는 '보내기' 관련 버튼은 PRESS_ACTION 이고 해당 버튼을 선택하세요.

[의사결정 및 추론 단계 (thought 필드 필수 작성)]
의사결정 시 아래 3단계 논리 프로세스를 따라 추론하고, 그 과정을 응답 JSON의 "thought" 필드에 요약하여 작성하세요:
1. 전체 목표(Overall Task): 사용자가 최종적으로 달성하려는 앱 내 최종 행동 목표가 무엇인가?
2. 이전 행동 및 UI 상태 분석(Context Analysis): 직전까지 어떤 행동을 취했으며, 현재 화면의 UI 배치와 활성화된 요소들의 시각적/기능적 역할은 무엇인가?
3. 현재 하위 목표(Current Sub-goal): 전체 목표를 달성하기 위해 '지금 이 화면에서만' 안전하게 수행해야 할 유일한 다음 단계의 하위 목표는 무엇인가?

[응답 JSON 스키마]
{
  "thought": "3단계 추론 프로세스의 요약 내용 (예: '최종목표는 대중교통 남영역 길찾기이며, 이전 단계에서 남영역 검색을 완료함. 현재 화면은 검색된 장소 목록들이 나열된 상태이므로, 다음 하위 목표는 구체적인 지점 하나를 골라 장소를 확정하는 것임. 따라서 다른 교통수단 필터 탭은 무시하고 장소 목록 영역만 가이드함.')",
  "state": "SELECT_TARGET | PRESS_ACTION | SELECT_OPTION | CONFIRM | COMPLETE | IDLE",
  "targets": [{"candidateId": "candidate_0", "text": "표시할 텍스트"}],
  "tts": "음성 안내 메시지",
  "unchanged": false
}

[입력 정보]
사용자 요청: "$userCommand"
앱 패키지명: $packageName
이전 상태: $previousState

현재 화면 UI 요소:
$uiTreeJson
        """.trimIndent()
    }
}
