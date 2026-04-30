package kr.ac.kopo.talkti

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.ac.kopo.talkti.models.GuideActionResponse
import kr.ac.kopo.talkti.models.RectDto
import kr.ac.kopo.talkti.models.ScreenStateRequest

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

    fun extractCandidates(uiTreeJson: String): List<UiCandidate> {
        val elements = runCatching { json.parseToJsonElement(uiTreeJson).jsonArray }
            .getOrElse { JsonArray(emptyList()) }

        return elements.mapNotNull { element -> parseCandidate(element) }
            .filter { candidate ->
                candidate.clickable && candidate.enabled && candidate.visibleToUser
            }
    }

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

    private fun parseCandidate(element: JsonElement): UiCandidate? {
        val obj = element as? JsonObject ?: return null
        val candidateId = obj.stringOrNull("candidateId") ?: return null
        val boundsObj = obj["bounds"]?.jsonObject ?: return null

        val bounds = RectDto(
            left = boundsObj.intOrNull("left") ?: return null,
            top = boundsObj.intOrNull("top") ?: return null,
            right = boundsObj.intOrNull("right") ?: return null,
            bottom = boundsObj.intOrNull("bottom") ?: return null
        )

        return UiCandidate(
            candidateId = candidateId,
            bounds = bounds,
            clickable = obj.booleanOrDefault("clickable", false),
            enabled = obj.booleanOrDefault("enabled", false),
            visibleToUser = obj.booleanOrDefault("visibleToUser", false)
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        return this[key]?.jsonPrimitive?.intOrNull
    }

    private fun JsonObject.booleanOrDefault(key: String, defaultValue: Boolean): Boolean {
        return this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: defaultValue
    }
}
