package kr.ac.kopo.talkti.backend.service

import kotlinx.serialization.json.*
import kr.ac.kopo.talkti.models.GuideActionResponse
import kr.ac.kopo.talkti.models.RectDto
import kr.ac.kopo.talkti.models.ScreenStateRequest

/**
 * 백엔드 파트: 분석 오케스트레이션 담당
 */
class AnalyzeService(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    data class UiCandidate(
        val candidateId: String,
        val bounds: RectDto,
        val clickable: Boolean,
        val enabled: Boolean,
        val visibleToUser: Boolean
    )

    fun analyze(request: ScreenStateRequest): GuideActionResponse {
        val candidates = extractCandidates(request.uiTreeJson)
        val firstCandidate = candidates.firstOrNull()

        return if (firstCandidate != null) {
            GuideActionResponse(
                actionType = "CLICK",
                targetBounds = firstCandidate.bounds,
                ttsMessage = "${request.userVoiceCommand}을(를) 위해 표시된 항목을 눌러주세요.",
                targetCandidateId = firstCandidate.candidateId,
                confidence = 0.75,
                screenSessionId = request.screenSessionId
            )
        } else {
            GuideActionResponse(
                actionType = "ASK_USER",
                targetBounds = null,
                ttsMessage = "화면에서 누를 수 있는 항목을 찾지 못했어요. 다시 말씀해 주세요.",
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
            visibleToUser = obj["visibleToUser"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        )
    }
}
