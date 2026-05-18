package kr.ac.kopo.talkti.models

import kotlinx.serialization.Serializable

/**
 * 사용자가 선택할 수 있는 조건/옵션을 표현하는 모델.
 * 예) "최단 시간", "최소 환승", "일반택시" 등
 */
@Serializable
data class Option(
    val id: String,
    val text: String
)
