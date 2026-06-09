package kr.ac.kopo.talkti

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import androidx.annotation.RequiresApi
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
import kr.ac.kopo.talkti.app.guide.AgentSessionManager

class TalkTiAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: TalkTiAccessibilityService? = null

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

    // ── 예외 처리 매니저 (팝업/이탈/무한대기 방지) ──
    private val errorHandlingManager = ErrorHandlingManager()

    // ── 연속 가이드 세션 매니저 ──
    private val agentSessionManager = AgentSessionManager()

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private var highlightView: android.view.View? = null
    private var highlightJob: Job? = null
    private var pendingCommand: String? = null
    private var autoDestinationQuery: String? = null
    private var isAutoDestinationFlowActive = false
    private enum class GuideStep {
        NONE,
        PLACE_SELECTION,
        DESTINATION_BUTTON,
        ROUTE_SELECTION,
        START_GUIDANCE
    }

    private var currentGuideStep = GuideStep.NONE
    private var llmJob: Job? = null

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

        // 예외 처리 매니저 초기화
        errorHandlingManager.initialize(this, textToSpeech)
        errorHandlingManager.onTerminateListener = {
            // 가이드 종료 시 서비스 상태 초기화
            pendingCommand = null
            removeTargetHighlight()
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
                        agentSessionManager.startSession(command)
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
                                agentSessionManager.startSession(userCommand)
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

                textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS 시작: $utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS 종료: $utteranceId")
                        if (utteranceId == "talkti_selection_ask") {
                            // TTS가 끝난 후 음성 인식을 재개합니다.
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(500) // 안정성을 위해 약간의 지연
                                Log.d(TAG, "음성 인식 재개 시도 (utteranceId=$utteranceId)")
                                startAppGuide()
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS 에러: $utteranceId")
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

        // ── 연속 가이드 티키타카 로직 ──
        // 화면에 변화가 생기거나 클릭이 일어났을 때, 세션이 진행 중이면 자동 캡처 후 서버 전송
        if (agentSessionManager.isActive) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                
                if (agentSessionManager.canCapture(3000)) {
                    val currentGoal = agentSessionManager.currentGoal
                    if (currentGoal != null) {
                        Log.d(TAG, "티키타카 루프 동작: 화면 전환/클릭 감지 -> 캡처 전송 (목표: \$currentGoal)")
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(1000)
                            if (!LlmLoadingOverlay.isShowing) {
                                captureScreenForLLM(currentGoal)
                            }
                        }
                    }
                }
            }
        }

        val command = pendingCommand
        if (command != null && (
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    )
        ) {
            println("📡 [이벤트 감지] 타입: ${event.eventType}, 현재 대기 목적지: $command")
            CoroutineScope(Dispatchers.Main).launch {
                delay(600) // 새로운 화면이 완전히 그려질 시간 대기
                val currentCmd = pendingCommand
                if (currentCmd != null) {
                    val success = autofillEditTextInActiveWindow(currentCmd)

                    if (success) {

                        pendingCommand = null // 타이핑 성공 시 대기 큐에서 제거

                        speakTts("${currentCmd}을 입력했습니다.")

                        if (isAutoDestinationFlowActive) {

                            CoroutineScope(Dispatchers.Main).launch {

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
    }

    override fun onInterrupt() {}

    private fun openAppByName(appNameOrPackage: String, searchQuery: String? = null): String? {
        val pm = packageManager
        Log.d(TAG, "openAppByName 호출: $appNameOrPackage, searchQuery: $searchQuery")

        // 1. 패키지명으로 직접 실행 시도 (LLM이 패키지명을 보낸 경우)
        try {
            if (isMapPackage(appNameOrPackage) && !searchQuery.isNullOrBlank()) {
                val launched = launchMapWithDeepLink(appNameOrPackage, searchQuery)
                if (launched) return appNameOrPackage
            }
            val intent = pm.getLaunchIntentForPackage(appNameOrPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            "네비" to listOf("net.daum.android.map", "com.nhn.android.nmap"),
            "내비게이션" to listOf("net.daum.android.map", "com.nhn.android.nmap"),
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
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    speakTts("${appLabel}을 실행합니다.")
                    return appInfo.packageName
                }
            }
        }
        return null
    }

    private fun isMapPackage(packageName: String): Boolean {
        return packageName == "net.daum.android.map" || packageName == "com.nhn.android.nmap"
    }

    private fun launchMapWithDeepLink(packageName: String, query: String): Boolean {
        return try {
            val uri = when (packageName) {
                "net.daum.android.map" -> android.net.Uri.parse("kakaomap://search?q=" + android.net.Uri.encode(query))
                "com.nhn.android.nmap" -> android.net.Uri.parse("nmap://search?query=" + android.net.Uri.encode(query))
                else -> null
            }

            if (uri == null) {
                false
            } else {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private fun processLocalCommand(command: String): Boolean {
        val cleanCmd = command.replace(" ", "").lowercase()

        val routeKeywords = listOf("가자", "가는길찾아줘", "길찾아줘", "찾아달라니까", "찾아줘", "어떻게가", "가고싶어", "알려줘", "길찾기")
        val isRouteCommand = routeKeywords.any { cleanCmd.endsWith(it) || cleanCmd.contains(it) }

        val isAppOpenCmd = cleanCmd.contains("열어") || cleanCmd.contains("켜") || cleanCmd.contains("실행") || cleanCmd.contains("보여줘")

        // 1. 하이브리드 모드: 정확한 NLP 파싱 및 딥링크 앱 실행 후 LLM 연속 루프에 제어권 이양
        if (isRouteCommand && !isAppOpenCmd) {
            val destination = cleanSearchQuery(command)
            if (destination.isNotBlank()) {
                val launchedPkg = openAppByName("지도", destination)
                if (launchedPkg != null) {
                    errorHandlingManager.onGuideStarted(launchedPkg, command)
                    // 노란색 오버레이 하드코딩 매크로 대신, 깨끗한 LLM 연속 루프로 전환
                    agentSessionManager.startSession(command)
                } else {
                    speakTts("${destination}을 검색할 수 있는 지도 앱이 없습니다.")
                }
                return true
            }
        }

        // 2. Selection 흐름 실제 시작 연결 (기존 흐름 유지, LLM 전송 회피)
        if (cleanCmd.contains("선택시작") || cleanCmd.contains("후보선택") || cleanCmd.contains("목록읽어줘")) {
            startSelectionFlow(isContinuation = false)
            return true
        }

        // 3. 단순 앱 실행 명령
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
        val screenSessionId = "screen_${System.currentTimeMillis()}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val hardwareBuffer = screenshotResult.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                    val realUiTree = extractScreenTree()

                    if (bitmap != null) {
                        val base64Image = bitmapToBase64(bitmap)
                        sendDataToServer(userCommand, base64Image, realUiTree, screenSessionId)
                    }
                    hardwareBuffer.close()
                }

                override fun onFailure(errorCode: Int) {}
            })
        }
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

    private fun sendDataToServer(command: String, base64Image: String, uiTree: String, screenSessionId: String) {
        val sharedPref = getSharedPreferences("talkti_prefs", Context.MODE_PRIVATE)
        var baseUrl = sharedPref.getString("server_url", "http://guide.aikopo.net") ?: "http://guide.aikopo.net"

        baseUrl = baseUrl.trim().removeSuffix("/")
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://$baseUrl"
        }

        val serverUrl = "$baseUrl/analyze"
        val installedApps = getInstalledApps()

        Log.d(TAG, "서버 전송 시작: $serverUrl, 명령어: $command, 앱 개수: ${installedApps.size}")

        llmJob = CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                LlmLoadingOverlay.show(this@TalkTiAccessibilityService)
                floatingMenuManager?.bringToFront()
                floatingMenuManager?.updateLoadingStatus(true)
                speakTts("똑띠가 생각 중이에요. 잠시만 기다려주세요.")
            }
            try {
                val response: GuideActionResponse = client.post(serverUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(ScreenStateRequest(
                        userVoiceCommand = command,
                        uiTreeJson = uiTree,
                        screenshotBase64 = base64Image,
                        screenSessionId = screenSessionId,
                        installedApps = installedApps
                    ))
                }.body()

                Log.d(TAG, "서버 응답 수신 성공: ${response.ttsMessage}")
                withContext(Dispatchers.Main) {
                    floatingMenuManager?.updateLoadingStatus(false)
                    
                    if (response.actionType == "FINISH") {
                        Log.d(TAG, "가이드 완전 종료 신호 (FINISH) 수신 -> 세션 종료")
                        agentSessionManager.endSession()
                    }
                    
                    // [수정] ASK_USER일 경우 음성 인식 재개를 위해 ID 부여
                    val utteranceId = if (response.actionType == "ASK_USER") "talkti_selection_ask" else "talkti_tts"
                    speakTts(response.ttsMessage, utteranceId)

                    // [수정] 질문(ASK_USER)일 때는 노란색, 그 외 액션은 빨간색 가이드
                    val highlightColor = if (response.actionType == "ASK_USER") Color.YELLOW else Color.RED

                    response.targetBounds?.let { bounds ->
                        showTargetHighlight(bounds, response.ttsMessage, highlightColor)
                    }

                    if (response.actionType == "OPEN_APP") {
                        val targetId = response.targetCandidateId
                        Log.d(TAG, "OPEN_APP 시도: targetId=$targetId")
                        if (targetId != null) {
                            val query = if (!response.actionArguments.isNullOrBlank()) {
                                response.actionArguments
                            } else {
                                cleanSearchQuery(command)
                            }
                            val launchedPkg = openAppByName(targetId, query)
                            if (launchedPkg != null) {
                                errorHandlingManager.onGuideStarted(launchedPkg, command)
                            }
                        }
                    } else {
                        // OPEN_APP이 아닐 때는 가이드 시작 전 현재 패키지를 타겟으로 설정
                        val currentPkg = rootInActiveWindow?.packageName?.toString() ?: ""
                        if (currentPkg.isNotBlank()) {
                            errorHandlingManager.onGuideStarted(currentPkg, command)
                        }
                    }
                    if (response.actionType == "OPEN_APP") {
                        // OPEN_APP 처리는 이미 위에서 완료
                    } else if (response.actionType == "ACTION_SET_TEXT" && !response.actionArguments.isNullOrBlank()) {
                        println("자동 텍스트 입력 예약 실행: ${response.actionArguments}")
                        response.targetBounds?.let { bounds ->
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(1000)
                                val success = performImmediateActionSetText(bounds, response.actionArguments!!)
                                if (!success) {
                                    // 텍스트 입력에 실패하면 클릭만 수행하고, 다음 화면에서 LLM이 다시 판단하도록 맡김
                                    performImmediateActionClick(bounds)
                                }
                            }
                        }
                    } else if (response.actionType == "CLICK") {
                        println("자동 클릭 예약 실행")
                        response.targetBounds?.let { bounds ->
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(1000)
                                performImmediateActionClick(bounds)
                                // LLM 모드에서는 pendingCommand를 사용한 강제 주입을 하지 않고 LLM 루프에 맡김
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "서버 통신 실패 (${e.javaClass.simpleName}): ${e.message}")
                withContext(Dispatchers.Main) {
                    floatingMenuManager?.updateLoadingStatus(false)
                    Toast.makeText(this@TalkTiAccessibilityService, "연결 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    floatingMenuManager?.updateLoadingStatus(false)
                    LlmLoadingOverlay.hide()
                }
            }
        }
    }

    private fun speakTts(message: String, utteranceId: String = "talkti_tts") {
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
                    CoroutineScope(Dispatchers.Main).launch {
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
        errorHandlingManager.destroy() // 예외 처리 매니저 리소스 정리
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        removeTargetHighlight()
        floatingMenuManager?.hide()
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

    private fun showTargetHighlight(bounds: RectDto, message: String, color: Int = Color.RED) {
        Log.d(TAG, "showTargetHighlight: bounds=$bounds, color=$color")
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 메인 스레드에서 UI 작업을 보장
        CoroutineScope(Dispatchers.Main).launch {
            removeTargetHighlight()

            val highlight = android.view.View(this@TalkTiAccessibilityService).apply {
                val strokeWidth = (6 * resources.displayMetrics.density).toInt() // 조금 더 두껍게
                background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(strokeWidth, color)
                    setColor(Color.TRANSPARENT)
                }
            }

            val params = WindowManager.LayoutParams(
                (bounds.right - bounds.left).coerceAtLeast(10),
                (bounds.bottom - bounds.top).coerceAtLeast(10),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or // 좌표계 일치를 위해 추가
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,   // 화면 밖으로 나가는 것 허용
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bounds.left
                y = bounds.top

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

            highlightJob = launch {
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

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || success) return
            if (node.isVisibleToUser) {
                val className = node.className?.toString() ?: ""
                val isEdit = className.contains("EditText") || className.contains("AutoCompleteTextView") || node.isEditable
                if (isEdit) {
                    println("🎯 [매크로 타겟 발견] 클래스명: $className, 텍스트('$text') 주입 시도")
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    if (success) {
                        println("✅ [텍스트 입력 성공] 타이핑 완료!")
                        return
                    }
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        // 1. 모든 가용한 윈도우(인터랙티브 레이어)를 완전 순회
        val activeWindows = windows
        println("🔍 [매크로 스캔] 현재 상호작용 가능한 윈도우 개수: ${activeWindows.size}개")
        for (window in activeWindows) {
            if (success) break
            val root = window.root
            if (root != null) {
                traverse(root)
            }
        }

        // 2. 만약 윈도우 순회에서 실패했다면 rootInActiveWindow로 최종 폴백
        if (!success) {
            println("⚠️ [매크로 스캔 폴백] 활성 윈도우에서 탐색 실패, rootInActiveWindow 개시")
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                traverse(rootNode)
            }
        }

        return success
    }

    private fun cleanSearchQuery(command: String): String {
        var clean = command.replace(".", "").replace(",", "")
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
            "찾아\\s*달라니까",
            "찾아\\s*줘",
            "알려\\s*줘",
            "버스\\s*타고",
            "지하철\\s*타고",
            "대중교통\\s*타고",
            "택시\\s*타고",
            "버스로",
            "지하철로",
            "도보로",
            "자전거로",
            "택시\\s*불러\\s*줘",
            "길찾기",
            "검색해\\s*줘",
            "가\\s*줘",
            "갈래",
            "가자",
            "으로"
        )
        for (pattern in patterns) {
            clean = clean.replace(Regex(pattern), "")
        }
        return clean.trim()
    }

}