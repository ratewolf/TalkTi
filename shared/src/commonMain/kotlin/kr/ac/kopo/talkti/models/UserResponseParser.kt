package kr.ac.kopo.talkti.models

class UserResponseParser {
    fun parse(sttResult: String): UserResponse {
        val normalized = sttResult.trim().lowercase()

        if (normalized.contains("취소")) {
            return UserResponse.CANCEL
        }

        if (normalized.contains("아니") || normalized.contains("아냐")) {
            return UserResponse.NO
        }

        if (
            normalized.contains("응") || 
            normalized.contains("네") || 
            normalized.contains("맞아") || 
            normalized.contains("그래")) {
            return UserResponse.YES
        }

        return UserResponse.UNKNOWN
    }
}
