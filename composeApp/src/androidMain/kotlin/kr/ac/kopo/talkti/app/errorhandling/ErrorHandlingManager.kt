package kr.ac.kopo.talkti.app.errorhandling

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Context
import kotlinx.coroutines.*

/**
 * 예외 처리 통합 오케스트레이터 (ErrorHandlingManager)
 *
 * AccessibilityService의 onAccessibilityEvent에서 기존 로직보다 먼저 호출되어
 * 3가지 예외 상황(팝업, 이탈, 무한 대기)을 처리하는 문지기(Interceptor) 역할을 합니다.
 *
 * 사용법 (TalkTiAccessibilityService에서):
 *   1. onServiceConnected()에서 errorHandlingManager.initialize(this, textToSpeech)
 *   2. onAccessibilityEvent()에서 if (errorHandlingManager.interceptEvent(event)) return
 *   3. 가이드 시작 시 errorHandlingManager.onGuideStarted(packageName, goal)
 *   4. 가이드 완료 시 errorHandlingManager.onGuideCompleted()
 *   5. onDestroy()에서 errorHandlingManager.destroy()
 */
class ErrorHandlingManager {

    companion object {
        private const val TAG = "ErrorHandlingManager"
    }

    // ─── 하위 매니저들 ───
    private val popupDetector = PopupDetector()
    private val boundaryChecker = BoundaryChecker()
    private val timeoutManager = GuideTimeoutManager()

    // ─── 서비스 참조 ───
    private var service: AccessibilityService? = null
    private var textToSpeech: TextToSpeech? = null

    // ─── 깜빡임 오버레이 상태 ───
    private var blinkOverlayView: View? = null
    private var blinkJob: Job? = null
    private var lastHighlightBounds: Rect? = null
    private var lastPopupPromptTime: Long = 0L

    // ─── 복귀 오버레이 상태 ───
    private var returnOverlayView: View? = null

    /** 가이드가 현재 진행 중인지 여부 */
    var isGuideActive: Boolean = false
        private set

    /**
     * 매니저를 초기화합니다.
     * TalkTiAccessibilityService.onServiceConnected()에서 호출합니다.
     *
     * @param service 접근성 서비스 인스턴스
     * @param tts TextToSpeech 인스턴스
     */
    fun initialize(service: AccessibilityService, tts: TextToSpeech?) {
        this.service = service
        this.textToSpeech = tts

        // 타이머 콜백 등록
        timeoutManager.setCallback(object : GuideTimeoutManager.TimeoutCallback {
            override fun onBlinkTimeout() {
                handleBlinkTimeout()
            }

            override fun onVoiceTimeout() {
                handleVoiceTimeout()
            }

            override fun onTerminateTimeout() {
                handleTerminateTimeout()
            }
        })

        Log.d(TAG, "ErrorHandlingManager 초기화 완료")
    }

    /**
     * TextToSpeech 인스턴스를 업데이트합니다.
     * TTS 초기화가 비동기이므로 나중에 세팅될 수 있습니다.
     */
    fun updateTts(tts: TextToSpeech?) {
        this.textToSpeech = tts
    }

    /**
     * 가이드 시작 시 호출합니다.
     * 대상 앱 패키지명과 목표를 등록하고, 타이머를 시작합니다.
     *
     * @param targetPackageName 가이드 대상 앱의 패키지명
     * @param goal 현재 가이드 목표 (예: "딸에게 카카오톡 보내기")
     */
    fun onGuideStarted(targetPackageName: String, goal: String) {
        isGuideActive = true
        boundaryChecker.setTarget(targetPackageName, goal)
        timeoutManager.startTimer()
        Log.d(TAG, "가이드 시작: pkg=$targetPackageName, goal=$goal")
    }

    /**
     * 가이드 완료(또는 수동 종료) 시 호출합니다.
     * 모든 예외 처리 상태를 초기화합니다.
     */
    fun onGuideCompleted() {
        isGuideActive = false
        boundaryChecker.clearTarget()
        timeoutManager.cancelTimer()
        removeBlinkOverlay()
        removeReturnOverlay()
        Log.d(TAG, "가이드 종료 - 예외 처리 상태 초기화")
    }

    /**
     * 가이드 도중 사용자가 올바른 행동(목표 노드 클릭 등)을 했을 때 호출합니다.
     * 타이머를 리셋합니다 (다음 단계 안내를 위해 다시 카운트 시작).
     */
    fun onUserActionDetected() {
        if (isGuideActive) {
            timeoutManager.resetTimer()
            removeBlinkOverlay()
        }
    }

    /**
     * 오버레이(하이라이트)가 표시될 때 해당 좌표를 저장합니다.
     * 깜빡임 효과에서 동일 좌표를 재사용하기 위해 필요합니다.
     */
    fun setHighlightBounds(bounds: Rect) {
        lastHighlightBounds = bounds
    }

    // ════════════════════════════════════════════════════════════════
    //  핵심: 이벤트 인터셉터
    // ════════════════════════════════════════════════════════════════

    /**
     * onAccessibilityEvent에서 기존 로직보다 먼저 호출됩니다.
     *
     * @param event 접근성 이벤트
     * @return true = 예외 상황을 처리했으므로 기존 로직을 건너뛰어야 함,
     *         false = 예외 없음, 기존 로직 계속 진행
     */
    fun interceptEvent(event: AccessibilityEvent): Boolean {
        // 가이드가 비활성 상태면 인터셉트하지 않음
        if (!isGuideActive) return false

        // ── Step 1: 외부 앱 이탈 검사 (비활성화) ──
        /*
        val boundaryResult = boundaryChecker.checkBoundary(event)
        
        // 사용자가 자발적으로 원래 앱으로 돌아온 경우 오버레이 제거
        if (boundaryResult == BoundaryChecker.CheckResult.ON_TRACK) {
            removeReturnOverlay()
        }

        if (boundaryResult is BoundaryChecker.CheckResult.EXTERNAL_APP_DEVIATION) {
            handleExternalDeviation(boundaryResult)
            return true
        }
        if (boundaryResult == BoundaryChecker.CheckResult.ALREADY_NOTIFIED) {
            return true  // 이미 안내함, 기존 로직도 차단
        }
        */

        /*
        // ── Step 2: 팝업/광고 감지 ──
        val svc = service ?: return false
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            when (val popupResult = popupDetector.detectAndClosePopup(svc)) {
                is PopupDetector.PopupResult.RequireManualClose -> {
                    Log.d(TAG, "팝업 수동 닫기 유도 → TTS 안내 및 오버레이 표시 (isExactButton=${popupResult.isExactButton})")
                    val now = System.currentTimeMillis()
                    if (now - lastPopupPromptTime > 5000) { // 5초 쿨타임
                        if (popupResult.isExactButton) {
                            speakTts("방해되는 창이 떴습니다. 화면에 깜빡이는 닫기 버튼을 찾아 직접 눌러주세요.")
                        } else {
                            speakTts("화면에 방해되는 창이 떴습니다. 닫기 표시를 찾아 직접 눌러주세요.")
                        }
                        lastPopupPromptTime = now
                    }
                    setHighlightBounds(popupResult.popupRect)
                    startBlinkOverlay()
                    return true
                }
                is PopupDetector.PopupResult.NoPopup -> {
                    // 팝업 없음, 기존 로직 계속 진행
                }
            }
        }
        */

        // ── Step 3: 화면 변화 감지 시 타이머 리셋 ──
        // 사용자가 화면에서 무언가 액션을 취했다는 의미이므로 타이머를 리셋합니다.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            timeoutManager.resetTimer()
            removeBlinkOverlay()
        }

        // 예외 상황 없음 → 기존 로직 계속 진행
        return false
    }

    // ════════════════════════════════════════════════════════════════
    //  이탈 처리
    // ════════════════════════════════════════════════════════════════

    /**
     * 외부 앱 이탈 시 처리: TTS 안내 → 원래 앱으로 돌아가기 오버레이 표시
     */
    private fun handleExternalDeviation(result: BoundaryChecker.CheckResult.EXTERNAL_APP_DEVIATION) {
        Log.d(TAG, "외부 앱 이탈 처리: ${result.targetPackage} → ${result.currentPackage}")

        speakTts("앗, 다른 화면으로 이동하셨어요. 원래 화면으로 돌아가시려면 화면 아래의 돌아가기 버튼을 눌러주세요.")

        CoroutineScope(Dispatchers.Main).launch {
            showReturnOverlay()
        }
    }

    /**
     * 앱 내부 이탈 감지를 위한 화면 텍스트를 수집합니다.
     * 타이머 Phase 2(음성 안내) 시점에 LLM 서버로 전달하여 판단을 맡깁니다.
     *
     * @return 현재 화면의 텍스트 리스트
     */
    fun getScreenTextsForValidation(): List<String> {
        val rootNode = service?.rootInActiveWindow ?: return emptyList()
        return boundaryChecker.collectScreenTexts(rootNode)
    }

    /**
     * LLM 검증 결과 앱 내부 이탈이 확인된 경우 호출합니다.
     * 뒤로가기를 수행하고 사용자에게 안내합니다.
     *
     * @param message LLM이 생성한 안내 메시지 (예: "채팅 탭이 아니라 선물하기 탭에 계세요")
     */
    fun handleInternalDeviation(message: String) {
        speakTts(message)
        CoroutineScope(Dispatchers.Main).launch {
            delay(2000)
            service?.let { svc ->
                boundaryChecker.navigateBack(svc)
            }
        }
    }

    /**
     * 외부 앱 이탈 시 복귀를 돕는 버튼 오버레이 표시
     */
    private fun showReturnOverlay() {
        val svc = service ?: return
        if (returnOverlayView != null) return

        val windowManager = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val button = android.widget.Button(svc).apply {
            text = "원래 화면으로 돌아가기"
            setBackgroundColor(Color.parseColor("#FF5252"))
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(50, 30, 50, 30)
            elevation = 10f
            
            val drawable = GradientDrawable()
            drawable.setColor(Color.parseColor("#FF5252"))
            drawable.cornerRadius = 24f
            background = drawable

            setOnClickListener {
                Log.d(TAG, "돌아가기 오버레이 클릭됨 -> 뒤로가기 실행")
                boundaryChecker.navigateBack(svc)
                removeReturnOverlay()
                boundaryChecker.resetNotification()
            }
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 150 // Bottom margin
        }

        try {
            windowManager.addView(button, params)
            returnOverlayView = button
            Log.d(TAG, "돌아가기 오버레이 표시 성공")
        } catch (e: Exception) {
            Log.e(TAG, "돌아가기 오버레이 표시 실패: ${e.message}")
        }
    }

    private fun removeReturnOverlay() {
        returnOverlayView?.let { view ->
            try {
                val windowManager = service?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "돌아가기 오버레이 제거 실패: ${e.message}")
            }
            returnOverlayView = null
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  타이머 콜백 핸들러
    // ════════════════════════════════════════════════════════════════

    /**
     * Phase 1 만료 (20초): 오버레이 깜빡임 시작
     */
    private fun handleBlinkTimeout() {
        Log.d(TAG, "타이머 Phase 1 → 오버레이 깜빡임 시작")
        startBlinkOverlay()
    }

    /**
     * Phase 2 만료 (30초): 음성 안내
     */
    private fun handleVoiceTimeout() {
        Log.d(TAG, "타이머 Phase 2 → 음성 안내")
        speakTts("도움이 필요하신가요? 다시 처음부터 알려드릴게요. 표시된 곳을 눌러주세요.")
    }

    /**
     * Phase 3 만료 (45초): 가이드 종료
     */
    private fun handleTerminateTimeout() {
        Log.d(TAG, "타이머 Phase 3 → 가이드 종료")
        speakTts("응답이 없으셔서 안내를 잠시 멈출게요. 다시 필요하시면 똑띠를 불러주세요.")

        // 상태 전부 초기화
        removeBlinkOverlay()
        onGuideCompleted()

        // 서비스에 가이드 종료를 알림 (pendingCommand 등 초기화)
        onTerminateListener?.invoke()
    }

    // ════════════════════════════════════════════════════════════════
    //  오버레이 깜빡임 효과
    // ════════════════════════════════════════════════════════════════

    /**
     * 마지막 하이라이트 위치에 깜빡임 오버레이를 표시합니다.
     */
    private fun startBlinkOverlay() {
        val svc = service ?: return
        val bounds = lastHighlightBounds ?: return

        removeBlinkOverlay()  // 기존 깜빡임이 있으면 제거

        val windowManager = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val blinkView = View(svc).apply {
            val strokeWidth = (6 * svc.resources.displayMetrics.density).toInt()
            background = GradientDrawable().apply {
                setStroke(strokeWidth, Color.RED)
                setColor(Color.TRANSPARENT)
                cornerRadius = 8f
            }
        }

        val params = WindowManager.LayoutParams(
            (bounds.right - bounds.left).coerceAtLeast(10),
            (bounds.bottom - bounds.top).coerceAtLeast(10),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }

        try {
            windowManager.addView(blinkView, params)
            blinkOverlayView = blinkView

            // 깜빡임 애니메이션: 0.5초 간격으로 표시/숨김 반복
            blinkJob = CoroutineScope(Dispatchers.Main).launch {
                try {
                    while (isActive) {
                        blinkView.visibility = View.INVISIBLE
                        delay(500)
                        blinkView.visibility = View.VISIBLE
                        delay(500)
                    }
                } catch (e: CancellationException) {
                    // 정상 취소
                }
            }

            Log.d(TAG, "깜빡임 오버레이 표시 시작")
        } catch (e: Exception) {
            Log.e(TAG, "깜빡임 오버레이 표시 실패: ${e.message}")
        }
    }

    /**
     * 깜빡임 오버레이를 제거합니다.
     */
    private fun removeBlinkOverlay() {
        blinkJob?.cancel()
        blinkJob = null

        blinkOverlayView?.let { view ->
            try {
                val windowManager = service?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "깜빡임 오버레이 제거 실패: ${e.message}")
            }
            blinkOverlayView = null
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  TTS 및 콜백
    // ════════════════════════════════════════════════════════════════

    /**
     * TTS로 음성 안내를 출력합니다.
     */
    private fun speakTts(message: String) {
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "error_handling_tts")
    }

    /**
     * 가이드 종료(Phase 3 만료) 시 서비스에 알려주기 위한 리스너
     * TalkTiAccessibilityService에서 pendingCommand = null 등 초기화에 사용합니다.
     */
    var onTerminateListener: (() -> Unit)? = null

    /**
     * 리소스를 정리합니다. 서비스 종료 시 호출합니다.
     */
    fun destroy() {
        removeBlinkOverlay()
        removeReturnOverlay()
        timeoutManager.destroy()
        boundaryChecker.clearTarget()
        onTerminateListener = null
        service = null
        textToSpeech = null
        Log.d(TAG, "ErrorHandlingManager 리소스 정리 완료")
    }
}
