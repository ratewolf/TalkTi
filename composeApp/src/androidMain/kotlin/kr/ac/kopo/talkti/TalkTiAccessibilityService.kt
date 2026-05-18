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
import kr.ac.kopo.talkti.models.ScreenStateRequest
import kr.ac.kopo.talkti.models.GuideActionResponse
import kr.ac.kopo.talkti.models.RectDto
import kr.ac.kopo.talkti.models.UiElement
import io.ktor.serialization.kotlinx.json.*

import kr.ac.kopo.talkti.app.overlay.FloatingMenuManager

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

    private var floatingMenuManager: FloatingMenuManager? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private var highlightView: android.view.View? = null
    private var highlightJob: Job? = null

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
        
        val sharedPref = getSharedPreferences("talkti_prefs", Context.MODE_PRIVATE)
        val isEnabled = sharedPref.getBoolean("overlay_enabled", true)
        if (isEnabled) {
            floatingMenuManager?.show()
        }
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
                        if (!processLocalCommand(userCommand)) {
                            captureScreenForLLM(userCommand)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    private fun openAppByName(appName: String): Boolean {
        val cleanCmd = appName.replace(" ", "").lowercase()
        val pm = packageManager

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
            //유튜브
            "유튜브" to listOf("com.google.android.youtube"),
            "유튭" to listOf("com.google.android.youtube"),
            //설정
            "돋보기" to listOf("com.samsung.android.app.magnifier"),
            "크게보기" to listOf("com.samsung.android.app.magnifier"),
            "만보기" to listOf("com.sec.android.app.shealth"),
            "걷기" to listOf("com.sec.android.app.shealth"),
            "설정" to listOf("com.android.settings"),
            "톱니바퀴" to listOf("com.android.settings")
        )

        //별칭 검색 및 실행 로직 (리스트 순회 적용)
        for ((alias, packageList) in aliasMap) {
            if (cleanCmd.contains(alias)) {
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

            // 2글자 이상 매칭 시 실행
            if (cleanLabel.length >= 2 && cleanCmd.contains(cleanLabel)) {
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
        val isAppOpenCmd =
            cleanCmd.contains("열어줘") || cleanCmd.contains("켜줘") || cleanCmd.contains("실행해") || cleanCmd.contains(
                "실행"
            )

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

    private fun sendDataToServer(command: String, base64Image: String, uiTree: String, screenSessionId: String) {
        val sharedPref = getSharedPreferences("talkti_prefs", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("server_url", "http://10.0.2.2:8080") ?: "http://10.0.2.2:8080"
        val serverUrl = if (baseUrl.endsWith("/")) "${baseUrl}analyze" else "$baseUrl/analyze"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response: GuideActionResponse = client.post(serverUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(ScreenStateRequest(
                        userVoiceCommand = command,
                        uiTreeJson = uiTree,
                        screenshotBase64 = base64Image,
                        screenSessionId = screenSessionId
                    ))
                }.body()

                withContext(Dispatchers.Main) {
                    speakTts(response.ttsMessage)
                    if (response.actionType == "OPEN_APP") {
                        println("OPEN_APP 명령 들어옴")
                        response.targetCandidateId?.let { appName ->
                            println("OPEN 할 앱은 " + appName + "입니다.")
                            openAppByName(appName)
                        }
                    } else if (isValidGuideResponse(response, screenSessionId)) {
                        println("클릭 명령이 아닌 ttsMessage 반환")
                        response.targetBounds?.let { showTargetHighlight(it, response.ttsMessage) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TalkTiAccessibilityService, "서버 연결 실패", Toast.LENGTH_SHORT).show()
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
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
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
}
