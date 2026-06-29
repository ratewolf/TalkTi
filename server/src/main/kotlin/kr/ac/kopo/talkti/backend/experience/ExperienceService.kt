package kr.ac.kopo.talkti.backend.experience

import kotlinx.serialization.Serializable

/**
 * 경험 저장/조회 API 비즈니스 로직
 */
class ExperienceService {

    /**
     * 세션 시작 — 새 세션 ID 발급
     */
    fun startSession(userCommand: String): StartSessionResponse {
        val scenarioType = ExperienceRepository.classifyScenario(userCommand)
        val sessionId = ExperienceRepository.createSession(scenarioType, userCommand)
        println("[Experience] 세션 시작: id=$sessionId, type=$scenarioType, cmd='$userCommand'")
        return StartSessionResponse(sessionId = sessionId, scenarioType = scenarioType)
    }

    /**
     * 상태 전이 기록
     */
    fun recordTransition(request: RecordTransitionRequest) {
        ExperienceRepository.saveTransition(
            sessionId = request.sessionId,
            step = request.step,
            fromState = request.fromState,
            toState = request.toState,
            actionTaken = request.actionTaken
        )
        println("[Experience] 전이 기록: session=${request.sessionId}, ${request.fromState}→${request.toState}")
    }

    /**
     * 세션 완료 — 성공/실패 기록
     */
    fun completeSession(request: CompleteSessionRequest) {
        ExperienceRepository.updateSessionResult(
            sessionId = request.sessionId,
            success = request.success,
            totalSteps = request.totalSteps
        )
        println("[Experience] 세션 완료: id=${request.sessionId}, success=${request.success}, steps=${request.totalSteps}")
    }

    /**
     * 성공 경험 조회 — 프롬프트 삽입용 텍스트 생성
     */
    fun getExperiencePrompt(userCommand: String): String {
        val scenarioType = ExperienceRepository.classifyScenario(userCommand)
        if (scenarioType == "기타") return ""

        val experiences = ExperienceRepository.getSuccessExperiences(scenarioType, limit = 2)
        if (experiences.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("[과거 성공 경험 - 참고용]")
        sb.appendLine("이 사용자의 요청은 '$scenarioType' 시나리오로 분류됩니다.")
        sb.appendLine("아래는 동일 시나리오에서 성공한 경험입니다. 같은 흐름으로 안내하세요.")
        sb.appendLine()

        experiences.forEachIndexed { idx, exp ->
            sb.appendLine("경험 ${idx + 1}: '${exp.userCommand}' (${exp.totalSteps}단계 성공)")
            exp.transitions.forEach { t ->
                sb.appendLine("  ${t.step}. ${t.fromState} → ${t.toState}: ${t.actionTaken}")
            }
            sb.appendLine()
        }

        return sb.toString()
    }
}

// ── API 요청/응답 모델 ──────────────────────────────────────────

@Serializable
data class StartSessionResponse(
    val sessionId: Long,
    val scenarioType: String
)

@Serializable
data class RecordTransitionRequest(
    val sessionId: Long,
    val step: Int,
    val fromState: String,
    val toState: String,
    val actionTaken: String? = null
)

@Serializable
data class CompleteSessionRequest(
    val sessionId: Long,
    val success: Boolean,
    val totalSteps: Int
)
