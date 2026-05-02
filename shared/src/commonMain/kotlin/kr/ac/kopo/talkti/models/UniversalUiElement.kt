package kr.ac.kopo.talkti.models

import kotlinx.serialization.Serializable

@Serializable
data class UniversalUiElement(
    val text: String,
    val role: String,
    val bounds: RectDto,
    val clickable: Boolean,
    val contentDescription: String = "",
    val id: String = ""
)
