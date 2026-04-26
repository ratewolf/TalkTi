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
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kr.ac.kopo.talkti.models.RectDto

@Serializable
data class ScreenStateRequest(
    val userVoiceCommand: String,
    val uiTreeJson: String,
    val screenshotBase64: String
)

@Serializable
data class GuideActionResponse(
    val actionType: String,
    val targetBounds: RectDto?,
    val ttsMessage: String
)

class TalkTiAccessibilityService : AccessibilityService() {

    private val TAG = "TalkTiService"

    // 오버레이 뷰를 관리할 변수
    private var windowManager: WindowManager? = null
    private var floatingButton: Button? = null

    // STT (음성 인식) 객체 추가
    private var speechRecognizer: SpeechRecognizer? = null

    private var highlightView: android.view.View? = null

    // 서비스가 시스템에 성공적으로 연결되었을 때 호출됨 (오버레이 생성의 최적 타이밍)
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "접근성 서비스 연결됨 - 플로팅 버튼 생성 시작")
        initSpeechRecognizer()
        createFloatingButton()
    }

    // 1. 음성 인식기 초기화 함수
    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    floatingButton?.text = "듣는 중..."
                    floatingButton?.setBackgroundColor(Color.parseColor("#34A853"))
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
                    resetButtonUI()
                }

                override fun onError(error: Int) {
                    Log.e(TAG, "음성 인식 에러 코드: $error")
                    Toast.makeText(this@TalkTiAccessibilityService, "음성 인식 실패", Toast.LENGTH_SHORT).show()
                    resetButtonUI()
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

    private fun resetButtonUI() {
        floatingButton?.text = "TalkTi\n말하기"
        floatingButton?.setBackgroundColor(Color.parseColor("#FEE500"))
    }

    private fun createFloatingButton() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. 버튼 UI 생성 (별도 XML 없이 코드로 뷰 생성)
        floatingButton = Button(this).apply {
            text = "TalkTi\n말하기"
            setBackgroundColor(Color.parseColor("#FEE500"))
            setTextColor(Color.BLACK)
            textSize = 18f
            minHeight = 120
            minWidth = 180
            isAllCaps = false
            elevation = 12f

            setOnClickListener {
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
        }

        // 2. 오버레이 레이아웃 속성 설정
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // 중요: 접근성 서비스 전용 오버레이 타입 (설정에서 다른 앱 위에 그리기 권한 불필요)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 버튼 밖의 영역은 터치가 통과하도록 설정
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END // 우측 상단 배치
            x = 50 // 우측 여백
            y = 300 // 상단 여백
        }

        // 3. 화면에 버튼 추가
        windowManager?.addView(floatingButton, params)
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
                            sendDataToServer(userCommand, base64Image, realUiTree)
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

    private fun sendDataToServer(command: String, base64Image: String, uiTree: String) {
        val serverUrl = "http://192.168.0.6:8080/analyze"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response: GuideActionResponse = client.post(serverUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(ScreenStateRequest(
                        userVoiceCommand = command,
                        uiTreeJson = uiTree,
                        screenshotBase64 = base64Image
                    ))
                }.body()

                // 서버 응답 수신 성공!
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "서버 응답 성공: ${response.ttsMessage}")

                    Toast.makeText(
                        this@TalkTiAccessibilityService,
                        response.ttsMessage,
                        Toast.LENGTH_LONG
                    ).show()

                    response.targetBounds?.let { bounds ->
                        showTargetHighlight(bounds, response.ttsMessage)
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
        removeTargetHighlight()
        if (floatingButton != null) {
            windowManager?.removeView(floatingButton)
        }
    }

    // ⭐️ 1. 추출한 UI 요소를 담을 내부 데이터 모델
    @Serializable
    data class UiElement(
        val text: String,
        val id: String,
        val className: String,
        val bounds: RectDto
    )

    // ⭐️ 2. 현재 화면의 노드들을 재귀적으로 탐색하여 JSON 문자열로 반환하는 함수
    private fun extractScreenTree(): String {
        val rootNode = rootInActiveWindow ?: return "[]"
        val elements = mutableListOf<UiElement>()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return

            // 화면에 실제로 보이는 노드만 처리
            if (node.isVisibleToUser) {
                val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                val id = node.viewIdResourceName ?: "no_id"
                val className = node.className?.toString() ?: "no_class"

                // 텍스트가 있거나 클릭 가능한 '의미 있는' 노드만 리스트에 추가
                if (text.isNotBlank() || node.isClickable) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)

                    elements.add(
                        UiElement(
                            text = text,
                            id = id,
                            className = className,
                            // shared 모듈에서 만든 RectDto 재사용
                            bounds = RectDto(rect.left, rect.top, rect.right, rect.bottom)
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
    }

    private fun removeTargetHighlight() {
        highlightView?.let {
            windowManager?.removeView(it)
            highlightView = null
        }
    }
}