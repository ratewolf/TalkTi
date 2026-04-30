package kr.ac.kopo.talkti.backend.validator

import kr.ac.kopo.talkti.models.ScreenStateRequest

/**
 * 백엔드 파트: 들어오는 요청 데이터의 유효성 검증
 */
class RequestValidator {
    fun validate(request: ScreenStateRequest): Boolean {
        if (request.userVoiceCommand.isBlank()) return false
        if (request.uiTreeJson.isBlank()) return false
        return true
    }
}
