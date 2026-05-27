package kr.ac.kopo.talkti.app.errorhandling

import android.util.Log
import kotlinx.coroutines.*

/**
 * 예외 처리 #3: 무한 대기 방지 타이머 (Timeout & Reset)
 *
 * 가이드 안내 후 어르신이 아무 동작도 하지 않을 때 단계적으로 개입합니다:
 *   - 20초 경과: 오버레이 깜빡임 효과 (시각적 힌트)
 *   - 30초 경과: TTS 음성 안내 ("도움이 필요하신가요?")
 *   - 45초 경과: 가이드 종료 음성 안내 + 상태 초기화
 */
class GuideTimeoutManager {

    companion object {
        private const val TAG = "GuideTimeoutManager"

        /** 1단계: 오버레이 깜빡임까지의 대기 시간 (밀리초) */
        const val BLINK_TIMEOUT_MS = 20_000L

        /** 2단계: 음성 안내까지의 대기 시간 (밀리초) */
        const val VOICE_TIMEOUT_MS = 30_000L

        /** 3단계: 가이드 종료까지의 대기 시간 (밀리초) */
        const val TERMINATE_TIMEOUT_MS = 45_000L

        /** 기능 활성화 플래그 (현재 LLM 속도로 인한 이슈로 임시 비활성화) */
        const val IS_ENABLED = false
    }

    /**
     * 타이머 만료 시 호출될 콜백 인터페이스
     */
    interface TimeoutCallback {
        /** 20초 경과: 오버레이 깜빡임 시작 요청 */
        fun onBlinkTimeout()

        /** 30초 경과: 음성 안내 요청 */
        fun onVoiceTimeout()

        /** 45초 경과: 가이드 종료 요청 (상태 초기화 포함) */
        fun onTerminateTimeout()
    }

    private var callback: TimeoutCallback? = null
    private var timerJob: Job? = null
    private var timerScope: CoroutineScope? = null

    /** 타이머가 현재 작동 중인지 여부 */
    var isRunning: Boolean = false
        private set

    /** 현재 타이머 단계 (0=미시작, 1=깜빡임 대기, 2=음성 대기, 3=종료 대기) */
    var currentPhase: Int = 0
        private set

    /**
     * 콜백을 등록합니다. ErrorHandlingManager에서 초기화 시 호출합니다.
     */
    fun setCallback(callback: TimeoutCallback) {
        this.callback = callback
    }

    /**
     * 타이머를 시작합니다.
     * 가이드 안내(오버레이 표시, TTS 완료) 직후에 호출됩니다.
     * 이미 타이머가 돌고 있다면 리셋(재시작)합니다.
     */
    fun startTimer() {
        if (!IS_ENABLED) return
        cancelTimer()

        isRunning = true
        currentPhase = 1
        timerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        timerJob = timerScope?.launch {
            try {
                // Phase 1: 20초 대기 → 깜빡임
                Log.d(TAG, "타이머 시작 - Phase 1 (깜빡임 대기 ${BLINK_TIMEOUT_MS / 1000}초)")
                delay(BLINK_TIMEOUT_MS)

                currentPhase = 2
                Log.d(TAG, "Phase 1 만료 → 오버레이 깜빡임 실행")
                callback?.onBlinkTimeout()

                // Phase 2: 추가 10초 대기 (총 30초) → 음성 안내
                Log.d(TAG, "Phase 2 (음성 안내 대기 ${(VOICE_TIMEOUT_MS - BLINK_TIMEOUT_MS) / 1000}초)")
                delay(VOICE_TIMEOUT_MS - BLINK_TIMEOUT_MS)

                currentPhase = 3
                Log.d(TAG, "Phase 2 만료 → 음성 안내 실행")
                callback?.onVoiceTimeout()

                // Phase 3: 추가 15초 대기 (총 45초) → 가이드 종료
                Log.d(TAG, "Phase 3 (종료 대기 ${(TERMINATE_TIMEOUT_MS - VOICE_TIMEOUT_MS) / 1000}초)")
                delay(TERMINATE_TIMEOUT_MS - VOICE_TIMEOUT_MS)

                Log.d(TAG, "Phase 3 만료 → 가이드 종료")
                callback?.onTerminateTimeout()

                isRunning = false
                currentPhase = 0
            } catch (e: CancellationException) {
                Log.d(TAG, "타이머 취소됨 (사용자 행동 감지)")
            }
        }
    }

    /**
     * 타이머를 취소합니다.
     * 사용자가 정상적인 행동(터치, 화면 전환 등)을 했을 때 호출합니다.
     */
    fun cancelTimer() {
        if (!IS_ENABLED) return
        if (isRunning) {
            Log.d(TAG, "타이머 취소 (현재 Phase=$currentPhase)")
        }
        timerJob?.cancel()
        timerJob = null
        timerScope?.cancel()
        timerScope = null
        isRunning = false
        currentPhase = 0
    }

    /**
     * 타이머를 리셋합니다 (취소 후 다시 시작).
     * 사용자가 행동은 했지만 아직 목표를 달성하지 못한 경우에 사용합니다.
     */
    fun resetTimer() {
        if (!IS_ENABLED) return
        Log.d(TAG, "타이머 리셋")
        startTimer()
    }

    /**
     * 리소스를 정리합니다. 서비스 종료 시 호출합니다.
     */
    fun destroy() {
        cancelTimer()
        callback = null
    }
}
