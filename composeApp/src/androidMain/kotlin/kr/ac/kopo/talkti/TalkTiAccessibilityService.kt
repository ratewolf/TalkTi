package kr.ac.kopo.talkti

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.graphics.Bitmap
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.EditText
import android.app.AlertDialog
import android.annotation.SuppressLint
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.ac.kopo.talkti.models.AppInfo
import kr.ac.kopo.talkti.models.ScreenStateRequest
import kr.ac.kopo.talkti.models.GuideActionResponse
import kr.ac.kopo.talkti.models.RectDto
import kr.ac.kopo.talkti.models.UiElement
import kr.ac.kopo.talkti.models.SelectionConversationManager
import kr.ac.kopo.talkti.models.SelectionPromptBuilder
import kr.ac.kopo.talkti.models.CandidateExtractor
import kr.ac.kopo.talkti.models.ActionTargetFinder
import kr.ac.kopo.talkti.models.SelectionSession
import kr.ac.kopo.talkti.models.UserResponseParser
import kr.ac.kopo.talkti.models.UserResponse
import kr.ac.kopo.talkti.models.SelectionFlow
import io.ktor.serialization.kotlinx.json.*

import kr.ac.kopo.talkti.app.overlay.FloatingMenuManager
import kr.ac.kopo.talkti.app.overlay.CandidateOverlayManager
import kr.ac.kopo.talkti.app.overlay.ActionButtonOverlayManager
import kr.ac.kopo.talkti.models.RouteCandidateFinder
import kr.ac.kopo.talkti.app.errorhandling.ErrorHandlingManager
import kr.ac.kopo.talkti.app.guide.GuideOrchestrator
import kr.ac.kopo.talkti.app.guide.UiChangeDetector
import kr.ac.kopo.talkti.models.GuideState
import kr.ac.kopo.talkti.app.guide.AgentSessionManager

class TalkTiAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TalkTiAccessibilityService? = null
            private set

        fun setOverlayVisible(visible: Boolean) {
            instance?.let {
                if (visible) {
                    it.floatingMenuManager?.show()
                } else {
                    it.floatingMenuManager?.hide()
                }
            }
        }

        fun isServiceRunning(): Boolean = instance != null
    }

    private val TAG = "TalkTiService"

    // 음성 선택 시스템 준비
    private val selectionManager = SelectionConversationManager()
    private val promptBuilder = SelectionPromptBuilder()
    private val candidateExtractor = CandidateExtractor()
    private val responseParser = UserResponseParser()
    private val visitedCandidateTexts = mutableSetOf<String>()

    private var floatingMenuManager: FloatingMenuManager? = null
    private var candidateOverlayManager: CandidateOverlayManager? = null
    private var actionButtonOverlayManager: ActionButtonOverlayManager? = null
    private var actionTargetFinder : ActionTargetFinder? = null
    private var routeCandidateFinder : RouteCandidateFinder? = null

    // ── UI 변경 감지 기반 가이드 시스템 ──
    private var guideOrchestrator: GuideOrchestrator? = null
    private var uiChangeDetector: UiChangeDetector? = null
    private val serviceJob = SupervisorJob()
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private val backgroundScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val guideScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob(serviceJob))

    // ── 예외 처리 매니저 (팝업/이탈/무한대기 방지) ──
    private val errorHandlingManager = ErrorHandlingManager()

    // ── 연속 가이드 세션 매니저 ──
    private val agentSessionManager = AgentSessionManager()

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private var highlightView: android.view.View? = null
    private var highlightJob: Job? = null
    private var pendingCommand: String? = null
    private var isAutoDestinationFlowActive = false
    private var lastSpokenTtsMessage: String? = null
    private var lastSpokenTtsTime: Long = 0L
    private enum class GuideStep {
        NONE,
        PLACE_SELECTION,
        DESTINATION_BUTTON,
        ROUTE_SELECTION,
        START_GUIDANCE,
        KAKAOTALK_OPENED,
        KAKAOTALK_SEARCH_CLICKED,
        KAKAOTALK_RESULT_SELECTION,
        KAKAOTALK_CHATROOM_OPENED,
        KAKAOTALK_PLUS_MENU_OPENED,
        KAKAOTALK_MEDIA_CHOOSER_OPENED,
        KAKAOTALK_MEDIA_SEND_READY
    }

    private var currentGuideStep = GuideStep.NONE
    private var mediaType: String? = null // "사진" or "동영상"
    private var llmJob: Job? = null
    private var testReceiver: android.content.BroadcastReceiver? = null
    private var lastInputText: String? = null
    private var lastInputTime: Long = 0L

    private val client = io.ktor.client.HttpClient(io.ktor.client.engine.android.Android) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 60000
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "==== 접근성 서비스 연결됨 (V2 - UI 개선 적용됨) ====")
        initSpeechRecognizer()
        initTextToSpeech()
        setupFloatingMenu()
        candidateOverlayManager = CandidateOverlayManager(this)
        actionButtonOverlayManager = ActionButtonOverlayManager(this)
        actionTargetFinder = ActionTargetFinder()
        routeCandidateFinder = RouteCandidateFinder()

        // ── UI 변경 감지 기반 가이드 시스템 초기화 ──
        guideOrchestrator = GuideOrchestrator(
            client = client,
            candidateOverlayManager = candidateOverlayManager!!,
            actionButtonOverlayManager = actionButtonOverlayManager!!
        )
        guideOrchestrator?.setTts(textToSpeech)
        val sharedPref = getSharedPreferences("talkti_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPref.getString("server_url", "http://guide.aikopo.net") ?: "http://guide.aikopo.net"
        guideOrchestrator?.setServerUrl(savedUrl)

        uiChangeDetector = UiChangeDetector()

        // ── [신규] GuideOrchestrator 와 UiChangeDetector 의 분석 상태 연동 ──
        guideOrchestrator?.onAnalyzeStateChanged = { analyzing ->
            Log.d(TAG, "[디버그] GuideOrchestrator 분석 상태 변경 알림 -> UiChangeDetector.isAnalyzing = $analyzing")
            uiChangeDetector?.isAnalyzing = analyzing
        }

        guideOrchestrator?.onStopGuide = {
            Log.d(TAG, "[디버그] GuideOrchestrator.stopGuide() 감지 → uiChangeDetector.reset() 호출")
            uiChangeDetector?.reset()
        }

        uiChangeDetector?.onMeaningfulChange = { uiTreeJson ->
            removeTargetHighlight()
            val orchestrator = guideOrchestrator
            if (orchestrator != null && orchestrator.isActive) {
                // 현재 패키지명 업데이트
                val currentPkg = rootInActiveWindow?.packageName?.toString() ?: ""
                orchestrator.updatePackageName(currentPkg)
                Log.d(TAG, "[디버그] UI 변경 통지 수신 (패키지: $currentPkg) → GuideOrchestrator.onUiChanged 호출")
                orchestrator.onUiChanged(uiTreeJson, guideScope)
            }
        }

        // 예외 처리 매니저 초기화
        errorHandlingManager.initialize(this, textToSpeech)
        errorHandlingManager.onTerminateListener = {
            // 가이드 종료 시 서비스 상태 초기화
            pendingCommand = null
            removeTargetHighlight()
            guideOrchestrator?.stopGuide()
            uiChangeDetector?.reset()
        }

        // 테스트를 위한 동적 브로드캐스트 리시버 등록
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val command = intent?.getStringExtra("command") ?: return
                Log.d(TAG, "Dynamic Test Receiver received command: $command")
                if (!processLocalCommand(command)) {
                    captureScreenForLLM(command)
                }
            }
        }
        testReceiver = receiver
        val filter = android.content.IntentFilter("kr.ac.kopo.talkti.TEST_COMMAND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun setupFloatingMenu() {
        floatingMenuManager = FloatingMenuManager(
            context = this,
            onAppGuideClick = { startAppGuide() },
            onTextInputClick = { showTextInputDialog() },
            onKioskModeClick = {
                Toast.makeText(this, "키오스크 안내 모드는 준비 중입니다.", Toast.LENGTH_SHORT).show()
            },
            onOpenAppClick = {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            },
            onLongClick = { showTerminationDialog() }
        )
        floatingMenuManager?.show()
    }

    private fun showTerminationDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("TalkTi 종료")
            .setMessage("똑띠 서비스를 종료하시겠습니까?")
            .setPositiveButton("종료") { _, _ ->
                agentSessionManager.endSession()
                removeTargetHighlight()
                disableSelf()
            }
            .setNegativeButton("취소", null)
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }

    private fun startAppGuide() {
        // [추가] LLM 대기 중 버튼 누르면 취소 처리
        if (LlmLoadingOverlay.isShowing) {
            llmJob?.cancel()
            llmJob = null
            LlmLoadingOverlay.hide()
            floatingMenuManager?.updateLoadingStatus(false)
            agentSessionManager.endSession()
            removeTargetHighlight()
            speakTts("요청을 취소했습니다.")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun showTextInputDialog() {
        val editText = EditText(this).apply {
            hint = "예: 카카오톡 보내줘, 택시 불러줘"
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("명령 입력")
            .setMessage("수행할 동작을 텍스트로 입력해주세요.")
            .setView(editText)
            .setPositiveButton("확인") { _, _ ->
                val command = editText.text.toString()
                if (command.isNotBlank()) {
                    if (!processLocalCommand(command)) {
                        if (!agentSessionManager.isActive) {
                            agentSessionManager.startSession(command)
                        }
                        captureScreenForLLM(command)
                    }
                }
            }
            .setNegativeButton("취소", null)
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }

    private fun updateButtonStatus(isListening: Boolean) {
        floatingMenuManager?.updateMainButtonStatus(isListening)
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    updateButtonStatus(true)
                    Toast.makeText(this@TalkTiAccessibilityService, "천천히 말씀해 주세요.", Toast.LENGTH_SHORT).show()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val userCommand = matches[0]

                        val currentFlow = selectionManager.currentFlow
                        if (currentFlow is SelectionFlow.Presenting || currentFlow is SelectionFlow.AwaitingVoice) {
                            handleSelectionResponse(userCommand)
                        } else {
                            if (!processLocalCommand(userCommand)) {
                                if (!agentSessionManager.isActive) {
                                    agentSessionManager.startSession(userCommand)
                                }
                                captureScreenForLLM(userCommand)
                            }
                        }
                    }
                    updateButtonStatus(false)
                }

                override fun onError(error: Int) {
                    updateButtonStatus(false)
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.setLanguage(java.util.Locale.KOREAN)
                Log.d(TAG, "TTS 초기화 성공")

                // [신규] GuideOrchestrator에도 TTS 전달
                guideOrchestrator?.setTts(textToSpeech)

                textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS 시작: $utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS 종료: $utteranceId")
                        if (utteranceId == "talkti_selection_ask") {
                            // TTS가 끝난 후 음성 인식을 재개합니다.
                            mainScope.launch {
                                delay(500) // 안정성을 위해 약간의 지연
                                Log.d(TAG, "음성 인식 재개 시도 (utteranceId=$utteranceId)")
                                startAppGuide()
                            }
                        }
                        // [신규] 완료 안내 TTS가 끝났을 때 stopGuide() 실제 정리 호출
                        if (utteranceId == "guide_orchestrator_tts" && guideOrchestrator?.isPendingStop == true) {
                            mainScope.launch {
                                Log.d(TAG, "[디버그] 완료 안내 TTS 종료 확인 → stopGuide() 호출")
                                guideOrchestrator?.stopGuide()
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS 에러: $utteranceId")
                        // [신규] 완료 안내 TTS 도중 에러가 나도 강제 stopGuide() 처리하여 교착 방지
                        if (utteranceId == "guide_orchestrator_tts" && guideOrchestrator?.isPendingStop == true) {
                            mainScope.launch {
                                Log.e(TAG, "[디버그] 완료 안내 TTS 에러 발생 → stopGuide() 강제 호출")
                                guideOrchestrator?.stopGuide()
                            }
                        }
                    }
                })
            } else {
                Log.e(TAG, "TTS 초기화 실패: status=$status")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // ── 예외 처리 인터셉터: 팝업/이탈/타이머 검사를 기존 로직보다 먼저 수행 ──
        if (errorHandlingManager.interceptEvent(event)) return

        // ── 카카오톡 결과 리스트에서 채팅방 클릭 감지 ──
        if (currentGuideStep == GuideStep.KAKAOTALK_RESULT_SELECTION && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedNode = event.source
            var shouldClose = true
            if (clickedNode != null) {
                val className = clickedNode.className?.toString() ?: ""
                if (className.contains("EditText") || className.contains("AutoCompleteTextView") || clickedNode.isEditable) {
                    shouldClose = false
                }
            }
            if (shouldClose) {
                removeTargetHighlight()
                if (mediaType != null) {
                    Log.d(TAG, "🎯 [클릭 감지] 결과 선택 완료 -> KAKAOTALK_CHATROOM_OPENED")
                    currentGuideStep = GuideStep.KAKAOTALK_CHATROOM_OPENED
                } else {
                    Log.d(TAG, "🎯 [클릭 감지] 일반 채팅방 이동 완료 -> 종료")
                    currentGuideStep = GuideStep.NONE
                    pendingCommand = null
                }
                return
            }
        }

        // ── 카카오톡 채팅방에서 더보기(+) 클릭 감지 ──
        if (currentGuideStep == GuideStep.KAKAOTALK_CHATROOM_OPENED && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedNode = event.source
            if (clickedNode != null) {
                val desc = clickedNode.contentDescription?.toString() ?: ""
                val text = clickedNode.text?.toString() ?: ""
                val viewId = clickedNode.viewIdResourceName ?: ""

                if (desc.contains("플러스") || desc.contains("더보기") || desc.contains("메뉴") || desc.contains("공유") ||
                    desc == "+" || text == "+" ||
                    viewId.endsWith("chat_menu_button") || viewId.endsWith("btn_chat_menu")
                ) {
                    val rect = Rect()
                    clickedNode.getBoundsInScreen(rect)
                    val screenHeight = resources.displayMetrics.heightPixels
                    if (rect.top > screenHeight / 2) {
                        Log.d(TAG, "🎯 [클릭 감지] 플러스 버튼 클릭됨 -> KAKAOTALK_PLUS_MENU_OPENED")
                        removeTargetHighlight()
                        currentGuideStep = GuideStep.KAKAOTALK_PLUS_MENU_OPENED
                        return
                    }
                }
            }
        }

        // ── 플러스 메뉴에서 앨범/미디어 클릭 감지 ──
        if (currentGuideStep == GuideStep.KAKAOTALK_PLUS_MENU_OPENED && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedNode = event.source
            if (clickedNode != null) {
                val text = clickedNode.text?.toString() ?: ""
                val desc = clickedNode.contentDescription?.toString() ?: ""
                val viewId = clickedNode.viewIdResourceName ?: ""

                if (text == "앨범" || desc == "앨범" || text.contains("사진") || text.contains("동영상") ||
                    viewId.endsWith("album_btn") || text.contains("album") || desc.contains("album")
                ) {
                    Log.d(TAG, "🎯 [클릭 감지] 앨범 버튼 클릭됨 -> KAKAOTALK_MEDIA_CHOOSER_OPENED")
                    removeTargetHighlight()
                    currentGuideStep = GuideStep.KAKAOTALK_MEDIA_CHOOSER_OPENED
                    return
                }
            }
        }

        // ── 미디어 선택 화면에서 썸네일 클릭 감지 ──
        if (currentGuideStep == GuideStep.KAKAOTALK_MEDIA_CHOOSER_OPENED && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedNode = event.source
            if (clickedNode != null) {
                val desc = clickedNode.contentDescription?.toString() ?: ""
                val className = clickedNode.className?.toString() ?: ""
                val viewId = clickedNode.viewIdResourceName ?: ""

                if (className.contains("ImageView") || viewId.endsWith("thumbnail") || viewId.endsWith("image") ||
                    desc.contains("사진") || desc.contains("동영상") || desc.contains("선택")
                ) {
                    Log.d(TAG, "🎯 [클릭 감지] 미디어 아이템 선택됨 -> KAKAOTALK_MEDIA_SEND_READY")
                    removeTargetHighlight()
                    currentGuideStep = GuideStep.KAKAOTALK_MEDIA_SEND_READY
                    return
                }
            }
        }

        // ── 전송 버튼 클릭 감지 ──
        if (currentGuideStep == GuideStep.KAKAOTALK_MEDIA_SEND_READY && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedNode = event.source
            if (clickedNode != null) {
                val text = clickedNode.text?.toString() ?: ""
                val desc = clickedNode.contentDescription?.toString() ?: ""
                val viewId = clickedNode.viewIdResourceName ?: ""

                if (text.contains("전송") || desc.contains("전송") || text.contains("보내기") || desc.contains("보내기") ||
                    viewId.endsWith("send_btn") || viewId.endsWith("ok_btn") || viewId.endsWith("confirm")
                ) {
                    Log.d(TAG, "🎯 [클릭 감지] 전송 버튼 클릭됨 -> 완료 종료")
                    removeTargetHighlight()
                    speakTts("전송을 완료합니다.")
                    currentGuideStep = GuideStep.NONE
                    pendingCommand = null
                    mediaType = null
                    return
                }
            }
        }

        // [수정] 사용자가 가이드된 타겟 영역을 클릭(터치)했을 때만 오버레이 및 TTS 정지/제거 처리
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedNode = event.source
            if (clickedNode != null) {
                val clickedRect = Rect()
                clickedNode.getBoundsInScreen(clickedRect)
                
                val orchestrator = guideOrchestrator
                val shouldClear = if (orchestrator != null && orchestrator.isActive) {
                    orchestrator.isClickInsideTargets(clickedRect)
                } else {
                    true // 가이드 모드가 아닐 때는 즉시 제거
                }
                
                if (shouldClear) {
                    Log.d(TAG, "🎯 [타겟 영역 클릭 감지] 오버레이 및 TTS 즉시 정리")
                    removeTargetHighlight()
                    candidateOverlayManager?.clearOverlays()
                    actionButtonOverlayManager?.clearHighlight()
                    textToSpeech?.stop()
                } else {
                    Log.d(TAG, "⚠️ [타겟 외 영역 클릭 감지] 오버레이 유지")
                }
            }
        }

        // ── 연속 가이드 티키타카 로직 ──
        // 화면에 변화가 생기거나 클릭이 일어났을 때, 세션이 진행 중이면 자동 캡처 후 서버 전송
        // (단, 실시간 가이드 오케스트레이터가 활성화된 상태이면 실시간 guide 루프에서 알아서 처리하므로 이 무거운 루프는 비활성화함)
        if (agentSessionManager.isActive && (guideOrchestrator == null || !guideOrchestrator!!.isActive)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                
                if (agentSessionManager.canCapture(3000)) {
                    val currentGoal = agentSessionManager.currentGoal
                    if (currentGoal != null) {
                        Log.d(TAG, "티키타카 루프 동작: 화면 전환/클릭 감지 -> 캡처 전송 (목표: $currentGoal)")
                        mainScope.launch {
                            delay(800) // [수정] 반응 속도를 올리기 위해 대기 시간 단축 (1000ms -> 800ms)
                            if (!LlmLoadingOverlay.isShowing) {
                                captureScreenForLLM(currentGoal)
                            }
                        }
                    }
                }
            }
        }

        val command = pendingCommand
        if (command != null && !isKakaoTalkStep(currentGuideStep) && (
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    )
        ) {
            println("📡 [이벤트 감지] 타입: ${event.eventType}, 현재 대기 목적지: $command")
            mainScope.launch {
                delay(600) // 새로운 화면이 완전히 그려질 시간 대기
                val currentCmd = pendingCommand
                if (currentCmd != null && !isKakaoTalkStep(currentGuideStep)) {
                    val success = autofillEditTextInActiveWindow(currentCmd)

                    if (success) {

                        pendingCommand = null // 타이핑 성공 시 대기 큐에서 제거

                        speakTts("${currentCmd}을 입력했습니다.")

                        if (isAutoDestinationFlowActive) {

                            mainScope.launch {

                                delay(1500)

                                showAutoDestinationCandidates()
                            }
                        }

                    } else {

                        println("❌ [매크로 대기] 입력창 검색 실패. 다음 변경 이벤트를 기다립니다.")
                    }
                }
            }
        }

        // ── KakaoTalk 매크로 흐름 제어 ──
        if (isKakaoTalkStep(currentGuideStep) && (
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    )
        ) {
            val rootNode = rootInActiveWindow
            val targetFriend = pendingCommand ?: "아부지"

            if (currentGuideStep == GuideStep.KAKAOTALK_OPENED) {
                // EditText(검색창)가 등장했다면 즉시 검색 입력 단계로 전환
                if (hasEditText(rootNode)) {
                    removeTargetHighlight()
                    currentGuideStep = GuideStep.KAKAOTALK_SEARCH_CLICKED
                } else {
                    val searchButton = findKakaoTalkSearchButton(rootNode)
                    if (searchButton != null) {
                        val rect = Rect()
                        searchButton.getBoundsInScreen(rect)
                        val bounds = RectDto(rect.left, rect.top, rect.right, rect.bottom)

                        showTargetHighlight(bounds, "돋보기 모양 검색 버튼을 눌러주세요.", Color.RED)
                        speakTts("돋보기 모양 검색 버튼을 눌러주세요.")
                    }
                }
            }
            else if (currentGuideStep == GuideStep.KAKAOTALK_SEARCH_CLICKED) {
                // EditText에 친구 이름(예: 아부지) 주입 시도
                val typed = autofillEditTextInActiveWindow(targetFriend)
                if (typed) {
                    speakTts("${targetFriend}을 입력했습니다.")
                    currentGuideStep = GuideStep.KAKAOTALK_RESULT_SELECTION
                }
            }
            else if (currentGuideStep == GuideStep.KAKAOTALK_RESULT_SELECTION) {
                // 상단 검색창(EditText)이 화면에 여전히 존재하는 동안 결과를 기다림 (레이스 컨디션 및 채팅방 구분 방지)
                if (hasTopSearchEditText(rootNode)) {
                    if (hasSearchEditText(rootNode, targetFriend)) {
                        val resultNode = findKakaoTalkSearchResult(rootNode, targetFriend)
                        if (resultNode != null) {
                            val rect = Rect()
                            resultNode.getBoundsInScreen(rect)
                            val bounds = RectDto(rect.left, rect.top, rect.right, rect.bottom)

                            showTargetHighlight(bounds, "검색된 $targetFriend 채팅방을 눌러주세요.", Color.RED)
                            speakTts("검색된 $targetFriend 채팅방을 눌러주세요.")
                        }
                    }
                } else {
                    // 상단 검색창이 완전히 사라졌다 -> 이미 클릭해서 채팅방으로 들어간 상태!
                    removeTargetHighlight()
                    if (mediaType != null) {
                        currentGuideStep = GuideStep.KAKAOTALK_CHATROOM_OPENED
                    } else {
                        currentGuideStep = GuideStep.NONE
                        pendingCommand = null
                    }
                }
            }
            else if (currentGuideStep == GuideStep.KAKAOTALK_CHATROOM_OPENED) {
                if (hasPlusMenuOpen(rootNode)) {
                    removeTargetHighlight()
                    currentGuideStep = GuideStep.KAKAOTALK_PLUS_MENU_OPENED
                } else {
                    val chatMenuButton = findKakaoTalkChatMenuButton(rootNode)
                    if (chatMenuButton != null) {
                        val rect = Rect()
                        chatMenuButton.getBoundsInScreen(rect)
                        val bounds = RectDto(rect.left, rect.top, rect.right, rect.bottom)

                        showTargetHighlight(bounds, "더보기 플러스 버튼을 눌러주세요.", Color.RED)
                        speakTts("입력창 왼쪽의 플러스 버튼을 눌러주세요.")
                    }
                }
            }
            else if (currentGuideStep == GuideStep.KAKAOTALK_PLUS_MENU_OPENED) {
                if (hasMediaChooserOpen(rootNode)) {
                    removeTargetHighlight()
                    currentGuideStep = GuideStep.KAKAOTALK_MEDIA_CHOOSER_OPENED
                } else {
                    val mediaButton = findKakaoTalkMediaButton(rootNode, mediaType ?: "사진")
                    if (mediaButton != null) {
                        val rect = Rect()
                        mediaButton.getBoundsInScreen(rect)
                        val bounds = RectDto(rect.left, rect.top, rect.right, rect.bottom)

                        val typeKorean = if (mediaType == "동영상") "동영상" else "사진"
                        showTargetHighlight(bounds, "앨범 버튼을 눌러주세요.", Color.RED)
                        speakTts("${typeKorean} 전송을 위해 앨범 버튼을 눌러주세요.")
                    }
                }
            }
            else if (currentGuideStep == GuideStep.KAKAOTALK_MEDIA_CHOOSER_OPENED) {
                val mediaGrid = findKakaoTalkMediaGrid(rootNode)
                if (mediaGrid != null) {
                    val rect = Rect()
                    mediaGrid.getBoundsInScreen(rect)
                    val bounds = RectDto(rect.left, rect.top, rect.right, rect.bottom)

                    val typeKorean = if (mediaType == "동영상") "보낼 동영상" else "보낼 사진"
                    showTargetHighlight(bounds, "보낼 파일을 선택해주세요.", Color.RED)
                    speakTts("보낼 ${typeKorean}을 선택해주세요.")
                }
            }
            else if (currentGuideStep == GuideStep.KAKAOTALK_MEDIA_SEND_READY) {
                if (!hasMediaChooserOpen(rootNode)) {
                    Log.d(TAG, "🎯 [화면 전환] 미디어 선택창이 닫혀 가이드 종료!")
                    removeTargetHighlight()
                    speakTts("전송을 완료합니다.")
                    currentGuideStep = GuideStep.NONE
                    pendingCommand = null
                    mediaType = null
                } else {
                    val sendButton = findKakaoTalkMediaSendButton(rootNode)
                    if (sendButton != null) {
                        val rect = Rect()
                        sendButton.getBoundsInScreen(rect)
                        val bounds = RectDto(rect.left, rect.top, rect.right, rect.bottom)

                        showTargetHighlight(bounds, "전송 버튼을 눌러주세요.", Color.RED)
                        speakTts("우측 상단의 전송 버튼을 눌러주세요.")
                    }
                }
            }
        }
        if (
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            // ── [신규] UI 변경 감지 기반 가이드 (LLM 우선) ──
            val orchestrator = guideOrchestrator
            if (orchestrator != null && orchestrator.isActive) {
                val uiTreeJsonForGuide = extractScreenTree()
                Log.d(TAG, "[디버그] 접근성 변경 이벤트 감지 → UiChangeDetector.onNewUiTree 전달")
                uiChangeDetector?.onNewUiTree(uiTreeJsonForGuide, guideScope)
            }

            // ── [기존 Fallback] GuideStep 기반 로직 (else if 체인으로 연쇄 전이 방지) ──
            if (currentGuideStep == GuideStep.PLACE_SELECTION) {

                val uiTreeJson = extractScreenTree()

                val elements = try {
                    Json.decodeFromString<List<UiElement>>(uiTreeJson)
                } catch (e: Exception) {
                    emptyList()
                }

                val actionTarget =
                    actionTargetFinder?.findPrimaryAction(elements)

                if (actionTarget != null) {

                    actionButtonOverlayManager?.showActionButtonHighlight(
                        actionTarget.bounds,
                        actionTarget.text
                    )

                    speakTts("${actionTarget.text} 버튼을 눌러주세요.")

                    currentGuideStep =
                        GuideStep.DESTINATION_BUTTON
                }
            } else if (currentGuideStep == GuideStep.DESTINATION_BUTTON) {

                showRouteSelectionOverlay()

                currentGuideStep =
                    GuideStep.ROUTE_SELECTION
            } else if (currentGuideStep == GuideStep.ROUTE_SELECTION) {

                val uiTreeJson = extractScreenTree()

                val elements = try {
                    Json.decodeFromString<List<UiElement>>(uiTreeJson)
                } catch (e: Exception) {
                    emptyList()
                }

                val actionTarget =
                    actionTargetFinder?.findPrimaryAction(elements)

                if (actionTarget != null) {

                    actionButtonOverlayManager?.showActionButtonHighlight(
                        actionTarget.bounds,
                        actionTarget.text
                    )

                    speakTts("${actionTarget.text} 버튼을 눌러주세요.")

                    currentGuideStep =
                        GuideStep.START_GUIDANCE
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun openAppByName(appNameOrPackage: String, searchQuery: String? = null): String? {
        val pm = packageManager
        Log.d(TAG, "openAppByName 호출: $appNameOrPackage, searchQuery: $searchQuery")

        // 1. 패키지명으로 직접 실행 시도 (LLM이 패키지명을 보낸 경우)
        try {
            val systemAndImePackages = setOf(
                "android",
                "com.android.systemui",
                "com.google.android.inputmethod.latin",
                "com.sec.android.inputmethod",
                "com.samsung.android.honeyboard"
            )
            val isAlreadyRunning = windows?.any { 
                val pkg = it.root?.packageName?.toString()
                pkg == appNameOrPackage && pkg !in systemAndImePackages 
            } ?: (rootInActiveWindow?.packageName?.toString() == appNameOrPackage)

            if (isAlreadyRunning) {
                Log.d(TAG, "앱이 이미 전면에 실행 중이거나 백그라운드 윈도우에 존재합니다: $appNameOrPackage")
                return appNameOrPackage
            }

            if (isMapPackage(appNameOrPackage) && !searchQuery.isNullOrBlank()) {
                val launched = launchMapWithDeepLink(appNameOrPackage, searchQuery)
                if (launched) return appNameOrPackage
            }
            val intent = pm.getLaunchIntentForPackage(appNameOrPackage)
            if (intent != null) {
                // 기존 태스크를 완전히 지우고 새 태스크로 앱을 처음부터 실행 (실행종료 후 재실행 효과)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                val appLabel = pm.getApplicationLabel(pm.getApplicationInfo(appNameOrPackage, 0))
                speakTts("${appLabel} 앱을 실행합니다.")
                return appNameOrPackage
            }
        } catch (e: Exception) {
            // 패키지명이 아닌 경우 아래의 검색 로직으로 진행
        }

        val cleanCmd = appNameOrPackage.replace(" ", "").lowercase()

        val aliasMap = mapOf(
            //갤러리
            "갤러리" to listOf("com.sec.android.gallery3d"),
            "사진첩" to listOf("com.sec.android.gallery3d"),
            "사진" to listOf("com.sec.android.gallery3d"),
            "앨범" to listOf("com.sec.android.gallery3d"),
            "찍은거" to listOf("com.sec.android.gallery3d"),
            //지도
            "지도" to listOf("net.daum.android.map", "com.nhn.android.nmap"),
            "길찾기" to listOf("net.daum.android.map", "com.nhn.android.nmap"),
            "네비" to listOf("com.skt.tmap.ku", "com.skt.tmap", "net.daum.android.map", "com.nhn.android.nmap"),
            "내비게이션" to listOf("com.skt.tmap.ku", "com.skt.tmap", "net.daum.android.map", "com.nhn.android.nmap"),
            "티맵" to listOf("com.skt.tmap.ku", "com.skt.tmap"),
            "카카오맵" to listOf("net.daum.android.map"),
            "카카오지도" to listOf("net.daum.android.map"),
            "네이버지도" to listOf("com.nhn.android.nmap"),
            "네이버맵" to listOf("com.nhn.android.nmap"),
            //택시
            "택시" to listOf("com.kakao.taxi"),
            "카카오택시" to listOf("com.kakao.taxi"),
            "카카오티" to listOf("com.kakao.taxi"),
            "콜택시" to listOf("com.kakao.taxi"),
            //통신
            "전화" to listOf("com.samsung.android.dialer"),
            "통화" to listOf("com.samsung.android.dialer"),
            "문자" to listOf("com.samsung.android.messaging"),
            "문자함" to listOf("com.samsung.android.messaging"),
            "카톡" to listOf("com.kakao.talk"),
            "카카오톡" to listOf("com.kakao.talk"),
            "네이버" to listOf("com.nhn.android.search", "com.nhn.android.nmap"),
            "당근마켓" to listOf("com.towneers.www"),
            "당근" to listOf("com.towneers.www"),
            "중고거래" to listOf("com.towneers.www"),
            //유튜브
            "유튜브" to listOf("com.google.android.youtube"),
            "유튭" to listOf("com.google.android.youtube"),
            //설정
            "돋보기" to listOf("com.samsung.android.app.magnifier"),
            "크게보기" to listOf("com.samsung.android.app.magnifier"),
            "만보기" to listOf("com.sec.android.app.shealth"),
            "걷기" to listOf("com.sec.android.app.shealth"),
            "설정" to listOf("com.android.settings"),
            "톱니바퀴" to listOf("com.android.settings"),
            //배달
            "배달앱" to listOf("woowahan.baemin","com.coupang.mobile.eats"),
            "배달의민족" to listOf("woowahan.baemin"),
            "배민" to listOf("woowahan.baemin"),
            "쿠팡이츠" to listOf("com.coupang.mobile.eats"),
            "쿠팡배달" to listOf("com.coupang.mobile.eats"),
            "쿠팡음식" to listOf("com.coupang.mobile.eats")

        )

        //별칭 검색 및 실행 로직 (양방향 매칭 적용)
        for ((alias, packageList) in aliasMap) {
            if (cleanCmd.contains(alias) || alias.contains(cleanCmd)) {
                // 리스트 중에서 실제로 설치된 앱 패키지 하나를 찾습니다.
                val installedPackage = packageList.firstOrNull { pkg ->
                    try {
                        pm.getPackageInfo(pkg, 0)
                        true
                    } catch (e: Exception) {
                        false
                    }
                }

                if (installedPackage != null) {
                    if (isMapPackage(installedPackage) && !searchQuery.isNullOrBlank()) {
                        val launched = launchMapWithDeepLink(installedPackage, searchQuery)
                        if (launched) return installedPackage
                    }
                    val intent = pm.getLaunchIntentForPackage(installedPackage)
                    if (intent != null) {
                        // 기존 태스크를 완전히 지우고 새 태스크로 앱을 처음부터 실행 (실행종료 후 재실행 효과)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent)
                        speakTts("${alias}를 실행합니다.")
                        return installedPackage
                    }
                }
            }
        }

        //설치된 전체 앱 리스트에서 검색 (별칭에 없는 경우)
        val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        for (appInfo in packages) {
            val appLabel = pm.getApplicationLabel(appInfo).toString()
            val cleanLabel = appLabel.replace(" ", "").lowercase()

            // 매칭 조건 개선: 명령어에 앱 이름이 포함되거나, 앱 이름에 명령어가 포함된 경우
            if (cleanLabel.length >= 2 && (cleanCmd.contains(cleanLabel) || cleanLabel.contains(cleanCmd))) {
                val intent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (intent != null) {
                    if (isMapPackage(appInfo.packageName) && !searchQuery.isNullOrBlank()) {
                        val launched = launchMapWithDeepLink(appInfo.packageName, searchQuery)
                        if (launched) return appInfo.packageName
                    }
                    // 기존 태스크를 완전히 지우고 새 태스크로 앱을 처음부터 실행 (실행종료 후 재실행 효과)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    speakTts("${appLabel}을 실행합니다.")
                    return appInfo.packageName
                }
            }
        }
        return null
    }

    private fun isMapPackage(packageName: String): Boolean {
        return packageName == "net.daum.android.map" || 
               packageName == "com.nhn.android.nmap" || 
               packageName == "com.skt.tmap.ku" || 
               packageName == "com.skt.tmap"
    }

    private fun launchMapWithDeepLink(packageName: String, query: String): Boolean {
        return try {
            val uri = when (packageName) {
                "net.daum.android.map" -> android.net.Uri.parse("kakaomap://search?q=" + android.net.Uri.encode(query))
                "com.nhn.android.nmap" -> android.net.Uri.parse("nmap://search?query=" + android.net.Uri.encode(query))
                "com.skt.tmap.ku", "com.skt.tmap" -> android.net.Uri.parse("tmap://search?name=" + android.net.Uri.encode(query))
                else -> null
            }

            if (uri == null) {
                false
            } else {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    // 기존 태스크를 완전히 지우고 새 태스크로 앱을 처음부터 실행 (실행종료 후 재실행 효과)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                val appLabel = if (packageName == "net.daum.android.map") "카카오맵" else "네이버지도"
                speakTts("${appLabel}에서 ${query}을 검색합니다.")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "딥링크 실행 실패: ${e.message}")
            false
        }
    }

    private fun startGuideFlow(command: String, packageName: String) {
        uiChangeDetector?.reset()
        guideOrchestrator?.startGuide(command, packageName)
        
        // 가이드 시작 후 앱이 로딩되고 화면이 완전히 그려질 시간을 확보하기 위해 약간 지연 후 분석 실행 (3초 지연 현상 방지)
        guideScope.launch {
            delay(1200) // 1.2초 대기하여 카카오맵 검색 결과 등이 뜰 때까지 대기
            val initialUiTree = extractScreenTree()
            Log.d(TAG, "[디버그] 가이드 시작 후 지연 분석을 실행합니다.")
            guideOrchestrator?.onUiChanged(initialUiTree, guideScope)
        }
    }

    private fun processLocalCommand(command: String): Boolean {
        val cleanCmd = command.replace(" ", "").lowercase()

        // 0. 카카오톡 매크로 의도 파악 (예: "아들한테 사진보내줘", "딸한테 카톡보내줘", "이영주 교수님한테 사진보내줘")
        val kakaoTalkRegex = Regex("^\\s*(.+?)한테.*(보내|사진|카톡|동영상|비디오|영상)")
        val kakaoMatchResult = kakaoTalkRegex.find(command)
        if (kakaoMatchResult != null) {
            val friendName = kakaoMatchResult.groupValues[1].trim()
            if (friendName.isNotBlank()) {
                pendingCommand = friendName
                currentGuideStep = GuideStep.KAKAOTALK_OPENED

                // 미디어 타입 분석
                if (command.contains("사진") || command.contains("이미지") || command.contains("앨범")) {
                    mediaType = "사진"
                } else if (command.contains("동영상") || command.contains("비디오") || command.contains("영상")) {
                    mediaType = "동영상"
                } else {
                    mediaType = null
                }

                val launchedPkg = openAppByName("com.kakao.talk")
                if (launchedPkg != null) {
                    errorHandlingManager.onGuideStarted(launchedPkg, command)
                    return true
                }
            }
        }

        // 1. 의도 파악 (공백 제거 기반 키워드 매칭)
        val routeKeywords = listOf("가자", "가는길찾아줘", "길찾아줘", "찾아달라니까", "찾아줘", "어떻게가", "가고싶어", "알려줘", "길찾기")
        val isRouteCommand = routeKeywords.any { cleanCmd.endsWith(it) || cleanCmd.contains(it) }
        val isAppOpenCmd = cleanCmd.contains("열어") || cleanCmd.contains("켜") || cleanCmd.contains("실행") || cleanCmd.contains("보여줘")

        // [수정] 대화 중심 LLM 의도 분석을 위해 음성 인식 시점에 미리 startGuideFlow()를 실행하지 않음
        // val currentPkg = rootInActiveWindow?.packageName?.toString() ?: ""
        // startGuideFlow(command, currentPkg)

        // 2. [수정] 하드코딩된 로컬 지도 실행 차단 및 LLM 통신으로 위임
        // if (isRouteCommand && !isAppOpenCmd) {
        //     val destination = cleanSearchQuery(command)
        //     if (destination.isNotBlank()) {
        //         val launchedPkg = openAppByName("지도", destination)
        //         if (launchedPkg != null) {
        //             errorHandlingManager.onGuideStarted(launchedPkg, command)
        //             agentSessionManager.startSession(command)
        //         } else {
        //             speakTts("${destination}을 검색할 수 있는 지도 앱이 없습니다.")
        //         }
        //         return true
        //     }
        // }

        // 3. Selection 흐름 실제 시작 연결 (기존 흐름 유지, LLM 전송 회피)
        if (cleanCmd.contains("선택시작") || cleanCmd.contains("후보선택") || cleanCmd.contains("목록읽어줘")) {
            startSelectionFlow(isContinuation = false)
            return true
        }

        // 4. 단순 앱 실행 명령
        if (isAppOpenCmd) {
            val launchedPkg = openAppByName(cleanCmd, cleanSearchQuery(command))
            if (launchedPkg != null) {
                errorHandlingManager.onGuideStarted(launchedPkg, command)
                // 길찾기뿐만 아니라, 일반 앱(유튜브, 카톡, 갤러리 등)을 실행한 후에도 무조건 LLM 연속 루프로 전환
                agentSessionManager.startSession(command)
                return true
            }
        }

        return false
    }


    fun captureScreenForLLM(userCommand: String) {
        removeTargetHighlight()
        showLlmLoading()

        val screenSessionId = agentSessionManager.sessionId ?: "screen_${System.currentTimeMillis()}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val hardwareBuffer = screenshotResult.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                    val realUiTree = extractScreenTree()
                    val softwareBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBuffer.close()

                    if (softwareBitmap != null) {
                        llmJob?.cancel()
                        llmJob = backgroundScope.launch {
                            val base64Image = bitmapToBase64(softwareBitmap)
                            softwareBitmap.recycle()
                            sendDataToServer(userCommand, base64Image, realUiTree, screenSessionId)
                        }
                    } else {
                        hideLlmLoading()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "스크린샷 실패: errorCode=$errorCode")
                    hideLlmLoading()
                }
            })
        } else {
            hideLlmLoading()
        }
    }

    private fun showLlmLoading() {
        LlmLoadingOverlay.show(this@TalkTiAccessibilityService)
        floatingMenuManager?.bringToFront()
        floatingMenuManager?.updateLoadingStatus(true)
        speakTts("똑띠가 생각 중이에요. 잠시만 기다려주세요.")
    }

    private fun hideLlmLoading() {
        floatingMenuManager?.updateLoadingStatus(false)
        LlmLoadingOverlay.hide()
    }

    private fun getInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        return apps.mapNotNull { appInfo ->
            val intent = pm.getLaunchIntentForPackage(appInfo.packageName)
            if (intent != null) { // 실행 가능한 앱만 필터링
                AppInfo(
                    appLabel = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName
                )
            } else null
        }
    }

    private suspend fun sendDataToServer(command: String, base64Image: String, uiTree: String, screenSessionId: String) {
        val sharedPref = getSharedPreferences("talkti_prefs", Context.MODE_PRIVATE)
        var baseUrl = sharedPref.getString("server_url", "http://guide.aikopo.net") ?: "http://guide.aikopo.net"

        baseUrl = baseUrl.trim().removeSuffix("/")
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://$baseUrl"
        }

        val serverUrl = "$baseUrl/analyze"
        val installedApps = getInstalledApps()
        val currentPackageName = getActiveAppPackageName()

        Log.d(TAG, "서버 전송 시작: $serverUrl, 명령어: $command, 앱 개수: ${installedApps.size}, 현재앱: $currentPackageName")

        try {
                val response: GuideActionResponse = client.post(serverUrl) {
                    contentType(ContentType.Application.Json)
                    header("bypass-tunnel-reminder", "true")
                    setBody(ScreenStateRequest(
                        userVoiceCommand = command,
                        uiTreeJson = uiTree,
                        screenshotBase64 = base64Image,
                        screenSessionId = screenSessionId,
                        installedApps = installedApps,
                        currentPackageName = currentPackageName
                    ))
                }.body()

                Log.d(TAG, "서버 응답 수신 성공: ${response.ttsMessage}")
                withContext(Dispatchers.Main) {
                    floatingMenuManager?.updateLoadingStatus(false)
                    
                    if (response.actionType == "FINISH") {
                        Log.d(TAG, "가이드 완전 종료 신호 (FINISH) 수신 -> 세션 종료")
                        agentSessionManager.endSession()
                        removeTargetHighlight()
                    }
                    
                    // [수정] ASK_USER일 경우 음성 인식 재개를 위해 ID 부여
                    val utteranceId = if (response.actionType == "ASK_USER") "talkti_selection_ask" else "talkti_tts"
                    speakTts(response.ttsMessage, utteranceId)

                    // [수정] 질문(ASK_USER)일 때는 노란색, 그 외 액션은 빨간색 가이드
                    val highlightColor = if (response.actionType == "ASK_USER") Color.YELLOW else Color.RED

                    val targetBounds = response.targetBounds
                    if (targetBounds != null) {
                        val keepInfinite = (response.actionType == "GUIDE" || response.actionType == "ASK_USER")
                        showTargetHighlight(targetBounds, response.ttsMessage, highlightColor, keepInfinite)
                    } else {
                        removeTargetHighlight()
                    }

                    if (response.actionType == "OPEN_APP") {
                        val targetId = response.targetCandidateId
                        Log.d(TAG, "OPEN_APP 시도: targetId=$targetId")
                        if (targetId != null) {
                            val systemAndImePackages = setOf(
                                "android",
                                "com.android.systemui",
                                "com.google.android.inputmethod.latin",
                                "com.sec.android.inputmethod",
                                "com.samsung.android.honeyboard"
                            )
                            val isAlreadyRunning = windows?.any { 
                                val pkg = it.root?.packageName?.toString()
                                pkg == targetId && pkg !in systemAndImePackages 
                            } ?: (rootInActiveWindow?.packageName?.toString() == targetId)

                            if (isAlreadyRunning) {
                                Log.d(TAG, "앱이 이미 화면에 활성화되어 있으므로 OPEN_APP 재실행을 차단합니다: $targetId")
                            } else {
                                val query = if (!response.actionArguments.isNullOrBlank()) {
                                    response.actionArguments
                                } else {
                                    cleanSearchQuery(command)
                                }
                                val launchedPkg = openAppByName(targetId, query)
                                if (launchedPkg != null) {
                                    val finalGoal = agentSessionManager.currentGoal ?: command
                                    errorHandlingManager.onGuideStarted(launchedPkg, finalGoal)
                                    startGuideFlow(finalGoal, launchedPkg)
                                }
                            }
                        }
                    } else if (response.actionType == "CLICK" || response.actionType == "ACTION_SET_TEXT") {
                        val currentPkg = rootInActiveWindow?.packageName?.toString() ?: ""
                        if (currentPkg.isNotBlank()) {
                            val finalGoal = agentSessionManager.currentGoal ?: command
                            errorHandlingManager.onGuideStarted(currentPkg, finalGoal)
                            startGuideFlow(finalGoal, currentPkg)
                        }
                    } else {
                        Log.d(TAG, "대화형 상태(ASK_USER 등) 또는 기타 상태 - guideOrchestrator 가이드 흐름을 시작하지 않습니다.")
                    }
                    if (response.actionType == "OPEN_APP") {
                        // OPEN_APP 처리는 이미 위에서 완료
                    } else if (response.actionType == "ACTION_SET_TEXT" && !response.actionArguments.isNullOrBlank()) {
                        println("자동 텍스트 입력 예약 실행: ${response.actionArguments}")
                        response.targetBounds?.let { bounds ->
                            mainScope.launch {
                                delay(1000)
                                val success = performImmediateActionSetText(bounds, response.actionArguments!!)
                                if (!success) {
                                    // [수정] 자동 클릭 기능을 전면 제거하여 사용자 직접 클릭 유도
                                    // performImmediateActionClick(bounds)
                                }
                            }
                        }
                    } else if (response.actionType == "CLICK") {
                        println("자동 클릭 예약 실행 (사용자 클릭 유도로 변경하여 자동 클릭 비활성화)")
                        response.targetBounds?.let { bounds ->
                            mainScope.launch {
                                delay(1000)
                                // [수정] 자동 클릭 기능을 제거하여 사용자가 하이라이트를 보고 직접 클릭하도록 함
                                // performImmediateActionClick(bounds)
                                // LLM 모드에서는 pendingCommand를 사용한 강제 주입을 하지 않고 LLM 루프에 맡김
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "서버 통신 실패 (${e.javaClass.simpleName}): ${e.message}")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@TalkTiAccessibilityService, "연결 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            withContext(Dispatchers.Main) {
                hideLlmLoading()
            }
        }
    }

    private fun speakTts(message: String, utteranceId: String = "talkti_tts") {
        val now = System.currentTimeMillis()
        if (message == lastSpokenTtsMessage && now - lastSpokenTtsTime < 3500) {
            Log.d(TAG, "speakTts 중복 차단 (stutter 방지): $message")
            return
        }
        lastSpokenTtsMessage = message
        lastSpokenTtsTime = now

        Log.d(TAG, "speakTts(msg='$message', id='$utteranceId')")
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        val result = textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "speakTts 호출 실패")
        }
    }

    private fun isValidGuideResponse(response: GuideActionResponse, requestSessionId: String): Boolean {
        if (response.actionType == "OPEN_APP") return true
        if (response.actionType == "CLICK" && response.targetBounds == null) return false
        return true
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 압축률을 50%로 조정하여 전송 데이터 크기를 대폭 줄임
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun handleSelectionResponse(sttResult: String) {
        val response = responseParser.parse(sttResult)

        if (response == UserResponse.UNKNOWN) {
            val currentCandidate = selectionManager.getCurrentCandidate()
            if (currentCandidate != null) {
                val prompt = promptBuilder.buildCandidateQuestion(currentCandidate, selectionManager.currentCandidateIndex)
                speakTts(prompt, "talkti_selection_ask")
                showTargetHighlight(currentCandidate.bounds, prompt, Color.YELLOW)
            }
            return
        }

        selectionManager.handleResponse(response)

        when (val flow = selectionManager.currentFlow) {
            is SelectionFlow.Resolved -> {
                val candidate = flow.selected
                val ttsMessage = "${candidate.text} 선택이 완료되었습니다."
                speakTts(ttsMessage)
                showTargetHighlight(candidate.bounds, ttsMessage, Color.RED)
            }
            is SelectionFlow.AwaitingVoice -> {
                val currentCandidate = selectionManager.getCurrentCandidate()
                if (currentCandidate != null) {
                    val prompt = promptBuilder.buildCandidateQuestion(currentCandidate, selectionManager.currentCandidateIndex)
                    speakTts(prompt, "talkti_selection_ask")
                    showTargetHighlight(currentCandidate.bounds, prompt, Color.YELLOW)
                }
            }
            is SelectionFlow.Cancelled -> {
                speakTts("선택이 취소되었습니다.")
                removeTargetHighlight()
            }
            is SelectionFlow.CandidatesExhausted -> {
                speakTts("화면의 모든 항목을 확인했습니다. 다음 화면을 탐색합니다.")
                removeTargetHighlight()
                val scrolled = attemptScrollForward()
                if (scrolled) {
                    mainScope.launch {
                        delay(1000) // 스크롤 애니메이션 대기
                        startSelectionFlow(isContinuation = true)
                    }
                }
            }
            else -> {}
        }
    }

    private fun attemptScrollForward(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        val queue = mutableListOf(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            // 스크롤 가능하며, 전방 스크롤(ACTION_SCROLL_FORWARD) 액션을 지원하는지 확인
            if (node.isScrollable && node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)) {
                val scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                if (scrolled) {
                    // 스크롤 성공 시 간단한 안내
                    // 추후 이 시점에 새 화면을 캡처하고 후보를 추출하는 로직을 연결할 수 있습니다.
                    speakTts("화면을 넘겼습니다.")
                    return true
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        speakTts("더 이상 넘길 화면이 없습니다.")
        return false
    }

    private fun startSelectionFlow(isContinuation: Boolean = false) {
        if (!isContinuation) {
            visitedCandidateTexts.clear()
        }
        currentGuideStep = GuideStep.PLACE_SELECTION

        val uiTreeJson = extractScreenTree()
        val elements = try {
            Json.decodeFromString<List<UiElement>>(uiTreeJson)
        } catch (e: Exception) {
            emptyList()
        }

        val candidates = candidateExtractor.extractCandidates(elements)
            .filter { it.text !in visitedCandidateTexts }

        // 현재 추출된 후보들을 방문 기록에 추가
        candidates.forEach { visitedCandidateTexts.add(it.text) }

        if (candidates.isNotEmpty()) {
            candidateOverlayManager?.showCandidates(candidates) { selectedCandidate ->

                Log.d(
                    "TalkTiService",
                    "Selected Candidate overlay touched: ${selectedCandidate.id} - ${selectedCandidate.text}"
                )


                val currentUiTree = extractScreenTree()

                val currentElements = try {
                    Json.decodeFromString<List<UiElement>>(currentUiTree)
                } catch (e: Exception) {
                    emptyList()
                }

                val actionTarget =
                    actionTargetFinder?.findPrimaryAction(currentElements)

                if (actionTarget != null) {

                    actionButtonOverlayManager?.showActionButtonHighlight(
                        actionTarget.bounds,
                        actionTarget.text
                    ) {
                        showRouteSelectionOverlay()
                    }

                    speakTts("${actionTarget.text} 버튼을 눌러주세요.")

                } else {

                    speakTts("도착 버튼을 찾을 수 없습니다.")

                }
            }
        } else {
            speakTts("더 이상 새로운 항목이 없습니다.")
        }
    }
    private fun showAutoDestinationCandidates() {

        val uiTreeJson = extractScreenTree()

        val elements = try {
            Json.decodeFromString<List<UiElement>>(uiTreeJson)
        } catch (e: Exception) {
            emptyList()
        }

        val candidates =
            candidateExtractor.extractCandidates(elements)

        if (candidates.isEmpty()) {

            speakTts("목적지를 찾을 수 없습니다.")

            return
        }

        candidateOverlayManager?.showCandidates(candidates) { selectedCandidate ->

            performImmediateActionClick(
                selectedCandidate.bounds
            )

            speakTts("${selectedCandidate.text} 선택됨")

            currentGuideStep =
                GuideStep.PLACE_SELECTION

            isAutoDestinationFlowActive = false
        }

        speakTts("목적지를 선택해주세요.")
    }
    private fun showRouteSelectionOverlay() {

        val currentUiTree = extractScreenTree()

        val currentElements = try {
            Json.decodeFromString<List<UiElement>>(currentUiTree)
        } catch (e: Exception) {
            emptyList()
        }

        val routeCandidates =
            routeCandidateFinder?.findRouteCandidates(currentElements)
                ?: emptyList()

        if (routeCandidates.isEmpty()) {
            speakTts("경로를 찾을 수 없습니다.")
            return
        }

        candidateOverlayManager?.showCandidates(routeCandidates) {
            speakTts(
                "추천 경로로 가시려면 첫 번째 경로를 눌러주세요. 다른 경로를 원하시면 원하는 경로를 눌러주세요."
            )
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        instance = null
        // [신규] 가이드 시스템 정리
        guideOrchestrator?.destroy()
        uiChangeDetector?.destroy()
        serviceJob.cancel()
        testReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "testReceiver 해제 실패: ${e.message}")
            }
            testReceiver = null
        }
        errorHandlingManager.destroy() // 예외 처리 매니저 리소스 정리
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        removeTargetHighlight()
        floatingMenuManager?.hide()
        LlmLoadingOverlay.hide()
        candidateOverlayManager?.clearOverlays()
        actionButtonOverlayManager?.clearHighlight()
    }

    private fun extractScreenTree(): String {
        val elements = mutableListOf<UiElement>()
        var candidateCounter = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                // 유효한 크기를 가진 노드만 수집
                if (rect.width() > 0 && rect.height() > 0) {
                    val text = node.text?.toString() ?: ""
                    val contentDescription = node.contentDescription?.toString() ?: ""
                    val id = node.viewIdResourceName ?: "no_id"
                    val className = node.className?.toString() ?: "no_class"

                    if (text.isNotBlank() || contentDescription.isNotBlank() || node.isClickable) {
                        elements.add(UiElement(
                            candidateId = "candidate_${candidateCounter++}",
                            text = text,
                            contentDescription = contentDescription,
                            id = id,
                            className = className,
                            bounds = RectDto(rect.left, rect.top, rect.right, rect.bottom),
                            clickable = node.isClickable,
                            enabled = node.isEnabled,
                            visibleToUser = node.isVisibleToUser
                        ))
                    }
                }
            }
            for (i in 0 until node.childCount) traverse(node.getChild(i))
        }

        // rootInActiveWindow 대신 모든 윈도우를 순회하여 더 정확한 좌표 정보를 수집합니다.
        val currentWindows = windows
        if (currentWindows.isNullOrEmpty()) {
            traverse(rootInActiveWindow)
        } else {
            for (window in currentWindows) {
                traverse(window.root)
            }
        }

        return Json.encodeToString(elements)
    }

    private fun getActiveAppPackageName(): String? {
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        val systemAndImePackages = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.sec.android.inputmethod",
            "com.samsung.android.honeyboard"
        )
        if (rootPkg != null && rootPkg !in systemAndImePackages) {
            return rootPkg
        }
        val activeWindows = windows ?: return rootPkg
        for (window in activeWindows) {
            val pkg = window.root?.packageName?.toString()
            if (pkg != null && pkg !in systemAndImePackages) {
                return pkg
            }
        }
        return rootPkg
    }

    private fun showTargetHighlight(bounds: RectDto, message: String, color: Int = Color.RED, keepInfinite: Boolean = false) {
        val optimizedBounds = findOptimizedBounds(bounds)
        Log.d(TAG, "showTargetHighlight: original=$bounds, optimized=$optimizedBounds, color=$color")
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        removeTargetHighlight()

        val highlight = android.view.View(this@TalkTiAccessibilityService).apply {
            val strokeWidth = (6 * resources.displayMetrics.density).toInt() // 조금 더 두껍게
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(strokeWidth, color)
                setColor(Color.TRANSPARENT)
            }
        }

        val params = WindowManager.LayoutParams(
            (optimizedBounds.right - optimizedBounds.left).coerceAtLeast(10),
            (optimizedBounds.bottom - optimizedBounds.top).coerceAtLeast(10),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or // 좌표계 일치를 위해 추가
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,   // 화면 밖으로 나가는 것 허용
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = optimizedBounds.left
            y = optimizedBounds.top

            // 디스플레이 컷아웃(노치) 영역까지 그리기 확장
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            highlightView = highlight
            windowManager.addView(highlightView, params)
            Log.d(TAG, "Highlight View 추가 성공")
        } catch (e: Exception) {
            Log.e(TAG, "Highlight View 추가 실패: ${e.message}")
        }

        if (!keepInfinite) {
            highlightJob = mainScope.launch {
                delay(3000) // 3초간 유지 후 제거
                removeTargetHighlight()
            }
        }
    }

    private fun removeTargetHighlight() {
        highlightJob?.cancel()
        highlightView?.let {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeView(it)
            highlightView = null
        }
    }

    fun findOptimizedBounds(bounds: RectDto): RectDto {
        val activeWindows = windows ?: emptyList()
        var foundNode: AccessibilityNodeInfo? = null
        
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || foundNode != null) return
            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (Math.abs(rect.left - bounds.left) < 50 && Math.abs(rect.top - bounds.top) < 50) {
                    foundNode = node
                    return
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }
        
        if (activeWindows.isNotEmpty()) {
            for (window in activeWindows) {
                if (foundNode != null) break
                traverse(window.root)
            }
        }
        if (foundNode == null) {
            traverse(rootInActiveWindow)
        }
        
        val node = foundNode ?: return bounds
        var bestNode: AccessibilityNodeInfo = node
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        
        var temp: AccessibilityNodeInfo? = node
        var count = 0
        while (temp != null && count < 3) {
            if (temp.isClickable) {
                val tempRect = Rect()
                temp.getBoundsInScreen(tempRect)
                
                val bestRect = Rect()
                bestNode.getBoundsInScreen(bestRect)
                
                // 화면의 가로 폭 전체를 거의 다 덮거나 세로로 너무 거대하지 않은 적절한 크기의 클릭 가능한 컨테이너만 선택
                if (tempRect.width() > bestRect.width() || tempRect.height() > bestRect.height()) {
                    if (tempRect.width() < screenWidth * 0.95 && tempRect.height() < screenHeight * 0.4) {
                        bestNode = temp
                    }
                }
            }
            temp = temp.parent
            count++
        }
        
        val finalRect = Rect()
        bestNode.getBoundsInScreen(finalRect)
        return RectDto(finalRect.left, finalRect.top, finalRect.right, finalRect.bottom)
    }

    private fun performImmediateActionSetText(bounds: RectDto, text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        var success = false

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || success) return
            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                // 50픽셀 오차범위 내 좌표 보정 매칭
                if (Math.abs(rect.left - bounds.left) < 50 && Math.abs(rect.top - bounds.top) < 50) {
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    if (success) return
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(rootNode)
        return success
    }

    private fun performImmediateActionClick(bounds: RectDto): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        var success = false

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || success) return
            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                // 50픽셀 오차범위 내 좌표 보정 매칭
                if (Math.abs(rect.left - bounds.left) < 50 && Math.abs(rect.top - bounds.top) < 50) {
                    var current = node
                    while (current != null) {
                        if (current.isClickable) {
                            success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (success) break
                        }
                        current = current.parent
                    }
                    return
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(rootNode)
        return success
    }

    private fun autofillEditTextInActiveWindow(text: String): Boolean {
        var success = false
        var actionPerformed = false

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || success || actionPerformed) return
            if (node.isVisibleToUser) {
                val className = node.className?.toString() ?: ""
                val isEdit = className.contains("EditText") || className.contains("AutoCompleteTextView") || node.isEditable
                if (isEdit) {
                    val currentText = node.text?.toString() ?: ""
                    val cleanCurrent = currentText.replace(" ", "").lowercase()
                    val cleanTarget = text.replace(" ", "").lowercase()

                    if (cleanCurrent == cleanTarget) {
                        println("✅ [텍스트 검증 완료] 입력창에 이미 '${text}'이 입력되어 있습니다.")
                        success = true
                        return
                    } else {
                        // 중복 입력 방지: 동일한 텍스트에 대한 주입 요청은 최소 1.5초 간격으로 제한합니다. (반영 대기 시간 확보)
                        val now = System.currentTimeMillis()
                        if (text == lastInputText && now - lastInputTime < 1500) {
                            println("⏳ [매크로 대기] 이미 주입 요청을 보냈습니다. 반영을 대기합니다.")
                            actionPerformed = true
                            return
                        }

                        println("🎯 [매크로 타겟 발견] 클래스명: $className, 현재텍스트: '$currentText', 목표텍스트: '$text' 주입 시도")
                        
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        
                        var setResult = false
                        try {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("text", text)
                            clipboard.setPrimaryClip(clip)
                            setResult = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        } catch (e: Exception) {
                            Log.e(TAG, "클립보드 입력 실패: ${e.message}")
                        }
                        
                        if (!setResult) {
                            val arguments = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                            }
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        }

                        lastInputText = text
                        lastInputTime = now
                        actionPerformed = true // 단일 함수 호출 내에서 주입은 한 번만 수행하도록 설정
                    }
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        // 1. 모든 가용한 윈도우(인터랙티브 레이어)를 완전 순회
        val activeWindows = windows
        for (window in activeWindows) {
            if (success || actionPerformed) break
            val root = window.root
            if (root != null) {
                traverse(root)
            }
        }

        // 2. 만약 윈도우 순회에서 실패했다면 rootInActiveWindow로 최종 폴백
        if (!success && !actionPerformed) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                traverse(rootNode)
            }
        }

        return success
    }

    private fun cleanSearchQuery(command: String): String {
        var result = command

        // 정규표현식 패턴 목록 정의
        val patterns = listOf(
            "지금\\s*내\\s*위치에서",
            "내\\s*위치에서",
            "현재\\s*위치에서",
            "으로\\s*가\\s*줘",
            "가는\\s*길\\s*찾아\\s*달라니까",
            "가는\\s*길\\s*찾아\\s*줘",
            "가는\\s*길\\s*알려\\s*줘",
            "가는\\s*경로\\s*알려\\s*줘",
            "가는\\s*경로",
            "어떻게\\s*가",
            "가고\\s*싶어",
            "찾아\\s*줘",
            "알려\\s*줘",
            "길찾기",
            "검색해\\s*줘",
            "가\\s*줘",
            "갈래",
            "가자",
            "으로",
            "택시\\s*불러\\s*줘",
            // 교통수단
            "버스\\s*타고",
            "지하철\\s*타고",
            "대중교통\\s*타고",
            "택시\\s*타고",
            "버스로",
            "지하철로",
            "도보로",
            "자전거로"
        )

        // 각 패턴에 대해 대소문자 무시하고 빈 문자열로 치환
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            result = result.replace(regex, "")
        }

        // 특수기호 제거 및 좌우 공백 정제
        result = result
            .replace(".", "")
            .replace(",", "")
            .trim()

        return result
    }

    private fun isKakaoTalkStep(step: GuideStep): Boolean {
        return step == GuideStep.KAKAOTALK_OPENED ||
                step == GuideStep.KAKAOTALK_SEARCH_CLICKED ||
                step == GuideStep.KAKAOTALK_RESULT_SELECTION ||
                step == GuideStep.KAKAOTALK_CHATROOM_OPENED ||
                step == GuideStep.KAKAOTALK_PLUS_MENU_OPENED ||
                step == GuideStep.KAKAOTALK_MEDIA_CHOOSER_OPENED ||
                step == GuideStep.KAKAOTALK_MEDIA_SEND_READY
    }

    private fun findKakaoTalkSearchButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            if (desc.contains("검색") || desc.contains("돋보기") || desc.contains("search") || desc.contains("Search") ||
                text.contains("검색") || text.contains("돋보기") || text.contains("search") || text.contains("Search") ||
                viewId.endsWith("search_button") || viewId.endsWith("menu_search") || viewId.endsWith("search_icon")
            ) {
                // 메시지 통합 검색 카드 등 제외
                if (!text.contains("메시지") && !text.contains("메세지") && !text.contains("검색해보세요") &&
                    !desc.contains("메시지") && !desc.contains("메세지") && !desc.contains("검색해보세요")
                ) {
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        if (current.isClickable) {
                            val rect = Rect()
                            current.getBoundsInScreen(rect)
                            // 실제 돋보기 아이콘 버튼은 가로/세로 300px 미만의 크기입니다.
                            if (rect.width() > 0 && rect.width() < 300 && rect.height() > 0 && rect.height() < 300) {
                                return current
                            }
                        }
                        current = current.parent
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val res = findKakaoTalkSearchButton(child)
            if (res != null) return res
        }
        return null
    }

    private fun findKakaoTalkSearchResult(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val className = node.className?.toString() ?: ""
            if (!className.contains("EditText")) {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""

                val cleanText = text.replace(" ", "").lowercase()
                val cleanDesc = desc.replace(" ", "").lowercase()
                val cleanQuery = query.replace(" ", "").lowercase()

                if (cleanQuery.isNotEmpty() && (cleanText.contains(cleanQuery) || cleanDesc.contains(cleanQuery))) {
                    // 메시지 검색 관련 노드는 매칭에서 명시적으로 제외 (채팅방/친구 항목만 선택하도록 필터링)
                    if (!cleanText.contains("메시지") && !cleanText.contains("메세지") && 
                        !cleanText.contains("검색해보세요") && !cleanText.contains("주고받은") &&
                        !cleanDesc.contains("메시지") && !cleanDesc.contains("메세지") && 
                        !cleanDesc.contains("검색해보세요") && !cleanDesc.contains("주고받은")
                    ) {
                        var current: AccessibilityNodeInfo? = node
                        while (current != null) {
                            if (current.isClickable) {
                                if (!hasExclusionInSiblingsOrSelf(current)) {
                                    return current
                                } else {
                                    break
                                }
                            }
                            current = current.parent
                        }
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val res = findKakaoTalkSearchResult(child, query)
            if (res != null) return res
        }
        return null
    }

    private fun hasSearchEditText(node: AccessibilityNodeInfo?, query: String): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val className = node.className?.toString() ?: ""
            if (className.contains("EditText") || className.contains("AutoCompleteTextView") || node.isEditable) {
                val text = node.text?.toString() ?: ""
                val cleanText = text.replace(" ", "").lowercase()
                val cleanQuery = query.replace(" ", "").lowercase()
                if (cleanText == cleanQuery) {
                    return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (hasSearchEditText(node.getChild(i), query)) return true
        }
        return false
    }

    private fun hasEditText(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val className = node.className?.toString() ?: ""
            if (className.contains("EditText") || className.contains("AutoCompleteTextView") || node.isEditable) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            if (hasEditText(node.getChild(i))) return true
        }
        return false
    }

    private fun hasTopSearchEditText(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val className = node.className?.toString() ?: ""
            if (className.contains("EditText") || className.contains("AutoCompleteTextView") || node.isEditable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val screenHeight = resources.displayMetrics.heightPixels
                // 상단 30% 영역 안에 있는 입력창만 검색창으로 인식
                if (rect.top < screenHeight / 3) {
                    return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (hasTopSearchEditText(node.getChild(i))) return true
        }
        return false
    }

    private fun hasMatchingChild(node: AccessibilityNodeInfo?, query: String): Boolean {
        if (node == null) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString() ?: ""
            val desc = child.contentDescription?.toString() ?: ""
            val cleanText = text.replace(" ", "").lowercase()
            val cleanDesc = desc.replace(" ", "").lowercase()
            if (cleanText.contains(query) || cleanDesc.contains(query)) {
                return true
            }
            if (hasMatchingChild(child, query)) return true
        }
        return false
    }

    private fun findKakaoTalkChatMenuButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        
        // 1. 먼저 화면 하단에 있는 메시지 입력창(EditText)을 찾습니다.
        val editTextNode = findMessageEditText(rootNode) ?: return findKakaoTalkChatMenuButtonFallback(rootNode)
        
        val editRect = Rect()
        editTextNode.getBoundsInScreen(editRect)
        
        // 2. 입력창의 왼쪽 영역에서 입력창과 세로 정렬이 일치하는 가장 인접한 클릭 가능한 노드를 찾습니다.
        var bestMatch: AccessibilityNodeInfo? = null
        var bestRight = -1
        
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isVisibleToUser && n.isClickable) {
                val rect = Rect()
                n.getBoundsInScreen(rect)
                
                // 입력창의 왼쪽에 있고, 세로 범위가 입력창과 겹치는 클릭 가능한 뷰
                if (rect.right <= editRect.left && rect.right > 0 &&
                    rect.bottom > editRect.top && rect.top < editRect.bottom
                ) {
                    val className = n.className?.toString() ?: ""
                    // EditText 자체는 제외
                    if (!className.contains("EditText")) {
                        // 가장 오른쪽에 붙어 있는 (입력창에 가장 인접한) 뷰를 선택
                        if (rect.right > bestRight) {
                            bestRight = rect.right
                            bestMatch = n
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) {
                traverse(n.getChild(i))
            }
        }
        
        traverse(rootNode)
        return bestMatch ?: findKakaoTalkChatMenuButtonFallback(rootNode)
    }

    private fun findMessageEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val className = node.className?.toString() ?: ""
            if (className.contains("EditText") || className.contains("AutoCompleteTextView") || node.isEditable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val screenHeight = resources.displayMetrics.heightPixels
                // 화면 하단 영역에 있는 입력창이어야 함
                if (rect.top > screenHeight / 2) {
                    return node
                }
            }
        }
        for (i in 0 until node.childCount) {
            val res = findMessageEditText(node.getChild(i))
            if (res != null) return res
        }
        return null
    }

    private fun findKakaoTalkChatMenuButtonFallback(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            if (desc.contains("플러스") || desc.contains("더보기") || desc.contains("메뉴") || desc.contains("공유") ||
                desc == "+" || text == "+" ||
                viewId.endsWith("chat_menu_button") || viewId.endsWith("btn_chat_menu")
            ) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val screenHeight = resources.displayMetrics.heightPixels
                if (rect.top > screenHeight / 2) {
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        if (current.isClickable) return current
                        current = current.parent
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val res = findKakaoTalkChatMenuButtonFallback(child)
            if (res != null) return res
        }
        return null
    }

    private fun hasPlusMenuOpen(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (text == "앨범" || text == "카메라" || desc == "앨범" || desc == "카메라") {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            if (hasPlusMenuOpen(node.getChild(i))) return true
        }
        return false
    }

    private fun findKakaoTalkMediaButton(node: AccessibilityNodeInfo?, mediaType: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            if (mediaType == "동영상" && (text.contains("동영상") || desc.contains("동영상") || text.contains("비디오") || desc.contains("비디오"))) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) return current
                    current = current.parent
                }
            }
            if (mediaType == "사진" && (text.contains("사진") || desc.contains("사진") || text.contains("이미지") || desc.contains("이미지"))) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) return current
                    current = current.parent
                }
            }

            if (text == "앨범" || desc == "앨범" || viewId.endsWith("album_btn") || text.contains("album") || desc.contains("album")) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) return current
                    current = current.parent
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val res = findKakaoTalkMediaButton(child, mediaType)
            if (res != null) return res
        }
        return null
    }

    private fun hasMediaChooserOpen(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            if (text.contains("묶어보내기") || desc.contains("묶어보내기") ||
                text == "전체" || viewId.endsWith("media_grid") || viewId.endsWith("gallery_grid")
            ) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            if (hasMediaChooserOpen(node.getChild(i))) return true
        }
        return false
    }

    private fun findKakaoTalkMediaGrid(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val className = node.className?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            if (className.contains("GridView") || className.contains("RecyclerView") ||
                viewId.endsWith("grid") || viewId.endsWith("gallery") || viewId.endsWith("list") || viewId.contains("media")
            ) {
                // 채팅방 메시지 목록(chat_list 등)은 제외
                if (!viewId.contains("chat")) {
                    // 미디어 아이템(사진/동영상 등)을 포함하고 있는지 검증
                    val items = mutableListOf<AccessibilityNodeInfo>()
                    findKakaoTalkMediaGridItems(node, items)
                    if (items.isNotEmpty()) {
                        return node
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val res = findKakaoTalkMediaGrid(child)
            if (res != null) return res
        }
        return null
    }

    private fun findKakaoTalkMediaSendButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            if (text.contains("전송") || desc.contains("전송") || text.contains("보내기") || desc.contains("보내기") ||
                viewId.endsWith("send_btn") || viewId.endsWith("ok_btn") || viewId.endsWith("confirm")
            ) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) return current
                    current = current.parent
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val res = findKakaoTalkMediaSendButton(child)
            if (res != null) return res
        }
        return null
    }

    private fun findKakaoTalkMediaGridItems(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        if (node == null) return list
        if (node.isVisibleToUser) {
            val className = node.className?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            if (node.isClickable && (
                className.contains("ImageView") ||
                viewId.endsWith("thumbnail") || viewId.endsWith("image") || viewId.endsWith("photo") ||
                desc.contains("사진") || desc.contains("동영상") || desc.contains("이미지") || desc.contains("영상") || desc.contains("선택")
            )) {
                val cleanText = node.text?.toString() ?: ""
                if (!cleanText.contains("묶어보내기") && !desc.contains("묶어보내기") && !cleanText.contains("전송")) {
                    list.add(node)
                }
            }
        }
        for (i in 0 until node.childCount) {
            findKakaoTalkMediaGridItems(node.getChild(i), list)
        }
        return list
    }


    private fun hasExclusionInSiblingsOrSelf(node: AccessibilityNodeInfo): Boolean {
        if (containsAnyExclusionKeyword(node)) return true
        val parent = node.parent ?: return false
        val parentClass = parent.className?.toString() ?: ""
        if (!parentClass.contains("RecyclerView") && !parentClass.contains("ListView") && !parentClass.contains("GridView")) {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i)
                if (sibling != null && sibling != node) {
                    if (containsAnyExclusionKeyword(sibling)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun containsAnyExclusionKeyword(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val cleanText = text.replace(" ", "").lowercase()
        val cleanDesc = desc.replace(" ", "").lowercase()

        val exclusions = listOf("메시지", "메세지", "검색해보세요", "주고받은", "친구와")
        for (ex in exclusions) {
            if (cleanText.contains(ex) || cleanDesc.contains(ex)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && containsAnyExclusionKeyword(child)) {
                return true
            }
        }
        return false
    }
}
