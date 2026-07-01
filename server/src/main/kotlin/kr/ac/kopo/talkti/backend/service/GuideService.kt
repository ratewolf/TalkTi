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
    private val claudeClient: ClaudeClient = ClaudeClient(),
    private val experienceService: kr.ac.kopo.talkti.backend.experience.ExperienceService = kr.ac.kopo.talkti.backend.experience.ExperienceService()
) {

    @Serializable
    private data class LlmGuideResponse(
        val state: String,
        val targets: List<LlmGuideTarget> = emptyList(),
        val tts: String,
        val unchanged: Boolean = false,
        val actionType: String? = null,
        val actionArguments: String? = null,
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

        // 과거 성공 경험 조회
        val experiencePrompt = experienceService.getExperiencePrompt(request.userCommand)

        // 프롬프트 생성
        val prompt = buildGuidePrompt(
            userCommand = request.userCommand,
            uiTreeJson = simplifiedJson,
            packageName = request.packageName,
            previousState = request.previousState,
            experiencePrompt = experiencePrompt
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

                println("🔍 매핑된 targets 개수: ${targets.size}, candidateId들: ${targets.map { it.candidateId }}")

                // targets 매핑 직후, 옵션 모달(SELECT_OPTION)이고 targets가 3개 이상이면
                // 마지막 타겟(장바구니 담기)을 제외한 나머지를 하나의 bounding box로 합침
                val finalTargets = if (llmRes.state == "SELECT_OPTION" && targets.size >= 3) {
                    val actionTarget = targets.last()
                    val optionTargets = targets.dropLast(1)
                    val mergedBounds = RectDto(
                        left = optionTargets.minOf { it.bounds.left },
                        top = optionTargets.minOf { it.bounds.top },
                        right = optionTargets.maxOf { it.bounds.right },
                        bottom = optionTargets.maxOf { it.bounds.bottom }
                    )
                    val mergedOptionTarget = GuideTarget(
                        candidateId = optionTargets.first().candidateId,
                        text = "온도/사이즈 선택",
                        bounds = mergedBounds
                    )
                    println("🔍 병합된 optionBounds: $mergedBounds, 개별: ${optionTargets.map { it.bounds }}")
                    listOf(mergedOptionTarget, actionTarget)
                } else {
                    targets
                }

                GuideScreenResponse(
                    state = llmRes.state,
                    targets = finalTargets,
                    tts = llmRes.tts,
                    unchanged = llmRes.unchanged,
                    actionType = llmRes.actionType,
                    actionArguments = llmRes.actionArguments
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
        // 1. 입력창(EditText) 탐색 — 2단계 흐름으로 처리
        //    1단계: 사용자에게 검색창을 눌러달라는 오버레이 안내
        //    2단계: 사용자가 탭해 포커스된 후 → 텍스트 자동 주입
        val query = cleanSearchQuery(request.userCommand)
        val editTextCandidate = candidates.firstOrNull { c ->
            c.className.contains("EditText") || c.className.contains("AutoCompleteTextView")
        }

        if (editTextCandidate != null && query.isNotBlank()) {
            val currentText = editTextCandidate.text.trim()
            val cleanCurrent = currentText.replace(" ", "").lowercase()
            val cleanTarget = query.replace(" ", "").lowercase()

            // 이미 목적지가 입력되어 있으면 이 단계 건너뜀
            if (!cleanCurrent.contains(cleanTarget)) {

                // 이전 상태가 PRESS_ACTION_EDIT_TEXT이면 사용자가 이미 검색창을 탭한 것
                // → 2단계: 텍스트 자동 주입 (ACTION_SET_TEXT)
                if (request.previousState == "PRESS_ACTION_EDIT_TEXT") {
                    return GuideScreenResponse(
                        state = "PRESS_ACTION",
                        targets = listOf(
                            GuideTarget(
                                candidateId = editTextCandidate.candidateId,
                                text = editTextCandidate.text.ifBlank { editTextCandidate.contentDesc }.ifBlank { "목적지 입력창" },
                                bounds = editTextCandidate.bounds
                            )
                        ),
                        tts = "${query}을 입력할게요.",
                        actionType = "ACTION_SET_TEXT",
                        actionArguments = query
                    )
                }

                // 1단계: 처음 검색창 발견 → 오버레이로 눌러달라고 안내
                return GuideScreenResponse(
                    state = "PRESS_ACTION_EDIT_TEXT",
                    targets = listOf(
                        GuideTarget(
                            candidateId = editTextCandidate.candidateId,
                            text = "목적지 검색창",
                            bounds = editTextCandidate.bounds
                        )
                    ),
                    tts = "목적지 검색창을 눌러주세요."
                )
            }
        }


        // 2. 액션 버튼 탐색 ("도착", "호출", "전송" 등)
        val actionKeywords = setOf("도착", "길찾기", "안내 시작", "출발", "결제", "결제하기", "전송", "확인", "예약", "다음", "계속", "호출")
        val actionCandidate = candidates.firstOrNull { c ->
            val text = c.text.ifBlank { c.contentDesc }.trim()
            actionKeywords.any { text.contains(it) } && (c.clickable || c.className.contains("TextView") || c.className.contains("Button")) && c.enabled
        }

        // previousState가 PRESS_ACTION이면 텍스트 주입 직후이므로 검색 버튼 등을 건너뛰고
        // 바로 장소 목록(3단계)으로 진행
        val skipActionButtons = request.previousState == "PRESS_ACTION" || request.previousState == "PRESS_ACTION_EDIT_TEXT"
        if (actionCandidate != null && !skipActionButtons && request.previousState != "PRESS_ACTION") {
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

        // 3. 선택 가능 후보 탐색
        val excludedTexts = setOf("뒤로", "검색", "현재 위치", "내 위치", "공유", "메뉴", "설정", "닫기", "취소", "확인")
        val selectCandidates = candidates.filter { c ->
            val text = c.text.ifBlank { c.contentDesc }.trim()
            text.isNotBlank() && text !in excludedTexts && c.clickable && c.enabled
        }

        // previousState가 SELECT_TARGET이어도 장소 목록은 항상 다시 보여줌 (검색 후 목록 유지)
        if (selectCandidates.isNotEmpty()) {
            return GuideScreenResponse(
                state = "SELECT_TARGET",
                targets = selectCandidates.take(10).map { c ->
                    GuideTarget(
                        candidateId = c.candidateId,
                        text = c.text.ifBlank { c.contentDesc },
                        bounds = c.bounds
                    )
                },
                tts = if (request.previousState != "SELECT_TARGET") "장소를 선택해주세요." else ""
            )
        }

        // 4. 변경 없음
        return GuideScreenResponse(
            state = request.previousState,
            targets = emptyList(),
            tts = "",
            unchanged = true
        )
    }

    private fun cleanSearchQuery(command: String): String {
        var result = command
        val patterns = listOf(
            "지금\\s*내\\s*위치에서",
            "내\\s*위치에서",
            "현재\\s*위치에서",
            "으로\\s*가\\s*줘",
            "가는\\s*길\\s*찾아\\s*달라니까",
            "가는\\s*길\\s*찾아\\s*줘",
            "가는\\s*길\\s*알려\\s*줘",
            "가는\\s*경로\\s*알려\\s*줘",
            "가는\\s*경로",
            "어떻게\\s*가",
            "가고\\s*싶어",
            "찾아\\s*줘",
            "알려\\s*줘",
            "길찾기",
            "검색해\\s*줘",
            "가\\s*줘",
            "갈래",
            "가자",
            "으로",
            "택시\\s*불러\\s*줘",
            "택시\\s*호출\\s*해\\s*줘",
            "택시\\s*불러\\s*달라니까",
            "택시",
            "호출",
            // 교통수단
            "버스\\s*타고",
            "지하철\\s*타고",
            "대중교통\\s*타고",
            "택시\\s*타고",
            "버스로",
            "지하철로",
            "도보로",
            "자전거로"
        )
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            result = result.replace(regex, "")
        }
        return result
            .replace(".", "")
            .replace(",", "")
            .trim()
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
                val isEditText = it.className.contains("EditText") || 
                                 it.className.contains("AutoCompleteTextView")
                (it.clickable || it.text.isNotBlank() || it.contentDesc.isNotBlank() || isListContainer || isEditText) 
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
        previousState: String,
        experiencePrompt: String = ""
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
  * 이 버튼을 누르면 가이드가 완전히 종료되는 최종 버튼(안내시작, 호출, 전송, 결제완료 등)이면 actionType을 "FINAL"로 설정
  * 이 버튼을 누르면 다음 단계가 있는 중간 버튼(도착, 검색 등)이면 actionType을 null로 설정
- SELECT_OPTION: 사용자가 옵션 중 하나를 선택해야 함 (경로 추천 등)
- CONFIRM: 사용자가 최종 확인을 해야 함
- COMPLETE: 가이드 완료. 반드시 아래 경우에 COMPLETE를 반환:
  * 이전 상태가 PRESS_ACTION이었고, 현재 화면이 내비게이션/길안내/경로안내 화면으로 전환된 경우 (안내시작 버튼을 누른 결과)
  * 이전 상태가 PRESS_ACTION이었고, 택시/배달 호출이 완료된 화면인 경우
  * 이전 상태가 PRESS_ACTION이었고, 메시지/파일 전송이 완료된 경우
  * 사용자가 원하는 최종 목표가 완전히 달성된 경우
  COMPLETE 시 tts는 "안내를 완료했어요." 또는 상황에 맞는 완료 멘트로 설정.
- IDLE: 현재 화면에서 사용자가 해야 할 행동이 명확하지 않거나, 가이드와 무관한 화면인 경우에만 사용. PRESS_ACTION 다음에는 IDLE 대신 COMPLETE를 우선 고려할 것.

[targets 규칙]
- SELECT_TARGET / SELECT_OPTION: 선택 가능한 후보의 candidateId 를 최대 10개까지 배열로 반환
- PRESS_ACTION / CONFIRM: 눌러야 할 버튼 1개의 candidateId 만 반환
- IDLE / COMPLETE: 빈 배열

[tts 규칙]
- 어르신(60대 이상)이 이해할 수 있는 쉽고 짧은 문장
- "~해주세요" 존댓말 사용
- 20자 이내 권장

[FINAL 액션 규칙 - 반드시 준수]
- ⭐최우선 원칙: 화면에 FINAL 버튼(안내시작, 호출, 전송, 결제하기 등)이 이미 보이고 클릭 가능한 상태라면, previousState나 다른 단계별 규칙(옵션 재선택 등)을 무시하고 즉시 PRESS_ACTION으로 그 FINAL 버튼을 우선 안내하십시오. 이미 진행된 선택(옵션, 경로 등)을 재차 선택하라고 안내하는 것은 금지합니다.
PRESS_ACTION 응답 시 아래 기준으로 actionType을 반드시 설정하라:
- actionType = "FINAL": 이 버튼을 누르면 목표가 완전히 달성되어 가이드가 종료되는 최종 버튼
  해당 버튼 예시: "안내시작", "안내 시작", "호출", "전송", "보내기", "결제", "결제하기", "예약완료"
  판단 기준: 이 버튼을 누른 후 사용자가 더 이상 선택하거나 누를 것이 없는 경우
- actionType = null: 이 버튼을 누르면 다음 단계가 있는 중간 버튼
  해당 버튼 예시: "도착", "출발", "검색", "다음", "확인(중간 단계)"
  판단 기준: 이 버튼을 누른 후 새로운 화면이 열리고 추가 선택이 필요한 경우

예시:
- 길찾기에서 "도착" 버튼 → actionType = null (누르면 경로 선택 화면이 열림)
- 길찾기에서 "안내시작" 버튼 → actionType = "FINAL" (누르면 내비 시작, 더 할 일 없음)
- 택시에서 "호출" 버튼 → actionType = "FINAL" (누르면 택시 호출 완료)
- 카카오톡에서 "전송" 버튼 → actionType = "FINAL" (누르면 메시지 전송 완료)

[자동 텍스트 입력 규칙]
- 현재 활성화된 화면에서 텍스트를 입력해야 하는 입력창(EditText 등)을 가이드하는 단계라면:
  1. `state`는 `PRESS_ACTION`으로 설정합니다.
  2. `actionType`을 `"ACTION_SET_TEXT"`로 설정합니다.
  3. `actionArguments`에는 사용자 요청(userCommand)을 바탕으로, 불필요한 조사나 어어(~검색해줘, ~찾아줘, ~어떻게가, ~불러줘 등)를 완전히 제거하고 정제한 핵심 검색어 또는 목적지 키워드(예: "가위", "서울역")만 정밀하게 추출하여 설정하십시오. (예: "쿠팡에서 가위 검색해줘" ➔ "가위", "서울역 어떻게 가?" ➔ "서울역")
- 텍스트 입력 단계가 아니라면 `actionType`과 `actionArguments`는 모두 `null`로 응답하십시오.

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
  * 현재 화면에 목적지 검색 입력창(EditText) 또는 '가고 싶은 곳을 찾아보세요' 버튼이 보이고 previousState가 "PRESS_ACTION_EDIT_TEXT"가 아니라면:
    상태를 PRESS_ACTION_EDIT_TEXT로 설정하고 해당 요소를 target으로 지정하며 TTS로 '목적지 검색창을 눌러주세요.'라고 안내하세요. (actionType/actionArguments 설정 안 함)
  * previousState가 "PRESS_ACTION_EDIT_TEXT"이면 사용자가 검색창을 탭한 직후이므로 즉시 텍스트 자동 입력:
    상태를 PRESS_ACTION으로 설정하고 actionType="ACTION_SET_TEXT", actionArguments에 목적지 키워드(예: "서울역")를 설정하세요. target은 화면에서 EditText 또는 검색 입력창으로 지정하세요.
  * previousState가 "PRESS_ACTION"이고 현재 화면에 검색어가 이미 입력된 EditText와 장소 목록이 보이면: 절대 검색 버튼을 누르라고 안내하지 말고 즉시 SELECT_TARGET으로 장소 목록의 candidateId들을 선택하십시오. 카카오T는 실시간 검색이므로 검색 버튼이 보여도 누를 필요가 없습니다. 이 규칙은 반드시 지켜야 합니다.
  * previousState가 "PRESS_ACTION"이고 장소 목록이 안 보이면: unchanged=true로 응답하십시오.
- 채팅 앱 (카카오톡 등):
  * 채팅방 목록은 SELECT_TARGET 이고 채팅방 목록들을 선택하세요.
  * '전송' 또는 '보내기' 관련 버튼은 PRESS_ACTION 이고 해당 버튼을 선택하세요.
  * 현재 대화방에서 메시지 입력을 기다리는 상태라면, 상태는 PRESS_ACTION이고 actionType="ACTION_SET_TEXT"와 actionArguments에 전송할 메시지를 설정하세요.
- 키오스크 앱 (무인 주문기 등):
  * 메뉴 목록(커피/에이드/티/디저트 탭 안의 항목들)이 보이면 SELECT_TARGET으로 메뉴 항목을 선택하게 하세요.
  * 메뉴 선택 시 뜨는 옵션 모달(온도, 사이즈, 수량과 함께 "장바구니 담기" 버튼이 한 화면에 보일 때): 상태를 SELECT_OPTION으로 응답하세요. targets에는 온도 선택 버튼들(HOT, ICE 등)과 사이즈 선택 버튼들(Regular, Large 등)의 candidateId를 모두 개별적으로 포함하고, 마지막에 장바구니 담기 버튼의 candidateId를 추가하세요. (예: HOT, ICE, Regular, Large, 장바구니담기 = 5개) tts는 "온도와 사이즈를 선택하시고, 다 선택하셨으면 장바구니 담기 버튼을 눌러주세요." 형태로 작성하세요.
  * 장바구니 화면에서 "주문하기" 버튼은 PRESS_ACTION으로 안내하세요.
  * 결제 수단 선택(신용카드/간편결제) 화면은 SELECT_OPTION으로 처리하세요.
  * "영수증을 출력하시겠습니까?" 같은 확인 화면은 SELECT_OPTION으로 처리하세요.
  * 모든 단계에서 일반 버튼 클릭으로 안내하며, FINAL 처리는 하지 않습니다.

[의사결정 및 추론 단계 (thought 필드 필수 작성)]
의사결정 시 아래 3단계 논리 프로세스를 따라 추론하고, 그 과정을 응답 JSON의 "thought" 필드에 요약하여 작성하세요:
1. 전체 목표(Overall Task): 사용자가 최종적으로 달성하려는 앱 내 최종 행동 목표가 무엇인가?
2. 이전 행동 및 UI 상태 분석(Context Analysis): 직전까지 어떤 행동을 취했으며, 현재 화면의 UI 배치와 활성화된 요소들의 시각적/기능적 역할은 무엇인가?
3. 현재 하위 목표(Current Sub-goal): 전체 목표를 달성하기 위해 '지금 이 화면에서만' 안전하게 수행해야 할 유일한 다음 단계의 하위 목표는 무엇인가?

[응답 JSON 스키마]
{
  "thought": "3단계 추론 프로세스의 요약 내용 (예: '최종목표는 대중교통 남영역 길찾기이며, 이전 단계에서 남영역 검색을 완료함. 현재 화면은 검색된 장소 목록들이 나열된 상태이므로, 다음 하위 목표는 구체적인 지점 하나를 골라 장소를 확정하는 것임. 따라서 다른 교통수단 필터 탭은 무시하고 장소 목록 영역만 가이드함.')",
  "state": "SELECT_TARGET | PRESS_ACTION | PRESS_ACTION_EDIT_TEXT | SELECT_OPTION | CONFIRM | COMPLETE | IDLE",
  "targets": [{"candidateId": "candidate_0", "text": "표시할 텍스트"}],
  "tts": "음성 안내 메시지",
  "unchanged": false,
  "actionType": "ACTION_SET_TEXT" or null,
  "actionArguments": "입력할 핵심 키워드" or null
}

${if (experiencePrompt.isNotBlank()) experiencePrompt else ""}
[입력 정보]
사용자 요청: "$userCommand"
앱 패키지명: $packageName
이전 상태: $previousState

현재 화면 UI 요소:
$uiTreeJson
        """.trimIndent()
    }
}
