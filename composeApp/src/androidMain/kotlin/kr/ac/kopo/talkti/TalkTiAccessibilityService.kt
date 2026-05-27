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
import kr.ac.kopo.talkti.models.SelectionSession
import kr.ac.kopo.talkti.models.UserResponseParser
import kr.ac.kopo.talkti.models.UserResponse
import kr.ac.kopo.talkti.models.SelectionFlow
import io.ktor.serialization.kotlinx.json.*


import kr.ac.kopo.talkti.app.overlay.FloatingMenuManager
import kr.ac.kopo.talkti.app.errorhandling.ErrorHandlingManager

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

    // ── 예외 처리 매니저 (팝업/이탈/무한대기 방지) ──
    private val errorHandlingManager = ErrorHandlingManager()

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private var highlightView: android.view.View? = null
    private var highlightJob: Job? = null
    private var pendingCommand: String? = null

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
        Log.d(TAG, "접근성 서비스 연결됨 - 플로팅 메뉴 생성 시작")
        initSpeechRecognizer()
        initTextToSpeech()
        setupFloatingMenu()

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
            }
        )
        floatingMenuManager?.show()
    }

    private fun startAppGuide() {
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
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // ── 예외 처리 인터셉터: 팝업/이탈/타이머 검사를 기존 로직보다 먼저 수행 ──
        if (errorHandlingManager.interceptEvent(event)) return

        val command = pendingCommand
        if (command != null && (
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )) {
            println("📡 [이벤트 감지] 타입: ${event.eventType}, 현재 대기 목적지: $command")
            CoroutineScope(Dispatchers.Main).launch {
                delay(600) // 새로운 화면이 완전히 그려질 시간 대기
                val currentCmd = pendingCommand
                if (currentCmd != null) {
                    val success = autofillEditTextInActiveWindow(currentCmd)
                    if (success) {
                        pendingCommand = null // 타이핑 성공 시 대기 큐에서 제거
                        speakTts("${currentCmd}을 입력했습니다.")
                    } else {
                        println("❌ [매크로 대기] 입력창 검색 실패. 다음 변경 이벤트를 기다립니다.")
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun openAppByName(appNameOrPackage: String): Boolean {
        val pm = packageManager
        Log.d(TAG, "openAppByName 호출: $appNameOrPackage")

        // 1. 패키지명으로 직접 실행 시도 (LLM이 패키지명을 보낸 경우)
        try {
            val intent = pm.getLaunchIntentForPackage(appNameOrPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                val appLabel = pm.getApplicationLabel(pm.getApplicationInfo(appNameOrPackage, 0))
                speakTts("${appLabel} 앱을 실행합니다.")
                return true
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
                    val intent = pm.getLaunchIntentForPackage(installedPackage)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        speakTts("${alias}를 실행합니다.")
                        return true
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
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    speakTts("${appLabel}을 실행합니다.")
                    return true
                }
            }
        }
        return false
    }

    private fun processLocalCommand(command: String): Boolean {
        val cleanCmd = command.replace(" ", "").lowercase()
        
        // [추가] Selection 흐름 실제 시작 연결 (기존 흐름 유지, LLM 전송 회피)
        if (cleanCmd.contains("선택시작") || cleanCmd.contains("후보선택") || cleanCmd.contains("목록읽어줘")) {
            startSelectionFlow(isContinuation = false)
            return true
        }
        val isAppOpenCmd =
            cleanCmd.contains("열어") || cleanCmd.contains("켜") || cleanCmd.contains("실행") ||
            cleanCmd.contains("보여줘")

        if (!isAppOpenCmd) return false
        return openAppByName(cleanCmd)
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

        CoroutineScope(Dispatchers.IO).launch {
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
                    speakTts(response.ttsMessage)

                    // 가이드 시작 등록 (예외 처리 매니저에 현재 대상 앱/목표 전달)
                    val currentPkg = rootInActiveWindow?.packageName?.toString() ?: ""
                    if (currentPkg.isNotBlank()) {
                        errorHandlingManager.onGuideStarted(currentPkg, command)
                    }
                    if (response.actionType == "OPEN_APP") {
                        val targetId = response.targetCandidateId
                        Log.d(TAG, "OPEN_APP 시도: targetId=$targetId")
                        if (targetId != null) {
                            openAppByName(targetId)
                        }
                    } else if (response.actionType == "ACTION_SET_TEXT" && !response.actionArguments.isNullOrBlank()) {
                        println("즉시 자동 텍스트 입력 실행: ${response.actionArguments}")
                        response.targetBounds?.let { bounds ->
                            val success = performImmediateActionSetText(bounds, response.actionArguments!!)
                            if (!success) {
                                println("즉시 입력 실패! 가짜 입력창으로 판단되어 자동 클릭 및 매크로 대기열을 가동합니다.")
                                pendingCommand = response.actionArguments!!
                                val clickSuccess = performImmediateActionClick(bounds)
                                if (!clickSuccess) {
                                    println("클릭 우회마저 실패하여 노란색 가이드로 우회합니다.")
                                    showTargetHighlight(bounds, response.ttsMessage)
                                }
                            }
                        }
                    } else if (response.actionType == "CLICK") {
                        println("즉시 자동 클릭 실행")
                        val targetText = if (!response.actionArguments.isNullOrBlank()) {
                            response.actionArguments!!
                        } else {
                            cleanSearchQuery(command)
                        }
                        pendingCommand = targetText
                        println("화면 전환 대기 목적지 설정: $targetText")

                        response.targetBounds?.let { bounds ->
                            val success = performImmediateActionClick(bounds)
                            if (!success) {
                                println("즉시 클릭 실패, 노란색 가이드로 우회합니다.")
                                showTargetHighlight(bounds, response.ttsMessage)
                            }
                        }
                    } else if (isValidGuideResponse(response, screenSessionId)) {
                        println("클릭 명령이 아닌 ttsMessage 반환")
                        response.targetBounds?.let { showTargetHighlight(it, response.ttsMessage) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "서버 통신 실패 (${e.javaClass.simpleName}): ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TalkTiAccessibilityService, "연결 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun speakTts(message: String) {
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "talkti_tts")
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
                val prompt = promptBuilder.buildCandidateQuestion(currentCandidate)
                speakTts(prompt)
            }
            return
        }

        selectionManager.handleResponse(response)

        when (val flow = selectionManager.currentFlow) {
            is SelectionFlow.Resolved -> {
                val candidate = flow.selected
                val ttsMessage = "${candidate.text} 선택이 완료되었습니다."
                speakTts(ttsMessage)
                showTargetHighlight(candidate.bounds, ttsMessage)
            }
            is SelectionFlow.AwaitingVoice -> {
                val currentCandidate = selectionManager.getCurrentCandidate()
                if (currentCandidate != null) {
                    val prompt = promptBuilder.buildCandidateQuestion(currentCandidate)
                    speakTts(prompt)
                }
            }
            is SelectionFlow.Cancelled -> {
                speakTts("선택이 취소되었습니다.")
            }
            is SelectionFlow.CandidatesExhausted -> {
                speakTts("화면의 모든 항목을 확인했습니다. 다음 화면을 탐색합니다.")
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
            val session = SelectionSession(
                sessionId = "session_${System.currentTimeMillis()}",
                question = "원하시는 항목을 말씀해주세요.",
                candidates = candidates
            )
            
            selectionManager.startSession(session)
            
            val currentCandidate = selectionManager.getCurrentCandidate()
            if (currentCandidate != null) {
                val prompt = promptBuilder.buildCandidateQuestion(currentCandidate)
                speakTts(prompt)
            }
        } else {
            speakTts("더 이상 새로운 항목이 없습니다.")
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
    }

    private fun extractScreenTree(): String {
        val rootNode = rootInActiveWindow ?: return "[]"
        val elements = mutableListOf<UiElement>()
        var candidateCounter = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) {
                val text = node.text?.toString() ?: ""
                val contentDescription = node.contentDescription?.toString() ?: ""
                val id = node.viewIdResourceName ?: "no_id"
                val className = node.className?.toString() ?: "no_class"

                if (text.isNotBlank() || contentDescription.isNotBlank() || node.isClickable) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
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
            for (i in 0 until node.childCount) traverse(node.getChild(i))
        }
        traverse(rootNode)
        return Json.encodeToString(elements)
    }

    private fun showTargetHighlight(bounds: RectDto, message: String) {
        removeTargetHighlight()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 빨간색 박스(테두리)만 표시하도록 변경
        val highlight = android.view.View(this).apply {
            val strokeWidth = (4 * resources.displayMetrics.density).toInt()
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(strokeWidth, Color.RED)
                setColor(Color.TRANSPARENT)
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
        highlightView = highlight
        windowManager.addView(highlightView, params)

        // 예외 처리 매니저에 하이라이트 좌표 전달 (깜빡임 효과에 재사용)
        errorHandlingManager.setHighlightBounds(
            android.graphics.Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        )
        // 하이라이트 표시 = 가이드 안내 완료 → 타이머 시작
        errorHandlingManager.onUserActionDetected()

        highlightJob = CoroutineScope(Dispatchers.Main).launch {
            delay(5000)
            removeTargetHighlight()
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
        return command
            .replace("택시 타고", "")
            .replace("택시 불러줘", "")
            .replace("길찾기", "")
            .replace("검색해줘", "")
            .replace("가줘", "")
            .replace("갈래", "")
            .replace("가자", "")
            .trim()
    }
}
