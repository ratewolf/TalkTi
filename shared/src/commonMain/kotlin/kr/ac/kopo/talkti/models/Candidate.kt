package kr.ac.kopo.talkti.models

import kotlinx.serialization.Serializable

@Serializable
data class Candidate(
    val id: String,
    val text: String,
    val bounds: RectDto
)
