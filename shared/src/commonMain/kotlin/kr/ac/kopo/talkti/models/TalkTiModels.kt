package kr.ac.kopo.talkti.models

import kotlinx.serialization.Serializable

@Serializable
data class UiElement(
    val candidateId: String,
    val text: String,
    val contentDescription: String,
    val id: String,
    val className: String,
    val bounds: RectDto,
    val clickable: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean
)

@Serializable
data class ScreenStateRequest(
    val userVoiceCommand: String,
    val uiTreeJson: String,
    val screenshotBase64: String? = null,
    val screenSessionId: String? = null
)

@Serializable
data class GuideActionResponse(
    val actionType: String,
    val targetBounds: RectDto?,
    val ttsMessage: String,
    val targetCandidateId: String? = null,
    val confidence: Double? = null,
    val screenSessionId: String? = null
)

@Serializable
data class RectDto(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
