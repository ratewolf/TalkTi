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
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.ac.kopo.talkti.models.ScreenStateRequest
import kr.ac.kopo.talkti.models.GuideActionResponse
import kr.ac.kopo.talkti.models.RectDto

import kr.ac.kopo.talkti.app.overlay.FloatingMenuManager

class TalkTiAccessibilityService : AccessibilityService() {

    private val TAG = "TalkTiService"

    // 오버레이 뷰를 관리할 변수
    private var floatingMenuManager: FloatingMenuManager? = null

    // STT (음성 인식) 객체 추가
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private var highlightView: android.view.View? = null
    private var highlightJob: Job? = null

    // 서비스가 시스템에 성공적으로 연결되었을 때 호출됨 (오버레이 생성의 최적 타이밍)
    override fun onServiceConnected() {
        super.onServiceConnected()
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
        floatingMenuManager?.show()
    }

    private fun startAppGuide() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
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
                    captureScreenForLLM(command)
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

    // 1. 음성 인식기 초기화 함수
    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    updateButtonStatus(true)
                    Toast.makeText(
                        this@TalkTiAccessibilityService,
                        "천천히 말씀해 주세요.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onResults(results: Bundle?) {
                    // 음성 인식 성공 시
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val userCommand = matches[0]
                        Log.d(TAG, "사용자 음성 명령: $userCommand")
                        Toast.makeText(this@TalkTiAccessibilityService, "명령: $userCommand", Toast.LENGTH_SHORT).show()

                        // ⭐️ 음성 명령을 인자로 넘기며 화면 캡처 시작!
                        captureScreenForLLM(userCommand)
                    }
                    updateButtonStatus(false)
                }

                override fun onError(error: Int) {
                    Log.e(TAG, "음성 인식 에러 코드: $error")
                    Toast.makeText(this@TalkTiAccessibilityService, "음성 인식 실패", Toast.LENGTH_SHORT).show()
                    updateButtonStatus(false)
                }

                // 사용하지 않는 콜백들은 비워둡니다
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
                val result = textToSpeech?.setLanguage(java.util.Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS 한국어 설정 실패")
                }
            } else {
                Log.e(TAG, "TTS 초기화 실패")
                textToSpeech = null
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 화면이 바뀌거나 내용이 변경될 때마다 호출됩니다.
        val rootNode = rootInActiveWindow ?: return

        Log.d(TAG, "새로운 화면 감지: ${event.packageName}")

        // 화면 트리 순회 시작
        traverseNodes(rootNode)
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?) {
        if (node == null) return

        // 텍스트가 있는 요소라면 로그에 출력 (버튼, 텍스트뷰 등)
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank()) {
            val viewId = node.viewIdResourceName ?: "no_id"
            Log.d(TAG, "발견된 UI 요소: [$viewId] -> $text")
        }

        // 자식 노드들을 재귀적으로 탐색
        for (i in 0 until node.childCount) {
            traverseNodes(node.getChild(i))
        }
    }

    override fun onInterrupt() {
        Log.e(TAG, "서비스가 중단되었습니다.")
    }

    // 화면 캡처를 실행하는 함수
    fun captureScreenForLLM(userCommand: String) {
        val screenSessionId = "screen_${System.currentTimeMillis()}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                @RequiresApi(Build.VERSION_CODES.R)
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                        val realUiTree = extractScreenTree()

                        if (bitmap != null) {
                            val base64Image = bitmapToBase64(bitmap)
                            Log.d(TAG, "최종 데이터 수집 완료! 명령: [$userCommand], 이미지 길이: ${base64Image.length}")
                            sendDataToServer(userCommand, base64Image, realUiTree, screenSessionId)
                        }
                        hardwareBuffer.close()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "스크린샷 캡처 실패, 에러 코드: $errorCode")
                    }
                }
            )
        } else {
            Log.e(TAG, "이 스크린샷 방식은 안드로이드 11(API 30) 이상에서만 지원됩니다.")
        }
    }

    private fun sendDataToServer(command: String, base64Image: String, uiTree: String, screenSessionId: String) {
        val serverUrl = "http://10.132.109.52:8080/analyze"

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

                // 서버 응답 수신 성공!
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "서버 응답 성공: ${response.ttsMessage}")

                    speakTts(response.ttsMessage)

                    if (isValidGuideResponse(response, screenSessionId)) {
                        response.targetBounds?.let { bounds ->
                            showTargetHighlight(bounds, response.ttsMessage)
                        }
                    } else {
                        Toast.makeText(this@TalkTiAccessibilityService, "화면을 다시 분석해 주세요.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "통신 오류: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TalkTiAccessibilityService, "서버 연결 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    private fun speakTts(message: String) {
        try {
            textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "talkti_tts")
        } catch (e: Exception) {
            Log.e(TAG, "TTS 재생 실패: ${e.message}")
        }
    }

    private fun isValidGuideResponse(response: GuideActionResponse, requestSessionId: String): Boolean {
        if (response.actionType == "CLICK" && response.targetBounds == null) return false

        if (response.screenSessionId != null && response.screenSessionId != requestSessionId) return false

        if (response.confidence != null && response.confidence!! < 0.3) return false

        response.targetBounds?.let { bounds ->
            val screenRect = Rect()
            rootInActiveWindow?.getBoundsInScreen(screenRect)
            if (screenRect.width() <= 0 || screenRect.height() <= 0) return false

            val outOfScreen = bounds.left < 0 || bounds.top < 0 ||
                bounds.right > screenRect.right || bounds.bottom > screenRect.bottom ||
                bounds.left >= bounds.right || bounds.top >= bounds.bottom

            if (outOfScreen) return false
        }

        return true
    }

    // Bitmap을 Base64 문자열로 압축 및 변환하는 헬퍼 함수
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 용량 최적화를 위해 JPEG 포맷 사용 및 품질 80%로 압축 (필요시 조절)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        // NO_WRAP: 줄바꿈 없이 한 줄의 긴 문자열로 생성 (서버에서 파싱하기 좋음)
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        removeTargetHighlight()
        floatingMenuManager?.hide()
    }

    // ⭐️ 1. 추출한 UI 요소를 담을 내부 데이터 모델
    @Serializable
    data class UiElement(
        val candidateId: String,
        val text: String,
        val contentDescription: String,
        val id: String,
        val className: String,
        val bounds: RectDto,
        val clickable: Boolean,
        val enabled: Boolean,
        val visibleToUser: Boolean
    )

    // ⭐️ 2. 현재 화면의 노드들을 재귀적으로 탐색하여 JSON 문자열로 반환하는 함수
    private fun extractScreenTree(): String {
        val rootNode = rootInActiveWindow ?: return "[]"
        val elements = mutableListOf<UiElement>()
        var candidateCounter = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return

            // 화면에 실제로 보이는 노드만 처리
            if (node.isVisibleToUser) {
                val text = node.text?.toString() ?: ""
                val contentDescription = node.contentDescription?.toString() ?: ""
                val id = node.viewIdResourceName ?: "no_id"
                val className = node.className?.toString() ?: "no_class"

                // 텍스트가 있거나 클릭 가능한 '의미 있는' 노드만 리스트에 추가
                if (text.isNotBlank() || contentDescription.isNotBlank() || node.isClickable) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)

                    elements.add(
                        UiElement(
                            candidateId = "candidate_${candidateCounter++}",
                            text = text,
                            contentDescription = contentDescription,
                            id = id,
                            className = className,
                            bounds = RectDto(rect.left, rect.top, rect.right, rect.bottom),
                            clickable = node.isClickable,
                            enabled = node.isEnabled,
                            visibleToUser = node.isVisibleToUser
                        )
                    )
                }
            }

            // 자식 노드 순회
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(rootNode)

        // 수집된 List를 JSON 문자열로 변환 (예: "[{text: '택시호출', ...}, {...}]")
        return Json.encodeToString(elements)
    }

    private fun showTargetHighlight(bounds: RectDto, message: String) {
        removeTargetHighlight()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val highlight = android.widget.TextView(this).apply {
            text = message
            setTextColor(Color.BLACK)
            textSize = 18f
            setBackgroundColor(Color.parseColor("#CCFEE500"))
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER
        }

        val width = (bounds.right - bounds.left).coerceAtLeast(160)
        val height = (bounds.bottom - bounds.top).coerceAtLeast(100)

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }

        highlightView = highlight
        windowManager?.addView(highlightView, params)

        highlightJob?.cancel()
        highlightJob = CoroutineScope(Dispatchers.Main).launch {
            delay(5000)
            removeTargetHighlight()
        }
    }

    private fun removeTargetHighlight() {
        highlightJob?.cancel()
        highlightJob = null

        highlightView?.let {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeView(it)
            highlightView = null
        }
    }
}
