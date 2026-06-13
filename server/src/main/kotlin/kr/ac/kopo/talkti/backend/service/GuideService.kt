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
        val unchanged: Boolean = false
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

        return elements.mapNotNull { element -> parseCandidate(element) }
            .filter { (it.clickable || it.text.isNotBlank() || it.contentDesc.isNotBlank()) && it.enabled && it.visibleToUser }
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

[앱별 행동 가이드 규칙 (필수 준수)]
- 지도 앱 (카카오맵, 네이버지도 등):
  * 장소 검색 결과 목록이 나타나면 상태는 SELECT_TARGET 이고 검색된 장소 목록들의 candidateId들을 선택하세요.
  * 연관 검색어(추천 검색어)보다 실제 장소를 우선 선택하세요.
  * 최근 검색어(히스토리)는 절대 선택하지 마세요.
  * '도착' 관련 버튼이 보이면 상태는 PRESS_ACTION 이고 해당 버튼을 선택하세요.
  * '안내 시작' 관련 버튼이 보이면 상태는 PRESS_ACTION 이고 해당 버튼을 선택하세요.
  * 여러 경로 목록이 보이면 상태는 SELECT_OPTION 이고 경로 목록들을 선택하세요.
- 택시 앱 (카카오T 등):
  * 장소 선택 후 택시 종류 선택은 SELECT_OPTION 이고 택시 종류 목록들을 선택하세요.
  * '호출' 관련 버튼은 PRESS_ACTION 이고 해당 버튼을 선택하세요.
- 채팅 앱 (카카오톡 등):
  * 채팅방 목록은 SELECT_TARGET 이고 채팅방 목록들을 선택하세요.
  * '전송' 또는 '보내기' 관련 버튼은 PRESS_ACTION 이고 해당 버튼을 선택하세요.

[응답 JSON 스키마]
{
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
