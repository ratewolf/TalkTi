package kr.ac.kopo.talkti.app.guide

import android.util.Log

class AgentSessionManager {
    companion object {
        private const val TAG = "AgentSessionManager"
    }

    var isActive: Boolean = false
        private set

    var currentGoal: String? = null
        private set

    var sessionId: String? = null
        private set

    private var lastCapturedTime: Long = 0L

    fun startSession(goal: String) {
        isActive = true
        currentGoal = goal
        sessionId = "session_${System.currentTimeMillis()}"
        lastCapturedTime = 0L // 첫 화면 전환 감지 시 딜레이 없이 즉시 캡처되도록 0으로 설정
        Log.d(TAG, "세션 시작 - 목표: $goal, sessionId: $sessionId")
    }

    fun endSession() {
        if (isActive) {
            Log.d(TAG, "세션 종료 - 기존 목표: $currentGoal, sessionId: $sessionId")
        }
        isActive = false
        currentGoal = null
        sessionId = null
    }

    /**
     * 무한 캡처 및 전송 방지를 위한 쿨타임(Debounce) 체크
     * @param delayMs 이전 캡처 시점으로부터 지나야 하는 최소 밀리초 (기본 3000ms = 3초)
     * @return 캡처 가능 여부
     */
    fun canCapture(delayMs: Long = 3000): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCapturedTime > delayMs) {
            lastCapturedTime = now
            return true
        }
        return false
    }
}
