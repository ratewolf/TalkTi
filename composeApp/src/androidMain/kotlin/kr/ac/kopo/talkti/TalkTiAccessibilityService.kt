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
import android.view.inputmethod.InputMethodManager
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.ac.kopo.talkti.models.ScreenStateRequest
import kr.ac.kopo.talkti.models.GuideActionResponse
import kr.ac.kopo.talkti.models.RectDto
import kr.ac.kopo.talkti.models.UiElement
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.plugins.*

import kr.ac.kopo.talkti.app.overlay.FloatingMenuManager

class TalkTiAccessibilityService : AccessibilityService() {

    private val tag = "TalkTiService"

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
            requestTimeoutMillis = 120000 
            connectTimeoutMillis = 15000  
            socketTimeoutMillis = 120000  
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "접근성 서비스 연결됨")
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun showTextInputDialog() {
        val editText = EditText(this).apply {
            hint = "예: 카카오톡 보내줘"
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("명령 입력")
            .setView(editText)
            .setPositiveButton("확인") { _, _ ->
                val command = editText.text.toString()
                if (command.isNotBlank()) {
                    // 1. 키보드 숨기기
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(editText.windowToken, 0)
                    
                    // 2. 다이얼로그와 키보드가 사라질 시간을 벌어줌 (0.6초 대기)
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(600) 
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
                override fun onReadyForSpeech(params: Bundle?) { updateButtonStatus(true) }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        // 음성 인식 결과창이 닫히는 시간을 고려해 약간의 지연 추가
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(300)
                            captureScreenForLLM(matches[0])
                        }
                    }
                    updateButtonStatus(false)
                }
                override fun onError(error: Int) { updateButtonStatus(false) }
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
            if (status == TextToSpeech.SUCCESS) textToSpeech?.language = java.util.Locale.KOREAN
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

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
                override fun onFailure(errorCode: Int) {
                    Log.e(tag, "화면 캡처 실패: $errorCode")
                }
            })
        }
    }

    private fun sendDataToServer(command: String, base64Image: String, uiTree: String, screenSessionId: String) {
        val sharedPref = getSharedPreferences("talkti_prefs", MODE_PRIVATE)
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

                Log.d(tag, "서버 응답 수신: ${response.ttsMessage}")

                withContext(Dispatchers.Main) {
                    speakTts(response.ttsMessage)
                    response.targetBounds?.let { 
                        showTargetHighlight(it, response.ttsMessage) 
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "서버 전송 실패", e)
            }
        }
    }

    private fun speakTts(message: String) {
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "talkti_tts")
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
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
                if (text.isNotBlank() || contentDescription.isNotBlank() || node.isClickable) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    elements.add(UiElement(
                        candidateId = "candidate_${candidateCounter++}",
                        text = text,
                        contentDescription = contentDescription,
                        id = node.viewIdResourceName ?: "no_id",
                        className = node.className?.toString() ?: "",
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
        
        if (bounds.left >= bounds.right || bounds.top >= bounds.bottom) return

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val highlight = android.widget.TextView(this).apply {
            text = "▼\n$message"
            setTextColor(Color.BLACK)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#EEFEE500"))
            setPadding(20, 10, 20, 10)
            gravity = Gravity.CENTER
        }

        val params = WindowManager.LayoutParams(
            (bounds.right - bounds.left).coerceAtLeast(200),
            (bounds.bottom - bounds.top).coerceAtLeast(120),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }

        try {
            highlightView = highlight
            windowManager.addView(highlightView, params)
            highlightJob = CoroutineScope(Dispatchers.Main).launch {
                delay(8000)
                removeTargetHighlight()
            }
        } catch (e: Exception) {
            Log.e(tag, "하이라이트 뷰 추가 에러", e)
        }
    }

    private fun removeTargetHighlight() {
        highlightJob?.cancel()
        highlightView?.let {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { }
            highlightView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
        removeTargetHighlight()
        floatingMenuManager?.hide()
    }
}
